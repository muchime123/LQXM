package com.liqiang.archive.controller;

import com.liqiang.archive.entity.WebsiteBanner;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WebsiteBannerPublicViewTest {
    @Test
    void usesWebsiteMediaUrlOnlyAfterBannerIsLinked() {
        WebsiteBanner banner = WebsiteBanner.builder()
                .imageUrl("/api/files/company/legacy.png")
                .build();

        assertEquals("/api/files/company/legacy.png",
                WebsiteBannerController.PublicBannerView.from(banner).imageUrl());

        banner.setWebsiteMediaId(2L);
        assertEquals("/api/website/media/2/content",
                WebsiteBannerController.PublicBannerView.from(banner).imageUrl());
    }
}
