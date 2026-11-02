package com.liqiang.archive.controller;

import com.liqiang.archive.entity.WebsitePackagingItem;
import com.liqiang.archive.entity.WebsitePackagingVariant;
import com.liqiang.archive.service.WebsitePackagingItemService;
import com.liqiang.archive.service.CompanyInfoService;
import com.liqiang.archive.service.ProductCatalogService;
import com.liqiang.archive.service.WebsiteNativeProductQueryService;
import com.liqiang.archive.service.WebsiteNativeCompanyQueryService;
import com.liqiang.archive.service.WebsiteCompanyMilestoneService;
import com.liqiang.archive.service.WebsiteSocialLinkService;
import com.liqiang.archive.service.InquiryAntiSpamService;
import com.liqiang.archive.repository.WebsiteInquiryRepository;
import com.liqiang.archive.repository.MediaAccountRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WebsitePackagingSourceSwitchTest {
    @Test
    void packagingUsesOnlyPublishedWebsiteRecords() {
        WebsitePackagingItemService nativePackaging = mock(WebsitePackagingItemService.class);
        WebsiteController controller = new WebsiteController(mock(CompanyInfoService.class),
                mock(ProductCatalogService.class), mock(WebsiteNativeProductQueryService.class),
                mock(WebsiteNativeCompanyQueryService.class), mock(WebsiteCompanyMilestoneService.class),
                mock(WebsiteSocialLinkService.class), nativePackaging,
                mock(WebsiteInquiryRepository.class),
                mock(InquiryAntiSpamService.class), mock(MediaAccountRepository.class));
        WebsitePackagingItem item = new WebsitePackagingItem();
        item.setId(9L);
        item.setSlug("food-tray");
        item.setName("托盒");
        item.setCoverMediaId(3L);
        WebsitePackagingVariant variant = new WebsitePackagingVariant();
        variant.setItemId(9L);
        variant.setName("500ml 托盒");
        variant.setPublicSku("BOX-500");
        when(nativePackaging.publishedCatalog()).thenReturn(List.of(
                new WebsitePackagingItemService.CatalogItem(item, List.of(variant))));

        var result = controller.packaging();

        assertEquals(1, result.size());
        assertEquals("托盒", result.get(0).name());
        assertEquals("/api/website/media/3/content", result.get(0).imageUrl());
        assertEquals(1, result.get(0).variantCount());
        assertEquals("BOX-500", result.get(0).variants().get(0).publicSku());
    }
}
