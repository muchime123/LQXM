package com.liqiang.archive.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 资金日记账核对口径契约：两类核对必须分开，且不得用余额单调性做任何推断。 */
class CashflowReviewRulesTest {

    private static CashflowReviewRules.SourceBalanceRow row(long id, String date, String income, String expense, String balance) {
        return new CashflowReviewRules.SourceBalanceRow(id, LocalDate.parse(date), dec(income), dec(expense), dec(balance));
    }

    private static BigDecimal dec(String value) {
        return value == null ? null : new BigDecimal(value);
    }

    // ==================== 期间余额核对 ====================

    @Test
    void openingMissingMeansClosingCannotBeComputedRatherThanZero() {
        assertEquals(CashflowReviewRules.CLOSING_OPENING_MISSING, CashflowReviewRules.closingStatusOf(null, false));
        assertNull(CashflowReviewRules.closingBalanceOf(null, dec("100"), dec("30")));
    }

    @Test
    void closingIsOpeningPlusIncomeMinusExpense() {
        BigDecimal closing = CashflowReviewRules.closingBalanceOf(dec("1000"), dec("250"), dec("120"));
        assertEquals(0, new BigDecimal("1130").compareTo(closing));
        assertEquals(CashflowReviewRules.CLOSING_OK, CashflowReviewRules.closingStatusOf(dec("1000"), false));
    }

    @Test
    void nullIncomeOrExpenseIsTreatedAsZeroNotSkipped() {
        BigDecimal closing = CashflowReviewRules.closingBalanceOf(dec("1000"), null, dec("120"));
        assertEquals(0, new BigDecimal("880").compareTo(closing));
    }

    // ==================== 来源结存核对 ====================

    @Test
    void continuousChainHasNoExceptions() {
        var rows = List.of(
                row(1, "2026-01-01", null, "90", "910"),
                row(2, "2026-01-02", "2000", null, "2910"));
        var review = CashflowReviewRules.reviewSourceBalance(rows, null);
        assertFalse(review.hasExceptions());
        assertEquals(CashflowReviewRules.DETAIL_OK, review.status());
        assertEquals(1, review.checkedLinks());
    }

    @Test
    void breakIsReportedWithDeterministicFactsOnly() {
        var rows = List.of(
                row(1, "2026-04-18", "25437", null, "980801.24"),
                row(2, "2026-04-19", null, "18", "446122.14"));
        var review = CashflowReviewRules.reviewSourceBalance(rows, null);
        assertTrue(review.hasExceptions());
        assertEquals(CashflowReviewRules.DETAIL_EXCEPTIONS, review.status());
        assertEquals(1, review.exceptionCount());
        var exception = review.exceptions().getFirst();
        assertEquals(2L, exception.entryId());
        assertEquals(LocalDate.parse("2026-04-19"), exception.entryDate());
        assertEquals(0, new BigDecimal("446122.14").compareTo(exception.balance()));
        assertEquals(LocalDate.parse("2026-04-19"), review.firstDate());
        assertEquals(LocalDate.parse("2026-04-19"), review.lastDate());
    }

    /** 余额下降是正常的收支结果，不能因为「结存变小」就判为断点。 */
    @Test
    void balanceFallingIsNotItselfAnException() {
        var rows = List.of(
                row(1, "2026-01-01", "1000", null, "2000"),
                row(2, "2026-01-01", null, "900", "1100"),
                row(3, "2026-01-01", null, "1000", "100"));
        var review = CashflowReviewRules.reviewSourceBalance(rows, null);
        assertFalse(review.hasExceptions(), "结存上下波动本身不是断点");
    }

    /** 反证：不允许为了让链条连续而重排同日行序。乱序输入就应报断点。 */
    @Test
    void sameDayRowsAreNotReorderedToForceContinuity() {
        var inOrder = List.of(
                row(1, "2026-01-02", "2000", null, "3000"),
                row(2, "2026-01-02", null, "3652.76", "-652.76"));
        var shuffled = List.of(
                row(2, "2026-01-02", null, "3652.76", "-652.76"),
                row(1, "2026-01-02", "2000", null, "3000"));
        assertFalse(CashflowReviewRules.reviewSourceBalance(inOrder, null).hasExceptions());
        assertEquals(1, CashflowReviewRules.reviewSourceBalance(shuffled, null).exceptionCount());
    }

    @Test
    void rowsWithoutSourceBalanceAreSkippedNotTreatedAsBreak() {
        var rows = List.of(
                row(1, "2026-01-01", "100", null, "1100"),
                row(2, "2026-01-02", "50", null, null),
                row(3, "2026-01-03", "50", null, null));
        var review = CashflowReviewRules.reviewSourceBalance(rows, null);
        assertFalse(review.hasExceptions());
        assertEquals(0, review.checkedLinks());
    }

