package com.liqiang.archive.service;

import com.liqiang.archive.erp.ErpActorResolver;
import com.liqiang.archive.erp.ErpChangeLogWriter;
import com.liqiang.archive.erp.ErpIdempotencyService;
import com.liqiang.archive.security.CurrentUserService;
import com.liqiang.archive.security.DataScopeSupport;
import com.liqiang.archive.security.FeatureCode;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 氚云来源任务的本地检验退回、返工报工及冲正。 */
@Service
public class H3ProductionReworkService {
    private final JdbcTemplate jdbc;
    private final DataScopeSupport scope;
    private final CurrentUserService users;
    private final ErpActorResolver actors;
    private final ErpIdempotencyService idem;
    private final ErpChangeLogWriter audit;
    private final H3ProductionExecutionService execution;

    public H3ProductionReworkService(JdbcTemplate jdbc, DataScopeSupport scope, CurrentUserService users,
            ErpActorResolver actors, @Qualifier("archiveIdempotencyService") ErpIdempotencyService idem,
            @Qualifier("archiveAuditWriter") ErpChangeLogWriter audit, H3ProductionExecutionService execution) {
        this.jdbc=jdbc; this.scope=scope; this.users=users; this.actors=actors;
        this.idem=idem; this.audit=audit; this.execution=execution;
    }

    public record FailRequest(String componentExternalId, BigDecimal quantity, String reason) {}
    public record ReportRequest(BigDecimal quantity, String remark) {}
    public record ReverseRequest(String reason) {}

    public Map<String,Object> current(long orderId) {
        var order = visibleOrder(orderId);
        var reworks = jdbc.queryForList("""
                SELECT id,component_external_id componentExternalId,component_name_snapshot componentName,
                       target_quantity targetQuantity,reported_quantity reportedQuantity,status,
                       failure_reason failureReason,inspected_by_name inspectedByName,inspected_at inspectedAt,closed_at closedAt
                  FROM production_execution_reworks WHERE BINARY order_external_id=BINARY ? ORDER BY id DESC
                """,order.get("external_id"));
        var reports = jdbc.queryForList("""
                SELECT r.id,r.rework_id reworkId,r.quantity,r.status,r.remark,r.reported_by_name reportedByName,
                       r.reported_at reportedAt,r.reversed_by_name reversedByName,r.reversed_at reversedAt,
                       r.reversal_reason reversalReason
                  FROM production_execution_rework_reports r
                  JOIN production_execution_reworks w ON w.id=r.rework_id
                 WHERE BINARY w.order_external_id=BINARY ? ORDER BY r.id DESC
                """,order.get("external_id"));
        var result = new LinkedHashMap<String,Object>();
        result.put("reworks",reworks);
        result.put("reworkReports",reports);
        result.put("activeRework",reworks.stream().filter(r -> active(String.valueOf(r.get("status"))))
                .findFirst().orElse(null));
        return result;
    }

    public long fail(long orderId, FailRequest request, String key) {
        if (!users.can("manufacturing.inspect")) throw new SecurityException("没有完工检验权限");
        var visible = visibleOrder(orderId);
        if (request == null || request.componentExternalId() == null || request.componentExternalId().isBlank()
                || request.quantity() == null || request.quantity().signum() <= 0 || request.quantity().scale() > 4)
            throw new IllegalArgumentException("请选择返工制程并填写有效返工数量（最多4位小数）");
        String reason = clean(request.reason(),1000,"不合格原因");
        if (reason.isBlank()) throw new IllegalArgumentException("请填写不合格原因");
        var actor = actors.current();
        var payload = Map.of("orderId",orderId,"componentExternalId",request.componentExternalId(),
                "quantity",request.quantity(),"reason",reason);
        return Long.parseLong(idem.execute(actor,key,"h3.production.inspection.fail",payload,() -> {
            var order = lockOrder(orderId,visible);
            ensureOpen(order);
            var active = jdbc.queryForList("SELECT id,status FROM production_execution_reworks "
                    + "WHERE BINARY order_external_id=BINARY ? AND status IN ('REWORKING','PENDING_REINSPECTION') FOR UPDATE",
                    order.get("external_id"));
            if (!active.isEmpty() && !"PENDING_REINSPECTION".equals(active.getFirst().get("status")))
                throw new IllegalStateException("返工尚未报工完成，不能再次提交检验不合格");
            var detail = execution.detail(orderId);
            long count = ((Number)detail.get("componentCount")).longValue();
            long done = ((Number)detail.get("completedComponentCount")).longValue();
            if (count == 0 || done != count) throw new IllegalStateException("制程尚未全部完成，不能提交完工检验");
            var components = jdbc.queryForList("SELECT external_id,component_name,required_quantity FROM production_order_components "
                    + "WHERE production_order_id=? AND BINARY external_id=BINARY ?",orderId,request.componentExternalId());
            if (components.size()!=1) throw new IllegalArgumentException("返工制程不存在或标识不唯一");
            var component = components.getFirst();
            BigDecimal required = qty(component.get("required_quantity"));
            if (required.signum()<=0 || request.quantity().compareTo(required)>0)
                throw new IllegalArgumentException("返工数量不能超过该制程的需求数量");
            if (!active.isEmpty()) jdbc.update("UPDATE production_execution_reworks SET status='REJECTED',closed_at=NOW() WHERE id=?",
                    active.getFirst().get("id"));
            jdbc.update("INSERT INTO production_execution_reworks(order_external_id,work_order_no_snapshot,"
                    + "component_external_id,component_name_snapshot,target_quantity,failure_reason,"
                    + "inspected_by_employee_id,inspected_by_name) VALUES(?,?,?,?,?,?,?,?)",
                    order.get("external_id"),order.get("work_order_no"),request.componentExternalId(),
                    component.get("component_name"),request.quantity(),reason,actor.employeeId(),actor.name());
            long id = Objects.requireNonNull(jdbc.queryForObject("SELECT LAST_INSERT_ID()",Long.class));
            audit.write(ErpChangeLogWriter.of(actor,"product_archive.production_execution_reworks",id,"INSERT",
                    ErpChangeLogWriter.changes("orderExternalId",order.get("external_id"),
                            "componentExternalId",request.componentExternalId(),"targetQuantity",request.quantity(),
                            "status","REWORKING"),null,reason,key,null));
            return String.valueOf(id);
        }));
    }

