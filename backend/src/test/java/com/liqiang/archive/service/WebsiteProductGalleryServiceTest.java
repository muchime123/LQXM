package com.liqiang.archive.service;

import com.liqiang.archive.entity.WebsiteMediaAsset;
import com.liqiang.archive.entity.WebsiteProduct;
import com.liqiang.archive.repository.WebsiteMediaAssetRepository;
import com.liqiang.archive.repository.WebsiteProductRepository;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WebsiteProductGalleryServiceTest {
    @Test
    void addsWebsiteImageAndUpdatesOnlyAssignedRow() {
        var products = mock(WebsiteProductRepository.class);
        var media = mock(WebsiteMediaAssetRepository.class);
        var jdbc = mock(JdbcTemplate.class);
        var audit = mock(AuditLogService.class);
        var service = new WebsiteProductGalleryService(products, media, jdbc, audit);
        var product = new WebsiteProduct();
        product.setName("设备");
        var image = new WebsiteMediaAsset();
        image.setMediaType("IMAGE");
        image.setStatus("DRAFT");
        when(products.findByIdAndDeletedAtIsNull(7L)).thenReturn(Optional.of(product));
        when(media.findByIdAndDeletedAtIsNull(9L)).thenReturn(Optional.of(image));
        when(jdbc.queryForObject(any(String.class), eq(Integer.class), eq(7L), eq(9L), eq("GALLERY"))).thenReturn(0);
        when(jdbc.update(any(String.class), eq("DETAIL"), eq("说明"), eq(2), eq(11L), eq(7L), eq(9L))).thenReturn(1);
        service.add(7L, new WebsiteProductGalleryService.GalleryFields(9L, "GALLERY", " 说明 ", 1));
        verify(jdbc).update(any(String.class), eq(7L), eq(9L), eq("GALLERY"), eq("说明"), eq(1));
        service.update(7L, 11L, new WebsiteProductGalleryService.GalleryFields(9L, "DETAIL", "说明", 2));
        verify(audit).log(eq("WebsiteProduct"), eq(7L), eq("设备"), eq("WEBSITE_PRODUCT_GALLERY_UPDATE"), any(String.class));
    }

    @Test
    void publishedProductAndNonImageAreRejected() {
        var products = mock(WebsiteProductRepository.class);
        var media = mock(WebsiteMediaAssetRepository.class);
        var jdbc = mock(JdbcTemplate.class);
        var service = new WebsiteProductGalleryService(products, media, jdbc, mock(AuditLogService.class));
        var product = new WebsiteProduct();
        when(products.findByIdAndDeletedAtIsNull(7L)).thenReturn(Optional.of(product));
        product.setStatus("PUBLISHED");
        var fields = new WebsiteProductGalleryService.GalleryFields(9L, "GALLERY", null, 0);
        assertEquals(HttpStatus.CONFLICT, assertThrows(ResponseStatusException.class,
                () -> service.add(7L, fields)).getStatusCode());
        product.setStatus("DRAFT");
        var document = new WebsiteMediaAsset();
        document.setMediaType("DOCUMENT");
        document.setStatus("DRAFT");
        when(media.findByIdAndDeletedAtIsNull(9L)).thenReturn(Optional.of(document));
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
                () -> service.add(7L, fields)).getStatusCode());
        verify(jdbc, never()).update(any(String.class), any(), any(), any(), any(), any());
    }
}
