package com.liqiang.archive.controller;

import com.liqiang.archive.entity.WebsiteMediaAsset;
import com.liqiang.archive.repository.WebsiteMediaAssetRepository;
import com.liqiang.archive.service.WebsiteMediaService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.any;

class WebsiteMediaAdminControllerTest {
    @TempDir
    Path root;

    @Test
    void servesOnlyWebsiteMediaFiles() throws Exception {
        WebsiteMediaAssetRepository repository = mock(WebsiteMediaAssetRepository.class);
        WebsiteMediaAdminController controller = new WebsiteMediaAdminController(repository, mock(WebsiteMediaService.class));
        ReflectionTestUtils.setField(controller, "fileRoot", root.toString());
        Path image = root.resolve("website/images/sample.png");
        Files.createDirectories(image.getParent());
        Files.write(image, new byte[]{1, 2, 3});
        WebsiteMediaAsset asset = new WebsiteMediaAsset();
        asset.setStorageKey("website/images/sample.png");
        asset.setMimeType("image/png");
        asset.setSizeBytes(3L);
        when(repository.findByIdAndDeletedAtIsNull(1L)).thenReturn(Optional.of(asset));

        assertNotNull(controller.content(1L).getBody());
        asset.setStorageKey("../outside.png");
        assertEquals(404, controller.content(1L).getStatusCode().value());
    }

    @Test
    void imagePickerUsesFilteredPageCount() {
        WebsiteMediaAssetRepository repository = mock(WebsiteMediaAssetRepository.class);
        WebsiteMediaAdminController controller = new WebsiteMediaAdminController(
                repository, mock(WebsiteMediaService.class));
        WebsiteMediaAsset image = new WebsiteMediaAsset();
        image.setId(3L);
        image.setMediaType("IMAGE");
        image.setSizeBytes(3L);
        when(repository.findByDeletedAtIsNullAndMediaTypeAndStatusNot(
                org.mockito.ArgumentMatchers.eq("IMAGE"), org.mockito.ArgumentMatchers.eq("ARCHIVED"),
                any(Pageable.class))).thenReturn(new PageImpl<>(List.of(image)));

        assertEquals(1, controller.list(0, 20, true).getTotalElements());
        verify(repository).findByDeletedAtIsNullAndMediaTypeAndStatusNot(
                org.mockito.ArgumentMatchers.eq("IMAGE"), org.mockito.ArgumentMatchers.eq("ARCHIVED"),
                any(Pageable.class));
    }
}
