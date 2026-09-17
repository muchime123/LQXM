package com.liqiang.archive.service;

import com.liqiang.archive.security.CurrentUserService;
import com.liqiang.archive.security.FeatureCode;
import com.liqiang.archive.security.DataScopeSupport;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 销售出库订单利润（方案：docs/current-sales-profit-design.md §5.2/§8）。
 *
 * <p>核算单位 = 销售出库（sales_outbounds.id），不再经过 BusinessOrder。
 *
 * <p>公式（§2.5）：
 * <pre>
 *   产品毛利            = 销售额 - 采购成本          （均为氚云主表最终值）
 *   订单实际利润        = 产品毛利 + 利润调整
 *   可计提利润          = MAX(订单实际利润 - 不参与提成金额, 0)
 * </pre>
 *
 * <p>采购成本为空/零/负数时只标记"氚云成本数据异常"，不本地补算。
 * 重算幂等（UPSERT）；重算后联动刷新订单提成。
 */
@Service
public class SalesOutboundProfitService {

    // 只描述数值事实，不凭备注自动认定换货或绑定原销售单。
    private static final String NATURE_CASE = """
        CASE
          WHEN EXISTS (SELECT 1 FROM sales_outbound_items ni WHERE ni.sales_outbound_id=o.id AND ni.quantity<0)
           AND EXISTS (SELECT 1 FROM sales_outbound_items pi WHERE pi.sales_outbound_id=o.id AND pi.quantity>0)
            THEN 'MIXED'
          WHEN o.sales_amount<0 THEN 'NEGATIVE'
          WHEN o.sales_amount=0 THEN 'ZERO_VALUE'
          WHEN EXISTS (SELECT 1 FROM sales_outbound_items ni WHERE ni.sales_outbound_id=o.id AND ni.quantity<0)
            THEN 'RETURN_LINES'
          ELSE 'SALE'
        END
        """;

    /** 销售出库是否具备进入利润核算的最小条件。只诊断氚云最终字段，不本地修正数据。 */
    private static final String READINESS_CASE = """
        CASE
          WHEN o.employee_id IS NULL THEN 'EMPLOYEE_UNMATCHED'
          WHEN o.sales_amount IS NULL THEN 'SALES_AMOUNT_ABNORMAL'
          WHEN o.purchase_amount IS NULL THEN 'PURCHASE_COST_ABNORMAL'
          WHEN ABS(COALESCE(o.received_amount,0)+COALESCE(o.outstanding_amount,0)-o.sales_amount)>0.02
            THEN 'RECEIPT_AMOUNT_ABNORMAL'
          WHEN o.sales_amount<=0 OR EXISTS (SELECT 1 FROM sales_outbound_items ri WHERE ri.sales_outbound_id=o.id AND ri.quantity<0)
            THEN 'SPECIAL_REVIEW'
          WHEN o.purchase_amount<=0 THEN 'PURCHASE_COST_ABNORMAL'
          ELSE 'READY'
        END
        """;

    private final JdbcTemplate jdbc;
    private final DataScopeSupport scopeSupport;
    private final CurrentUserService currentUsers;
    private final AuditLogService auditLogService;
    private final SalesOutboundCommissionService commissionService;

    public SalesOutboundProfitService(JdbcTemplate jdbc,
                                      DataScopeSupport scopeSupport,
                                      CurrentUserService currentUsers,
                                      AuditLogService auditLogService,
                                      SalesOutboundCommissionService commissionService) {
        this.jdbc = jdbc;
        this.scopeSupport = scopeSupport;
        this.currentUsers = currentUsers;
        this.auditLogService = auditLogService;
        this.commissionService = commissionService;
    }

    // ==================== 核心计算 ====================

