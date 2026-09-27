package com.liqiang.archive.service;

import com.liqiang.archive.security.DataScopeSupport;
import com.liqiang.archive.security.FeatureCode;
import com.liqiang.archive.security.CurrentUserService;
import com.liqiang.archive.erp.ErpActorResolver;
import com.liqiang.archive.erp.ErpChangeLogWriter;
import com.liqiang.archive.erp.ErpIdempotencyService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.math.BigDecimal;

/** 氚云生产任务的执行视图。来源事实只读，进度按制程完成标记或完成时间计算。 */
@Service
public class H3ProductionExecutionService {
    private final JdbcTemplate jdbc;
    private final DataScopeSupport scope;
    private final CurrentUserService users;
    private final ErpActorResolver actors;
    private final ErpIdempotencyService idem;
    private final ErpChangeLogWriter audit;

    public H3ProductionExecutionService(JdbcTemplate jdbc, DataScopeSupport scope, CurrentUserService users,
            ErpActorResolver actors, @Qualifier("archiveIdempotencyService") ErpIdempotencyService idem,
            @Qualifier("archiveAuditWriter") ErpChangeLogWriter audit) {
        this.jdbc=jdbc;this.scope=scope;this.users=users;this.actors=actors;this.idem=idem;this.audit=audit;
    }

    public record ReportRequest(BigDecimal quantity, String remark) {}
    public record ReverseRequest(String reason) {}
    private static final String COMPONENT_JOIN = " LEFT JOIN production_execution_component_states s ON "
            + "BINARY s.order_external_id=BINARY p.external_id AND BINARY s.component_external_id=BINARY c.external_id ";
    private static final String REWORK_JOIN = " LEFT JOIN production_execution_reworks rw ON "
            + "BINARY rw.order_external_id=BINARY p.external_id AND BINARY rw.component_external_id=BINARY c.external_id "
            + "AND rw.status IN ('REWORKING','PENDING_REINSPECTION') ";
    private static final String EFFECTIVE_QTY = "GREATEST(COALESCE(c.completed_quantity,0),"
            + "COALESCE(s.baseline_source_quantity,0)+COALESCE(s.net_local_quantity,0))";
    private static final String DONE = "((c.completed=1 OR c.completed_at IS NOT NULL OR "
            + "(c.required_quantity>0 AND " + EFFECTIVE_QTY + ">=c.required_quantity)) "
            + "AND (rw.id IS NULL OR rw.reported_quantity>=rw.target_quantity))";

    private StringBuilder visibleWhere(List<Object> params) {
        var where = new StringBuilder(" WHERE p.external_source='H3YUN'");
        scope.appendByEmployeeId(where, params, "p.employee_id", "e.department", FeatureCode.MANUFACTURING);
        return where;
    }

