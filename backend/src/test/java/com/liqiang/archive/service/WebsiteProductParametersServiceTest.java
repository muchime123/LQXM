package com.liqiang.archive.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.liqiang.archive.entity.WebsiteProduct;
import com.liqiang.archive.repository.WebsiteProductRepository;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WebsiteProductParametersServiceTest {
    @Test
    void savesWebsiteDraftParametersOnly() {
        var products = mock(WebsiteProductRepository.class);
        var jdbc = mock(JdbcTemplate.class);
        var audit = mock(AuditLogService.class);
        var product = new WebsiteProduct();
        product.setName("设备");
        when(products.findByIdAndDeletedAtIsNull(7L)).thenReturn(Optional.of(product));
        when(jdbc.update(any(String.class), any(String.class), eq(7L))).thenReturn(1);
        var service = new WebsiteProductParametersService(products, jdbc, new ObjectMapper(), audit);

        var result = service.update(7L, new WebsiteProductParametersService.ParametersFields(
                Map.of("功率", "1.5kW", "工位", 2)));

        assertEquals(2, result.technicalParameters().size());
        verify(jdbc).update(any(String.class), any(String.class), eq(7L));
        verify(audit).log(eq("WebsiteProduct"), eq(7L), eq("设备"),
                eq("WEBSITE_PRODUCT_PARAMETERS_UPDATE"), any(String.class));
    }

    @Test
    void rejectsPublishedOrNestedParameters() {
        var products = mock(WebsiteProductRepository.class);
        var jdbc = mock(JdbcTemplate.class);
        var product = new WebsiteProduct();
        when(products.findByIdAndDeletedAtIsNull(7L)).thenReturn(Optional.of(product));
        var service = new WebsiteProductParametersService(products, jdbc, new ObjectMapper(), mock(AuditLogService.class));
        product.setStatus("PUBLISHED");
        assertEquals(HttpStatus.CONFLICT, assertThrows(ResponseStatusException.class,
                () -> service.update(7L, new WebsiteProductParametersService.ParametersFields(Map.of())))
                .getStatusCode());
        product.setStatus("DRAFT");
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
                () -> service.update(7L, new WebsiteProductParametersService.ParametersFields(
                        Map.of("nested", Map.of("key", "value"))))).getStatusCode());
        verify(jdbc, never()).update(any(String.class), any(String.class), any());
    }
}