    public long report(long orderId,long reworkId,ReportRequest request,String key) {
        if (!users.can("manufacturing.report")) throw new SecurityException("没有车间报工权限");
        var visible = visibleOrder(orderId);
        if (request==null || request.quantity()==null || request.quantity().signum()<=0 || request.quantity().scale()>4)
            throw new IllegalArgumentException("返工报工数量必须大于0且最多4位小数");
        String remark=clean(request.remark(),1000,"报工说明");
        var actor=actors.current();
        var payload=Map.of("orderId",orderId,"reworkId",reworkId,"quantity",request.quantity(),"remark",remark);
        return Long.parseLong(idem.execute(actor,key,"h3.production.rework.report",payload,() -> {
            var order=lockOrder(orderId,visible);
            ensureOpen(order);
            var rework=lockedRework(reworkId,order);
            if (!"REWORKING".equals(rework.get("status")))
                throw new IllegalStateException("该返工轮次已报工完成或已关闭");
            long sourceComponentCount=Objects.requireNonNull(jdbc.queryForObject("SELECT COUNT(*) "
                    + "FROM production_order_components WHERE production_order_id=? "
                    + "AND BINARY external_id=BINARY ?",Long.class,orderId,rework.get("component_external_id")));
            if (sourceComponentCount!=1)
                throw new IllegalStateException("返工制程的氚云来源标识已变化，请核对重新导入的制程明细");
            BigDecimal total=qty(rework.get("reported_quantity")).add(request.quantity());
            if (total.compareTo(qty(rework.get("target_quantity")))>0)
                throw new IllegalArgumentException("返工报工超过待返工数量");
            jdbc.update("INSERT INTO production_execution_rework_reports(rework_id,quantity,remark,"
                    + "reported_by_employee_id,reported_by_name) VALUES(?,?,?,?,?)",
                    reworkId,request.quantity(),remark,actor.employeeId(),actor.name());
            long id=Objects.requireNonNull(jdbc.queryForObject("SELECT LAST_INSERT_ID()",Long.class));
            jdbc.update("UPDATE production_execution_reworks SET reported_quantity=?,status=? WHERE id=?",
                    total,total.compareTo(qty(rework.get("target_quantity")))==0?"PENDING_REINSPECTION":"REWORKING",reworkId);
            audit.write(ErpChangeLogWriter.of(actor,"product_archive.production_execution_rework_reports",id,"INSERT",
                    ErpChangeLogWriter.changes("reworkId",reworkId,"quantity",request.quantity(),"status","VALID"),
                    null,remark,key,null));
            return String.valueOf(id);
        }));
    }