    /** 每单聚合列：制程完成计数、本地报工/入库计数、活动返工状态。 */
    private static final String AGG_COLUMNS = "SELECT p.id,p.work_order_no,p.product_name,p.customer_name,p.production_line,"
            + "p.status,p.required_quantity,p.required_completion_date,p.last_synced_at,p.warehoused_quantity,"
            + "p.warehousing_completed,p.completed_quantity,"
            + "(SELECT COUNT(*) FROM production_execution_completions pc WHERE "
            + "BINARY pc.order_external_id=BINARY p.external_id AND pc.status='POSTED') local_posted_completion_count,"
            + "(SELECT COUNT(*) FROM production_execution_reports lr WHERE "
            + "BINARY lr.order_external_id=BINARY p.external_id AND lr.status='VALID') local_posted_report_count,"
            + "(SELECT status FROM production_execution_reworks pr WHERE "
            + "BINARY pr.order_external_id=BINARY p.external_id "
            + "AND pr.status IN ('REWORKING','PENDING_REINSPECTION') LIMIT 1) rework_status,"
            + "COUNT(c.id) component_count,"
            + "COALESCE(SUM(CASE WHEN " + DONE + " THEN 1 ELSE 0 END),0) completed_component_count";
    private static final String AGG_FROM = " FROM production_orders p LEFT JOIN employees e ON e.id=p.employee_id"
            + " LEFT JOIN production_order_components c ON c.production_order_id=p.id "
            + COMPONENT_JOIN + REWORK_JOIN;
    /**
     * 办理阶段（与前端行内标签同口径，优先级从上到下）。
     *
     * <p>「报表统计卡 → 列表筛选 → 行内标签」三处必须共用这一条判定，否则会出现
     * 「卡片显示 12 张已超期、点进去却只有 9 张」这类口径分裂。
     */
    private static final String STAGE_EXPR = "CASE"
            + " WHEN (a.warehoused_quantity>0 OR a.warehousing_completed=1) THEN 'SOURCE_WAREHOUSED'"
            + " WHEN a.local_posted_completion_count>0 THEN 'LOCAL_WAREHOUSED'"
            + " WHEN a.rework_status IS NOT NULL THEN 'REWORK'"
            + " WHEN a.component_count>0 AND a.completed_component_count=a.component_count THEN 'PENDING_INSPECTION'"
            + " WHEN a.completed_component_count=0 AND a.local_posted_report_count=0"
            + " AND COALESCE(a.completed_quantity,0)=0 THEN 'NOT_STARTED'"
            + " ELSE 'IN_PRODUCTION' END";
    /** 看板卡片 → 列表筛选条件（键与前端卡片 state 一致）。 */
    private static final Map<String, String> STATE_CONDITIONS = Map.of(
            "NOT_STARTED", "stage='NOT_STARTED'",
            "IN_PRODUCTION", "stage='IN_PRODUCTION'",
            "PENDING_INSPECTION", "stage='PENDING_INSPECTION'",
            "REWORK", "stage='REWORK'",
            "WAREHOUSED", "stage IN ('SOURCE_WAREHOUSED','LOCAL_WAREHOUSED')",
            "OVERDUE", "overdue=1");

    /**
     * 看板与列表共用的每单一行派生集：聚合层 → 阶段层 → 超期层。
     *
     * <p>分三层是因为 SQL 同一层不能引用同级别名：{@code stage} 要用聚合别名、{@code overdue} 要用 stage。
     * 只有一层算不出，另起一层又要复制判定式（迟早漂移），故按依赖顺序派生三次。
     */
    private String boardRowset(List<Object> params, String keyword) {
        var where = visibleWhere(params);
        if (keyword != null && !keyword.isBlank()) {
            where.append(" AND (p.work_order_no LIKE ? OR p.product_name LIKE ? OR p.customer_name LIKE ?)");
            for (int i = 0; i < 3; i++) params.add("%" + keyword.trim() + "%");
        }
        return "SELECT b.*, CASE WHEN b.required_completion_date IS NOT NULL AND b.required_completion_date<CURDATE()"
                + " AND b.stage NOT IN ('SOURCE_WAREHOUSED','LOCAL_WAREHOUSED') THEN 1 ELSE 0 END overdue"
                + " FROM (SELECT a.*, " + STAGE_EXPR + " stage FROM ("
                + AGG_COLUMNS + AGG_FROM + where + " GROUP BY p.id) a) b";
    }

    private String stateFilter(String state) {
        if (state == null || state.isBlank() || "ALL".equalsIgnoreCase(state.trim())) return "";
        var condition = STATE_CONDITIONS.get(state.trim().toUpperCase(Locale.ROOT));
        if (condition == null) throw new IllegalArgumentException("未知的看板筛选状态：" + state);
        return " WHERE " + condition;
    }

    /** 兼容旧调用：不带看板筛选。 */
    public Map<String, Object> list(String keyword, int page, int size) {
        return list(keyword, "", page, size);
    }

