package com.liqiang.archive.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.liqiang.archive.erp.*;
import com.liqiang.archive.security.DataScopeSupport;
import com.liqiang.archive.security.FeatureCode;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.*;

/** 跟单是本地业务记录；不写来源台账、正式制造、报工或库存。 */
@Service
public class ProductionFollowUpService {
    private final JdbcTemplate jdbc;
    private final DataScopeSupport scope;
    private final ErpActorResolver actors;
    private final ErpIdempotencyService idem;
    private final ErpChangeLogWriter audit;
    private final ObjectMapper json;
    private static final String FROM = " FROM production_orders p LEFT JOIN employees e ON e.id=p.employee_id "
            + "LEFT JOIN production_followup_records f ON f.external_source=p.external_source AND BINARY f.external_id=BINARY p.external_id ";
    private static final String SELECT = "SELECT p.id,p.external_id,p.work_order_no,p.product_name,p.mold_layout,p.box_size,"
            + "p.required_quantity,p.production_line,p.salesperson_name,p.customer_name,p.status,"
            + "CASE WHEN f.id IS NULL THEN p.required_completion_date ELSE f.original_required_date END required_completion_date,"
            + "p.required_completion_date source_required_completion_date,"
            + "p.expected_completion_date,p.actual_completion_date,p.production_completed,p.warehousing_completed,p.production_requirement,"
            + "p.completed_quantity,p.warehoused_quantity,p.production_progress,p.warehousing_progress,p.application_date,p.last_synced_at,"
            + "f.id followup_id,f.version,f.state_json,f.updated_by_name,f.updated_at";

    public ProductionFollowUpService(JdbcTemplate jdbc, DataScopeSupport scope, ErpActorResolver actors,
            @Qualifier("archiveIdempotencyService") ErpIdempotencyService idem,
            @Qualifier("archiveAuditWriter") ErpChangeLogWriter audit, ObjectMapper json) {
        this.jdbc=jdbc; this.scope=scope; this.actors=actors; this.idem=idem; this.audit=audit; this.json=json;
    }

    public record State(LocalDate plannedStartDate, LocalDate expectedFinishDate, String delayReason,
                        LocalDate completedDate, LocalDate warehousedDate, LocalDate shippedDate,
                        String remark, Map<String,LocalDate> stages) {}
    public record SaveRequest(long version, State state, String content) {}

    public static List<String> stageNames(String workshop) {
        if (workshop != null && workshop.contains("气调")) return List.of("画图","备料","钣金","模具","组装","调试");
        if (workshop != null && workshop.contains("封口")) return List.of("备料","模具生产","组装");
        return List.of(); // 未知车间不能猜工序；仍可记录交期和跟进。
    }

    private StringBuilder visibleWhere(List<Object> params) {
        var where=new StringBuilder(" WHERE p.external_source='H3YUN'");
        // employee_id 是 employees 的外键；salesperson_id 指向氚云通讯录，不能混用。
        scope.appendByEmployeeId(where,params,"p.employee_id","e.department",FeatureCode.MANUFACTURING);
        return where;
    }

    public Map<String,Object> list(String keyword,String workshop,String status,boolean overdue,int page,int size) {
        return list(keyword,workshop,status,overdue,"newest",page,size);
    }

    public Map<String,Object> list(String keyword,String workshop,String status,boolean overdue,String orderBy,int page,int size) {
        if(page<1 || size<1 || size>100) throw new IllegalArgumentException("分页范围无效");
        var params=new ArrayList<Object>(); var where=visibleWhere(params);
        if(keyword!=null && !keyword.isBlank()) {
            where.append(" AND (p.work_order_no LIKE ? OR p.product_name LIKE ? OR p.salesperson_name LIKE ?)");
            for(int i=0;i<3;i++) params.add("%"+keyword.trim()+"%");
        }
        if(workshop!=null && !workshop.isBlank()) {where.append(" AND p.production_line=?");params.add(workshop);}
        // 氚云完成标记是独立来源事实，缺日期不能推断为未完成。
        String done="(COALESCE(p.production_completed,0)=1 OR p.actual_completion_date IS NOT NULL OR f.completed_date IS NOT NULL)";
        switch(status==null?"all":status) {
            case "active" -> where.append(" AND COALESCE(p.status,'') NOT IN ('已取消','CANCELLED') AND NOT "+done);
            case "completed" -> where.append(" AND COALESCE(p.status,'') NOT IN ('已取消','CANCELLED') AND "+done);
            case "untracked" -> where.append(" AND f.id IS NULL AND COALESCE(p.status,'') NOT IN ('已取消','CANCELLED')");
            case "cancelled" -> where.append(" AND p.status IN ('已取消','CANCELLED')");
            case "all" -> { }
            default -> throw new IllegalArgumentException("跟单状态无效");
        }
        String due="(CASE WHEN f.id IS NULL THEN p.required_completion_date ELSE f.original_required_date END)";
        if(overdue) where.append(" AND "+due+"<CURDATE() AND NOT "+done+" AND COALESCE(p.status,'') NOT IN ('已取消','CANCELLED')");
        long total=Objects.requireNonNull(jdbc.queryForObject("SELECT COUNT(*)"+FROM+where,Long.class,params.toArray()));
        params.add(size);params.add((long)(page-1)*size);
        String ordering=switch(orderBy==null?"newest":orderBy) {
            case "newest" -> "p.id DESC"; // 与氚云生产台账保持一致，避免旧脏交期霸占首页。
            case "due" -> due+" IS NULL,"+due+",p.id DESC";
            default -> throw new IllegalArgumentException("排序方式无效");
        };
        var items=jdbc.query(SELECT+FROM+where+" ORDER BY "+ordering+" LIMIT ? OFFSET ?",
                this::orderRow,params.toArray());
        return Map.of("items",items,"total",total,"page",page,"size",size);
    }

