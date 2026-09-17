package com.liqiang.archive.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CommissionPolicyHistoricalRateTest {
    @Test
    void receiptAndPreviousMonthFreightUseTheirOwnOccurrenceDates() {
        var oldRate = new CommissionPolicy.Rate(new BigDecimal("0.03"), new BigDecimal("0.04"));
        var newRate = new CommissionPolicy.Rate(new BigDecimal("0.05"), new BigDecimal("0.06"));
        var history = Map.<Long, List<CommissionPolicy.RateEntry>>of(
                23L, List.of(
                        new CommissionPolicy.RateEntry(LocalDate.of(2026, 8, 1), oldRate),
                        new CommissionPolicy.RateEntry(LocalDate.of(2026, 9, 1), newRate)));

        assertEquals(oldRate, CommissionPolicy.rateAt(history, 23L, LocalDate.of(2026, 8, 31)));
        assertEquals(newRate, CommissionPolicy.rateAt(history, 23L, LocalDate.of(2026, 9, 1)));
        assertEquals(newRate, CommissionPolicy.rateAt(history, 23L, null));
    }
}
