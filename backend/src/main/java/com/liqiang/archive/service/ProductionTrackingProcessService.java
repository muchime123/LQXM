package com.liqiang.archive.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** 产品模板是可发布的定义；任务快照是不可被源单重导或模板编辑覆盖的执行依据。 */
@Service
public class ProductionTrackingProcessService {
    public record Node(String code, int sortNo, boolean conditional) {}
    public record Edge(String from, String to) {}
    public record Draft(List<Node> nodes, List<Edge> edges, String note) {}
    public record Revision(int expectedRevision,List<Node> nodes,List<Edge> edges,
                           Map<String,String> applicability,String reason) {}
    public record LegacyBatch(List<Long> orderIds,String reason) {}

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper=new ObjectMapper();
    public ProductionTrackingProcessService(JdbcTemplate jdbc) {
        this.jdbc=jdbc;
    }

    public List<Map<String,Object>> versions(long productConfigId) {
        return jdbc.queryForList("SELECT id,version_no AS versionNo,workshop_kind AS workshopKind,state,note,"
                +"published_at AS publishedAt,published_by AS publishedBy FROM production_tracking_process_templates "
                +"WHERE product_config_id=? ORDER BY version_no DESC",productConfigId);
    }

    public Map<String,Object> version(long templateId) {
        var result=new LinkedHashMap<String,Object>(jdbc.queryForMap("SELECT id,product_config_id AS productConfigId,"
                +"workshop_kind AS workshopKind,version_no AS versionNo,state,note,published_at AS publishedAt "
                +"FROM production_tracking_process_templates WHERE id=?",templateId));
        result.put("nodes",jdbc.queryForList("SELECT stage_code AS code,stage_label AS label,sort_no AS sortNo,conditional,"
                +"match_keywords AS matchKeywords,exclude_keywords AS excludeKeywords "
                +"FROM production_tracking_process_template_nodes WHERE template_id=? ORDER BY sort_no,stage_code",templateId));
        result.put("edges",jdbc.queryForList("SELECT predecessor_code AS predecessor,successor_code AS successor "
                +"FROM production_tracking_process_template_edges WHERE template_id=?",templateId));
        return result;
    }

