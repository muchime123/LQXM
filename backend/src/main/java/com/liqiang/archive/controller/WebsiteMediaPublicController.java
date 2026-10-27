package com.liqiang.archive.controller;

import com.liqiang.archive.entity.WebsiteMediaAsset;
import com.liqiang.archive.repository.WebsiteBannerRepository;
import com.liqiang.archive.repository.WebsiteMediaAssetRepository;
import com.liqiang.archive.security.PublicApi;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Public media access is limited to images referenced by a currently active Banner. */
@RestController
@RequestMapping("/api/website/media")
@PublicApi
@RequiredArgsConstructor
public class WebsiteMediaPublicController {
    private final WebsiteMediaAssetRepository mediaRepository;
    private final WebsiteBannerRepository bannerRepository;

    @Value("${app.files.root}")
    private String fileRoot;

    @GetMapping("/{id}/content")
    public ResponseEntity<Resource> content(@PathVariable Long id) throws IOException {
        WebsiteMediaAsset media = mediaRepository.findByIdAndDeletedAtIsNull(id).orElse(null);
        if (media == null || !"PUBLISHED".equals(media.getStatus()) || !"IMAGE".equals(media.getMediaType())
                || !bannerRepository.existsActiveWebsiteMediaReference(id)) {
            return ResponseEntity.notFound().build();
        }
        Path websiteRoot = Path.of(fileRoot).toAbsolutePath().normalize().resolve("website");
        Path file = Path.of(fileRoot).toAbsolutePath().normalize().resolve(media.getStorageKey()).normalize();
        if (!file.startsWith(websiteRoot) || !Files.isRegularFile(file)
                || !file.toRealPath().startsWith(websiteRoot.toRealPath())) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(media.getMimeType()))
                .contentLength(Files.size(file))
                .cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options", "nosniff")
                .body(new UrlResource(file.toUri()));
    }
}
