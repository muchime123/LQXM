package com.liqiang.archive.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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

    /**
     * 「台账最新出库日」的取数口径：必须是两段 {@code ORDER BY ... DESC LIMIT 1}，
     * <b>不得</b>回到 {@code MAX(COALESCE(...)) ... WHERE external_source='H3YUN'}。
     *
     * <p>为什么钉死 SQL 形态而不只钉结果：原写法实测 277ms（占跟单列表 440ms 的 2/3），
     * 但返回值与原写法逐字相等 —— 纯看结果的测试永远发现不了它慢。根因是 WHERE 条件在全表
     * 109379/109379 行上恒真，优化器因此放弃 idx_so_ship_date 改做全量索引扫描。
     */
    @Test
    void coverageThroughUsesOrderByDescLimitNotAggregateOverWhere() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(java.sql.Date.class)))
                .thenReturn(java.sql.Date.valueOf("2026-10-13"));
        ProductionOutboundTraceService service = new ProductionOutboundTraceService(jdbc, null, null);

        List<Map<String, Object>> items = new ArrayList<>(List.of(new HashMap<>(Map.of("id", 1L))));
        service.attachSummaries(items);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).queryForObject(sql.capture(), eq(java.sql.Date.class));
        // 文本块里的换行/缩进不稳定，统一压成单空格再断言，避免脆弱的空格匹配。
        String coverageSql = sql.getValue().replaceAll("\\s+", " ");
        assertFalse(coverageSql.contains("MAX("),
                "覆盖日查询不得用 MAX() 聚合：它会把 external_source='H3YUN' 恒真条件带进来，退化成全表扫描");
        assertFalse(coverageSql.contains("external_source"),
                "覆盖日查询不得带 external_source 过滤：该条件在全表上恒真，只会让优化器放弃排序列索引");
        assertTrue(coverageSql.contains("ORDER BY ship_date DESC LIMIT 1"), coverageSql);
        assertTrue(coverageSql.contains("ORDER BY business_date DESC LIMIT 1"), coverageSql);

        assertEquals("2026-10-13", items.getFirst().get("salesTraceCoverageThrough"));
    }

    /** 覆盖日整张台账唯一，多个 item、多次调用都只查一次库。 */
    @Test
    void coverageThroughIsQueriedOncePerCallNotPerItem() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(java.sql.Date.class)))
                .thenReturn(java.sql.Date.valueOf("2026-10-13"));
        ProductionOutboundTraceService service = new ProductionOutboundTraceService(jdbc, null, null);

        List<Map<String, Object>> items = new ArrayList<>();
        for (long id = 1; id <= 50; id++) items.add(new HashMap<>(Map.of("id", id)));
        service.attachSummaries(items);

        verify(jdbc, times(1)).queryForObject(anyString(), eq(java.sql.Date.class));
    }

    /** 空表：覆盖日为 null，且「已查过但没数据」也要缓存，不能每个请求都重扫一遍空表。 */
    @Test
    void emptyOutboundLedgerCachesTheEmptyResultInsteadOfRescanning() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(java.sql.Date.class))).thenReturn(null);
        ProductionOutboundTraceService service = new ProductionOutboundTraceService(jdbc, null, null);

        for (int round = 0; round < 3; round++) {
            List<Map<String, Object>> items = new ArrayList<>(List.of(new HashMap<>(Map.of("id", 1L))));
            service.attachSummaries(items);
            assertNull(items.getFirst().get("salesTraceCoverageThrough"));
        }

        verify(jdbc, times(1)).queryForObject(anyString(), eq(java.sql.Date.class));
    }

    /**
     * 按 orderId 回填的语义必须与原「每个 item 都 stream().filter() 全量结果」完全一致：
     * 未命中 → 0/false；命中 → 取该行值；staleCount&gt;0 → stale=true；unitCount&gt;1 → mixedUnits=true；
     * 入库日晚于覆盖日 → beyondCoverage=true。
     */
    @Test
    void summariesAreFilledPerOrderWithoutChangingTheMeaning() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(java.sql.Date.class)))
                .thenReturn(java.sql.Date.valueOf("2026-10-13"));

        // 三条 SELECT：入库汇总、归属汇总，各返回多行（含未命中的 item）
        when(jdbc.queryForList(anyString(), any(Object[].class)))
                .thenReturn(new ArrayList<>(List.of(
                        Map.of("orderId", 10L, "receivedQuantity", q("8"),
                               "unitCount", 2L, "latestReceiptDate", java.sql.Date.valueOf("2026-10-20")),
                        Map.of("orderId", 20L, "receivedQuantity", q("3"),
                               "unitCount", 1L, "latestReceiptDate", java.sql.Date.valueOf("2026-10-01")))))
                .thenReturn(new ArrayList<>(List.of(
                        Map.of("orderId", 10L, "confirmedQuantity", q("5"), "staleCount", 1L),
                        Map.of("orderId", 20L, "confirmedQuantity", q("2"), "staleCount", 0L))));

        ProductionOutboundTraceService service = new ProductionOutboundTraceService(jdbc, null, null);
        List<Map<String, Object>> items = new ArrayList<>();
        items.add(new HashMap<>(Map.of("id", 10L)));
        items.add(new HashMap<>(Map.of("id", 20L)));
        items.add(new HashMap<>(Map.of("id", 99L)));   // 两个汇总里都没有 → 必须落 0/false
        service.attachSummaries(items);

        assertEquals(q("8"), items.get(0).get("salesTraceReceivedQuantity"));
        assertEquals(q("5"), items.get(0).get("salesTraceConfirmedQuantity"));
        assertEquals(true, items.get(0).get("salesTraceStale"));
        assertEquals(true, items.get(0).get("salesTraceMixedUnits"));
        assertEquals(true, items.get(0).get("salesTraceBeyondCoverage"));   // 10-20 > 10-13

        assertEquals(q("3"), items.get(1).get("salesTraceReceivedQuantity"));
        assertEquals(q("2"), items.get(1).get("salesTraceConfirmedQuantity"));
        assertEquals(false, items.get(1).get("salesTraceStale"));
        assertEquals(false, items.get(1).get("salesTraceMixedUnits"));
        assertEquals(false, items.get(1).get("salesTraceBeyondCoverage"));  // 10-01 <= 10-13

        assertEquals(BigDecimal.ZERO, items.get(2).get("salesTraceReceivedQuantity"));
        assertEquals(BigDecimal.ZERO, items.get(2).get("salesTraceConfirmedQuantity"));
        assertEquals(false, items.get(2).get("salesTraceStale"));
        assertEquals(false, items.get(2).get("salesTraceMixedUnits"));
        assertEquals(false, items.get(2).get("salesTraceBeyondCoverage"));
    }
}
