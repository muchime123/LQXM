package com.liqiang.archive.service;

import com.liqiang.archive.erp.ErpActor;
import com.liqiang.archive.erp.ErpActorResolver;
import com.liqiang.archive.erp.ErpChangeLogWriter;
import com.liqiang.archive.erp.ErpIdempotencyService;
import com.liqiang.archive.security.CurrentUserService;
import com.liqiang.archive.security.FeatureCode;
import com.liqiang.archive.security.DataScopeSupport;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.sql.ResultSetMetaData;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 生产任务与车间交付收货。
 *
 * <p>M0 契约接入（与 {@code ManufacturingExecutionService} 同款）：本类的三个写动作
 * —— 建单 {@link #create}、车间交付收货 {@link #complete}、收货冲销 {@link #reverseReceipt}
 * —— 全部经「幂等模板 + 行级审计」，操作人与请求键缺失即拒绝。
 * 幂等表与审计表走主库绑定（{@code archiveIdempotencyService} / {@code archiveAuditWriter}），
 * 与业务写入同连接同事务；选错绑定不报错，只是保护静默失效（见 {@code ErpContractConfig}）。
 */
@Service
public class ManufacturingService {

    private static final String ACTION_ORDER_CREATE = "manufacturing.order.create";
    private static final String ACTION_COMPLETION_RECEIPT = "manufacturing.completionReceipt.post";
    private static final String ACTION_COMPLETION_RECEIPT_REVERSE = "manufacturing.completionReceipt.reverse";

    private final JdbcTemplate jdbc;
    private final ProductInventoryService productInventory;
    private final CurrentUserService users;
    private final DataScopeSupport scope;
    private final ManufacturingExecutionService execution;
    private final ManufacturingTaskTemplateResolver taskTemplateResolver;
    private final ErpIdempotencyService idem;
    private final ErpChangeLogWriter changeLog;
    private final ErpActorResolver actors;

    public ManufacturingService(JdbcTemplate jdbc,
                                ProductInventoryService productInventory,
                                CurrentUserService users,
                                DataScopeSupport scope,
                                ManufacturingExecutionService execution,
                                ManufacturingTaskTemplateResolver taskTemplateResolver,
                                @Qualifier("archiveIdempotencyService") ErpIdempotencyService idem,
                                @Qualifier("archiveAuditWriter") ErpChangeLogWriter changeLog,
                                ErpActorResolver actors) {
        this.jdbc = jdbc;
        this.productInventory = productInventory;
        this.users = users;
        this.scope = scope;
        this.execution = execution;
        this.taskTemplateResolver = taskTemplateResolver;
        this.idem = idem;
        this.changeLog = changeLog;
        this.actors = actors;
    }

    /**
     * 建单入参（P3 ①：产品身份必须是 SKU，不再是自由文本）。
     *
     * <p><b>为什么同时要 {@code processCategory} 和 {@code salesProductId}（实测得出的约束）</b>：
     * 制程解析（{@link ManufacturingTaskTemplateResolver}）按
     * {@code manufacturing_task_config_items.config_name}（{@code config_type='PRODUCT_NAME'}，
     * 共 49 项）**精确匹配产品名**，而这些配置值全是**品类级**（"手动片膜" / "模具" /
     * "LQ260气调机卷膜"…）——与 SKU 级产品名**只命中 1 个**。
     * 反向也不成立：拿 SKU 名去反推品类，实测 4,916 个 SKU 里 **74.5% 零命中、13% 命中多个**。
     * → 「制程品类」与「产出 SKU」是**两个独立维度**（一个品类对应多个 SKU），必须都显式给，
     *   不存在自动推导。{@code processCategory} 走既有的 49 项配置（仍是下拉选择，不是自由文本），
     *   {@code salesProductId} 提供真正的商品身份。
     *
     * <p>{@code salesProductId} + {@code plannedQuantity} 即**主产品**，会自动成为工单产出清单的
     * 第 1 行（{@code is_primary=1}）。单位不再由前端传 —— 一律取 SKU 主数据上的单位，
     * 避免「同一件货在工单与库存里单位不一致」这种最难查的分叉。
     *
     * <p>{@code outputs} 是**附加产出**（配套件 / 模具），可为空：实测 **86.0% 的工单只产 1 个 SKU**，
     * 强制每次填一堆模具行只会让人绕过它。多 SKU 的 14% 才需要走这里。
     */
    public record CreateOrder(String salesOrderRef,Long customerId,String customerName,
                              String processCategory,Long salesProductId,
                              String specification,String customRequirement,
                              BigDecimal plannedQuantity,LocalDate dueDate,String remark,
                              String taskType,Long processTemplateId,Long workshopId,
                              List<OutputLine> outputs,Map<String,Object> productConfig) {}
    /** 附加产出项（主产品不走这里；它由建单头部的 {@code salesProductId} + {@code plannedQuantity} 表达）。 */
    public record OutputLine(Long salesProductId,BigDecimal plannedQuantity,Boolean isMold,String remark) {}
    /** 收货明细项（P3 ②：按产出清单逐项确认实际数量，不再手打名称）。 */
    public record ReceiptLine(Long outputId,BigDecimal quantity,BigDecimal unitCost,String remark) {}
    /**
     * 收货入参（P3 ②：收货单 头 → 主子）。
     *
     * <p>{@code items} 必须非空且逐项指向本工单的产出清单行；头部数量与成品名由明细汇总回填，
     * 因此不再接受自由文本成品名 —— 这正是「微信传名」在 ERP 里的那一段。
     */
    public record CompleteOrder(Long warehouseId,LocalDateTime completedAt,List<ReceiptLine> items,String remark) {}

    public List<Map<String,Object>> warehouses() {
        return jdbc.queryForList("SELECT id,warehouse_code warehouseCode,warehouse_name warehouseName FROM warehouses WHERE active=1 ORDER BY id");
    }

    /**
     * 建单弹窗的车间下拉选项（读权限即可访问）。
     *
     * <p>不放在 {@code ManufacturingTaskConfigController}（/master/workshops）——那个控制器
     * 类级要求 {@code manufacturing.template.manage}，报工/收货用户没有；建单用户只需要
     * 「看到有哪些启用中的车间」来让后端解析默认制程。
     */
    public List<Map<String,Object>> workshopOptions() {
        return jdbc.queryForList(
            "SELECT id,workshop_name workshopName,default_process_template_id defaultProcessTemplateId "
          + "FROM manufacturing_workshops WHERE enabled=1 ORDER BY id");
    }

    /**
     * 工单产出清单（P3 ①）。
     *
     * <p>建单写入、收货读取、详情页展示共用同一条读法 —— 「清单」是全链路的唯一权威，
     * 不允许任何环节另写一份口径（否则又会出现"两处取值"的静默分叉）。
     */
    public List<Map<String,Object>> outputs(long orderId) {
        return jdbc.query("SELECT * FROM manufacturing_order_outputs WHERE manufacturing_order_id=? ORDER BY line_no",
                (r,n)->row(r),orderId);
    }

    /**
     * 建单/产出清单的产品选择器（P3 ①：产品身份必须从主数据里选）。
     *
     * <p>标准 SKU（{@code external_id} 非空 = 氚云台账来源）排前面，自建 / 非标件（{@code MFG-}）
     * 排后面并带 {@code custom=1} 标识 —— 让"选了个非标件"这件事在界面上可见，
     * 而不是混在一堆产品里无从分辨。
     */
    public List<Map<String,Object>> productOptions(String keyword,int limit) {
        StringBuilder where=new StringBuilder(" WHERE sp.deleted_at IS NULL");List<Object> p=new ArrayList<>();
        if(keyword!=null&&!keyword.isBlank()){
            where.append(" AND (sp.product_code LIKE ? OR sp.product_name LIKE ? OR COALESCE(sp.specification,'') LIKE ?)");
            String k="%"+keyword.trim()+"%";p.add(k);p.add(k);p.add(k);
        }
        p.add(Math.max(1,Math.min(limit,200)));
        return jdbc.queryForList(
                "SELECT sp.id,sp.product_code productCode,sp.product_name productName,sp.specification,sp.unit,"
              + " CASE WHEN sp.external_id IS NULL THEN 1 ELSE 0 END custom"
              + " FROM sales_products sp"+where
              + " ORDER BY (sp.external_id IS NULL), sp.product_name, COALESCE(sp.specification,'') LIMIT ?",p.toArray());
    }

    /**
     * 该车间可选的「制程品类」。
     *
     * <p>取值就是 {@code manufacturing_task_config_items} 里 {@code config_type='PRODUCT_NAME'}
     * 的行 —— 与 {@link ManufacturingTaskTemplateResolver} 精确匹配的**同一来源**。
     * 让建单只能从这里选，是为了把原来的自由文本产品名收成"有限枚举"，
     * 同时保证制程解析一定能命中（两者读同一张表，不存在两处口径）。
     */
    public List<String> processCategories(Long workshopId) {
        if(workshopId==null) return List.of();
        return jdbc.queryForList("SELECT DISTINCT config_name FROM manufacturing_task_config_items"
                + " WHERE workshop_id=? AND config_type='PRODUCT_NAME' AND active=1 ORDER BY config_name",
                String.class,workshopId);
    }

    /**
     * 生产任务单下单的联动选项（复刻氚云《生产任务单》的「先选产线，再选产品名称与可选配置」）。
     *
     * <p>两者都读 {@code manufacturing_task_config_items}（制程与工序管理里的「任务单产品与配置」），
     * 与制程解析同源 —— 选得到的必然解析得出，不存在两处口径。
     */
    public Map<String,Object> taskConfigOptions(Long workshopId) {
        var m=new LinkedHashMap<String,Object>();
        if(workshopId==null){ m.put("productNames",List.of()); m.put("optionalConfigs",List.of()); return m; }
        m.put("productNames",jdbc.queryForList("SELECT config_name FROM manufacturing_task_config_items"
                +" WHERE workshop_id=? AND config_type='PRODUCT_NAME' AND active=1 ORDER BY config_name",
                String.class,workshopId));
        m.put("optionalConfigs",jdbc.queryForList("SELECT config_name FROM manufacturing_task_config_items"
                +" WHERE workshop_id=? AND config_type='OPTIONAL_CONFIG' AND active=1 ORDER BY config_name",
                String.class,workshopId));
        return m;
    }

    public Map<String,Object> list(String keyword,String status,String priority,String scheduled,String team,
                                   Long responsible,String workshop,String overdue,String orderBy,int page,int size) {
        if(page<1 || size<1 || size>100) throw new IllegalArgumentException("分页范围无效");
        var where=new StringBuilder(" WHERE 1=1");var params=new ArrayList<>();
        scope.appendByEmployeeId(where,params,"m.salesperson_id","e.department", FeatureCode.MANUFACTURING);
        if(keyword!=null && !keyword.isBlank()) {
            where.append(" AND (m.order_no LIKE ? OR m.sales_order_ref LIKE ? OR m.customer_name_snapshot LIKE ? OR m.product_name_snapshot LIKE ?)");
            String k="%"+keyword.trim()+"%";for(int i=0;i<4;i++)params.add(k);
        }
        if(status!=null && !status.isBlank()){where.append(" AND m.status=?");params.add(status);}
        if(priority!=null && !priority.isBlank()){where.append(" AND m.priority=?");params.add(priority);}
        if("UNSCHEDULED".equalsIgnoreCase(scheduled)){where.append(" AND m.planned_start_date IS NULL AND m.planned_finish_date IS NULL");}
        else if("SCHEDULED".equalsIgnoreCase(scheduled)){where.append(" AND (m.planned_start_date IS NOT NULL OR m.planned_finish_date IS NOT NULL)");}
        if(team!=null && !team.isBlank()){where.append(" AND m.production_team_name LIKE ?");params.add("%"+team.trim()+"%");}
        if(responsible!=null){where.append(" AND m.responsible_employee_id=?");params.add(responsible);}
        if(workshop!=null && !workshop.isBlank()){where.append(" AND m.workshop_type=?");params.add(workshop.trim());}
        if("Y".equalsIgnoreCase(overdue)){where.append(" AND m.planned_finish_date IS NOT NULL AND m.planned_finish_date<CURDATE() AND m.status NOT IN ('COMPLETED','CLOSED','CANCELLED')");}
        Long total=jdbc.queryForObject("SELECT COUNT(*) FROM manufacturing_orders m LEFT JOIN employees e ON e.id=m.salesperson_id"+where,Long.class,params.toArray());
        params.add(size);params.add((page-1)*size);
        String orderClause="ORDER BY m.id DESC";
        if("due".equalsIgnoreCase(orderBy))orderClause="ORDER BY (m.due_date IS NULL) ASC, m.due_date ASC, m.id ASC";
        else if("priority".equalsIgnoreCase(orderBy))orderClause="ORDER BY FIELD(m.priority,'URGENT','HIGH','NORMAL','LOW'), m.id DESC";
        else if("schedule".equalsIgnoreCase(orderBy))orderClause="ORDER BY (m.planned_finish_date IS NULL) ASC, m.planned_finish_date ASC, m.id ASC";
        var items=jdbc.query("SELECT m.* FROM manufacturing_orders m LEFT JOIN employees e ON e.id=m.salesperson_id"+where+
                " "+orderClause+" LIMIT ? OFFSET ?",(r,n)->row(r),params.toArray());
        return Map.of("items",items,"total",total==null?0:total,"page",page,"size",size);
    }

    /** 排产看板摘要：待排产 / 已排产 / 生产中 / 逾期 + 班组负载（行级范围与任务列表一致）。 */
    public Map<String,Object> scheduleSummary() {
        var where=new StringBuilder(" WHERE 1=1");var params=new ArrayList<>();
        scope.appendByEmployeeId(where,params,"m.salesperson_id","e.department", FeatureCode.MANUFACTURING);
        String active=" AND m.status NOT IN ('COMPLETED','CLOSED','CANCELLED')";
        Long total=jdbc.queryForObject("SELECT COUNT(*) FROM manufacturing_orders m LEFT JOIN employees e ON e.id=m.salesperson_id"+where+active,Long.class,params.toArray());
        Long unscheduled=jdbc.queryForObject("SELECT COUNT(*) FROM manufacturing_orders m LEFT JOIN employees e ON e.id=m.salesperson_id"+where+active+" AND m.planned_start_date IS NULL AND m.planned_finish_date IS NULL",Long.class,params.toArray());
        Long scheduled=jdbc.queryForObject("SELECT COUNT(*) FROM manufacturing_orders m LEFT JOIN employees e ON e.id=m.salesperson_id"+where+active+" AND (m.planned_start_date IS NOT NULL OR m.planned_finish_date IS NOT NULL)",Long.class,params.toArray());
        Long inProduction=jdbc.queryForObject("SELECT COUNT(*) FROM manufacturing_orders m LEFT JOIN employees e ON e.id=m.salesperson_id"+where+" AND m.status='IN_PRODUCTION'",Long.class,params.toArray());
        Long overdue=jdbc.queryForObject("SELECT COUNT(*) FROM manufacturing_orders m LEFT JOIN employees e ON e.id=m.salesperson_id"+where+active+" AND m.planned_finish_date IS NOT NULL AND m.planned_finish_date<CURDATE()",Long.class,params.toArray());
        var teams=jdbc.query("SELECT COALESCE(NULLIF(m.production_team_name,''),'未分派') team,COUNT(*) task_count,"+
            " SUM(CASE WHEN m.planned_finish_date IS NOT NULL AND m.planned_finish_date<CURDATE() AND m.status NOT IN ('COMPLETED','CLOSED','CANCELLED') THEN 1 ELSE 0 END) overdue_count"+
            " FROM manufacturing_orders m LEFT JOIN employees e ON e.id=m.salesperson_id"+where+active+
            " GROUP BY team ORDER BY task_count DESC,team",(r,n)->row(r),params.toArray());
        return Map.of("total",total==null?0:total,"unscheduled",unscheduled==null?0:unscheduled,"scheduled",scheduled==null?0:scheduled,
                "inProduction",inProduction==null?0:inProduction,"overdue",overdue==null?0:overdue,"teams",teams);
    }
    public Map<String,Object> detail(long id) {
        requireVisible(id);
        var result=new LinkedHashMap<String,Object>();
        result.put("order",jdbc.query("SELECT * FROM manufacturing_orders WHERE id=?",(r,n)->row(r),id).getFirst());
        // 产出清单（P3 ①）：收货弹窗据此逐项填写实际数量，不再让用户打名称
        result.put("outputs",outputs(id));
        result.put("receipts",jdbc.query("""
            SELECT r.*,w.warehouse_name FROM manufacturing_completion_receipts r
            JOIN warehouses w ON w.id=r.warehouse_id WHERE r.manufacturing_order_id=? ORDER BY r.id DESC
            """,(r,n)->row(r),id));
        result.put("lots",jdbc.query("""
            SELECT l.*,w.warehouse_name FROM finished_goods_lots l JOIN warehouses w ON w.id=l.warehouse_id
            WHERE l.manufacturing_order_id=? ORDER BY l.id DESC
            """,(r,n)->row(r),id));
        result.put("operations",execution.operations(id));
        result.put("workReports",execution.reports(id));
        result.put("inspections",execution.inspections(id));
        result.put("issues",execution.issues(id));
        result.put("reworks",execution.reworks(id));
        return result;
    }
    /**
     * 建生产任务（M0 契约写动作）。
     *
     * <p>为什么建单也要带键：重复点击/网络重试会生成两张内容相同、单号不同的工单，
     * 后续报工与收货都会各做一遍，属于最难回滚的一类重复（单据已流转）。
     *
     * @param requestKey 请求键，必填；重放返回首次建单 id，不再新建
     */
    public long create(CreateOrder request,String requestKey) {
        ErpActor actor=actors.current();
        Map<String,Object> payload=ErpChangeLogWriter.changes(
                "salesOrderRef",clean(request.salesOrderRef()),"customerId",request.customerId(),
                "customerName",clean(request.customerName()),"processCategory",clean(request.processCategory()),
                "salesProductId",request.salesProductId(),
                "plannedQuantity",request.plannedQuantity(),"outputs",request.outputs(),
                "taskType",clean(request.taskType()),"workshopId",request.workshopId(),
                "processTemplateId",request.processTemplateId(),"dueDate",request.dueDate());
        return Long.parseLong(idem.execute(actor,requestKey,ACTION_ORDER_CREATE,payload,
                ()->String.valueOf(doCreate(request,actor,requestKey))));
    }

    private long doCreate(CreateOrder request,ErpActor actor,String requestKey) {
        String customer=required(request.customerName(),"客户名称",300);
        String product=required(request.processCategory(),"制程品类",500);
        var primary=requireProduct(request.salesProductId(),"生产产品");
        long primaryId=((Number)primary.get("id")).longValue();
        String unit=String.valueOf(primary.get("unit"));
        BigDecimal planned=request.plannedQuantity();
        if(planned==null||planned.signum()<=0||planned.scale()>6)
            throw new IllegalArgumentException("计划数量必须大于0且最多6位小数");
        String taskType=clean(request.taskType());
        // 手工任务类型：SAMPLE 打样 / REPLACEMENT 返工替换 / REWORK 返工 / INTERNAL 内部领用 / SPECIAL 特殊定制 / SALES_ORDER 销售排产（业务员缺货时下达）
        if(!Set.of("SAMPLE","REPLACEMENT","REWORK","INTERNAL","SPECIAL","SALES_ORDER").contains(taskType))
            throw new IllegalArgumentException("请选择有效的手工任务类型");
        var user=users.current();
        String temporary="PENDING-"+UUID.randomUUID();
        String productConfigJson=null;
        if(request.productConfig()!=null && !request.productConfig().isEmpty()){
            try{ productConfigJson=new com.fasterxml.jackson.databind.ObjectMapper()
                    .writeValueAsString(request.productConfig()); }
            catch(Exception e){ throw new IllegalArgumentException("产品配置参数无法序列化"); }
        }
        jdbc.update("""
            INSERT INTO manufacturing_orders(order_no,sales_order_ref,customer_id,customer_name_snapshot,
              salesperson_id,salesperson_name_snapshot,product_name_snapshot,sales_product_id,specification_snapshot,
              custom_requirement,planned_quantity,unit,due_date,remark,task_type,created_by_employee_id,created_by_name,
              product_config_json)
            VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """,temporary,clean(request.salesOrderRef()),request.customerId(),customer,user.employeeId(),user.name(),
                product,primaryId,clean(request.specification()),clean(request.customRequirement()),planned,unit,
                request.dueDate(),clean(request.remark()),taskType,user.employeeId(),user.name(),productConfigJson);
        long id=jdbc.queryForObject("SELECT id FROM manufacturing_orders WHERE order_no=?",Long.class,temporary);
        String no="MO"+String.format("%08d",id);jdbc.update("UPDATE manufacturing_orders SET order_no=? WHERE id=?",no,id);
        // 产出清单：第 1 行恒为主产品（P3 ①），附加产出按行追加。清单是后续收货的唯一入口。
        insertOutput(id,1,true,primary,planned,false,null);
        int lineNo=2;
        if(request.outputs()!=null){
            Set<Long> seen=new HashSet<>();seen.add(primaryId);
            for(var line:request.outputs()){
                var p=requireProduct(line.salesProductId(),"产出清单第"+lineNo+"行");
                long pid=((Number)p.get("id")).longValue();
                if(!seen.add(pid))
                    throw new IllegalArgumentException("产出清单第"+lineNo+"行的产品与前面的行重复，请合并为一行");
                BigDecimal q=line.plannedQuantity();
                if(q==null||q.signum()<=0||q.scale()>6)
                    throw new IllegalArgumentException("产出清单第"+lineNo+"行的计划数量必须大于0且最多6位小数");
                insertOutput(id,lineNo,false,p,q,Boolean.TRUE.equals(line.isMold()),clean(line.remark()));
                lineNo++;
            }
        }
        var selection = request.workshopId()==null ? null : taskTemplateResolver.resolve(request.workshopId(),product);
        Long templateId=selection!=null ? selection.templateId() : request.processTemplateId();
        if(templateId==null) throw new IllegalArgumentException("请选择生产车间以确定产品或车间默认制程");
        execution.doGenerateOperations(id,templateId,actor,requestKey);
        // 产线由下单选择决定，不能被制程模板上的历史车间标签覆盖。
        if(selection!=null) jdbc.update("UPDATE manufacturing_orders SET workshop_type=? WHERE id=?",selection.workshopName(),id);
        changeLog.write(ErpChangeLogWriter.of(actor,"product_archive.manufacturing_orders",id,"INSERT",
                ErpChangeLogWriter.changes(
                        "orderNo",no,"taskType",taskType,"customerNameSnapshot",customer,
                        "processCategory",product,"salesProductId",primaryId,
                        "productCode",primary.get("productCode"),"productName",primary.get("productName"),
                        "plannedQuantity",planned,"unit",unit,"outputLineCount",lineNo-1,
                        "workshopType",selection!=null?selection.workshopName():null,
                        "processTemplateId",templateId,"dueDate",request.dueDate()),
                null,"创建手工生产任务",requestKey,null));
        return id;
    }

    /**
     * 取产品身份（P3 ①：产品必须是主数据里的 SKU，不接受自由文本）。
     *
     * <p>只从 {@code sales_products} 取，且要求未软删。返回的 {@code canonicalProductId}
     * 一并带出，供产出清单冗余存储（库存聚合口径）。
     */
    private Map<String,Object> requireProduct(Long salesProductId,String label){
        if(salesProductId==null) throw new IllegalArgumentException(label+"必须选择产品");
        return jdbc.query("""
            SELECT id,product_code,product_name,specification,unit,COALESCE(canonical_product_id,id) canonical_product_id
            FROM sales_products WHERE id=? AND deleted_at IS NULL
            """,(r,n)->row(r),salesProductId)
            .stream().findFirst().orElseThrow(()->new IllegalArgumentException(label+"指向的产品不存在或已删除"));
    }

    /** 写一行工单产出清单。{@code is_primary=1} 由部分唯一索引保证每张工单至多一行。 */
    private void insertOutput(long orderId,int lineNo,boolean primary,Map<String,Object> product,
                              BigDecimal planned,boolean isMold,String remark){
        jdbc.update("""
            INSERT INTO manufacturing_order_outputs(manufacturing_order_id,line_no,is_primary,sales_product_id,
              canonical_product_id,product_code_snapshot,product_name_snapshot,specification_snapshot,unit,
              planned_quantity,is_mold,remark)
            VALUES(?,?,?,?,?,?,?,?,?,?,?,?)
            """,orderId,lineNo,primary?1:0,((Number)product.get("id")).longValue(),
                ((Number)product.get("canonicalProductId")).longValue(),
                product.get("productCode"),product.get("productName"),product.get("specification"),
                product.get("unit"),planned,isMold?1:0,remark);
    }


    /**
     * 车间交付收货（M0 契约写动作：幂等 + 行级审计）。
     *
     * <p>口径提醒（决策登记 #17）：这是**仓库收货方**的动作 —— 车间把成品交付到利强仓库，
     * 利强确认入库。不是「车间报完工」，也不是「向车间领料」。
     *
     * <p><b>P3 ②：收货按产出清单逐项确认</b>。一张工单一次收货可含多个产出（成品 + 配套模具），
     * 每项落一个成品批次与一笔库存流水。收益是**不再接受自由文本成品名** ——
     * 「车间完工后靠微信告诉业务员入库产品名」这一步在 ERP 里就此消失（决策 #28）。
     *
     * @param requestKey 请求键，必填；重放返回首次入库单 id，不重复入库（否则会多出成品批次与库存流水）
     */
    public long complete(long orderId,CompleteOrder request,String requestKey) {
        ErpActor actor=actors.current();
        Map<String,Object> payload=ErpChangeLogWriter.changes(
                "orderId",orderId,"warehouseId",request.warehouseId(),
                "completedAt",request.completedAt(),"items",request.items(),
                "remark",clean(request.remark()));
        return Long.parseLong(idem.execute(actor,requestKey,ACTION_COMPLETION_RECEIPT,payload,
                ()->String.valueOf(doComplete(orderId,request,actor,requestKey))));
    }

    private long doComplete(long orderId,CompleteOrder request,ErpActor actor,String requestKey) {
        requireVisible(orderId);
        var order=jdbc.query("SELECT * FROM manufacturing_orders WHERE id=? FOR UPDATE",(r,n)->row(r),orderId)
                .stream().findFirst().orElseThrow(()->new IllegalArgumentException("生产任务不存在"));
        if(Set.of("CANCELLED","CLOSED").contains(order.get("status"))) throw new IllegalStateException("当前生产任务不能入库");
        Integer operationCount=jdbc.queryForObject("SELECT COUNT(*) FROM manufacturing_order_operations WHERE manufacturing_order_id=?",Integer.class,orderId);
        if(operationCount!=null&&operationCount>0&&!Set.of("PENDING_RECEIPT","PARTIALLY_RECEIVED").contains(String.valueOf(order.get("status"))))
            throw new IllegalStateException("生产任务必须完成全部工序并检验通过后才能入库");
        if(request.warehouseId()==null || jdbc.queryForObject("SELECT COUNT(*) FROM warehouses WHERE id=? AND active=1",Integer.class,request.warehouseId())!=1)
            throw new IllegalArgumentException("请选择有效仓库");
        if(request.items()==null||request.items().isEmpty())
            throw new IllegalArgumentException("请至少填写一行收货明细：收货按工单产出清单逐项确认实际数量");
        var outputs=jdbc.query("SELECT * FROM manufacturing_order_outputs WHERE manufacturing_order_id=? ORDER BY line_no",(r,n)->row(r),orderId);
        if(outputs.isEmpty())
            throw new IllegalStateException("该生产任务没有产出清单，无法办理收货（P3 起收货必须按产出清单逐项确认，不再接受自由文本成品名）");
        Map<Long,Map<String,Object>> outputById=new LinkedHashMap<>();
        for(var o:outputs) outputById.put(((Number)o.get("id")).longValue(),o);

        // 逐项校验并归一成内部行结构（归属、数量、单位成本）
        var lines=new ArrayList<Map<String,Object>>();
        Set<Long> seen=new HashSet<>();
        int lineNo=1;
        for(var item:request.items()){
            if(item.outputId()==null) throw new IllegalArgumentException("收货明细第"+lineNo+"行缺少产出清单行");
            var output=outputById.get(item.outputId());
            if(output==null) throw new IllegalArgumentException("收货明细第"+lineNo+"行的产出不属于该生产任务");
            if(!seen.add(item.outputId())) throw new IllegalArgumentException("收货明细第"+lineNo+"行的产出重复");
            BigDecimal qty=item.quantity();
            if(qty==null||qty.signum()<=0||qty.scale()>6)
                throw new IllegalArgumentException("收货明细第"+lineNo+"行的数量必须大于0且最多6位小数");
            BigDecimal planned=(BigDecimal)output.get("plannedQuantity"),received=(BigDecimal)output.get("receivedQuantity");
            if(received.add(qty).compareTo(planned)>0)
                throw new IllegalArgumentException("「"+output.get("productNameSnapshot")+"」收货数量超过产出清单计划（已收 "
                        +received.stripTrailingZeros().toPlainString()+"，计划 "+planned.stripTrailingZeros().toPlainString()+"）");
            BigDecimal unitCost=item.unitCost()==null?BigDecimal.ZERO:item.unitCost();
            if(unitCost.signum()<0||unitCost.scale()>4) throw new IllegalArgumentException("实际单位成本必须≥0且最多4位小数");
            var line=new LinkedHashMap<String,Object>();
            line.put("lineNo",lineNo);line.put("output",output);line.put("quantity",qty);
            line.put("unitCost",unitCost);line.put("remark",clean(item.remark()));
            lines.add(line);lineNo++;
        }
        // 头部冗余值（P3 保留头部单值列以兼容既有查询/页面）：主产品优先，没有主产品行时退首个明细
        Map<String,Object> header=lines.stream().map(l->(Map<String,Object>)l.get("output"))
                .filter(o->flag(o.get("isPrimary"))).findFirst()
                .orElseGet(()->(Map<String,Object>)lines.getFirst().get("output"));
        BigDecimal headerQty=lines.stream().map(l->(BigDecimal)l.get("quantity")).reduce(BigDecimal.ZERO,BigDecimal::add);
        LocalDateTime at=request.completedAt()==null?LocalDateTime.now():request.completedAt();
        var user=users.current();
        String tmp="PENDING-"+UUID.randomUUID();
        jdbc.update("""
            INSERT INTO manufacturing_completion_receipts(receipt_no,manufacturing_order_id,warehouse_id,completed_at,
              quantity,unit,finished_goods_name,specification_snapshot,operator_employee_id,operator_name,remark)
            VALUES(?,?,?,?,?,?,?,?,?,?,?)
            """,tmp,orderId,request.warehouseId(),at,headerQty,String.valueOf(header.get("unit")),
                String.valueOf(header.get("productNameSnapshot")),(String)header.get("specificationSnapshot"),
                user.employeeId(),user.name(),clean(request.remark()));
        long receiptId=jdbc.queryForObject("SELECT id FROM manufacturing_completion_receipts WHERE receipt_no=?",Long.class,tmp);
        String receiptNo="RC"+String.format("%08d",receiptId);
        jdbc.update("UPDATE manufacturing_completion_receipts SET receipt_no=? WHERE id=?",receiptNo,receiptId);

        // 逐项：落批次 → 写成品流水 → 写统一库存流水 → 落收货明细 → 累加产出清单已收数量
        var lotNos=new ArrayList<String>();
        for(var line:lines){
            var output=(Map<String,Object>)line.get("output");
            long outputId=((Number)output.get("id")).longValue();
            long productId=((Number)output.get("salesProductId")).longValue();
            BigDecimal qty=(BigDecimal)line.get("quantity");
            BigDecimal unitCost=(BigDecimal)line.get("unitCost");
            String name=String.valueOf(output.get("productNameSnapshot"));
            String spec=(String)output.get("specificationSnapshot");
            String unit=String.valueOf(output.get("unit"));
            String remark=(String)line.get("remark");
            String lotTmp="PENDING-"+UUID.randomUUID();
            jdbc.update("""
                INSERT INTO finished_goods_lots(lot_no,manufacturing_order_id,completion_receipt_id,manufacturing_order_output_id,
                  warehouse_id,sales_order_ref,sales_product_id,product_model_id,product_machine_tier_id,
                  product_model_name_snapshot,machine_tier_snapshot,customer_name_snapshot,salesperson_id,salesperson_name_snapshot,
                  finished_goods_name,specification_snapshot,customization_mode,tooling_requirement,unit,
                  received_quantity,available_quantity,unit_cost)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,lotTmp,orderId,receiptId,outputId,request.warehouseId(),order.get("salesOrderRef"),
                    productId,order.get("productModelId"),order.get("productMachineTierId"),
                    order.get("productModelNameSnapshot"),order.get("machineTierSnapshot"),order.get("customerNameSnapshot"),
                    order.get("salespersonId"),order.get("salespersonNameSnapshot"),name,spec,
                    order.get("customizationMode"),order.get("toolingRequirement"),unit,qty,qty,unitCost);
            long lotId=jdbc.queryForObject("SELECT id FROM finished_goods_lots WHERE lot_no=?",Long.class,lotTmp);
            String lotNo="FG"+String.format("%08d",lotId);
            jdbc.update("UPDATE finished_goods_lots SET lot_no=? WHERE id=?",lotNo,lotId);
            lotNos.add(lotNo);
            jdbc.update("""
                INSERT INTO finished_goods_movements(lot_id,warehouse_id,movement_type,quantity_change,source_type,source_id,
                  source_no,operator_employee_id,operator_name,occurred_at) VALUES(?,?,'PRODUCTION_RECEIPT',?,'COMPLETION_RECEIPT',?,?,?,?,?)
                """,lotId,request.warehouseId(),qty,receiptId,receiptNo,user.employeeId(),user.name(),at);
            productInventory.post(request.warehouseId(),productId,lotId,name,spec,unit,"PRODUCTION_RECEIPT",qty,
                    "COMPLETION_RECEIPT",receiptId,receiptNo,at,remark);
            jdbc.update("""
                INSERT INTO manufacturing_completion_receipt_items(receipt_id,line_no,output_id,sales_product_id,
                  canonical_product_id,finished_goods_lot_id,product_code_snapshot,product_name_snapshot,
                  specification_snapshot,unit,quantity,unit_cost,is_mold,remark)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,receiptId,(Integer)line.get("lineNo"),outputId,productId,
                    ((Number)output.get("canonicalProductId")).longValue(),lotId,
                    output.get("productCode"),name,spec,unit,qty,unitCost,flag(output.get("isMold"))?1:0,remark);
            jdbc.update("UPDATE manufacturing_order_outputs SET received_quantity=received_quantity+? WHERE id=?",qty,outputId);
        }
        // 工单状态机：只按**主产品**的累计收货判定（配套件/模具收了几件不影响"这张单做完没有"）
        BigDecimal primaryQty=lines.stream()
                .filter(l->flag(((Map<String,Object>)l.get("output")).get("isPrimary")))
                .map(l->(BigDecimal)l.get("quantity")).reduce(BigDecimal.ZERO,BigDecimal::add);
        BigDecimal planned=(BigDecimal)order.get("plannedQuantity"),completed=(BigDecimal)order.get("completedQuantity");
        BigDecimal total=completed.add(primaryQty);
        String status=total.compareTo(planned)>=0?"COMPLETED":(operationCount!=null&&operationCount>0?"PARTIALLY_RECEIVED":"IN_PRODUCTION");
        jdbc.update("UPDATE manufacturing_orders SET completed_quantity=?,status=? WHERE id=?",total,status,orderId);
        changeLog.write(ErpChangeLogWriter.of(actor,"product_archive.manufacturing_completion_receipts",receiptId,"INSERT",
                ErpChangeLogWriter.changes(
                        "receiptNo",receiptNo,"manufacturingOrderId",orderId,"warehouseId",request.warehouseId(),
                        "itemCount",lines.size(),"quantity",headerQty,"unit",header.get("unit"),
                        "finishedGoodsName",header.get("productNameSnapshot"),"completedAt",at,
                        "finishedGoodsLotNos",String.join(",",lotNos),"status","POSTED"),
                null,clean(request.remark()),requestKey,null));
        changeLog.write(ErpChangeLogWriter.of(actor,"product_archive.manufacturing_orders",orderId,"UPDATE",
                ErpChangeLogWriter.changes(
                        "completedQuantity",completed+"→"+total,"status","→"+status,"completionReceiptNo",receiptNo,
                        "receivedOutputLines",lines.size()),
                null,"车间交付收货",requestKey,null));
        return receiptId;
    }

    /** 完工入库单查询：支持关键词、状态、仓库、日期区间、报工人筛选。 */
    public Map<String,Object> allReceipts(String keyword,String status,Long warehouseId,
            String operatorName,String dateFrom,String dateTo,int page,int size){
        if(page<1||size<1||size>100)throw new IllegalArgumentException("分页范围无效");
        var where=new StringBuilder(" WHERE 1=1");var params=new ArrayList<>();
        scope.appendByEmployeeId(where,params,"m.salesperson_id","e.department", FeatureCode.MANUFACTURING);
        if(keyword!=null&&!keyword.isBlank()){
            where.append(" AND (r.receipt_no LIKE ? OR m.order_no LIKE ? OR m.sales_order_ref LIKE ? OR m.customer_name_snapshot LIKE ? OR r.finished_goods_name LIKE ?)");
            String k="%"+keyword.trim()+"%";for(int i=0;i<5;i++)params.add(k);
        }
        if(status!=null&&!status.isBlank()){where.append(" AND r.status=?");params.add(status);}
        if(warehouseId!=null){where.append(" AND r.warehouse_id=?");params.add(warehouseId);}
        if(operatorName!=null&&!operatorName.isBlank()){
            where.append(" AND r.operator_name LIKE ?");params.add("%"+operatorName.trim()+"%");
        }
        if(dateFrom!=null&&!dateFrom.isBlank()){where.append(" AND r.completed_at >= ?");params.add(java.sql.Timestamp.valueOf(dateFrom+" 00:00:00"));}
        if(dateTo!=null&&!dateTo.isBlank()){where.append(" AND r.completed_at <= ?");params.add(java.sql.Timestamp.valueOf(dateTo+" 23:59:59"));}
        String from=" FROM manufacturing_completion_receipts r JOIN manufacturing_orders m ON m.id=r.manufacturing_order_id LEFT JOIN employees e ON e.id=m.salesperson_id JOIN warehouses w ON w.id=r.warehouse_id";
        Long total=jdbc.queryForObject("SELECT COUNT(*)"+from+where,Long.class,params.toArray());
        var listParams=new ArrayList<>(params);listParams.add(size);listParams.add((page-1)*size);
        var items=jdbc.query("SELECT r.*,m.order_no,m.sales_order_ref,m.customer_name_snapshot,m.product_name_snapshot,m.unit order_unit,"
                + "w.warehouse_name warehouseName"+from+where+" ORDER BY r.completed_at DESC,r.id DESC LIMIT ? OFFSET ?",
                (r,n)->row(r),listParams.toArray());
        var warehouses=jdbc.queryForList("SELECT id,warehouse_code warehouseCode,warehouse_name warehouseName FROM warehouses WHERE active=1 ORDER BY id");
        return Map.of("items",items,"total",total==null?0:total,"page",page,"size",size,"warehouses",warehouses);
    }

    /** 入库单详情：含关联生产任务、批次、库存流水。 */
    public Map<String,Object> receiptDetail(long id){
        var r=jdbc.query("""
            SELECT r.*,m.order_no,m.sales_order_ref,m.customer_name_snapshot,m.product_name_snapshot,m.unit order_unit,
                   m.planned_quantity,m.completed_quantity,m.status order_status,w.warehouse_name warehouseName
            FROM manufacturing_completion_receipts r
            JOIN manufacturing_orders m ON m.id=r.manufacturing_order_id
            JOIN warehouses w ON w.id=r.warehouse_id
            WHERE r.id=?
            """,(rs,n)->row(rs),id).stream().findFirst().orElseThrow(()->new IllegalArgumentException("入库单不存在"));
        var items=jdbc.query("""
            SELECT i.*,l.lot_no finishedGoodsLotNo FROM manufacturing_completion_receipt_items i
            LEFT JOIN finished_goods_lots l ON l.id=i.finished_goods_lot_id
            WHERE i.receipt_id=? ORDER BY i.line_no
            """,(rs,n)->row(rs),id);
        var lots=jdbc.query("""
            SELECT l.*,w.warehouse_name warehouseName FROM finished_goods_lots l
            JOIN warehouses w ON w.id=l.warehouse_id WHERE l.completion_receipt_id=? ORDER BY l.id
            """,(rs,n)->row(rs),id);
        var movements=jdbc.query("""
            SELECT mv.*,w.warehouse_name warehouseName FROM finished_goods_movements mv
            JOIN warehouses w ON w.id=mv.warehouse_id WHERE mv.source_type IN ('COMPLETION_RECEIPT','COMPLETION_RECEIPT_REVERSAL')
              AND mv.source_id=? ORDER BY mv.id
            """,(rs,n)->row(rs),id);
        return Map.of("receipt",r,"items",items,"lots",lots,"movements",movements);
    }

    /**
     * 反入库（M0 契约写动作：幂等 + 行级审计）。
     *
     * @param requestKey 请求键，必填；重放不重复反入库（否则会二次扣减批次与库存）
     */
    public void reverseReceipt(long receiptId,String reason,String requestKey){
        ErpActor actor=actors.current();
        Map<String,Object> payload=ErpChangeLogWriter.changes(
                "receiptId",receiptId,"reason",clean(reason));
        idem.execute(actor,requestKey,ACTION_COMPLETION_RECEIPT_REVERSE,payload,
                ()->{doReverseReceipt(receiptId,reason,actor,requestKey);return String.valueOf(receiptId);});
    }

    /** 反入库：抵消本次入库数量，回退生产任务已完成数量与状态。 */
    private void doReverseReceipt(long receiptId,String reason,ErpActor actor,String requestKey){
        String r=clean(reason);
        if(r==null||r.isBlank())throw new IllegalArgumentException("请填写反入库原因");
        if(r.length()>500)throw new IllegalArgumentException("反入库原因不能超过500字");
        var receipt=jdbc.query("SELECT * FROM manufacturing_completion_receipts WHERE id=? FOR UPDATE",(rs,n)->row(rs),receiptId)
                .stream().findFirst().orElseThrow(()->new IllegalArgumentException("入库单不存在"));
        if(!"POSTED".equals(String.valueOf(receipt.get("status"))))
            throw new IllegalStateException("入库单当前状态不允许反入库");
        long orderId=((Number)receipt.get("manufacturingOrderId")).longValue();
        var order=jdbc.query("SELECT * FROM manufacturing_orders WHERE id=? FOR UPDATE",(rs,n)->row(rs),orderId)
                .stream().findFirst().orElseThrow(()->new IllegalStateException("关联生产任务不存在"));
        // 关联批次必须存在且未出库消耗才能反入库
        var lots=jdbc.query("SELECT * FROM finished_goods_lots WHERE completion_receipt_id=? FOR UPDATE",(rs,n)->row(rs),receiptId);
        if(lots.isEmpty())throw new IllegalStateException("入库单未关联成品批次，无法反入库");
        for(var lot:lots){
            BigDecimal received=(BigDecimal)lot.get("receivedQuantity");
            BigDecimal available=(BigDecimal)lot.get("availableQuantity");
            if(available.compareTo(received)<0)
                throw new IllegalStateException("成品批次"+lot.get("lotNo")+"已出库消耗，不能反入库（剩余可用"+available+"，原入库"+received+"）");
        }
        var user=users.current();java.time.LocalDateTime at=java.time.LocalDateTime.now();
        // ⚠️ 每批次回退**自己那一份**入库量，不能用头部汇总值 —— 一张收货单可含多个产出，
        //   若沿用头部 quantity 逐批次回退，多产出单会被重复扣减（P3 主子化前该分支不可达，因为
        //   一张单只落一个批次；主子化后立刻成立，故一并修正）。
        BigDecimal primaryReversal=BigDecimal.ZERO;
        for(var lot:lots){
            long lotId=((Number)lot.get("id")).longValue();
            BigDecimal lotQty=(BigDecimal)lot.get("receivedQuantity");
            // 1. 写入反向库存流水（每批次一条，数量 = 该批次入库量）
            jdbc.update("""
                INSERT INTO finished_goods_movements(lot_id,warehouse_id,movement_type,quantity_change,source_type,source_id,
                  source_no,operator_employee_id,operator_name,occurred_at)
                VALUES(?,?,'PRODUCTION_RECEIPT_REVERSAL',?,'COMPLETION_RECEIPT_REVERSAL',?,?,?,?,?)
                """,lotId,lot.get("warehouseId"),lotQty.negate(),receiptId,receipt.get("receiptNo"),user.employeeId(),user.name(),at);
            productInventory.post(((Number)lot.get("warehouseId")).longValue(),((Number)lot.get("salesProductId")).longValue(),lotId,String.valueOf(lot.get("finishedGoodsName")),(String)lot.get("specificationSnapshot"),String.valueOf(lot.get("unit")),"PRODUCTION_RECEIPT_REVERSAL",lotQty.negate(),"COMPLETION_RECEIPT_REVERSAL",receiptId,String.valueOf(receipt.get("receiptNo")),at,r);
            // 2. 回退产出清单已收数量；主产品部分计入工单数量回退
            Object outputIdRaw=lot.get("manufacturingOrderOutputId");
            if(outputIdRaw!=null){
                long outputId=((Number)outputIdRaw).longValue();
                jdbc.update("UPDATE manufacturing_order_outputs SET received_quantity=GREATEST(received_quantity-?,0) WHERE id=?",lotQty,outputId);
                if(flag(jdbc.queryForObject("SELECT is_primary FROM manufacturing_order_outputs WHERE id=?",Object.class,outputId)))
                    primaryReversal=primaryReversal.add(lotQty);
            }
            // 3. 标记批次为 REVERSED，扣减 available_quantity
            jdbc.update("""
                UPDATE finished_goods_lots SET status='REVERSED',available_quantity=available_quantity-?,
                  reversed_at=?,reversal_reason=?,reversal_receipt_id=? WHERE id=?
                """,lotQty,at,r,receiptId,lotId);
        }
        Long reversalMovementId=jdbc.queryForObject("SELECT id FROM finished_goods_movements WHERE source_type='COMPLETION_RECEIPT_REVERSAL' AND source_id=? ORDER BY id DESC LIMIT 1",Long.class,receiptId);
        // 4. 入库单标记为 REVERSED
        jdbc.update("""
            UPDATE manufacturing_completion_receipts SET status='REVERSED',reversed_at=?,reversed_by_employee_id=?,
              reversed_by_name=?,reversal_reason=?,reversal_movement_id=? WHERE id=?
            """,at,user.employeeId(),user.name(),r,reversalMovementId,receiptId);
        // 5. 回退生产任务 completed_quantity（只退主产品）并重算 status
        BigDecimal completed=(BigDecimal)order.get("completedQuantity");
        BigDecimal newCompleted=completed.subtract(primaryReversal);
        if(newCompleted.signum()<0)newCompleted=BigDecimal.ZERO;
        String newStatus;
        BigDecimal planned=(BigDecimal)order.get("plannedQuantity");
        Integer opCount=jdbc.queryForObject("SELECT COUNT(*) FROM manufacturing_order_operations WHERE manufacturing_order_id=?",Integer.class,orderId);
        if(newCompleted.compareTo(BigDecimal.ZERO)==0){
            newStatus="PENDING_RECEIPT";
        }else if(newCompleted.compareTo(planned)>=0){
            newStatus="COMPLETED";
        }else if(opCount!=null&&opCount>0){
            newStatus="PARTIALLY_RECEIVED";
        }else{
            newStatus="IN_PRODUCTION";
        }
        jdbc.update("UPDATE manufacturing_orders SET completed_quantity=?,status=? WHERE id=?",newCompleted,newStatus,orderId);
        changeLog.write(ErpChangeLogWriter.of(actor,"product_archive.manufacturing_completion_receipts",receiptId,"UPDATE",
                ErpChangeLogWriter.changes(
                        "status","POSTED→REVERSED","reversalReason",r,"reversedQuantity",receipt.get("quantity"),
                        "returnedLotCount",lots.size(),"primaryQuantityReturned",primaryReversal,
                        "reversalMovementId",reversalMovementId,"reversedAt",at),
                "REVERSAL",r,requestKey,null));
        changeLog.write(ErpChangeLogWriter.of(actor,"product_archive.manufacturing_orders",orderId,"UPDATE",
                ErpChangeLogWriter.changes(
                        "completedQuantity",completed+"→"+newCompleted,"status","→"+newStatus,
                        "reversedReceiptNo",receipt.get("receiptNo")),
                "REVERSAL",r,requestKey,null));
    }
    // ==================== 生产跟单（跟进日志 + 工序完成日期补录） ====================

    private static final String ACTION_FOLLOW_UP = "manufacturing.followup.submit";
    private static final String ACTION_OPERATION_MANUAL_COMPLETE = "manufacturing.operation.manualComplete";

    /**
     * 跟进入参。
     *
     * <p>{@code content} 必填；{@code expectedFinishDate} / {@code delayReason} /
     * {@code merchandiserEmployeeId} 可选 —— 传了就前滚到订单快照列，不传维持原值
     * （对应跟单进度表「预计完成日期 / 延期原因」两列的"最新一次跟进有效"语义）。
     */
    public record FollowUpRequest(String content, LocalDate expectedFinishDate, String delayReason, Long merchandiserEmployeeId) {}

    /** 某生产任务的跟进日志（新 → 旧）。 */
    public List<Map<String,Object>> followUps(long orderId){
        requireVisible(orderId);
        return jdbc.query("SELECT * FROM manufacturing_order_follow_ups WHERE manufacturing_order_id=? ORDER BY id DESC",
                (r,n)->row(r),orderId);
    }

    /**
     * 新增跟进记录（M0 契约写动作：幂等 + 行级审计）。
     *
     * <p>每次跟进落一条日志（只增不改），同时把可选项前滚到 {@code manufacturing_orders}
     * 的快照列。跟单记录对已完工/已收货的任务同样允许（跟单收尾说明是正常业务），
     * 仅要求任务可见。
     */
    public long addFollowUp(long orderId, FollowUpRequest request, String requestKey){
        ErpActor actor=actors.current();
        Map<String,Object> payload=ErpChangeLogWriter.changes("orderId",orderId,
                "content",clean(request.content()),"expectedFinishDate",request.expectedFinishDate(),
                "delayReason",clean(request.delayReason()),"merchandiserEmployeeId",request.merchandiserEmployeeId());
        return Long.parseLong(idem.execute(actor,requestKey,ACTION_FOLLOW_UP,payload,
                ()->String.valueOf(doAddFollowUp(orderId,request,actor,requestKey))));
    }

    /** 跟进业务体：不加 {@code @Transactional}，事务边界由幂等模板提供。 */
    private long doAddFollowUp(long orderId, FollowUpRequest request, ErpActor actor, String requestKey){
        requireVisible(orderId);
        String content=required(request.content(),"跟进内容",1000);
        String delayReason=request.delayReason()==null||request.delayReason().isBlank()
                ?null:required(request.delayReason(),"延期原因",300);
        Long merchandiserId=request.merchandiserEmployeeId();
        String merchandiserName=null;
        if(merchandiserId!=null){
            merchandiserName=jdbc.query("SELECT name FROM employees WHERE id=? AND status='ACTIVE'",
                    (r,n)->r.getString(1),merchandiserId).stream().findFirst()
                    .orElseThrow(()->new IllegalArgumentException("跟单员不存在或已停用"));
        }
        jdbc.update("""
            UPDATE manufacturing_orders SET
              expected_finish_date=COALESCE(?,expected_finish_date),
              delay_reason=COALESCE(?,delay_reason),
              merchandiser_employee_id=COALESCE(?,merchandiser_employee_id),
              merchandiser_name=COALESCE(?,merchandiser_name)
            WHERE id=?
            """,request.expectedFinishDate(),delayReason,merchandiserId,merchandiserName,orderId);
        jdbc.update("""
            INSERT INTO manufacturing_order_follow_ups(manufacturing_order_id,content,expected_finish_date,
              delay_reason,created_by_employee_id,created_by_name)
            VALUES(?,?,?,?,?,?)
            """,orderId,content,request.expectedFinishDate(),delayReason,actor.employeeId(),actor.name());
        long id=jdbc.queryForObject("SELECT LAST_INSERT_ID()",Long.class);
        changeLog.write(ErpChangeLogWriter.of(actor,"product_archive.manufacturing_order_follow_ups",id,"INSERT",
                ErpChangeLogWriter.changes("orderId",orderId,"content",content,
                        "expectedFinishDate",request.expectedFinishDate(),"delayReason",delayReason,
                        "merchandiserEmployeeId",merchandiserId),
                "FOLLOW_UP",content,requestKey,null));
        changeLog.write(ErpChangeLogWriter.of(actor,"product_archive.manufacturing_orders",orderId,"UPDATE",
                ErpChangeLogWriter.changes("expectedFinishDate",request.expectedFinishDate(),
                        "delayReason",delayReason,"merchandiserEmployeeId",merchandiserId),
                "FOLLOW_UP","跟进记录",requestKey,null));
        return id;
    }

    /**
     * 跟单工作台列表：在 {@link #list} 的数据范围之上叠加「我是跟单员」可见性
     * （被指定为跟单员的人永远能看到自己跟的单，不受业务员轴范围收窄影响），并为每行带出
     * 工序进度（报工完成日期优先，补录日期兜底）与最新一条跟进。
     */
    public Map<String,Object> followUpBoard(String keyword,String status,String workshop,String overdue,
                                            boolean mineOnly,String orderBy,int page,int size){
        if(page<1||size<1||size>100)throw new IllegalArgumentException("分页范围无效");
        var scopeWhere=new StringBuilder();var scopeParams=new ArrayList<>();
        scope.appendByEmployeeId(scopeWhere,scopeParams,"m.salesperson_id","e.department", FeatureCode.MANUFACTURING);
        var where=new StringBuilder(" WHERE (");
        var params=new ArrayList<>();
        if(scopeParams.isEmpty()){
            where.append("1=1");
        }else{
            // scope 帮助器拼出的是 " AND <cond>"，剥掉前缀后与跟单员条件做 OR
            where.append(scopeWhere.substring(5));
            params.addAll(scopeParams);
        }
        where.append(" OR m.merchandiser_employee_id=?)");
        params.add(actors.current().employeeId());
        if(mineOnly){where.append(" AND m.merchandiser_employee_id=?");params.add(actors.current().employeeId());}
        if(keyword!=null&&!keyword.isBlank()){
            where.append(" AND (m.order_no LIKE ? OR m.customer_name_snapshot LIKE ? OR m.product_name_snapshot LIKE ?)");
            String k="%"+keyword.trim()+"%";for(int i=0;i<3;i++)params.add(k);
        }
        if(status!=null&&!status.isBlank()){where.append(" AND m.status=?");params.add(status);}
        else{where.append(" AND m.status<>'CANCELLED'");}
        if(workshop!=null&&!workshop.isBlank()){where.append(" AND m.workshop_type=?");params.add(workshop.trim());}
        if("Y".equalsIgnoreCase(overdue)){
            // 超期判定：原交期与修订预计完成都过期、且未完工（与跟单进度表「已超期」口径一致）
            where.append(" AND m.status NOT IN ('COMPLETED','CLOSED','CANCELLED') AND COALESCE(m.expected_finish_date,m.due_date,m.planned_finish_date) IS NOT NULL");
            where.append(" AND COALESCE(m.expected_finish_date,m.due_date,m.planned_finish_date)<CURDATE()");
        }
        Long total=jdbc.queryForObject("SELECT COUNT(*) FROM manufacturing_orders m LEFT JOIN employees e ON e.id=m.salesperson_id"+where,Long.class,params.toArray());
        params.add(size);params.add((page-1)*size);
        String orderClause="ORDER BY m.id DESC";
        if("due".equalsIgnoreCase(orderBy))orderClause="ORDER BY COALESCE(m.expected_finish_date,m.due_date) IS NULL ASC, COALESCE(m.expected_finish_date,m.due_date) ASC, m.id ASC";
        var items=jdbc.query("SELECT m.* FROM manufacturing_orders m LEFT JOIN employees e ON e.id=m.salesperson_id"+where+
                " "+orderClause+" LIMIT ? OFFSET ?",(r,n)->row(r),params.toArray());
        enrichFollowUpBoard(items);
        return Map.of("items",items,"total",total==null?0:total,"page",page,"size",size);
    }

    /** 为看板行批量带出工序进度与最新跟进（一次查询一批，避免每行一查）。 */
    private void enrichFollowUpBoard(List<Map<String,Object>> items){
        if(items.isEmpty())return;
        List<Long> ids=items.stream().map(m->((Number)m.get("id")).longValue()).toList();
        var byOrder=new LinkedHashMap<Long,List<Map<String,Object>>>();
        String inIds=ids.stream().map(id->"?").collect(java.util.stream.Collectors.joining(","));
        jdbc.query("SELECT manufacturing_order_id,sequence_no,operation_name_snapshot,status,"
                        +"completed_at,manual_completed_date FROM manufacturing_order_operations "
                        +"WHERE manufacturing_order_id IN ("+inIds+") ORDER BY manufacturing_order_id,sequence_no",
                (r,n)->{
            var op=new LinkedHashMap<String,Object>();
            op.put("sequenceNo",r.getInt("sequence_no"));
            op.put("operationName",r.getString("operation_name_snapshot"));
            op.put("status",r.getString("status"));
            Object completed=r.getObject("completed_at");if(completed instanceof java.sql.Timestamp t)completed=t.toLocalDateTime();
            Object manual=r.getObject("manual_completed_date");if(manual instanceof java.sql.Date d)manual=d.toLocalDate();
            op.put("completedAt",completed);
            op.put("manualCompletedDate",manual);
            byOrder.computeIfAbsent(r.getLong("manufacturing_order_id"),k->new ArrayList<>()).add(op);
            return null;
        },ids.toArray());
        var latest=new LinkedHashMap<Long,Map<String,Object>>();
        jdbc.query("SELECT f.* FROM manufacturing_order_follow_ups f "
                        +"JOIN (SELECT manufacturing_order_id,MAX(id) max_id FROM manufacturing_order_follow_ups "
                        +"WHERE manufacturing_order_id IN ("+inIds+") GROUP BY manufacturing_order_id) x "
                        +"ON x.manufacturing_order_id=f.manufacturing_order_id AND x.max_id=f.id",
                (r,n)->latest.put(r.getLong("manufacturing_order_id"),row(r)),ids.toArray());
        for(var item:items){
            long id=((Number)item.get("id")).longValue();
            item.put("operations",byOrder.getOrDefault(id,List.of()));
            item.put("latestFollowUp",latest.get(id));
        }
    }

    /**
     * 补录工序完成日期（车间实际做完但忘了报工的场景）。
     *
     * <p>只补日期，<b>不改</b> status / completed_at / reported_quantity —— 不虚构报工数量、
     * 不触发下游工序解锁；报工仍是工序完成的唯一权威来源。已由报工确认完成的工序不允许补录。
     */
    public void manualCompleteOperation(long orderId,long operationId,LocalDate completedDate,String requestKey){
        ErpActor actor=actors.current();
        Map<String,Object> payload=ErpChangeLogWriter.changes("orderId",orderId,"operationId",operationId,
                "completedDate",completedDate);
        idem.execute(actor,requestKey,ACTION_OPERATION_MANUAL_COMPLETE,payload,
                ()->doManualCompleteOperation(orderId,operationId,completedDate,actor,requestKey));
    }

    private String doManualCompleteOperation(long orderId,long operationId,LocalDate completedDate,ErpActor actor,String requestKey){
        requireVisible(orderId);
        if(completedDate==null)throw new IllegalArgumentException("请选择工序完成日期");
        if(completedDate.isAfter(LocalDate.now()))throw new IllegalArgumentException("工序完成日期不能是未来");
        var op=jdbc.query("""
            SELECT * FROM manufacturing_order_operations WHERE id=? AND manufacturing_order_id=?
            """,(r,n)->row(r),operationId,orderId).stream().findFirst()
            .orElseThrow(()->new IllegalArgumentException("工序不存在或不属于该生产任务"));
        if("COMPLETED".equals(op.get("status")))
            throw new IllegalStateException("该工序已由报工确认完成，完成日期以报工为准，不能补录");
        jdbc.update("""
            UPDATE manufacturing_order_operations SET manual_completed_date=?,manual_completed_at=NOW(),
              manual_completed_by_employee_id=?,manual_completed_by_name=?
            WHERE id=?
            """,completedDate,actor.employeeId(),actor.name(),operationId);
        changeLog.write(ErpChangeLogWriter.of(actor,"product_archive.manufacturing_order_operations",operationId,"UPDATE",
                ErpChangeLogWriter.changes("orderId",orderId,"operationName",op.get("operationNameSnapshot"),
                        "manualCompletedDate",completedDate),
                "MANUAL_COMPLETION",actor.name()+"补录工序完成日期",requestKey,null));
        return "ok";
    }

    private Long defaultTemplateId(String code){return jdbc.query("SELECT id FROM manufacturing_process_templates WHERE template_code=? AND active=1",(r,n)->r.getLong(1),code).stream().findFirst().orElseThrow(()->new IllegalStateException("缺少默认制程模板："+code));}
    public void requireVisible(long id) {
        var where=new StringBuilder(" WHERE m.id=?");var params=new ArrayList<>();params.add(id);
        scope.appendByEmployeeId(where,params,"m.salesperson_id","e.department", FeatureCode.MANUFACTURING);
        Long count=jdbc.queryForObject("SELECT COUNT(*) FROM manufacturing_orders m LEFT JOIN employees e ON e.id=m.salesperson_id"+where,Long.class,params.toArray());
        if(count==null||count==0)throw new IllegalArgumentException("生产任务不存在或无权查看");
    }
    private Map<String,Object> row(java.sql.ResultSet r) throws java.sql.SQLException {
        var map=new LinkedHashMap<String,Object>();ResultSetMetaData md=r.getMetaData();
        for(int i=1;i<=md.getColumnCount();i++){
            Object value=r.getObject(i);if(value instanceof java.sql.Date d)value=d.toLocalDate();else if(value instanceof java.sql.Timestamp t)value=t.toLocalDateTime();
            map.put(camel(md.getColumnLabel(i)),value);
        }return map;
    }
    private static String camel(String s){var b=new StringBuilder();boolean up=false;for(char c:s.toLowerCase().toCharArray()){if(c=='_')up=true;else{b.append(up?Character.toUpperCase(c):c);up=false;}}return b.toString();}
    /**
     * 读 TINYINT(1) 布尔列。
     *
     * <p>必须同时兼容 {@code Boolean} 与 {@code Number}：MySQL Connector/J 默认
     * {@code tinyInt1isBit=true} 会把 TINYINT(1) 读成 {@code Boolean}，但经
     * {@code queryForObject(..., Object.class)} 或在某些驱动/连接参数下会读成整数。
     * 只判其中一种就会在这些路径上抛 {@code ClassCastException}。
     */
    private static boolean flag(Object value){
        return value instanceof Boolean b ? b : value instanceof Number n && n.intValue()!=0;
    }
    private static String clean(String s){return s==null?null:s.trim();}
    private static String required(String s,String label,int max){s=clean(s);if(s==null||s.isBlank())throw new IllegalArgumentException(label+"不能为空");if(s.length()>max)throw new IllegalArgumentException(label+"不能超过"+max+"字");return s;}
}
