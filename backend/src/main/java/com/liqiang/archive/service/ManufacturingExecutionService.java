package com.liqiang.archive.service;

import com.liqiang.archive.security.CurrentUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.ResultSetMetaData;
import java.time.LocalDateTime;
import java.time.LocalDate;
import java.util.*;

@Service
@RequiredArgsConstructor
public class ManufacturingExecutionService {
    private final JdbcTemplate jdbc;
    private final CurrentUserService users;
    private final AuditLogService audit;

    public record WorkReportRequest(BigDecimal quantity, LocalDateTime reportedAt, String remark,
                                    boolean allowOverproduction, String overproductionReason) {}
    public record InspectionRequest(String result, BigDecimal qualifiedQuantity, BigDecimal unqualifiedQuantity,
                                    String issueDescription, String remark,Long returnOperationId,String reworkRequirement) {}
    public record ScheduleRequest(String priority,LocalDate plannedStartDate,LocalDate plannedFinishDate,
                                  String productionTeamName,Long responsibleEmployeeId) {}
    public record AssignmentRequest(String assignedTeamName,Long assignedEmployeeId) {}
    public record IssueRequest(Long operationId,String issueType,String severity,String description) {}
    public record OperationMasterRequest(String operationCode,String operationName,String operationCategory,String defaultWorkshop,
                                         BigDecimal standardHours,String reportingUnit,boolean requiresInspection,String description,
                                         boolean active,Integer sortOrder) {}
    public record TemplateMasterRequest(String templateCode,String templateName,String workshopType,String applicableProductKeyword,
                                        String description,boolean active,boolean defaultForWorkshop) {}
    public record TemplateOperationRequest(Long operationId,BigDecimal requirementCoefficient,boolean required,
                                           boolean finalOperation,boolean allowParallel,String remark) {}
    public record TemplateRuleRequest(Long productModelId,String customizationMode,Integer priority,boolean active,String remark) {}

    public List<Map<String,Object>> templates() {
        return jdbc.query("""
            SELECT t.*,COUNT(o.id) operation_count FROM manufacturing_process_templates t
            LEFT JOIN manufacturing_process_template_operations o ON o.template_id=t.id
            WHERE t.active=1 GROUP BY t.id ORDER BY t.id
            """,(r,n)->row(r));
    }

    public List<Map<String,Object>> operationCatalog(){return jdbc.query("SELECT * FROM manufacturing_operations ORDER BY sort_order,id",(r,n)->row(r));}
    public List<Map<String,Object>> templateCatalog(){return jdbc.query("""
        SELECT t.*,COUNT(x.id) operation_count FROM manufacturing_process_templates t
        LEFT JOIN manufacturing_process_template_operations x ON x.template_id=t.id GROUP BY t.id ORDER BY t.id
        """,(r,n)->row(r));}
    public Map<String,Object> templateDetail(long id){
        var result=new LinkedHashMap<String,Object>();result.put("template",jdbc.query("SELECT * FROM manufacturing_process_templates WHERE id=?",(r,n)->row(r),id).stream().findFirst().orElseThrow(()->new IllegalArgumentException("制程模板不存在")));
        result.put("operations",jdbc.query("""
            SELECT x.*,o.operation_code,o.operation_name,o.operation_category,o.active operation_active
            FROM manufacturing_process_template_operations x JOIN manufacturing_operations o ON o.id=x.operation_id
            WHERE x.template_id=? ORDER BY x.sequence_no
            """,(r,n)->row(r),id));return result;
    }
    public List<Map<String,Object>> templateRules(long templateId){
        return jdbc.query("""
            SELECT r.*,p.name product_name,p.model_code FROM manufacturing_process_template_rules r
            JOIN product_models p ON p.id=r.product_model_id WHERE r.template_id=?
            ORDER BY r.active DESC,r.priority DESC,p.name,r.id
            """,(r,n)->row(r),templateId);
    }
    @Transactional public long saveTemplateRule(long templateId,Long id,TemplateRuleRequest request){
        if(jdbc.queryForObject("SELECT COUNT(*) FROM manufacturing_process_templates WHERE id=?",Integer.class,templateId)!=1)throw new IllegalArgumentException("制程模板不存在");
        if(request.productModelId()==null||jdbc.queryForObject("SELECT COUNT(*) FROM product_models WHERE id=?",Integer.class,request.productModelId())!=1)throw new IllegalArgumentException("请选择有效的标准产品");
        String mode=clean(request.customizationMode());
        if(mode!=null&&!Set.of("STANDARD","TOOLING_ADAPTATION","CONFIGURATION_CHANGE","SPECIAL_BUILD").contains(mode))throw new IllegalArgumentException("订单方式无效");
        int priority=request.priority()==null?100:request.priority();if(priority<0||priority>999)throw new IllegalArgumentException("推荐优先级应在0到999之间");
        Integer duplicate=jdbc.queryForObject("SELECT COUNT(*) FROM manufacturing_process_template_rules WHERE template_id=? AND product_model_id=? AND COALESCE(customization_mode,'')=COALESCE(?,'') AND id<>COALESCE(?,0)",Integer.class,templateId,request.productModelId(),mode,id);
        if(duplicate!=null&&duplicate>0)throw new IllegalArgumentException("该产品和订单方式已经配置过推荐规则");
        if(id==null){jdbc.update("INSERT INTO manufacturing_process_template_rules(template_id,product_model_id,customization_mode,priority,active,remark) VALUES(?,?,?,?,?,?)",templateId,request.productModelId(),mode,priority,request.active(),clean(request.remark()));id=jdbc.queryForObject("SELECT LAST_INSERT_ID()",Long.class);}
        else{if(jdbc.update("UPDATE manufacturing_process_template_rules SET product_model_id=?,customization_mode=?,priority=?,active=?,remark=? WHERE id=? AND template_id=?",request.productModelId(),mode,priority,request.active(),clean(request.remark()),id,templateId)!=1)throw new IllegalArgumentException("推荐规则不存在");}
        audit.log("ManufacturingTemplateRule",id,String.valueOf(templateId),"SAVE","维护产品制程推荐规则");return id;
    }
    @Transactional public void deleteTemplateRule(long templateId,long id){
        if(jdbc.update("DELETE FROM manufacturing_process_template_rules WHERE id=? AND template_id=?",id,templateId)!=1)throw new IllegalArgumentException("推荐规则不存在");
        audit.log("ManufacturingTemplateRule",id,String.valueOf(templateId),"DELETE","删除产品制程推荐规则");
    }