    /** 同一已发布产品制程版本的只读里程碑间隔报告；不把并行节点当加工工时。 */
    public Map<String,Object> versionDurationSamples(long templateId) {
        var template=version(templateId);
        @SuppressWarnings("unchecked")
        var nodes=(List<Map<String,Object>>)template.get("nodes");
        @SuppressWarnings("unchecked")
        var edges=(List<Map<String,Object>>)template.get("edges");
        var ordered=nodes.stream().map(node->(String)node.get("code")).toList();
        var expectedEdges=new HashSet<String>();
        for(int i=1;i<ordered.size();i++)expectedEdges.add(ordered.get(i-1)+"→"+ordered.get(i));
        var actualEdges=new HashSet<String>();
        for(var edge:edges)actualEdges.add(edge.get("predecessor")+"→"+edge.get("successor"));
        boolean linear=expectedEdges.equals(actualEdges)&&edges.size()==expectedEdges.size();
        var rows=jdbc.queryForList("SELECT p.id AS order_id,DATE(p.schedule_time) AS anchor_date,"
                +"n.stage_code,n.applicability,m.stage_status,m.completed_date "
                +"FROM production_tracking_process_snapshots s "
                +"JOIN production_orders p ON p.id=s.production_order_id "
                +"JOIN production_tracking_process_snapshot_nodes n ON n.production_order_id=s.production_order_id "
                +"LEFT JOIN production_tracking_milestones m ON m.production_order_id=n.production_order_id "
                +"AND m.stage_code=n.stage_code "
                +"LEFT JOIN production_tracking_state t ON t.production_order_id=p.id "
                +"WHERE s.template_id=? AND s.source_kind='PUBLISHED' AND t.stopped_at IS NULL "
                +"AND (p.status IS NULL OR p.status NOT IN ('CANCELLED','CANCELED','已取消','取消','已作废','作废')) "
                +"ORDER BY p.id,n.sort_no,n.stage_code",templateId);
        var byOrder=new LinkedHashMap<Long,List<Map<String,Object>>>();
        for(var row:rows)byOrder.computeIfAbsent(((Number)row.get("order_id")).longValue(),key->new ArrayList<>()).add(row);
        var result=new LinkedHashMap<String,Object>();
        result.put("templateId",templateId);
        result.put("templateVersion",template.get("versionNo"));
        result.put("matchedOrders",byOrder.size());
        result.put("linear",linear);
        result.put("metric","历史节点完成间隔（自然日），非加工工时");
        if(!linear){result.put("reason","存在并行或未定义的依赖边，暂不计算线性节点 P50/P75");return result;}
        var samples=new LinkedHashMap<String,List<Long>>();
        var excluded=new LinkedHashMap<String,Map<String,Integer>>();
        for(String code:ordered){samples.put(code,new ArrayList<>());excluded.put(code,new HashMap<>());}
        var whole=new ArrayList<Long>();
        var wholeExcluded=new HashMap<String,Integer>();
        LocalDate today=LocalDate.now();
        for(var orderRows:byOrder.values()){
            LocalDate anchor=date(orderRows.getFirst().get("anchor_date"));
            LocalDate previous=anchor;
            boolean allDone=true;
            LocalDate lastDate=null;
            for(var row:orderRows){
                String code=(String)row.get("stage_code");
                if("NOT_APPLICABLE".equals(row.get("applicability")))continue;
                if("PENDING_REVIEW".equals(row.get("applicability"))){
                    allDone=false;
                    excluded.get(code).merge("APPLICABILITY_UNCONFIRMED",1,Integer::sum);
                    previous=null;
                    continue;
                }
                if(!"DONE".equals(row.get("stage_status")))allDone=false;
                LocalDate completed=date(row.get("completed_date"));
                String reason=null;
                if(completed==null)reason="MISSING_DATE";
                else if(previous==null)reason=anchor==null?"NO_ANCHOR":"PREVIOUS_DATE_MISSING";
                else{
                    long days=ChronoUnit.DAYS.between(previous,completed);
                    if(days<0)reason="REVERSED_DATE";
                    else if(days>60)reason="OVER_60_DAYS";
                    else if(completed.isAfter(today))reason="FUTURE_DATE";
                    else samples.get(code).add(days);
                }
                if(reason!=null)excluded.get(code).merge(reason,1,Integer::sum);
                previous=completed;
                lastDate=completed;
            }
            String wholeReason=null;
            if(!allDone)wholeReason="NOT_ALL_DONE";
            else if(anchor==null)wholeReason="NO_ANCHOR";
            else if(lastDate==null)wholeReason="MISSING_FINISH_DATE";
            else{
                long days=ChronoUnit.DAYS.between(anchor,lastDate);
                if(days<0)wholeReason="REVERSED_DATE";
                else if(days>365)wholeReason="OVER_365_DAYS";
                else if(lastDate.isAfter(today))wholeReason="FUTURE_DATE";
                else whole.add(days);
            }
            if(wholeReason!=null)wholeExcluded.merge(wholeReason,1,Integer::sum);
        }
        var stageResults=new ArrayList<Map<String,Object>>();
        for(String code:ordered)stageResults.add(durationResult(code,samples.get(code),excluded.get(code)));
        result.put("stages",stageResults);
        result.put("whole",durationResult("WHOLE",whole,wholeExcluded));
        return result;
    }

    private static LocalDate date(Object value) {
        if(value instanceof java.sql.Date d)return d.toLocalDate();
        if(value instanceof LocalDate d)return d;
        return null;
    }

    private static Map<String,Object> durationResult(String code,List<Long> values,Map<String,Integer> excluded) {
        values.sort(Long::compareTo);
        var result=new LinkedHashMap<String,Object>();
        result.put("code",code);result.put("sampleCount",values.size());
        result.put("p50Days",values.isEmpty()?null:ProductionTrackingService.percentile(values,0.5));
        result.put("p75Days",values.isEmpty()?null:ProductionTrackingService.percentile(values,0.75));
        result.put("excluded",excluded);
        return result;
    }