    /**
     * 按销售出库幂等重算利润，并联动重算订单提成。
     * 调用点：出库导入/更新、利润调整新增/撤销、管理员手工修复。
     */
    @Transactional
    public Map<String, Object> recalculate(Long outboundId, String operator) {
        Map<String, Object> o = oneOrNull("""
            SELECT id, outbound_no, COALESCE(sales_amount,0) sales_amount,
                   COALESCE(purchase_amount,0) purchase_amount, outbound_status
            FROM sales_outbounds WHERE id=?
            """, outboundId);
        if (o == null) throw new IllegalArgumentException("销售出库不存在（id=" + outboundId + "）");
        if (!"CONFIRMED".equals(o.get("outboundStatus"))) {
            throw new IllegalStateException("销售出库尚未确认，不能参与利润与提成核算（出库号="
                    + o.get("outboundNo") + "）");
        }

        BigDecimal sales = bd(o.get("salesAmount"));
        BigDecimal cost = bd(o.get("purchaseAmount"));
        BigDecimal gross = sales.subtract(cost);

        // 钉钉报销是独立只读财务台账，不参与销售出库利润或月度提成。
        BigDecimal expense = BigDecimal.ZERO;

        // 利润调整：direction=PLUS 增利润 / MINUS 减利润；区分是否参与提成
        BigDecimal adjAll = adjustments(outboundId, null);
        BigDecimal adjCommission = adjustments(outboundId, Boolean.TRUE);

        BigDecimal accountingProfit = gross.subtract(expense).add(adjAll);
        // 不参与提成的调整净额 = 全部调整 - 参与提成调整；可计提利润 = 实际利润 - 不参与提成净额（下限 0）
        BigDecimal nonCommissionable = adjAll.subtract(adjCommission);
        BigDecimal commissionable = accountingProfit.subtract(nonCommissionable).max(BigDecimal.ZERO);

        // 成本异常标记：销售额为正但采购成本缺失/为负，或销售与成本均异常
        String costAbnormal = null;
        Long negativeLines = jdbc.queryForObject("SELECT COUNT(*) FROM sales_outbound_items WHERE sales_outbound_id=? AND quantity<0", Long.class, outboundId);
        if (sales.signum() > 0 && cost.signum() <= 0 && (negativeLines == null || negativeLines == 0)) {
            costAbnormal = "氚云成本数据异常：采购成本为" + (cost.signum() < 0 ? "负" : "零");
        }

        String operatorName = operator != null && !operator.isBlank() ? operator : operatorName();

        jdbc.update("""
            INSERT INTO sales_outbound_profits(sales_outbound_id, sales_amount, purchase_amount,
                product_gross_profit, confirmed_expense_amount, adjustment_amount, accounting_profit,
                non_commissionable_amount, commissionable_profit, calculation_status, cost_abnormal,
                formula_version, calculated_at)
            VALUES(?,?,?,?,?,?,?,?,?,
                   ?,?,'V1',NOW())
            ON DUPLICATE KEY UPDATE
                sales_amount=VALUES(sales_amount), purchase_amount=VALUES(purchase_amount),
                product_gross_profit=VALUES(product_gross_profit),
                confirmed_expense_amount=VALUES(confirmed_expense_amount),
                adjustment_amount=VALUES(adjustment_amount), accounting_profit=VALUES(accounting_profit),
                non_commissionable_amount=VALUES(non_commissionable_amount),
                commissionable_profit=VALUES(commissionable_profit),
                calculation_status=VALUES(calculation_status), cost_abnormal=VALUES(cost_abnormal),
                formula_version=VALUES(formula_version), calculated_at=NOW()
            """, outboundId, sales, cost, gross, expense, adjAll, accountingProfit,
                nonCommissionable, commissionable,
                costAbnormal == null ? "CALCULATED" : "ERROR", costAbnormal);

        Long profitId = jdbc.queryForObject(
                "SELECT id FROM sales_outbound_profits WHERE sales_outbound_id=?", Long.class, outboundId);

        // 联动提成（同事务内）
        commissionService.recalculate(outboundId, profitId, commissionable, operatorName);

        auditLogService.log("SalesOutboundProfit", outboundId, "订单利润", "PROFIT_RECALCULATE",
                "重算出库 " + o.get("outboundNo") + " 利润：毛利 " + gross + "，费用 " + expense
                        + "，调整 " + adjAll + "，实际利润 " + accountingProfit + "，可计提 " + commissionable
                        + "，操作人 " + operatorName);
        return outboundProfitDetail(outboundId);
    }

    // ==================== 利润调整 ====================

