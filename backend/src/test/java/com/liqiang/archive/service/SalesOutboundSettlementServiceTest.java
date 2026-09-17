package com.liqiang.archive.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SalesOutboundSettlementServiceTest {

    @Test
    void normalizesH3OutboundLifecycleStatuses() {
        assertEquals("CONFIRMED", SalesOutboundSettlementService.normalizeOutboundStatus("已生效"));
        assertEquals("CONFIRMED", SalesOutboundSettlementService.normalizeOutboundStatus("CONFIRMED"));
        assertEquals("DRAFT", SalesOutboundSettlementService.normalizeOutboundStatus("草稿"));
        assertEquals("PROCESSING", SalesOutboundSettlementService.normalizeOutboundStatus("进行中"));
        assertEquals("CANCELLED", SalesOutboundSettlementService.normalizeOutboundStatus("已取消"));
    }
}
