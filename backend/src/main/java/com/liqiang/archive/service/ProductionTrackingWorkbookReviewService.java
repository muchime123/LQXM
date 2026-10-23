package com.liqiang.archive.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.liqiang.archive.infra.ActorResolver;
import com.liqiang.archive.infra.IdempotencyService;
import com.liqiang.archive.security.CurrentUserService;
import com.liqiang.archive.security.FeatureCode;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Supervisor-reviewed deltas from later workbook snapshots into local tracking only. */
@Service
public class ProductionTrackingWorkbookReviewService {
    /** 【同步范围 2026-10-10 LQ 定】差异确认与应用只认「延期至/延期原因」两个状态字段 + 各节点完成日期；
     *  计划开工/交付结果/发货状态/跟单备注已退出表格同步，payload 里残留的旧键也不会再出现在差异里。 */
    private static final Map<String,String> STATE_COLUMNS=Map.of(
            "forecastFinishDate","forecast_finish_date",
            "delayReason","delay_reason");
    private static final Map<String,String> STATE_LABELS=Map.of(
            "forecastFinishDate","延期至",
            "delayReason","延期原因");
    public record Difference(String key,String label,String currentValue,String proposedValue) {}
    public record Review(long rowId,long productionOrderId,long version,List<Difference> differences) {}

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final ActorResolver actors;
    private final IdempotencyService idem;
    private final CurrentUserService users;

    public ProductionTrackingWorkbookReviewService(JdbcTemplate jdbc,ObjectMapper json,ActorResolver actors,
            @Qualifier("idempotencyService") IdempotencyService idem,
            CurrentUserService users) {
        this.jdbc=jdbc;this.json=json;this.actors=actors;this.idem=idem;this.users=users;
    }