    public Map<String, Object> list(String keyword, String state, int page, int size) {
        if (page < 1 || size < 1 || size > 100) throw new IllegalArgumentException("分页范围无效");
        var params = new ArrayList<Object>();
        String rowset = boardRowset(params, keyword);
        String filter = stateFilter(state);
        long total = Objects.requireNonNull(jdbc.queryForObject(
                "SELECT COUNT(*) FROM (" + rowset + ") x" + filter, Long.class, params.toArray()));
        params.add(size);
        params.add((long) (page - 1) * size);
        var items = jdbc.query("SELECT * FROM (" + rowset + ") x" + filter + " ORDER BY x.id DESC LIMIT ? OFFSET ?",
                this::row, params.toArray());
        return Map.of("items", items, "total", total, "page", page, "size", size);
    }

    /** 看板统计卡：按同一派生集与同一数据范围聚合，keyword 生效、卡片筛选不影响分母。 */
    public Map<String, Object> summary(String keyword) {
        var params = new ArrayList<Object>();
        String rowset = boardRowset(params, keyword);
        return Objects.requireNonNull(jdbc.queryForObject("SELECT COUNT(*) total,"
                + " COALESCE(SUM(CASE WHEN stage='NOT_STARTED' THEN 1 ELSE 0 END),0) not_started,"
                + " COALESCE(SUM(CASE WHEN stage='IN_PRODUCTION' THEN 1 ELSE 0 END),0) in_production,"
                + " COALESCE(SUM(CASE WHEN stage='PENDING_INSPECTION' THEN 1 ELSE 0 END),0) pending_inspection,"
                + " COALESCE(SUM(CASE WHEN stage='REWORK' THEN 1 ELSE 0 END),0) rework,"
                + " COALESCE(SUM(CASE WHEN stage IN ('SOURCE_WAREHOUSED','LOCAL_WAREHOUSED') THEN 1 ELSE 0 END),0) warehoused,"
                + " COALESCE(SUM(overdue),0) overdue FROM (" + rowset + ") x", this::row, params.toArray()));
    }

    public Map<String, Object> detail(long id) {
        var params = new ArrayList<Object>();
        var where = visibleWhere(params).append(" AND p.id=?");
        params.add(id);
        var orders = jdbc.query("""
                SELECT p.id,p.external_id,p.work_order_no,p.product_name,p.customer_name,p.production_line,
                       p.status,p.plan_type,p.application_date,p.required_completion_date,p.expected_completion_date,
                       p.actual_completion_date,p.required_quantity,p.completed_quantity,p.warehoused_quantity,
                       p.warehousing_completed,
                       p.production_progress,p.production_requirement,p.process_name,p.linked_sales_order_no,
                       p.last_synced_at,p.source_updated_at
                  FROM production_orders p LEFT JOIN employees e ON e.id=p.employee_id
                """ + where, this::row, params.toArray());
        if (orders.isEmpty()) throw new IllegalArgumentException("生产任务单不存在或无权查看");
        var order = orders.getFirst();
        var components = jdbc.query("SELECT c.external_id,c.sequence_no,c.component_name,c.component_ref,c.component_serial_no,"
                + "c.required_quantity,c.completed_quantity,c.completion_ratio,c.completed_at,c.completed,"
                + "c.final_process,c.process_drawing,COALESCE(s.net_local_quantity,0) local_reported_quantity,"
                + "rw.id active_rework_id,rw.target_quantity rework_target_quantity,"
                + "rw.reported_quantity rework_reported_quantity,rw.status rework_status,"
                + EFFECTIVE_QTY + " effective_quantity,CASE WHEN " + DONE + " THEN 1 ELSE 0 END effective_completed"
                + " FROM production_order_components c JOIN production_orders p ON p.id=c.production_order_id "
                + COMPONENT_JOIN + REWORK_JOIN + " WHERE c.production_order_id=? ORDER BY c.sequence_no,c.id", this::row, id);
        var reports = jdbc.query("""
                SELECT r.id,r.external_id,r.report_time,r.component_serial,r.component_name,r.report_qty,
                       r.reporter,r.reporter_assist,r.reporter_dept,r.data_status,r.last_synced_at
                  FROM h3_production_reports r WHERE r.external_source='H3YUN' AND r.work_order_no=?
                 ORDER BY r.report_time DESC,r.id DESC LIMIT 200
                """, this::row, order.get("workOrderNo"));
        long reportCount = Objects.requireNonNull(jdbc.queryForObject("""
                SELECT COUNT(*) FROM h3_production_reports r
                 WHERE r.external_source='H3YUN' AND r.work_order_no=?
                """, Long.class, order.get("workOrderNo")));
        long completed = components.stream().filter(c -> c.get("effectiveCompleted") instanceof Number n && n.intValue() == 1).count();
        var localReports = jdbc.query("""
                SELECT id,component_external_id,component_name_snapshot,quantity,status,remark,
                       reported_by_name,reported_at,reversed_by_name,reversed_at,reversal_reason
                  FROM production_execution_reports WHERE BINARY order_external_id=BINARY ?
                 ORDER BY id DESC LIMIT 200
                """, this::row, order.get("externalId"));
        return Map.of("order", order, "components", components, "reports", reports,
                "reportCount", reportCount, "localReports", localReports,
                "componentCount", components.size(), "completedComponentCount", completed);
    }

