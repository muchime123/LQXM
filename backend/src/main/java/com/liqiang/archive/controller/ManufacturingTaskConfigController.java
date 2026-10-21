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
                +"w.workshop_name AS workshopName,c.config_type AS configType,c.external_source AS externalSource,"
                +"CASE WHEN c.config_type='PRODUCT_NAME' THEN (SELECT COUNT(*) FROM production_orders p WHERE p.product_name=c.config_name AND p.production_line=w.workshop_name) ELSE NULL END AS historicalTaskCount,"
                +"CASE WHEN c.config_type='PRODUCT_NAME' THEN (SELECT COUNT(*) FROM production_orders p JOIN production_tracking_state t ON t.production_order_id=p.id WHERE p.product_name=c.config_name AND p.production_line=w.workshop_name) ELSE NULL END AS trackedTaskCount,"
                // 同车间同类型同名出现多行时，参考日期匹配不猜（沿用车间基准）——把这个歧义返回给页面，别让它静默失效。
                +"(SELECT COUNT(*) FROM manufacturing_task_config_items d WHERE d.workshop_id<=>c.workshop_id AND d.config_type=c.config_type AND d.config_name=c.config_name) AS sameNameCount "
                +"FROM manufacturing_task_config_items c LEFT JOIN manufacturing_workshops w ON w.id=c.workshop_id "
                +"ORDER BY w.workshop_name,c.config_type,c.id");
    }

    /**
     * 覆盖率：历史任务中匹配到产品配置的张数，以及命中「该产品已发布节点间隔」的张数（后者之外的仍用车间基准）。
     *
     * <p><b>2026-10-21 起不再判 {@code active}</b>：任务单产品是氚云生产任务单产品下拉字典的镜像，
     * 产品能否下单由氚云决定，LQXM 的本地开关既不影响氚云、也不该让本地跟单匹配凭空失效
     * （否则「氚云正常在用 + 本地被停用」= 产品级参考工期静默退回车间基准）。全库 50 个产品实测全为启用，
     * 去掉判据后覆盖率数字不变。
     */
    @GetMapping("/duration-coverage")
    public Map<String,Object> durationCoverage() {
        return jdbc.queryForMap("SELECT COUNT(*) AS total,"
                // trackedTotal＝已建跟单记录的任务数：参考间隔的真实样本基数（2026-10-21：593 / 6038）。
                +"SUM(EXISTS(SELECT 1 FROM production_tracking_state ts WHERE ts.production_order_id=p.id)) AS trackedTotal,"
                +"SUM(EXISTS(SELECT 1 FROM manufacturing_task_config_items c "
                +"JOIN manufacturing_workshops w ON w.id=c.workshop_id "
                +"WHERE c.config_type='PRODUCT_NAME' "
                +"AND c.config_name=p.product_name AND w.workshop_name=p.production_line)) AS productMatched,"
                +"SUM(EXISTS(SELECT 1 FROM manufacturing_task_config_items c "
                +"JOIN manufacturing_workshops w ON w.id=c.workshop_id "
                +"JOIN production_tracking_product_benchmarks b ON b.task_config_id=c.id AND b.typical_days IS NOT NULL "
                +"WHERE c.config_type='PRODUCT_NAME' AND c.config_name=p.product_name "
                +"AND w.workshop_name=p.production_line)) AS benchmarkMatched FROM production_orders p");
    }

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

    /**
     * 新增配置入参。
     *
     * <p><b>没有 {@code active}</b>：产品与可选配置都是氚云生产任务单下拉字典的镜像
     * （分别对应「产品名称」「可选配置」两个下拉项），能不能选由氚云决定。本地留着开关只会
     * 出现「氚云照常在用、这边被停用」的假状态，而 LQXM 里没有任何地方会因为这个开关而
     * 让氚云真的关掉它（2026-10-21 LQ 定）。
     */
    public record ConfigCreate(String configName, Long workshopId, String configType) {}

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
        // 这些从未被消费的派生列（V398 已删列）；active 也已随 V400 删除（两类配置都没有启用概念）。
        jdbc.update("INSERT INTO manufacturing_task_config_items(external_source,external_id,config_name,workshop_id,config_type) "
                +"VALUES('MANUAL',?,?,?,?)",
                externalId,name,request.workshopId(),request.configType());
        long id=jdbc.queryForObject("SELECT id FROM manufacturing_task_config_items WHERE external_source='MANUAL' AND external_id=?",Long.class,externalId);
        changeLog.write(ErpChangeLogWriter.of(actors.current(),"product_archive.manufacturing_task_config_items",id,"INSERT",
                ErpChangeLogWriter.changes("configName",name,"workshopId",request.workshopId(),
                        "configType",request.configType()),
                null,"新增任务单配置",null,null));
        return id;
    }

    /*
     * 刻意<b>没有</b> PUT /task-configs/{id}（2026-10-21 删除）。
     *
     * 配置名称就是氚云生产任务单下拉项的镜像（产品名称 / 可选配置），本地改名不会同步氚云：
     * 产品改名的直接后果是 {@code production_orders.product_name} 全量失配——产品级参考间隔静默退回
     * 车间基准、覆盖率下降，而氚云那边照旧叫原名；可选配置改名则让历史任务的
     * {@code optional_configuration} 文本与本字典脱钩（按需工序判定认不出「氧化 / 木箱」这类项）。
     * 两者都是「本地抄错一个字 → 全部历史静默失效」，而纠正一条抄错的行只需删掉重录，
     * 所以统一只留「补录 + 删除」两条写入路径。
     */


    /**
     * 删除本地补录的配置行（任务单产品 / 可选配置都走这里）。
     *
     * <p><b>为什么必须有它</b>：名称只读之后只剩「补录」一条写入路径，而「启用/停用」取消后
     * 也没有「停用即隐藏」的遮蔽手段——名字敲错的行如果既不能改也不能删，就会永久留在字典里，
     * 一边污染下拉清单、一边参与文本匹配（产品→参考间隔；可选配置→按需工序判定）。纠正路径只能是删除。
     *
     * <p><b>硬保护</b>（宁可拒绝，也不静默丢数据）：
     * <ul>
     *   <li>产品：已有 {@code production_tracking_product_benchmarks} 行（含只有候选统计、尚未发布的）→ 拒绝，
     *       删掉会连同已发布的参考间隔一起永久消失，而「撤销发布」是单独动作、要留审计；</li>
     *   <li>产品：该车间 + 该产品名能匹配到历史任务单 → 拒绝，说明它在氚云真被用过，是真实产品而非本地产物；</li>
     *   <li>可选配置：作为独立一项出现在该车间历史任务的 {@code optional_configuration} 文本里 → 拒绝，
     *       同理（2026-10-21 实测 70 条里 67 条真被用过）。</li>
     * </ul>
     * 都不占的行必然是补录错了的，删掉零损失。
     */
    @DeleteMapping("/task-configs/{id}")
    @Transactional
    public void deleteConfig(@PathVariable long id) {
        var rows=jdbc.queryForList("SELECT config_name,config_type,workshop_id FROM manufacturing_task_config_items WHERE id=? FOR UPDATE",id);
        if(rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"配置不存在");
        var row=rows.getFirst();
        String name=String.valueOf(row.get("config_name"));
        Object workshopId=row.get("workshop_id");
        if("PRODUCT_NAME".equals(row.get("config_type"))){
            Integer benchmarks=jdbc.queryForObject("SELECT COUNT(*) FROM production_tracking_product_benchmarks WHERE task_config_id=?",Integer.class,id);
            if(benchmarks!=null&&benchmarks>0)
                throw new ResponseStatusException(HttpStatus.CONFLICT,"该产品已有节点参考间隔记录（含候选统计），删除会永久丢失已发布基准，请先撤销发布");
            Integer orders=jdbc.queryForObject("SELECT COUNT(*) FROM production_orders p JOIN manufacturing_workshops w ON w.id=? "
                    +"WHERE p.product_name=? AND w.workshop_name=p.production_line",Integer.class,workshopId,name);
            if(orders!=null&&orders>0)
                throw new ResponseStatusException(HttpStatus.CONFLICT,"该产品已匹配 "+orders+" 张历史任务单，是氚云在用的真实产品，不能删除");
        }else{
            // 历史任务的「可选配置」是分号分隔的自由文本（如「出口木箱;易撕口」），没有外键。
            // 用 LOCATE 而不是 LIKE：配置名里带括号、下划线很常见，LIKE 会把 _ 当通配符放宽匹配。
            Integer used=jdbc.queryForObject("SELECT COUNT(*) FROM production_orders p JOIN manufacturing_workshops w ON w.id=? "
                    +"WHERE p.production_line=w.workshop_name "
                    +"AND LOCATE(CONCAT(';',?,';'),CONCAT(';',REPLACE(REPLACE(p.optional_configuration,' ',''),'；',';'),';'))>0",
                    Integer.class,workshopId,name);
            if(used!=null&&used>0)
                throw new ResponseStatusException(HttpStatus.CONFLICT,"该可选配置已出现在 "+used+" 张历史任务单的「可选配置」里，是氚云在用的真实选项，不能删除");
        }
        jdbc.update("DELETE FROM manufacturing_task_config_items WHERE id=?",id);
        changeLog.write(ErpChangeLogWriter.of(actors.current(),"product_archive.manufacturing_task_config_items",id,"DELETE",
                ErpChangeLogWriter.changes("configName",name,"configType",row.get("config_type"),"workshopId",workshopId),
                null,"删除本地补录的配置（无历史使用、无参考间隔）",null,null));
    }
    private String requiredName(String value,String label) {
        if(value==null||value.isBlank()||value.trim().length()>200) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"请填写有效"+label);
        return value.trim();
    }
    private String clean(String value){return value==null||value.isBlank()?null:value.trim();}
}
