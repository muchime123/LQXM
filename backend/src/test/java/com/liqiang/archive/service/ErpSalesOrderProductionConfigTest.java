package com.liqiang.archive.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.liqiang.archive.erp.ErpActorResolver;
import com.liqiang.archive.erp.ErpChangeLogWriter;
import com.liqiang.archive.erp.ErpIdempotencyService;
import com.liqiang.archive.security.DataScopeSupport;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ErpSalesOrderProductionConfigTest {
    @Test
    void productionNameAndOptionsMustBelongToSelectedWorkshop() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(contains("manufacturing_workshops"), eq(Long.class), eq(7L)))
                .thenReturn(1L);
        when(jdbc.queryForList(contains("config_type='PRODUCT_NAME'"), eq(String.class), eq(7L)))
                .thenReturn(List.of("全自动气调机"));
        when(jdbc.queryForList(contains("config_type='OPTIONAL_CONFIG'"), eq(String.class), eq(7L)))
                .thenReturn(List.of("加宽"));
        var service = new ErpSalesOrderService(jdbc, mock(ErpActorResolver.class),
                mock(DataScopeSupport.class), mock(ErpIdempotencyService.class),
                mock(ErpChangeLogWriter.class), mock(SalesOutboundService.class),
                new ObjectMapper(), mock(ManufacturingService.class), mock(ErpApprovalService.class));

        assertDoesNotThrow(() -> service.validateProductionConfig(config("全自动气调机", List.of("加宽"))));
        assertThrows(IllegalArgumentException.class,
                () -> service.validateProductionConfig(config("其他产线产品", List.of("加宽"))));
        assertThrows(IllegalArgumentException.class,
                () -> service.validateProductionConfig(config("全自动气调机", List.of("未配置选项"))));
    }

    private ErpSalesOrderService.ProductionConfig config(String name, List<String> options) {
        return new ErpSalesOrderService.ProductionConfig(7L, name, "200x150", "40", "1出1",
                "方形", "PP", "单相220V", "国标", "利强品牌", options, "", false,
                null, null, null, null, null);
    }
}
