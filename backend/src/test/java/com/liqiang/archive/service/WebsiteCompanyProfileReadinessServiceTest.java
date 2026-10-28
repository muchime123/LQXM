package com.liqiang.archive.service;

import com.liqiang.archive.entity.WebsiteCompanyProfile;
import com.liqiang.archive.entity.WebsiteMediaAsset;
import com.liqiang.archive.repository.WebsiteCompanyProfileRepository;
import com.liqiang.archive.repository.WebsiteMediaAssetRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WebsiteCompanyProfileReadinessServiceTest {
    @TempDir Path files;

    @Test
    void missingProfileBlocksCutover() {
        var profiles = mock(WebsiteCompanyProfileRepository.class);
        var service = new WebsiteCompanyProfileReadinessService(profiles, mock(WebsiteMediaAssetRepository.class));
        assertFalse(service.check().ready());
        assertTrue(service.check().blockers().stream().anyMatch(item -> item.contains("尚无")));
    }

    @Test
    void draftLogoOrInternalPathBlocksButPublishedWebsiteFilePasses() throws Exception {
        var profiles = mock(WebsiteCompanyProfileRepository.class);
        var media = mock(WebsiteMediaAssetRepository.class);
        var service = new WebsiteCompanyProfileReadinessService(profiles, media);
        ReflectionTestUtils.setField(service, "fileRoot", files.toString());
        var profile = new WebsiteCompanyProfile();
        profile.setCompanyName("官网公司");
        profile.setMainBusiness("包装设备");
        profile.setPhone("123456");
        profile.setFactoryAddress("温州");
        profile.setLogoMediaId(5L);
        when(profiles.findById(1L)).thenReturn(Optional.of(profile));
        var logo = new WebsiteMediaAsset();
        logo.setMediaType("IMAGE");
        logo.setStatus("DRAFT");
        logo.setStorageKey("website/images/logo.png");
        when(media.findByIdAndDeletedAtIsNull(5L)).thenReturn(Optional.of(logo));

        assertFalse(service.check().ready());
        logo.setStatus("PUBLISHED");
        logo.setStorageKey("company/logo.png");
        assertFalse(service.check().ready());
        Path file = files.resolve("website/images/logo.png");
        Files.createDirectories(file.getParent());
        Files.write(file, new byte[] {1, 2, 3});
        logo.setStorageKey("website/images/logo.png");
        var result = service.check();
        assertTrue(result.ready());
        assertTrue(result.warnings().stream().anyMatch(item -> item.contains("英文")));
    }
}
