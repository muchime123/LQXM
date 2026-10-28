package com.liqiang.archive.service;

import com.liqiang.archive.entity.WebsiteProduct;
import com.liqiang.archive.repository.WebsiteProductRepository;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WebsiteProductDescriptionServiceTest {
    @Test
    void escapesTextIntoParagraphsAndAllowsClearingEnglish() {
        var products = mock(WebsiteProductRepository.class);
        var jdbc = mock(JdbcTemplate.class);
        var audit = mock(AuditLogService.class);
        var product = new WebsiteProduct();
        product.setName("设备");
        when(products.findByIdAndDeletedAtIsNull(7L)).thenReturn(Optional.of(product));
        when(jdbc.update(any(String.class), any(), any(), eq(7L))).thenReturn(1);
        var service = new WebsiteProductDescriptionService(products, jdbc, audit);

        var result = service.update(7L, new WebsiteProductDescriptionService.DescriptionFields(
                "第一段 <script>alert(1)</script>\n\n第二段 & 说明", ""));

        assertEquals("<p>第一段 &lt;script&gt;alert(1)&lt;/script&gt;</p><p>第二段 &amp; 说明</p>",
                result.descriptionHtml());
        assertNull(result.descriptionHtmlEn());
        verify(jdbc).update(any(String.class), eq(result.descriptionHtml()), eq(null), eq(7L));
        verify(audit).log(eq("WebsiteProduct"), eq(7L), eq("设备"),
                eq("WEBSITE_PRODUCT_DESCRIPTION_UPDATE"), any(String.class));
    }

    @Test
    void rejectsPublishedProductWithoutWriting() {
        var products = mock(WebsiteProductRepository.class);
        var jdbc = mock(JdbcTemplate.class);
        var product = new WebsiteProduct();
        product.setStatus("PUBLISHED");
        when(products.findByIdAndDeletedAtIsNull(7L)).thenReturn(Optional.of(product));
        var service = new WebsiteProductDescriptionService(products, jdbc, mock(AuditLogService.class));

        assertEquals(HttpStatus.CONFLICT, assertThrows(ResponseStatusException.class,
                () -> service.update(7L, new WebsiteProductDescriptionService.DescriptionFields("正文", null)))
                .getStatusCode());
        verify(jdbc, never()).update(any(String.class), any(), any(), any());
    }
}