    public List<String> workshops() {
        var params=new ArrayList<Object>();var where=visibleWhere(params);
        return jdbc.query("SELECT DISTINCT p.production_line"+FROM+where+" AND p.production_line IS NOT NULL ORDER BY p.production_line",
                (r,n)->r.getString(1),params.toArray());
    }

    private Map<String,Object> visibleOrder(long id) {
        var params=new ArrayList<Object>();var where=visibleWhere(params);where.append(" AND p.id=?");params.add(id);
        return jdbc.query(SELECT+FROM+where,this::orderRow,params.toArray()).stream().findFirst()
                .orElseThrow(()->new IllegalArgumentException("生产工单不存在或无权查看"));
    }

    public Map<String,Object> detail(long id) {
        var order=visibleOrder(id);
        List<Map<String,Object>> logs=List.of();
        if(order.get("followupId")!=null) logs=jdbc.query("SELECT * FROM production_followup_logs WHERE record_id=? ORDER BY version DESC LIMIT 100",
                (r,n)->{var m=row(r);m.put("before",read(r.getString("before_json")));m.put("after",read(r.getString("after_json")));
                    m.remove("beforeJson");m.remove("afterJson");return m;},order.get("followupId"));
        long logCount=order.get("followupId")==null?0:Objects.requireNonNull(jdbc.queryForObject(
                "SELECT COUNT(*) FROM production_followup_logs WHERE record_id=?",Long.class,order.get("followupId")));
        var sourceComponents=jdbc.query("SELECT sequence_no,component_name,required_quantity,completed_quantity,completion_ratio,completed_at,completed "
                +"FROM production_order_components WHERE production_order_id=? ORDER BY sequence_no,id",(r,n)->row(r),id);
        return Map.of("order",order,"logs",logs,"logCount",logCount,"sourceComponents",sourceComponents);
    }

    public long save(long id,SaveRequest request,String key) {
        var order=visibleOrder(id); // 幂等重放也必须重新校验行级权限。
        if(request==null || request.state()==null || request.version()<0) throw new IllegalArgumentException("缺少跟单内容或版本");
        String content=text(request.content(),1000,"跟进说明");
        if(content.isEmpty())throw new IllegalArgumentException("请填写本次跟进或修改原因");
        @SuppressWarnings("unchecked")
        var allowed=(List<String>)order.get("stageNames");
        State state=validate(request.state(),allowed,LocalDate.now());
        var actor=actors.current();
        var payload=Map.of("sourceId",id,"version",request.version(),"state",state,"content",content);
        return Long.parseLong(idem.execute(actor,key,"production.followup.save",payload,()->{
            var current=visibleOrder(id);
            String externalId=(String)current.get("externalId");
            if(externalId==null || externalId.isBlank())throw new IllegalArgumentException("来源工单缺少唯一标识，无法保存跟单");
            jdbc.update("INSERT INTO production_followup_records(external_source,external_id,state_json,original_required_date) VALUES('H3YUN',?,?,?) "
                    +"ON DUPLICATE KEY UPDATE id=id",externalId,write(current.get("state")),current.get("requiredCompletionDate"));
            var record=jdbc.queryForMap("SELECT id,version,state_json FROM production_followup_records WHERE external_source='H3YUN' AND BINARY external_id=BINARY ? FOR UPDATE",externalId);
            long version=((Number)record.get("version")).longValue();long recordId=((Number)record.get("id")).longValue();
            if(version!=request.version())throw new IllegalStateException("该工单已被其他人更新，请重新打开后填写，避免覆盖他人记录");
            String before=String.valueOf(record.get("state_json"));String after=write(state);
            jdbc.update("UPDATE production_followup_records SET state_json=?,completed_date=?,expected_finish_date=?,version=?,updated_by_name=?,updated_at=NOW() WHERE id=?",
                    after,state.completedDate(),state.expectedFinishDate(),version+1,actor.name(),recordId);
            jdbc.update("INSERT INTO production_followup_logs(record_id,version,content,before_json,after_json,created_by_employee_id,created_by_name) VALUES(?,?,?,?,?,?,?)",
                    recordId,version+1,content,before,after,actor.employeeId(),actor.name());
            audit.write(ErpChangeLogWriter.of(actor,"product_archive.production_followup_records",recordId,version==0?"INSERT":"UPDATE",
                    Map.of("state",Map.of("from",read(before),"to",state),"version",Map.of("from",version,"to",version+1)),
                    "FOLLOW_UP",content,key,null));
            return String.valueOf(version+1);
        }));
    }

