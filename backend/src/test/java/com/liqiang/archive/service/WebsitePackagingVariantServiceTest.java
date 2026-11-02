package com.liqiang.archive.service;

import com.liqiang.archive.entity.WebsitePackagingItem;
import com.liqiang.archive.entity.WebsitePackagingVariant;
import com.liqiang.archive.repository.WebsitePackagingVariantRepository;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WebsitePackagingVariantServiceTest {
    private final WebsitePackagingItemService items = mock(WebsitePackagingItemService.class);
    private final WebsitePackagingVariantRepository variants = mock(WebsitePackagingVariantRepository.class);
    private final AuditLogService audit = mock(AuditLogService.class);
    private final WebsitePackagingVariantService service = new WebsitePackagingVariantService(items, variants, audit);

    @Test
    void createsWebsiteVariantUnderDraftSeries() {
        WebsitePackagingItem parent = new WebsitePackagingItem();
        parent.setStatus("DRAFT");
        when(items.get(7L)).thenReturn(parent);
        when(variants.save(any())).thenAnswer(call -> {
            WebsitePackagingVariant row = call.getArgument(0);
            row.setId(19L);
            return row;
        });

        WebsitePackagingVariant result = service.create(7L, fields());

        assertEquals(7L, result.getItemId());
        assertEquals("500ml 托盒", result.getName());
        assertEquals("500", result.getCapacityMl());
        verify(audit).log("WebsitePackagingVariant", 19L, "500ml 托盒",
                "WEBSITE_PACKAGING_VARIANT_CREATE", "创建官网包材规格 itemId=7");
    }

    @Test
    void publishedSeriesCannotChangeVariant() {
        WebsitePackagingItem parent = new WebsitePackagingItem();
        parent.setStatus("PUBLISHED");
        when(items.get(7L)).thenReturn(parent);
        assertEquals(HttpStatus.CONFLICT, assertThrows(ResponseStatusException.class,
                () -> service.create(7L, fields())).getStatusCode());
        assertEquals(HttpStatus.CONFLICT, assertThrows(ResponseStatusException.class,
                () -> service.remove(7L, 19L)).getStatusCode());
        verify(variants, never()).save(any());
    }

    @Test
    void softRemoveIsScopedToParent() {
        WebsitePackagingItem parent = new WebsitePackagingItem();
        parent.setStatus("DRAFT");
        when(items.get(7L)).thenReturn(parent);
        assertEquals(HttpStatus.NOT_FOUND, assertThrows(ResponseStatusException.class,
                () -> service.remove(7L, 19L)).getStatusCode());
        WebsitePackagingVariant row = new WebsitePackagingVariant();
        row.setName("500ml 托盒");
        when(variants.findByIdAndItemIdAndDeletedAtIsNull(19L, 7L)).thenReturn(Optional.of(row));

        service.remove(7L, 19L);

        assertNotNull(row.getDeletedAt());
        verify(variants).save(row);
    }

    private static WebsitePackagingVariantService.Fields fields() {
        return new WebsitePackagingVariantService.Fields(" 500ml 托盒 ", "BOX-500", "160×120×45 mm",
                "500", "适用冷链餐食", 1);
    }
}
