package com.liqiang.archive.service;

import com.liqiang.archive.entity.MediaAccount;
import com.liqiang.archive.entity.WebsiteSocialLink;
import com.liqiang.archive.repository.MediaAccountRepository;
import com.liqiang.archive.repository.WebsiteSocialLinkRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WebsiteSocialLinkParityServiceTest {
    @Test
    void matchesDuplicateAccountsOneByOneAndReportsExtra() {
        var legacy = mock(MediaAccountRepository.class);
        var website = mock(WebsiteSocialLinkRepository.class);
        when(legacy.findByWebsiteOfficialTrueOrderByPlatformAscIdAsc()).thenReturn(List.of(account(1L), account(2L)));
        var published = link(10L, "PUBLISHED");
        var draft = link(11L, "DRAFT");
        var extra = link(12L, "PUBLISHED");
        extra.setAccountName("其他账号");
        when(website.findByDeletedAtIsNullOrderBySortOrderAscIdAsc()).thenReturn(List.of(published, draft, extra));

        var result = new WebsiteSocialLinkParityService(legacy, website).compare();

        assertEquals(2, result.legacyCount());
        assertEquals(1, result.publishedMatches());
        assertEquals(1, result.draftMatches());
        assertEquals(12L, result.unmatchedWebsiteRows().get(0).websiteId());
    }

    @Test
    void missingWebsiteLinkIsReported() {
        var legacy = mock(MediaAccountRepository.class);
        var website = mock(WebsiteSocialLinkRepository.class);
        when(legacy.findByWebsiteOfficialTrueOrderByPlatformAscIdAsc()).thenReturn(List.of(account(1L)));
        when(website.findByDeletedAtIsNullOrderBySortOrderAscIdAsc()).thenReturn(List.of());
        var result = new WebsiteSocialLinkParityService(legacy, website).compare();
        assertEquals("MISSING", result.legacyRows().get(0).matchStatus());
    }

    private static MediaAccount account(Long id) {
        var item = new MediaAccount();
        item.setId(id);
        item.setPlatform("抖音");
        item.setAccountName("官方号");
        item.setLink("https://example.com");
        return item;
    }

    private static WebsiteSocialLink link(Long id, String status) {
        var item = new WebsiteSocialLink();
        item.setId(id);
        item.setPlatform("抖音");
        item.setAccountName("官方号");
        item.setLink("https://example.com");
        item.setStatus(status);
        return item;
    }
}