    static State validate(State s,List<String> allowed,LocalDate today) {
        var stages=new LinkedHashMap<String,LocalDate>();
        if(s.stages()!=null)for(var entry:s.stages().entrySet()) {
            if(!allowed.contains(entry.getKey()))throw new IllegalArgumentException("车间不支持该跟单阶段："+entry.getKey());
            past(entry.getValue(),today);stages.put(entry.getKey(),entry.getValue());
        }
        past(s.completedDate(),today);past(s.warehousedDate(),today);past(s.shippedDate(),today);
        if(s.completedDate()!=null && s.warehousedDate()!=null && s.warehousedDate().isBefore(s.completedDate()))
            throw new IllegalArgumentException("入库日期不能早于实际生产完成日期");
        if(s.warehousedDate()!=null && s.shippedDate()!=null && s.shippedDate().isBefore(s.warehousedDate()))
            throw new IllegalArgumentException("发货日期不能早于入库日期");
        return new State(s.plannedStartDate(),s.expectedFinishDate(),text(s.delayReason(),300,"延期原因"),
                s.completedDate(),s.warehousedDate(),s.shippedDate(),text(s.remark(),1000,"备注"),stages);
    }
    private static void past(LocalDate d,LocalDate today) {
        if(d!=null && d.isAfter(today))throw new IllegalArgumentException("实际完成、入库或发货日期不能是未来日期");
    }
    private static String text(String s,int max,String label) {
        String value=s==null?"":s.trim();if(value.length()>max)throw new IllegalArgumentException(label+"最多"+max+"字");return value;
    }
    private String write(Object value) {
        try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException("跟单记录序列化失败",e);}
    }
    private State read(String value) {
        try{return json.readValue(value,State.class);}catch(Exception e){throw new IllegalStateException("跟单记录读取失败",e);}
    }
    private Map<String,Object> orderRow(ResultSet r,int n)throws SQLException {
        var m=row(r);m.remove("stateJson");
        var state=r.getString("state_json")==null?new State(null,date(r,"expected_completion_date"),"",
                date(r,"actual_completion_date"),null,null,"",new LinkedHashMap<>()):read(r.getString("state_json"));
        var stages=new LinkedHashSet<>(stageNames(r.getString("production_line")));
        // 来源修改车间后仍显示已有本地阶段，防止跟单日期无声消失。
        if(state.stages()!=null)stages.addAll(state.stages().keySet());
        m.put("state",state);m.put("version",r.getLong("version"));m.put("stageNames",List.copyOf(stages));
        return m;
    }
    private static LocalDate date(ResultSet r,String name)throws SQLException {var d=r.getDate(name);return d==null?null:d.toLocalDate();}
    private static Map<String,Object> row(ResultSet r)throws SQLException {
        var m=new LinkedHashMap<String,Object>();var md=r.getMetaData();
        for(int i=1;i<=md.getColumnCount();i++) {
            StringBuilder key=new StringBuilder();boolean up=false;
            for(char c:md.getColumnLabel(i).toLowerCase(Locale.ROOT).toCharArray()) {if(c=='_')up=true;else{key.append(up?Character.toUpperCase(c):c);up=false;}}
            Object v=r.getObject(i);if(v instanceof java.sql.Date d)v=d.toLocalDate();if(v instanceof java.sql.Timestamp t)v=t.toLocalDateTime();m.put(key.toString(),v);
        }return m;
    }
}