    @Transactional public long saveOperation(Long id,OperationMasterRequest request){
        String code=masterCode(request.operationCode()),name=requiredText(request.operationName(),"工序名称"),category=requiredText(request.operationCategory(),"工序分类");
        if(request.standardHours()!=null&&request.standardHours().signum()<0)throw new IllegalArgumentException("标准工时不能小于0");int sort=request.sortOrder()==null?0:request.sortOrder();
        if(id==null){jdbc.update("INSERT INTO manufacturing_operations(operation_code,operation_name,operation_category,default_workshop,standard_hours,reporting_unit,requires_inspection,description,active,sort_order) VALUES(?,?,?,?,?,?,?,?,?,?)",code,name,category,clean(request.defaultWorkshop()),request.standardHours(),clean(request.reportingUnit()),request.requiresInspection(),clean(request.description()),request.active(),sort);id=jdbc.queryForObject("SELECT id FROM manufacturing_operations WHERE operation_code=?",Long.class,code);}
        else{
            String existingCode=jdbc.queryForList("SELECT operation_code FROM manufacturing_operations WHERE id=?",String.class,id).stream().findFirst().orElseThrow(()->new IllegalArgumentException("工序不存在"));
            if(!existingCode.equals(code))throw new IllegalArgumentException("工序编码创建后不能修改");
            if(!request.active()){
                Integer used=jdbc.queryForObject("SELECT COUNT(*) FROM manufacturing_process_template_operations x JOIN manufacturing_process_templates t ON t.id=x.template_id WHERE x.operation_id=? AND t.active=1",Integer.class,id);
                if(used!=null&&used>0)throw new IllegalStateException("该工序仍被启用的制程模板使用，请先停用模板或移除该工序");
            }
            jdbc.update("UPDATE manufacturing_operations SET operation_name=?,operation_category=?,default_workshop=?,standard_hours=?,reporting_unit=?,requires_inspection=?,description=?,active=?,sort_order=? WHERE id=?",name,category,clean(request.defaultWorkshop()),request.standardHours(),clean(request.reportingUnit()),request.requiresInspection(),clean(request.description()),request.active(),sort,id);
        }
        audit.log("ManufacturingOperation",id,code,"SAVE_MASTER","维护工序“"+name+"”");return id;
    }

    @Transactional public long saveTemplate(Long id,TemplateMasterRequest request){
        String code=masterCode(request.templateCode()),name=requiredText(request.templateName(),"模板名称");
        if(id==null){jdbc.update("INSERT INTO manufacturing_process_templates(template_code,template_name,workshop_type,applicable_product_keyword,description,active) VALUES(?,?,?,?,?,?)",code,name,clean(request.workshopType()),clean(request.applicableProductKeyword()),clean(request.description()),request.active());id=jdbc.queryForObject("SELECT id FROM manufacturing_process_templates WHERE template_code=?",Long.class,code);}
        else{
            String existingCode=jdbc.queryForList("SELECT template_code FROM manufacturing_process_templates WHERE id=?",String.class,id).stream().findFirst().orElseThrow(()->new IllegalArgumentException("制程模板不存在"));
            if(!existingCode.equals(code))throw new IllegalArgumentException("模板编码创建后不能修改");
            if(request.active()){
                Integer invalid=jdbc.queryForObject("SELECT COUNT(*) FROM manufacturing_process_template_operations x JOIN manufacturing_operations o ON o.id=x.operation_id WHERE x.template_id=? AND o.active=0",Integer.class,id);
                if(invalid!=null&&invalid>0)throw new IllegalStateException("模板包含已停用工序，处理后才能启用");
            }
            jdbc.update("UPDATE manufacturing_process_templates SET template_name=?,workshop_type=?,applicable_product_keyword=?,description=?,active=? WHERE id=?",name,clean(request.workshopType()),clean(request.applicableProductKeyword()),clean(request.description()),request.active(),id);
        }
        // 默认关系属于模板配置：一个车间只能有一个默认制程，产品专属制程仍可覆盖它。
        jdbc.update("UPDATE manufacturing_workshops SET default_process_template_id=NULL WHERE default_process_template_id=?",id);
        if(request.defaultForWorkshop()) {
            if(!request.active()) throw new IllegalArgumentException("停用的模板不能设为车间默认制程");
            String workshop=clean(request.workshopType());
            if(workshop==null) throw new IllegalArgumentException("设为车间默认制程时必须选择车间");
            if(jdbc.update("UPDATE manufacturing_workshops SET default_process_template_id=? WHERE workshop_name=? AND enabled=1",id,workshop)==0)
                throw new IllegalArgumentException("所选车间不存在或已停用");
        }
        audit.log("ManufacturingProcessTemplate",id,code,"SAVE_MASTER","维护制程模板“"+name+"”；车间默认="+request.defaultForWorkshop());return id;
    }

    @Transactional public void replaceTemplateOperations(long id,List<TemplateOperationRequest> rows){
        var template=jdbc.queryForMap("SELECT template_code FROM manufacturing_process_templates WHERE id=? FOR UPDATE",id);if(rows==null||rows.isEmpty())throw new IllegalArgumentException("制程模板至少需要一道工序");
        var ids=new HashSet<Long>();int finals=0;for(var x:rows){if(x.operationId()==null||!ids.add(x.operationId()))throw new IllegalArgumentException("模板中存在重复或无效工序");if(x.requirementCoefficient()==null||x.requirementCoefficient().signum()<=0)throw new IllegalArgumentException("工序数量系数必须大于0");if(x.finalOperation())finals++;}
        if(finals!=1)throw new IllegalArgumentException("制程模板必须且只能设置一道最终工序");Integer valid=jdbc.queryForObject("SELECT COUNT(*) FROM manufacturing_operations WHERE id IN ("+String.join(",",Collections.nCopies(ids.size(),"?"))+") AND active=1",Integer.class,ids.toArray());if(valid==null||valid!=ids.size())throw new IllegalArgumentException("模板包含不存在或已停用的工序");
        jdbc.update("DELETE FROM manufacturing_process_template_operations WHERE template_id=?",id);int sequence=1;for(var x:rows)jdbc.update("INSERT INTO manufacturing_process_template_operations(template_id,operation_id,sequence_no,requirement_coefficient,required,is_final,allow_parallel,remark) VALUES(?,?,?,?,?,?,?,?)",id,x.operationId(),sequence++,x.requirementCoefficient(),x.required(),x.finalOperation(),x.allowParallel(),clean(x.remark()));
        jdbc.update("UPDATE manufacturing_process_templates SET version=version+1 WHERE id=?",id);audit.log("ManufacturingProcessTemplate",id,String.valueOf(template.get("template_code")),"REPLACE_OPERATIONS","更新模板工序，共"+rows.size()+"道");
    }

