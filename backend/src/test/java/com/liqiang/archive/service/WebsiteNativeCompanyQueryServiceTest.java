package com.liqiang.archive.service;

import com.liqiang.archive.entity.WebsiteCompanyProfile;
import com.liqiang.archive.repository.WebsiteCompanyProfileRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WebsiteNativeCompanyQueryServiceTest {
    @Test
    void onlyPublishedCopyIsVisible() {
        var profiles = mock(WebsiteCompanyProfileRepository.class);
        var service = new WebsiteNativeCompanyQueryService(profiles);
        var copy = new WebsiteCompanyProfile();
        when(profiles.findById(1L)).thenReturn(Optional.of(copy));
        assertNull(service.published());
        copy.setStatus("PUBLISHED");
        assertSame(copy, service.published());
    }
}
