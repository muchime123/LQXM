package com.liqiang.archive.service;

import com.liqiang.archive.entity.WebsiteMediaAsset;
import com.liqiang.archive.repository.WebsiteMediaAssetRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WebsiteMediaServiceTest {
    @TempDir
    Path root;

    @Test
    void uploadsAsWebsiteDraftAndReusesIdenticalFile() throws Exception {
        WebsiteMediaAssetRepository repository = mock(WebsiteMediaAssetRepository.class);
        AtomicReference<WebsiteMediaAsset> saved = new AtomicReference<>();
        when(repository.findByStorageKeyAndDeletedAtIsNull(any())).thenAnswer(call -> Optional.ofNullable(saved.get()));
        when(repository.save(any())).thenAnswer(call -> {
            WebsiteMediaAsset asset = call.getArgument(0);
            asset.setId(10L);
            saved.set(asset);
            return asset;
        });
        WebsiteMediaService service = new WebsiteMediaService(repository);
        ReflectionTestUtils.setField(service, "fileRoot", root.toString());
        byte[] png = {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 1};
        MockMultipartFile upload = new MockMultipartFile("file", "internal.png", "image/png", png);

        WebsiteMediaAsset first = service.upload(upload);
        WebsiteMediaAsset second = service.upload(upload);

        assertEquals(first.getId(), second.getId());
        assertEquals("DRAFT", first.getStatus());
        assertTrue(first.getStorageKey().startsWith("website/images/"));
        assertEquals(png.length, Files.size(root.resolve(first.getStorageKey())));
    }

    @Test
    void rejectsUnsupportedBytesDespiteClaimedImageMime() {
        WebsiteMediaService service = new WebsiteMediaService(mock(WebsiteMediaAssetRepository.class));
        ReflectionTestUtils.setField(service, "fileRoot", root.toString());
        MockMultipartFile upload = new MockMultipartFile("file", "script.png", "image/png", "not an image".getBytes());

        assertThrows(IllegalArgumentException.class, () -> service.upload(upload));
    }
}
