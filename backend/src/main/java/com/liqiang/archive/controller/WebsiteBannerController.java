package com.liqiang.archive.controller;

import com.liqiang.archive.entity.WebsiteBanner;
import com.liqiang.archive.entity.WebsiteBanner.BannerPosition;
import com.liqiang.archive.service.WebsiteBannerService;
import com.liqiang.archive.security.PublicApi;
import com.liqiang.archive.security.RequirePermission;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 官网Banner管理接口。
 * - 公开接口: GET /api/website/banners/public
 * - 管理接口: /api/admin/website/banners/*
 */
@RestController
@RequiredArgsConstructor
public class WebsiteBannerController {

    private final WebsiteBannerService bannerService;

    /**
     * 公开接口: 获取所有官网可见的Banner（按位置分组）。
     */
    @PublicApi
    @GetMapping("/api/website/banners/public")
    public Map<String, List<PublicBannerView>> publicBanners() {
        return bannerService.getPublicBanners().entrySet().stream()
                .collect(Collectors.toMap(
                        e -> e.getKey().name(),
                        e -> e.getValue().stream().map(PublicBannerView::from).toList()
                ));
    }

    /**
     * 公开接口: 获取指定位置的Banner列表。
     */
    @PublicApi
    @GetMapping("/api/website/banners/public/{position}")
    public List<PublicBannerView> publicBannersByPosition(@PathVariable BannerPosition position) {
        return bannerService.getPublicBannersByPosition(position).stream()
                .map(PublicBannerView::from)
                .toList();
    }

