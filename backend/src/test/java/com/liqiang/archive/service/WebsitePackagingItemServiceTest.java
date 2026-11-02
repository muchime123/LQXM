package com.liqiang.archive.service;

import com.liqiang.archive.entity.WebsiteMediaAsset;
import com.liqiang.archive.entity.WebsitePackagingItem;
import com.liqiang.archive.entity.WebsitePackagingVariant;
import com.liqiang.archive.repository.WebsiteMediaAssetRepository;
import com.liqiang.archive.repository.WebsitePackagingItemRepository;
import com.liqiang.archive.repository.WebsitePackagingVariantRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;
import java.util.List;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WebsitePackagingItemServiceTest {
    @TempDir Path files;
    private final WebsitePackagingItemRepository items = mock(WebsitePackagingItemRepository.class);
    private final WebsitePackagingVariantRepository variants = mock(WebsitePackagingVariantRepository.class);
    private final WebsiteMediaAssetRepository media = mock(WebsiteMediaAssetRepository.class);
    private final AuditLogService audit = mock(AuditLogService.class);
    private final WebsitePackagingItemService service = new WebsitePackagingItemService(items, variants, media, audit);

    @Test
    void createsWebsiteOwnedDraftWithOwnImage() {
        WebsiteMediaAsset image = new WebsiteMediaAsset();
        image.setMediaType("IMAGE");
        image.setStatus("DRAFT");
        when(media.findByIdAndDeletedAtIsNull(4L)).thenReturn(Optional.of(image));
        when(items.saveAndFlush(any())).thenAnswer(call -> {
            WebsitePackagingItem item = call.getArgument(0);
            item.setId(7L);
            return item;
        });

        WebsitePackagingItem draft = service.createDraft(fields(4L));

        assertEquals("DRAFT", draft.getStatus());
        assertEquals("托盒", draft.getName());
        assertEquals(4L, draft.getCoverMediaId());
        verify(audit).log("WebsitePackagingItem", 7L, "托盒", "WEBSITE_PACKAGING_CREATE", "创建官网包材草稿");
    }

    @Test
    void rejectsArchivedMediaAndDuplicateSlug() {
        WebsiteMediaAsset archived = new WebsiteMediaAsset();
        archived.setMediaType("IMAGE");
        archived.setStatus("ARCHIVED");
        when(media.findByIdAndDeletedAtIsNull(4L)).thenReturn(Optional.of(archived));
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
                () -> service.createDraft(fields(4L))).getStatusCode());
        verify(items, never()).saveAndFlush(any());

        when(items.existsBySlug("food-tray")).thenReturn(true);
        assertEquals(HttpStatus.CONFLICT, assertThrows(ResponseStatusException.class,
                () -> service.createDraft(fields(null))).getStatusCode());
    }

    @Test
    void publishedItemCannotBeChangedThroughDraftEndpoint() {
        WebsitePackagingItem published = new WebsitePackagingItem();
        published.setStatus("PUBLISHED");
        when(items.findByIdAndDeletedAtIsNull(3L)).thenReturn(Optional.of(published));
        assertEquals(HttpStatus.CONFLICT, assertThrows(ResponseStatusException.class,
                () -> service.updateDraft(3L, fields(null))).getStatusCode());
        verify(items, never()).saveAndFlush(any());
    }

    @Test
    void publishingPromotesCoverAndExposesOnlyPublishedRows() throws Exception {
        Path imageFile = files.resolve("website/images/cover.png");
        Files.createDirectories(imageFile.getParent());
        Files.write(imageFile, new byte[] {1, 2, 3});
        ReflectionTestUtils.setField(service, "fileRoot", files.toString());
        WebsitePackagingItem draft = new WebsitePackagingItem();
        draft.setId(3L);
        draft.setName("托盒");
        draft.setCoverMediaId(4L);
        WebsiteMediaAsset image = new WebsiteMediaAsset();
        image.setMediaType("IMAGE");
        image.setStatus("DRAFT");
        image.setStorageKey("website/images/cover.png");
        when(items.findByIdAndDeletedAtIsNull(3L)).thenReturn(Optional.of(draft));
        when(variants.findByItemIdAndDeletedAtIsNullOrderBySortOrderAscIdAsc(3L))
                .thenReturn(List.of(new WebsitePackagingVariant()));
        when(media.findByIdAndDeletedAtIsNull(4L)).thenReturn(Optional.of(image));
        when(items.save(draft)).thenReturn(draft);
        when(items.findByDeletedAtIsNullAndStatusOrderBySortOrderAscIdAsc("PUBLISHED"))
                .thenReturn(List.of(draft));

        service.publish(3L);

        assertEquals("PUBLISHED", draft.getStatus());
        assertEquals("PUBLISHED", image.getStatus());
        assertEquals(List.of(draft), service.published());
        verify(audit).log("WebsitePackagingItem", 3L, "托盒", "WEBSITE_PACKAGING_PUBLISH", "发布官网包材");
    }

    @Test
    void missingOrExternalCoverFileBlocksPublication() {
        ReflectionTestUtils.setField(service, "fileRoot", files.toString());
        WebsitePackagingItem draft = new WebsitePackagingItem();
        draft.setId(3L);
        draft.setName("托盒");
        draft.setCoverMediaId(4L);
        WebsiteMediaAsset image = new WebsiteMediaAsset();
        image.setMediaType("IMAGE");
        image.setStatus("DRAFT");
        when(items.findByIdAndDeletedAtIsNull(3L)).thenReturn(Optional.of(draft));
        when(variants.findByItemIdAndDeletedAtIsNullOrderBySortOrderAscIdAsc(3L))
                .thenReturn(List.of(new WebsitePackagingVariant()));
        when(media.findByIdAndDeletedAtIsNull(4L)).thenReturn(Optional.of(image));

        image.setStorageKey("website/images/missing.png");
        assertEquals(HttpStatus.CONFLICT, assertThrows(ResponseStatusException.class,
                () -> service.publish(3L)).getStatusCode());
        image.setStorageKey("company/logo.png");
        assertEquals(HttpStatus.CONFLICT, assertThrows(ResponseStatusException.class,
                () -> service.publish(3L)).getStatusCode());
        assertEquals("DRAFT", image.getStatus());
        assertEquals("DRAFT", draft.getStatus());
        verify(media, never()).save(any());
        verify(items, never()).save(any());
    }

    @Test
    void incompletePackagingCannotPublish() {
        WebsitePackagingItem draft = new WebsitePackagingItem();
        draft.setId(3L);
        draft.setName("托盒");
        when(items.findByIdAndDeletedAtIsNull(3L)).thenReturn(Optional.of(draft));

        assertEquals(HttpStatus.CONFLICT, assertThrows(ResponseStatusException.class,
                () -> service.publish(3L)).getStatusCode());
        draft.setCoverMediaId(4L);
        assertEquals(HttpStatus.CONFLICT, assertThrows(ResponseStatusException.class,
                () -> service.publish(3L)).getStatusCode());
        verify(media, never()).save(any());
        verify(items, never()).save(any());
    }

    @Test
    void unpublishReturnsToEditableDraftWithoutDeletingMedia() {
        WebsitePackagingItem published = new WebsitePackagingItem();
        published.setId(3L);
        published.setName("托盒");
        published.setStatus("PUBLISHED");
        published.setPublishedAt(java.time.LocalDateTime.now());
        published.setCoverMediaId(4L);
        when(items.findByIdAndDeletedAtIsNull(3L)).thenReturn(Optional.of(published));
        when(items.save(published)).thenReturn(published);

        WebsitePackagingItem draft = service.unpublish(3L);

        assertEquals("DRAFT", draft.getStatus());
        org.junit.jupiter.api.Assertions.assertNull(draft.getPublishedAt());
        assertEquals(4L, draft.getCoverMediaId());
        verify(media, never()).save(any());
        verify(audit).log("WebsitePackagingItem", 3L, "托盒",
                "WEBSITE_PACKAGING_UNPUBLISH", "下线官网包材并转为草稿");
    }

    @Test
    void publishedCatalogLoadsActiveVariantsInOneQuery() {
        WebsitePackagingItem item = new WebsitePackagingItem();
        item.setId(7L);
        item.setStatus("PUBLISHED");
        WebsitePackagingVariant variant = new WebsitePackagingVariant();
        variant.setId(19L);
        variant.setItemId(7L);
        variant.setName("500ml 托盒");
        when(items.findByDeletedAtIsNullAndStatusOrderBySortOrderAscIdAsc("PUBLISHED"))
                .thenReturn(List.of(item));
        when(variants.findByItemIdInAndDeletedAtIsNullOrderBySortOrderAscIdAsc(List.of(7L)))
                .thenReturn(List.of(variant));

        var catalog = service.publishedCatalog();

        assertEquals(1, catalog.size());
        assertEquals(List.of(variant), catalog.get(0).variants());
        verify(variants).findByItemIdInAndDeletedAtIsNullOrderBySortOrderAscIdAsc(List.of(7L));
    }

    private static WebsitePackagingItemService.Fields fields(Long mediaId) {
        return new WebsitePackagingItemService.Fields("food-tray", " 托盒 ", "container", "PET",
                "方形", "500", "冷链餐食", true, mediaId, 1);
    }
}
