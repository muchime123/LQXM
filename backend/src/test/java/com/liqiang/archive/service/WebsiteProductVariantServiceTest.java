package com.liqiang.archive.service;

import com.liqiang.archive.entity.WebsiteProduct;
import com.liqiang.archive.entity.WebsiteProductVariant;
import com.liqiang.archive.repository.WebsiteProductVariantRepository;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WebsiteProductVariantServiceTest {
    private final WebsiteProductService products = mock(WebsiteProductService.class);
    private final WebsiteProductVariantRepository variants = mock(WebsiteProductVariantRepository.class);
    private final AuditLogService audit = mock(AuditLogService.class);
    private final WebsiteProductVariantService service = new WebsiteProductVariantService(products, variants, audit);

    @Test
    void createsIndependentDraftOnlyUnderDraftWebsiteProduct() {
        WebsiteProduct product = new WebsiteProduct();
        product.setId(10L);
        when(products.get(10L)).thenReturn(product);
        when(variants.save(any(WebsiteProductVariant.class))).thenAnswer(call -> {
            WebsiteProductVariant variant = call.getArgument(0);
            variant.setId(20L);
            return variant;
        });

        WebsiteProductVariant created = service.createDraft(10L, fields());

        assertEquals(10L, created.getProductId());
        assertEquals("DRAFT", created.getStatus());
        assertEquals("公开规格", created.getName());
        verify(audit).log("WebsiteProductVariant", 20L, "公开规格",
                "WEBSITE_PRODUCT_VARIANT_CREATE", "创建官网产品规格草稿 productId=10");

        product.setStatus("PUBLISHED");
        ResponseStatusException blocked = assertThrows(ResponseStatusException.class,
                () -> service.createDraft(10L, fields()));
        assertEquals(HttpStatus.CONFLICT, blocked.getStatusCode());
    }

    @Test
    void rejectsVariantFromAnotherProductAndPublishedVariant() {
        WebsiteProduct product = new WebsiteProduct();
        product.setId(10L);
        when(products.get(10L)).thenReturn(product);

        ResponseStatusException missing = assertThrows(ResponseStatusException.class,
                () -> service.updateDraft(10L, 99L, fields()));
        assertEquals(HttpStatus.NOT_FOUND, missing.getStatusCode());
        verify(variants).findByIdAndProductIdAndDeletedAtIsNull(99L, 10L);

        WebsiteProductVariant published = new WebsiteProductVariant();
        published.setStatus("PUBLISHED");
        when(variants.findByIdAndProductIdAndDeletedAtIsNull(99L, 10L))
                .thenReturn(Optional.of(published));
        ResponseStatusException blocked = assertThrows(ResponseStatusException.class,
                () -> service.updateDraft(10L, 99L, fields()));
        assertEquals(HttpStatus.CONFLICT, blocked.getStatusCode());
    }

    private static WebsiteProductVariantService.VariantFields fields() {
        return new WebsiteProductVariantService.VariantFields("公开规格", "MODEL-1",
                "220 × 150 mm", "仅供官网展示", 1);
    }
}
