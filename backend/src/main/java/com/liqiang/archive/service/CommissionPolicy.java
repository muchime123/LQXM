package com.liqiang.archive.service;

import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 月度提成统一口径：经销商判定 + 比例取用。
 *
 * <p>提成制度相关口径集中于此，{@link MonthlySalesCommissionService} 与
 * {@link CommissionCalculationService} 共享引用，改口径只改本类。
 *
 * <p>经销商判定 = 出库单「选择经销商」非空，或毛利率低于 15%（防漏填经销商导致漏算低毛利经销商单）；
 * 毛利率 = COALESCE(source_gross_profit,0) / sales_amount。
 * 提成比例按业务员配置（{@code monthly_commission_rates}，财务可维护，保存即生效）；
 * 缺默认规则时兜底 4%/4%。
 */
public final class CommissionPolicy {

    private CommissionPolicy() {
    }

    /** 默认比例（未单独配置的业务员，经销商/零售均 4%；比例精度 6 位小数，2026-09-04 起）。 */
    public static final BigDecimal DEFAULT_RATE = new BigDecimal("0.040000");

    /** 低利润兜底阈值：毛利率低于 15% 的出库单即使漏填经销商也按经销商计算。 */
    public static final BigDecimal LOW_PROFIT_DEALER_THRESHOLD = new BigDecimal("0.15");

    /**
     * 经销商判定 SQL 片段（作用于别名 o）：
     * 出库单「选择经销商」非空，或毛利率低于 15%（防漏填经销商导致漏算低毛利经销商单）。
     * 毛利率 = COALESCE(source_gross_profit,0) / sales_amount（源毛利覆盖 105k+ 单，全部可用）。
     */
    public static final String DEALER_CASE = """
        (o.dealer_name IS NOT NULL AND o.dealer_name<>'')
        OR (o.sales_amount IS NOT NULL AND o.sales_amount>0
            AND COALESCE(o.source_gross_profit,0) / o.sales_amount < """ + LOW_PROFIT_DEALER_THRESHOLD.toPlainString() + ")";

    /**
     * 渠道判定来源 CASE 表达式（作用于别名 o，返回三档字符串，G5）：
     * 'DEALER_FIELD'=出库单填了经销商 / 'MARGIN_FALLBACK'=毛利率兜底（未填经销商但毛利 <15%）/
     * 'DEFAULT_RETAIL'=默认终端。与 {@link #DEALER_CASE} 判定完全一致，仅多记录「依据」。
     */
    public static final String DEALER_SOURCE_CASE =
            """
            CASE
              WHEN (o.dealer_name IS NOT NULL AND o.dealer_name<>'') THEN 'DEALER_FIELD'
              WHEN (o.sales_amount IS NOT NULL AND o.sales_amount>0
                    AND COALESCE(o.source_gross_profit,0) / o.sales_amount < %s)
                THEN 'MARGIN_FALLBACK'
              ELSE 'DEFAULT_RETAIL'
            END
            """.formatted(LOW_PROFIT_DEALER_THRESHOLD.toPlainString());

    /** 运费范围模式：结束日所在月的上一个完整自然月（现行默认口径，财务确认）。 */
    public static final String FREIGHT_MODE_PREVIOUS_CALENDAR_MONTH = "PREVIOUS_CALENDAR_MONTH";
    /** 运费范围模式：核算范围之前的一个等长周期（如核算 8/1~8/31 → 运费取 7/1~7/31）。 */
    public static final String FREIGHT_MODE_PREVIOUS_EQUAL_PERIOD = "PREVIOUS_EQUAL_PERIOD";
    /** 运费范围模式：自定义起止日期（须填运费范围原因，审计留痕）。 */
    public static final String FREIGHT_MODE_CUSTOM = "CUSTOM";

    /** 实际采用的运费扣减窗口（半开区间 [startInclusive, endExclusive)），含实际模式。 */
    public record FreightWindow(LocalDate startInclusive, LocalDate endExclusive, String mode) {

        /** 展示用结束日（含）。 */
        public LocalDate endInclusive() {
            return endExclusive.minusDays(1);
        }
    }

