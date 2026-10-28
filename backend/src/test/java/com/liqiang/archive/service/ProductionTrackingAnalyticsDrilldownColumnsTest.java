package com.liqiang.archive.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 下钻列表「业务员 / 产品规格 / 模具出数」三列的<b>取数契约</b>。
 *
 * <p>【为什么不用现有的一致性测试盖住】{@link ProductionTrackingAnalyticsConsistencyTest} 那套全是
 * Java 侧行为断言：它能证明「加字段不影响判据」，但<b>证明不了 SQL 真把这三列查了出来</b>。
 * 若只改了 {@code OrderRow} 与 {@code orders()} 而漏改 {@code loadRows()} 的 SELECT，
 * 编译通过、行为测试全绿、前端「也有这三列」—— 只是每格都显示「—」，属于最难发现的静默失败。
 * 所以这里捕获真实下发的 SQL，逐项断言列清单。
 *
 * <p>另外钉死「规格」的拼装式：它必须与生产跟单列表 {@code LIST_COLUMNS} 里的
 * {@code specification_snapshot} 逐字一致（{@code CONCAT_WS(' / ',box_size,box_shape,box_material)}），
 * 两页对同一张单显示出不同规格就是口径分叉。
 */
class ProductionTrackingAnalyticsDrilldownColumnsTest {
    private JdbcTemplate jdbc;
    private ProductionTrackingService tracking;
    private ProductionTrackingAnalyticsService service;

    @BeforeEach
    void setUp(){
        jdbc = mock(JdbcTemplate.class);
        tracking = mock(ProductionTrackingService.class);
        service = new ProductionTrackingAnalyticsService(jdbc, tracking, new ObjectMapper());
        ReflectionTestUtils.setField(service, "jdbc", jdbc);
        ReflectionTestUtils.setField(service, "tracking", tracking);
        // loadRows 往 visible() 返回的 StringBuilder 上续接条件，返回 null 会 NPE。
        when(tracking.visible(any())).thenReturn(new StringBuilder());
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(List.of());
    }

    private String capturedLoadRowsSql(){
        service.orders(null, null, null, "baseline", null, "total", null, null, 1, 30);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(sql.capture(), any(RowMapper.class), any(Object[].class));
        return sql.getValue();
    }

    @Test
    void drilldownSelectCarriesSalespersonSpecificationAndMoldLayout(){
        String sql = capturedLoadRowsSql();
        assertTrue(sql.contains("p.salesperson_name salesperson"), "下钻缺业务员列：" + sql);
        assertTrue(sql.contains("p.mold_layout mold_layout"), "下钻缺模具出数列：" + sql);
        // 规格必须与生产跟单列表 LIST_COLUMNS 的 specification_snapshot 同一条拼装式，
        // 否则同一个产品在两个页面会显示成两种规格（口径分叉）。
        assertTrue(sql.contains("CONCAT_WS(' / ',p.box_size,p.box_shape,p.box_material) specification"),
                "下钻的产品规格拼装式与生产跟单列表不一致：" + sql);
    }

    /**
     * 空串必须归一成 null：{@code CONCAT_WS} 遇到三列全空返回的是空串而不是 SQL NULL，
     * 若原样透给前端，页面会出现「有值但是空白的格子」而不是「—」。
     */
    @Test
    void blankDisplayValuesAreNormalisedToNull(){
        Map<String, Object> blank = new java.util.HashMap<>();
        blank.put("id", 1L);
        blank.put("work_order_no", "SCGD20260006047");
        blank.put("product_name", "封口机");
        blank.put("workshop", "封口机车间");
        blank.put("salesperson", "");
        blank.put("specification", "   ");
        blank.put("mold_layout", null);
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            RowMapper<Object> mapper = invocation.getArgument(1);
            return List.of(mapper.mapRow(row(blank), 0));
        });
        @SuppressWarnings("unchecked")
        var result = (java.util.List<Map<String, Object>>) service
                .orders(null, null, null, "baseline", null, "total", null, null, 1, 30).get("items");
        assertEquals(1, result.size());
        assertNull(result.get(0).get("salesperson"), "空串业务员应折成 null（前端显示「—」）");
        assertNull(result.get(0).get("specification"), "纯空格规格应折成 null");
        assertNull(result.get(0).get("moldLayout"), "缺模具出数应为 null");
    }

    /** 真正有值时三列原样透出，且规格里的分隔符保持「 / 」。 */
    @Test
    void displayValuesPassThroughWhenPresent(){
        Map<String, Object> filled = new java.util.HashMap<>();
        filled.put("id", 2L);
        filled.put("work_order_no", "SCGD20260006048");
        filled.put("product_name", "封口机");
        filled.put("workshop", "封口机车间");
        filled.put("salesperson", " 徐永恩 ");
        filled.put("specification", "120×80 / 双层 / 瓦楞");
        filled.put("mold_layout", "一出四");
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            RowMapper<Object> mapper = invocation.getArgument(1);
            return List.of(mapper.mapRow(row(filled), 0));
        });
        @SuppressWarnings("unchecked")
        var result = (java.util.List<Map<String, Object>>) service
                .orders(null, null, null, "baseline", null, "total", null, null, 1, 30).get("items");
        assertEquals("徐永恩", result.get(0).get("salesperson"), "业务员应去掉首尾空格");
        assertEquals("120×80 / 双层 / 瓦楞", result.get(0).get("specification"));
        assertEquals("一出四", result.get(0).get("moldLayout"));
    }

    /**
     * 最小 ResultSet 桩：只实现被测列，其余返回 null。
     * 用动态代理以免为了一个列清单测试拖进 H2 或完整建表。
     */
    private static java.sql.ResultSet row(Map<String, Object> values){
        return (java.sql.ResultSet) java.lang.reflect.Proxy.newProxyInstance(
                ProductionTrackingAnalyticsDrilldownColumnsTest.class.getClassLoader(),
                new Class<?>[]{java.sql.ResultSet.class},
                (proxy, method, args) -> {
                    String name = method.getName();
                    if ("getString".equals(name)) return toText(values.get(args[0]));
                    if ("getLong".equals(name)) {
                        Object v = values.get(args[0]);
                        return v == null ? 0L : ((Number) v).longValue();
                    }
                    if ("getBoolean".equals(name)) return Boolean.TRUE.equals(values.get(args[0]));
                    if ("getDate".equals(name) || "getTimestamp".equals(name)) return null;
                    if ("wasNull".equals(name)) return false;
                    if ("toString".equals(name)) return "ResultSetStub";
                    if ("hashCode".equals(name)) return System.identityHashCode(proxy);
                    if ("equals".equals(name)) return proxy == args[0];
                    return null;
                });
    }

    private static String toText(Object value){
        if (value == null) return null;
        String text = value.toString();
        // 刻意保留原样（含空格）：归一化由被测代码负责，桩不该替它做。
        return text;
    }
}
