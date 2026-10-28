package com.liqiang.archive.service;

import com.liqiang.archive.entity.WebsiteProductCategory;
import com.liqiang.archive.repository.WebsiteProductCategoryRepository;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

import java.util.Optional;

class WebsiteProductCategoryServiceTest {
    @Test
    void createsTopLevelCategoryDraftAndRejectsDuplicateSlug() {
        WebsiteProductCategoryRepository repository = mock(WebsiteProductCategoryRepository.class);
        WebsiteProductCategoryService service = new WebsiteProductCategoryService(
                repository, mock(AuditLogService.class));
        when(repository.save(any(WebsiteProductCategory.class))).thenAnswer(call -> call.getArgument(0));

        WebsiteProductCategory draft = service.createDraft(
                new WebsiteProductCategoryService.CategoryFields("设备", "equipment", 2));
        assertEquals("DRAFT", draft.getStatus());
        assertNull(draft.getParentId());
        assertEquals(2, draft.getSortOrder());

        when(repository.existsBySlug("equipment")).thenReturn(true);
        ResponseStatusException duplicate = assertThrows(ResponseStatusException.class,
                () -> service.createDraft(new WebsiteProductCategoryService.CategoryFields(
                        "设备", "equipment", 2)));
        assertEquals(HttpStatus.CONFLICT, duplicate.getStatusCode());
    }

    @Test
    void updatesDraftAndProtectsPublishedOrDuplicateSlug() {
        WebsiteProductCategoryRepository repository = mock(WebsiteProductCategoryRepository.class);
        AuditLogService audit = mock(AuditLogService.class);
        WebsiteProductCategoryService service = new WebsiteProductCategoryService(repository, audit);
        WebsiteProductCategory category = new WebsiteProductCategory();
        category.setId(7L);
        category.setName("旧名称");
        category.setSlug("old-slug");
        when(repository.findByIdAndDeletedAtIsNull(7L)).thenReturn(Optional.of(category));
        when(repository.save(any(WebsiteProductCategory.class))).thenAnswer(call -> call.getArgument(0));

        WebsiteProductCategory updated = service.updateDraft(7L,
                new WebsiteProductCategoryService.CategoryFields("新名称", "new-slug", 3));
        assertEquals("新名称", updated.getName());
        assertEquals("new-slug", updated.getSlug());
        assertEquals(3, updated.getSortOrder());
        verify(audit).log("WebsiteProductCategory", 7L, "新名称",
                "WEBSITE_PRODUCT_CATEGORY_UPDATE", "更新官网产品分类草稿");

        when(repository.existsBySlug("taken")).thenReturn(true);
        assertEquals(HttpStatus.CONFLICT, assertThrows(ResponseStatusException.class,
                () -> service.updateDraft(7L, new WebsiteProductCategoryService.CategoryFields(
                        "其他", "taken", 0))).getStatusCode());
        category.setStatus("PUBLISHED");
        assertEquals(HttpStatus.CONFLICT, assertThrows(ResponseStatusException.class,
                () -> service.updateDraft(7L, new WebsiteProductCategoryService.CategoryFields(
                        "其他", "newer", 0))).getStatusCode());
    }
}
