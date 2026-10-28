package com.liqiang.archive.service;

import com.liqiang.archive.entity.WebsiteMediaAsset;
import com.liqiang.archive.entity.WebsiteProduct;
import com.liqiang.archive.entity.WebsiteProductCategory;
import com.liqiang.archive.repository.WebsiteMediaAssetRepository;
import com.liqiang.archive.repository.WebsiteProductCategoryRepository;
import com.liqiang.archive.repository.WebsiteProductRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WebsiteProductPublicationReadinessServiceTest {
    @TempDir Path files;

    @Test
    void draftCategoryAndMediaAreReportedWithoutPublishing() {
        var products = mock(WebsiteProductRepository.class);
        var categories = mock(WebsiteProductCategoryRepository.class);
        var media = mock(WebsiteMediaAssetRepository.class);
        var jdbc = mock(JdbcTemplate.class);
        var service = new WebsiteProductPublicationReadinessService(products, categories, media, jdbc);
        ReflectionTestUtils.setField(service, "fileRoot", files.toString());
        WebsiteProduct product = product();
        product.setHeroMediaId(5L);
        when(products.findByIdAndDeletedAtIsNull(7L)).thenReturn(Optional.of(product));
        when(categories.findByIdAndDeletedAtIsNull(2L)).thenReturn(Optional.of(category("DRAFT")));
        when(media.findByIdAndDeletedAtIsNull(5L)).thenReturn(Optional.of(image("DRAFT", "website/images/a.png")));
        when(jdbc.query(any(String.class), any(org.springframework.jdbc.core.RowMapper.class), eq(7L)))
                .thenReturn(List.of());

        var result = service.check(7L);
        assertFalse(result.ready());
        assertTrue(result.blockers().stream().anyMatch(item -> item.contains("分类未发布")));
        assertTrue(result.blockers().stream().anyMatch(item -> item.contains("主图不是已发布")));
        assertTrue(result.blockers().stream().anyMatch(item -> item.contains("图库为空")));
        assertTrue(result.warnings().stream().anyMatch(item -> item.contains("SEO")));
    }

    @Test
    void publishedMediaMustHaveReadableWebsiteFile() throws Exception {
        var products = mock(WebsiteProductRepository.class);
        var categories = mock(WebsiteProductCategoryRepository.class);
        var media = mock(WebsiteMediaAssetRepository.class);
        var jdbc = mock(JdbcTemplate.class);
        var service = new WebsiteProductPublicationReadinessService(products, categories, media, jdbc);
        ReflectionTestUtils.setField(service, "fileRoot", files.toString());
        WebsiteProduct product = product();
        product.setHeroMediaId(5L);
        product.setCardMediaId(5L);
        product.setSeoTitle("标题");
        product.setSeoDescription("描述");
        when(products.findByIdAndDeletedAtIsNull(7L)).thenReturn(Optional.of(product));
        when(categories.findByIdAndDeletedAtIsNull(2L)).thenReturn(Optional.of(category("PUBLISHED")));
        when(media.findByIdAndDeletedAtIsNull(5L)).thenReturn(Optional.of(image("PUBLISHED", "website/images/a.png")));
        when(jdbc.query(any(String.class), any(org.springframework.jdbc.core.RowMapper.class), eq(7L)))
                .thenReturn(List.of(5L));

        assertFalse(service.check(7L).ready());
        Path image = files.resolve("website/images/a.png");
        Files.createDirectories(image.getParent());
        Files.write(image, new byte[] {1, 2, 3});
        assertTrue(service.check(7L).ready());
    }

    @Test
    void internalOrInlineDescriptionMediaBlocksPublication() throws Exception {
        var products = mock(WebsiteProductRepository.class);
        var categories = mock(WebsiteProductCategoryRepository.class);
        var media = mock(WebsiteMediaAssetRepository.class);
        var jdbc = mock(JdbcTemplate.class);
        var service = new WebsiteProductPublicationReadinessService(products, categories, media, jdbc);
        ReflectionTestUtils.setField(service, "fileRoot", files.toString());
        WebsiteProduct product = product();
        product.setHeroMediaId(5L);
        product.setCardMediaId(5L);
        when(products.findByIdAndDeletedAtIsNull(7L)).thenReturn(Optional.of(product));
        when(categories.findByIdAndDeletedAtIsNull(2L)).thenReturn(Optional.of(category("PUBLISHED")));
        when(media.findByIdAndDeletedAtIsNull(5L)).thenReturn(Optional.of(image("PUBLISHED", "website/images/a.png")));
        when(jdbc.query(any(String.class), any(org.springframework.jdbc.core.RowMapper.class), eq(7L)))
                .thenReturn(List.of(5L));
        when(jdbc.queryForObject(any(String.class), eq(String.class), eq(7L)))
                .thenReturn("<p><img src=\"/api/files/company?name=old.jpg\"></p>");
        Path image = files.resolve("website/images/a.png");
        Files.createDirectories(image.getParent());
        Files.write(image, new byte[] {1, 2, 3});

        var result = service.check(7L);
        assertFalse(result.ready());
        assertTrue(result.blockers().stream().anyMatch(item -> item.contains("内嵌媒体")));
        assertTrue(result.blockers().stream().anyMatch(item -> item.contains("内部档案文件")));
    }

    private static WebsiteProduct product() {
        WebsiteProduct value = new WebsiteProduct();
        value.setName("设备");
        value.setSlug("machine");
        value.setSummary("简介");
        value.setCategoryId(2L);
        return value;
    }

    private static WebsiteProductCategory category(String status) {
        WebsiteProductCategory value = new WebsiteProductCategory();
        value.setStatus(status);
        return value;
    }

    private static WebsiteMediaAsset image(String status, String storageKey) {
        WebsiteMediaAsset value = new WebsiteMediaAsset();
        value.setStatus(status);
        value.setMediaType("IMAGE");
        value.setStorageKey(storageKey);
        return value;
    }
}