    public List<Map<String,Object>> operations(long orderId) {
        return jdbc.query("""
            SELECT o.*,CASE WHEN o.required_quantity=0 THEN 0
              ELSE LEAST(100,ROUND(o.reported_quantity/o.required_quantity*100,1)) END progress_percent
            FROM manufacturing_order_operations o WHERE o.manufacturing_order_id=? ORDER BY o.sequence_no
            """,(r,n)->row(r),orderId);
    }

    public List<Map<String,Object>> reports(long orderId) {
        return jdbc.query("""
            SELECT r.*,o.operation_name_snapshot FROM manufacturing_work_reports r
            JOIN manufacturing_order_operations o ON o.id=r.manufacturing_order_operation_id
            WHERE r.manufacturing_order_id=? ORDER BY r.reported_at DESC,r.id DESC
            """,(r,n)->row(r),orderId);
    }

    public List<Map<String,Object>> inspections(long orderId) {
        return jdbc.query("SELECT * FROM manufacturing_inspections WHERE manufacturing_order_id=? ORDER BY id DESC",
                (r,n)->row(r),orderId);
    }
    public List<Map<String,Object>> reworks(long orderId){
        return jdbc.query("""
            SELECT r.*,op.operation_name_snapshot FROM manufacturing_reworks r
            JOIN manufacturing_order_operations op ON op.id=r.return_operation_id
            WHERE r.manufacturing_order_id=? ORDER BY r.id DESC
            """,(r,n)->row(r),orderId);
    }

    public Map<String,Object> statistics(){
        var summary=jdbc.queryForMap("""
            SELECT COUNT(*) total,
              SUM(CASE WHEN status IN ('RELEASED','SCHEDULED','IN_PRODUCTION','REWORK') THEN 1 ELSE 0 END) active,
              SUM(CASE WHEN status='REWORK' THEN 1 ELSE 0 END) rework,
              SUM(CASE WHEN status='PENDING_INSPECTION' OR status='PENDING_REINSPECTION' THEN 1 ELSE 0 END) pending_inspection,
              SUM(CASE WHEN status='PENDING_RECEIPT' OR status='PARTIALLY_RECEIVED' THEN 1 ELSE 0 END) pending_receipt,
              SUM(CASE WHEN due_date IS NOT NULL AND due_date<CURDATE() AND status NOT IN ('COMPLETED','CLOSED','CANCELLED') THEN 1 ELSE 0 END) overdue
            FROM manufacturing_orders
            """);
        var quality=jdbc.queryForMap("""
            SELECT COUNT(*) inspections,SUM(CASE WHEN inspection_result='FAILED' THEN 1 ELSE 0 END) failed
            FROM manufacturing_inspections WHERE inspected_at>=DATE_SUB(NOW(),INTERVAL 30 DAY)
            """);
        var delivery=jdbc.queryForMap("""
            SELECT COUNT(*) completed,SUM(CASE WHEN x.due_date IS NULL OR DATE(x.completed_at)<=x.due_date THEN 1 ELSE 0 END) on_time
            FROM (SELECT m.id,m.due_date,MAX(r.completed_at) completed_at FROM manufacturing_orders m
              JOIN manufacturing_completion_receipts r ON r.manufacturing_order_id=m.id
              WHERE m.status='COMPLETED' GROUP BY m.id,m.due_date HAVING MAX(r.completed_at)>=DATE_SUB(NOW(),INTERVAL 30 DAY)) x
            """);
        var workshops=jdbc.query("""
            SELECT COALESCE(workshop_type,'未指定') workshop,COUNT(*) task_count,
              SUM(CASE WHEN status='REWORK' THEN 1 ELSE 0 END) rework_count,
              SUM(CASE WHEN due_date IS NOT NULL AND due_date<CURDATE() THEN 1 ELSE 0 END) overdue_count
            FROM manufacturing_orders WHERE status IN ('RELEASED','SCHEDULED','IN_PRODUCTION','REWORK','PENDING_INSPECTION','PENDING_REINSPECTION')
            GROUP BY workshop_type ORDER BY task_count DESC
            """,(r,n)->row(r));
        return Map.of("summary",camelMap(summary),"quality30d",camelMap(quality),"delivery30d",camelMap(delivery),"workshops",workshops);
    }

    public List<Map<String,Object>> issues(long orderId) {
        return jdbc.query("""
            SELECT i.*,op.operation_name_snapshot FROM manufacturing_issues i
            LEFT JOIN manufacturing_order_operations op ON op.id=i.manufacturing_order_operation_id
            WHERE i.manufacturing_order_id=? ORDER BY CASE i.status WHEN 'OPEN' THEN 0 ELSE 1 END,i.id DESC
            """,(r,n)->row(r),orderId);
    }

    public List<Map<String,Object>> assignees(){
        return jdbc.query("SELECT id,name,department FROM employees WHERE status='ACTIVE' ORDER BY department,name",(r,n)->row(r));
    }

    @Transactional
    public void schedule(long orderId,ScheduleRequest request){
        var order=jdbc.queryForMap("SELECT order_no,status FROM manufacturing_orders WHERE id=? FOR UPDATE",orderId);
        if(Set.of("COMPLETED","CLOSED","CANCELLED").contains(String.valueOf(order.get("status"))))throw new IllegalStateException("已结束的生产任务不能修改排产");
        String priority=clean(request.priority());if(priority==null)priority="NORMAL";
        if(!Set.of("LOW","NORMAL","HIGH","URGENT").contains(priority))throw new IllegalArgumentException("生产优先级无效");
        if(request.plannedStartDate()!=null&&request.plannedFinishDate()!=null&&request.plannedFinishDate().isBefore(request.plannedStartDate()))throw new IllegalArgumentException("计划完工日期不能早于计划开工日期");
        String employeeName=employeeName(request.responsibleEmployeeId());
        jdbc.update("""
            UPDATE manufacturing_orders SET priority=?,planned_start_date=?,planned_finish_date=?,production_team_name=?,
              responsible_employee_id=?,responsible_employee_name=? WHERE id=?
            """,priority,request.plannedStartDate(),request.plannedFinishDate(),clean(request.productionTeamName()),request.responsibleEmployeeId(),employeeName,orderId);
        audit.log("ManufacturingOrder",orderId,String.valueOf(order.get("order_no")),"SCHEDULE","优先级="+priority+"；班组="+clean(request.productionTeamName())+"；负责人="+employeeName);
    }

