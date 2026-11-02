package com.liqiang.archive.controller;

import com.liqiang.archive.entity.WebsiteMediaAsset;
import com.liqiang.archive.repository.WebsiteBannerRepository;
import com.liqiang.archive.repository.WebsiteCompanyProfileRepository;
import com.liqiang.archive.repository.WebsiteMediaAssetRepository;
import com.liqiang.archive.repository.WebsiteSolutionRepository;
import com.liqiang.archive.repository.WebsitePackagingItemRepository;
import com.liqiang.archive.repository.WebsiteCredentialRepository;
import com.liqiang.archive.repository.WebsiteIpItemRepository;
import com.liqiang.archive.repository.WebsitePageSectionRepository;
import com.liqiang.archive.service.WebsiteProductMediaVisibilityService;
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

/** Public media access requires an active Banner or published website content reference. */
@RestController
@RequestMapping("/api/website/media")
@PublicApi
@RequiredArgsConstructor
public class WebsiteMediaPublicController {
    private final WebsiteMediaAssetRepository mediaRepository;
    private final WebsiteBannerRepository bannerRepository;
    private final WebsiteProductMediaVisibilityService productMediaVisibility;
    private final WebsiteCompanyProfileRepository companyProfiles;
    private final WebsiteSolutionRepository solutions;
    private final WebsitePackagingItemRepository packagingItems;
    private final WebsiteCredentialRepository credentials;
    private final WebsiteIpItemRepository ipItems;
    private final WebsitePageSectionRepository pageSections;

    @Value("${app.website.credentials-native-enabled:false}")
    private boolean nativeCredentialsEnabled;

    @Value("${app.website.ip-native-enabled:false}")
    private boolean nativeIpEnabled;

    @Value("${app.files.root}")
    private String fileRoot;

    @GetMapping("/{id}/content")
    public ResponseEntity<Resource> content(@PathVariable Long id) throws IOException {
        WebsiteMediaAsset media = mediaRepository.findByIdAndDeletedAtIsNull(id).orElse(null);
        if (media == null || !"PUBLISHED".equals(media.getStatus()) || !"IMAGE".equals(media.getMediaType())) {
            return ResponseEntity.notFound().build();
        }
        String mediaUrl = "/api/website/media/" + id + "/content";
        if (!bannerRepository.existsActiveWebsiteMediaReference(id)
                && !productMediaVisibility.isReferencedByPublishedProduct(id)
                && !companyProfiles.existsByLogoMediaIdAndStatus(id, "PUBLISHED")
                && !packagingItems.existsByCoverMediaIdAndStatusAndDeletedAtIsNull(id, "PUBLISHED")
                && !(nativeCredentialsEnabled && (
                    credentials.existsByMediaIdAndStatusAndDeletedAtIsNullAndValidUntilGreaterThanEqual(id, "PUBLISHED", java.time.LocalDate.now())
                    || credentials.existsByMediaIdAndStatusAndDeletedAtIsNullAndValidUntilIsNull(id, "PUBLISHED")))
                && !(nativeIpEnabled && ipItems.existsByMediaIdAndStatusAndDeletedAtIsNull(id, "PUBLISHED"))
                && !pageSections.existsByPageKeyNotAndEnabledTrueAndDeletedAtIsNullAndImageUrl("MOLDS", mediaUrl)
                && !pageSections.existsByPageKeyNotAndEnabledTrueAndDeletedAtIsNullAndContentContaining("MOLDS", mediaUrl)
                && !solutions.existsByWebsiteEnabledTrueAndCoverImageUrl(mediaUrl)
                && !solutions.existsByWebsiteEnabledTrueAndContentHtmlContaining(mediaUrl)
                && !solutions.existsByWebsiteEnabledTrueAndContentSectionsContaining(mediaUrl)) {
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