    public Map<String,Object> snapshot(long orderId) {
        var rows=jdbc.queryForList("SELECT production_order_id AS orderId,template_id AS templateId,"
                +"template_version AS templateVersion,source_kind AS sourceKind,revision_no AS revisionNo "
                +"FROM production_tracking_process_snapshots WHERE production_order_id=?",orderId);
        if(rows.isEmpty())return Map.of("orderId",orderId,"sourceKind","NOT_CAPTURED", "nodes",List.of(),"edges",List.of());
        var result=new LinkedHashMap<String,Object>(rows.getFirst());
        result.put("nodes",jdbc.queryForList("SELECT stage_code AS code,stage_label AS label,sort_no AS sortNo,"
                +"conditional,applicability,applicability_reason AS applicabilityReason "
                +"FROM production_tracking_process_snapshot_nodes WHERE production_order_id=? "
                +"ORDER BY sort_no,stage_code",orderId));
        result.put("edges",jdbc.queryForList("SELECT predecessor_code AS predecessor,successor_code AS successor "
                +"FROM production_tracking_process_snapshot_edges WHERE production_order_id=?",orderId));
        return result;
    }

    /** 旧任务兼容快照的只读预览；仅用于逐批核实，不套用现行产品模板。 */
    public Map<String,Object> legacyPreview(int requestedLimit) {
        int limit=Math.max(1,Math.min(requestedLimit,500));
        long missing=jdbc.queryForObject("SELECT COUNT(*) FROM production_orders p "
                +"LEFT JOIN production_tracking_process_snapshots s ON s.production_order_id=p.id "
                +"WHERE s.production_order_id IS NULL",Long.class);
        var rows=jdbc.queryForList("SELECT p.id AS orderId,p.work_order_no AS orderNo,"
                +"p.product_name AS productName,p.production_line AS workshop,"
                +"COUNT(DISTINCT m.stage_code) AS recordedNodes,"
                +"SUM(CASE WHEN m.stage_status='DONE' THEN 1 ELSE 0 END) AS doneNodes,"
                +"MAX(CASE WHEN t.closed_at IS NOT NULL THEN 1 ELSE 0 END) AS closed "
                +"FROM production_orders p "
                +"LEFT JOIN production_tracking_process_snapshots s ON s.production_order_id=p.id "
                +"LEFT JOIN production_tracking_milestones m ON m.production_order_id=p.id "
                +"LEFT JOIN production_tracking_state t ON t.production_order_id=p.id "
                +"WHERE s.production_order_id IS NULL GROUP BY p.id,p.work_order_no,p.product_name,p.production_line "
                +"ORDER BY p.id LIMIT ?",limit);
        return Map.of("missingCount",missing,"limit",limit,"items",rows);
    }

    /** 单单迁移前核对：保留原节点及已登记节点，列出结案后仍未确认的节点。 */
    public Map<String,Object> legacyOrderPreview(long orderId) {
        var order=jdbc.queryForMap("SELECT p.work_order_no,p.product_name,p.production_line,"
                +"p.optional_configuration,p.production_requirement,t.closed_at "
                +"FROM production_orders p LEFT JOIN production_tracking_state t ON t.production_order_id=p.id "
                +"WHERE p.id=?",orderId);
        if(jdbc.queryForObject("SELECT COUNT(*) FROM production_tracking_process_snapshots WHERE production_order_id=?",
                Integer.class,orderId)>0)
            throw new IllegalArgumentException("任务已有制程快照，请查看当前快照");
        String kind=ProductionTrackingPolicy.isSealer((String)order.get("production_line"))?"SEALER":"MACHINE";
        var nodes=legacyNodes(orderId,kind);
        var recorded=jdbc.queryForList("SELECT stage_code,stage_status,completed_date,completion_source,applicability "
                +"FROM production_tracking_milestones WHERE production_order_id=?",orderId);
        var byCode=new HashMap<String,Map<String,Object>>();
        for(var row:recorded)byCode.put((String)row.get("stage_code"),row);
        var preview=new ArrayList<Map<String,Object>>();
        int unresolved=0;
        for(var node:nodes){
            String code=(String)node.get("stage_code");
            boolean conditional=flag(node.get("conditional"));
            String applicability=conditional?"NOT_APPLICABLE":"APPLICABLE";
            if(conditional&&(indicated(node,(String)order.get("optional_configuration"))
                    ||indicated(node,(String)order.get("production_requirement"))||byCode.containsKey(code)))
                applicability="APPLICABLE";
            var execution=byCode.get(code);
            if(execution!=null&&execution.get("applicability")!=null)
                applicability=(String)execution.get("applicability");
            boolean needsReview=order.get("closed_at")!=null&&"APPLICABLE".equals(applicability)
                    &&(execution==null||!"DONE".equals(execution.get("stage_status")));
            if(needsReview)unresolved++;
            var item=new LinkedHashMap<String,Object>();
            item.put("code",code);item.put("label",node.get("stage_label"));
            item.put("conditional",conditional);item.put("applicability",applicability);
            item.put("status",execution==null?"PENDING":execution.get("stage_status"));
            item.put("completedDate",execution==null?null:execution.get("completed_date"));
            item.put("completionSource",execution==null?null:execution.get("completion_source"));
            item.put("needsReview",needsReview);
            preview.add(item);
        }
        var result=new LinkedHashMap<String,Object>();
        result.put("orderId",orderId);result.put("orderNo",order.get("work_order_no"));
        result.put("productName",order.get("product_name"));
        result.put("workshop",order.get("production_line"));
        result.put("closed",order.get("closed_at")!=null);
        result.put("unresolvedNodes",unresolved);result.put("nodes",preview);
        return result;
    }

