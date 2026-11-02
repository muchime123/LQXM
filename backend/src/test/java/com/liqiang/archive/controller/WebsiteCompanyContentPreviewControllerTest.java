package com.liqiang.archive.controller;

import com.liqiang.archive.config.GlobalExceptionHandler;
import com.liqiang.archive.entity.WebsiteCompanyMilestone;
import com.liqiang.archive.entity.WebsiteCompanyProfile;
import com.liqiang.archive.entity.WebsiteSocialLink;
import com.liqiang.archive.security.CurrentUserService;
import com.liqiang.archive.security.PermissionInterceptor;
import com.liqiang.archive.service.WebsiteCompanyMilestoneService;
import com.liqiang.archive.service.WebsiteNativeCompanyQueryService;
import com.liqiang.archive.service.WebsiteSocialLinkService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class WebsiteCompanyContentPreviewControllerTest {
    @Test
    void previewRequiresWebsiteManageAndReturnsOnlyNativePublicFields() throws Exception {
        var company = mock(WebsiteNativeCompanyQueryService.class);
        var milestones = mock(WebsiteCompanyMilestoneService.class);
        var socials = mock(WebsiteSocialLinkService.class);
        var users = mock(CurrentUserService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new WebsiteCompanyContentPreviewController(company, milestones, socials))
                .addInterceptors(new PermissionInterceptor(users))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        when(users.current()).thenReturn(new CurrentUserService.CurrentUser(1L, "Reader", "Dept",
                Set.of(), Set.of("company.read"), "ALL", false, "LOCAL", 1L, null, Map.of()));
        mvc.perform(get("/api/website/admin/company-content/preview")).andExpect(status().isForbidden());
        verify(company, never()).published();

        var profile = new WebsiteCompanyProfile();
        profile.setCompanyName("官网公司");
        profile.setLogoMediaId(12L);
        var milestone = new WebsiteCompanyMilestone();
        milestone.setYear(2020);
        milestone.setTitle("官网历程");
        var social = new WebsiteSocialLink();
        social.setId(8L);
        social.setPlatform("抖音");
        social.setAccountName("官网账号");
        social.setLink("https://example.com");
        when(company.published()).thenReturn(profile);
        when(milestones.published()).thenReturn(List.of(milestone));
        when(socials.published()).thenReturn(List.of(social));
        when(users.current()).thenReturn(new CurrentUserService.CurrentUser(2L, "Manager", "Dept",
                Set.of(), Set.of("website.manage"), "ALL", false, "LOCAL", 2L, null, Map.of()));
        mvc.perform(get("/api/website/admin/company-content/preview"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.company.companyName").value("官网公司"))
                .andExpect(jsonPath("$.company.logoMediaId").value(12))
                .andExpect(jsonPath("$.milestones[0].title").value("官网历程"))
                .andExpect(jsonPath("$.officialMediaAccounts[0].accountName").value("官网账号"))
                .andExpect(jsonPath("$.company.licenseFileName").doesNotExist());
        verify(company).published();
    }

    @Test
    void missingPublishedCompanyDoesNotFallBackToArchive() {
        var company = mock(WebsiteNativeCompanyQueryService.class);
        var milestones = mock(WebsiteCompanyMilestoneService.class);
        var socials = mock(WebsiteSocialLinkService.class);
        when(milestones.published()).thenReturn(List.of());
        when(socials.published()).thenReturn(List.of());
        var result = new WebsiteCompanyContentPreviewController(company, milestones, socials).preview();
        org.junit.jupiter.api.Assertions.assertNull(result.company());
        org.junit.jupiter.api.Assertions.assertTrue(result.milestones().isEmpty());
    }
}
