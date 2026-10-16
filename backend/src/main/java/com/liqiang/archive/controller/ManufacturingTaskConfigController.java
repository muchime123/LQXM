package com.liqiang.archive.controller;

import com.liqiang.archive.erp.ErpActorResolver;
import com.liqiang.archive.erp.ErpChangeLogWriter;
import com.liqiang.archive.security.RequirePermission;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.util.*;

/**
 * 制造域主数据维护（生产车间 / 任务单配置）。
 *
 * <p><b>本类不写 {@code audit_logs}</b>：审计身份走 {@code row_change_log.target_table}
 * （schema 限定表名，物理唯一），不再依赖自由字符串 {@code entity_type}——
 * 后者一个字段要同时表达「哪张表」和「什么业务对象」，两个模块对同名概念用同一个词就会撞。
 * 见 {@code docs/审计实体身份治理与撞名防治-20260921.md}。
 *
 * <p>⚠️ 构造器必须显式书写并带 {@code @Qualifier("archiveAuditWriter")}：
 * 容器里有 {@code archiveAuditWriter} / {@code erpAuditWriter} 两个同类型 bean，
 * 靠 Lombok 生成构造器会丢掉 {@code @Qualifier}（默认不复制到构造参数）→ 注入歧义。
 */
@RestController
@RequestMapping("/api/manufacturing/master")
@RequirePermission("manufacturing.template.manage")
public class ManufacturingTaskConfigController {
    private final JdbcTemplate jdbc;
    private final ErpChangeLogWriter changeLog;
    private final ErpActorResolver actors;

    public ManufacturingTaskConfigController(JdbcTemplate jdbc,
                                             @Qualifier("archiveAuditWriter") ErpChangeLogWriter changeLog,
                                             ErpActorResolver actors) {
        this.jdbc = jdbc;
        this.changeLog = changeLog;
        this.actors = actors;
    }

    @GetMapping("/task-configs")
    public List<Map<String,Object>> list() {
        return jdbc.queryForList("SELECT c.id,c.workshop_id workshopId,c.config_code configCode,c.product_type productType,c.option_group_code optionGroupCode,c.config_name AS configName,c.workshop_name AS workshopName,c.config_type AS configType,c.active,c.process_template_id AS processTemplateId,t.template_name AS processTemplateName FROM manufacturing_task_config_items c LEFT JOIN manufacturing_process_templates t ON t.id=c.process_template_id ORDER BY c.workshop_name,c.config_type,c.id");
    }

    public record Update(String configName, Boolean active, Long processTemplateId) {}

    @GetMapping("/workshops")
    public List<Map<String,Object>> workshops() {
        return jdbc.queryForList("SELECT id,workshop_name AS workshopName,enabled,order_receiver_name AS orderReceiverName,warehouse_clerk_name AS warehouseClerkName,default_process_template_id AS defaultProcessTemplateId FROM manufacturing_workshops ORDER BY enabled DESC,id");
    }

    public record WorkshopUpdate(String workshopName, Boolean enabled, String orderReceiverName,
                                 String warehouseClerkName, Long defaultProcessTemplateId) {}

    @PostMapping("/workshops")
    @Transactional
    public long createWorkshop(@RequestBody WorkshopUpdate request) {
        String name=requiredName(request.workshopName(),"车间名称");
        if(jdbc.queryForObject("SELECT COUNT(*) FROM manufacturing_workshops WHERE workshop_name=?",Integer.class,name)>0)
            throw new ResponseStatusException(HttpStatus.CONFLICT,"车间名称已存在");
        String externalId="MANUAL-"+UUID.randomUUID();
        jdbc.update("INSERT INTO manufacturing_workshops(external_source,external_id,workshop_name,enabled,order_receiver_name,warehouse_clerk_name) VALUES('MANUAL',?,?,?,?,?)",
                externalId,name,request.enabled()==null||request.enabled(),clean(request.orderReceiverName()),clean(request.warehouseClerkName()));
        long id=jdbc.queryForObject("SELECT id FROM manufacturing_workshops WHERE external_source='MANUAL' AND external_id=?",Long.class,externalId);
        changeLog.write(ErpChangeLogWriter.of(actors.current(),"product_archive.manufacturing_workshops",id,"INSERT",
                ErpChangeLogWriter.changes("workshopName",name,"enabled",request.enabled(),
                        "orderReceiverName",clean(request.orderReceiverName()),
                        "warehouseClerkName",clean(request.warehouseClerkName())),
                null,"新增生产车间",null,null));
        return id;
    }

