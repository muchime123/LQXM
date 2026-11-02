package com.liqiang.archive.service;

import com.liqiang.archive.entity.WebsitePackagingItem;
import com.liqiang.archive.entity.WebsiteMediaAsset;
import com.liqiang.archive.repository.WebsiteMediaAssetRepository;
import com.liqiang.archive.repository.WebsitePackagingItemRepository;
import com.liqiang.archive.repository.WebsitePackagingVariantRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WebsitePackagingCutoverReadinessServiceTest {
    @TempDir Path root;
    @Test
    void emptyOrIncompleteCatalogBlocksSwitch() {
        var items = mock(WebsitePackagingItemRepository.class);
        var variants = mock(WebsitePackagingVariantRepository.class);
        var service = new WebsitePackagingCutoverReadinessService(items, variants,
                mock(WebsiteMediaAssetRepository.class));
        when(items.findByDeletedAtIsNullOrderByIdAsc()).thenReturn(List.of());
        assertFalse(service.check().technicallyReady());

        WebsitePackagingItem published = new WebsitePackagingItem();
        published.setId(7L);
        published.setName("托盒");
        published.setStatus("PUBLISHED");
        when(items.findByDeletedAtIsNullOrderByIdAsc()).thenReturn(List.of(published));
        var result = service.check();
        assertFalse(result.technicallyReady());
        assertEquals(1, result.publishedItems());
        assertEquals(2, result.itemIssues().get(0).reasons().size());
    }

    @Test
    void publishedCoverMustExistInsideWebsiteDirectory() throws Exception {
        var items = mock(WebsitePackagingItemRepository.class);
        var variants = mock(WebsitePackagingVariantRepository.class);
        var media = mock(WebsiteMediaAssetRepository.class);
        var service = new WebsitePackagingCutoverReadinessService(items, variants, media);
        ReflectionTestUtils.setField(service, "fileRoot", root.toString());
        WebsitePackagingItem item = new WebsitePackagingItem();
        item.setId(7L);
        item.setName("托盒");
        item.setStatus("PUBLISHED");
        item.setCoverMediaId(4L);
        WebsiteMediaAsset cover = new WebsiteMediaAsset();
        cover.setMediaType("IMAGE");
        cover.setStatus("PUBLISHED");
        cover.setStorageKey("website/images/cover.png");
        when(items.findByDeletedAtIsNullOrderByIdAsc()).thenReturn(List.of(item));
        when(media.findByIdAndDeletedAtIsNull(4L)).thenReturn(Optional.of(cover));
        var missing = service.check();
        assertFalse(missing.technicallyReady());
        org.junit.jupiter.api.Assertions.assertTrue(missing.itemIssues().get(0).reasons()
                .contains("封面文件缺失或不在官网媒体目录"));

        Path file = root.resolve("website/images/cover.png");
        Files.createDirectories(file.getParent());
        Files.write(file, new byte[]{1, 2, 3});
        var present = service.check();
        org.junit.jupiter.api.Assertions.assertFalse(present.itemIssues().get(0).reasons()
                .contains("封面文件缺失或不在官网媒体目录"));
    }
}
