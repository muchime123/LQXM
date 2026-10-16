package com.liqiang.archive.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class ProductionOutboundTraceServiceTest {
    private static BigDecimal q(String value) {return new BigDecimal(value);}

    @Test
    void cannotAllocateMoreThanEitherSourceEvenWhenTheOtherHasCapacity() {
        assertDoesNotThrow(()->ProductionOutboundTraceService.requireAvailable(
                q("2.5"),q("10"),q("7.5"),q("12"),q("3")));
        assertThrows(IllegalArgumentException.class,()->ProductionOutboundTraceService.requireAvailable(
                q("2.5001"),q("10"),q("7.5"),q("12"),q("3")));
        assertThrows(IllegalArgumentException.class,()->ProductionOutboundTraceService.requireAvailable(
                q("2.5"),q("10"),q("1"),q("4"),q("1.5001")));
        assertThrows(IllegalArgumentException.class,()->ProductionOutboundTraceService.requireAvailable(
                q("0"),q("10"),q("0"),q("10"),q("0")));
    }
}
