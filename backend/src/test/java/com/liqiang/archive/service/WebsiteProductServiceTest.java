package com.liqiang.archive.service;

import com.liqiang.archive.entity.WebsiteProduct;
import com.liqiang.archive.repository.WebsiteProductRepository;
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
    private final WebsiteProductService service = new WebsiteProductService(repository, audit);

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

    private static WebsiteProductService.DraftFields fields(String slug) {
        return new WebsiteProductService.DraftFields("官网独立产品", null, null, slug,
                "公开摘要", null, null, null, 0, false);
    }
}