    /** 新增利润调整并立即重算。 */
    @Transactional
    public Map<String, Object> addAdjustment(Long outboundId, String type, BigDecimal amount,
                                             boolean plus, boolean affectsCommission, String reason) {
        requireOutboundVisible(outboundId);
        if (type == null || type.isBlank()) throw new IllegalArgumentException("请填写调整类型");
        if (amount == null || amount.signum() <= 0) throw new IllegalArgumentException("调整金额必须大于 0");
        if (reason == null || reason.isBlank()) throw new IllegalArgumentException("请填写调整原因");
        String operator = operatorName();
        jdbc.update("""
            INSERT INTO sales_outbound_profit_adjustments(sales_outbound_id, adjustment_type, amount,
                direction, affects_commission, reason, created_by)
            VALUES(?,?,?,?,?,?,?)
            """, outboundId, type.trim(), amount, plus ? "PLUS" : "MINUS", affectsCommission ? 1 : 0, reason.trim(), operator);
        auditLogService.log("SalesOutboundProfitAdjustment", null, "利润调整", "ADJUSTMENT_CREATE",
                "出库 " + outboundId + " 新增调整 " + type + " ¥" + amount + "（" + (plus ? "增加" : "减少")
                        + (affectsCommission ? "，参与提成" : "，不参与提成") + "），原因：" + reason + "，操作人 " + operator);
        return recalculate(outboundId, operator);
    }

    /** 撤销利润调整并立即重算。 */
    @Transactional
    public Map<String, Object> reverseAdjustment(Long adjustmentId) {
        Map<String, Object> a = oneOrNull("SELECT * FROM sales_outbound_profit_adjustments WHERE id=?", adjustmentId);
        if (a == null) throw new IllegalArgumentException("利润调整不存在");
        if ("REVERSED".equals(a.get("status"))) throw new IllegalArgumentException("该调整已撤销");
        requireOutboundVisible(((Number) a.get("salesOutboundId")).longValue());
        String operator = operatorName();
        jdbc.update("""
            UPDATE sales_outbound_profit_adjustments SET status='REVERSED', reversed_by=?, reversed_at=NOW()
            WHERE id=?
            """, operator, adjustmentId);
        auditLogService.log("SalesOutboundProfitAdjustment", adjustmentId, "利润调整", "ADJUSTMENT_REVERSE",
                "撤销调整 " + a.get("adjustmentType") + " ¥" + a.get("amount") + "，操作人 " + operator);
        return recalculate(((Number) a.get("salesOutboundId")).longValue(), operator);
    }

    // ==================== 查询 ====================

    /** 分页查询订单利润（出库 + 利润结果），按行级数据范围。
     *  当前已无独立"核算期"概念（利润/提成统计已下线，仅剩独立月度提成核算），
     *  故列表覆盖全部发货期间的已确认出库；未确认单经 status=PENDING_OUTBOUND 另行查看。 */
    public Map<String, Object> pageOutboundProfits(String keyword, String status, int page, int size) {
        return pageOutboundProfits(keyword, status, page, size, null);
    }