    @PutMapping("/workshops/{id}")
    @Transactional
    public void updateWorkshopMaster(@PathVariable long id,@RequestBody WorkshopUpdate request) {
        var existing=jdbc.queryForList("SELECT workshop_name FROM manufacturing_workshops WHERE id=? FOR UPDATE",id);
        if(existing.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"车间不存在");
        String oldName=String.valueOf(existing.getFirst().get("workshop_name"));
        String name=requiredName(request.workshopName(),"车间名称");
        if(jdbc.queryForObject("SELECT COUNT(*) FROM manufacturing_workshops WHERE workshop_name=? AND id<>?",Integer.class,name,id)>0)
            throw new ResponseStatusException(HttpStatus.CONFLICT,"车间名称已存在");
        jdbc.update("UPDATE manufacturing_workshops SET workshop_name=?,enabled=?,order_receiver_name=?,warehouse_clerk_name=? WHERE id=?",
                name,request.enabled()==null||request.enabled(),clean(request.orderReceiverName()),clean(request.warehouseClerkName()),id);
        jdbc.update("UPDATE manufacturing_task_config_items SET workshop_name=? WHERE workshop_id=?",name,id);
        jdbc.update("UPDATE manufacturing_process_templates SET workshop_type=? WHERE workshop_type=?",name,oldName);
        changeLog.write(ErpChangeLogWriter.of(actors.current(),"product_archive.manufacturing_workshops",id,"UPDATE",
                ErpChangeLogWriter.changes("workshopName",name,"oldWorkshopName",oldName,"enabled",request.enabled(),
                        "orderReceiverName",clean(request.orderReceiverName()),
                        "warehouseClerkName",clean(request.warehouseClerkName())),
                null,"维护生产车间主数据",null,null));
    }

