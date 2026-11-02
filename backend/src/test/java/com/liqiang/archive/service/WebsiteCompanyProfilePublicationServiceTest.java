package com.liqiang.archive.service;

import com.liqiang.archive.entity.WebsiteCompanyProfile;
import com.liqiang.archive.entity.WebsiteMediaAsset;
import com.liqiang.archive.repository.WebsiteCompanyProfileRepository;
import com.liqiang.archive.repository.WebsiteMediaAssetRepository;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WebsiteCompanyProfilePublicationServiceTest {
    @Test
    void blocksIncompleteDraftWithoutWriting() {
        var profiles = mock(WebsiteCompanyProfileRepository.class);
        var readiness = mock(WebsiteCompanyProfileReadinessService.class);
        var media = mock(WebsiteMediaAssetRepository.class);
        var audit = mock(AuditLogService.class);
        var profile = new WebsiteCompanyProfile();
        when(profiles.findById(1L)).thenReturn(Optional.of(profile));
        when(readiness.check()).thenReturn(new WebsiteCompanyProfileReadinessService.ReadinessView(
                false, List.of("缺少 Logo"), List.of()));
        var service = new WebsiteCompanyProfilePublicationService(profiles, readiness, media, audit);

        assertEquals(HttpStatus.CONFLICT, assertThrows(ResponseStatusException.class,
                service::publish).getStatusCode());
        verify(profiles, never()).save(any());
        verify(media, never()).save(any());
        verify(audit, never()).log(any(), any(), any(), any(), any());
    }

    @Test
    void publishesReadyDraftAndAuditsOnce() {
        var profiles = mock(WebsiteCompanyProfileRepository.class);
        var readiness = mock(WebsiteCompanyProfileReadinessService.class);
        var media = mock(WebsiteMediaAssetRepository.class);
        var audit = mock(AuditLogService.class);
        var profile = new WebsiteCompanyProfile();
        profile.setId(1L);
        profile.setCompanyName("官网公司");
        profile.setLogoMediaId(5L);
        var logo = new WebsiteMediaAsset();
        logo.setMediaType("IMAGE");
        logo.setStatus("DRAFT");
        when(profiles.findById(1L)).thenReturn(Optional.of(profile));
        when(profiles.save(profile)).thenReturn(profile);
        when(media.findByIdAndDeletedAtIsNull(5L)).thenReturn(Optional.of(logo));
        when(readiness.check()).thenReturn(new WebsiteCompanyProfileReadinessService.ReadinessView(
                true, List.of(), List.of()));
        var service = new WebsiteCompanyProfilePublicationService(profiles, readiness, media, audit);

        service.publish();
        service.publish();

        assertEquals("PUBLISHED", profile.getStatus());
        assertEquals("PUBLISHED", logo.getStatus());
        assertNotNull(profile.getPublishedAt());
        verify(media).save(logo);
        verify(profiles).save(profile);
        verify(audit).log("WebsiteCompanyProfile", 1L, "官网公司",
                "WEBSITE_COMPANY_PROFILE_PUBLISH", "发布官网独立公司介绍");
    }
}
