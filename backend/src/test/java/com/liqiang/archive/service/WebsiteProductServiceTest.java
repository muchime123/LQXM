package com.liqiang.archive.service;

import com.liqiang.archive.entity.WebsiteProduct;
import com.liqiang.archive.entity.WebsiteProductCategory;
import com.liqiang.archive.entity.WebsiteMediaAsset;
import com.liqiang.archive.repository.WebsiteProductRepository;
import com.liqiang.archive.repository.WebsiteProductCategoryRepository;
import com.liqiang.archive.repository.WebsiteMediaAssetRepository;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WebsiteProductServiceTest {
    private final WebsiteProductRepository repository = mock(WebsiteProductRepository.class);
    private final AuditLogService audit = mock(AuditLogService.class);
    private final WebsiteProductCategoryRepository categories = mock(WebsiteProductCategoryRepository.class);
    private final WebsiteMediaAssetRepository media = mock(WebsiteMediaAssetRepository.class);
    private final WebsiteProductService service = new WebsiteProductService(repository, categories, media, audit);

    @Test
    void createsWebsiteDraftWithoutPublishingOrInternalArchiveReference() {
        when(repository.save(any(WebsiteProduct.class))).thenAnswer(call -> {
            WebsiteProduct product = call.getArgument(0);
            product.setId(7L);
            return product;
        });

        WebsiteProduct created = service.createDraft(fields("own-product"));

        assertEquals(7L, created.getId());
        assertEquals("DRAFT", created.getStatus());
        assertNull(created.getPublishedAt());
        assertEquals("own-product", created.getSlug());
        verify(repository).existsBySlug("own-product");
        verify(audit).log("WebsiteProduct", 7L, "官网独立产品", "WEBSITE_PRODUCT_CREATE", "创建官网产品草稿");
    }

    @Test
    void refusesDuplicateSlugAndPublishedRecordUpdate() {
        when(repository.existsBySlug("own-product")).thenReturn(true);
        ResponseStatusException duplicate = assertThrows(ResponseStatusException.class,
                () -> service.createDraft(fields("own-product")));
        assertEquals(HttpStatus.CONFLICT, duplicate.getStatusCode());

        WebsiteProduct published = new WebsiteProduct();
        published.setId(7L);
        published.setStatus("PUBLISHED");
        when(repository.findByIdAndDeletedAtIsNull(7L)).thenReturn(Optional.of(published));
        ResponseStatusException edit = assertThrows(ResponseStatusException.class,
                () -> service.updateDraft(7L, fields("new-slug")));
        assertEquals(HttpStatus.CONFLICT, edit.getStatusCode());
    }

    @Test
    void refusesMissingWebsiteCategory() {
        WebsiteProductService.DraftFields withCategory = new WebsiteProductService.DraftFields(
                12L, "官网独立产品", null, null, null, null, "own-product", "公开摘要", null,
                null, null, 0, false);
        ResponseStatusException invalid = assertThrows(ResponseStatusException.class,
                () -> service.createDraft(withCategory));
        assertEquals(HttpStatus.BAD_REQUEST, invalid.getStatusCode());

        WebsiteProductCategory category = new WebsiteProductCategory();
        category.setId(12L);
        when(categories.findByIdAndDeletedAtIsNull(12L)).thenReturn(Optional.of(category));
        when(repository.save(any(WebsiteProduct.class))).thenAnswer(call -> call.getArgument(0));
        assertEquals(12L, service.createDraft(withCategory).getCategoryId());
    }

    @Test
    void acceptsOnlyWebsiteImagesForDraftAndKeepsThemPrivate() {
        WebsiteProductService.DraftFields withImages = new WebsiteProductService.DraftFields(
                null, "官网独立产品", null, null, 21L, 22L, "own-product", "公开摘要", null,
                null, null, 0, false);
        WebsiteMediaAsset image = new WebsiteMediaAsset();
        image.setMediaType("IMAGE");
        image.setStatus("DRAFT");
        when(media.findByIdAndDeletedAtIsNull(21L)).thenReturn(Optional.of(image));

        ResponseStatusException missing = assertThrows(ResponseStatusException.class,
                () -> service.createDraft(withImages));
        assertEquals(HttpStatus.BAD_REQUEST, missing.getStatusCode());

        WebsiteMediaAsset document = new WebsiteMediaAsset();
        document.setMediaType("DOCUMENT");
        document.setStatus("DRAFT");
        when(media.findByIdAndDeletedAtIsNull(22L)).thenReturn(Optional.of(document));
        ResponseStatusException wrongType = assertThrows(ResponseStatusException.class,
                () -> service.createDraft(withImages));
        assertEquals(HttpStatus.BAD_REQUEST, wrongType.getStatusCode());

        when(media.findByIdAndDeletedAtIsNull(22L)).thenReturn(Optional.of(image));
        when(repository.save(any(WebsiteProduct.class))).thenAnswer(call -> call.getArgument(0));
        WebsiteProduct draft = service.createDraft(withImages);
        assertEquals(21L, draft.getHeroMediaId());
        assertEquals(22L, draft.getCardMediaId());
        assertEquals("DRAFT", draft.getStatus());
    }

    private static WebsiteProductService.DraftFields fields(String slug) {
        return new WebsiteProductService.DraftFields(null, "官网独立产品", null, null, null, null, slug,
                "公开摘要", null, null, null, 0, false);
    }
}
