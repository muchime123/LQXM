package com.liqiang.archive.controller;

import com.liqiang.archive.entity.WebsiteMediaAsset;
import com.liqiang.archive.repository.WebsiteBannerRepository;
import com.liqiang.archive.repository.WebsiteMediaAssetRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class WebsiteMediaPublicControllerTest {
    @TempDir
    Path root;

    @Test
    void onlyActiveBannerMayExposePublishedWebsiteImage() throws Exception {
        WebsiteMediaAssetRepository mediaRepository = mock(WebsiteMediaAssetRepository.class);
        WebsiteBannerRepository bannerRepository = mock(WebsiteBannerRepository.class);
        WebsiteMediaPublicController controller = new WebsiteMediaPublicController(mediaRepository, bannerRepository);
        ReflectionTestUtils.setField(controller, "fileRoot", root.toString());
        Path image = root.resolve("website/images/banner.png");
        Files.createDirectories(image.getParent());
        Files.write(image, new byte[]{1, 2, 3});
        WebsiteMediaAsset media = new WebsiteMediaAsset();
        media.setStorageKey("website/images/banner.png");
        media.setMimeType("image/png");
        media.setMediaType("IMAGE");
        media.setStatus("DRAFT");
        when(mediaRepository.findByIdAndDeletedAtIsNull(1L)).thenReturn(Optional.of(media));
        when(bannerRepository.existsActiveWebsiteMediaReference(1L)).thenReturn(true);

        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();
        mvc.perform(get("/api/website/media/1/content")).andExpect(status().isNotFound());
        media.setStatus("PUBLISHED");
        mvc.perform(get("/api/website/media/1/content"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("image/png"))
                .andExpect(content().bytes(new byte[]{1, 2, 3}))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));
        when(bannerRepository.existsActiveWebsiteMediaReference(1L)).thenReturn(false);
        mvc.perform(get("/api/website/media/1/content")).andExpect(status().isNotFound());
    }
}