    @Transactional
    public void assign(long orderId,long operationId,AssignmentRequest request){
        var operation=jdbc.query("SELECT * FROM manufacturing_order_operations WHERE id=? AND manufacturing_order_id=? FOR UPDATE",(r,n)->row(r),operationId,orderId)
                .stream().findFirst().orElseThrow(()->new IllegalArgumentException("任务工序不存在"));
        if(Set.of("COMPLETED","CANCELLED").contains(String.valueOf(operation.get("status"))))throw new IllegalStateException("已结束的工序不能重新分派");
        String orderStatus=jdbc.queryForObject("SELECT status FROM manufacturing_orders WHERE id=?",String.class,orderId);
        if(Set.of("COMPLETED","CLOSED","CANCELLED").contains(orderStatus))throw new IllegalStateException("已结束的生产任务不能重新分派");
        String employeeName=employeeName(request.assignedEmployeeId());
        jdbc.update("UPDATE manufacturing_order_operations SET assigned_team_name=?,assigned_employee_id=?,assigned_employee_name=? WHERE id=?",
                clean(request.assignedTeamName()),request.assignedEmployeeId(),employeeName,operationId);
        audit.log("ManufacturingOrderOperation",operationId,String.valueOf(operation.get("operationNameSnapshot")),"ASSIGN","班组="+clean(request.assignedTeamName())+"；负责人="+employeeName);
    }

    @Transactional
    public long reportIssue(long orderId,IssueRequest request){
        String type=clean(request.issueType()),severity=clean(request.severity()),description=clean(request.description());
        if(!Set.of("MATERIAL","DRAWING","TOOLING","EQUIPMENT","CUSTOMER_CONFIRM","QUALITY","OUTSOURCING","OTHER").contains(type))throw new IllegalArgumentException("异常类型无效");
        if(!Set.of("NORMAL","HIGH","CRITICAL").contains(severity))throw new IllegalArgumentException("异常级别无效");
        if(description==null)throw new IllegalArgumentException("请填写异常说明");
        if(request.operationId()!=null&&jdbc.queryForObject("SELECT COUNT(*) FROM manufacturing_order_operations WHERE id=? AND manufacturing_order_id=?",Integer.class,request.operationId(),orderId)!=1)throw new IllegalArgumentException("任务工序不存在");
        var order=jdbc.queryForMap("SELECT order_no FROM manufacturing_orders WHERE id=? FOR UPDATE",orderId);var user=users.current();String tmp="PENDING-"+UUID.randomUUID();
        jdbc.update("""
            INSERT INTO manufacturing_issues(issue_no,manufacturing_order_id,manufacturing_order_operation_id,issue_type,severity,
              description,reporter_employee_id,reporter_name_snapshot,reported_at) VALUES(?,?,?,?,?,?,?,?,NOW())
            """,tmp,orderId,request.operationId(),type,severity,description,user.employeeId(),user.name());
        long id=jdbc.queryForObject("SELECT id FROM manufacturing_issues WHERE issue_no=?",Long.class,tmp);String no="MI"+String.format("%08d",id);jdbc.update("UPDATE manufacturing_issues SET issue_no=? WHERE id=?",no,id);
        if(request.operationId()!=null)jdbc.update("UPDATE manufacturing_order_operations SET status='BLOCKED' WHERE id=? AND status IN ('READY','IN_PROGRESS')",request.operationId());
        audit.log("ManufacturingIssue",id,no,"REPORT",type+"；"+description);return id;
    }

    @Transactional
    public void resolveIssue(long issueId,String resolution){
        resolution=clean(resolution);if(resolution==null)throw new IllegalArgumentException("请填写处理结果");
        var issue=jdbc.query("SELECT * FROM manufacturing_issues WHERE id=? FOR UPDATE",(r,n)->row(r),issueId).stream().findFirst().orElseThrow(()->new IllegalArgumentException("生产异常不存在"));
        if(!"OPEN".equals(issue.get("status")))throw new IllegalStateException("该异常已经处理");var user=users.current();
        jdbc.update("UPDATE manufacturing_issues SET status='RESOLVED',resolver_employee_id=?,resolver_name_snapshot=?,resolved_at=NOW(),resolution=? WHERE id=?",user.employeeId(),user.name(),resolution,issueId);
        Object operationId=issue.get("manufacturingOrderOperationId");
        if(operationId!=null){
            Integer open=jdbc.queryForObject("SELECT COUNT(*) FROM manufacturing_issues WHERE manufacturing_order_operation_id=? AND status='OPEN'",Integer.class,operationId);
            if(open!=null&&open==0)jdbc.update("UPDATE manufacturing_order_operations SET status=CASE WHEN reported_quantity>0 THEN 'IN_PROGRESS' ELSE 'READY' END WHERE id=? AND status='BLOCKED'",operationId);
        }
        audit.log("ManufacturingIssue",issueId,String.valueOf(issue.get("issueNo")),"RESOLVE",resolution);
    }

