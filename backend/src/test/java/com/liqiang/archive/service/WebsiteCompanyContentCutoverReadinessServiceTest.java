package com.liqiang.archive.service;

import com.liqiang.archive.entity.WebsiteCompanyProfile;
import com.liqiang.archive.repository.WebsiteCompanyProfileRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WebsiteCompanyContentCutoverReadinessServiceTest {
    @Test
    void blocksUnpublishedProfileAndDraftOnlyTimeline() {
        var profiles = mock(WebsiteCompanyProfileRepository.class);
        var content = mock(WebsiteCompanyProfileReadinessService.class);
        var company = mock(WebsiteCompanyProfileParityService.class);
        var timeline = mock(WebsiteCompanyMilestoneParityService.class);
        var social = mock(WebsiteSocialLinkParityService.class);
        when(profiles.findById(1L)).thenReturn(Optional.of(new WebsiteCompanyProfile()));
        when(content.check()).thenReturn(new WebsiteCompanyProfileReadinessService.ReadinessView(true, List.of(), List.of()));
        when(company.compare()).thenReturn(new WebsiteCompanyProfileParityService.ParityView(true, true, 0, List.of()));
        when(timeline.compare()).thenReturn(new WebsiteCompanyMilestoneParityService.ParityView(
                2, 2, 1, 1, List.of(), List.of()));
        when(social.compare()).thenReturn(new WebsiteSocialLinkParityService.ParityView(0, 0, 0, 0, List.of(), List.of()));

        var result = new WebsiteCompanyContentCutoverReadinessService(profiles, content, company, timeline, social).check();

        assertFalse(result.ready());
        assertTrue(result.blockers().stream().anyMatch(item -> item.contains("尚未发布")));
        assertTrue(result.blockers().stream().anyMatch(item -> item.contains("1 条")));
    }

    @Test
    void blocksChangedCompanyFieldsAndExtraPublishedTimeline() {
        var profiles = mock(WebsiteCompanyProfileRepository.class);
        var content = mock(WebsiteCompanyProfileReadinessService.class);
        var company = mock(WebsiteCompanyProfileParityService.class);
        var timeline = mock(WebsiteCompanyMilestoneParityService.class);
        var social = mock(WebsiteSocialLinkParityService.class);
        var profile = new WebsiteCompanyProfile();
        profile.setStatus("PUBLISHED");
        when(profiles.findById(1L)).thenReturn(Optional.of(profile));
        when(content.check()).thenReturn(new WebsiteCompanyProfileReadinessService.ReadinessView(true, List.of(), List.of()));
        when(company.compare()).thenReturn(new WebsiteCompanyProfileParityService.ParityView(true, true, 1, List.of()));
        when(timeline.compare()).thenReturn(new WebsiteCompanyMilestoneParityService.ParityView(
                0, 1, 0, 0, List.of(), List.of(new WebsiteCompanyMilestoneParityService.WebsiteRow(
                        5L, 2020, "新增", null, "PUBLISHED"))));
        when(social.compare()).thenReturn(new WebsiteSocialLinkParityService.ParityView(0, 0, 0, 0, List.of(), List.of()));

        var result = new WebsiteCompanyContentCutoverReadinessService(profiles, content, company, timeline, social).check();

        assertFalse(result.ready());
        assertTrue(result.blockers().stream().anyMatch(item -> item.contains("字段不同")));
        assertTrue(result.blockers().stream().anyMatch(item -> item.contains("另有 1 条")));
    }

    @Test
    void exactPublishedCopyCanPass() {
        var profiles = mock(WebsiteCompanyProfileRepository.class);
        var content = mock(WebsiteCompanyProfileReadinessService.class);
        var company = mock(WebsiteCompanyProfileParityService.class);
        var timeline = mock(WebsiteCompanyMilestoneParityService.class);
        var social = mock(WebsiteSocialLinkParityService.class);
        var profile = new WebsiteCompanyProfile();
        profile.setStatus("PUBLISHED");
        when(profiles.findById(1L)).thenReturn(Optional.of(profile));
        when(content.check()).thenReturn(new WebsiteCompanyProfileReadinessService.ReadinessView(true, List.of(), List.of()));
        when(company.compare()).thenReturn(new WebsiteCompanyProfileParityService.ParityView(true, true, 0, List.of()));
        when(timeline.compare()).thenReturn(new WebsiteCompanyMilestoneParityService.ParityView(
                2, 2, 2, 0, List.of(), List.of()));
        when(social.compare()).thenReturn(new WebsiteSocialLinkParityService.ParityView(0, 0, 0, 0, List.of(), List.of()));

        assertTrue(new WebsiteCompanyContentCutoverReadinessService(profiles, content, company, timeline, social).check().ready());
    }

    @Test
    void blocksMissingAndExtraPublishedOfficialLinks() {
        var profiles = mock(WebsiteCompanyProfileRepository.class);
        var content = mock(WebsiteCompanyProfileReadinessService.class);
        var company = mock(WebsiteCompanyProfileParityService.class);
        var timeline = mock(WebsiteCompanyMilestoneParityService.class);
        var social = mock(WebsiteSocialLinkParityService.class);
        var profile = new WebsiteCompanyProfile();
        profile.setStatus("PUBLISHED");
        when(profiles.findById(1L)).thenReturn(Optional.of(profile));
        when(content.check()).thenReturn(new WebsiteCompanyProfileReadinessService.ReadinessView(true, List.of(), List.of()));
        when(company.compare()).thenReturn(new WebsiteCompanyProfileParityService.ParityView(true, true, 0, List.of()));
        when(timeline.compare()).thenReturn(new WebsiteCompanyMilestoneParityService.ParityView(0, 0, 0, 0, List.of(), List.of()));
        when(social.compare()).thenReturn(new WebsiteSocialLinkParityService.ParityView(
                1, 1, 0, 1, List.of(), List.of(new WebsiteSocialLinkParityService.WebsiteRow(
                        5L, "抖音", "新号", "https://example.com", "PUBLISHED"))));

        var result = new WebsiteCompanyContentCutoverReadinessService(profiles, content, company, timeline, social).check();
        assertFalse(result.ready());
        assertTrue(result.blockers().stream().anyMatch(item -> item.contains("当前公开官方链接")));
        assertTrue(result.blockers().stream().anyMatch(item -> item.contains("官网已发布官方链接")));
    }
}
