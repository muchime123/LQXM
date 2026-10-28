package com.liqiang.archive.service;

import com.liqiang.archive.entity.CompanyInfo;
import com.liqiang.archive.entity.WebsiteCompanyProfile;
import com.liqiang.archive.repository.WebsiteCompanyProfileRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WebsiteCompanyProfileParityServiceTest {
    @Test
    void comparesOnlyPublicFieldsWithoutCopying() {
        var company = mock(CompanyInfoService.class);
        var profiles = mock(WebsiteCompanyProfileRepository.class);
        var legacy = new CompanyInfo();
        legacy.setCompanyName("旧名称");
        legacy.setPhone("123");
        var draft = new WebsiteCompanyProfile();
        draft.setCompanyName("新名称");
        draft.setPhone("123");
        when(company.getCompanyInfo()).thenReturn(legacy);
        when(profiles.findById(1L)).thenReturn(Optional.of(draft));

        var result = new WebsiteCompanyProfileParityService(company, profiles).compare();

        assertTrue(result.legacyExists());
        assertTrue(result.draftExists());
        assertEquals(20, result.fields().size());
        assertEquals(1, result.differentCount());
        assertTrue(result.fields().stream().anyMatch(f -> f.key().equals("companyName") && f.different()));
        assertFalse(result.fields().stream().filter(f -> f.key().equals("phone")).findFirst().orElseThrow().different());
        assertEquals("旧名称", legacy.getCompanyName());
        assertEquals("新名称", draft.getCompanyName());
    }
}
