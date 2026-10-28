package com.liqiang.archive.service;

import com.liqiang.archive.entity.WebsiteCompanyProfile;
import com.liqiang.archive.repository.WebsiteCompanyProfileRepository;
import com.liqiang.archive.repository.WebsiteMediaAssetRepository;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WebsiteCompanyProfileServiceTest {
    @Test
    void createsIndependentDraftAndClearsOptionalFields() {
        var profiles = mock(WebsiteCompanyProfileRepository.class);
        var media = mock(WebsiteMediaAssetRepository.class);
        var audit = mock(AuditLogService.class);
        var service = new WebsiteCompanyProfileService(profiles, media, audit);
        when(profiles.findById(1L)).thenReturn(Optional.empty());
        when(profiles.save(any(WebsiteCompanyProfile.class))).thenAnswer(call -> call.getArgument(0));
        var fields = new WebsiteCompanyProfileService.ProfileFields(" 官网公司 ", null, null, null,
                null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null);

        var saved = service.saveDraft(fields);

        assertEquals(1L, saved.getId());
        assertEquals("官网公司", saved.getCompanyName());
        assertEquals("DRAFT", saved.getStatus());
        assertNull(saved.getLogoMediaId());
        verify(media, never()).findByIdAndDeletedAtIsNull(any());
        verify(audit).log("WebsiteCompanyProfile", 1L, "官网公司",
                "WEBSITE_COMPANY_PROFILE_DRAFT_SAVE", "保存官网独立公司介绍草稿");
    }

    @Test
    void publishedProfileCannotBeOverwritten() {
        var profiles = mock(WebsiteCompanyProfileRepository.class);
        var service = new WebsiteCompanyProfileService(profiles, mock(WebsiteMediaAssetRepository.class),
                mock(AuditLogService.class));
        var published = new WebsiteCompanyProfile();
        published.setStatus("PUBLISHED");
        when(profiles.findById(1L)).thenReturn(Optional.of(published));
        var fields = new WebsiteCompanyProfileService.ProfileFields("官网公司", null, null, null,
                null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null);
        assertEquals(HttpStatus.CONFLICT, assertThrows(ResponseStatusException.class,
                () -> service.saveDraft(fields)).getStatusCode());
        verify(profiles, never()).save(any());
    }

    @Test
    void rejectsUnsafePublicWebsiteUrl() {
        var profiles = mock(WebsiteCompanyProfileRepository.class);
        var service = new WebsiteCompanyProfileService(profiles, mock(WebsiteMediaAssetRepository.class),
                mock(AuditLogService.class));
        var fields = new WebsiteCompanyProfileService.ProfileFields("官网公司", null, null, null,
                null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, "javascript:alert(1)", null);
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
                () -> service.saveDraft(fields)).getStatusCode());
        verify(profiles, never()).save(any());
    }
}