    /** 明确调整本单流程；已执行节点不可从快照删除，前后内容作为一条事务内审计保存。 */
    @Transactional
    public int revise(long orderId,Revision request,String actor) {
        if(request==null||request.reason()==null||request.reason().isBlank()
                ||request.reason().length()>500)throw new IllegalArgumentException("调整本单制程需填写 1–500 字原因");
        var locked=jdbc.queryForList("SELECT revision_no FROM production_tracking_process_snapshots "
                +"WHERE production_order_id=? FOR UPDATE",orderId);
        if(locked.size()!=1)throw new IllegalArgumentException("该任务尚无制程快照，请先建立旧制程兼容快照");
        int revision=((Number)locked.getFirst().get("revision_no")).intValue();
        if(request.expectedRevision()!=revision)throw new IllegalArgumentException("本单制程已更新，请刷新后重试");
        var before=snapshot(orderId);
        var oldCodes=new HashSet<String>();
        @SuppressWarnings("unchecked")
        var oldNodes=(List<Map<String,Object>>)before.get("nodes");
        for(var node:oldNodes)oldCodes.add((String)node.get("code"));
        var order=jdbc.queryForMap("SELECT production_line FROM production_orders WHERE id=?",orderId);
        String kind=ProductionTrackingPolicy.isSealer((String)order.get("production_line"))?"SEALER":"MACHINE";
        var allowed=new HashSet<>(oldCodes);
        allowed.addAll(jdbc.query("SELECT stage_code FROM production_tracking_stage_dictionary",
                (r,n)->r.getString(1)));
        validate(new Draft(request.nodes(),request.edges(),request.reason()),allowed);
        var newCodes=new HashSet<String>();
        for(var node:request.nodes())newCodes.add(node.code());
        for(var old:oldCodes)if(!newCodes.contains(old)&&jdbc.queryForObject(
                "SELECT COUNT(*) FROM production_tracking_milestones WHERE production_order_id=? AND stage_code=?",
                Integer.class,orderId,old)>0)
            throw new IllegalArgumentException("节点 "+old+" 已有执行记录，不可从本单删除");
        var defs=jdbc.queryForList("SELECT stage_code,stage_label FROM production_tracking_stage_dictionary");
        var labels=new HashMap<String,String>();
        for(var def:defs)labels.put((String)def.get("stage_code"),(String)def.get("stage_label"));
        for(var node:oldNodes)labels.putIfAbsent((String)node.get("code"),(String)node.get("label"));
        jdbc.update("DELETE FROM production_tracking_process_snapshot_edges WHERE production_order_id=?",orderId);
        jdbc.update("DELETE FROM production_tracking_process_snapshot_nodes WHERE production_order_id=?",orderId);
        for(var node:request.nodes()){
            String applicability=request.applicability()==null?null:request.applicability().get(node.code());
            if(applicability==null){
                applicability=oldNodes.stream().filter(row->node.code().equals(row.get("code")))
                        .map(row->(String)row.get("applicability")).findFirst()
                        .orElse(node.conditional()?"PENDING_REVIEW":"APPLICABLE");
            }
            if(!Set.of("APPLICABLE","NOT_APPLICABLE","PENDING_REVIEW").contains(applicability))
                throw new IllegalArgumentException("节点适用性无效："+node.code());
            if("NOT_APPLICABLE".equals(applicability)&&jdbc.queryForObject(
                    "SELECT COUNT(*) FROM production_tracking_milestones WHERE production_order_id=? "
                    +"AND stage_code=? AND stage_status='DONE'",Integer.class,orderId,node.code())>0)
                throw new IllegalArgumentException("已完成节点不能直接标为不适用："+node.code());
            jdbc.update("INSERT INTO production_tracking_process_snapshot_nodes"
                    +"(production_order_id,stage_code,stage_label,sort_no,conditional,applicability,applicability_reason)"
                    +" VALUES(?,?,?,?,?,?,?)",orderId,node.code(),labels.get(node.code()),node.sortNo(),node.conditional()?1:0,
                    applicability,request.reason());
        }
        for(var edge:request.edges()==null?List.<Edge>of():request.edges())
            jdbc.update("INSERT INTO production_tracking_process_snapshot_edges"
                    +"(production_order_id,predecessor_code,successor_code) VALUES(?,?,?)",
                    orderId,edge.from(),edge.to());
        jdbc.update("UPDATE production_tracking_process_snapshots SET source_kind='MANUAL',revision_no=? "
                +"WHERE production_order_id=?",revision+1,orderId);
        jdbc.update("INSERT INTO production_tracking_process_changes"
                +"(production_order_id,revision_no,before_json,after_json,reason,changed_by) "
                +"VALUES(?,?,?,?,?,?)",orderId,revision+1,json(before),json(snapshot(orderId)),request.reason(),actor);
        return revision+1;
    }

