package com.liqiang.archive.service;

import com.liqiang.archive.entity.CompanyMilestone;
import com.liqiang.archive.entity.WebsiteCompanyMilestone;
import com.liqiang.archive.repository.WebsiteCompanyMilestoneRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WebsiteCompanyMilestoneParityServiceTest {
    @Test
    void comparesVisibleRowsWithDuplicateAwareMatching() {
        var company = mock(CompanyInfoService.class);
        var website = mock(WebsiteCompanyMilestoneRepository.class);
        var first = legacy(1L, true);
        var second = legacy(2L, true);
        var hidden = legacy(3L, false);
        when(company.getMilestones()).thenReturn(List.of(first, second, hidden));
        var published = independent(10L, "PUBLISHED");
        var draft = independent(11L, "DRAFT");
        var extra = independent(12L, "DRAFT");
        extra.setTitle("其他历程");
        when(website.findByDeletedAtIsNullOrderByYearAscSortOrderAscIdAsc())
                .thenReturn(List.of(published, draft, extra));

        var result = new WebsiteCompanyMilestoneParityService(company, website).compare();

        assertEquals(2, result.legacyCount());
        assertEquals(3, result.websiteCount());
        assertEquals(1, result.publishedMatches());
        assertEquals(1, result.draftMatches());
        assertEquals(1, result.unmatchedWebsiteRows().size());
        assertEquals(12L, result.unmatchedWebsiteRows().get(0).websiteId());
    }

    @Test
    void reportsMissingInsteadOfSilentlyCopying() {
        var company = mock(CompanyInfoService.class);
        var website = mock(WebsiteCompanyMilestoneRepository.class);
        when(company.getMilestones()).thenReturn(List.of(legacy(1L, true)));
        when(website.findByDeletedAtIsNullOrderByYearAscSortOrderAscIdAsc()).thenReturn(List.of());
        var result = new WebsiteCompanyMilestoneParityService(company, website).compare();
        assertEquals("MISSING", result.legacyRows().get(0).matchStatus());
    }

    private static CompanyMilestone legacy(Long id, boolean enabled) {
        var item = new CompanyMilestone();
        item.setId(id);
        item.setYear(2020);
        item.setTitle("新厂投产");
        item.setDescription("说明");
        item.setWebsiteEnabled(enabled);
        return item;
    }

    private static WebsiteCompanyMilestone independent(Long id, String status) {
        var item = new WebsiteCompanyMilestone();
        item.setId(id);
        item.setYear(2020);
        item.setTitle("新厂投产");
        item.setDescription("说明");
        item.setStatus(status);
        return item;
    }
}
