package com.liqiang.archive.service;

import com.liqiang.archive.security.CurrentUserService;
import com.liqiang.archive.security.FeatureCode;
import com.liqiang.archive.security.DataScopeSupport;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 销售出库订单提成（方案：docs/current-sales-profit-design.md §5.3/§9）。
 *
 * <p>核算单位 = 销售出库（sales_outbounds.id）。直接读取：
 * <ul>
 *   <li>业务员：sales_outbounds.employee_id；</li>
 *   <li>可计提利润：sales_outbound_profits.commissionable_profit；</li>
 *   <li>回款结果：sales_outbounds.received_amount / sales_amount；</li>
 *   <li>提成规则：business_commission_rules（员工专属优先，全局默认兜底）。</li>
 * </ul>
 *
 * <p>状态机：TRIAL（仅试算）→ WAITING_RULE（未映射业务员/未配置规则）→
 * NOT_AVAILABLE（负利润或未满足回款条件）→ AVAILABLE（可结算）→ SETTLED（已结算）。
 * 已结算记录不得被普通重算覆盖。
 */
@Service
public class SalesOutboundCommissionService {

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private final JdbcTemplate jdbc;
    private final DataScopeSupport scopeSupport;
    private final CurrentUserService currentUsers;
    private final AuditLogService auditLogService;

    public SalesOutboundCommissionService(JdbcTemplate jdbc,
                                          DataScopeSupport scopeSupport,
                                          CurrentUserService currentUsers,
                                          AuditLogService auditLogService) {
        this.jdbc = jdbc;
        this.scopeSupport = scopeSupport;
        this.currentUsers = currentUsers;
        this.auditLogService = auditLogService;
    }

    // ==================== 核心计算 ====================

    /**
     * 按销售出库重算提成（幂等 UPSERT）。由利润重算联动调用，也可手工触发。
     * 已结算记录跳过，避免普通重算改写历史工资。
     */
    @Transactional
    public void recalculate(Long outboundId, Long profitId, BigDecimal commissionableProfit, String operator) {
        Map<String, Object> o = oneOrNull("""
            SELECT o.id, o.outbound_no, o.employee_id, o.salesperson_name,
                   COALESCE(o.sales_amount,0) sales_amount, COALESCE(o.received_amount,0) received_amount
            FROM sales_outbounds o WHERE o.id=?
            """, outboundId);
        if (o == null) return;

        String settled = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sales_outbound_commissions WHERE sales_outbound_id=? AND status='SETTLED'",
                String.class, outboundId);
        if (settled != null && !"0".equals(settled)) return;

        Long employee = o.get("employeeId") == null ? null : ((Number) o.get("employeeId")).longValue();
        BigDecimal revenue = bd(o.get("salesAmount"));
        BigDecimal received = bd(o.get("receivedAmount"));
        if (received.signum() < 0) received = BigDecimal.ZERO;
        if (revenue.signum() > 0 && received.compareTo(revenue) > 0) received = revenue;

        // 可计提利润仅当销售额为正时成立（退货/冲销订单不得产生正向提成）
        BigDecimal basis = revenue.signum() > 0 && commissionableProfit != null && commissionableProfit.signum() > 0
                ? commissionableProfit : BigDecimal.ZERO;

        Map<String, Object> rule = resolveRule(employee);
        BigDecimal rate = rule == null ? BigDecimal.ZERO : bd(rule.get("commissionRate"));
        BigDecimal threshold = rule == null ? HUNDRED : bd(rule.get("receiptThresholdPct"));

        BigDecimal theoretical = basis.multiply(rate).divide(HUNDRED, 2, RoundingMode.HALF_UP);
        BigDecimal ratio = revenue.signum() > 0
                ? received.multiply(HUNDRED).divide(revenue, 4, RoundingMode.HALF_UP).min(HUNDRED)
                : BigDecimal.ZERO;
        // 老板/财务尚未确认正式发放规则：规则仅用于理论试算，不能形成可发工资。
        BigDecimal available = BigDecimal.ZERO;

