package com.liqiang.archive.service;

import com.liqiang.archive.entity.WebsiteProduct;
import com.liqiang.archive.repository.WebsiteMediaAssetRepository;
import com.liqiang.archive.repository.WebsiteProductCategoryRepository;
import com.liqiang.archive.repository.WebsiteProductRepository;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WebsiteProductCutoverReadinessServiceTest {
    @Test
    void draftOnlyDatabaseCannotPassTechnicalCutoverGate() {
        var products = mock(WebsiteProductRepository.class);
        var categories = mock(WebsiteProductCategoryRepository.class);
        var media = mock(WebsiteMediaAssetRepository.class);
        var readiness = mock(WebsiteProductPublicationReadinessService.class);
        when(products.countByDeletedAtIsNullAndStatus("DRAFT")).thenReturn(45L);
        when(categories.countByDeletedAtIsNullAndStatus("DRAFT")).thenReturn(9L);
        when(media.countByDeletedAtIsNullAndStatus("DRAFT")).thenReturn(136L);
        when(products.findByDeletedAtIsNullAndStatus(eq("PUBLISHED"), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        var result = new WebsiteProductCutoverReadinessService(products, categories, media, readiness).check();
        assertFalse(result.technicallyReady());
        assertEquals(45, result.draftProducts());
        assertEquals(9, result.draftCategories());
        assertEquals(136, result.draftMedia());
        assertTrue(result.blockers().stream().anyMatch(item -> item.contains("列表会为空")));
        verify(products, never()).save(any(WebsiteProduct.class));
    }

    @Test
    void publishedProductWithMissingMediaBlocksCutover() {
        var products = mock(WebsiteProductRepository.class);
        var categories = mock(WebsiteProductCategoryRepository.class);
        var media = mock(WebsiteMediaAssetRepository.class);
        var readiness = mock(WebsiteProductPublicationReadinessService.class);
        WebsiteProduct product = new WebsiteProduct();
        product.setId(7L);
        product.setName("设备");
        when(products.countByDeletedAtIsNullAndStatus("PUBLISHED")).thenReturn(1L);
        when(products.findByDeletedAtIsNullAndStatus(eq("PUBLISHED"), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(product)));
        when(readiness.check(7L)).thenReturn(new WebsiteProductPublicationReadinessService.ReadinessView(
                7L, false, List.of("主图未设置"), List.of()));

        var result = new WebsiteProductCutoverReadinessService(products, categories, media, readiness).check();
        assertFalse(result.technicallyReady());
        assertEquals(1, result.productIssues().size());
        assertEquals("主图未设置", result.productIssues().get(0).blockers().get(0));
        verify(readiness).check(7L);
    }
}