    public Map<String, Object> pageOutboundProfits(String keyword, String status, int page, int size, Long customerId) {
        if ("PENDING_OUTBOUND".equals(status)) return pendingOutbounds(keyword, page, size, customerId);
        int sz = Math.max(1, Math.min(size <= 0 ? 20 : size, 100));
        int normalizedPage = Math.max(0, page);
        int offset = normalizedPage * sz;
        StringBuilder where = new StringBuilder(" WHERE o.outbound_status='CONFIRMED'");
        List<Object> params = new ArrayList<>();
        if (customerId != null) { where.append(" AND o.customer_id=?"); params.add(customerId); }
        scopeSupport.appendByEmployeeId(where, params, "o.employee_id", "e.department", FeatureCode.ORDER_PROFIT);
        if (keyword != null && !keyword.isBlank()) {
            where.append(" AND (o.outbound_no LIKE ? OR COALESCE(o.customer_name,'') LIKE ? OR COALESCE(o.salesperson_name,'') LIKE ?"
                    + " OR COALESCE(o.region,'') LIKE ? OR COALESCE(o.customer_address,'') LIKE ?"
                    + " OR COALESCE(o.contact_phone,'') LIKE ? OR COALESCE(o.dealer_name,'') LIKE ? OR COALESCE(o.business_owner,'') LIKE ?)");
            String like = "%" + keyword.trim() + "%";
            for (int i = 0; i < 8; i++) params.add(like);
        }
        if (status != null && !status.isBlank()) {
            if ("COST_ABNORMAL".equals(status)) {
                where.append(" AND p.cost_abnormal IS NOT NULL");
            } else if ("CUSTOMER_UNMATCHED".equals(status)) {
                where.append(" AND o.customer_id IS NULL");
            } else if ("NOT_CALCULATED".equals(status)) {
                where.append(" AND (").append(READINESS_CASE)
                        .append(")='READY' AND (p.id IS NULL OR p.calculation_status<>'CALCULATED')");
            } else if (isReadinessStatus(status)) {
                where.append(" AND (").append(READINESS_CASE).append(")=?");
                params.add(status);
            } else {
                where.append(" AND p.calculation_status=?");
                params.add(status);
            }
        }
        Long total = jdbc.queryForObject("""
            SELECT COUNT(*) FROM sales_outbounds o
            LEFT JOIN employees e ON e.id=o.employee_id
            LEFT JOIN sales_outbound_profits p ON p.sales_outbound_id=o.id
            """ + where, Long.class, params.toArray());
        List<Object> pageParams = new ArrayList<>(params);
        pageParams.add(sz);
        pageParams.add(offset);
        String pageSql = """
            SELECT o.id, o.outbound_no, o.ship_date, o.customer_id, o.employee_id,
                   o.customer_name, o.salesperson_name, o.dealer_name, o.business_owner,
                   o.region, o.contact_phone, o.customer_address,
                   COALESCE(o.sales_amount,0) sales_amount, COALESCE(o.purchase_amount,0) purchase_amount,
                   COALESCE(o.received_amount,0) received_amount,
                   COALESCE(o.outstanding_amount,0) outstanding_amount,
                   (""" + NATURE_CASE + ") order_nature,(" + READINESS_CASE + ") readiness_status," + """
                   p.id profit_id, p.product_gross_profit, p.confirmed_expense_amount, p.adjustment_amount,
                   p.accounting_profit, p.commissionable_profit, p.calculation_status, p.cost_abnormal,
                   p.calculated_at, c.theoretical_commission, c.available_commission,
                   c.settled_commission, c.status commission_status
            FROM sales_outbounds o
            LEFT JOIN employees e ON e.id=o.employee_id
            LEFT JOIN sales_outbound_profits p ON p.sales_outbound_id=o.id
            LEFT JOIN sales_outbound_commissions c ON c.sales_outbound_id=o.id
            """ + where + " ORDER BY o.ship_date DESC, o.id DESC LIMIT ? OFFSET ?";
        List<Map<String, Object>> rows = jdbc.query(pageSql, this::row, pageParams.toArray());
        return Map.of("items", rows, "total", total == null ? 0L : total,
                "page", normalizedPage, "size", sz);
    }

    private boolean isReadinessStatus(String status) {
        return "READY".equals(status) || "SPECIAL_REVIEW".equals(status) || "EMPLOYEE_UNMATCHED".equals(status)
                || "SALES_AMOUNT_ABNORMAL".equals(status) || "PURCHASE_COST_ABNORMAL".equals(status)
                || "RECEIPT_AMOUNT_ABNORMAL".equals(status);
    }

    /** 单张销售出库利润详情（含调整、费用、提成）。 */
    public Map<String, Object> outboundProfitDetail(Long outboundId) {
        requireOutboundVisible(outboundId);
        String detailSql = """
            SELECT o.id, o.outbound_no, o.ship_date, o.business_date, o.customer_id, o.employee_id,
                   o.customer_name, o.dealer_name, o.salesperson_name, o.payment_status, o.freight,
                   o.warehouse, o.outbound_status,
                   o.purchase_amount source_purchase_amount, o.shipping_method, o.free_shipping,
                   o.warehouse_keeper, o.invoiced_amount, o.uninvoiced_amount, o.invoicer,
                   o.department_name, o.order_remark, o.collection_remark, o.source_updated_at,
                   COALESCE(o.sales_amount,0) sales_amount, COALESCE(o.purchase_amount,0) purchase_amount,
                   COALESCE(o.source_gross_profit,0) source_gross_profit,
                   COALESCE(o.received_amount,0) received_amount, COALESCE(o.outstanding_amount,0) outstanding_amount,
                   (""" + NATURE_CASE + ") order_nature,(" + READINESS_CASE + ") readiness_status," + """
                   p.id profit_id, p.product_gross_profit, p.confirmed_expense_amount, p.adjustment_amount,
                   p.accounting_profit, p.non_commissionable_amount, p.commissionable_profit,
                   p.calculation_status, p.cost_abnormal, p.formula_version, p.calculated_at,
                   c.id commission_id, c.rule_id, c.commission_rate, c.theoretical_commission,
                   c.receipt_ratio_pct, c.available_commission, c.settled_commission, c.status commission_status,
                   r.rule_name
            FROM sales_outbounds o
            LEFT JOIN employees e ON e.id=o.employee_id
            LEFT JOIN sales_outbound_profits p ON p.sales_outbound_id=o.id
            LEFT JOIN sales_outbound_commissions c ON c.sales_outbound_id=o.id
            LEFT JOIN business_commission_rules r ON r.id=c.rule_id
            WHERE o.id=?
            """;
        Map<String, Object> o = oneOrNull(detailSql, outboundId);
        if (o == null) throw new IllegalArgumentException("销售出库不存在或无权查看");

        // 产品明细（销售单价/采购单价）
        o.put("items", jdbc.query("""
            SELECT sequence_no, line_type, category, product_code, product_name, specification, unit,
                   quantity, sales_unit_price, sales_total, purchase_unit_price, purchase_total, source_profit
            FROM sales_outbound_items WHERE sales_outbound_id=?
            ORDER BY COALESCE(sequence_no, 999999), id
            """, this::row, outboundId));
        // 利润调整
        o.put("adjustments", jdbc.query("""
            SELECT * FROM sales_outbound_profit_adjustments WHERE sales_outbound_id=?
            ORDER BY id DESC
            """, this::row, outboundId));
        return o;
    }