    public Map<String,Object> executionBoard(String keyword,String workshop,String state,int page,int size) {
        if(page<1||size<1||size>100)throw new IllegalArgumentException("分页范围无效");
        var where=new StringBuilder(" WHERE op.status IN ('READY','IN_PROGRESS','BLOCKED') AND m.status IN ('RELEASED','SCHEDULED','IN_PRODUCTION','REWORK')");
        var params=new ArrayList<Object>();
        if(keyword!=null&&!keyword.isBlank()){
            where.append(" AND (m.order_no LIKE ? OR m.sales_order_ref LIKE ? OR m.customer_name_snapshot LIKE ? OR m.product_name_snapshot LIKE ? OR op.operation_name_snapshot LIKE ?)");
            String k="%"+keyword.trim()+"%";for(int i=0;i<5;i++)params.add(k);
        }
        if(workshop!=null&&!workshop.isBlank()){where.append(" AND m.workshop_type=?");params.add(workshop.trim());}
        if("READY".equals(state))where.append(" AND op.status='READY'");
        else if("IN_PROGRESS".equals(state))where.append(" AND op.status='IN_PROGRESS'");
        else if("BLOCKED".equals(state))where.append(" AND op.status='BLOCKED'");
        else if("OVERDUE".equals(state))where.append(" AND m.due_date IS NOT NULL AND m.due_date<CURDATE()");
        else if("REWORK".equals(state))where.append(" AND m.status='REWORK'");
        else if(state!=null&&!state.isBlank())throw new IllegalArgumentException("看板状态无效");
        String from=" FROM manufacturing_order_operations op JOIN manufacturing_orders m ON m.id=op.manufacturing_order_id";
        Long total=jdbc.queryForObject("SELECT COUNT(*)"+from+where,Long.class,params.toArray());
        var listParams=new ArrayList<>(params);listParams.add(size);listParams.add((page-1)*size);
        var items=jdbc.query("""
            SELECT op.id operation_id,op.manufacturing_order_id,op.sequence_no,op.operation_name_snapshot,
              op.required_quantity,op.reported_quantity,op.status operation_status,op.started_at,
              m.order_no,m.sales_order_ref,m.customer_name_snapshot,m.product_name_snapshot,m.specification_snapshot,
              m.custom_requirement,m.planned_quantity,m.unit,m.due_date,m.workshop_type,m.process_template_name_snapshot,
              m.task_type,m.salesperson_name_snapshot,m.status order_status,m.priority,m.planned_start_date,m.planned_finish_date,
              m.production_team_name,m.responsible_employee_id,m.responsible_employee_name,
              op.assigned_team_name,op.assigned_employee_id,op.assigned_employee_name,
              (SELECT COUNT(*) FROM manufacturing_issues i WHERE i.manufacturing_order_operation_id=op.id AND i.status='OPEN') open_issue_count
            """+from+where+" ORDER BY FIELD(m.priority,'URGENT','HIGH','NORMAL','LOW'),CASE WHEN m.planned_finish_date IS NULL THEN 1 ELSE 0 END,m.planned_finish_date,CASE WHEN m.due_date IS NULL THEN 1 ELSE 0 END,m.due_date,m.id LIMIT ? OFFSET ?",
                (r,n)->row(r),listParams.toArray());
        var summaryRow=jdbc.queryForMap("""
            SELECT COUNT(*) total,
              SUM(CASE WHEN op.status='READY' THEN 1 ELSE 0 END) ready,
              SUM(CASE WHEN op.status='IN_PROGRESS' THEN 1 ELSE 0 END) in_progress,
              SUM(CASE WHEN op.status='BLOCKED' THEN 1 ELSE 0 END) blocked,
              SUM(CASE WHEN m.due_date IS NOT NULL AND m.due_date<CURDATE() THEN 1 ELSE 0 END) overdue,
              SUM(CASE WHEN m.status='REWORK' THEN 1 ELSE 0 END) rework
            """+from+" WHERE op.status IN ('READY','IN_PROGRESS','BLOCKED') AND m.status IN ('RELEASED','SCHEDULED','IN_PRODUCTION','REWORK')");
        var summary=Map.<String,Object>of("total",number(summaryRow.get("total")),"ready",number(summaryRow.get("ready")),
                "inProgress",number(summaryRow.get("in_progress")),"blocked",number(summaryRow.get("blocked")),
                "overdue",number(summaryRow.get("overdue")),"rework",number(summaryRow.get("rework")));
        var workshops=jdbc.queryForList("SELECT DISTINCT workshop_type FROM manufacturing_orders WHERE workshop_type IS NOT NULL AND workshop_type<>'' ORDER BY workshop_type",String.class);
        return Map.of("items",items,"total",total==null?0:total,"page",page,"size",size,"summary",summary,"workshops",workshops);
    }

    public Map<String,Object> myReports(int page,int size) {
        if(page<1||size<1||size>100)throw new IllegalArgumentException("分页范围无效");
        Long employeeId=users.current().employeeId();
        Long total=jdbc.queryForObject("SELECT COUNT(*) FROM manufacturing_work_reports WHERE reporter_employee_id=?",Long.class,employeeId);
        var items=jdbc.query("""
            SELECT r.*,op.operation_name_snapshot,m.order_no,m.customer_name_snapshot,m.product_name_snapshot,m.unit
            FROM manufacturing_work_reports r
            JOIN manufacturing_order_operations op ON op.id=r.manufacturing_order_operation_id
            JOIN manufacturing_orders m ON m.id=r.manufacturing_order_id
            WHERE r.reporter_employee_id=? ORDER BY r.reported_at DESC,r.id DESC LIMIT ? OFFSET ?
            """,(r,n)->row(r),employeeId,size,(page-1)*size);
        return Map.of("items",items,"total",total==null?0:total,"page",page,"size",size);
    }

    public Map<String,Object> allReports(String keyword,String reporterName,String workshop,
            String operationName,String status,String dateFrom,String dateTo,int page,int size) {
        if(page<1||size<1||size>100)throw new IllegalArgumentException("分页范围无效");
        var where=new StringBuilder(" WHERE 1=1");
        var params=new ArrayList<Object>();
        if(keyword!=null&&!keyword.isBlank()){
            where.append(" AND (m.order_no LIKE ? OR m.sales_order_ref LIKE ? OR m.customer_name_snapshot LIKE ? OR m.product_name_snapshot LIKE ?)");
            String k="%"+keyword.trim()+"%";for(int i=0;i<4;i++)params.add(k);
        }
        if(reporterName!=null&&!reporterName.isBlank()){
            where.append(" AND r.reporter_name_snapshot LIKE ?");
            params.add("%"+reporterName.trim()+"%");
        }
        if(workshop!=null&&!workshop.isBlank()){
            where.append(" AND m.workshop_type=?");
            params.add(workshop.trim());
        }
        if(operationName!=null&&!operationName.isBlank()){
            where.append(" AND op.operation_name_snapshot LIKE ?");
            params.add("%"+operationName.trim()+"%");
        }
        if(status!=null&&!status.isBlank()){
            where.append(" AND r.status=?");
            params.add(status.trim());
        }
        if(dateFrom!=null&&!dateFrom.isBlank()){
            where.append(" AND r.reported_at >= ?");
            params.add(java.sql.Timestamp.valueOf(dateFrom.trim()+" 00:00:00"));
        }
        if(dateTo!=null&&!dateTo.isBlank()){
            where.append(" AND r.reported_at <= ?");
            params.add(java.sql.Timestamp.valueOf(dateTo.trim()+" 23:59:59"));
        }
        String from=" FROM manufacturing_work_reports r JOIN manufacturing_order_operations op ON op.id=r.manufacturing_order_operation_id JOIN manufacturing_orders m ON m.id=r.manufacturing_order_id";
        Long total=jdbc.queryForObject("SELECT COUNT(*)"+from+where,Long.class,params.toArray());
        var listParams=new ArrayList<>(params);listParams.add(size);listParams.add((page-1)*size);
        var items=jdbc.query("SELECT r.*,op.operation_name_snapshot,m.order_no,m.customer_name_snapshot,m.product_name_snapshot,m.unit,m.workshop_type"
                +from+where+" ORDER BY r.reported_at DESC,r.id DESC LIMIT ? OFFSET ?",
                (r,n)->row(r),listParams.toArray());
        var workshops=jdbc.queryForList("SELECT DISTINCT workshop_type FROM manufacturing_orders WHERE workshop_type IS NOT NULL AND workshop_type<>'' ORDER BY workshop_type",String.class);
        return Map.of("items",items,"total",total==null?0:total,"page",page,"size",size,"workshops",workshops);
    }

