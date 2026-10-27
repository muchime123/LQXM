package com.liqiang.archive.controller;

import com.liqiang.archive.entity.WebsiteMediaAsset;
import com.liqiang.archive.repository.WebsiteMediaAssetRepository;
import com.liqiang.archive.security.RequirePermission;
import com.liqiang.archive.service.WebsiteMediaService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** 官网媒体管理接口；草稿预览和上传均须网站管理权限。 */
@RestController
@RequestMapping("/api/website/admin/media")
@RequirePermission("website.manage")
@RequiredArgsConstructor
public class WebsiteMediaAdminController {
    private final WebsiteMediaAssetRepository repository;
    private final WebsiteMediaService mediaService;

    @Value("${app.files.root}")
    private String fileRoot;

    @GetMapping
    public Page<MediaView> list(@RequestParam(defaultValue = "0") int page,
                                @RequestParam(defaultValue = "20") int size) {
        return repository.findByDeletedAtIsNull(PageRequest.of(Math.max(0, page), Math.max(1, Math.min(size, 100)),
                Sort.by(Sort.Direction.DESC, "id"))).map(MediaView::from);
    }

    @GetMapping("/{id}")
    public ResponseEntity<MediaView> get(@PathVariable Long id) {
        return repository.findByIdAndDeletedAtIsNull(id).map(MediaView::from)
                .map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public MediaView upload(@RequestPart("file") MultipartFile file) throws IOException {
        return MediaView.from(mediaService.upload(file));
    }

    @GetMapping("/{id}/content")
    public ResponseEntity<Resource> content(@PathVariable Long id) throws IOException {
        WebsiteMediaAsset asset = repository.findByIdAndDeletedAtIsNull(id).orElse(null);
        if (asset == null) {
            return ResponseEntity.notFound().build();
        }
        Path websiteRoot = Path.of(fileRoot).toAbsolutePath().normalize().resolve("website");
        Path file = Path.of(fileRoot).toAbsolutePath().normalize().resolve(asset.getStorageKey()).normalize();
        if (!file.startsWith(websiteRoot) || !Files.isRegularFile(file)
                || !file.toRealPath().startsWith(websiteRoot.toRealPath())) {
            return ResponseEntity.notFound().build();
        }
        Resource resource = new UrlResource(file.toUri());
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(asset.getMimeType()))
                .contentLength(Files.size(file))
                .header("X-Content-Type-Options", "nosniff")
                .body(resource);
    }

    public record MediaView(Long id, String displayName, String originalFilename, String mediaType,
                            String mimeType, long sizeBytes, String sha256, String status,
                            String altText, String caption) {
        static MediaView from(WebsiteMediaAsset item) {
            return new MediaView(item.getId(), item.getDisplayName(), item.getOriginalFilename(),
                    item.getMediaType(), item.getMimeType(), item.getSizeBytes(), item.getSha256(),
                    item.getStatus(), item.getAltText(), item.getCaption());
        }
    }
}
