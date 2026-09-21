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
    private final AuditLogService audit;
    private final ManufacturingExecutionService execution;
    private final ManufacturingTaskTemplateResolver taskTemplateResolver;
    private final ErpIdempotencyService idem;
    private final ErpChangeLogWriter changeLog;
    private final ErpActorResolver actors;

    public ManufacturingService(JdbcTemplate jdbc,
                                ProductInventoryService productInventory,
                                CurrentUserService users,
                                DataScopeSupport scope,
                                AuditLogService audit,
                                ManufacturingExecutionService execution,
                                ManufacturingTaskTemplateResolver taskTemplateResolver,
                                @Qualifier("archiveIdempotencyService") ErpIdempotencyService idem,
                                @Qualifier("archiveAuditWriter") ErpChangeLogWriter changeLog,
                                ErpActorResolver actors) {
        this.jdbc = jdbc;
        this.productInventory = productInventory;
        this.users = users;
        this.scope = scope;
        this.audit = audit;
        this.execution = execution;
        this.taskTemplateResolver = taskTemplateResolver;
        this.idem = idem;
        this.changeLog = changeLog;
        this.actors = actors;
    }

    public record CreateOrder(String salesOrderRef,Long customerId,String customerName,String productName,
                              String specification,String customRequirement,BigDecimal plannedQuantity,
                              String unit,LocalDate dueDate,String remark,String taskType,Long processTemplateId,Long workshopId) {}
    public record CompleteOrder(Long warehouseId,LocalDateTime completedAt,BigDecimal quantity,
                                 String finishedGoodsName,String specification,String remark,BigDecimal unitCost) {}

    public List<Map<String,Object>> warehouses() {
        return jdbc.queryForList("SELECT id,warehouse_code warehouseCode,warehouse_name warehouseName FROM warehouses WHERE active=1 ORDER BY id");
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
                "customerName",clean(request.customerName()),"productName",clean(request.productName()),
                "plannedQuantity",request.plannedQuantity(),"unit",clean(request.unit()),
                "taskType",clean(request.taskType()),"workshopId",request.workshopId(),
                "processTemplateId",request.processTemplateId(),"dueDate",request.dueDate());
        return Long.parseLong(idem.execute(actor,requestKey,ACTION_ORDER_CREATE,payload,
                ()->String.valueOf(doCreate(request,actor,requestKey))));
    }

    private long doCreate(CreateOrder request,ErpActor actor,String requestKey) {
        String customer=required(request.customerName(),"客户名称",300);
        String product=required(request.productName(),"生产成品名称",500);
        String unit=required(request.unit(),"单位",40);
        if(request.plannedQuantity()==null||request.plannedQuantity().signum()<=0||request.plannedQuantity().scale()>6)
            throw new IllegalArgumentException("计划数量必须大于0且最多6位小数");
        String taskType=clean(request.taskType());
        // 手工任务类型：SAMPLE 打样 / REPLACEMENT 返工替换 / REWORK 返工 / INTERNAL 内部领用 / SPECIAL 特殊定制 / SALES_ORDER 销售排产（业务员缺货时下达）
        if(!Set.of("SAMPLE","REPLACEMENT","REWORK","INTERNAL","SPECIAL","SALES_ORDER").contains(taskType))
            throw new IllegalArgumentException("请选择有效的手工任务类型");
        var user=users.current();
        String temporary="PENDING-"+UUID.randomUUID();
        jdbc.update("""
            INSERT INTO manufacturing_orders(order_no,sales_order_ref,customer_id,customer_name_snapshot,
              salesperson_id,salesperson_name_snapshot,product_name_snapshot,specification_snapshot,
              custom_requirement,planned_quantity,unit,due_date,remark,task_type,created_by_employee_id,created_by_name)
            VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """,temporary,clean(request.salesOrderRef()),request.customerId(),customer,user.employeeId(),user.name(),
                product,clean(request.specification()),clean(request.customRequirement()),request.plannedQuantity(),unit,
                request.dueDate(),clean(request.remark()),taskType,user.employeeId(),user.name());
        long id=jdbc.queryForObject("SELECT id FROM manufacturing_orders WHERE order_no=?",Long.class,temporary);
        String no="MO"+String.format("%08d",id);jdbc.update("UPDATE manufacturing_orders SET order_no=? WHERE id=?",no,id);
        var selection = request.workshopId()==null ? null : taskTemplateResolver.resolve(request.workshopId(),product);
        Long templateId=selection!=null ? selection.templateId() : request.processTemplateId();
        if(templateId==null) throw new IllegalArgumentException("请选择生产车间以确定产品或车间默认制程");
        execution.doGenerateOperations(id,templateId,actor,requestKey);
        // 产线由下单选择决定，不能被制程模板上的历史车间标签覆盖。
        if(selection!=null) jdbc.update("UPDATE manufacturing_orders SET workshop_type=? WHERE id=?",selection.workshopName(),id);
        audit.log("ManufacturingOrder",id,no,"CREATE","创建手工生产任务；类型="+taskType+"；计划数量="+request.plannedQuantity()+unit);
        changeLog.write(ErpChangeLogWriter.of(actor,"product_archive.manufacturing_orders",id,"INSERT",
                ErpChangeLogWriter.changes(
                        "orderNo",no,"taskType",taskType,"customerNameSnapshot",customer,
                        "productNameSnapshot",product,"plannedQuantity",request.plannedQuantity(),"unit",unit,
                        "workshopType",selection!=null?selection.workshopName():null,
                        "processTemplateId",templateId,"dueDate",request.dueDate()),
                null,"创建手工生产任务",requestKey,null));
        return id;
    }


    /**
     * 车间交付收货（M0 契约写动作：幂等 + 行级审计）。
     *
     * <p>口径提醒（决策登记 #17）：这是**仓库收货方**的动作 —— 车间把成品交付到利强仓库，
     * 利强确认入库。不是「车间报完工」，也不是「向车间领料」。
     *
     * @param requestKey 请求键，必填；重放返回首次入库单 id，不重复入库（否则会多出成品批次与库存流水）
     */
    public long complete(long orderId,CompleteOrder request,String requestKey) {
        ErpActor actor=actors.current();
        Map<String,Object> payload=ErpChangeLogWriter.changes(
                "orderId",orderId,"warehouseId",request.warehouseId(),
                "quantity",request.quantity(),"completedAt",request.completedAt(),
                "finishedGoodsName",clean(request.finishedGoodsName()),
                "unitCost",request.unitCost(),"specification",clean(request.specification()));
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
        BigDecimal qty=request.quantity();
        if(qty==null||qty.signum()<=0||qty.scale()>6)throw new IllegalArgumentException("入库数量必须大于0且最多6位小数");
        BigDecimal unitCost=request.unitCost()==null?BigDecimal.ZERO:request.unitCost();
        if(unitCost.signum()<0||unitCost.scale()>4)throw new IllegalArgumentException("实际单位成本必须≥0且最多4位小数");
        BigDecimal planned=(BigDecimal)order.get("plannedQuantity"),completed=(BigDecimal)order.get("completedQuantity");
        if(completed.add(qty).compareTo(planned)>0)throw new IllegalArgumentException("入库数量超过生产任务剩余数量");
        String name=required(request.finishedGoodsName(),"入库成品名称",500);
        LocalDateTime at=request.completedAt()==null?LocalDateTime.now():request.completedAt();
        var user=users.current();String tmp="PENDING-"+UUID.randomUUID();
        jdbc.update("""
            INSERT INTO manufacturing_completion_receipts(receipt_no,manufacturing_order_id,warehouse_id,completed_at,
              quantity,unit,finished_goods_name,specification_snapshot,operator_employee_id,operator_name,remark)
            VALUES(?,?,?,?,?,?,?,?,?,?,?)
            """,tmp,orderId,request.warehouseId(),at,qty,order.get("unit"),name,
                clean(request.specification()),user.employeeId(),user.name(),clean(request.remark()));
        long receiptId=jdbc.queryForObject("SELECT id FROM manufacturing_completion_receipts WHERE receipt_no=?",Long.class,tmp);
        String receiptNo="RC"+String.format("%08d",receiptId);jdbc.update("UPDATE manufacturing_completion_receipts SET receipt_no=? WHERE id=?",receiptNo,receiptId);
        long salesProductId=productInventory.ensureManufacturingProduct(orderId,String.valueOf(order.get("orderNo")),name,clean(request.specification()),String.valueOf(order.get("unit")));
        String lotTmp="PENDING-"+UUID.randomUUID();
        jdbc.update("""
            INSERT INTO finished_goods_lots(lot_no,manufacturing_order_id,completion_receipt_id,warehouse_id,sales_order_ref,
              sales_product_id,product_model_id,product_machine_tier_id,
              product_model_name_snapshot,machine_tier_snapshot,customer_name_snapshot,salesperson_id,salesperson_name_snapshot,
              finished_goods_name,specification_snapshot,customization_mode,tooling_requirement,unit,received_quantity,available_quantity,unit_cost)
            VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """,lotTmp,orderId,receiptId,request.warehouseId(),order.get("salesOrderRef"),
                salesProductId,order.get("productModelId"),order.get("productMachineTierId"),
                order.get("productModelNameSnapshot"),order.get("machineTierSnapshot"),order.get("customerNameSnapshot"),
                order.get("salespersonId"),order.get("salespersonNameSnapshot"),name,clean(request.specification()),
                order.get("customizationMode"),order.get("toolingRequirement"),order.get("unit"),qty,qty,unitCost);
        long lotId=jdbc.queryForObject("SELECT id FROM finished_goods_lots WHERE lot_no=?",Long.class,lotTmp);
        String lotNo="FG"+String.format("%08d",lotId);jdbc.update("UPDATE finished_goods_lots SET lot_no=? WHERE id=?",lotNo,lotId);
        jdbc.update("""
            INSERT INTO finished_goods_movements(lot_id,warehouse_id,movement_type,quantity_change,source_type,source_id,
              source_no,operator_employee_id,operator_name,occurred_at) VALUES(?,?,'PRODUCTION_RECEIPT',?,'COMPLETION_RECEIPT',?,?,?,?,?)
            """,lotId,request.warehouseId(),qty,receiptId,receiptNo,user.employeeId(),user.name(),at);
        productInventory.post(request.warehouseId(),salesProductId,lotId,name,clean(request.specification()),String.valueOf(order.get("unit")),"PRODUCTION_RECEIPT",qty,"COMPLETION_RECEIPT",receiptId,receiptNo,at,clean(request.remark()));
        BigDecimal total=completed.add(qty);String status=total.compareTo(planned)==0?"COMPLETED":(operationCount!=null&&operationCount>0?"PARTIALLY_RECEIVED":"IN_PRODUCTION");
        jdbc.update("UPDATE manufacturing_orders SET completed_quantity=?,status=? WHERE id=?",total,status,orderId);
        audit.log("ManufacturingOrder",orderId,String.valueOf(order.get("orderNo")),"COMPLETE_RECEIPT",
                receiptNo+"生成成品批次"+lotNo+"；入库"+qty+order.get("unit")+"；实际单位成本="+unitCost);
        changeLog.write(ErpChangeLogWriter.of(actor,"product_archive.manufacturing_completion_receipts",receiptId,"INSERT",
                ErpChangeLogWriter.changes(
                        "receiptNo",receiptNo,"manufacturingOrderId",orderId,"warehouseId",request.warehouseId(),
                        "quantity",qty,"unit",order.get("unit"),"finishedGoodsName",name,
                        "completedAt",at,"finishedGoodsLotNo",lotNo,"salesProductId",salesProductId,
                        "status","POSTED"),
                null,clean(request.remark()),requestKey,null));
        changeLog.write(ErpChangeLogWriter.of(actor,"product_archive.manufacturing_orders",orderId,"UPDATE",
                ErpChangeLogWriter.changes(
                        "completedQuantity",completed+"→"+total,"status","→"+status,"completionReceiptNo",receiptNo),
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
        var lots=jdbc.query("""
            SELECT l.*,w.warehouse_name warehouseName FROM finished_goods_lots l
            JOIN warehouses w ON w.id=l.warehouse_id WHERE l.completion_receipt_id=? ORDER BY l.id
            """,(rs,n)->row(rs),id);
        var movements=jdbc.query("""
            SELECT mv.*,w.warehouse_name warehouseName FROM finished_goods_movements mv
            JOIN warehouses w ON w.id=mv.warehouse_id WHERE mv.source_type IN ('COMPLETION_RECEIPT','COMPLETION_RECEIPT_REVERSAL')
              AND mv.source_id=? ORDER BY mv.id
            """,(rs,n)->row(rs),id);
        return Map.of("receipt",r,"lots",lots,"movements",movements);
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
        BigDecimal qty=(BigDecimal)receipt.get("quantity");
        // 1. 写入反向库存流水（每批次一条）
        for(var lot:lots){
            long lotId=((Number)lot.get("id")).longValue();
            jdbc.update("""
                INSERT INTO finished_goods_movements(lot_id,warehouse_id,movement_type,quantity_change,source_type,source_id,
                  source_no,operator_employee_id,operator_name,occurred_at)
                VALUES(?,?,'PRODUCTION_RECEIPT_REVERSAL',?,'COMPLETION_RECEIPT_REVERSAL',?,?,?,?,?)
                """,lotId,lot.get("warehouseId"),qty.negate(),receiptId,receipt.get("receiptNo"),user.employeeId(),user.name(),at);
            productInventory.post(((Number)lot.get("warehouseId")).longValue(),((Number)lot.get("salesProductId")).longValue(),lotId,String.valueOf(lot.get("finishedGoodsName")),(String)lot.get("specificationSnapshot"),String.valueOf(lot.get("unit")),"PRODUCTION_RECEIPT_REVERSAL",qty.negate(),"COMPLETION_RECEIPT_REVERSAL",receiptId,String.valueOf(receipt.get("receiptNo")),at,r);
        }
        Long reversalMovementId=jdbc.queryForObject("SELECT id FROM finished_goods_movements WHERE source_type='COMPLETION_RECEIPT_REVERSAL' AND source_id=? ORDER BY id DESC LIMIT 1",Long.class,receiptId);
        // 2. 标记批次为 REVERSED，扣减 available_quantity
        for(var lot:lots){
            long lotId=((Number)lot.get("id")).longValue();
            BigDecimal received=(BigDecimal)lot.get("receivedQuantity");
            jdbc.update("""
                UPDATE finished_goods_lots SET status='REVERSED',available_quantity=available_quantity-?,
                  reversed_at=?,reversal_reason=?,reversal_receipt_id=? WHERE id=?
                """,received,at,r,receiptId,lotId);
        }
        // 3. 入库单标记为 REVERSED
        jdbc.update("""
            UPDATE manufacturing_completion_receipts SET status='REVERSED',reversed_at=?,reversed_by_employee_id=?,
              reversed_by_name=?,reversal_reason=?,reversal_movement_id=? WHERE id=?
            """,at,user.employeeId(),user.name(),r,reversalMovementId,receiptId);
        // 4. 回退生产任务 completed_quantity 并重算 status
        BigDecimal completed=(BigDecimal)order.get("completedQuantity");
        BigDecimal newCompleted=completed.subtract(qty);
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
        audit.log("ManufacturingOrder",orderId,String.valueOf(order.get("orderNo")),"RECEIPT_REVERSE",
                "反入库"+receipt.get("receiptNo")+"；抵消"+qty+receipt.get("unit")+"；原因="+r);
        changeLog.write(ErpChangeLogWriter.of(actor,"product_archive.manufacturing_completion_receipts",receiptId,"UPDATE",
                ErpChangeLogWriter.changes(
                        "status","POSTED→REVERSED","reversalReason",r,"quantity",qty,
                        "reversalMovementId",reversalMovementId,"reversedAt",at),
                "REVERSAL",r,requestKey,null));
        changeLog.write(ErpChangeLogWriter.of(actor,"product_archive.manufacturing_orders",orderId,"UPDATE",
                ErpChangeLogWriter.changes(
                        "completedQuantity",completed+"→"+newCompleted,"status","→"+newStatus,
                        "reversedReceiptNo",receipt.get("receiptNo")),
                "REVERSAL",r,requestKey,null));
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
    private static String clean(String s){return s==null?null:s.trim();}
    private static String required(String s,String label,int max){s=clean(s);if(s==null||s.isBlank())throw new IllegalArgumentException(label+"不能为空");if(s.length()>max)throw new IllegalArgumentException(label+"不能超过"+max+"字");return s;}
}
