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

    /**
     * 任务单配置列表。
     *
     * <p>2026-10-21：删掉了 6 个「建了但没用」的列（{@code workshop_name} 冗余副本、{@code config_code}、
     * {@code product_type}、{@code option_group_code}、{@code sort_order}、{@code process_template_id}），
     * 见迁移 V398。产品的身份就是「{@code workshop_id} 外键 + {@code config_name} 文本」，
     * 车间名一律从 {@code manufacturing_workshops} 关联出来，不再有第二个真相。
     */
    @GetMapping("/task-configs")
    public List<Map<String,Object>> list() {
        // 车间名只有一处来源：manufacturing_workshops（经 workshop_id 关联）。
        // historicalTaskCount 与参考日期匹配同源（都用 w.workshop_name），避免两处口径漂移。
        // ⚠️ historicalTaskCount 是「该车间+该产品名的全部历史任务单」，**不是**参考间隔的样本。
        // 样本只取其中已建跟单的记录（trackedTaskCount），两者量级差一个数量级（2026-10-21：手动片膜 1115 / 103），
        // 只显示前者会让「历史工单 N」看起来像样本量。两列同屏显示，样本口径才不会被误读。
        return jdbc.queryForList("SELECT c.id,c.workshop_id AS workshopId,c.config_name AS configName,"
                +"w.workshop_name AS workshopName,c.config_type AS configType,c.active,"
                +"CASE WHEN c.config_type='PRODUCT_NAME' THEN (SELECT COUNT(*) FROM production_orders p WHERE p.product_name=c.config_name AND p.production_line=w.workshop_name) ELSE NULL END AS historicalTaskCount,"
                +"CASE WHEN c.config_type='PRODUCT_NAME' THEN (SELECT COUNT(*) FROM production_orders p JOIN production_tracking_state t ON t.production_order_id=p.id WHERE p.product_name=c.config_name AND p.production_line=w.workshop_name) ELSE NULL END AS trackedTaskCount,"
                // 同车间同类型同名出现多行时，参考日期匹配不猜（沿用车间基准）——把这个歧义返回给页面，别让它静默失效。
                +"(SELECT COUNT(*) FROM manufacturing_task_config_items d WHERE d.workshop_id<=>c.workshop_id AND d.config_type=c.config_type AND d.config_name=c.config_name) AS sameNameCount "
                +"FROM manufacturing_task_config_items c LEFT JOIN manufacturing_workshops w ON w.id=c.workshop_id "
                +"ORDER BY w.workshop_name,c.config_type,c.id");
    }

    /** 覆盖率：历史任务中匹配到产品配置的张数，以及命中「该产品已发布节点间隔」的张数（后者之外的仍用车间基准）。 */
    @GetMapping("/duration-coverage")
    public Map<String,Object> durationCoverage() {
        return jdbc.queryForMap("SELECT COUNT(*) AS total,"
                // trackedTotal＝已建跟单记录的任务数：参考间隔的真实样本基数（2026-10-21：593 / 6038）。
                +"SUM(EXISTS(SELECT 1 FROM production_tracking_state ts WHERE ts.production_order_id=p.id)) AS trackedTotal,"
                +"SUM(EXISTS(SELECT 1 FROM manufacturing_task_config_items c "
                +"JOIN manufacturing_workshops w ON w.id=c.workshop_id "
                +"WHERE c.config_type='PRODUCT_NAME' "
                +"AND c.active=1 AND c.config_name=p.product_name AND w.workshop_name=p.production_line)) AS productMatched,"
                +"SUM(EXISTS(SELECT 1 FROM manufacturing_task_config_items c "
                +"JOIN manufacturing_workshops w ON w.id=c.workshop_id "
                +"JOIN production_tracking_product_benchmarks b ON b.task_config_id=c.id AND b.typical_days IS NOT NULL "
                +"WHERE c.config_type='PRODUCT_NAME' AND c.active=1 AND c.config_name=p.product_name "
                +"AND w.workshop_name=p.production_line)) AS benchmarkMatched FROM production_orders p");
    }

    /** 任务单配置可改字段：只有名称与启用状态。产品身份＝{@code workshop_id} 外键 + {@code config_name} 文本。 */
    public record Update(String configName, Boolean active) {}

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
        // 不再需要把新名字级联写回 manufacturing_task_config_items：V398 已删掉那儿的 workshop_name 副本，
        // 产品归属车间只有 workshop_id 一个真相，改名自动跟随，漏级联导致静默错配的可能性从根上消除。
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

    public record ConfigCreate(String configName, Long workshopId, String configType, Boolean active) {}

    @PostMapping("/task-configs")
    @Transactional
    public long create(@RequestBody ConfigCreate request) {
        String name=requiredName(request.configName(),"配置名称");
        if(request.workshopId()==null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"请选择生产车间");
        if(!Set.of("PRODUCT_NAME","OPTIONAL_CONFIG").contains(request.configType()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"请选择配置类型");
        var workshops=jdbc.queryForList("SELECT workshop_name FROM manufacturing_workshops WHERE id=? AND enabled=1",request.workshopId());
        if(workshops.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"车间不存在或已停用");
        if(jdbc.queryForObject("SELECT COUNT(*) FROM manufacturing_task_config_items WHERE workshop_id=? AND config_type=? AND config_name=?",Integer.class,request.workshopId(),request.configType(),name)>0)
            throw new ResponseStatusException(HttpStatus.CONFLICT,"该车间已存在同名配置");
        String externalId="MANUAL-"+UUID.randomUUID();
        // 只写身份本身：车间归属走 workshop_id 外键，不再落 config_code / product_type / option_group_code
        // 这些从未被消费的派生列（V398 已删列）。
        jdbc.update("INSERT INTO manufacturing_task_config_items(external_source,external_id,config_name,workshop_id,config_type,active) "
                +"VALUES('MANUAL',?,?,?,?,?)",
                externalId,name,request.workshopId(),request.configType(),request.active()==null||request.active());
        long id=jdbc.queryForObject("SELECT id FROM manufacturing_task_config_items WHERE external_source='MANUAL' AND external_id=?",Long.class,externalId);
        changeLog.write(ErpChangeLogWriter.of(actors.current(),"product_archive.manufacturing_task_config_items",id,"INSERT",
                ErpChangeLogWriter.changes("configName",name,"workshopId",request.workshopId(),
                        "configType",request.configType(),"active",request.active()),
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
        if(jdbc.queryForObject("SELECT COUNT(*) FROM manufacturing_task_config_items WHERE workshop_id=? AND config_type=? AND config_name=? AND id<>?",Integer.class,
                rows.getFirst().get("workshop_id"),rows.getFirst().get("config_type"),request.configName().trim(),id)>0)
            throw new ResponseStatusException(HttpStatus.CONFLICT,"该产线已存在同名配置");
        // 只改名称与启用状态：其余派生/历史列已随 V398 删除，不存在「改名后残留旧值」的问题。
        jdbc.update("UPDATE manufacturing_task_config_items SET config_name=?,active=? WHERE id=?",request.configName().trim(),request.active(),id);
        changeLog.write(ErpChangeLogWriter.of(actors.current(),"product_archive.manufacturing_task_config_items",id,"UPDATE",
                ErpChangeLogWriter.changes("configName",request.configName().trim(),"active",request.active()),
                null,"维护任务单配置",null,null));
    }
    private String requiredName(String value,String label) {
        if(value==null||value.isBlank()||value.trim().length()>200) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"请填写有效"+label);
        return value.trim();
    }
    private String clean(String value){return value==null||value.isBlank()?null:value.trim();}
}