    @Test
    void toleranceAbsorbsSubCentRounding() {
        var rows = List.of(
                row(1, "2026-01-01", "100.004", null, "1100.004"),
                row(2, "2026-01-02", null, null, "1100.005"));
        assertFalse(CashflowReviewRules.reviewSourceBalance(rows, null).hasExceptions());
    }

    @Test
    void singleRowCannotBeJudged() {
        var review = CashflowReviewRules.reviewSourceBalance(
                List.of(row(1, "2026-01-01", "100", null, "1100")), null);
        assertEquals(CashflowReviewRules.DETAIL_OK, review.status());
        assertEquals(0, review.checkedLinks());
    }

    @Test
    void groupedReviewOnlyReturnsAccountsWithExceptions() {
        var accounts = List.of("A", "A", "B", "B");
        var rows = List.of(
                row(1, "2026-01-01", "100", null, "1100"),
                row(2, "2026-01-02", "100", null, "1200"),
                row(3, "2026-01-01", "100", null, "500"),
                row(4, "2026-01-02", "100", null, "999"));
        var result = CashflowReviewRules.reviewByAccount(accounts, rows);
        assertEquals(List.of("B"), List.copyOf(result.keySet()));
        assertEquals(1, result.get("B").exceptionCount());
    }

    // ==================== 两类核对互不代表（核心不变量） ====================

    /**
     * 逐笔结存有断点，但期间期初 + 期间收支 = 期间期末仍成立 ——
     * 这正是「历史补录期结存未同步重算」的真实形态：明细待核实，账户汇总可用。
     */
    @Test
    void detailExceptionDoesNotInvalidatePeriodClosing() {
        var rows = List.of(
                row(1, "2026-05-01", "1000", null, "2000"),
                row(2, "2026-05-02", null, "500", "1400"),   // 断点：2000-500=1500 ≠ 1400
                row(3, "2026-05-03", "600", null, "2000"));
        var review = CashflowReviewRules.reviewSourceBalance(rows, null);
        assertTrue(review.hasExceptions());

        // 期间余额只认「期初 + 收支」，不受行内断点影响
        BigDecimal opening = dec("1000");
        BigDecimal income = dec("1600");
        BigDecimal expense = dec("500");
        assertEquals(CashflowReviewRules.CLOSING_OK, CashflowReviewRules.closingStatusOf(opening, false));
        assertEquals(0, new BigDecimal("2100").compareTo(
                CashflowReviewRules.closingBalanceOf(opening, income, expense)));
    }

    /** 反过来也成立：期间余额不可算（缺期初），不因明细全部衔接就变成可算。 */
    @Test
    void detailOkDoesNotMakeMissingOpeningComputable() {
        var rows = List.of(
                row(1, "2026-05-01", "100", null, "100"),
                row(2, "2026-05-02", "100", null, "200"));
        assertFalse(CashflowReviewRules.reviewSourceBalance(rows, null).hasExceptions());
        assertEquals(CashflowReviewRules.CLOSING_OPENING_MISSING, CashflowReviewRules.closingStatusOf(null, false));
    }

    // ==================== 文案：可计算 ≠ 已核对 ====================

    /** 结论陈述只允许出现「可计算 / 衔接」两个事实，不允许出现「已核对」「一致」这类对账结论。 */
    @Test
    void reconcileSummaryNeverClaimsManualReconciliation() {
        for (String closing : List.of(CashflowReviewRules.CLOSING_OK, CashflowReviewRules.CLOSING_OPENING_MISSING)) {
            for (int exceptions : new int[]{0, 1, 13}) {
                String text = CashflowReviewRules.reconcileSummaryOf(closing, exceptions);
                assertFalse(text.contains("已核对"), text);
                assertFalse(text.contains("核对一致"), text);
                assertFalse(text.contains("已对账"), text);
                assertTrue(text.startsWith(CashflowReviewRules.CLOSING_OK.equals(closing)
                        ? "期末可计算" : "缺期初，期末不可计算"), text);
            }
        }
    }

    @Test
    void reconcileSummaryCarriesBothFacts() {
        assertEquals("期末可计算；流水逐笔衔接", CashflowReviewRules.reconcileSummaryOf(CashflowReviewRules.CLOSING_OK, 0));
        assertEquals("期末可计算；3 处明细不衔接", CashflowReviewRules.reconcileSummaryOf(CashflowReviewRules.CLOSING_OK, 3));
        assertEquals("缺期初，期末不可计算；流水逐笔衔接",
                CashflowReviewRules.reconcileSummaryOf(CashflowReviewRules.CLOSING_OPENING_MISSING, 0));
    }

    /** 人工对账维度在 P1 恒为「未人工对账」，不得被任何计算状态改写。 */
    @Test
    void manualReconcileIsAlwaysNotStartedInP1() {
        assertEquals("未人工对账", CashflowReviewRules.manualReconcileLabel(CashflowReviewRules.MANUAL_NOT_STARTED));
        assertEquals("未人工对账", CashflowReviewRules.manualReconcileLabel(null));
    }
}
