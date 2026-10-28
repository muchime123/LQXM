package com.liqiang.archive.service;

import com.liqiang.archive.entity.WebsiteMediaAsset;
import com.liqiang.archive.entity.WebsiteProduct;
import com.liqiang.archive.entity.WebsiteProductCategory;
import com.liqiang.archive.repository.WebsiteMediaAssetRepository;
import com.liqiang.archive.repository.WebsiteProductCategoryRepository;
import com.liqiang.archive.repository.WebsiteProductRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WebsiteProductPublicationServiceTest {
    @TempDir Path files;

    @Test
    void productCannotPublishWithReadinessBlockers() {
        var products = mock(WebsiteProductRepository.class);
        var readiness = mock(WebsiteProductPublicationReadinessService.class);
        var service = service(mock(WebsiteMediaAssetRepository.class), mock(WebsiteProductCategoryRepository.class),
                products, readiness, mock(AuditLogService.class));
        WebsiteProduct product = new WebsiteProduct();
        product.setStatus("DRAFT");
        when(products.findByIdAndDeletedAtIsNull(7L)).thenReturn(Optional.of(product));
        when(readiness.check(7L)).thenReturn(new WebsiteProductPublicationReadinessService.ReadinessView(
                7L, false, List.of("主图未设置"), List.of()));

        assertThrows(ResponseStatusException.class, () -> service.publishProduct(7L));
        verify(products, never()).save(product);
        assertEquals("DRAFT", product.getStatus());
    }

    @Test
    void readyProductPublishesWithTimestamp() {
        var products = mock(WebsiteProductRepository.class);
        var readiness = mock(WebsiteProductPublicationReadinessService.class);
        var audit = mock(AuditLogService.class);
        var service = service(mock(WebsiteMediaAssetRepository.class), mock(WebsiteProductCategoryRepository.class),
                products, readiness, audit);
        WebsiteProduct product = new WebsiteProduct();
        product.setId(7L);
        product.setName("设备");
        product.setStatus("DRAFT");
        when(products.findByIdAndDeletedAtIsNull(7L)).thenReturn(Optional.of(product));
        when(readiness.check(7L)).thenReturn(new WebsiteProductPublicationReadinessService.ReadinessView(
                7L, true, List.of(), List.of("SEO 待审阅")));
        when(products.save(product)).thenReturn(product);

        service.publishProduct(7L);
        assertEquals("PUBLISHED", product.getStatus());
        assertNotNull(product.getPublishedAt());
        verify(audit).log("WebsiteProduct", 7L, "设备", "WEBSITE_PRODUCT_PUBLISH", "发布官网产品");
    }

    @Test
    void mediaPublishChecksRealWebsiteFile() throws Exception {
        var media = mock(WebsiteMediaAssetRepository.class);
        var service = service(media, mock(WebsiteProductCategoryRepository.class),
                mock(WebsiteProductRepository.class), mock(WebsiteProductPublicationReadinessService.class),
                mock(AuditLogService.class));
        WebsiteMediaAsset asset = new WebsiteMediaAsset();
        asset.setStatus("DRAFT");
        asset.setStorageKey("website/images/sample.png");
        when(media.findByIdAndDeletedAtIsNull(5L)).thenReturn(Optional.of(asset));
        assertThrows(ResponseStatusException.class, () -> service.publishMedia(5L));
        assertEquals("DRAFT", asset.getStatus());

        Path image = files.resolve("website/images/sample.png");
        Files.createDirectories(image.getParent());
        Files.write(image, new byte[] {1});
        when(media.save(asset)).thenReturn(asset);
        service.publishMedia(5L);
        assertEquals("PUBLISHED", asset.getStatus());
    }

    private WebsiteProductPublicationService service(WebsiteMediaAssetRepository media,
            WebsiteProductCategoryRepository categories, WebsiteProductRepository products,
            WebsiteProductPublicationReadinessService readiness, AuditLogService audit) {
        var service = new WebsiteProductPublicationService(media, categories, products, readiness, audit);
        ReflectionTestUtils.setField(service, "fileRoot", files.toString());
        return service;
    }
}
