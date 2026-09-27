package com.liqiang.archive.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.liqiang.archive.erp.ErpActor;
import com.liqiang.archive.erp.ErpActorResolver;
import com.liqiang.archive.erp.ErpChangeLogWriter;
import com.liqiang.archive.erp.ErpIdempotencyService;
import com.liqiang.archive.security.DataScopeSupport;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ErpSalesOrderLineIdentityTest {
    @Test
    void updatingDraftKeepsExistingLineIdForDownstreamDocuments() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(contains("FROM erp_sales_orders WHERE id=? FOR UPDATE"), eq(42L)))
                .thenReturn(List.of(Map.of("status", "DRAFT")));
        when(jdbc.queryForList(contains("SELECT id FROM erp_sales_order_items WHERE order_id=? FOR UPDATE"), eq(42L)))
                .thenReturn(List.of(Map.of("id", 91L)));
        var service = new ErpSalesOrderService(jdbc, mock(ErpActorResolver.class),
                mock(DataScopeSupport.class), mock(ErpIdempotencyService.class),
                mock(ErpChangeLogWriter.class), mock(SalesOutboundService.class),
                new ObjectMapper(), mock(ManufacturingService.class));
        var line = new ErpSalesOrderService.Line(91L, "外采设备", "标准配置", null, "BUY",
                "台", BigDecimal.ONE, BigDecimal.TEN, null, null, null);
        var request = new ErpSalesOrderService.Request(null, "客户", LocalDate.of(2026, 9, 24),
                null, null, List.of(line));

        Long result = ReflectionTestUtils.invokeMethod(service, "replace", 42L, request,
                new ErpActor(1L, "测试人员"), "request-key");

        assertEquals(42L, result);
        verify(jdbc).update(contains("SET line_no=? WHERE id=?"), eq(-1), eq(91L));
        var updates = mockingDetails(jdbc).getInvocations().stream()
                .filter(call -> call.getMethod().getName().equals("update")).toList();
        assertTrue(updates.stream().anyMatch(call ->
                String.valueOf((Object) call.getArgument(0)).contains("production_config_json=? WHERE id=?")
                        && Long.valueOf(91L).equals(call.getArguments()[call.getArguments().length - 1])));
        assertTrue(updates.stream().noneMatch(call ->
                String.valueOf((Object) call.getArgument(0)).startsWith("DELETE FROM erp_sales_order_items")));
    }

    @Test
    void linkedLineCannotBeRemovedFromDraft() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(contains("FROM erp_sales_orders WHERE id=? FOR UPDATE"), eq(42L)))
                .thenReturn(List.of(Map.of("status", "DRAFT")));
        when(jdbc.queryForList(contains("SELECT id FROM erp_sales_order_items WHERE order_id=? FOR UPDATE"), eq(42L)))
                .thenReturn(List.of(Map.of("id", 91L)));
        when(jdbc.queryForObject(contains("FROM manufacturing_orders WHERE sales_order_item_id=?"),
                eq(Long.class), eq(91L), eq(91L), eq(91L))).thenReturn(1L);
        var service = new ErpSalesOrderService(jdbc, mock(ErpActorResolver.class),
                mock(DataScopeSupport.class), mock(ErpIdempotencyService.class),
                mock(ErpChangeLogWriter.class), mock(SalesOutboundService.class),
                new ObjectMapper(), mock(ManufacturingService.class));
        var replacement = new ErpSalesOrderService.Line(null, "新产品", null, null, "BUY",
                "台", BigDecimal.ONE, null, null, null, null);
        var request = new ErpSalesOrderService.Request(null, "客户", LocalDate.of(2026, 9, 24),
                null, null, List.of(replacement));

        assertThrows(IllegalStateException.class, () -> ReflectionTestUtils.invokeMethod(service,
                "replace", 42L, request, new ErpActor(1L, "测试人员"), "request-key"));
        assertTrue(mockingDetails(jdbc).getInvocations().stream()
                .filter(call -> call.getMethod().getName().equals("update"))
                .noneMatch(call -> String.valueOf((Object) call.getArgument(0))
                        .startsWith("DELETE FROM erp_sales_order_items")));
    }
}
