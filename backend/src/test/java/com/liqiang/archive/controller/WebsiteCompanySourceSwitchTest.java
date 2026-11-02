package com.liqiang.archive.controller;

import com.liqiang.archive.entity.CompanyInfo;
import com.liqiang.archive.entity.WebsiteCompanyProfile;
import com.liqiang.archive.entity.WebsiteCompanyMilestone;
import com.liqiang.archive.repository.MediaAccountRepository;
import com.liqiang.archive.repository.WebsiteInquiryRepository;
import com.liqiang.archive.service.CompanyInfoService;
import com.liqiang.archive.service.InquiryAntiSpamService;
import com.liqiang.archive.service.ProductCatalogService;
import com.liqiang.archive.service.WebsiteNativeCompanyQueryService;
import com.liqiang.archive.service.WebsiteCompanyMilestoneService;
import com.liqiang.archive.service.WebsiteSocialLinkService;
import com.liqiang.archive.entity.WebsiteSocialLink;
import com.liqiang.archive.service.WebsiteNativeProductQueryService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class WebsiteCompanySourceSwitchTest {
    private final CompanyInfoService legacyCompany = mock(CompanyInfoService.class);
    private final WebsiteNativeCompanyQueryService nativeCompany = mock(WebsiteNativeCompanyQueryService.class);
    private final WebsiteCompanyMilestoneService nativeMilestones = mock(WebsiteCompanyMilestoneService.class);
    private final WebsiteSocialLinkService nativeSocialLinks = mock(WebsiteSocialLinkService.class);
    private final MediaAccountRepository legacySocialLinks = mock(MediaAccountRepository.class);
    private final ProductCatalogService products = mock(ProductCatalogService.class);
    private final WebsiteNativeProductQueryService nativeProducts = mock(WebsiteNativeProductQueryService.class);
    private final WebsiteController controller = new WebsiteController(legacyCompany, products,
            nativeProducts, nativeCompany, nativeMilestones, nativeSocialLinks,
            mock(com.liqiang.archive.service.WebsitePackagingItemService.class),
            mock(WebsiteInquiryRepository.class), mock(InquiryAntiSpamService.class), legacySocialLinks);

    @Test
    void nativeCompanyDetailReadsOnlyPublishedWebsiteCopy() {
        ReflectionTestUtils.setField(controller, "companySource", "native");
        var copy = new WebsiteCompanyProfile();
        copy.setCompanyName("官网名称");
        copy.setMainBusiness("官网业务");
        copy.setLogoMediaId(12L);
        when(nativeCompany.published()).thenReturn(copy);

        var result = controller.companyDetail();

        assertEquals("官网名称", result.companyName());
        assertEquals("官网业务", result.mainBusiness());
        assertEquals(12L, result.logoMediaId());
        verifyNoInteractions(legacyCompany);
    }

    @Test
    void nativeHomeDoesNotReadArchiveCompanyWhenCopyMissing() {
        ReflectionTestUtils.setField(controller, "companySource", "native");
        ReflectionTestUtils.setField(controller, "productSource", "native");
        when(nativeProducts.featured(12)).thenReturn(List.of());
        when(nativeProducts.list(null, null, null, null, null, null, 0, 6))
                .thenReturn(new WebsiteNativeProductQueryService.PageView(List.of(), 0, 0, 0));
        when(nativeMilestones.published()).thenReturn(List.of());

        var result = (WebsiteController.NativeHomeView) controller.home();

        assertNull(result.company());
        assertEquals("native", result.companySource());
        verifyNoInteractions(legacyCompany);
    }

    @Test
    void nativeMilestonesNeverFallBackToArchiveTimeline() {
        ReflectionTestUtils.setField(controller, "companySource", "native");
        var item = new WebsiteCompanyMilestone();
        item.setYear(2020);
        item.setTitle("官网历程");
        when(nativeMilestones.published()).thenReturn(List.of(item));
        assertEquals("官网历程", controller.milestones().get(0).title());
        verifyNoInteractions(legacyCompany);
    }

    @Test
    void nativeOfficialLinksDoNotReadInternalMediaAccounts() {
        ReflectionTestUtils.setField(controller, "companySource", "native");
        var link = new WebsiteSocialLink();
        link.setId(3L);
        link.setPlatform("抖音");
        link.setAccountName("官网号");
        link.setLink("https://example.com");
        when(nativeSocialLinks.published()).thenReturn(List.of(link));
        assertEquals("官网号", controller.officialMediaAccounts().get(0).accountName());
        verifyNoInteractions(legacySocialLinks);
    }

    @Test
    void explicitLegacyOverrideRemainsAvailableForRollback() {
        ReflectionTestUtils.setField(controller, "companySource", "legacy");
        var company = new CompanyInfo();
        company.setCompanyName("旧名称");
        when(legacyCompany.getCompanyInfo()).thenReturn(company);
        assertEquals("旧名称", controller.companyDetail().companyName());
        verifyNoInteractions(nativeCompany);
    }

}