    public long report(long orderId, String componentExternalId, ReportRequest request, String requestKey) {
        if (!users.can("manufacturing.report")) throw new SecurityException("没有车间报工权限");
        var order = visibleOrder(orderId);
        if (request == null || request.quantity() == null || request.quantity().signum() <= 0
                || request.quantity().scale() > 4) throw new IllegalArgumentException("报工数量必须大于0且最多4位小数");
        String remark = clean(request.remark(), 1000, "备注");
        var actor = actors.current();
        var payload = Map.of("orderId", orderId, "componentExternalId", componentExternalId,
                "quantity", request.quantity(), "remark", remark);
        return Long.parseLong(idem.execute(actor, requestKey, "h3.production.report", payload, () -> {
            var locked = lockOrder(orderId, order);
            if (sourceWarehoused(locked))
                throw new IllegalStateException("氚云来源已记录入库，不能继续本地报工");
            if (hasPostedCompletion(String.valueOf(locked.get("externalId"))))
                throw new IllegalStateException("该工单已完工入库，不能继续报工");
            var component = sourceComponent(orderId, componentExternalId);
            if ("已取消".equals(locked.get("status")) || "CANCELLED".equals(locked.get("status")))
                throw new IllegalStateException("已取消工单不能报工");
            if (doneFromSource(component)) throw new IllegalStateException("来源制程已完成，不能重复报工");
            String orderExternalId = String.valueOf(locked.get("externalId"));
            BigDecimal sourceQty = qty(component.get("completedQuantity"));
            jdbc.update("INSERT INTO production_execution_component_states(order_external_id,component_external_id,baseline_source_quantity) "
                    + "VALUES(?,?,?) ON DUPLICATE KEY UPDATE id=id", orderExternalId, componentExternalId, sourceQty);
            var state = jdbc.queryForMap("SELECT baseline_source_quantity,net_local_quantity FROM production_execution_component_states "
                    + "WHERE BINARY order_external_id=BINARY ? AND BINARY component_external_id=BINARY ? FOR UPDATE",
                    orderExternalId, componentExternalId);
            BigDecimal current = sourceQty.max(qty(state.get("baseline_source_quantity")).add(qty(state.get("net_local_quantity"))));
            BigDecimal required = qty(component.get("requiredQuantity"));
            if (required.signum() <= 0) throw new IllegalStateException("来源制程缺少有效需求数量，暂不能报工");
            if (current.add(request.quantity()).compareTo(required) > 0)
                throw new IllegalArgumentException("本次报工超过制程剩余数量，请先核对来源数量");
            jdbc.update("INSERT INTO production_execution_reports(order_external_id,component_external_id,work_order_no_snapshot,"
                    + "component_name_snapshot,quantity,remark,reported_by_employee_id,reported_by_name) VALUES(?,?,?,?,?,?,?,?)",
                    orderExternalId, componentExternalId, locked.get("workOrderNo"),
                    String.valueOf(component.get("componentName")), request.quantity(), remark,
                    actor.employeeId(), actor.name());
            long id = Objects.requireNonNull(jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class));
            jdbc.update("UPDATE production_execution_component_states SET net_local_quantity=net_local_quantity+? "
                    + "WHERE BINARY order_external_id=BINARY ? AND BINARY component_external_id=BINARY ?",
                    request.quantity(), orderExternalId, componentExternalId);
            audit.write(ErpChangeLogWriter.of(actor, "product_archive.production_execution_reports", id, "INSERT",
                    ErpChangeLogWriter.changes("orderExternalId", orderExternalId, "componentExternalId", componentExternalId,
                            "quantity", request.quantity(), "status", "VALID"), null, remark, requestKey, null));
            return String.valueOf(id);
        }));
    }

    public void reverse(long orderId, long reportId, ReverseRequest request, String requestKey) {
        if (!users.can("manufacturing.report.override")) throw new SecurityException("没有报工冲正权限");
        var order = visibleOrder(orderId);
        String reason = clean(request == null ? null : request.reason(), 1000, "冲正原因");
        if (reason.isBlank()) throw new IllegalArgumentException("请填写冲正原因");
        var actor = actors.current();
        var payload = Map.of("orderId", orderId, "reportId", reportId, "reason", reason);
        idem.execute(actor, requestKey, "h3.production.report.reverse", payload, () -> {
            var locked = lockOrder(orderId, order);
            if (hasPostedCompletion(String.valueOf(locked.get("externalId"))))
                throw new IllegalStateException("该工单已完工入库，请先冲正入库再冲正报工");
            var rows = jdbc.query("SELECT * FROM production_execution_reports WHERE id=? AND BINARY order_external_id=BINARY ? FOR UPDATE",
                    this::row, reportId, locked.get("externalId"));
            if (rows.isEmpty()) throw new IllegalArgumentException("本地报工记录不存在");
            var report = rows.getFirst();
            if (!"VALID".equals(report.get("status"))) throw new IllegalStateException("该报工已经冲正");
            String componentExternalId = String.valueOf(report.get("componentExternalId"));
            var component = sourceComponent(orderId, componentExternalId);
            Object sequence = component.get("sequenceNo");
            if (sequence instanceof Number number) {
                long later = Objects.requireNonNull(jdbc.queryForObject("""
                        SELECT COUNT(*) FROM production_execution_reports r
                        JOIN production_order_components c ON BINARY c.external_id=BINARY r.component_external_id
                        WHERE c.production_order_id=? AND c.sequence_no>? AND r.status='VALID'
                          AND BINARY r.order_external_id=BINARY ?
                        """, Long.class, orderId, number.intValue(), locked.get("externalId")));
                if (later > 0) throw new IllegalStateException("后续制程已有本地报工，请先冲正后续制程");
            }
            jdbc.update("UPDATE production_execution_reports SET status='REVERSED',reversal_reason=?,"
                    + "reversed_by_employee_id=?,reversed_by_name=?,reversed_at=NOW() WHERE id=?",
                    reason, actor.employeeId(), actor.name(), reportId);
            jdbc.update("UPDATE production_execution_component_states SET net_local_quantity=net_local_quantity-? "
                    + "WHERE BINARY order_external_id=BINARY ? AND BINARY component_external_id=BINARY ?",
                    report.get("quantity"), locked.get("externalId"), componentExternalId);
            audit.write(ErpChangeLogWriter.of(actor, "product_archive.production_execution_reports", reportId, "UPDATE",
                    ErpChangeLogWriter.changes("status", "VALID→REVERSED", "quantity", report.get("quantity"),
                            "reversalReason", reason), "REVERSAL", reason, requestKey, null));
            return String.valueOf(reportId);
        });
    }

    private Map<String,Object> visibleOrder(long id) {
        var params = new ArrayList<Object>();
        var where = visibleWhere(params).append(" AND p.id=?"); params.add(id);
        return jdbc.query("SELECT p.id,p.external_id,p.work_order_no,p.status,p.warehoused_quantity,p.warehousing_completed FROM production_orders p "
                + "LEFT JOIN employees e ON e.id=p.employee_id " + where, this::row, params.toArray())
                .stream().findFirst().orElseThrow(() -> new IllegalArgumentException("生产任务单不存在或无权操作"));
    }

    private Map<String,Object> lockOrder(long id, Map<String,Object> previouslyVisible) {
        var rows = jdbc.query("SELECT id,external_id,work_order_no,status,warehoused_quantity,warehousing_completed FROM production_orders "
                + "WHERE id=? AND external_source='H3YUN' AND BINARY external_id=BINARY ? FOR UPDATE",
                this::row, id, previouslyVisible.get("externalId"));
        if (rows.isEmpty()) throw new IllegalStateException("来源工单已变化，请刷新后重试");
        return rows.getFirst();
    }

    private Map<String,Object> sourceComponent(long orderId, String componentExternalId) {
        if (componentExternalId == null || componentExternalId.isBlank()) throw new IllegalArgumentException("制程缺少来源标识，无法报工");
        var rows = jdbc.query("SELECT external_id,sequence_no,component_name,required_quantity,completed_quantity,"
                + "completed,completed_at FROM production_order_components WHERE production_order_id=? "
                + "AND BINARY external_id=BINARY ?", this::row, orderId, componentExternalId);
        if (rows.size() != 1) throw new IllegalArgumentException("来源制程不存在或标识不唯一，无法报工");
        return rows.getFirst();
    }

    private boolean hasPostedCompletion(String orderExternalId) {
        return Objects.requireNonNull(jdbc.queryForObject("SELECT COUNT(*) FROM production_execution_completions "
                + "WHERE BINARY order_external_id=BINARY ? AND status='POSTED'", Long.class, orderExternalId)) > 0;
    }

    private static boolean sourceWarehoused(Map<String,Object> order) {
        Object quantity = order.get("warehousedQuantity");
        Object completed = order.get("warehousingCompleted");
        return quantity instanceof BigDecimal n && n.signum() > 0
                || Boolean.TRUE.equals(completed)
                || completed instanceof Number n && n.intValue() == 1;
    }

    private static boolean doneFromSource(Map<String,Object> component) {
        return component.get("completedAt") != null || Boolean.TRUE.equals(component.get("completed"))
                || component.get("completed") instanceof Number n && n.intValue() == 1;
    }
    private static BigDecimal qty(Object value) { return value instanceof BigDecimal n ? n : BigDecimal.ZERO; }
    private static String clean(String value, int max, String label) {
        String text = value == null ? "" : value.trim();
        if (text.length() > max) throw new IllegalArgumentException(label + "最多" + max + "字");
        return text;
    }

    private Map<String, Object> row(ResultSet rs, int ignored) throws SQLException {
        var result = new LinkedHashMap<String, Object>();
        var meta = rs.getMetaData();
        for (int i = 1; i <= meta.getColumnCount(); i++) {
            var key = new StringBuilder();
            boolean upper = false;
            for (char c : meta.getColumnLabel(i).toLowerCase(Locale.ROOT).toCharArray()) {
                if (c == '_') upper = true;
                else { key.append(upper ? Character.toUpperCase(c) : c); upper = false; }
            }
            Object value = rs.getObject(i);
            if (value instanceof java.sql.Date date) value = date.toLocalDate();
            if (value instanceof java.sql.Timestamp time) value = time.toLocalDateTime();
            result.put(key.toString(), value);
        }
        return result;
    }
}