        String status;
        if (employee == null) status = "WAITING_RULE";
        else if (rule == null) status = "WAITING_RULE";
        else if (basis.signum() == 0) status = "NOT_AVAILABLE";
        else status = "TRIAL";

        jdbc.update("""
            INSERT INTO sales_outbound_commissions(sales_outbound_id, employee_id, profit_id, rule_id,
                commissionable_profit, commission_rate, theoretical_commission, receipt_ratio_pct,
                available_commission, settled_commission, status, formula_version, calculated_at)
            VALUES(?,?,?,?,?,?,?,?,?,0,?,'V1',NOW())
            ON DUPLICATE KEY UPDATE
                employee_id=VALUES(employee_id), profit_id=VALUES(profit_id), rule_id=VALUES(rule_id),
                commissionable_profit=VALUES(commissionable_profit), commission_rate=VALUES(commission_rate),
                theoretical_commission=VALUES(theoretical_commission), receipt_ratio_pct=VALUES(receipt_ratio_pct),
                available_commission=VALUES(available_commission), status=VALUES(status),
                formula_version=VALUES(formula_version), calculated_at=NOW()
            """, outboundId, employee, profitId,
                rule == null ? null : ((Number) rule.get("id")).longValue(),
                basis, rate, theoretical, ratio, available, status);

    }

    /** 提成试算规则变化后，仅重算销售出库新主链，不再生成 BusinessOrder 提成快照。 */
    @Transactional
    public int recalculateByEmployee(Long employeeId) {
        String sql = """
            SELECT p.sales_outbound_id, p.id profit_id, p.commissionable_profit
            FROM sales_outbound_profits p
            JOIN sales_outbounds o ON o.id=p.sales_outbound_id
            WHERE p.calculation_status='CALCULATED' AND o.outbound_status='CONFIRMED'
            """ + (employeeId == null ? "" : " AND o.employee_id=?");
        List<Map<String, Object>> rows = employeeId == null
                ? jdbc.query(sql, this::row)
                : jdbc.query(sql, this::row, employeeId);
        for (Map<String, Object> row : rows) {
            recalculate(((Number) row.get("salesOutboundId")).longValue(),
                    ((Number) row.get("profitId")).longValue(),
                    bd(row.get("commissionableProfit")), null);
        }
        return rows.size();
    }

    /** 按业务员 + 有效期 + 优先级选择规则：员工专属优先，全局默认兜底。 */
    private Map<String, Object> resolveRule(Long employee) {
        if (employee == null) return null;
        Map<String, Object> rule = oneOrNull("""
            SELECT * FROM business_commission_rules
            WHERE status='ACTIVE' AND (employee_id=? OR employee_id IS NULL)
              AND effective_from<=CURRENT_DATE AND (effective_to IS NULL OR effective_to>=CURRENT_DATE)
            ORDER BY (employee_id IS NULL), priority, id LIMIT 1
            """, employee);
        if (rule == null) {
            rule = oneOrNull("""
                SELECT * FROM business_commission_rules
                WHERE status='ACTIVE' AND employee_id IS NULL
                  AND effective_from<=CURRENT_DATE AND (effective_to IS NULL OR effective_to>=CURRENT_DATE)
                ORDER BY priority, id LIMIT 1
                """);
        }
        return rule;
    }

    // ==================== 查询 ====================

    /** 分页查询订单提成（出库 + 提成结果），按行级数据范围。 */
    public Map<String, Object> pageCommissions(String keyword, int page, int size) {
        int sz = Math.max(1, Math.min(size <= 0 ? 20 : size, 100));
        int offset = Math.max(0, page - 1) * sz;
        StringBuilder where = new StringBuilder(" WHERE o.outbound_status='CONFIRMED'");
        List<Object> params = new ArrayList<>();
        scopeSupport.appendByEmployeeId(where, params, "o.employee_id", "e.department", FeatureCode.ORDER_COMMISSION);
        if (keyword != null && !keyword.isBlank()) {
            where.append(" AND (o.outbound_no LIKE ? OR COALESCE(o.customer_name,'') LIKE ?)");
            String like = "%" + keyword.trim() + "%";
            params.add(like);
            params.add(like);
        }
        Long total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sales_outbounds o LEFT JOIN employees e ON e.id=o.employee_id" + where,
                Long.class, params.toArray());
        List<Object> pageParams = new ArrayList<>(params);
        pageParams.add(sz);
        pageParams.add(offset);
        List<Map<String, Object>> rows = jdbc.query("""
            SELECT o.id, o.outbound_no, o.ship_date, o.customer_name, o.salesperson_name,
                   COALESCE(o.sales_amount,0) sales_amount, COALESCE(o.received_amount,0) received_amount,
                   COALESCE(o.outstanding_amount,0) outstanding_amount, o.payment_status,
                   c.id commission_id, c.commissionable_profit, c.commission_rate, c.theoretical_commission,
                   c.receipt_ratio_pct, c.available_commission, c.settled_commission, c.status, c.calculated_at
            FROM sales_outbounds o
            LEFT JOIN employees e ON e.id=o.employee_id
            LEFT JOIN sales_outbound_commissions c ON c.sales_outbound_id=o.id
            """ + where + " ORDER BY o.ship_date DESC, o.id DESC LIMIT ? OFFSET ?", this::row, pageParams.toArray());
        return Map.of("items", rows, "total", total == null ? 0L : total);
    }

    /** 单张销售出库提成详情。 */
    public Map<String, Object> commissionDetail(Long outboundId) {
        requireOutboundVisible(outboundId);
        return oneOrNull("""
            SELECT o.id, o.outbound_no, o.ship_date, o.customer_name, o.salesperson_name,
                   COALESCE(o.sales_amount,0) sales_amount, COALESCE(o.received_amount,0) received_amount,
                   COALESCE(o.outstanding_amount,0) outstanding_amount, o.payment_status,
                   c.id commission_id, c.employee_id, c.rule_id, c.commissionable_profit, c.commission_rate,
                   c.theoretical_commission, c.receipt_ratio_pct, c.available_commission,
                   c.settled_commission, c.status, c.calculated_at, r.rule_name
            FROM sales_outbounds o
            LEFT JOIN sales_outbound_commissions c ON c.sales_outbound_id=o.id
            LEFT JOIN business_commission_rules r ON r.id=c.rule_id
            WHERE o.id=?
            """, outboundId);
    }

    private void requireOutboundVisible(Long outboundId) {
        StringBuilder where = new StringBuilder(" WHERE o.id=?");
        List<Object> params = new ArrayList<>();
        params.add(outboundId);
        scopeSupport.appendByEmployeeId(where, params, "o.employee_id", "e.department", FeatureCode.ORDER_COMMISSION);
        Long n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sales_outbounds o LEFT JOIN employees e ON e.id=o.employee_id" + where,
                Long.class, params.toArray());
        if (n == null || n == 0) throw new SecurityException("无权查看该订单提成");
    }

    // ==================== 内部工具 ====================

    private Map<String, Object> oneOrNull(String sql, Object... args) {
        List<Map<String, Object>> rows = jdbc.query(sql, this::row, args);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private Map<String, Object> row(ResultSet rs, int n) throws SQLException {
        ResultSetMetaData md = rs.getMetaData();
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 1; i <= md.getColumnCount(); i++) m.put(camel(md.getColumnLabel(i)), rs.getObject(i));
        return m;
    }

    private String camel(String value) {
        StringBuilder out = new StringBuilder();
        boolean upper = false;
        for (char c : value.toLowerCase().toCharArray()) {
            if (c == '_') upper = true;
            else {
                out.append(upper ? Character.toUpperCase(c) : c);
                upper = false;
            }
        }
        return out.toString();
    }

    private BigDecimal bd(Object value) {
        if (value instanceof BigDecimal b) return b;
        return value == null ? BigDecimal.ZERO : new BigDecimal(value.toString());
    }
}