    public Map<String,Object> pendingInspections(String keyword,int page,int size) {
        if(page<1||size<1||size>100)throw new IllegalArgumentException("分页范围无效");
        var where=new StringBuilder(" WHERE m.status IN ('PENDING_INSPECTION','PENDING_REINSPECTION')");
        var params=new ArrayList<Object>();
        if(keyword!=null&&!keyword.isBlank()){
            where.append(" AND (m.order_no LIKE ? OR m.sales_order_ref LIKE ? OR m.customer_name_snapshot LIKE ? OR m.product_name_snapshot LIKE ?)");
            String k="%"+keyword.trim()+"%";for(int i=0;i<4;i++)params.add(k);
        }
        Long total=jdbc.queryForObject("SELECT COUNT(*) FROM manufacturing_orders m"+where,Long.class,params.toArray());
        var listParams=new ArrayList<>(params);listParams.add(size);listParams.add((page-1)*size);
        var items=jdbc.query("""
            SELECT m.id,m.order_no,m.sales_order_ref,m.customer_name_snapshot,m.product_name_snapshot,
              m.specification_snapshot,m.custom_requirement,m.planned_quantity,m.completed_quantity,m.unit,
              m.due_date,m.workshop_type,m.process_template_name_snapshot,m.task_type,m.salesperson_name_snapshot,
              m.status,m.production_completed_at,m.inspection_status
            FROM manufacturing_orders m
            """+where+" ORDER BY CASE WHEN m.due_date IS NULL THEN 1 ELSE 0 END,m.due_date,m.id DESC LIMIT ? OFFSET ?",
                (r,n)->row(r),listParams.toArray());
        long readyCount=items.stream().filter(m->"PENDING_REINSPECTION".equals(String.valueOf(m.get("status")))).count();
        return Map.of("items",items,"total",total==null?0:total,"page",page,"size",size,
                "reinspectionCount",readyCount);
    }

    @Transactional
    public void generateOperations(long orderId, Long templateId) {
        if(templateId==null) throw new IllegalArgumentException("请选择制程模板");
        Integer existing=jdbc.queryForObject("SELECT COUNT(*) FROM manufacturing_order_operations WHERE manufacturing_order_id=?",Integer.class,orderId);
        if(existing!=null&&existing>0) throw new IllegalStateException("生产任务已经生成工序，不能重复生成");
        var order=jdbc.queryForMap("SELECT id,order_no,planned_quantity FROM manufacturing_orders WHERE id=? FOR UPDATE",orderId);
        var template=jdbc.query("SELECT id,template_name,workshop_type FROM manufacturing_process_templates WHERE id=? AND active=1",
                (r,n)->Map.<String,Object>of("id",r.getLong("id"),"name",r.getString("template_name"),"workshop",r.getString("workshop_type")),templateId)
                .stream().findFirst().orElseThrow(()->new IllegalArgumentException("制程模板不存在或已停用"));
        var rows=jdbc.query("""
            SELECT x.*,o.operation_name FROM manufacturing_process_template_operations x
            JOIN manufacturing_operations o ON o.id=x.operation_id
            WHERE x.template_id=? ORDER BY x.sequence_no
            """,(r,n)->row(r),templateId);
        if(rows.isEmpty()) throw new IllegalArgumentException("所选制程模板没有工序");
        BigDecimal planned=(BigDecimal)order.get("planned_quantity");
        for(int i=0;i<rows.size();i++){
            var x=rows.get(i);BigDecimal coefficient=(BigDecimal)x.get("requirementCoefficient");
            jdbc.update("""
                INSERT INTO manufacturing_order_operations(manufacturing_order_id,source_template_operation_id,operation_id,
                  sequence_no,operation_name_snapshot,requirement_coefficient,required_quantity,required,is_final,
                  allow_parallel,status) VALUES(?,?,?,?,?,?,?,?,?,?,?)
                """,orderId,x.get("id"),x.get("operationId"),x.get("sequenceNo"),x.get("operationName"),coefficient,
                    planned.multiply(coefficient),x.get("required"),x.get("isFinal"),x.get("allowParallel"),i==0?"READY":"PENDING");
        }
        boolean needInspection=needsInspection(orderId);
        jdbc.update("UPDATE manufacturing_orders SET process_template_id=?,process_template_name_snapshot=?,workshop_type=?,inspection_status=?,status='RELEASED' WHERE id=?",
                templateId,template.get("name"),template.get("workshop"),needInspection?"PENDING":"NOT_REQUIRED",orderId);
        audit.log("ManufacturingOrder",orderId,String.valueOf(order.get("order_no")),"GENERATE_OPERATIONS","按模板“"+template.get("name")+"”生成"+rows.size()+"道任务工序"+(needInspection?"（含人工检验工序）":"（无人工检验工序，完工后自动确认）"));
    }

    @Transactional
    public long report(long orderId,long operationId,WorkReportRequest request) {
        if(request.quantity()==null||request.quantity().signum()<=0||request.quantity().scale()>6) throw new IllegalArgumentException("报工数量必须大于0且最多6位小数");
        var operation=jdbc.query("SELECT * FROM manufacturing_order_operations WHERE id=? AND manufacturing_order_id=? FOR UPDATE",
                (r,n)->row(r),operationId,orderId).stream().findFirst().orElseThrow(()->new IllegalArgumentException("任务工序不存在"));
        if(!Set.of("READY","IN_PROGRESS").contains(String.valueOf(operation.get("status")))) throw new IllegalStateException("当前工序尚不可报工或已经完成");
        BigDecimal required=(BigDecimal)operation.get("requiredQuantity");
        BigDecimal reported=(BigDecimal)operation.get("reportedQuantity");
        BigDecimal remaining=required.subtract(reported),over=request.quantity().subtract(remaining).max(BigDecimal.ZERO);
        if(over.signum()>0){
            if(!request.allowOverproduction()||!users.can("manufacturing.report.override")) throw new IllegalArgumentException("本次报工超过剩余数量，需要生产管理权限");
            if(clean(request.overproductionReason())==null) throw new IllegalArgumentException("超额报工必须填写原因");
        }
        var user=users.current();String temp="PENDING-"+UUID.randomUUID();LocalDateTime at=request.reportedAt()==null?LocalDateTime.now():request.reportedAt();
        jdbc.update("""
            INSERT INTO manufacturing_work_reports(report_no,manufacturing_order_id,manufacturing_order_operation_id,execution_round,
              reported_quantity,reported_at,reporter_employee_id,reporter_name_snapshot,department_name_snapshot,
              overproduction_quantity,overproduction_reason,remark) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)
            """,temp,orderId,operationId,operation.get("executionRound"),request.quantity(),at,user.employeeId(),user.name(),user.department(),over,
                clean(request.overproductionReason()),clean(request.remark()));
        long id=jdbc.queryForObject("SELECT id FROM manufacturing_work_reports WHERE report_no=?",Long.class,temp);
        String no="WR"+String.format("%08d",id);jdbc.update("UPDATE manufacturing_work_reports SET report_no=? WHERE id=?",no,id);
        recalculate(orderId,operationId);
        audit.log("ManufacturingWorkReport",id,no,"CREATE","工序“"+operation.get("operationNameSnapshot")+"”报工"+request.quantity());
        return id;
    }

