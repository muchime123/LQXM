package com.liqiang.archive.service;

import com.liqiang.archive.entity.WebsitePackagingItem;
import com.liqiang.archive.entity.WebsitePackagingVariant;
import com.liqiang.archive.repository.WebsitePackagingVariantRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;

/** 官网包材规格只归属官网包材草稿，不引用内部 SKU。 */
@Service
@RequiredArgsConstructor
public class WebsitePackagingVariantService {
    private final WebsitePackagingItemService items;
    private final WebsitePackagingVariantRepository variants;
    private final AuditLogService audit;

    @Transactional(readOnly = true)
    public List<WebsitePackagingVariant> list(Long itemId) {
        items.get(itemId);
        return variants.findByItemIdAndDeletedAtIsNullOrderBySortOrderAscIdAsc(itemId);
    }

    @Transactional
    public WebsitePackagingVariant create(Long itemId, Fields fields) {
        validate(fields);
        requireDraft(itemId);
        WebsitePackagingVariant variant = new WebsitePackagingVariant();
        variant.setItemId(itemId);
        apply(variant, fields);
        WebsitePackagingVariant saved = variants.save(variant);
        audit.log("WebsitePackagingVariant", saved.getId(), saved.getName(),
                "WEBSITE_PACKAGING_VARIANT_CREATE", "创建官网包材规格 itemId=" + itemId);
        return saved;
    }

    @Transactional
    public WebsitePackagingVariant update(Long itemId, Long id, Fields fields) {
        validate(fields);
        requireDraft(itemId);
        WebsitePackagingVariant variant = find(itemId, id);
        apply(variant, fields);
        WebsitePackagingVariant saved = variants.save(variant);
        audit.log("WebsitePackagingVariant", id, saved.getName(),
                "WEBSITE_PACKAGING_VARIANT_UPDATE", "更新官网包材规格 itemId=" + itemId);
        return saved;
    }

    @Transactional
    public void remove(Long itemId, Long id) {
        requireDraft(itemId);
        WebsitePackagingVariant variant = find(itemId, id);
        variant.setDeletedAt(LocalDateTime.now());
        variants.save(variant);
        audit.log("WebsitePackagingVariant", id, variant.getName(),
                "WEBSITE_PACKAGING_VARIANT_REMOVE", "移除官网包材草稿规格 itemId=" + itemId);
    }

    private WebsitePackagingVariant find(Long itemId, Long id) {
        return variants.findByIdAndItemIdAndDeletedAtIsNull(id, itemId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "官网包材规格不存在"));
    }

    private void requireDraft(Long itemId) {
        WebsitePackagingItem item = items.get(itemId);
        if (!"DRAFT".equals(item.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "只有草稿包材可以修改规格");
        }
    }

    private static void validate(Fields fields) {
        if (fields == null || fields.name() == null || fields.name().isBlank()
                || fields.name().length() > 300 || tooLong(fields.publicSku(), 160)
                || tooLong(fields.dimensions(), 300) || tooLong(fields.capacityMl(), 100)
                || tooLong(fields.description(), 20_000)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "官网包材规格字段无效");
        }
    }

    private static boolean tooLong(String value, int max) {
        return value != null && value.length() > max;
    }

    private static void apply(WebsitePackagingVariant variant, Fields fields) {
        variant.setName(fields.name().trim());
        variant.setPublicSku(fields.publicSku());
        variant.setDimensions(fields.dimensions());
        variant.setCapacityMl(fields.capacityMl());
        variant.setDescription(fields.description());
        variant.setSortOrder(fields.sortOrder() == null ? 0 : fields.sortOrder());
    }

    public record Fields(String name, String publicSku, String dimensions, String capacityMl,
                         String description, Integer sortOrder) {}
}
