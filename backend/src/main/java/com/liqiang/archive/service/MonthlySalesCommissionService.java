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
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * 月度提成核算（现行简化口径，2026-08 财务确认）。
 *
 * <p>业务参考只依赖销售出库事实并实时汇总；正式核算由 CommissionCalculationService
 * 生成版本与逐笔来源快照。本服务同时返回实时参考和有效版本来源累计，两种口径互不覆盖：
 * <pre>
 *   本月销售额   = SUM(sales_outbounds.sales_amount)
 *                 WHERE COALESCE(ship_date,business_date) 属于本月（发货口径，按经销商/零售类型拆分）
 *   本月实际回款 = SUM(sales_outbound_payment_snapshots.received_amount)
 *                 WHERE receipt_date 属于本月 AND legacy_status='CONFIRMED'
 *   上月运杂费   = SUM(sales_outbounds.freight)
 *                 WHERE COALESCE(ship_date,business_date) 属于上月（按经销商/零售类型拆分）
 *   经销商判定   = dealer_name 非空，或毛利率低于 15%（防漏填经销商导致漏算低毛利经销商单）
 *                 毛利率 = COALESCE(source_gross_profit,0) / sales_amount
 *   提成金额     = (经销商回款 − 上月经销商运杂费) × 经销商比例
 *                + (零售回款 − 上月零售运杂费) × 零售比例
 * </pre>
 *
 * <p>提成比例按业务员配置（{@code monthly_commission_rates}，财务可维护）：
 * 默认（未单独配置）经销商/零售均 4%；李水云全部 1%；叶宪克经销商 1%/零售 4%；邵林经销商 1%/零售 3%。
 *
 * <p>与旧提成体系（BusinessCommission / SalesOutboundCommission）完全隔离，
 * 后续正式提成制度重设计时本页仅作为历史核算视图保留或替换，不互相污染。
 *
 * <p>行级数据范围：复用 {@link DataScopeSupport#appendByEmployeeId}，
 * SELF 仅本人、DEPARTMENT 仅本部门、ALL 全部。
 */
@Service
public class MonthlySalesCommissionService {

    /** 月度提成只认氚云销售出库；历史恢复来源仍属于氚云事实。 */
    private static final String H3_SOURCE_FILTER =
            "o.external_source IN ('H3YUN','H3YUN_RECOVERED','H3_HISTORICAL_RECOVERED')";

    private static final String RATE_ENTITY = "MonthlyCommissionRate";

    private final JdbcTemplate jdbc;
    private final DataScopeSupport scopeSupport;
    private final CurrentUserService currentUsers;
    private final AuditLogService auditLog;

    public MonthlySalesCommissionService(JdbcTemplate jdbc,
                                         DataScopeSupport scopeSupport,
                                         CurrentUserService currentUsers,
                                         AuditLogService auditLog) {
        this.jdbc = jdbc;
        this.scopeSupport = scopeSupport;
        this.currentUsers = currentUsers;
        this.auditLog = auditLog;
    }

    // ==================== 汇总（任意日期范围） ====================

    /**
     * 指定日期范围 [start,end] 的提成核算汇总：顶部指标 + 业务员汇总行 + 数据异常提示。
     * 仅做范围过滤后的实时合计，不落库。
     *
     * <p>口径（2026-09-01 财务确认）：
     * 期间内实际回款 = 收款日期 ∈ [start,end]（未收款/应收款按时间段计算）；
     * 运杂费扣减（上一期）= 发货日期 ∈ 「结束日所在月的前一个完整自然月」（财务口径：运杂费按整月计算，
     * 不随核算时间段切分；期间恰为自然月时 = 上月运杂费，与旧口径一致）。
     */
    public Map<String, Object> summary(LocalDate start, LocalDate end) {
        return summary(start, end, CommissionPolicy.FREIGHT_MODE_PREVIOUS_CALENDAR_MONTH, null, null);
    }

    /** 带运费范围模式的汇总（G4）：mode 缺省/为空 = 现行默认口径；CUSTOM 须同时提供起止日期。 */
    public Map<String, Object> summary(LocalDate start, LocalDate end,
                                       String freightMode, LocalDate freightStart, LocalDate freightEnd) {
        validateRange(start, end);
        CommissionPolicy.FreightWindow fw = CommissionPolicy.resolveFreightWindow(
                start, end, freightMode, freightStart, freightEnd);
        String actualMode = fw.mode();
        LocalDate curStart = start;
        LocalDate curEnd = end.plusDays(1);          // 半开区间
        // 运杂费扣减窗口由运费范围模式决定（默认 = 结束日所在月的前一个完整自然月，财务口径：运杂费按整月）
        LocalDate prevStart = fw.startInclusive();
        LocalDate prevEnd = fw.endExclusive();       // 半开区间

        // 本月实际回款（按业务员，拆经销商/零售；经销商含低利润兜底判定）
        Map<Long, Map<String, Object>> receiptByEmp = queryByEmployee(
            "SELECT COALESCE(o.employee_id,0) employee_id,\n"
            + "       COALESCE(MAX(o.salesperson_name),'') employee_name,\n"
            + "       COALESCE(SUM(ps.received_amount),0) receipt_amount,\n"
            + "       COALESCE(SUM(CASE WHEN (" + CommissionPolicy.DEALER_CASE + ") THEN ps.received_amount ELSE 0 END),0) dealer_receipt,\n"
            + "       COALESCE(SUM(CASE WHEN NOT (" + CommissionPolicy.DEALER_CASE + ") THEN ps.received_amount ELSE 0 END),0) retail_receipt\n"
            + "FROM sales_outbound_payment_snapshots ps\n"
            + "JOIN sales_outbounds o ON o.id=ps.sales_outbound_id\n"
            + "LEFT JOIN employees e ON e.id=o.employee_id\n"
            + "WHERE ps.receipt_date>=? AND ps.receipt_date<?\n"
            + "  AND ps.legacy_status='CONFIRMED'\n"
            + "  AND o.outbound_status='CONFIRMED'\n"
            + "  AND " + H3_SOURCE_FILTER + "\n"
            + "  AND o.employee_id IS NOT NULL\n",
            List.of(curStart, curEnd));

        // 上月运杂费（按业务员，拆经销商/零售；经销商含低利润兜底判定）
        Map<Long, Map<String, Object>> freightByEmp = queryByEmployee(
            "SELECT COALESCE(o.employee_id,0) employee_id,\n"
            + "       COALESCE(MAX(o.salesperson_name),'') employee_name,\n"
            + "       COALESCE(SUM(COALESCE(o.freight,0)),0) freight_amount,\n"
            + "       COALESCE(SUM(CASE WHEN (" + CommissionPolicy.DEALER_CASE + ") THEN COALESCE(o.freight,0) ELSE 0 END),0) dealer_freight,\n"
            + "       COALESCE(SUM(CASE WHEN NOT (" + CommissionPolicy.DEALER_CASE + ") THEN COALESCE(o.freight,0) ELSE 0 END),0) retail_freight\n"
            + "FROM sales_outbounds o\n"
            + "LEFT JOIN employees e ON e.id=o.employee_id\n"
            + "WHERE o.attribution_date>=? AND o.attribution_date<?\n"
            + "  AND o.outbound_status='CONFIRMED'\n"
            + "  AND " + H3_SOURCE_FILTER + "\n"
            + "  AND o.employee_id IS NOT NULL\n",
            List.of(prevStart, prevEnd));

        // 期间销售与未收款均直接使用氚云销售出库主单快照；不在本系统二次分配或推算未收款。
        Map<Long, Map<String, Object>> salesByEmp = queryByEmployee(
            "SELECT COALESCE(o.employee_id,0) employee_id,\n"
            + "       COALESCE(MAX(o.salesperson_name),'') employee_name,\n"
            + "       COALESCE(SUM(COALESCE(o.sales_amount,0)),0) sales_amount,\n"
            + "       COALESCE(SUM(CASE WHEN (" + CommissionPolicy.DEALER_CASE + ") THEN COALESCE(o.sales_amount,0) ELSE 0 END),0) dealer_sales,\n"
            + "       COALESCE(SUM(CASE WHEN NOT (" + CommissionPolicy.DEALER_CASE + ") THEN COALESCE(o.sales_amount,0) ELSE 0 END),0) retail_sales\n"
            + "       ,COALESCE(SUM(COALESCE(o.source_received_amount,o.received_amount,0)),0) source_collected\n"
            + "       ,COALESCE(SUM(CASE WHEN (" + CommissionPolicy.DEALER_CASE + ") THEN COALESCE(o.source_received_amount,o.received_amount,0) ELSE 0 END),0) dealer_collected\n"
            + "       ,COALESCE(SUM(CASE WHEN NOT (" + CommissionPolicy.DEALER_CASE + ") THEN COALESCE(o.source_received_amount,o.received_amount,0) ELSE 0 END),0) retail_collected\n"
            + "       ,COALESCE(SUM(COALESCE(o.outstanding_amount,0)),0) source_uncollected\n"
            + "FROM sales_outbounds o\n"
            + "LEFT JOIN employees e ON e.id=o.employee_id\n"
            + "WHERE o.attribution_date>=? AND o.attribution_date<?\n"
            + "  AND o.outbound_status='CONFIRMED'\n"
            + "  AND " + H3_SOURCE_FILTER + "\n"
            + "  AND o.employee_id IS NOT NULL\n",
            List.of(curStart, curEnd));

        Map<Long, List<CommissionPolicy.RateEntry>> rateHistory = CommissionPolicy.loadRateHistory(jdbc);
        Map<Long, BigDecimal[]> contributions = sourceContributions(curStart, curEnd, prevStart, prevEnd, rateHistory);

        // 月度参考始终展示氚云实时事实。历史核算金额单独放入 settlement，绝不覆盖实时字段；
        // 日期范围可重叠后，不能用某一个同范围版本冒充整个期间结果。
        Map<String, Object> settled = settledVersionFor(start, end);
        // 合并业务员行（本月有回款、运杂费扣减或销售额即纳入）
        List<Map<String, Object>> items = new ArrayList<>();
        BigDecimal totalSales = BigDecimal.ZERO;
        BigDecimal totalDealerSales = BigDecimal.ZERO;
        BigDecimal totalRetailSales = BigDecimal.ZERO;
        BigDecimal totalReceipt = BigDecimal.ZERO;
        BigDecimal totalFreight = BigDecimal.ZERO;
        BigDecimal totalDealerFreight = BigDecimal.ZERO;
        BigDecimal totalRetailFreight = BigDecimal.ZERO;
        BigDecimal totalDealerReceipt = BigDecimal.ZERO;
        BigDecimal totalRetailReceipt = BigDecimal.ZERO;
        BigDecimal totalCollected = BigDecimal.ZERO;
        BigDecimal totalCommission = BigDecimal.ZERO;
        BigDecimal totalBaseCommission = BigDecimal.ZERO;
        BigDecimal totalAdjCommission = BigDecimal.ZERO;
        TreeSet<Long> allEmpIds = new TreeSet<>();
        allEmpIds.addAll(salesByEmp.keySet());
        allEmpIds.addAll(receiptByEmp.keySet());
        allEmpIds.addAll(freightByEmp.keySet());
        for (Long empId : allEmpIds) {
            Map<String, Object> s = salesByEmp.get(empId);
            Map<String, Object> r = receiptByEmp.get(empId);
            Map<String, Object> f = freightByEmp.get(empId);
            String name = r != null ? (String) r.get("employeeName")
                    : f != null ? (String) f.get("employeeName")
                    : (String) s.get("employeeName");
            BigDecimal sales = s == null ? BigDecimal.ZERO : (BigDecimal) s.get("salesAmount");
            BigDecimal dealerSales = s == null ? BigDecimal.ZERO : (BigDecimal) s.get("dealerSales");
            BigDecimal retailSales = s == null ? BigDecimal.ZERO : (BigDecimal) s.get("retailSales");
            BigDecimal receipt = r == null ? BigDecimal.ZERO : (BigDecimal) r.get("receiptAmount");
            BigDecimal dealerReceipt = r == null ? BigDecimal.ZERO : (BigDecimal) r.get("dealerReceipt");
            BigDecimal retailReceipt = r == null ? BigDecimal.ZERO : (BigDecimal) r.get("retailReceipt");
            BigDecimal dealerFreight = f == null ? BigDecimal.ZERO : (BigDecimal) f.get("dealerFreight");
            BigDecimal retailFreight = f == null ? BigDecimal.ZERO : (BigDecimal) f.get("retailFreight");
            BigDecimal freight = dealerFreight.add(retailFreight);
            // 核心考核指标：直接采用氚云出库主单「已收款金额 / 未收款金额」。
            BigDecimal collected = s == null ? BigDecimal.ZERO : (BigDecimal) s.get("sourceCollected");
            BigDecimal dealerCollected = s == null ? BigDecimal.ZERO : (BigDecimal) s.get("dealerCollected");
            BigDecimal retailCollected = s == null ? BigDecimal.ZERO : (BigDecimal) s.get("retailCollected");
            BigDecimal uncollected = s == null ? BigDecimal.ZERO : (BigDecimal) s.get("sourceUncollected");
            BigDecimal collectionRate = collectionRate(collected, collected.add(uncollected));
            // 与正式结账一致：每笔来源按发生日追溯比例，最后按业务员汇总取分。
            CommissionPolicy.Rate rate = CommissionPolicy.rateAt(rateHistory, empId, null);
            BigDecimal[] sourceContribution = contributions.getOrDefault(empId,
                    new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
            BigDecimal dealerCommission = money(sourceContribution[0]);
            BigDecimal baseCommission = money(sourceContribution[0].add(sourceContribution[1]));
            BigDecimal retailCommission = baseCommission.subtract(dealerCommission);
            BigDecimal adjCommission = BigDecimal.ZERO;
            BigDecimal commissionAmount = baseCommission;

            totalSales = totalSales.add(sales);
            totalDealerSales = totalDealerSales.add(dealerSales);
            totalRetailSales = totalRetailSales.add(retailSales);
            totalReceipt = totalReceipt.add(receipt);
            totalFreight = totalFreight.add(freight);
            totalDealerFreight = totalDealerFreight.add(dealerFreight);
            totalRetailFreight = totalRetailFreight.add(retailFreight);
            totalDealerReceipt = totalDealerReceipt.add(dealerReceipt);
            totalRetailReceipt = totalRetailReceipt.add(retailReceipt);
            totalCollected = totalCollected.add(collected);
            totalBaseCommission = totalBaseCommission.add(baseCommission);
            totalAdjCommission = totalAdjCommission.add(adjCommission);
            totalCommission = totalCommission.add(commissionAmount);

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("employeeId", empId);
            row.put("employeeName", name);
            row.put("currentMonthSalesAmount", sales);
            row.put("dealerSalesAmount", dealerSales);
            row.put("retailSalesAmount", retailSales);
            row.put("currentMonthReceiptAmount", receipt);
            row.put("dealerReceiptAmount", dealerReceipt);
            row.put("retailReceiptAmount", retailReceipt);
            row.put("collectedAmount", collected);
            row.put("dealerCollectedAmount", dealerCollected);
            row.put("retailCollectedAmount", retailCollected);
            row.put("uncollectedAmount", uncollected);
            row.put("collectionRate", collectionRate);
            row.put("previousMonthFreightAmount", freight);
            row.put("dealerFreightAmount", dealerFreight);
            row.put("retailFreightAmount", retailFreight);
            row.put("commissionBase", receipt.subtract(freight));
            row.put("dealerRate", rate.dealerRate());
            row.put("retailRate", rate.retailRate());
            row.put("dealerCommission", dealerCommission);
            row.put("retailCommission", retailCommission);
            row.put("baseCommission", baseCommission);
            row.put("adjCommission", adjCommission);
            row.put("commissionAmount", commissionAmount);
            items.add(row);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("startDate", start.toString());
        result.put("endDate", end.toString());
        result.put("period", periodLabel(start, end));
        LocalDate prevEndIncl = prevEnd.minusDays(1);
        result.put("previousStartDate", prevStart.toString());
        result.put("previousEndDate", prevEndIncl.toString());
        result.put("previousPeriod", periodLabel(prevStart, prevEndIncl));
        result.put("freightPeriodMode", actualMode);
        result.put("currentMonthSalesAmount", totalSales);
        result.put("dealerSalesAmount", totalDealerSales);
        result.put("retailSalesAmount", totalRetailSales);
        result.put("currentMonthReceiptAmount", totalReceipt);
        result.put("dealerReceiptAmount", totalDealerReceipt);
        result.put("retailReceiptAmount", totalRetailReceipt);
        result.put("totalCollectedAmount", totalCollected);
        BigDecimal totalUncollected = items.stream()
                .map(item -> (BigDecimal) item.get("uncollectedAmount"))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        result.put("totalUncollectedAmount", totalUncollected);
        result.put("collectionRate", collectionRate(totalCollected, totalCollected.add(totalUncollected)));
        result.put("previousMonthFreightAmount", totalFreight);
        result.put("dealerFreightAmount", totalDealerFreight);
        result.put("retailFreightAmount", totalRetailFreight);
        result.put("commissionBase", totalReceipt.subtract(totalFreight));
        result.put("totalBaseCommission", totalBaseCommission);
        result.put("totalAdjCommission", totalAdjCommission);
        result.put("commissionAmount", totalCommission);
        result.put("employeeCount", items.size());
        result.put("items", items);
        result.put("issues", collectIssues(start, end, curStart, curEnd, prevStart, prevEnd));
        result.put("generatedAt", LocalDate.now().toString());
        result.put("settlement", settlementStatus(curStart, curEnd, prevStart, prevEnd));
        // 已结账标记：期间已生成核算版本时，比例/回款/运杂费/提成以版本快照为准
        result.put("settled", settled != null);
        if (settled != null) {
            result.put("settledVersionId", settled.get("id"));
            result.put("settledVersionNo", settled.get("versionNo"));
            result.put("settledAt", String.valueOf(settled.get("calculatedAt")));
        }
        return result;
    }

    /**
     * 当前查询窗口内已被有效版本占用的来源累计。按来源发生日统计，因此能正确处理重叠日期范围；
     * VOID 版本和 RELEASED 来源不参与。实时试算与冻结结果保持为两个独立口径。
     */
    private Map<String, Object> settlementStatus(LocalDate receiptStart, LocalDate receiptEnd,
                                                  LocalDate freightStart, LocalDate freightEnd) {
        String window = """
            l.status='ACTIVE' AND (
                (l.line_type='RECEIPT' AND l.occurrence_date>=? AND l.occurrence_date<?) OR
                (l.line_type='FREIGHT' AND l.occurrence_date>=? AND l.occurrence_date<?) OR
                (l.line_type='ADJUSTMENT' AND l.occurrence_date>=? AND l.occurrence_date<?)
            )
            """;
        StringBuilder scope = new StringBuilder();
        List<Object> params = new ArrayList<>(List.of(
                receiptStart, receiptEnd, freightStart, freightEnd, receiptStart, receiptEnd));
        List<Object> scopeParams = new ArrayList<>();
        scopeSupport.appendByEmployeeId(scope, scopeParams, "l.employee_id", "e.department",
                FeatureCode.MONTHLY_COMMISSION);
        params.addAll(scopeParams);
        Map<String, Object> totals = oneOrNull("""
            SELECT COUNT(DISTINCT v.id) version_count,
                   COUNT(l.id) source_line_count,
                   COALESCE(SUM(CASE WHEN l.line_type<>'ADJUSTMENT' THEN l.commission_contribution ELSE 0 END),0) settled_base,
                   COALESCE(SUM(CASE WHEN l.line_type='ADJUSTMENT' THEN l.commission_contribution ELSE 0 END),0) settled_adjustment,
                   COALESCE(SUM(l.commission_contribution),0) settled_total,
                   COALESCE(SUM(CASE WHEN v.paid_flag='PAID' THEN l.commission_contribution ELSE 0 END),0) paid_total
            FROM commission_calculation_source_lines l
            JOIN commission_calculation_versions v ON v.id=l.version_id AND v.status='FINAL'
            LEFT JOIN employees e ON e.id=l.employee_id
            """ + " WHERE " + window + scope, params.toArray());
        List<Map<String, Object>> employeeRows = jdbc.query("""
            SELECT l.employee_id, COALESCE(MAX(l.employee_name),MAX(e.name),'') employee_name,
                   COALESCE(SUM(CASE WHEN l.line_type<>'ADJUSTMENT' THEN l.commission_contribution ELSE 0 END),0) settled_base,
                   COALESCE(SUM(CASE WHEN l.line_type='ADJUSTMENT' THEN l.commission_contribution ELSE 0 END),0) settled_adjustment,
                   COALESCE(SUM(l.commission_contribution),0) settled_total,
                   COALESCE(SUM(CASE WHEN v.paid_flag='PAID' THEN l.commission_contribution ELSE 0 END),0) paid_total
            FROM commission_calculation_source_lines l
            JOIN commission_calculation_versions v ON v.id=l.version_id AND v.status='FINAL'
            LEFT JOIN employees e ON e.id=l.employee_id
            """ + " WHERE " + window + scope + " GROUP BY l.employee_id ORDER BY employee_name",
                this::row, params.toArray());
        for (Map<String, Object> employee : employeeRows) {
            BigDecimal employeeSettled = dec(employee, "settledTotal");
            BigDecimal employeePaid = dec(employee, "paidTotal");
            employee.put("settledBaseCommission", money(dec(employee, "settledBase")));
            employee.put("settledAdjustmentCommission", money(dec(employee, "settledAdjustment")));
            employee.put("settledCommission", money(employeeSettled));
            employee.put("paidCommission", money(employeePaid));
            employee.put("unpaidCommission", money(employeeSettled.subtract(employeePaid)));
        }
        BigDecimal settledTotal = dec(totals, "settledTotal");
        BigDecimal paidTotal = dec(totals, "paidTotal");
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("versionCount", totals == null ? 0 : totals.get("versionCount"));
        status.put("sourceLineCount", totals == null ? 0 : totals.get("sourceLineCount"));
        status.put("settledBaseCommission", money(dec(totals, "settledBase")));
        status.put("settledAdjustmentCommission", money(dec(totals, "settledAdjustment")));
        status.put("settledCommission", money(settledTotal));
        status.put("paidCommission", money(paidTotal));
        status.put("unpaidCommission", money(settledTotal.subtract(paidTotal)));
        status.put("items", employeeRows);
        return status;
    }

    // ==================== 单业务员详情 ====================

    /**
     * 指定业务员某日期范围的核算明细：期间收款明细（含经销商/零售标识与单笔提成） + 上期运杂费明细 + 汇总公式。
     * 供页面下钻核对"怎么算出来的"。
     */
    public Map<String, Object> employeeDetail(Long employeeId, LocalDate start, LocalDate end) {
        return employeeDetail(employeeId, start, end, CommissionPolicy.FREIGHT_MODE_PREVIOUS_CALENDAR_MONTH, null, null);
    }

    /** 带运费范围模式的单业务员详情（G4）：仅改变运杂费扣减窗口；CUSTOM 须同时提供起止日期。 */
    public Map<String, Object> employeeDetail(Long employeeId, LocalDate start, LocalDate end,
                                              String freightMode, LocalDate freightStart, LocalDate freightEnd) {
        if (employeeId == null) throw new IllegalArgumentException("请指定业务员");
        if (!currentUsers.canAccessEmployee(employeeId, FeatureCode.MONTHLY_COMMISSION)) {
            throw new SecurityException("无权查看该业务员的月度提成");
        }
        validateRange(start, end);
        CommissionPolicy.FreightWindow fw = CommissionPolicy.resolveFreightWindow(
                start, end, freightMode, freightStart, freightEnd);
        String actualMode = fw.mode();
        LocalDate curStart = start;
        LocalDate curEnd = end.plusDays(1);
        // 运杂费扣减窗口由运费范围模式决定（默认 = 结束日所在月的前一个完整自然月）
        LocalDate prevStart = fw.startInclusive();
        LocalDate prevEnd = fw.endExclusive();       // 半开区间

        // 显示名优先取氚云出库单业务员名（财务核对口径），无记录时回退员工名册
        Map<String, Object> emp = oneOrNull("""
            SELECT COALESCE(
                (SELECT salesperson_name FROM sales_outbounds
                  WHERE employee_id=? AND COALESCE(salesperson_name,'')<>'' ORDER BY id DESC LIMIT 1),
                (SELECT name FROM employees WHERE id=?)) name
            """, employeeId, employeeId);
        String empName = emp == null || emp.get("name") == null ? "" : String.valueOf(emp.get("name"));

        Map<Long, List<CommissionPolicy.RateEntry>> rateHistory = CommissionPolicy.loadRateHistory(jdbc);
        CommissionPolicy.Rate rate = CommissionPolicy.rateAt(rateHistory, employeeId, null);
        Map<String, Object> settled = settledVersionFor(start, end);
        // 该期间已存在核算版本时，读取本业务员在该版本中的金额/比例快照（结账口径），供页面提示"已结账，以版本为准"
        Map<String, Object> settledItem = null;
        if (settled != null) {
            Long settledVersionId = settled.get("id") instanceof Number n ? n.longValue() : null;
            if (settledVersionId != null) {
                settledItem = oneOrNull("""
                    SELECT COALESCE(base_commission,0) base_commission,
                           COALESCE(adj_commission,0) adj_commission,
                           COALESCE(total_commission,0) total_commission,
                           dealer_rate, retail_rate, employee_name
                    FROM commission_calculation_items
                    WHERE version_id=? AND employee_id=?
                    """, settledVersionId, employeeId);
            }
        }

        List<Map<String, Object>> receipts = jdbc.query(
            "SELECT ps.id, o.id outbound_id, o.outbound_no, o.customer_name,\n"
            + "       ps.receipt_date, ps.received_amount amount,\n"
            + "       CASE WHEN (" + CommissionPolicy.DEALER_CASE + ") THEN 1 ELSE 0 END dealer_flag\n"
            + "FROM sales_outbound_payment_snapshots ps\n"
            + "JOIN sales_outbounds o ON o.id=ps.sales_outbound_id\n"
            + "WHERE ps.receipt_date>=? AND ps.receipt_date<?\n"
            + "  AND ps.legacy_status='CONFIRMED'\n"
            + "  AND o.outbound_status='CONFIRMED'\n"
            + "  AND " + H3_SOURCE_FILTER + "\n"
            + "  AND o.employee_id=?\n"
            + "ORDER BY ps.receipt_date, ps.id\n",
            this::row, curStart, curEnd, employeeId);

        List<Map<String, Object>> freights = jdbc.query(
            "SELECT o.id outbound_id, o.outbound_no, o.customer_name,\n"
            + "       o.ship_date, o.business_date, o.attribution_date, COALESCE(o.freight,0) freight,\n"
            + "       CASE WHEN (" + CommissionPolicy.DEALER_CASE + ") THEN 1 ELSE 0 END dealer_flag\n"
            + "FROM sales_outbounds o\n"
            + "WHERE o.attribution_date>=? AND o.attribution_date<?\n"
            + "  AND o.outbound_status='CONFIRMED'\n"
            + "  AND " + H3_SOURCE_FILTER + "\n"
            + "  AND o.employee_id=?\n"
            + "ORDER BY o.attribution_date, o.id\n",
            this::row, prevStart, prevEnd, employeeId);

        // 本月销售额（发货口径，合计与经销/零售拆分，供页面参考展示）
        Map<String, Object> salesRow = oneOrNull(
            "SELECT COALESCE(SUM(COALESCE(o.sales_amount,0)),0) sales_amount,\n"
            + "       COALESCE(SUM(CASE WHEN (" + CommissionPolicy.DEALER_CASE + ") THEN COALESCE(o.sales_amount,0) ELSE 0 END),0) dealer_sales,\n"
            + "       COALESCE(SUM(CASE WHEN NOT (" + CommissionPolicy.DEALER_CASE + ") THEN COALESCE(o.sales_amount,0) ELSE 0 END),0) retail_sales,\n"
            + "       COALESCE(SUM(COALESCE(o.source_received_amount,o.received_amount,0)),0) source_collected,\n"
            + "       COALESCE(SUM(CASE WHEN (" + CommissionPolicy.DEALER_CASE + ") THEN COALESCE(o.source_received_amount,o.received_amount,0) ELSE 0 END),0) dealer_collected,\n"
            + "       COALESCE(SUM(CASE WHEN NOT (" + CommissionPolicy.DEALER_CASE + ") THEN COALESCE(o.source_received_amount,o.received_amount,0) ELSE 0 END),0) retail_collected,\n"
            + "       COALESCE(SUM(COALESCE(o.outstanding_amount,0)),0) source_uncollected\n"
            + "FROM sales_outbounds o\n"
            + "WHERE o.attribution_date>=? AND o.attribution_date<?\n"
            + "  AND o.outbound_status='CONFIRMED'\n"
            + "  AND " + H3_SOURCE_FILTER + "\n"
            + "  AND o.employee_id=?\n",
            curStart, curEnd, employeeId);
        BigDecimal sales = salesRow == null ? BigDecimal.ZERO : (BigDecimal) salesRow.get("salesAmount");
        BigDecimal dealerSales = salesRow == null ? BigDecimal.ZERO : (BigDecimal) salesRow.get("dealerSales");
        BigDecimal retailSales = salesRow == null ? BigDecimal.ZERO : (BigDecimal) salesRow.get("retailSales");

        // 未收款是老板后续提成考核的核心指标，严格采用氚云销售出库主单快照。
        BigDecimal collected = salesRow == null ? BigDecimal.ZERO : (BigDecimal) salesRow.get("sourceCollected");
        BigDecimal dealerCollected = salesRow == null ? BigDecimal.ZERO : (BigDecimal) salesRow.get("dealerCollected");
        BigDecimal retailCollected = salesRow == null ? BigDecimal.ZERO : (BigDecimal) salesRow.get("retailCollected");
        BigDecimal uncollected = salesRow == null ? BigDecimal.ZERO : (BigDecimal) salesRow.get("sourceUncollected");
        BigDecimal collectionRate = collectionRate(collected, collected.add(uncollected));

        BigDecimal receipt = BigDecimal.ZERO;
        BigDecimal dealerReceipt = BigDecimal.ZERO;
        BigDecimal retailReceipt = BigDecimal.ZERO;
        BigDecimal dealerContribution = BigDecimal.ZERO;
        BigDecimal retailContribution = BigDecimal.ZERO;
        for (Map<String, Object> r : receipts) {
            BigDecimal amount = (BigDecimal) r.get("amount");
            CommissionPolicy.Rate occurrenceRate = CommissionPolicy.rateAt(rateHistory, employeeId,
                    sourceDate(r.get("receiptDate")));
            BigDecimal singleRate = isDealer(r) ? occurrenceRate.dealerRate() : occurrenceRate.retailRate();
            r.put("customerType", isDealer(r) ? "DEALER" : "RETAIL");
            r.put("rate", singleRate);
            r.put("commission", money(amount.multiply(singleRate)));
            receipt = receipt.add(amount);
            if (isDealer(r)) {
                dealerReceipt = dealerReceipt.add(amount);
                dealerContribution = dealerContribution.add(amount.multiply(singleRate));
            } else {
                retailReceipt = retailReceipt.add(amount);
                retailContribution = retailContribution.add(amount.multiply(singleRate));
            }
        }
        BigDecimal dealerFreight = BigDecimal.ZERO;
        BigDecimal retailFreight = BigDecimal.ZERO;
        for (Map<String, Object> fr : freights) {
            BigDecimal amt = (BigDecimal) fr.get("freight");
            boolean dealer = isDealer(fr);
            CommissionPolicy.Rate occurrenceRate = CommissionPolicy.rateAt(rateHistory, employeeId,
                    sourceDate(fr.get("attributionDate")));
            BigDecimal singleRate = dealer ? occurrenceRate.dealerRate() : occurrenceRate.retailRate();
            fr.put("customerType", dealer ? "DEALER" : "RETAIL");
            fr.put("rate", singleRate);
            fr.put("commissionDeduction", money(amt.multiply(singleRate)));
            if (dealer) {
                dealerFreight = dealerFreight.add(amt);
                dealerContribution = dealerContribution.subtract(amt.multiply(singleRate));
            } else {
                retailFreight = retailFreight.add(amt);
                retailContribution = retailContribution.subtract(amt.multiply(singleRate));
            }
        }
        BigDecimal freight = dealerFreight.add(retailFreight);
        BigDecimal dealerCommission = money(dealerContribution);
        BigDecimal commissionAmount = money(dealerContribution.add(retailContribution));
        BigDecimal retailCommission = commissionAmount.subtract(dealerCommission);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("employeeId", employeeId);
        result.put("employeeName", empName);
        result.put("startDate", start.toString());
        result.put("endDate", end.toString());
        result.put("period", periodLabel(start, end));
        LocalDate prevEndIncl = prevEnd.minusDays(1);
        result.put("previousStartDate", prevStart.toString());
        result.put("previousEndDate", prevEndIncl.toString());
        result.put("previousPeriod", periodLabel(prevStart, prevEndIncl));
        result.put("freightPeriodMode", actualMode);
        result.put("currentMonthSalesAmount", sales);
        result.put("dealerSalesAmount", dealerSales);
        result.put("retailSalesAmount", retailSales);
        result.put("currentMonthReceiptAmount", receipt);
        result.put("dealerReceiptAmount", dealerReceipt);
        result.put("retailReceiptAmount", retailReceipt);
        result.put("collectedAmount", collected);
        result.put("dealerCollectedAmount", dealerCollected);
        result.put("retailCollectedAmount", retailCollected);
        result.put("uncollectedAmount", uncollected);
        result.put("collectionRate", collectionRate);
        result.put("receipts", receipts);
        result.put("previousMonthFreightAmount", freight);
        result.put("dealerFreightAmount", dealerFreight);
        result.put("retailFreightAmount", retailFreight);
        result.put("freights", freights);
        result.put("commissionBase", receipt.subtract(freight));
        result.put("dealerRate", rate.dealerRate());
        result.put("retailRate", rate.retailRate());
        result.put("dealerCommission", dealerCommission);
        result.put("retailCommission", retailCommission);
        result.put("commissionAmount", commissionAmount);
        // 已结账标记：该期间已核算结账时，比例/金额以版本快照为准
        result.put("settled", settled != null && settledItem != null);
        result.put("settledVersionNo", settled == null ? null : settled.get("versionNo"));
        result.put("settledAt", settled == null ? null : String.valueOf(settled.get("calculatedAt")));
        if (settledItem != null) {
            result.put("settledEmployeeName", settledItem.get("employeeName"));
            result.put("settledBaseCommission", settledItem.get("baseCommission"));
            result.put("settledAdjCommission", settledItem.get("adjCommission"));
            result.put("settledTotalCommission", settledItem.get("totalCommission"));
        }
        return result;
    }

    // ==================== 比例配置 CRUD ====================

    /** 提成比例配置列表（默认规则在前）。 */
    public List<Map<String, Object>> listRates() {
        return jdbc.query("""
            SELECT r.id, r.employee_id, COALESCE(e.name,'') employee_name,
                   r.dealer_rate, r.retail_rate, r.remark, r.created_at, r.updated_at
            FROM monthly_commission_rates r
            LEFT JOIN employees e ON e.id=r.employee_id
            ORDER BY r.employee_id IS NULL DESC, COALESCE(e.name,'')
            """, this::row);
    }

    /** 新增业务员比例配置（默认规则在 V214 迁移中已内置，无需创建）。 */
    @Transactional
    public Map<String, Object> createRate(Long employeeId, BigDecimal dealerRate, BigDecimal retailRate, String remark) {
        if (employeeId == null) throw new IllegalArgumentException("默认规则已存在，请直接调整；如需新增请选择业务员");
        validateRate(dealerRate);
        validateRate(retailRate);
        Map<String, Object> emp = oneOrNull("SELECT id, name FROM employees WHERE id=?", employeeId);
        if (emp == null) throw new IllegalArgumentException("所选业务员不存在");
        Map<String, Object> dup = oneOrNull("SELECT id FROM monthly_commission_rates WHERE employee_id=?", employeeId);
        if (dup != null) throw new IllegalArgumentException("该业务员已配置提成比例，请直接修改");

        jdbc.update("INSERT INTO monthly_commission_rate_history(employee_id, dealer_rate, retail_rate, remark, effective_date) VALUES (?,?,?,?,CURRENT_DATE)",
                employeeId, dealerRate, retailRate, clean(remark));
        jdbc.update("INSERT INTO monthly_commission_rates(employee_id, dealer_rate, retail_rate, remark) VALUES (?,?,?,?)",
                employeeId, dealerRate, retailRate, clean(remark));
        auditLog.log(RATE_ENTITY, null, String.valueOf(emp.get("name")), "CREATE",
                "新增提成比例：经销商 " + pct(dealerRate) + " / 零售 " + pct(retailRate)
                        + (clean(remark) == null ? "" : "，备注：" + clean(remark))
                        + "（生效日期 " + LocalDate.now() + "，历史修正按生效日追溯）");
        return detailRate(employeeId);
    }

    /** 修改比例配置（含默认规则行）。 */
    @Transactional
    public Map<String, Object> updateRate(Long id, BigDecimal dealerRate, BigDecimal retailRate, String remark) {
        validateRate(dealerRate);
        validateRate(retailRate);
        Map<String, Object> row = rateRow(id);
        if (row == null) throw new IllegalArgumentException("配置不存在");
        String name = row.get("employeeName") == null || "".equals(row.get("employeeName"))
                ? "默认规则" : String.valueOf(row.get("employeeName"));

        // 调整比例：先记历史（自今日起生效，历史日期按旧配置追溯），再更新当前值
        jdbc.update("INSERT INTO monthly_commission_rate_history(employee_id, dealer_rate, retail_rate, remark, effective_date) VALUES (?,?,?,?,CURRENT_DATE)",
                row.get("employeeId"), dealerRate, retailRate,
                "调整为 " + pct(dealerRate) + "/" + pct(retailRate) + (clean(remark) == null ? "" : "：" + clean(remark)));
        jdbc.update("UPDATE monthly_commission_rates SET dealer_rate=?, retail_rate=?, remark=? WHERE id=?",
                dealerRate, retailRate, clean(remark), id);
        auditLog.log(RATE_ENTITY, id, name, "UPDATE",
                "调整提成比例：经销商 " + pct(dealerRate) + " / 零售 " + pct(retailRate)
                        + (clean(remark) == null ? "" : "，备注：" + clean(remark))
                        + "（生效日期 " + LocalDate.now() + "，历史修正按生效日追溯）");
        return rateRow(id);
    }

    /** 删除业务员比例配置（默认规则行不可删除，仅可调整）。 */
    @Transactional
    public void deleteRate(Long id) {
        Map<String, Object> row = rateRow(id);
        if (row == null) throw new IllegalArgumentException("配置不存在");
        if (row.get("employeeId") == null) throw new IllegalArgumentException("默认规则不可删除，仅可调整比例");
        String name = row.get("employeeName") == null ? "" : String.valueOf(row.get("employeeName"));
        // 删除独立配置 → 自今日起按默认规则：历史记一条当前默认值快照，供「原期间比例」追溯衔接
        Map<String, Object> def = oneOrNull("SELECT dealer_rate, retail_rate FROM monthly_commission_rates WHERE employee_id IS NULL");
        if (def != null) {
            jdbc.update("INSERT INTO monthly_commission_rate_history(employee_id, dealer_rate, retail_rate, remark, effective_date) VALUES (?,?,?,?,CURRENT_DATE)",
                    row.get("employeeId"), def.get("dealerRate"), def.get("retailRate"), "删除独立配置，恢复默认规则");
        }
        jdbc.update("DELETE FROM monthly_commission_rates WHERE id=?", id);
        auditLog.log(RATE_ENTITY, id, name, "DELETE", "删除提成比例配置（恢复默认规则，生效日期 " + LocalDate.now() + "）");
    }

    private Map<String, Object> rateRow(Long id) {
        return oneOrNull("""
            SELECT r.id, r.employee_id, COALESCE(e.name,'') employee_name,
                   r.dealer_rate, r.retail_rate, r.remark, r.created_at, r.updated_at
            FROM monthly_commission_rates r
            LEFT JOIN employees e ON e.id=r.employee_id
            WHERE r.id=?
            """, id);
    }

    private Map<String, Object> detailRate(Long employeeId) {
        return oneOrNull("""
            SELECT r.id, r.employee_id, COALESCE(e.name,'') employee_name,
                   r.dealer_rate, r.retail_rate, r.remark, r.created_at, r.updated_at
            FROM monthly_commission_rates r
            LEFT JOIN employees e ON e.id=r.employee_id
            WHERE r.employee_id=?
            """, employeeId);
    }

    private void validateRate(BigDecimal rate) {
        if (rate == null || rate.signum() < 0 || rate.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException("提成比例须在 0%~100% 之间");
        }
    }

    private String pct(BigDecimal rate) {
        return rate == null ? "—" : rate.multiply(BigDecimal.valueOf(100)).stripTrailingZeros().toPlainString() + "%";
    }

    private String clean(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    // ==================== 数据异常 ====================

    /**
     * 只做三类最小检查，提示回氚云修数据，不建设自动修正：
     * 1) 期间收款明细所属出库无业务员（未计入任何业务员回款）；
     * 2) 上期运杂费出库无业务员（未计入任何业务员扣减）；
     * 3) 收款明细缺少收款日期（无法归属期间）。
     */
    private List<Map<String, Object>> collectIssues(LocalDate start, LocalDate end,
                                                    LocalDate curStart, LocalDate curEnd,
                                                    LocalDate prevStart, LocalDate prevEnd) {
        List<Map<String, Object>> issues = new ArrayList<>();

        Map<String, Object> noEmpReceipt = oneOrNull("""
            SELECT COUNT(*) cnt, COALESCE(SUM(ps.received_amount),0) amt
            FROM sales_outbound_payment_snapshots ps
            JOIN sales_outbounds o ON o.id=ps.sales_outbound_id
            WHERE ps.receipt_date>=? AND ps.receipt_date<?
              AND ps.legacy_status='CONFIRMED' AND o.outbound_status='CONFIRMED'
              AND o.external_source IN ('H3YUN','H3YUN_RECOVERED','H3_HISTORICAL_RECOVERED')
              AND o.employee_id IS NULL
            """, curStart, curEnd);
        if (count(noEmpReceipt) > 0) {
            issues.add(issue("RECEIPT_WITHOUT_EMPLOYEE",
                    periodLabel(start, end) + " 有 " + count(noEmpReceipt) + " 条收款明细无法识别业务员，金额 ¥"
                            + amt(noEmpReceipt) + " 未计入任何业务员回款，请到氚云检查出库单业务员。",
                    count(noEmpReceipt), amt(noEmpReceipt)));
        }

        Map<String, Object> noEmpFreight = oneOrNull("""
            SELECT COUNT(*) cnt, COALESCE(SUM(COALESCE(o.freight,0)),0) amt
            FROM sales_outbounds o
            WHERE o.attribution_date>=? AND o.attribution_date<?
              AND o.outbound_status='CONFIRMED'
              AND o.external_source IN ('H3YUN','H3YUN_RECOVERED','H3_HISTORICAL_RECOVERED')
              AND o.employee_id IS NULL
            """, prevStart, prevEnd);
        if (count(noEmpFreight) > 0) {
            issues.add(issue("FREIGHT_OUTBOUND_WITHOUT_EMPLOYEE",
                    periodLabel(prevStart, prevEnd.minusDays(1)) + " 有 " + count(noEmpFreight)
                            + " 张含运杂费的出库单无法识别业务员，金额 ¥"
                            + amt(noEmpFreight) + " 未计入任何业务员扣减，请到氚云检查出库单业务员。",
                    count(noEmpFreight), amt(noEmpFreight)));
        }

        Map<String, Object> noDateReceipt = oneOrNull("""
            SELECT COUNT(*) cnt, COALESCE(SUM(ps.received_amount),0) amt
            FROM sales_outbound_payment_snapshots ps
            JOIN sales_outbounds o ON o.id=ps.sales_outbound_id
            WHERE ps.legacy_status='CONFIRMED' AND o.outbound_status='CONFIRMED'
              AND o.external_source IN ('H3YUN','H3YUN_RECOVERED','H3_HISTORICAL_RECOVERED')
              AND ps.receipt_date IS NULL
            """);
        if (count(noDateReceipt) > 0) {
            issues.add(issue("RECEIPT_WITHOUT_DATE",
                    "有 " + count(noDateReceipt) + " 条收款明细缺少收款日期（金额 ¥" + amt(noDateReceipt)
                            + "），无法归属期间，未计入期间回款，请到氚云补填收款日期。",
                    count(noDateReceipt), amt(noDateReceipt)));
        }
        return issues;
    }

    private Map<String, Object> issue(String type, String message, long count, BigDecimal amount) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type);
        m.put("message", message);
        m.put("count", count);
        m.put("amount", amount);
        return m;
    }

    // ==================== 内部工具 ====================

    private boolean isDealer(Map<String, Object> row) {
        Object flag = row.get("dealerFlag");
        return flag != null && ((Number) flag).intValue() == 1;
    }

    /** 精确匹配期间 [start,end] 的最新「已结账」核算版本；无则 null（已结账期间以版本快照为准，作废 VOID 不参与）。 */
    private Map<String, Object> settledVersionFor(LocalDate start, LocalDate end) {
        List<Map<String, Object>> versions = jdbc.query("""
            SELECT id, version_no, calculated_at, start_date, end_date
            FROM commission_calculation_versions
            WHERE start_date=? AND end_date=? AND status='FINAL'
            ORDER BY calculated_at DESC, id DESC LIMIT 1
            """, this::row, start, end);
        return versions.isEmpty() ? null : versions.getFirst();
    }

    /** 从 map 取金额（兼容 BigDecimal / Number / String），缺省 0。 */
    private BigDecimal dec(Map<String, Object> m, String key) {
        Object v = m == null ? null : m.get(key);
        if (v == null) return BigDecimal.ZERO;
        if (v instanceof BigDecimal d) return d;
        if (v instanceof Number n) return new BigDecimal(n.toString());
        return new BigDecimal(String.valueOf(v));
    }

    private BigDecimal money(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP);
    }

    /** 氚云回款占比 = 已收 / (已收 + 未收)（0~1，保留 4 位）；无应收时视为已全部收回。 */
    private BigDecimal collectionRate(BigDecimal collected, BigDecimal receivable) {
        if (receivable == null || receivable.signum() <= 0) return BigDecimal.ONE;
        return collected.divide(receivable, 4, RoundingMode.HALF_UP).min(BigDecimal.ONE);
    }

    /** 核算日期范围校验：起止必填且 start <= end。 */
    private void validateRange(LocalDate start, LocalDate end) {
        if (start == null || end == null) throw new IllegalArgumentException("请选择核算日期范围");
        if (start.isAfter(end)) throw new IllegalArgumentException("核算开始日期不能晚于结束日期");
    }

    /** 日期范围显示标签（如 2026-08-01~2026-08-31）。 */
    private String periodLabel(LocalDate start, LocalDate end) {
        return start + "~" + end;
    }

    private LocalDate sourceDate(Object value) {
        if (value instanceof java.sql.Date date) return date.toLocalDate();
        if (value instanceof LocalDate date) return date;
        return value == null ? null : LocalDate.parse(String.valueOf(value).substring(0, 10));
    }

    /** 实时参考包含全部来源；正式预览另行排除已占用来源。两者逐笔比例与取分顺序相同。 */
    private Map<Long, BigDecimal[]> sourceContributions(LocalDate receiptStart, LocalDate receiptEnd,
            LocalDate freightStart, LocalDate freightEnd,
            Map<Long, List<CommissionPolicy.RateEntry>> history) {
        Map<Long, BigDecimal[]> result = new LinkedHashMap<>();
        String receiptSql = """
            SELECT o.employee_id, ps.receipt_date occurrence_date, ps.received_amount amount,
                   CASE WHEN (%s) THEN 1 ELSE 0 END dealer_flag
            FROM sales_outbound_payment_snapshots ps
            JOIN sales_outbounds o ON o.id=ps.sales_outbound_id
            LEFT JOIN employees e ON e.id=o.employee_id
            WHERE ps.receipt_date>=? AND ps.receipt_date<?
              AND ps.legacy_status='CONFIRMED' AND o.outbound_status='CONFIRMED'
              AND o.employee_id IS NOT NULL AND %s
            """.formatted(CommissionPolicy.DEALER_CASE, H3_SOURCE_FILTER);
        String freightSql = """
            SELECT o.employee_id, o.attribution_date occurrence_date, COALESCE(o.freight,0) amount,
                   CASE WHEN (%s) THEN 1 ELSE 0 END dealer_flag
            FROM sales_outbounds o
            LEFT JOIN employees e ON e.id=o.employee_id
            WHERE o.attribution_date>=? AND o.attribution_date<?
              AND o.outbound_status='CONFIRMED' AND o.employee_id IS NOT NULL
              AND COALESCE(o.freight,0)<>0 AND %s
            """.formatted(CommissionPolicy.DEALER_CASE, H3_SOURCE_FILTER);
        addSourceContributions(result, receiptSql, receiptStart, receiptEnd, false, history);
        addSourceContributions(result, freightSql, freightStart, freightEnd, true, history);
        return result;
    }

    private void addSourceContributions(Map<Long, BigDecimal[]> result, String sql,
            LocalDate start, LocalDate end, boolean freight,
            Map<Long, List<CommissionPolicy.RateEntry>> history) {
        StringBuilder scope = new StringBuilder();
        List<Object> scopeParams = new ArrayList<>();
        scopeSupport.appendByEmployeeId(scope, scopeParams, "o.employee_id", "e.department", FeatureCode.MONTHLY_COMMISSION);
        List<Object> params = new ArrayList<>(List.of(start, end));
        params.addAll(scopeParams);
        jdbc.query(sql + scope, rs -> {
            long employeeId = rs.getLong("employee_id");
            java.sql.Date date = rs.getDate("occurrence_date");
            CommissionPolicy.Rate rate = CommissionPolicy.rateAt(history, employeeId,
                    date == null ? null : date.toLocalDate());
            boolean dealer = rs.getInt("dealer_flag") == 1;
            BigDecimal amount = rs.getBigDecimal("amount");
            BigDecimal value = amount.multiply(dealer ? rate.dealerRate() : rate.retailRate());
            if (freight) value = value.negate();
            BigDecimal[] totals = result.computeIfAbsent(employeeId,
                    id -> new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
            int channel = dealer ? 0 : 1;
            totals[channel] = totals[channel].add(value);
        }, params.toArray());
    }

    /**
     * 执行按业务员分组的查询并追加行级数据范围。
     * baseSql 必须以 WHERE 条件结尾（不闭合），内部 SQL 需已 LEFT JOIN employees e ON e.id=o.employee_id。
     */
    private Map<Long, Map<String, Object>> queryByEmployee(String baseSql, List<Object> baseParams) {
        StringBuilder scope = new StringBuilder();
        List<Object> scopeParams = new ArrayList<>();
        scopeSupport.appendByEmployeeId(scope, scopeParams, "o.employee_id", "e.department", FeatureCode.MONTHLY_COMMISSION);
        List<Object> params = new ArrayList<>(baseParams);
        params.addAll(scopeParams);
        List<Map<String, Object>> rows = jdbc.query(baseSql + scope + " GROUP BY o.employee_id",
                this::row, params.toArray());
        Map<Long, Map<String, Object>> map = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            map.put(((Number) row.get("employeeId")).longValue(), row);
        }
        return map;
    }

    private long count(Map<String, Object> m) {
        if (m == null) return 0;
        Object v = m.get("cnt");
        return v == null ? 0L : ((Number) v).longValue();
    }

    private BigDecimal amt(Map<String, Object> m) {
        if (m == null) return BigDecimal.ZERO;
        Object v = m.get("amt");
        return v == null ? BigDecimal.ZERO : (BigDecimal) v;
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
}