    private String json(Object value) {
        try{return mapper.writeValueAsString(value);}
        catch(JsonProcessingException e){throw new IllegalStateException("制程快照审计序列化失败",e);}
    }

    @Transactional
    public long createDraft(long productConfigId,Draft draft,String actor) {
        var products=jdbc.queryForList("SELECT c.id,w.workshop_name FROM manufacturing_task_config_items c "
                +"JOIN manufacturing_workshops w ON w.id=c.workshop_id "
                +"WHERE c.id=? AND c.config_type='PRODUCT_NAME'",productConfigId);
        if(products.size()!=1)throw new IllegalArgumentException("请选择有效的任务单产品配置");
        String workshop=(String)products.getFirst().get("workshop_name");
        String kind=ProductionTrackingPolicy.isSealer(workshop)?"SEALER":"MACHINE";
        var definitions=jdbc.queryForList("SELECT g.stage_code,COALESCE(d.stage_label,g.stage_label) AS stage_label,"
                +"COALESCE(d.match_keywords,g.match_keywords) AS match_keywords,"
                +"COALESCE(d.exclude_keywords,g.exclude_keywords) AS exclude_keywords,"
                +"g.optional,g.header_aliases FROM production_tracking_stage_dictionary g "
                +"LEFT JOIN production_tracking_stage_defs d ON d.stage_code=g.stage_code "
                +"AND d.workshop_kind=? AND d.enabled=1",kind);
        var byCode=new HashMap<String,Map<String,Object>>();
        for(var row:definitions)byCode.put((String)row.get("stage_code"),row);
        validate(draft,byCode.keySet());
        // A product may select any global dictionary node. The per-kind row is
        // only a compatibility binding for labels and reference-day queries;
        // it is never added to the workshop fallback flow here.
        for(var node:draft.nodes()){
            var def=byCode.get(node.code());
            int nextSort=jdbc.queryForObject("SELECT COALESCE(MAX(sort_no),0)+1 FROM production_tracking_stage_defs "
                    +"WHERE workshop_kind=?",Integer.class,kind);
            jdbc.update("INSERT IGNORE INTO production_tracking_stage_defs"
                    +"(workshop_kind,stage_code,stage_label,sort_no,optional,match_keywords,"
                    +"exclude_keywords,header_aliases,enabled,default_included) "
                    +"VALUES(?,?,?,?,?,?,?,?,1,0)",kind,node.code(),def.get("stage_label"),nextSort,
                    flag(def.get("optional"))?1:0,def.get("match_keywords"),
                    def.get("exclude_keywords"),def.get("header_aliases"));
        }
        int next=jdbc.queryForObject("SELECT COALESCE(MAX(version_no),0)+1 FROM production_tracking_process_templates "
                +"WHERE product_config_id=?",Integer.class,productConfigId);
        jdbc.update("INSERT INTO production_tracking_process_templates(product_config_id,workshop_kind,version_no,state,note,created_by) "
                +"VALUES(?,?,?,'DRAFT',?,?)",productConfigId,kind,next,draft.note(),actor);
        long id=jdbc.queryForObject("SELECT LAST_INSERT_ID()",Long.class);
        for(var node:draft.nodes()){
            var def=byCode.get(node.code());
            jdbc.update("INSERT INTO production_tracking_process_template_nodes(template_id,stage_code,stage_label,"
                    +"sort_no,conditional,match_keywords,exclude_keywords) VALUES(?,?,?,?,?,?,?)",
                    id,node.code(),def.get("stage_label"),node.sortNo(),node.conditional()?1:0,
                    node.conditional()?def.get("match_keywords"):null,
                    node.conditional()?def.get("exclude_keywords"):null);
        }
        for(var edge:draft.edges()==null?List.<Edge>of():draft.edges())jdbc.update("INSERT INTO production_tracking_process_template_edges"
                +"(template_id,predecessor_code,successor_code) VALUES(?,?,?)",id,edge.from(),edge.to());
        return id;
    }

