package com.liqiang.archive.service;

import com.liqiang.archive.entity.WebsiteBanner;
import com.liqiang.archive.entity.WebsiteBanner.BannerPosition;
import com.liqiang.archive.repository.WebsiteBannerRepository;
import com.liqiang.archive.repository.WebsiteMediaAssetRepository;
import com.liqiang.archive.entity.WebsiteMediaAsset;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class WebsiteBannerService {

    private final WebsiteBannerRepository bannerRepository;
    private final WebsiteMediaAssetRepository mediaRepository;
    private final AuditLogService auditLogService;

    /**
     * 获取所有Banner列表（管理端，支持筛选）。
     */
    public Page<WebsiteBanner> list(
            String keyword,
            BannerPosition position,
            Boolean websiteEnabled,
            int page,
            int size) {
        PageRequest pageable = PageRequest.of(page, size,
                Sort.by("position").ascending()
                        .and(Sort.by("sortOrder").ascending())
                        .and(Sort.by("id").descending()));
        /* 先全量内存过滤（Banner 数据量极小），再手动分页，保证 total 与筛选条件一致。 */
        List<WebsiteBanner> all = bannerRepository.findByDeletedAtIsNullOrderByPositionAscSortOrderAscIdAsc();
        List<WebsiteBanner> filtered = all.stream()
                .filter(banner -> {
                    if (keyword != null && !keyword.isBlank()) {
                        String kw = keyword.toLowerCase();
                        boolean matchesTitle = banner.getTitle() != null && banner.getTitle().toLowerCase().contains(kw);
                        boolean matchesSubtitle = banner.getSubtitle() != null && banner.getSubtitle().toLowerCase().contains(kw);
                        if (!matchesTitle && !matchesSubtitle) return false;
                    }
                    if (position != null && !position.equals(banner.getPosition())) return false;
                    if (websiteEnabled != null && !websiteEnabled.equals(banner.getWebsiteEnabled())) return false;
                    return true;
                })
                /* 内存按 pageable 的排序重新排，确保分页前顺序一致。 */
                .sorted((a, b) -> {
                    int c1 = a.getPosition().compareTo(b.getPosition());
                    if (c1 != 0) return c1;
                    int sa = a.getSortOrder() == null ? 0 : a.getSortOrder();
                    int sb = b.getSortOrder() == null ? 0 : b.getSortOrder();
                    int c2 = Integer.compare(sa, sb);
                    if (c2 != 0) return c2;
                    return Long.compare(b.getId(), a.getId());
                })
                .toList();
        long total = filtered.size();
        int fromIdx = Math.min((int) pageable.getOffset(), filtered.size());
        int toIdx = Math.min(fromIdx + pageable.getPageSize(), filtered.size());
        List<WebsiteBanner> paged = fromIdx < toIdx ? filtered.subList(fromIdx, toIdx) : List.of();
        return new PageImpl<>(paged, pageable, total);
    }

    /**
     * 获取官网可见的Banner（按位置分组，仅返回当前生效的）。
     */
    public Map<BannerPosition, List<WebsiteBanner>> getPublicBanners() {
        return bannerRepository.findAllActive().stream()
                .collect(Collectors.groupingBy(WebsiteBanner::getPosition));
    }

    /**
     * 获取指定位置的官网可见Banner。
     */
    public List<WebsiteBanner> getPublicBannersByPosition(BannerPosition position) {
        return bannerRepository.findActiveByPosition(position);
    }

    /**
     * 按ID获取Banner。
     */
    public WebsiteBanner getById(Long id) {
        return bannerRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Banner not found: " + id));
    }

    /**
     * 创建Banner。
     */
    @Transactional
    public WebsiteBanner create(WebsiteBanner banner) {
        if (banner.getSortOrder() == null) {
            banner.setSortOrder(0);
        }
        if (banner.getWebsiteEnabled() == null) {
            banner.setWebsiteEnabled(true);
        }
        validateAndPublishMedia(banner);
        WebsiteBanner saved = bannerRepository.save(banner);
        auditLogService.log("WebsiteBanner", saved.getId(), saved.getTitle(), "BANNER_CREATE",
                String.format("创建Banner position=%s", saved.getPosition()));
        log.info("Created banner id={}, position={}, title={}", saved.getId(), saved.getPosition(), saved.getTitle());
        return saved;
    }

    /**
     * 更新Banner。
     */
    @Transactional
    public WebsiteBanner update(Long id, WebsiteBanner banner) {
        WebsiteBanner existing = getById(id);
        validateAndPublishMedia(banner);
        Map<String, Object> before = bannerSnapshot(existing);
        existing.setPosition(banner.getPosition());
        existing.setTitle(banner.getTitle());
        existing.setSubtitle(banner.getSubtitle());
        existing.setDescription(banner.getDescription());
        existing.setImageUrl(banner.getImageUrl());
        existing.setImageAssetId(banner.getImageAssetId());
        existing.setWebsiteMediaId(banner.getWebsiteMediaId());
        existing.setButtonText(banner.getButtonText());
        existing.setButtonUrl(banner.getButtonUrl());
        existing.setSecondaryButtonText(banner.getSecondaryButtonText());
        existing.setSecondaryButtonUrl(banner.getSecondaryButtonUrl());
        existing.setBadge(banner.getBadge());
        existing.setSortOrder(banner.getSortOrder() != null ? banner.getSortOrder() : 0);
        existing.setWebsiteEnabled(banner.getWebsiteEnabled() != null ? banner.getWebsiteEnabled() : true);
        existing.setStartDate(banner.getStartDate());
        existing.setEndDate(banner.getEndDate());
        WebsiteBanner saved = bannerRepository.save(existing);
        Map<String, Object> after = bannerSnapshot(saved);
        String changes = buildBannerChanges(before, after);
        auditLogService.log("WebsiteBanner", saved.getId(), saved.getTitle(), "BANNER_UPDATE", changes.isEmpty() ? "更新Banner" : changes);
        log.info("Updated banner id={}, position={}", id, saved.getPosition());
        return saved;
    }

    /**
     * 软删除Banner。
     */
    @Transactional
    public void softDelete(Long id) {
        WebsiteBanner banner = getById(id);
        banner.setDeletedAt(LocalDateTime.now());
        WebsiteBanner saved = bannerRepository.save(banner);
        auditLogService.log("WebsiteBanner", id, banner.getTitle(), "BANNER_DELETE",
                String.format("软删除Banner position=%s", banner.getPosition()));
        log.info("Soft-deleted banner id={}, position={}", id, saved.getPosition());
    }

    /**
     * 恢复已删除的Banner。
     */
    @Transactional
    public WebsiteBanner restore(Long id) {
        WebsiteBanner banner = getById(id);
        validateAndPublishMedia(banner);
        banner.setDeletedAt(null);
        WebsiteBanner saved = bannerRepository.save(banner);
        auditLogService.log("WebsiteBanner", id, banner.getTitle(), "BANNER_RESTORE",
                String.format("恢复Banner position=%s", banner.getPosition()));
        log.info("Restored banner id={}, position={}", id, saved.getPosition());
        return saved;
    }

    /**
     * 切换官网可见状态。
     */
    @Transactional
    public WebsiteBanner toggleWebsiteEnabled(Long id) {
        WebsiteBanner banner = getById(id);
        banner.setWebsiteEnabled(!Boolean.TRUE.equals(banner.getWebsiteEnabled()));
        validateAndPublishMedia(banner);
        WebsiteBanner saved = bannerRepository.save(banner);
        auditLogService.log("WebsiteBanner", id, banner.getTitle(), "BANNER_TOGGLE",
                String.format("切换Banner可见性 websiteEnabled=%s", saved.getWebsiteEnabled()));
        log.info("Toggled banner websiteEnabled id={}, enabled={}", id, saved.getWebsiteEnabled());
        return saved;
    }

    /**
     * 批量更新排序。
     */
    @Transactional
    public void batchUpdateSortOrder(Map<Long, Integer> sortOrders) {
        sortOrders.forEach((id, order) -> {
            WebsiteBanner banner = getById(id);
            banner.setSortOrder(order);
            bannerRepository.save(banner);
        });
        if (!sortOrders.isEmpty()) {
            auditLogService.log("WebsiteBanner", null, "批量排序", "BANNER_BATCH_SORT",
                    String.format("批量更新排序，共 %d 条", sortOrders.size()));
            log.info("Batch updated {} banner sort orders", sortOrders.size());
        }
    }

    /* -------- Banner 审计辅助 -------- */

    private void validateAndPublishMedia(WebsiteBanner banner) {
        if (banner.getWebsiteMediaId() == null) return;
        WebsiteMediaAsset media = mediaRepository.findByIdAndDeletedAtIsNull(banner.getWebsiteMediaId())
                .orElseThrow(() -> new IllegalArgumentException("官网媒体不存在或已删除"));
        if (!"IMAGE".equals(media.getMediaType()) || "ARCHIVED".equals(media.getStatus())) {
            throw new IllegalArgumentException("Banner 只能使用未归档的官网图片");
        }
        if (Boolean.TRUE.equals(banner.getWebsiteEnabled()) && "DRAFT".equals(media.getStatus())) {
            media.setStatus("PUBLISHED");
            mediaRepository.save(media);
        }
    }

    private static java.util.Map<String, Object> bannerSnapshot(WebsiteBanner b) {
        java.util.LinkedHashMap<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("position", b.getPosition());
        m.put("title", b.getTitle());
        m.put("subtitle", b.getSubtitle());
        m.put("imageUrl", b.getImageUrl());
        m.put("websiteMediaId", b.getWebsiteMediaId());
        m.put("badge", b.getBadge());
        m.put("buttonText", b.getButtonText());
        m.put("buttonUrl", b.getButtonUrl());
        m.put("secondaryButtonText", b.getSecondaryButtonText());
        m.put("secondaryButtonUrl", b.getSecondaryButtonUrl());
        m.put("sortOrder", b.getSortOrder());
        m.put("websiteEnabled", b.getWebsiteEnabled());
        m.put("startDate", b.getStartDate());
        m.put("endDate", b.getEndDate());
        return m;
    }

    private static String buildBannerChanges(java.util.Map<String, Object> before, java.util.Map<String, Object> after) {
        java.util.StringJoiner sj = new java.util.StringJoiner("; ");
        for (java.util.Map.Entry<String, Object> entry : after.entrySet()) {
            String key = entry.getKey();
            Object oldVal = before.get(key);
            Object newVal = entry.getValue();
            if (java.util.Objects.equals(oldVal, newVal)) continue;
            String oldStr = oldVal == null ? "空" : truncate(String.valueOf(oldVal));
            String newStr = newVal == null ? "空" : truncate(String.valueOf(newVal));
            sj.add(key + ": " + oldStr + " → " + newStr);
        }
        return sj.toString();
    }

    private static String truncate(String s) {
        if (s.length() <= 120) return s;
        return s.substring(0, 120) + "...（共 " + s.length() + " 字符）";
    }
}
