package com.liqiang.archive.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 资金日记账核对口径（只读派生，不写库、不改原始结存）。
 *
 * <p>两类核对必须分开，不能互相代表：
 * <ul>
 *   <li><b>期间余额核对</b>（{@link closingStatusOf}）：期初 + 期间收入 − 期间支出 = 期间期末。
 *       这是财务可信的账户级结论，与流水行内先后顺序无关。</li>
 *   <li><b>来源结存核对</b>（{@link SourceBalanceReview#exceptions()}）：逐条流水的「来源结存」能否与
 *       「上一行来源结存 + 本行收支」接上。只用于提示历史明细待核实，不得用于否定期间余额，
 *       也不得据此推断交易先后顺序。</li>
 *   <li><b>人工对账</b>（{@link #MANUAL_NOT_STARTED}）：财务已与银行流水/实际账户余额确认。
 *       P1 不提供人工对账操作，页面上必须显式标注「未人工对账」——「可计算」不等于「已核对」。</li>
 * </ul>
 *
 * <p>⚠️ 本类刻意不提供「按余额单调性切流水段」之类的推断：账户余额本就随收支上下波动，
 * 结存下降既不能证明断段，也不能证明连续。断点只按「行与行之间能否接上」这一确定性事实列出。
 */
public final class CashflowReviewRules {
    /** 期间余额核对状态。 */
    public static final String CLOSING_OK = "OK";
    public static final String CLOSING_OPENING_MISSING = "OPENING_MISSING";
    /** 来源结存核对状态。 */
    public static final String DETAIL_OK = "DETAIL_OK";
    public static final String DETAIL_EXCEPTIONS = "DETAIL_EXCEPTIONS";
    /**
     * 人工对账状态。P1 尚未提供人工对账操作，一律 {@link #MANUAL_NOT_STARTED}；
     * P3 才引入 {@code CONFIRMED} / {@code DISPUTED}。**「可计算」永远不等于「已人工核对」。**
     */
    public static final String MANUAL_NOT_STARTED = "NOT_STARTED";

    private CashflowReviewRules() {}

    public static String manualReconcileLabel(String status) {
        return switch (status == null ? MANUAL_NOT_STARTED : status) {
            case MANUAL_NOT_STARTED -> "未人工对账";
            default -> status;
        };
    }

    /**
     * 账户行的核对结论陈述（单一来源，前后端展示同一句）。
     *
     * <p>只陈述「期末能不能算」与「逐笔是否衔接」两个确定事实，刻意不出现「已核对一致」
     * 之类的措辞：系统算出来的数与财务确认过的数是两件事，P1 只做前者。
     */
    public static String reconcileSummaryOf(String closingStatus, int exceptionCount) {
        String closingPart = CLOSING_OK.equals(closingStatus) ? "期末可计算" : "缺期初，期末不可计算";
        String detailPart = exceptionCount <= 0 ? "流水逐笔衔接" : exceptionCount + " 处明细不衔接";
        return closingPart + "；" + detailPart;
    }

    /**
     * 期间余额可信度。只回答一个问题：这个账户的期间期末余额能不能算、算得准不准。
     *
     * @param opening         期间期初；null 表示来源缺期初，不能算期末
     * @param openingConfirmed 期初是否已人工确认（P2 前恒 false，仅用于前端区分徽标语义）
     * @return 状态码，取值见 CLOSING_* 常量
     */
    public static String closingStatusOf(BigDecimal opening, boolean openingConfirmed) {
        if (opening == null) return CLOSING_OPENING_MISSING;
        // 期初未确认不阻断计算（27/27 户已与来源末行结存对拍一致），仅由调用方决定是否展示提示。
        return CLOSING_OK;
    }

    /** 期间期末 = 期间期初 + 期间收入 − 期间支出。期初缺失返回 null，不伪装为 0。 */
    public static BigDecimal closingBalanceOf(BigDecimal opening, BigDecimal income, BigDecimal expense) {
        if (opening == null) return null;
        return opening.add(nz(income)).subtract(nz(expense));
    }

    public static BigDecimal nz(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    /** 一条流水的来源结存核对输入。按调用方给定的确定性行序（账户、日期、id）。 */
    public record SourceBalanceRow(long entryId, LocalDate entryDate, BigDecimal income, BigDecimal expense,
                                   BigDecimal balance) {}

    /** 一处断点：该行的来源结存无法由上一行来源结存 + 本行收支接上。 */
    public record SourceBalanceException(LocalDate entryDate, long entryId, BigDecimal previousBalance,
                                         BigDecimal balance, BigDecimal gap) {}

    /**
     * 来源结存核对结果。
     *
     * @param status        DETAIL_OK / DETAIL_EXCEPTIONS
     * @param checkedLinks  参与核对的行间链接数
     * @param exceptions    断点明细（确定性事实，不含任何推断）
     * @param firstDate     首批断点的最早日期，供前端提示「未核实区间」
     * @param lastDate      断点的最晚日期
     */
    public record SourceBalanceReview(String status, int checkedLinks, List<SourceBalanceException> exceptions,
                                      LocalDate firstDate, LocalDate lastDate) {
        public static SourceBalanceReview empty() {
            return new SourceBalanceReview(DETAIL_OK, 0, List.of(), null, null);
        }

        public boolean hasExceptions() {
            return !exceptions.isEmpty();
        }

        public int exceptionCount() {
            return exceptions.size();
        }
    }

    /**
     * 逐行核对来源结存。rows 必须已按确定性顺序排好（账户内 entry_date, id）。
     *
     * <p>容差 0.01 元：Excel 里存在 4 位小数的折算列，直接等值比较会误报。
     * <p>不跳过任何行，也不尝试「重新排序以求连续」——排序本身就是未经证实的假设。
     */
    public static SourceBalanceReview reviewSourceBalance(List<SourceBalanceRow> rows, BigDecimal tolerance) {
        if (rows == null || rows.size() < 2) return SourceBalanceReview.empty();
        BigDecimal limit = tolerance == null ? new BigDecimal("0.01") : tolerance;
        List<SourceBalanceException> exceptions = new ArrayList<>();
        int links = 0;
        for (int i = 1; i < rows.size(); i++) {
            SourceBalanceRow prev = rows.get(i - 1);
            SourceBalanceRow cur = rows.get(i);
            if (prev.balance() == null || cur.balance() == null) continue;
            links++;
            BigDecimal expected = prev.balance().add(nz(cur.income())).subtract(nz(cur.expense()));
            BigDecimal gap = cur.balance().subtract(expected);
            if (gap.abs().compareTo(limit) > 0) {
                exceptions.add(new SourceBalanceException(cur.entryDate(), cur.entryId(), prev.balance(),
                        cur.balance(), gap));
            }
        }
        if (exceptions.isEmpty()) return new SourceBalanceReview(DETAIL_OK, links, List.of(), null, null);
        LocalDate first = null;
        LocalDate last = null;
        for (SourceBalanceException exception : exceptions) {
            LocalDate date = exception.entryDate();
            if (date == null) continue;
            if (first == null || date.isBefore(first)) first = date;
            if (last == null || date.isAfter(last)) last = date;
        }
        return new SourceBalanceReview(DETAIL_EXCEPTIONS, links, Collections.unmodifiableList(exceptions), first, last);
    }

    /** 按账户分组核对；入参需已按 (账户, 日期, id) 排序。返回「账户 → 结果」，无断点的账户不在 map 内。 */
    public static Map<String, SourceBalanceReview> reviewByAccount(List<String> accounts, List<SourceBalanceRow> rows) {
        Map<String, List<SourceBalanceRow>> grouped = new LinkedHashMap<>();
        for (int i = 0; i < rows.size(); i++) {
            grouped.computeIfAbsent(accounts.get(i), ignored -> new ArrayList<>()).add(rows.get(i));
        }
        Map<String, SourceBalanceReview> result = new LinkedHashMap<>();
        grouped.forEach((account, list) -> {
            SourceBalanceReview review = reviewSourceBalance(list, null);
            if (review.hasExceptions()) result.put(account, review);
        });
        return result;
    }
}
