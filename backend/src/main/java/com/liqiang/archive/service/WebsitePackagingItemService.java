package com.liqiang.archive.service;

import com.liqiang.archive.entity.WebsiteMediaAsset;
import com.liqiang.archive.entity.WebsitePackagingItem;
import com.liqiang.archive.entity.WebsitePackagingVariant;
import com.liqiang.archive.repository.WebsiteMediaAssetRepository;
import com.liqiang.archive.repository.WebsitePackagingItemRepository;
import com.liqiang.archive.repository.WebsitePackagingVariantRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.regex.Pattern;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** 官网包材草稿只使用官网自身的数据和媒体。 */
@Service
@RequiredArgsConstructor
public class WebsitePackagingItemService {
    private static final Pattern SLUG = Pattern.compile("[a-z0-9]+(?:-[a-z0-9]+)*");
    private final WebsitePackagingItemRepository items;
    private final WebsitePackagingVariantRepository variants;
    private final WebsiteMediaAssetRepository media;
    private final AuditLogService audit;

    @Value("${app.files.root}")
    private String fileRoot;

    @Transactional(readOnly = true)
    public Page<WebsitePackagingItem> list(int page, int size) {
        return items.findByDeletedAtIsNull(PageRequest.of(Math.max(0, page),
                Math.max(1, Math.min(size, 100)), Sort.by("sortOrder").ascending().and(Sort.by("id"))));
    }

