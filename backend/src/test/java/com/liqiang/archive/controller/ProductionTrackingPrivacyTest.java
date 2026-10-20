package com.liqiang.archive.controller;

import com.liqiang.archive.service.H3BusinessQueryService;
import com.liqiang.archive.service.ProductionOutboundTraceService;
import com.liqiang.archive.service.ProductionTrackingService;
import com.liqiang.archive.service.ProductionTrackingAnalyticsService;
import com.liqiang.archive.service.ProductionTrackingWeeklyReviewService;
import com.liqiang.archive.service.ProductionTrackingRoundTripService;
import com.liqiang.archive.service.ProductionTrackingWorkbookImportService;
import com.liqiang.archive.service.ProductionTrackingWorkbookReviewService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProductionTrackingPrivacyTest {
    private final ProductionTrackingService tracking=mock(ProductionTrackingService.class);
    private final ProductionOutboundTraceService trace=mock(ProductionOutboundTraceService.class);
    private final H3BusinessQueryService ledger=mock(H3BusinessQueryService.class);
    private final ProductionTrackingController controller=new ProductionTrackingController(tracking,mock(ProductionTrackingAnalyticsService.class),
            mock(ProductionTrackingWeeklyReviewService.class),trace,ledger,
            mock(ProductionTrackingWorkbookImportService.class),mock(ProductionTrackingWorkbookReviewService.class),
            mock(ProductionTrackingRoundTripService.class));

    @Test
    void purchaseDetailShowsOrderNumberForEveryLineButNoFinancialFields() {
        when(tracking.purchaseReceipts(7)).thenReturn(List.of(Map.of("id",21)));
        when(tracking.visibleOrderNo(7)).thenReturn("SCGD-7");
        when(ledger.purchaseDetail(21L)).thenReturn(Map.of(
                "row",Map.of("id",21,"sourceNo","RK-21","purchaseDate","2026-09-23",
                        "supplierNameRaw","private supplier","totalAmount",999),
                "rows",List.of(Map.of("productionOrderNo","SCGD-7","productCode","P-7","quantity",1,
                                "purchasePrice",999,"amount",999),
                        Map.of("productionOrderNo","OTHER","productCode","P-8","quantity",2,
                                "purchasePrice",888))));

        var result=controller.purchaseReceiptDetail(7,21);
        @SuppressWarnings("unchecked") var row=(Map<String,Object>)result.get("row");
        @SuppressWarnings("unchecked") var lines=(List<Map<String,Object>>)result.get("rows");
        assertEquals("RK-21",row.get("sourceNo"));
        assertEquals("SCGD-7",row.get("trackingOrderNo"));
        assertEquals(2,lines.size());
        assertEquals("P-7",lines.getFirst().get("productCode"));
        assertEquals("SCGD-7",lines.getFirst().get("productionOrderNo"));
        assertEquals("OTHER",lines.get(1).get("productionOrderNo"));
        assertFalse(result.toString().contains("private supplier"));
        assertFalse(result.toString().contains("purchasePrice"));
        assertFalse(result.toString().contains("999"));
        assertEquals("P-8",lines.get(1).get("productCode"));
    }

    @Test
    void outboundDetailContainsOnlyMatchingProductsWithoutCustomerOrPrices() {
        when(trace.trace(7)).thenReturn(Map.of("receipts",List.of(Map.of("productCode","P-7"))));
        when(ledger.outboundDetail(33L)).thenReturn(Map.of(
                "row",Map.of("id",33,"outboundNo","CK-33","customerName","private customer",
                        "contactPhone","123456789","salesAmount",999),
                "rows",List.of(Map.of("productCode","P-7","quantity",1,"salesUnitPrice",999),
                        Map.of("productCode","P-8","quantity",2,"purchaseUnitPrice",888))));

        var result=controller.salesTraceOutbound(7,33);
        @SuppressWarnings("unchecked") var row=(Map<String,Object>)result.get("row");
        @SuppressWarnings("unchecked") var lines=(List<Map<String,Object>>)result.get("rows");
        assertEquals("CK-33",row.get("outboundNo"));
        assertEquals(1,lines.size());
        assertEquals("P-7",lines.getFirst().get("productCode"));
        assertFalse(result.toString().contains("private customer"));
        assertFalse(result.toString().contains("contactPhone"));
        assertFalse(result.toString().contains("999"));
        assertFalse(result.toString().contains("P-8"));
    }
}
