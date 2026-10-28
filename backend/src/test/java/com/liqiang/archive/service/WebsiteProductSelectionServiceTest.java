package com.liqiang.archive.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.liqiang.archive.entity.WebsiteProduct;
import com.liqiang.archive.repository.WebsiteProductRepository;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WebsiteProductSelectionServiceTest {
    @Test
    void savesOnlyWebsiteDraftSelectionAndNormalizesTags() {
        var products = mock(WebsiteProductRepository.class);
        var jdbc = mock(JdbcTemplate.class);
        var audit = mock(AuditLogService.class);
        var service = new WebsiteProductSelectionService(products, jdbc, new ObjectMapper(), audit);
        var product = new WebsiteProduct();
        product.setName("设备");
        when(products.findByIdAndDeletedAtIsNull(7L)).thenReturn(Optional.of(product));
        when(jdbc.update(any(String.class), any(), any(), any(), any(), eq(7L))).thenReturn(1);

        var result = service.update(7L, new WebsiteProductSelectionService.SelectionFields(
                List.of(" 灌装 ", "灌装"), List.of(), List.of("热封"), "  高速  "));

        assertEquals(List.of("灌装"), result.applicationTags());
        assertEquals("高速", result.capacityLevel());
        verify(jdbc).update(any(String.class), eq("[\"灌装\"]"), eq("[]"),
                eq("[\"热封\"]"), eq("高速"), eq(7L));
        verify(audit).log(eq("WebsiteProduct"), eq(7L), eq("设备"),
                eq("WEBSITE_PRODUCT_SELECTION_UPDATE"), any(String.class));
    }

    @Test
    void rejectsPublishedProductAndInvalidTagsWithoutWriting() {
        var products = mock(WebsiteProductRepository.class);
        var jdbc = mock(JdbcTemplate.class);
        var service = new WebsiteProductSelectionService(products, jdbc, new ObjectMapper(), mock(AuditLogService.class));
        var product = new WebsiteProduct();
        when(products.findByIdAndDeletedAtIsNull(7L)).thenReturn(Optional.of(product));
        product.setStatus("PUBLISHED");
        var fields = new WebsiteProductSelectionService.SelectionFields(List.of(), List.of(), List.of(), null);
        assertEquals(HttpStatus.CONFLICT, assertThrows(ResponseStatusException.class,
                () -> service.update(7L, fields)).getStatusCode());
        product.setStatus("DRAFT");
        var invalid = new WebsiteProductSelectionService.SelectionFields(null, List.of(), List.of(), null);
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
                () -> service.update(7L, invalid)).getStatusCode());
        verify(jdbc, never()).update(any(String.class), any(), any(), any(), any(), any());
    }
}