    @Transactional
    public void reverse(long reportId,String reason) {
        if(!users.can("manufacturing.report.override")) throw new SecurityException("没有报工冲正权限");
        reason=clean(reason);if(reason==null)throw new IllegalArgumentException("请填写冲正原因");
        var report=jdbc.query("SELECT * FROM manufacturing_work_reports WHERE id=? FOR UPDATE",(r,n)->row(r),reportId)
                .stream().findFirst().orElseThrow(()->new IllegalArgumentException("报工记录不存在"));
        if(!"VALID".equals(report.get("status")))throw new IllegalStateException("该报工已冲正");
        long orderId=((Number)report.get("manufacturingOrderId")).longValue();
        long operationId=((Number)report.get("manufacturingOrderOperationId")).longValue();
        Integer laterReports=jdbc.queryForObject("""
            SELECT COUNT(*) FROM manufacturing_work_reports r
            JOIN manufacturing_order_operations current_op ON current_op.id=?
            JOIN manufacturing_order_operations later_op ON later_op.id=r.manufacturing_order_operation_id
            WHERE r.manufacturing_order_id=? AND r.status='VALID' AND later_op.sequence_no>current_op.sequence_no
            """,Integer.class,operationId,orderId);
        if(laterReports!=null&&laterReports>0)throw new IllegalStateException("后续工序已经报工，请先冲正后续工序记录");
        jdbc.update("UPDATE manufacturing_work_reports SET status='REVERSED',reversal_reason=? WHERE id=?",reason,reportId);
        recalculate(orderId,operationId);
        audit.log("ManufacturingWorkReport",reportId,String.valueOf(report.get("reportNo")),"REVERSE",reason);
    }

    @Transactional
    public long inspect(long orderId,InspectionRequest request) {
        String result=clean(request.result());if(!Set.of("PASSED","FAILED").contains(result))throw new IllegalArgumentException("检验结果无效");
        var order=jdbc.query("SELECT * FROM manufacturing_orders WHERE id=? FOR UPDATE",(r,n)->row(r),orderId).stream().findFirst().orElseThrow();
        if(!Set.of("PENDING_INSPECTION","PENDING_REINSPECTION").contains(String.valueOf(order.get("status"))))throw new IllegalStateException("当前生产任务不在待检验状态");
        BigDecimal qualified=request.qualifiedQuantity()==null?BigDecimal.ZERO:request.qualifiedQuantity();BigDecimal unqualified=request.unqualifiedQuantity()==null?BigDecimal.ZERO:request.unqualifiedQuantity();
        if(qualified.signum()<0||unqualified.signum()<0||qualified.add(unqualified).signum()<=0)throw new IllegalArgumentException("请填写有效检验数量");
        BigDecimal planned=(BigDecimal)order.get("plannedQuantity");if(qualified.add(unqualified).compareTo(planned)!=0)throw new IllegalArgumentException("第一版检验必须覆盖本任务全部计划数量");
        if("PASSED".equals(result)&&qualified.compareTo(planned)!=0)throw new IllegalArgumentException("检验通过时合格数量必须等于计划数量；存在不合格品请先选择不通过并返工");
        var user=users.current();String tmp="PENDING-"+UUID.randomUUID();LocalDateTime now=LocalDateTime.now();
        jdbc.update("""
            INSERT INTO manufacturing_inspections(inspection_no,manufacturing_order_id,inspection_result,qualified_quantity,
              unqualified_quantity,inspector_employee_id,inspector_name_snapshot,inspected_at,issue_description,remark)
            VALUES(?,?,?,?,?,?,?,?,?,?)
            """,tmp,orderId,result,qualified,unqualified,user.employeeId(),user.name(),now,clean(request.issueDescription()),clean(request.remark()));
        long id=jdbc.queryForObject("SELECT id FROM manufacturing_inspections WHERE inspection_no=?",Long.class,tmp);String no="QI"+String.format("%08d",id);jdbc.update("UPDATE manufacturing_inspections SET inspection_no=? WHERE id=?",no,id);
        String status="PASSED".equals(result)?"PENDING_RECEIPT":"REWORK";
        jdbc.update("UPDATE manufacturing_orders SET status=?,inspection_status=?,inspection_source='MANUAL',inspection_at=?,inspection_by_employee_id=?,inspection_by_name=?,inspection_remark=? WHERE id=?",
                status,result,now,user.employeeId(),user.name(),clean(request.remark()),orderId);
        if("REWORK".equals(status)){
            String requirement=clean(request.reworkRequirement());if(requirement==null)requirement=clean(request.issueDescription());
            if(request.returnOperationId()==null||requirement==null)throw new IllegalArgumentException("检验不通过时请选择返工起始工序并填写返工要求");
            var returnOp=jdbc.query("SELECT id,sequence_no,operation_name_snapshot FROM manufacturing_order_operations WHERE id=? AND manufacturing_order_id=?",(r,n)->row(r),request.returnOperationId(),orderId).stream().findFirst().orElseThrow(()->new IllegalArgumentException("返工起始工序不存在"));
            Integer round=jdbc.queryForObject("SELECT COALESCE(MAX(rework_round),0)+1 FROM manufacturing_reworks WHERE manufacturing_order_id=?",Integer.class,orderId);
            jdbc.update("""
                UPDATE manufacturing_order_operations SET execution_round=execution_round+1,reported_quantity=0,
                  status=CASE WHEN sequence_no=? THEN 'READY' ELSE 'PENDING' END,started_at=NULL,completed_at=NULL
                WHERE manufacturing_order_id=? AND sequence_no>=?
                """,returnOp.get("sequenceNo"),orderId,returnOp.get("sequenceNo"));
            String rt="PENDING-"+UUID.randomUUID();jdbc.update("INSERT INTO manufacturing_reworks(rework_no,manufacturing_order_id,inspection_id,return_operation_id,rework_round,requirement,created_by_employee_id,created_by_name) VALUES(?,?,?,?,?,?,?,?)",rt,orderId,id,request.returnOperationId(),round,requirement,user.employeeId(),user.name());
            long reworkId=jdbc.queryForObject("SELECT id FROM manufacturing_reworks WHERE rework_no=?",Long.class,rt);jdbc.update("UPDATE manufacturing_reworks SET rework_no=? WHERE id=?","RW"+String.format("%08d",reworkId),reworkId);
        }
        audit.log("ManufacturingInspection",id,no,"CREATE",result+"；合格="+qualified+"；不合格="+unqualified);return id;
    }

