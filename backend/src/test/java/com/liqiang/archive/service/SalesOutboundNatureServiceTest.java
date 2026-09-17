package com.liqiang.archive.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SalesOutboundNatureServiceTest {
    @Test
    void mixedPositiveAndNegativeLinesAreInferredAsExchange() {
        var result = SalesOutboundNatureService.infer(
                BigDecimal.ZERO, List.of(new BigDecimal("-1"), BigDecimal.ONE), "", "");
        assertEquals("EXCHANGE", result.type());
        assertEquals(90, result.confidence());
    }

    @Test
    void negativeAmountIsInferredAsReturn() {
        var result = SalesOutboundNatureService.infer(
                new BigDecimal("-100"), List.of(), "", "");
        assertEquals("RETURN", result.type());
        assertEquals(95, result.confidence());
    }

    @Test
    void keywordOnlyNeedsManualReview() {
        var result = SalesOutboundNatureService.infer(
                new BigDecimal("100"), List.of(BigDecimal.ONE), "退换货", "");
        assertEquals("RETURN_OR_EXCHANGE", result.type());
        assertEquals(60, result.confidence());
    }

    @Test
    void ordinaryOutboundRemainsSale() {
        var result = SalesOutboundNatureService.infer(
                new BigDecimal("100"), List.of(BigDecimal.ONE), "物流", "普通订单");
        assertEquals("SALE", result.type());
        assertEquals(100, result.confidence());
    }
}