    // ==================== 内部工具 ====================

    private BigDecimal adjustments(Long outboundId, Boolean affectsCommission) {
        StringBuilder sql = new StringBuilder("""
            SELECT COALESCE(SUM(CASE WHEN direction='PLUS' THEN amount ELSE -amount END),0)
            FROM sales_outbound_profit_adjustments
            WHERE sales_outbound_id=? AND status='ACTIVE'
            """);
        List<Object> params = new ArrayList<>();
        params.add(outboundId);
        if (affectsCommission != null) {
            sql.append(" AND affects_commission=?");
            params.add(affectsCommission ? 1 : 0);
        }
        BigDecimal v = jdbc.queryForObject(sql.toString(), BigDecimal.class, params.toArray());
        return v == null ? BigDecimal.ZERO : v;
    }

    private BigDecimal decimal(String sql, Object... args) {
        BigDecimal b = jdbc.queryForObject(sql, BigDecimal.class, args);
        return b == null ? BigDecimal.ZERO : b;
    }

    /** 未确认源单只供查看，不连接利润与提成结果；沿用订单行级权限。 */
    private Map<String, Object> pendingOutbounds(String keyword, int page, int size, Long customerId) {
        StringBuilder where = new StringBuilder(" WHERE (o.outbound_status IS NULL OR o.outbound_status<>'CONFIRMED')");
        List<Object> params = new ArrayList<>();
        if (customerId != null) { where.append(" AND o.customer_id=?"); params.add(customerId); }
        scopeSupport.appendByEmployeeId(where, params, "o.employee_id", "e.department", FeatureCode.ORDER_PROFIT);
        if (keyword != null && !keyword.isBlank()) {
            where.append(" AND (o.outbound_no LIKE ? OR o.customer_name LIKE ? OR o.salesperson_name LIKE ?)");
            for (int i = 0; i < 3; i++) params.add("%" + keyword.trim() + "%");
        }
        String from = " FROM sales_outbounds o LEFT JOIN employees e ON e.id=o.employee_id" + where;
        Long total = jdbc.queryForObject("SELECT COUNT(*)" + from, Long.class, params.toArray());
        int sz = Math.max(1, Math.min(size <= 0 ? 20 : size, 100));
        int pg = Math.max(0, page);
        params.add(sz); params.add(pg * sz);
        List<Map<String, Object>> items = jdbc.query("""
            SELECT o.id,o.outbound_no,o.outbound_status,o.ship_date,o.customer_name,o.salesperson_name,
                   o.sales_amount,o.purchase_amount,o.received_amount,o.outstanding_amount
            """ + from + " ORDER BY o.ship_date DESC,o.id DESC LIMIT ? OFFSET ?", this::row, params.toArray());
        return Map.of("items", items, "total", total == null ? 0L : total, "page", pg, "size", sz);
    }

    private void requireOutboundVisible(Long outboundId) {
        StringBuilder where = new StringBuilder(" WHERE o.id=?");
        List<Object> params = new ArrayList<>();
        params.add(outboundId);
        scopeSupport.appendByEmployeeId(where, params, "o.employee_id", "e.department", FeatureCode.ORDER_PROFIT);
        Long n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sales_outbounds o LEFT JOIN employees e ON e.id=o.employee_id" + where,
                Long.class, params.toArray());
        if (n == null || n == 0) throw new SecurityException("无权查看该订单利润");
    }

    private String operatorName() {
        var user = currentUsers.current();
        return user.name() == null ? "system" : user.name();
    }

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
