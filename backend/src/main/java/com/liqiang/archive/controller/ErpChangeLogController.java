package com.liqiang.archive.controller;

import com.liqiang.archive.security.RequirePermission;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ERP 行级变更日志查询端点（系统管理 → 操作日志 → ERP 变更）。
 *
 * <p><b>为什么需要它</b>：{@code erp.row_change_log} 从 M0 起就在写（制造域 9 处 + 出库单 4 处），
 * 但**一直没有查询端点**，前端「操作日志」页只读 {@code audit_logs}。结果是 ERP 的审计
 * 「写了没人看得见」—— 而这正是各业务 Service 当年**额外往 {@code audit_logs} 也写一份**的原因，
 * 也是「审计实体名撞名」这个反复出现的缺陷的根源（两个模块对同名概念用同一个
 * {@code entity_type} 就撞，且撞了不报错）。
 *
 * <p>补上本端点后，ERP 域审计单通道（方案 A）才成立：业务不再写 {@code audit_logs}，
 * 审计统一以 {@code target_table}（schema 限定表名，物理唯一）作身份。
 * 见 {@code docs/审计实体身份治理与撞名防治-20260921.md}。
 *
 * <p><b>为什么用主库 JdbcTemplate 查 {@code erp.} 表</b>：{@code erp} schema 与主库在
 * **同一 MySQL 实例**，限定名跨 schema 查询是一条连接的事，不需要第二个数据源
 * （决策 #19 的同一事实）。
 *
 * <p>本端点**只读**，不写任何表。
 */
@RestController
@RequestMapping("/api/erp-change-logs")
@RequirePermission("system.employee.manage")
public class ErpChangeLogController {

    /** 审计表限定名：不带 schema 会歧义（两侧表可能重名，见决策 #20②）。 */
    private static final String LOG_TABLE = "erp.row_change_log";

    /** 单页上限：与 {@code /api/audit-logs} 保持一致，避免拖库。 */
    private static final int MAX_PAGE_SIZE = 200;

    private final JdbcTemplate jdbc;

    public ErpChangeLogController(@Qualifier("jdbcTemplate") JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping
    public Map<String, Object> list(
            @RequestParam(required = false) String targetTable,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String operatorName,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String dateFrom,
            @RequestParam(required = false) String dateTo,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        List<Object> args = new ArrayList<>();
        String where = buildWhere(targetTable, action, operatorName, keyword, dateFrom, dateTo, args);

        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM " + LOG_TABLE + where,
                Long.class, args.toArray());
        long totalElements = total == null ? 0L : total;

        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(safeSize);
        pageArgs.add((long) safePage * safeSize);

        List<Map<String, Object>> content = jdbc.query(
                "SELECT id, target_table, target_id, action, changed_fields, reason_code, change_reason,"
                        + " operator_id, operator_name, request_key, trace_id, changed_at"
                        + " FROM " + LOG_TABLE + where
                        + " ORDER BY changed_at DESC, id DESC LIMIT ? OFFSET ?",
                ROW_MAPPER, pageArgs.toArray());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("content", content);
        result.put("totalElements", totalElements);
        result.put("totalPages", safeSize == 0 ? 0
                : (int) ((totalElements + safeSize - 1) / safeSize));
        result.put("number", safePage);
        result.put("size", safeSize);
        return result;
    }

    /** 筛选候选：被改动的表 / 动作 / 操作人。前端下拉用。 */
    @GetMapping("/facets")
    public Map<String, Object> facets() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("targetTables", jdbc.queryForList(
                "SELECT DISTINCT target_table FROM " + LOG_TABLE + " ORDER BY target_table", String.class));
        result.put("actions", jdbc.queryForList(
                "SELECT DISTINCT action FROM " + LOG_TABLE + " ORDER BY action", String.class));
        result.put("operatorNames", jdbc.queryForList(
                "SELECT DISTINCT operator_name FROM " + LOG_TABLE
                        + " WHERE operator_name IS NOT NULL ORDER BY operator_name", String.class));
        return result;
    }

    // ---- 内部 ----

    private static String buildWhere(String targetTable, String action, String operatorName,
                                     String keyword, String dateFrom, String dateTo, List<Object> args) {
        StringBuilder w = new StringBuilder(" WHERE 1=1 ");
        if (notBlank(targetTable)) {
            w.append(" AND target_table = ? ");
            args.add(targetTable.trim());
        }
        if (notBlank(action)) {
            w.append(" AND action = ? ");
            args.add(action.trim());
        }
        if (notBlank(operatorName)) {
            w.append(" AND operator_name = ? ");
            args.add(operatorName.trim());
        }
        if (notBlank(keyword)) {
            w.append(" AND (change_reason LIKE ? OR changed_fields LIKE ? OR target_table LIKE ?) ");
            String like = "%" + keyword.trim() + "%";
            args.add(like);
            args.add(like);
            args.add(like);
        }
        LocalDateTime from = parseDate(dateFrom, false);
        if (from != null) {
            w.append(" AND changed_at >= ? ");
            args.add(from);
        }
        LocalDateTime to = parseDate(dateTo, true);
        if (to != null) {
            w.append(" AND changed_at <= ? ");
            args.add(to);
        }
        return w.toString();
    }

    private static final RowMapper<Map<String, Object>> ROW_MAPPER = (rs, rowNum) -> {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", rs.getLong("id"));
        m.put("targetTable", rs.getString("target_table"));
        m.put("targetId", rs.getLong("target_id"));
        m.put("action", rs.getString("action"));
        // json 列按文本返回：前端要的是「改前改后长什么样」，不是再解析一层
        m.put("changedFields", rs.getString("changed_fields"));
        m.put("reasonCode", rs.getString("reason_code"));
        m.put("changeReason", rs.getString("change_reason"));
        m.put("operatorId", rs.getObject("operator_id"));
        m.put("operatorName", rs.getString("operator_name"));
        m.put("requestKey", rs.getString("request_key"));
        m.put("traceId", rs.getString("trace_id"));
        Timestamp ts = rs.getTimestamp("changed_at");
        m.put("changedAt", ts == null ? null : ts.toLocalDateTime().toString());
        return m;
    };

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static LocalDateTime parseDate(String s, boolean endOfDay) {
        if (!notBlank(s)) return null;
        try {
            LocalDate d = LocalDate.parse(s.trim());
            return endOfDay ? d.atTime(LocalTime.MAX) : d.atStartOfDay();
        } catch (Exception e) {
            return null;
        }
    }
}