    @PutMapping("/workshops/{id}/default-template")
    @Transactional
    public void updateWorkshop(@PathVariable long id, @RequestBody WorkshopUpdate request) {
        if(request.defaultProcessTemplateId()!=null && jdbc.queryForObject("SELECT COUNT(*) FROM manufacturing_process_templates WHERE id=? AND active=1",Integer.class,request.defaultProcessTemplateId())==0)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"请选择启用的制程模板");
        if(jdbc.update("UPDATE manufacturing_workshops SET default_process_template_id=? WHERE id=?",request.defaultProcessTemplateId(),id)==0)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,"车间不存在");
        changeLog.write(ErpChangeLogWriter.of(actors.current(),"product_archive.manufacturing_workshops",id,"UPDATE",
                ErpChangeLogWriter.changes("defaultProcessTemplateId",request.defaultProcessTemplateId()),
                null,"修改车间默认制程",null,null));
    }

    public record ConfigCreate(String configName, Long workshopId, String configType, Boolean active, Long processTemplateId) {}

    @PostMapping("/task-configs")
    @Transactional
    public long create(@RequestBody ConfigCreate request) {
        String name=requiredName(request.configName(),"配置名称");
        if(request.workshopId()==null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"请选择生产车间");
        if(!Set.of("PRODUCT_NAME","OPTIONAL_CONFIG").contains(request.configType()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"请选择配置类型");
        var workshops=jdbc.queryForList("SELECT workshop_name FROM manufacturing_workshops WHERE id=? AND enabled=1",request.workshopId());
        if(workshops.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"车间不存在或已停用");
        if("OPTIONAL_CONFIG".equals(request.configType()) && request.processTemplateId()!=null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"可选配置不能关联制程");
        validateTemplate(request.processTemplateId());
        if(jdbc.queryForObject("SELECT COUNT(*) FROM manufacturing_task_config_items WHERE workshop_id=? AND config_type=? AND config_name=?",Integer.class,request.workshopId(),request.configType(),name)>0)
            throw new ResponseStatusException(HttpStatus.CONFLICT,"该车间已存在同名配置");
        String externalId="MANUAL-"+UUID.randomUUID();
        String code=("PRODUCT_NAME".equals(request.configType())?"BP-":"OPT-")+UUID.randomUUID();
        String type="PRODUCT_NAME".equals(request.configType())?(name.equals("配件")?"PART":name.equals("模具")||name.endsWith("模具")?"MOLD":"MACHINE"):null;
        jdbc.update("INSERT INTO manufacturing_task_config_items(external_source,external_id,config_name,workshop_name,workshop_id,config_type,active,process_template_id,config_code,product_type,option_group_code) VALUES('MANUAL',?,?,?,?,?,?,?,?,?,?)",
                externalId,name,workshops.getFirst().get("workshop_name"),request.workshopId(),request.configType(),request.active()==null||request.active(),request.processTemplateId(),code,type,type==null?"UNCLASSIFIED":null);
        long id=jdbc.queryForObject("SELECT id FROM manufacturing_task_config_items WHERE external_source='MANUAL' AND external_id=?",Long.class,externalId);
        changeLog.write(ErpChangeLogWriter.of(actors.current(),"product_archive.manufacturing_task_config_items",id,"INSERT",
                ErpChangeLogWriter.changes("configName",name,"workshopId",request.workshopId(),
                        "configType",request.configType(),"active",request.active(),
                        "processTemplateId",request.processTemplateId()),
                null,"新增任务单配置",null,null));
        return id;
    }

    @PutMapping("/task-configs/{id}")
    @Transactional
    public void update(@PathVariable long id, @RequestBody Update request) {
        var rows = jdbc.queryForList("SELECT config_type,workshop_id FROM manufacturing_task_config_items WHERE id=? FOR UPDATE",id);
        if(rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"配置不存在");
        if(request.configName()==null || request.configName().isBlank() || request.configName().trim().length()>200 || request.active()==null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"请填写有效名称和启用状态");
        if(request.processTemplateId()!=null) {
            if(!"PRODUCT_NAME".equals(rows.getFirst().get("config_type")))
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"只有产品名称可以关联制程");
            validateTemplate(request.processTemplateId());
        }
        if(jdbc.queryForObject("SELECT COUNT(*) FROM manufacturing_task_config_items WHERE workshop_id=? AND config_type=? AND config_name=? AND id<>?",Integer.class,
                rows.getFirst().get("workshop_id"),rows.getFirst().get("config_type"),request.configName().trim(),id)>0)
            throw new ResponseStatusException(HttpStatus.CONFLICT,"该产线已存在同名配置");
        jdbc.update("UPDATE manufacturing_task_config_items SET config_name=?,active=?,process_template_id=? WHERE id=?",request.configName().trim(),request.active(),request.processTemplateId(),id);
        changeLog.write(ErpChangeLogWriter.of(actors.current(),"product_archive.manufacturing_task_config_items",id,"UPDATE",
                ErpChangeLogWriter.changes("configName",request.configName().trim(),"active",request.active(),
                        "processTemplateId",request.processTemplateId()),
                null,"维护任务单配置",null,null));
    }

    private void validateTemplate(Long id) {
        if(id!=null && jdbc.queryForObject("SELECT COUNT(*) FROM manufacturing_process_templates WHERE id=? AND active=1",Integer.class,id)==0)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"请选择启用的制程模板");
    }
    private String requiredName(String value,String label) {
        if(value==null||value.isBlank()||value.trim().length()>200) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"请填写有效"+label);
        return value.trim();
    }
    private String clean(String value){return value==null||value.isBlank()?null:value.trim();}
}