    public void reverse(long orderId,long reworkId,long reportId,ReverseRequest request,String key) {
        if (!users.can("manufacturing.report.override")) throw new SecurityException("没有报工冲正权限");
        var visible=visibleOrder(orderId);
        String reason=clean(request==null?null:request.reason(),1000,"冲正原因");
        if (reason.isBlank()) throw new IllegalArgumentException("请填写冲正原因");
        var actor=actors.current();
        var payload=Map.of("orderId",orderId,"reworkId",reworkId,"reportId",reportId,"reason",reason);
        idem.execute(actor,key,"h3.production.rework.report.reverse",payload,() -> {
            var order=lockOrder(orderId,visible);
            if (posted(order)) throw new IllegalStateException("已完工入库，请先冲正入库");
            var rework=lockedRework(reworkId,order);
            if (!active(String.valueOf(rework.get("status")))) throw new IllegalStateException("返工轮次已关闭");
            var rows=jdbc.queryForList("SELECT id,quantity,status FROM production_execution_rework_reports "
                    + "WHERE id=? AND rework_id=? FOR UPDATE",reportId,reworkId);
            if (rows.isEmpty()) throw new IllegalArgumentException("返工报工记录不存在");
            var report=rows.getFirst();
            if (!"VALID".equals(report.get("status"))) throw new IllegalStateException("该返工报工已冲正");
            BigDecimal total=qty(rework.get("reported_quantity")).subtract(qty(report.get("quantity")));
            if (total.signum()<0) throw new IllegalStateException("返工报工数量异常");
            jdbc.update("UPDATE production_execution_rework_reports SET status='REVERSED',reversal_reason=?,"
                    + "reversed_by_employee_id=?,reversed_by_name=?,reversed_at=NOW() WHERE id=?",
                    reason,actor.employeeId(),actor.name(),reportId);
            jdbc.update("UPDATE production_execution_reworks SET reported_quantity=?,status='REWORKING' WHERE id=?",
                    total,reworkId);
            audit.write(ErpChangeLogWriter.of(actor,"product_archive.production_execution_rework_reports",reportId,"UPDATE",
                    ErpChangeLogWriter.changes("status","VALID→REVERSED","quantity",report.get("quantity")),
                    "REVERSAL",reason,key,null));
            return String.valueOf(reportId);
        });
    }

    private Map<String,Object> visibleOrder(long id) {
        var params=new ArrayList<Object>(); params.add(id);
        var where=new StringBuilder(" WHERE p.id=? AND p.external_source='H3YUN'");
        scope.appendByEmployeeId(where,params,"p.employee_id","e.department",FeatureCode.MANUFACTURING);
        var rows=jdbc.queryForList("SELECT p.id,p.external_id,p.work_order_no,p.status,p.warehoused_quantity,"
                + "p.warehousing_completed FROM production_orders p LEFT JOIN employees e ON e.id=p.employee_id "
                + where,params.toArray());
        if (rows.isEmpty()) throw new IllegalArgumentException("生产任务单不存在或无权操作");
        return rows.getFirst();
    }

    private Map<String,Object> lockOrder(long id,Map<String,Object> visible) {
        var rows=jdbc.queryForList("SELECT id,external_id,work_order_no,status,warehoused_quantity,"
                + "warehousing_completed FROM production_orders WHERE id=? AND external_source='H3YUN' "
                + "AND BINARY external_id=BINARY ? FOR UPDATE",id,visible.get("external_id"));
        if (rows.isEmpty()) throw new IllegalStateException("来源工单已变化，请刷新后重试");
        return rows.getFirst();
    }

    private Map<String,Object> lockedRework(long id,Map<String,Object> order) {
        var rows=jdbc.queryForList("SELECT * FROM production_execution_reworks WHERE id=? "
                + "AND BINARY order_external_id=BINARY ? FOR UPDATE",id,order.get("external_id"));
        if (rows.isEmpty()) throw new IllegalArgumentException("返工轮次不存在");
        return rows.getFirst();
    }

    private void ensureOpen(Map<String,Object> order) {
        if ("已取消".equals(order.get("status")) || "CANCELLED".equals(order.get("status")))
            throw new IllegalStateException("已取消工单不能办理质检或返工");
        Object sourceQty=order.get("warehoused_quantity"),sourceFlag=order.get("warehousing_completed");
        if (qty(sourceQty).signum()>0 || Boolean.TRUE.equals(sourceFlag)
                || sourceFlag instanceof Number n && n.intValue()==1)
            throw new IllegalStateException("氚云来源已入库，不能办理本地质检或返工");
        if (posted(order)) throw new IllegalStateException("该工单已完成本地入库");
    }

    private boolean posted(Map<String,Object> order) {
        return Objects.requireNonNull(jdbc.queryForObject("SELECT COUNT(*) FROM production_execution_completions "
                + "WHERE BINARY order_external_id=BINARY ? AND status='POSTED'",Long.class,
                order.get("external_id")))>0;
    }
    private static boolean active(String status) {
        return "REWORKING".equals(status) || "PENDING_REINSPECTION".equals(status);
    }
    private static BigDecimal qty(Object value) {
        return value instanceof BigDecimal n?n:BigDecimal.ZERO;
    }
    private static String clean(String value,int max,String label) {
        String result=value==null?"":value.trim();
        if (result.length()>max) throw new IllegalArgumentException(label+"最多"+max+"字");
        return result;
    }
}