    /**
     * 管理接口: 获取Banner列表（支持筛选）。
     */
    @RequirePermission("website.manage")
    @GetMapping("/api/admin/website/banners")
    public Page<BannerView> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) BannerPosition position,
            @RequestParam(required = false) Boolean websiteEnabled,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<WebsiteBanner> result = bannerService.list(keyword, position, websiteEnabled, page, size);
        return result.map(BannerView::from);
    }

    /**
     * 管理接口: 获取Banner详情。
     */
    @RequirePermission("website.manage")
    @GetMapping("/api/admin/website/banners/{id}")
    public ResponseEntity<BannerDetailView> getById(@PathVariable Long id) {
        WebsiteBanner banner = bannerService.getById(id);
        return ResponseEntity.ok(BannerDetailView.from(banner));
    }

    /**
     * 管理接口: 创建Banner。
     */
    @RequirePermission("website.manage")
    @PostMapping("/api/admin/website/banners")
    public ResponseEntity<BannerDetailView> create(@RequestBody BannerForm form) {
        WebsiteBanner banner = WebsiteBanner.builder()
                .position(form.position())
                .title(form.title())
                .subtitle(form.subtitle())
                .description(form.description())
                .imageUrl(form.imageUrl())
                .imageAssetId(form.imageAssetId())
                .websiteMediaId(form.websiteMediaId())
                .buttonText(form.buttonText())
                .buttonUrl(form.buttonUrl())
                .secondaryButtonText(form.secondaryButtonText())
                .secondaryButtonUrl(form.secondaryButtonUrl())
                .badge(form.badge())
                .sortOrder(form.sortOrder() != null ? form.sortOrder() : 0)
                .websiteEnabled(form.websiteEnabled() != null ? form.websiteEnabled() : true)
                .startDate(form.startDate())
                .endDate(form.endDate())
                .build();
        WebsiteBanner saved = bannerService.create(banner);
        return ResponseEntity.ok(BannerDetailView.from(saved));
    }

    /**
     * 管理接口: 更新Banner。
     */
    @RequirePermission("website.manage")
    @PutMapping("/api/admin/website/banners/{id}")
    public ResponseEntity<BannerDetailView> update(
            @PathVariable Long id,
            @RequestBody BannerForm form) {
        WebsiteBanner banner = WebsiteBanner.builder()
                .position(form.position())
                .title(form.title())
                .subtitle(form.subtitle())
                .description(form.description())
                .imageUrl(form.imageUrl())
                .imageAssetId(form.imageAssetId())
                .websiteMediaId(form.websiteMediaId())
                .buttonText(form.buttonText())
                .buttonUrl(form.buttonUrl())
                .secondaryButtonText(form.secondaryButtonText())
                .secondaryButtonUrl(form.secondaryButtonUrl())
                .badge(form.badge())
                .sortOrder(form.sortOrder())
                .websiteEnabled(form.websiteEnabled())
                .startDate(form.startDate())
                .endDate(form.endDate())
                .build();
        WebsiteBanner updated = bannerService.update(id, banner);
        return ResponseEntity.ok(BannerDetailView.from(updated));
    }

    /**
     * 管理接口: 删除Banner（软删除）。
     */
    @RequirePermission("website.manage")
    @DeleteMapping("/api/admin/website/banners/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        bannerService.softDelete(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * 管理接口: 恢复已删除的Banner。
     */
    @RequirePermission("website.manage")
    @PostMapping("/api/admin/website/banners/{id}/restore")
    public ResponseEntity<BannerDetailView> restore(@PathVariable Long id) {
        WebsiteBanner restored = bannerService.restore(id);
        return ResponseEntity.ok(BannerDetailView.from(restored));
    }

    /**
     * 管理接口: 切换官网可见状态。
     */
    @RequirePermission("website.manage")
    @PostMapping("/api/admin/website/banners/{id}/toggle-enabled")
    public ResponseEntity<BannerDetailView> toggleEnabled(@PathVariable Long id) {
        WebsiteBanner updated = bannerService.toggleWebsiteEnabled(id);
        return ResponseEntity.ok(BannerDetailView.from(updated));
    }

    /**
     * 管理接口: 批量更新排序。
     */
    @RequirePermission("website.manage")
    @PutMapping("/api/admin/website/banners/batch-sort")
    public ResponseEntity<Void> batchUpdateSortOrder(
            @RequestBody Map<Long, Integer> sortOrders) {
        bannerService.batchUpdateSortOrder(sortOrders);
        return ResponseEntity.ok().build();
    }

    /**
     * Banner表单（用于创建/更新）。
     */
    public record BannerForm(
            BannerPosition position,
            String title,
            String subtitle,
            String description,
            String imageUrl,
            Long imageAssetId,
            Long websiteMediaId,
            String buttonText,
            String buttonUrl,
            String secondaryButtonText,
            String secondaryButtonUrl,
            String badge,
            Integer sortOrder,
            Boolean websiteEnabled,
            java.time.LocalDate startDate,
            java.time.LocalDate endDate
    ) {}

    /**
     * 公开Banner视图（脱敏）。
     */
    public record PublicBannerView(
            Long id,
            String title,
            String subtitle,
            String description,
            String imageUrl,
            String buttonText,
            String buttonUrl,
            String secondaryButtonText,
            String secondaryButtonUrl,
            String badge,
            Integer sortOrder,
            BannerPosition position
    ) {
        static PublicBannerView from(WebsiteBanner banner) {
            return new PublicBannerView(
                    banner.getId(),
                    banner.getTitle(),
                    banner.getSubtitle(),
                    banner.getDescription(),
                    banner.getWebsiteMediaId() != null
                            ? "/api/website/media/" + banner.getWebsiteMediaId() + "/content"
                            : banner.getImageUrl(),
                    banner.getButtonText(),
                    banner.getButtonUrl(),
                    banner.getSecondaryButtonText(),
                    banner.getSecondaryButtonUrl(),
                    banner.getBadge(),
                    banner.getSortOrder(),
                    banner.getPosition()
            );
        }
    }

    /**
     * Banner列表视图。
     */
    public record BannerView(
            Long id,
            BannerPosition position,
            String title,
            String subtitle,
            Integer sortOrder,
            Boolean websiteEnabled,
            Boolean deleted,
            java.time.LocalDate startDate,
            java.time.LocalDate endDate,
            java.time.LocalDateTime updatedAt
    ) {
        static BannerView from(WebsiteBanner banner) {
            return new BannerView(
                    banner.getId(),
                    banner.getPosition(),
                    banner.getTitle(),
                    banner.getSubtitle(),
                    banner.getSortOrder(),
                    banner.getWebsiteEnabled(),
                    banner.getDeletedAt() != null,
                    banner.getStartDate(),
                    banner.getEndDate(),
                    banner.getUpdatedAt()
            );
        }
    }

    /**
     * Banner详情视图。
     */
    public record BannerDetailView(
            Long id,
            BannerPosition position,
            String title,
            String subtitle,
            String description,
            String imageUrl,
            Long imageAssetId,
            Long websiteMediaId,
            String buttonText,
            String buttonUrl,
            String secondaryButtonText,
            String secondaryButtonUrl,
            String badge,
            Integer sortOrder,
            Boolean websiteEnabled,
            Boolean deleted,
            java.time.LocalDate startDate,
            java.time.LocalDate endDate,
            java.time.LocalDateTime createdAt,
            java.time.LocalDateTime updatedAt
    ) {
        static BannerDetailView from(WebsiteBanner banner) {
            return new BannerDetailView(
                    banner.getId(),
                    banner.getPosition(),
                    banner.getTitle(),
                    banner.getSubtitle(),
                    banner.getDescription(),
                    banner.getImageUrl(),
                    banner.getImageAssetId(),
                    banner.getWebsiteMediaId(),
                    banner.getButtonText(),
                    banner.getButtonUrl(),
                    banner.getSecondaryButtonText(),
                    banner.getSecondaryButtonUrl(),
                    banner.getBadge(),
                    banner.getSortOrder(),
                    banner.getWebsiteEnabled(),
                    banner.getDeletedAt() != null,
                    banner.getStartDate(),
                    banner.getEndDate(),
                    banner.getCreatedAt(),
                    banner.getUpdatedAt()
            );
        }
    }
}