    private void requireSupervisor() {
        if(!"ALL".equals(users.scopeOf(FeatureCode.MANUFACTURING)))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"仅生产跟单主管可审核表格差异");
    }

    public Review review(long rowId) {
        requireSupervisor();
        var source=workbookRow(rowId,false);
        long orderId=((Number)source.get("production_order_id")).longValue();
        var state=state(orderId,false);
        long version=state.isEmpty()?0:((Number)state.get("version")).longValue();
        return new Review(rowId,orderId,version,differences(orderId,String.valueOf(source.get("payload_json")),state));
    }

    /** Called by import preview to distinguish unchanged rows from actual update proposals. */
    List<Difference> differences(long orderId,String payload) {
        return differences(orderId,payload,state(orderId,false));
    }

    private List<Difference> differences(long orderId,String payload,Map<String,Object> state) {
        JsonNode data=parse(payload);
        var result=new ArrayList<Difference>();
        for(var entry:STATE_COLUMNS.entrySet()) {
            String proposed=value(data.path(entry.getKey()));
            if(proposed==null)continue; // Empty workbook cells never erase local follow-up facts.
            String current=value(state.get(entry.getValue()));
            if(!Objects.equals(current,proposed))
                result.add(new Difference(entry.getKey(),STATE_LABELS.get(entry.getKey()),current,proposed));
        }
        var stages=new HashMap<String,Map<String,Object>>();
        for(var row:jdbc.queryForList("SELECT stage_code,stage_status,completed_date FROM production_tracking_milestones "
                +"WHERE production_order_id=?",orderId))
            stages.put(String.valueOf(row.get("stage_code")),row);
        String workshop=workshop(orderId);
        var allowedStages=ProductionTrackingPolicy.stageCodes(workshop);
        for(JsonNode stage:data.path("milestones")) {
            String code=value(stage.path("code"));
            String proposed=value(stage.path("completedDate"));
            if(code==null||proposed==null||!allowedStages.contains(code))continue;
            var currentStage=stages.get(code);
            String current=currentStage==null?null:value(currentStage.get("completed_date"));
            boolean done=currentStage!=null&&"DONE".equals(currentStage.get("stage_status"));
            if(!done||!Objects.equals(current,proposed))
                result.add(new Difference("stage:"+code,ProductionTrackingPolicy.stageLabel(code,
                        ProductionTrackingPolicy.isSealer(workshop))+"完成日期",
                        done?current:(current==null?null:current+"（未完成）"),proposed));
        }
        return result;
    }

    public long apply(long rowId,long expectedVersion,List<String> selectedKeys,String key) {
        requireSupervisor();
        if(selectedKeys==null||selectedKeys.isEmpty()||selectedKeys.size()!=new HashSet<>(selectedKeys).size())
            throw new IllegalArgumentException("请选择需要应用的差异字段");
        var actor=actors.current();
        return Long.parseLong(idem.execute(actor,key,"production.tracking.workbook.review",
                Map.of("rowId",rowId,"version",expectedVersion,"fields",selectedKeys),()->{
            var source=workbookRow(rowId,true);
            long orderId=((Number)source.get("production_order_id")).longValue();
            jdbc.update("INSERT INTO production_tracking_state(production_order_id) VALUES(?) "
                    +"ON DUPLICATE KEY UPDATE production_order_id=production_order_id",orderId);
            var before=state(orderId,true);
            if(before.get("stopped_at")!=null)
                throw new IllegalArgumentException("该任务已停止跟单，请先恢复跟单再应用进度表差异");
            long version=((Number)before.get("version")).longValue();
            if(version!=expectedVersion)
                throw new ResponseStatusException(HttpStatus.CONFLICT,"跟单已更新，请重新核对差异");
            var beforeStages=jdbc.queryForList("SELECT stage_code,stage_status,completed_date,note "
                    +"FROM production_tracking_milestones WHERE production_order_id=? ORDER BY stage_code",orderId);
            var proposed=differences(orderId,String.valueOf(source.get("payload_json")),before);
            var available=new HashMap<String,Difference>();
            for(var difference:proposed)available.put(difference.key(),difference);
            for(String field:selectedKeys)if(!available.containsKey(field))
                throw new ResponseStatusException(HttpStatus.CONFLICT,"差异已变化，请重新核对");
            applyChanges(orderId,selectedKeys.stream().map(available::get).toList());
            jdbc.update("UPDATE production_tracking_state SET version=?,last_followup_at=NOW() WHERE production_order_id=?",
                    version+1,orderId);
            var after=state(orderId,false);
            var afterStages=jdbc.queryForList("SELECT stage_code,stage_status,completed_date,note "
                    +"FROM production_tracking_milestones WHERE production_order_id=? ORDER BY stage_code",orderId);
            var selected=new HashSet<>(selectedKeys);
            String appliedLabels=String.join("、",selectedKeys.stream().map(f->available.get(f).label()).toList());
            String retainedLabels=String.join("、",proposed.stream().filter(d->!selected.contains(d.key()))
                    .map(Difference::label).toList());
            String content="主管审核表格差异：应用"+appliedLabels
                    +(retainedLabels.isEmpty()?"":"；保留本地"+retainedLabels);
            jdbc.update("INSERT INTO production_tracking_entries(production_order_id,version,content,before_json,after_json,"
                    +"created_by_employee_id,created_by_name) VALUES(?,?,?,?,?,?,?)",orderId,version+1,content,
                    snapshot(before,beforeStages),snapshot(after,afterStages),actor.employeeId(),actor.name());
            long entryId=Objects.requireNonNull(jdbc.queryForObject("SELECT LAST_INSERT_ID()",Long.class));
            jdbc.update("UPDATE production_tracking_workbook_rows SET match_status='REVIEWED',reviewed_at=NOW(),"
                    +"reviewed_by_employee_id=?,match_note=? WHERE id=?",actor.employeeId(),content,rowId);

            return String.valueOf(rowId);
        }));
    }

    public long ignore(long rowId,String key) {
        requireSupervisor();
        var actor=actors.current();
        return Long.parseLong(idem.execute(actor,key,"production.tracking.workbook.review.ignore",Map.of("rowId",rowId),()->{
            workbookRow(rowId,true);
            jdbc.update("UPDATE production_tracking_workbook_rows SET match_status='REVIEW_IGNORED',reviewed_at=NOW(),"
                    +"reviewed_by_employee_id=?,match_note='主管确认保留现有跟单记录' WHERE id=?",actor.employeeId(),rowId);

            return String.valueOf(rowId);
        }));
    }

    /** Called only inside the supervisor-confirmed workbook import transaction. */
    void applyWorkbookOnly(long orderId,String payload,long rowId) {
        var before=state(orderId,true);
        if(before.get("stopped_at")!=null)
            throw new IllegalArgumentException("该任务已停止跟单，请先恢复跟单再导入进度");
        if(before.isEmpty())throw new ResponseStatusException(HttpStatus.CONFLICT,"跟单状态已变化，请重新预览");
        Integer manual=jdbc.queryForObject("SELECT COUNT(*) FROM production_tracking_entries "
                +"WHERE production_order_id=? AND created_by_employee_id>0",Integer.class,orderId);
        if(manual!=null&&manual>0)
            throw new ResponseStatusException(HttpStatus.CONFLICT,"该任务已有人工跟进，请重新预览并逐项审核");
        var changes=differences(orderId,payload,before);
        if(changes.isEmpty()) {
            jdbc.update("UPDATE production_tracking_workbook_rows SET match_status='EXISTING',"
                    +"match_note='与现有表格跟单内容相同' WHERE id=?",rowId);
            return;
        }
        long version=((Number)before.get("version")).longValue();
        var beforeStages=jdbc.queryForList("SELECT stage_code,stage_status,completed_date,note "
                +"FROM production_tracking_milestones WHERE production_order_id=? ORDER BY stage_code",orderId);
        applyChanges(orderId,changes);
        jdbc.update("UPDATE production_tracking_state SET version=?,last_followup_at=NOW() WHERE production_order_id=?",
                version+1,orderId);
        var after=state(orderId,false);
        var afterStages=jdbc.queryForList("SELECT stage_code,stage_status,completed_date,note "
                +"FROM production_tracking_milestones WHERE production_order_id=? ORDER BY stage_code",orderId);
        String content="更新车间进度表："+String.join("、",changes.stream().map(Difference::label).toList());
        jdbc.update("INSERT INTO production_tracking_entries(production_order_id,version,content,before_json,after_json,"
                +"created_by_employee_id,created_by_name) VALUES(?,?,?,?,?,0,'表格导入')",orderId,version+1,content,
                snapshot(before,beforeStages),snapshot(after,afterStages));
        jdbc.update("UPDATE production_tracking_workbook_rows SET match_status='UPDATED',imported_at=NOW(),"
                +"match_note=? WHERE id=?",content,rowId);
    }

    private void applyChanges(long orderId,List<Difference> changes) {
        for(var change:changes) {
            String field=change.key();
            if(field.startsWith("stage:")) {
                String code=field.substring(6);
                jdbc.update("INSERT INTO production_tracking_milestones(production_order_id,stage_code,stage_status,completed_date) "
                        +"VALUES(?,?,'DONE',?) ON DUPLICATE KEY UPDATE stage_status='DONE',completed_date=VALUES(completed_date)",
                        orderId,code,LocalDate.parse(change.proposedValue()));
            } else {
                String column=STATE_COLUMNS.get(field); // Strict whitelist; never interpolate a client field.
                Object proposedValue=field.endsWith("Date")?LocalDate.parse(change.proposedValue()):change.proposedValue();
                if("forecastFinishDate".equals(field)){
                    LocalDate deadline=jdbc.queryForObject("SELECT COALESCE(expected_completion_date,CASE WHEN "
                            +"(application_date IS NULL OR required_completion_date>=application_date) AND "
                            +"(source_created_at IS NULL OR required_completion_date>=DATE(source_created_at)) "
                            +"THEN required_completion_date END) "
                            +"FROM production_orders WHERE id=?",LocalDate.class,orderId);
                    if(java.util.Objects.equals(proposedValue,deadline))proposedValue=null;
                }
                jdbc.update("UPDATE production_tracking_state SET "+column+"=? WHERE production_order_id=?",proposedValue,orderId);
            }
        }
    }

    private Map<String,Object> workbookRow(long rowId,boolean lock) {
        return jdbc.queryForList("SELECT production_order_id,payload_json,match_status FROM production_tracking_workbook_rows "
                +"WHERE id=?"+(lock?" FOR UPDATE":""),rowId).stream().findFirst()
                .filter(row->"REVIEW".equals(row.get("match_status"))&&row.get("production_order_id")!=null)
                .orElseThrow(()->new IllegalArgumentException("待审核表格差异不存在"));
    }
    private Map<String,Object> state(long orderId,boolean lock) {
        return jdbc.queryForList("SELECT * FROM production_tracking_state WHERE production_order_id=?"
                +(lock?" FOR UPDATE":""),orderId).stream().findFirst().orElse(Map.of());
    }
    private String workshop(long orderId) {
        return jdbc.queryForObject("SELECT production_line FROM production_orders WHERE id=?",String.class,orderId);
    }
    private JsonNode parse(String payload) {
        try{return json.readTree(payload);}catch(Exception e){throw new IllegalArgumentException("表格差异数据无效",e);}
    }
    private String snapshot(Map<String,Object> state,List<Map<String,Object>> stages) {
        var data=new LinkedHashMap<String,Object>();data.put("state",state);data.put("milestones",stages);
        try{return json.writeValueAsString(data);}catch(Exception e){throw new IllegalStateException("跟单历史序列化失败",e);}
    }
    private static String value(JsonNode node) {return node==null||node.isNull()||node.isMissingNode()?null:blank(node.asText());}
    private static String value(Object value) {return value==null?null:blank(String.valueOf(value));}
    private static String blank(String text) {return text==null||text.isBlank()?null:text.trim();}
}
