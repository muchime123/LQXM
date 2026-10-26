package com.liqiang.archive.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CashflowOpeningServiceTest {
    @Test
    void activeAcceptsBooleanAndNumericJdbcValues() {
        assertTrue(CashflowOpeningService.isActive(true));
        assertTrue(CashflowOpeningService.isActive(1));
        assertFalse(CashflowOpeningService.isActive(false));
        assertFalse(CashflowOpeningService.isActive(0));
        assertFalse(CashflowOpeningService.isActive(null));
    }
}
