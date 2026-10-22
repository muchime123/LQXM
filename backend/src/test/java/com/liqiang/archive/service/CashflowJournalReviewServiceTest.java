package com.liqiang.archive.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CashflowJournalReviewServiceTest {
    @Test
    void movingAnUnchangedExcelRowDoesNotBecomeANewTransaction() {
        var old = row("账户A#日期#金额#结存#1", null, "120");
        var moved = row("账户A#日期#金额#结存#1", "117", "120");
        var diff = CashflowJournalReviewService.compare(List.of(moved), List.of(old), null);
        assertEquals(0, diff.oldOnlyCount());
        assertEquals(0, diff.newOnlyCount());
    }

    @Test
    void changedBalanceRemainsTwoUnmatchedRowsForHumanReview() {
        var old = row("账户A#日期#金额#旧结存#1", null, "120");
        var changed = row("账户A#日期#金额#新结存#1", "117", "130");
        var diff = CashflowJournalReviewService.compare(List.of(changed), List.of(old), null);
        assertEquals(1, diff.oldOnlyCount());
        assertEquals(1, diff.newOnlyCount());
        assertEquals(List.of("账户A"), diff.accounts());
        assertEquals(2, diff.items().size());
    }

    private CashflowJournalReviewService.ReviewRow row(String key, String excelRow, String balance) {
        return new CashflowJournalReviewService.ReviewRow(key, "账户A", excelRow, "2026-01-01",
                "收款", new BigDecimal("20"), null, new BigDecimal(balance));
    }
}