    @Transactional
    public void publish(long templateId,String actor) {
        var rows=jdbc.queryForList("SELECT t.state,c.config_name,c.workshop_id FROM production_tracking_process_templates t "
                +"JOIN manufacturing_task_config_items c ON c.id=t.product_config_id WHERE t.id=? FOR UPDATE",templateId);
        if(rows.size()!=1||!"DRAFT".equals(rows.getFirst().get("state")))
            throw new IllegalArgumentException("仅草稿版本可以发布");
        var product=rows.getFirst();
        int sameName=jdbc.queryForObject("SELECT COUNT(*) FROM manufacturing_task_config_items "
                +"WHERE workshop_id=? AND config_type='PRODUCT_NAME' AND config_name=?",
                Integer.class,product.get("workshop_id"),product.get("config_name"));
        if(sameName!=1)throw new IllegalArgumentException("同车间产品名称不唯一，无法安全匹配新任务；请先整理产品配置");
        jdbc.update("UPDATE production_tracking_process_templates SET state='PUBLISHED',published_at=NOW(),"
                +"published_by=? WHERE id=?",actor,templateId);
    }

    /** 仅新建源任务调用。重复全量导入命中既有单时绝不触碰快照。 */
    @Transactional
    public void captureNewOrder(long orderId) {
        capture(orderId,true,"H3_IMPORT");
    }

    /** 旧任务只冻结兼容制程，绝不追溯套用今天发布的产品模板。 */
    @Transactional
    public void captureLegacyOrder(long orderId,String actor) {
        if(jdbc.queryForObject("SELECT COUNT(*) FROM production_tracking_process_snapshots WHERE production_order_id=?",
                Integer.class,orderId)>0)return;
        capture(orderId,false,actor);
        jdbc.update("INSERT INTO production_tracking_process_changes"
                +"(production_order_id,revision_no,before_json,after_json,reason,changed_by) "
                +"VALUES(?,?,?,?,?,?)",orderId,0,"{}",json(snapshot(orderId)),"建立旧制程兼容快照",actor);
    }

