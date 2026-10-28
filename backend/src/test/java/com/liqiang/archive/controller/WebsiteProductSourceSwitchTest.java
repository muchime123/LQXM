package com.liqiang.archive.controller;

import com.liqiang.archive.entity.CompanyInfo;
import com.liqiang.archive.repository.MediaAccountRepository;
import com.liqiang.archive.repository.WebsiteInquiryRepository;
import com.liqiang.archive.service.CompanyInfoService;
import com.liqiang.archive.service.InquiryAntiSpamService;
import com.liqiang.archive.service.PackagingMoldService;
import com.liqiang.archive.service.PackagingService;
import com.liqiang.archive.service.ProductCatalogService;
import com.liqiang.archive.service.WebsiteNativeProductQueryService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class WebsiteProductSourceSwitchTest {
    private final CompanyInfoService company = mock(CompanyInfoService.class);
    private final ProductCatalogService legacy = mock(ProductCatalogService.class);
    private final WebsiteNativeProductQueryService nativeProducts = mock(WebsiteNativeProductQueryService.class);
    private final WebsiteController controller = new WebsiteController(company, legacy,
            nativeProducts, mock(PackagingService.class), mock(PackagingMoldService.class),
            mock(WebsiteInquiryRepository.class), mock(InquiryAntiSpamService.class),
            mock(MediaAccountRepository.class));

    @Test
    void nativeProductEndpointsReadOnlyWebsiteTables() {
        ReflectionTestUtils.setField(controller, "productSource", "native");
        var page = new WebsiteNativeProductQueryService.PageView(List.of(), 0, 0, 0);
        when(nativeProducts.list(null, null, null, null, null, null, 0, 12)).thenReturn(page);
        when(nativeProducts.featured(6)).thenReturn(List.of());
        when(nativeProducts.detail("missing")).thenReturn(Optional.empty());
        var categories = new WebsiteNativeProductQueryService.CategoryFiltersView(
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        when(nativeProducts.filterOptions()).thenReturn(categories);

        assertEquals(page, controller.products(null, null, null, null, null, null, 0, 12));
        assertEquals(List.of(), controller.featuredProducts(6));
        assertEquals(categories, controller.productCategories());
        assertTrue(controller.productDetail("missing").getStatusCode().is4xxClientError());
        verifyNoInteractions(legacy);
    }

    @Test
    void legacyRemainsDefaultForPublishedProductEndpoint() {
        ReflectionTestUtils.setField(controller, "productSource", "legacy");
        when(legacy.getWebsiteFeaturedProducts(500)).thenReturn(List.of());
        assertEquals(List.of(), controller.featuredProducts(6));
        verify(legacy).getWebsiteFeaturedProducts(500);
        verifyNoInteractions(nativeProducts);
    }

    @Test
    void nativeHomeUsesWebsiteProductCountAndNeverFallsBackToArchiveProducts() {
        ReflectionTestUtils.setField(controller, "productSource", "native");
        when(company.getCompanyInfo()).thenReturn(new CompanyInfo());
        when(company.getMilestones()).thenReturn(List.of());
        when(nativeProducts.featured(12)).thenReturn(List.of());
        when(nativeProducts.list(null, null, null, null, null, null, 0, 6))
                .thenReturn(new WebsiteNativeProductQueryService.PageView(List.of(), 45, 8, 0));

        var result = (WebsiteController.NativeHomeView) controller.home();
        assertEquals(45, result.productCount());
        assertTrue(result.products().isEmpty());
        verifyNoInteractions(legacy);
    }
}
