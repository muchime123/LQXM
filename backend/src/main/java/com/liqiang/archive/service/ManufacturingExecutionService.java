package com.liqiang.archive.service;

import com.liqiang.archive.erp.ErpActor;
import com.liqiang.archive.erp.ErpActorResolver;
import com.liqiang.archive.erp.ErpChangeLogWriter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.ResultSetMetaData;
import java.util.*;

/**
 * 制造主数据管理（工序库 / 制程模板 / 推荐规则）。
 *
 * <p>2026-10-08：生产执行链（排产/报工/冲正/检验/异常/返工等）已整体下线，
 * 本服务仅保留主数据 CRUD，供 {@link com.liqiang.archive.controller.ManufacturingController} 使用。
 */
@Service
public class ManufacturingExecutionService {

    private final JdbcTemplate jdbc;
    private final ErpChangeLogWriter changeLog;
    private final ErpActorResolver actors;

    public ManufacturingExecutionService(JdbcTemplate jdbc,
                                         @Qualifier("archiveAuditWriter") ErpChangeLogWriter changeLog,
                                         ErpActorResolver actors) {
        this.jdbc = jdbc;
        this.changeLog = changeLog;
        this.actors = actors;
    }

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
        boolean creating=(id==null);
        if(id==null){jdbc.update("INSERT INTO manufacturing_process_template_rules(template_id,product_model_id,customization_mode,priority,active,remark) VALUES(?,?,?,?,?,?)",templateId,request.productModelId(),mode,priority,request.active(),clean(request.remark()));id=jdbc.queryForObject("SELECT LAST_INSERT_ID()",Long.class);}
        else{if(jdbc.update("UPDATE manufacturing_process_template_rules SET product_model_id=?,customization_mode=?,priority=?,active=?,remark=? WHERE id=? AND template_id=?",request.productModelId(),mode,priority,request.active(),clean(request.remark()),id,templateId)!=1)throw new IllegalArgumentException("推荐规则不存在");}
        changeLog.write(ErpChangeLogWriter.of(actors.current(),"product_archive.manufacturing_process_template_rules",id,
                creating?"INSERT":"UPDATE",
                ErpChangeLogWriter.changes("templateId",templateId,"productModelId",request.productModelId(),
                        "customizationMode",mode,"priority",priority,"active",request.active(),
                        "remark",clean(request.remark())),
                null,"维护产品制程推荐规则",null,null));
        return id;
    }
    @Transactional public void deleteTemplateRule(long templateId,long id){
        if(jdbc.update("DELETE FROM manufacturing_process_template_rules WHERE id=? AND template_id=?",id,templateId)!=1)throw new IllegalArgumentException("推荐规则不存在");
        changeLog.write(ErpChangeLogWriter.of(actors.current(),"product_archive.manufacturing_process_template_rules",id,"DELETE",
                ErpChangeLogWriter.changes("templateId",templateId),null,"删除产品制程推荐规则",null,null));
    }

    @Transactional public long saveOperation(Long id,OperationMasterRequest request){
        String code=masterCode(request.operationCode()),name=requiredText(request.operationName(),"工序名称"),category=requiredText(request.operationCategory(),"工序分类");
        if(request.standardHours()!=null&&request.standardHours().signum()<0)throw new IllegalArgumentException("标准工时不能小于0");int sort=request.sortOrder()==null?0:request.sortOrder();
        boolean creating=(id==null);
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
        changeLog.write(ErpChangeLogWriter.of(actors.current(),"product_archive.manufacturing_operations",id,
                creating?"INSERT":"UPDATE",
                ErpChangeLogWriter.changes("operationCode",code,"operationName",name,"operationCategory",category,
                        "defaultWorkshop",clean(request.defaultWorkshop()),"standardHours",request.standardHours(),
                        "reportingUnit",clean(request.reportingUnit()),"requiresInspection",request.requiresInspection(),
                        "active",request.active()),
                null,"维护工序主数据",null,null));
        return id;
    }

    @Transactional public long saveTemplate(Long id,TemplateMasterRequest request){
        String code=masterCode(request.templateCode()),name=requiredText(request.templateName(),"模板名称");
        boolean creating=(id==null);
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
        changeLog.write(ErpChangeLogWriter.of(actors.current(),"product_archive.manufacturing_process_templates",id,
                creating?"INSERT":"UPDATE",
                ErpChangeLogWriter.changes("templateCode",code,"templateName",name,
                        "workshopType",clean(request.workshopType()),
                        "applicableProductKeyword",clean(request.applicableProductKeyword()),
                        "active",request.active(),"defaultForWorkshop",request.defaultForWorkshop()),
                null,"维护制程模板",null,null));
        return id;
    }

    @Transactional public void replaceTemplateOperations(long id,List<TemplateOperationRequest> rows){
        var template=jdbc.queryForMap("SELECT template_code FROM manufacturing_process_templates WHERE id=? FOR UPDATE",id);if(rows==null||rows.isEmpty())throw new IllegalArgumentException("制程模板至少需要一道工序");
        var ids=new HashSet<Long>();int finals=0;for(var x:rows){if(x.operationId()==null||!ids.add(x.operationId()))throw new IllegalArgumentException("模板中存在重复或无效工序");if(x.requirementCoefficient()==null||x.requirementCoefficient().signum()<=0)throw new IllegalArgumentException("工序数量系数必须大于0");if(x.finalOperation())finals++;}
        if(finals!=1)throw new IllegalArgumentException("制程模板必须且只能设置一道最终工序");Integer valid=jdbc.queryForObject("SELECT COUNT(*) FROM manufacturing_operations WHERE id IN ("+String.join(",",Collections.nCopies(ids.size(),"?"))+") AND active=1",Integer.class,ids.toArray());if(valid==null||valid!=ids.size())throw new IllegalArgumentException("模板包含不存在或已停用的工序");
        jdbc.update("DELETE FROM manufacturing_process_template_operations WHERE template_id=?",id);int sequence=1;for(var x:rows)jdbc.update("INSERT INTO manufacturing_process_template_operations(template_id,operation_id,sequence_no,requirement_coefficient,required,is_final,allow_parallel,remark) VALUES(?,?,?,?,?,?,?,?)",id,x.operationId(),sequence++,x.requirementCoefficient(),x.required(),x.finalOperation(),x.allowParallel(),clean(x.remark()));
        jdbc.update("UPDATE manufacturing_process_templates SET version=version+1 WHERE id=?",id);
        changeLog.write(ErpChangeLogWriter.of(actors.current(),"product_archive.manufacturing_process_templates",id,"UPDATE",
                ErpChangeLogWriter.changes("templateCode",template.get("template_code"),"operationCount",rows.size()),
                null,"更新模板工序（整组替换）",null,null));
    }

    private Map<String,Object> row(java.sql.ResultSet r)throws java.sql.SQLException{var m=new LinkedHashMap<String,Object>();ResultSetMetaData md=r.getMetaData();for(int i=1;i<=md.getColumnCount();i++){Object v=r.getObject(i);if(v instanceof java.sql.Date d)v=d.toLocalDate();else if(v instanceof java.sql.Timestamp t)v=t.toLocalDateTime();m.put(camel(md.getColumnLabel(i)),v);}return m;}
    private static String camel(String s){var b=new StringBuilder();boolean up=false;for(char c:s.toLowerCase().toCharArray()){if(c=='_')up=true;else{b.append(up?Character.toUpperCase(c):c);up=false;}}return b.toString();}
    private static String clean(String s){return s==null||s.isBlank()?null:s.trim();}
    private static String requiredText(String value,String label){String result=clean(value);if(result==null)throw new IllegalArgumentException("请填写"+label);return result;}
    private static String masterCode(String value){String result=requiredText(value,"编码").toUpperCase(Locale.ROOT);if(!result.matches("[A-Z0-9_]{2,50}"))throw new IllegalArgumentException("编码只能包含大写字母、数字和下划线");return result;}
}