    /** 明确选择旧任务后原子地建立兼容快照；任何一单失败则整批回滚。 */
    @Transactional
    public Map<String,Object> captureLegacyBatch(LegacyBatch request,String actor) {
        if(request==null||request.orderIds()==null||request.orderIds().isEmpty()
                ||request.orderIds().size()>100||new HashSet<>(request.orderIds()).size()!=request.orderIds().size()
                ||request.orderIds().stream().anyMatch(id->id==null||id<=0))
            throw new IllegalArgumentException("请选择 1–100 张不重复的历史任务");
        if(request.reason()==null||request.reason().isBlank()||request.reason().length()>500)
            throw new IllegalArgumentException("批量建立兼容快照须填写核实原因");
        for(long id:request.orderIds()){
            if(jdbc.queryForObject("SELECT COUNT(*) FROM production_tracking_process_snapshots WHERE production_order_id=?",
                    Integer.class,id)>0)
                throw new IllegalArgumentException("任务 "+id+" 已有制程快照，请刷新预览");
            capture(id,false,actor);
            jdbc.update("INSERT INTO production_tracking_process_changes"
                    +"(production_order_id,revision_no,before_json,after_json,reason,changed_by) "
                    +"VALUES(?,?,?,?,?,?)",id,0,"{}",json(snapshot(id)),request.reason().trim(),actor);
        }
        return Map.of("capturedCount",request.orderIds().size(),"orderIds",request.orderIds());
    }

    private void capture(long orderId,boolean allowPublishedTemplate,String actor) {
        if(jdbc.queryForObject("SELECT COUNT(*) FROM production_tracking_process_snapshots WHERE production_order_id=?",
                Integer.class,orderId)>0)return;
        var order=jdbc.queryForMap("SELECT product_name,production_line,optional_configuration,production_requirement "
                +"FROM production_orders WHERE id=?",orderId);
        String workshop=(String)order.get("production_line");
        String kind=ProductionTrackingPolicy.isSealer(workshop)?"SEALER":"MACHINE";
        var matches=allowPublishedTemplate?jdbc.queryForList("SELECT t.id,t.version_no FROM manufacturing_task_config_items c "
                +"JOIN manufacturing_workshops w ON w.id=c.workshop_id "
                +"JOIN production_tracking_process_templates t ON t.product_config_id=c.id AND t.state='PUBLISHED' "
                +"WHERE c.config_type='PRODUCT_NAME' AND c.config_name=? AND w.workshop_name=? "
                +"AND t.workshop_kind=? AND t.version_no=(SELECT MAX(t2.version_no) "
                +"FROM production_tracking_process_templates t2 WHERE t2.product_config_id=c.id "
                +"AND t2.state='PUBLISHED')",order.get("product_name"),workshop,kind):List.<Map<String,Object>>of();
        // 产品名称不唯一或尚未发布：保留旧制程快照，绝不猜测产品身份。
        Long templateId=matches.size()==1?((Number)matches.getFirst().get("id")).longValue():null;
        Integer templateVersion=templateId==null?null:((Number)matches.getFirst().get("version_no")).intValue();
        jdbc.update("INSERT INTO production_tracking_process_snapshots"
                +"(production_order_id,template_id,template_version,source_kind,created_by) VALUES(?,?,?,?,?)",
                orderId,templateId,templateVersion,templateId==null?"LEGACY":"PUBLISHED",actor);
        var nodes=templateId==null?legacyNodes(orderId,kind):new ArrayList<>(jdbc.queryForList(
                "SELECT stage_code,stage_label,sort_no,conditional,match_keywords,exclude_keywords "
                +"FROM production_tracking_process_template_nodes WHERE template_id=? ORDER BY sort_no",templateId));
        if(nodes.isEmpty())throw new IllegalArgumentException("任务 "+orderId+" 无可冻结的制程节点，请先核对车间节点字典");
        for(var node:nodes){
            String code=(String)node.get("stage_code");
            boolean conditional=flag(node.get("conditional"));
            String applicability=conditional
                    ?(templateId==null?"NOT_APPLICABLE":"PENDING_REVIEW"):"APPLICABLE";
            if(conditional&&(indicated(node,(String)order.get("optional_configuration"))
                    ||indicated(node,(String)order.get("production_requirement"))))
                applicability="APPLICABLE";
            if(!allowPublishedTemplate&&jdbc.queryForObject("SELECT COUNT(*) FROM production_tracking_milestones "
                    +"WHERE production_order_id=? AND stage_code=?",Integer.class,orderId,code)>0)
                applicability="APPLICABLE";
            if(!allowPublishedTemplate){
                var confirmed=jdbc.query("SELECT applicability FROM production_tracking_milestones "
                        +"WHERE production_order_id=? AND stage_code=? AND applicability IS NOT NULL",
                        (r,n)->r.getString(1),orderId,code);
                if(!confirmed.isEmpty())applicability=confirmed.getFirst();
            }
            jdbc.update("INSERT INTO production_tracking_process_snapshot_nodes"
                    +"(production_order_id,stage_code,stage_label,sort_no,conditional,applicability) VALUES(?,?,?,?,?,?)",
                    orderId,code,node.get("stage_label"),node.get("sort_no"),conditional?1:0,applicability);
        }
        if(templateId!=null){
            var edges=jdbc.queryForList("SELECT predecessor_code,successor_code "
                    +"FROM production_tracking_process_template_edges WHERE template_id=?",templateId);
            for(var edge:edges)jdbc.update("INSERT INTO production_tracking_process_snapshot_edges"
                    +"(production_order_id,predecessor_code,successor_code) VALUES(?,?,?)",orderId,
                    edge.get("predecessor_code"),edge.get("successor_code"));
        }
    }

