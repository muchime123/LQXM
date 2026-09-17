package com.liqiang.archive.service;

import com.liqiang.archive.dto.PageSectionDtos.PageSectionPayload;
import com.liqiang.archive.dto.PageSectionDtos.PageSectionView;
import com.liqiang.archive.dto.PageSectionDtos.PublicPageSectionView;
import com.liqiang.archive.entity.WebsitePageSection;
import com.liqiang.archive.repository.WebsitePageSectionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class WebsitePageSectionService {

    /** 允许的页面标识。 */
    private static final Set<String> VALID_PAGE_KEYS = Set.of("HOME", "PRODUCTS", "SOLUTIONS", "ABOUT", "CONTACT", "PACKAGING", "MOLDS");

    private static final Sort DEFAULT_SORT = Sort.by(
            Sort.Order.asc("pageKey"),
            Sort.Order.asc("sortOrder"),
            Sort.Order.asc("id")
    );

    private final WebsitePageSectionRepository sectionRepo;
    private final AuditLogService auditLogService;

    @Transactional(readOnly = true)
    public List<PageSectionView> listAll() {
        /* 管理端列表：仅展示未删除的区块。 */
        return sectionRepo.findByDeletedAtIsNullOrderByPageKeyAscSortOrderAscIdAsc().stream()
                .map(PageSectionView::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<PageSectionView> listByPage(String pageKey) {
        return sectionRepo.findByDeletedAtIsNullOrderByPageKeyAscSortOrderAscIdAsc().stream()
                .filter(s -> pageKey == null || pageKey.isBlank() || s.getPageKey().equalsIgnoreCase(pageKey))
                .map(PageSectionView::from)
                .toList();
    }

    /** 官网公开区块：某页面下启用且未删除、排序后的区块列表。 */
    @Transactional(readOnly = true)
    public List<PublicPageSectionView> listPublic(String pageKey) {
        return sectionRepo.findByPageKeyAndEnabledTrueAndDeletedAtIsNullOrderBySortOrderAscIdAsc(pageKey).stream()
                .map(PublicPageSectionView::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public PageSectionView getById(Long id) {
        WebsitePageSection entity = sectionRepo.findById(id).orElseThrow(() -> new IllegalArgumentException("页面区块不存在: " + id));
        if (entity.getDeletedAt() != null) {
            throw new IllegalArgumentException("页面区块不存在: " + id);
        }
        return PageSectionView.from(entity);
    }

    @Transactional(readOnly = true)
    public WebsitePageSection getEntityById(Long id) {
        WebsitePageSection entity = sectionRepo.findById(id).orElseThrow(() -> new IllegalArgumentException("页面区块不存在: " + id));
        if (entity.getDeletedAt() != null) throw new IllegalArgumentException("页面区块不存在: " + id);
        return entity;
    }

    @Transactional
    public PageSectionView create(PageSectionPayload payload) {
        validatePayload(payload);
        String pageKey = payload.pageKey().toUpperCase();
        String sectionKey = payload.sectionKey().trim();
        if (sectionRepo.existsByPageKeyAndSectionKeyAndDeletedAtIsNull(pageKey, sectionKey)) {
            throw new IllegalArgumentException("同一页面下已存在该区块标识: " + sectionKey);
        }
        WebsitePageSection entity = new WebsitePageSection();
        applyPayload(entity, payload);
        WebsitePageSection saved = sectionRepo.save(entity);
        auditLogService.log("WebsitePageSection", saved.getId(), saved.getTitle() == null ? saved.getSectionKey() : saved.getTitle(),
                "PAGE_SECTION_CREATE", String.format(Locale.ROOT, "创建页面区块 page=%s section=%s", saved.getPageKey(), saved.getSectionKey()));
        log.info("Created website page section: id={}, page={}, key={}", saved.getId(), saved.getPageKey(), saved.getSectionKey());
        return PageSectionView.from(saved);
    }

    @Transactional
    public PageSectionView update(Long id, PageSectionPayload payload) {
        WebsitePageSection entity = sectionRepo.findById(id).orElseThrow(() -> new IllegalArgumentException("页面区块不存在: " + id));
        if (entity.getDeletedAt() != null) throw new IllegalArgumentException("页面区块不存在: " + id);
        validatePayload(payload);
        String pageKey = payload.pageKey().toUpperCase();
        String sectionKey = payload.sectionKey().trim();
        if (sectionRepo.existsByPageKeyAndSectionKeyAndIdNotAndDeletedAtIsNull(pageKey, sectionKey, id)) {
            throw new IllegalArgumentException("同一页面下已存在该区块标识: " + sectionKey);
        }
        Map<String, Object> before = snapshot(entity);
        applyPayload(entity, payload);
        WebsitePageSection saved = sectionRepo.save(entity);
        Map<String, Object> after = snapshot(saved);
        String changes = buildChanges(before, after);
        auditLogService.log("WebsitePageSection", saved.getId(), saved.getTitle() == null ? saved.getSectionKey() : saved.getTitle(),
                "PAGE_SECTION_UPDATE", changes.isEmpty() ? "更新页面区块" : changes);
        log.info("Updated website page section: id={}, page={}, key={}", id, saved.getPageKey(), saved.getSectionKey());
        return PageSectionView.from(saved);
    }

    /** 软删除（设置 deletedAt）。与 BannerService.softDelete 保持一致。 */
    @Transactional
    public void softDelete(Long id) {
        WebsitePageSection entity = sectionRepo.findById(id).orElseThrow(() -> new IllegalArgumentException("页面区块不存在: " + id));
        if (entity.getDeletedAt() != null) return;
        String title = entity.getTitle() == null ? entity.getSectionKey() : entity.getTitle();
        entity.setDeletedAt(LocalDateTime.now());
        sectionRepo.save(entity);
        auditLogService.log("WebsitePageSection", id, title, "PAGE_SECTION_DELETE",
                String.format(Locale.ROOT, "软删除页面区块 page=%s section=%s", entity.getPageKey(), entity.getSectionKey()));
        log.info("Soft-deleted website page section: id={}, page={}, key={}", id, entity.getPageKey(), entity.getSectionKey());
    }

    /** 恢复已软删除的区块。 */
    @Transactional
    public PageSectionView restore(Long id) {
        WebsitePageSection entity = sectionRepo.findById(id).orElseThrow(() -> new IllegalArgumentException("页面区块不存在: " + id));
        if (entity.getDeletedAt() == null) return PageSectionView.from(entity);
        entity.setDeletedAt(null);
        WebsitePageSection saved = sectionRepo.save(entity);
        String title = saved.getTitle() == null ? saved.getSectionKey() : saved.getTitle();
        auditLogService.log("WebsitePageSection", saved.getId(), title, "PAGE_SECTION_RESTORE",
                String.format(Locale.ROOT, "恢复页面区块 page=%s section=%s", saved.getPageKey(), saved.getSectionKey()));
        log.info("Restored website page section: id={}, page={}, key={}", id, saved.getPageKey(), saved.getSectionKey());
        return PageSectionView.from(saved);
    }

    @Transactional
    public void delete(Long id) {
        /* 兼容旧接口：降级为软删除。 */
        softDelete(id);
    }

    @Transactional
    public List<PageSectionView> batchUpdateEnabled(List<Long> ids, boolean enabled) {
        if (ids == null || ids.isEmpty()) return List.of();
        List<WebsitePageSection> entities = sectionRepo.findAllById(ids).stream()
                .filter(e -> e.getDeletedAt() == null)
                .toList();
        int changed = 0;
        for (WebsitePageSection e : entities) {
            if ((enabled && !Boolean.TRUE.equals(e.getEnabled())) || (!enabled && Boolean.TRUE.equals(e.getEnabled()))) {
                e.setEnabled(enabled);
                changed++;
            }
        }
        List<WebsitePageSection> saved = sectionRepo.saveAll(entities);
        if (changed > 0) {
            auditLogService.log("WebsitePageSection", null, "批量启停", "PAGE_SECTION_BATCH_TOGGLE",
                    String.format(Locale.ROOT, "批量更新 enabled=%s，共 %d 条（本次变更 %d 条）", enabled, saved.size(), changed));
        }
        log.info("Batch updated enabled to {} for {} website page sections", enabled, saved.size());
        return saved.stream().map(PageSectionView::from).toList();
    }

    private void validatePayload(PageSectionPayload payload) {
        if (payload.pageKey() == null || !VALID_PAGE_KEYS.contains(payload.pageKey().toUpperCase())) {
            throw new IllegalArgumentException("页面标识不合法，仅支持 HOME / PRODUCTS / SOLUTIONS / ABOUT / CONTACT");
        }
        if (payload.sectionKey() == null || payload.sectionKey().isBlank()) {
            throw new IllegalArgumentException("区块标识不能为空");
        }
    }

    private void applyPayload(WebsitePageSection entity, PageSectionPayload payload) {
        entity.setPageKey(payload.pageKey().toUpperCase());
        entity.setSectionKey(payload.sectionKey().trim());
        entity.setTitle(trimToNull(payload.title()));
        entity.setSubtitle(trimToNull(payload.subtitle()));
        entity.setContent(trimToNull(payload.content()));
        entity.setImageUrl(trimToNull(payload.imageUrl()));
        entity.setSortOrder(payload.sortOrder() == null ? 0 : payload.sortOrder());
        entity.setEnabled(payload.enabled() == null || payload.enabled());
    }

    private static String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /* ---------- 审计辅助：快照 + 差异 ---------- */

    private static Map<String, Object> snapshot(WebsitePageSection e) {
        LinkedHashMap<String, Object> m = new LinkedHashMap<>();
        m.put("pageKey", e.getPageKey());
        m.put("sectionKey", e.getSectionKey());
        m.put("title", e.getTitle());
        m.put("subtitle", e.getSubtitle());
        m.put("content", e.getContent());
        m.put("imageUrl", e.getImageUrl());
        m.put("sortOrder", e.getSortOrder());
        m.put("enabled", e.getEnabled());
        return m;
    }

    private static String buildChanges(Map<String, Object> before, Map<String, Object> after) {
        StringJoiner sj = new StringJoiner("; ");
        for (Map.Entry<String, Object> entry : after.entrySet()) {
            String key = entry.getKey();
            Object oldVal = before.get(key);
            Object newVal = entry.getValue();
            if (Objects.equals(oldVal, newVal)) continue;
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
