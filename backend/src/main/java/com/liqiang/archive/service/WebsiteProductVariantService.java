package com.liqiang.archive.service;

import com.liqiang.archive.entity.WebsiteProduct;
import com.liqiang.archive.entity.WebsiteProductVariant;
import com.liqiang.archive.repository.WebsiteProductVariantRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/** 官网产品规格草稿，只通过官网产品 ID 建立归属。 */
@Service
@RequiredArgsConstructor
public class WebsiteProductVariantService {
    private final WebsiteProductService productService;
    private final WebsiteProductVariantRepository repository;
    private final AuditLogService auditLogService;

    @Transactional(readOnly = true)
    public List<WebsiteProductVariant> list(Long productId) {
        productService.get(productId);
        return repository.findByProductIdAndDeletedAtIsNullOrderBySortOrderAscIdAsc(productId);
    }

    @Transactional
    public WebsiteProductVariant createDraft(Long productId, VariantFields fields) {
        validate(fields);
        requireDraftProduct(productId);
        WebsiteProductVariant variant = new WebsiteProductVariant();
        variant.setProductId(productId);
        apply(variant, fields);
        WebsiteProductVariant saved = repository.save(variant);
        auditLogService.log("WebsiteProductVariant", saved.getId(), saved.getName(),
                "WEBSITE_PRODUCT_VARIANT_CREATE", "创建官网产品规格草稿 productId=" + productId);
        return saved;
    }

    @Transactional
    public WebsiteProductVariant updateDraft(Long productId, Long id, VariantFields fields) {
        validate(fields);
        requireDraftProduct(productId);
        WebsiteProductVariant variant = repository.findByIdAndProductIdAndDeletedAtIsNull(id, productId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "官网产品规格不存在"));
        if (!"DRAFT".equals(variant.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "只有草稿规格可以编辑");
        }
        apply(variant, fields);
        WebsiteProductVariant saved = repository.save(variant);
        auditLogService.log("WebsiteProductVariant", saved.getId(), saved.getName(),
                "WEBSITE_PRODUCT_VARIANT_UPDATE", "更新官网产品规格草稿 productId=" + productId);
        return saved;
    }

    private void requireDraftProduct(Long productId) {
        WebsiteProduct product = productService.get(productId);
        if (!"DRAFT".equals(product.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "只有草稿产品可以修改规格");
        }
    }

    private static void validate(VariantFields fields) {
        if (fields == null || fields.name() == null || fields.name().isBlank()
                || fields.name().length() > 300) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请填写 300 字以内的规格名称");
        }
        if (tooLong(fields.publicSku(), 200) || tooLong(fields.dimensions(), 300)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "官网规格字段超过长度限制");
        }
    }

    private static boolean tooLong(String value, int max) {
        return value != null && value.length() > max;
    }

    private static void apply(WebsiteProductVariant variant, VariantFields fields) {
        variant.setName(fields.name().trim());
        variant.setPublicSku(fields.publicSku());
        variant.setDimensions(fields.dimensions());
        variant.setDescription(fields.description());
        variant.setSortOrder(fields.sortOrder() == null ? 0 : fields.sortOrder());
    }

    public record VariantFields(String name, String publicSku, String dimensions,
                                String description, Integer sortOrder) {
    }
}
