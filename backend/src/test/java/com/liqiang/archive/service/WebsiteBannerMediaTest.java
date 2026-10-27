package com.liqiang.archive.service;

import com.liqiang.archive.entity.WebsiteBanner;
import com.liqiang.archive.entity.WebsiteMediaAsset;
import com.liqiang.archive.repository.WebsiteBannerRepository;
import com.liqiang.archive.repository.WebsiteMediaAssetRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WebsiteBannerMediaTest {
    @Test
    void savingActiveBannerPublishesOnlyWebsiteImage() {
        WebsiteBannerRepository banners = mock(WebsiteBannerRepository.class);
        WebsiteMediaAssetRepository media = mock(WebsiteMediaAssetRepository.class);
        WebsiteBannerService service = new WebsiteBannerService(banners, media, mock(AuditLogService.class));
        WebsiteMediaAsset image = new WebsiteMediaAsset();
        image.setMediaType("IMAGE");
        image.setStatus("DRAFT");
        when(media.findByIdAndDeletedAtIsNull(7L)).thenReturn(Optional.of(image));
        when(banners.save(any())).thenAnswer(call -> {
            WebsiteBanner saved = call.getArgument(0);
            saved.setId(1L);
            return saved;
        });
        WebsiteBanner banner = new WebsiteBanner();
        banner.setWebsiteMediaId(7L);
        banner.setPosition(WebsiteBanner.BannerPosition.HERO);
        banner.setTitle("Banner");

        service.create(banner);
        assertEquals("PUBLISHED", image.getStatus());

        image.setStatus("ARCHIVED");
        assertThrows(IllegalArgumentException.class, () -> service.create(banner));
    }
}