    @Transactional(readOnly = true)
    public WebsitePackagingItem get(Long id) {
        return items.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "官网包材不存在"));
    }

    @Transactional
    public WebsitePackagingItem createDraft(Fields fields) {
        validate(fields);
        if (items.existsBySlug(fields.slug())) throw duplicateSlug();
        validateMedia(fields.coverMediaId());
        WebsitePackagingItem item = new WebsitePackagingItem();
        apply(item, fields);
        WebsitePackagingItem saved = save(item);
        audit.log("WebsitePackagingItem", saved.getId(), saved.getName(),
                "WEBSITE_PACKAGING_CREATE", "创建官网包材草稿");
        return saved;
    }

    @Transactional
    public WebsitePackagingItem updateDraft(Long id, Fields fields) {
        validate(fields);
        WebsitePackagingItem item = get(id);
        if (!"DRAFT".equals(item.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "只有官网包材草稿可以编辑");
        }
        if (items.existsBySlugAndIdNot(fields.slug(), id)) throw duplicateSlug();
        validateMedia(fields.coverMediaId());
        apply(item, fields);
        WebsitePackagingItem saved = save(item);
        audit.log("WebsitePackagingItem", id, saved.getName(),
                "WEBSITE_PACKAGING_UPDATE", "更新官网包材草稿");
        return saved;
    }

    @Transactional(readOnly = true)
    public List<WebsitePackagingItem> published() {
        return items.findByDeletedAtIsNullAndStatusOrderBySortOrderAscIdAsc("PUBLISHED");
    }

    @Transactional(readOnly = true)
    public List<CatalogItem> publishedCatalog() {
        List<WebsitePackagingItem> published = published();
        if (published.isEmpty()) return List.of();
        List<Long> ids = published.stream().map(WebsitePackagingItem::getId).toList();
        Map<Long, List<WebsitePackagingVariant>> byItem = variants
                .findByItemIdInAndDeletedAtIsNullOrderBySortOrderAscIdAsc(ids).stream()
                .collect(Collectors.groupingBy(WebsitePackagingVariant::getItemId));
        return published.stream().map(item -> new CatalogItem(item,
                byItem.getOrDefault(item.getId(), List.of()))).toList();
    }

    @Transactional
    public WebsitePackagingItem publish(Long id) {
        WebsitePackagingItem item = get(id);
        if ("PUBLISHED".equals(item.getStatus())) return item;
        if (!"DRAFT".equals(item.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "只有官网包材草稿可以发布");
        }
        if (item.getName() == null || item.getName().isBlank()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "官网包材缺少公开名称");
        }
        Long coverId = item.getCoverMediaId();
        if (coverId == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "官网包材缺少封面图片");
        }
        if (variants.findByItemIdAndDeletedAtIsNullOrderBySortOrderAscIdAsc(id).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "官网包材缺少公开规格");
        }
        validateMedia(coverId);
        WebsiteMediaAsset cover = media.findByIdAndDeletedAtIsNull(coverId).orElseThrow();
        requireReadableWebsiteFile(cover.getStorageKey());
        cover.setStatus("PUBLISHED");
        media.save(cover);
        item.setStatus("PUBLISHED");
        item.setPublishedAt(LocalDateTime.now());
        WebsitePackagingItem saved = items.save(item);
        audit.log("WebsitePackagingItem", id, saved.getName(),
                "WEBSITE_PACKAGING_PUBLISH", "发布官网包材");
        return saved;
    }

    private void requireReadableWebsiteFile(String storageKey) {
        if (fileRoot == null || storageKey == null || storageKey.isBlank()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "官网包材封面文件不可读取");
        }
        try {
            Path root = Path.of(fileRoot).toAbsolutePath().normalize().resolve("website");
            Path file = Path.of(fileRoot).toAbsolutePath().normalize().resolve(storageKey).normalize();
            if (!file.startsWith(root) || !Files.isRegularFile(file) || !Files.isReadable(file)
                    || !file.toRealPath().startsWith(root.toRealPath())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "官网包材封面文件不可读取");
            }
        } catch (IOException | RuntimeException ex) {
            if (ex instanceof ResponseStatusException response) throw response;
            throw new ResponseStatusException(HttpStatus.CONFLICT, "官网包材封面文件不可读取", ex);
        }
    }

    @Transactional
    public WebsitePackagingItem unpublish(Long id) {
        WebsitePackagingItem item = get(id);
        if ("DRAFT".equals(item.getStatus())) return item;
        if (!"PUBLISHED".equals(item.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "只有已发布的官网包材可以下线");
        }
        item.setStatus("DRAFT");
        item.setPublishedAt(null);
        WebsitePackagingItem saved = items.save(item);
        audit.log("WebsitePackagingItem", id, saved.getName(),
                "WEBSITE_PACKAGING_UNPUBLISH", "下线官网包材并转为草稿");
        return saved;
    }

    private WebsitePackagingItem save(WebsitePackagingItem item) {
        try {
            return items.saveAndFlush(item);
        } catch (DataIntegrityViolationException ex) {
            String detail = ex.getMostSpecificCause().getMessage();
            if (detail != null && detail.contains("uk_website_packaging_item_slug")) throw duplicateSlug();
            throw ex;
        }
    }

    private static ResponseStatusException duplicateSlug() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "官网包材 URL 标识已存在");
    }

    private void validateMedia(Long mediaId) {
        if (mediaId == null) return;
        WebsiteMediaAsset asset = media.findByIdAndDeletedAtIsNull(mediaId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "官网包材图片不存在"));
        if (!"IMAGE".equals(asset.getMediaType()) || "ARCHIVED".equals(asset.getStatus())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "官网包材只能使用未归档的官网图片");
        }
    }

    private static void validate(Fields fields) {
        if (fields == null || fields.slug() == null || !SLUG.matcher(fields.slug()).matches()
                || fields.slug().length() > 160 || fields.name() == null || fields.name().isBlank()
                || fields.name().length() > 300 || tooLong(fields.packagingKind(), 80)
                || tooLong(fields.material(), 200) || tooLong(fields.shapeType(), 120)
                || tooLong(fields.capacityMl(), 100) || tooLong(fields.applicable(), 20_000)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "官网包材名称、URL 标识或公开字段无效");
        }
    }

    private static boolean tooLong(String value, int max) {
        return value != null && value.length() > max;
    }

    private static void apply(WebsitePackagingItem item, Fields fields) {
        item.setSlug(fields.slug());
        item.setName(fields.name().trim());
        item.setPackagingKind(fields.packagingKind());
        item.setMaterial(fields.material());
        item.setShapeType(fields.shapeType());
        item.setCapacityMl(fields.capacityMl());
        item.setApplicable(fields.applicable());
        item.setCustomizable(Boolean.TRUE.equals(fields.customizable()));
        item.setCoverMediaId(fields.coverMediaId());
        item.setSortOrder(fields.sortOrder() == null ? 0 : fields.sortOrder());
    }

    public record Fields(String slug, String name, String packagingKind, String material,
                         String shapeType, String capacityMl, String applicable, Boolean customizable,
                         Long coverMediaId, Integer sortOrder) {}

    public record CatalogItem(WebsitePackagingItem item, List<WebsitePackagingVariant> variants) {}
}