    private ArrayList<Map<String,Object>> legacyNodes(long orderId,String kind) {
        var nodes=new ArrayList<>(jdbc.queryForList("SELECT stage_code,stage_label,sort_no,"
                +"optional AS conditional,match_keywords,exclude_keywords FROM production_tracking_stage_defs "
                +"WHERE workshop_kind=? AND enabled=1 AND default_included=1 ORDER BY sort_no",kind));
        var known=new HashSet<String>();
        for(var node:nodes)known.add((String)node.get("stage_code"));
        for(var code:jdbc.query("SELECT stage_code FROM production_tracking_milestones "
                +"WHERE production_order_id=?",(r,n)->r.getString(1),orderId)){
            if(known.add(code)){
                var recovered=new HashMap<String,Object>();
                recovered.put("stage_code",code);
                recovered.put("stage_label",code);
                recovered.put("sort_no",nodes.size()+1);
                recovered.put("conditional",0);
                nodes.add(recovered);
            }
        }
        return nodes;
    }

    private static boolean indicated(Map<String,Object> node,String text) {
        String match=(String)node.get("match_keywords");
        if(text==null||text.isBlank()||match==null||match.isBlank())return false;
        try{
            if(!Pattern.compile(match).matcher(text).find())return false;
            String exclude=(String)node.get("exclude_keywords");
            return exclude==null||exclude.isBlank()||!Pattern.compile(exclude).matcher(text).find();
        }catch(PatternSyntaxException e){
            throw new IllegalArgumentException("制程节点 "+node.get("stage_code")+" 的适用条件不是有效正则",e);
        }
    }

    static boolean flag(Object value) {
        return Boolean.TRUE.equals(value)||value instanceof Number n&&n.intValue()!=0;
    }

    static void validate(Draft draft,Set<String> allowed) {
        if(draft==null||draft.nodes()==null||draft.nodes().isEmpty()||draft.nodes().size()>40)
            throw new IllegalArgumentException("模板节点数须为 1–40");
        var codes=new HashSet<String>();
        var sorts=new HashSet<Integer>();
        for(var node:draft.nodes()){
            if(node==null||!allowed.contains(node.code())||!codes.add(node.code())||!sorts.add(node.sortNo())
                    ||node.sortNo()<1)throw new IllegalArgumentException("模板节点编码或顺序无效");
        }
        var indegree=new HashMap<String,Integer>();
        var next=new HashMap<String,List<String>>();
        for(var code:codes)indegree.put(code,0);
        for(var edge:draft.edges()==null?List.<Edge>of():draft.edges()){
            if(edge==null||!codes.contains(edge.from())||!codes.contains(edge.to())||edge.from().equals(edge.to()))
                throw new IllegalArgumentException("节点依赖必须引用本模板中的不同节点");
            next.computeIfAbsent(edge.from(),k->new ArrayList<>()).add(edge.to());
            indegree.merge(edge.to(),1,Integer::sum);
        }
        var queue=new ArrayDeque<String>();
        indegree.forEach((code,degree)->{if(degree==0)queue.add(code);});
        int visited=0;
        while(!queue.isEmpty()){
            String code=queue.remove();visited++;
            for(var to:next.getOrDefault(code,List.of()))if(indegree.merge(to,-1,Integer::sum)==0)queue.add(to);
        }
        if(visited!=codes.size())throw new IllegalArgumentException("制程依赖不能形成环");
    }
}