    /**
     * 解析核算请求的运费扣减窗口（G4，P2）：mode 为空/未知时按 {@link #FREIGHT_MODE_PREVIOUS_CALENDAR_MONTH}
     * 处理（与旧口径完全一致，旧请求不受影响）。
     * <ul>
     *   <li>PREVIOUS_CALENDAR_MONTH：结束日所在月的上一个完整自然月 [上月1日, 本月1日)；</li>
     *   <li>PREVIOUS_EQUAL_PERIOD：核算范围前一个等长周期 [start−len, start)，len=start~end 天数；</li>
     *   <li>CUSTOM：[customStart, customEnd]（入参为含端点，内部转半开区间）。</li>
     * </ul>
     */
    public static FreightWindow resolveFreightWindow(LocalDate start, LocalDate end,
                                                     String mode, LocalDate customStart, LocalDate customEnd) {
        if (start == null || end == null) throw new IllegalArgumentException("请选择核算日期范围");
        if (start.isAfter(end)) throw new IllegalArgumentException("核算开始日期不能晚于结束日期");
        String m = mode == null ? "" : mode.trim();
        if (m.isEmpty() || FREIGHT_MODE_PREVIOUS_CALENDAR_MONTH.equalsIgnoreCase(m)) {
            LocalDate month = end.minusMonths(1);
            return new FreightWindow(month.withDayOfMonth(1), month.plusMonths(1).withDayOfMonth(1),
                    FREIGHT_MODE_PREVIOUS_CALENDAR_MONTH);
        }
        if (FREIGHT_MODE_PREVIOUS_EQUAL_PERIOD.equalsIgnoreCase(m)) {
            long len = ChronoUnit.DAYS.between(start, end) + 1;
            LocalDate prevStart = start.minusDays(len);
            return new FreightWindow(prevStart, start, FREIGHT_MODE_PREVIOUS_EQUAL_PERIOD);
        }
        if (FREIGHT_MODE_CUSTOM.equalsIgnoreCase(m)) {
            if (customStart == null || customEnd == null) {
                throw new IllegalArgumentException("自定义运费范围必须同时填写开始与结束日期");
            }
            if (customStart.isAfter(customEnd)) throw new IllegalArgumentException("运费范围开始日期不能晚于结束日期");
            return new FreightWindow(customStart, customEnd.plusDays(1), FREIGHT_MODE_CUSTOM);
        }
        throw new IllegalArgumentException("未知的运费范围模式：" + m);
    }

    /** 自定义运费范围必须填写原因（前端与结账门禁共用；审计留痕于版本/审计日志）。 */
    public static void requireFreightCustomReason(String mode, String reason) {
        if (FREIGHT_MODE_CUSTOM.equals(mode == null ? "" : mode.trim())
                && (reason == null || reason.isBlank())) {
            throw new IllegalArgumentException("自定义运费范围必须填写运费范围原因");
        }
    }

    /** 提成比例结果（dealerRate=经销商 / retailRate=零售）。 */
    public record Rate(BigDecimal dealerRate, BigDecimal retailRate) {
    }

    /** 加载全部比例配置；缺默认规则时兜底 4%/4%。 */
    public static Map<Long, Rate> loadRates(JdbcTemplate jdbc) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT employee_id, dealer_rate, retail_rate FROM monthly_commission_rates");
        Map<Long, Rate> rates = new HashMap<>();
        for (Map<String, Object> row : rows) {
            Object empId = row.get("employee_id");
            Long key = empId == null ? null : ((Number) empId).longValue();
            rates.put(key, new Rate((BigDecimal) row.get("dealer_rate"), (BigDecimal) row.get("retail_rate")));
        }
        if (!rates.containsKey(null)) {
            rates.put(null, new Rate(DEFAULT_RATE, DEFAULT_RATE));
        }
        return rates;
    }

    /** 业务员比例：优先独立配置，否则默认规则。 */
    public static Rate rateOf(Map<Long, Rate> rates, long employeeId) {
        Rate r = rates.get(employeeId);
        return r != null ? r : rates.get(null);
    }

    /** 比例历史条目（自 effectiveDate 起生效；同员工多条按生效日升序）。 */
    public record RateEntry(LocalDate effectiveDate, Rate rate) {
    }

    /**
     * 加载全部比例调整历史（含默认规则 employeeId=null），按员工聚合、按生效日升序。
     * 来源 {@code monthly_commission_rate_history}（每次调整一条，V244 起维护）。
     */
    public static Map<Long, List<RateEntry>> loadRateHistory(JdbcTemplate jdbc) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
            SELECT employee_id, dealer_rate, retail_rate, effective_date
            FROM monthly_commission_rate_history
            ORDER BY employee_id, effective_date, id
            """);
        Map<Long, List<RateEntry>> history = new HashMap<>();
        for (Map<String, Object> row : rows) {
            Object empId = row.get("employee_id");
            Long key = empId == null ? null : ((Number) empId).longValue();
            Rate rate = new Rate((BigDecimal) row.get("dealer_rate"), (BigDecimal) row.get("retail_rate"));
            Object d = row.get("effective_date");
            LocalDate date = d instanceof java.sql.Date sd ? sd.toLocalDate()
                    : d instanceof LocalDate ld ? ld : LocalDate.parse(String.valueOf(d));
            history.computeIfAbsent(key, k -> new ArrayList<>()).add(new RateEntry(date, rate));
        }
        return history;
    }

    /**
     * 追溯业务员在指定日期的生效比例（原期间比例）：
     * 取该员工 effective_date ≤ date 的最近一条历史，无专属则回溯默认规则历史，仍无则兜底默认比例。
     * @param date 归属日期；null 表示不追溯（取该员工最新一条历史，即当前配置）
     */
    public static Rate rateAt(Map<Long, List<RateEntry>> history, long employeeId, LocalDate date) {
        Rate r = lookup(history.get(employeeId), date);
        if (r != null) return r;
        Rate def = lookup(history.get(null), date);
        return def != null ? def : new Rate(DEFAULT_RATE, DEFAULT_RATE);
    }

    private static Rate lookup(List<RateEntry> entries, LocalDate date) {
        if (entries == null || entries.isEmpty()) return null;
        if (date == null) return entries.get(entries.size() - 1).rate();
        Rate best = null;
        for (RateEntry e : entries) {
            if (e.effectiveDate().isAfter(date)) break;   // 升序：越过归属日期即停
            best = e.rate();
        }
        return best;
    }
}