    private void recalculate(long orderId,long operationId){
        var op=jdbc.queryForMap("SELECT required_quantity,sequence_no,execution_round FROM manufacturing_order_operations WHERE id=?",operationId);
        BigDecimal total=jdbc.queryForObject("SELECT COALESCE(SUM(reported_quantity),0) FROM manufacturing_work_reports WHERE manufacturing_order_operation_id=? AND execution_round=? AND status='VALID'",BigDecimal.class,operationId,op.get("execution_round"));
        BigDecimal required=(BigDecimal)op.get("required_quantity");boolean complete=total.compareTo(required)>=0;
        jdbc.update("UPDATE manufacturing_order_operations SET reported_quantity=?,status=?,started_at=COALESCE(started_at,NOW()),completed_at=? WHERE id=?",total,complete?"COMPLETED":"IN_PROGRESS",complete?LocalDateTime.now():null,operationId);
        if(complete)jdbc.update("UPDATE manufacturing_order_operations SET status='READY' WHERE manufacturing_order_id=? AND sequence_no>? AND status='PENDING' ORDER BY sequence_no LIMIT 1",orderId,op.get("sequence_no"));
        else jdbc.update("UPDATE manufacturing_order_operations SET status='PENDING' WHERE manufacturing_order_id=? AND sequence_no>? AND status='READY'",orderId,op.get("sequence_no"));
        Integer remaining=jdbc.queryForObject("SELECT COUNT(*) FROM manufacturing_order_operations WHERE manufacturing_order_id=? AND required=1 AND status<>'COMPLETED'",Integer.class,orderId);
        boolean allDone=remaining!=null&&remaining==0,needInspection=allDone&&needsInspection(orderId);
        String curStatus=jdbc.queryForObject("SELECT status FROM manufacturing_orders WHERE id=?",String.class,orderId);
        if("REWORK".equals(curStatus)){
            if(allDone){
                if(needInspection)jdbc.update("UPDATE manufacturing_orders SET status='PENDING_REINSPECTION' WHERE id=?",orderId);
                else autoConfirmInspection(orderId,operationId);
                jdbc.update("UPDATE manufacturing_reworks SET status='COMPLETED',completed_at=NOW() WHERE manufacturing_order_id=? AND status='IN_PROGRESS'",orderId);
            }
        } else {
            if(!allDone)jdbc.update("UPDATE manufacturing_orders SET status='IN_PRODUCTION' WHERE id=? AND status IN ('RELEASED','SCHEDULED','PENDING_INSPECTION')",orderId);
            else if(needInspection)jdbc.update("UPDATE manufacturing_orders SET status='PENDING_INSPECTION',inspection_status='PENDING',production_completed_at=NOW() WHERE id=?",orderId);
            else autoConfirmInspection(orderId,operationId);
        }
    }

    /** 工单是否存在"需要人工检验"的工序：由 manufacturing_operations.requires_inspection 单点控制（复用既有列，当前 14 道工序全为 0）。 */
    private boolean needsInspection(long orderId){
        Integer count=jdbc.queryForObject("""
            SELECT COUNT(*) FROM manufacturing_order_operations o
            JOIN manufacturing_operations m ON m.id=o.operation_id
            WHERE o.manufacturing_order_id=? AND o.required=1 AND m.requires_inspection=1
            """,Integer.class,orderId);
        return count!=null&&count>0;
    }

    /**
     * 无人工检验工序时的自动确认放行。口径：自动确认 ≠ 检验合格 ——
     * 不写 manufacturing_inspections（未发生检验事实），inspection_at / inspection_by_* 保持 NULL，
     * 仅以 inspection_source='AUTO' 标记来源，页面与对外表述应为"末道工序已完成"。
     */
    private void autoConfirmInspection(long orderId,long operationId){
        int updated=jdbc.update("""
            UPDATE manufacturing_orders SET status='PENDING_RECEIPT',inspection_status='NOT_REQUIRED',
              inspection_source='AUTO',production_completed_at=NOW()
            WHERE id=? AND status IN ('RELEASED','SCHEDULED','IN_PRODUCTION','REWORK')
            """,orderId);
        if(updated==0)return;
        String orderNo=jdbc.queryForObject("SELECT order_no FROM manufacturing_orders WHERE id=?",String.class,orderId);
        String operationName=jdbc.queryForObject("SELECT operation_name_snapshot FROM manufacturing_order_operations WHERE id=?",String.class,operationId);
        audit.log("ManufacturingOrder",orderId,orderNo,"AUTO_CONFIRM","工序“"+operationName+"”报工完成；本单无人工检验工序，按工序政策自动确认放行至待入库（未执行人工检验）");
    }

    private Map<String,Object> row(java.sql.ResultSet r)throws java.sql.SQLException{var m=new LinkedHashMap<String,Object>();ResultSetMetaData md=r.getMetaData();for(int i=1;i<=md.getColumnCount();i++){Object v=r.getObject(i);if(v instanceof java.sql.Date d)v=d.toLocalDate();else if(v instanceof java.sql.Timestamp t)v=t.toLocalDateTime();m.put(camel(md.getColumnLabel(i)),v);}return m;}
    private static String camel(String s){var b=new StringBuilder();boolean up=false;for(char c:s.toLowerCase().toCharArray()){if(c=='_')up=true;else{b.append(up?Character.toUpperCase(c):c);up=false;}}return b.toString();}
    private static String clean(String s){return s==null||s.isBlank()?null:s.trim();}
    private static String requiredText(String value,String label){String result=clean(value);if(result==null)throw new IllegalArgumentException("请填写"+label);return result;}
    private static String masterCode(String value){String result=requiredText(value,"编码").toUpperCase(Locale.ROOT);if(!result.matches("[A-Z0-9_]{2,50}"))throw new IllegalArgumentException("编码只能包含大写字母、数字和下划线");return result;}
    private static long number(Object value){return value instanceof Number n?n.longValue():0L;}
    private String employeeName(Long id){
        if(id==null)return null;return jdbc.queryForList("SELECT name FROM employees WHERE id=? AND status='ACTIVE'",String.class,id).stream().findFirst().orElseThrow(()->new IllegalArgumentException("负责人不存在或已停用"));
    }
    private Map<String,Object> camelMap(Map<String,Object> source){var result=new LinkedHashMap<String,Object>();source.forEach((k,v)->result.put(camel(k),v));return result;}
}
