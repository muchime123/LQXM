package com.liqiang.archive.service;

import com.liqiang.archive.entity.WebsiteProduct;
import com.liqiang.archive.repository.WebsiteProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.regex.Pattern;

/** 官网产品草稿的独立维护入口，不读取或同步内部产品档案。 */
@Service
@RequiredArgsConstructor
public class WebsiteProductService {
    private static final Pattern SLUG = Pattern.compile("[a-z0-9]+(?:-[a-z0-9]+)*");

    private final WebsiteProductRepository repository;
    private final AuditLogService auditLogService;

    @Transactional(readOnly = true)
    public Page<WebsiteProduct> list(int page, int size) {
        return repository.findByDeletedAtIsNull(PageRequest.of(Math.max(0, page),
                Math.max(1, Math.min(size, 100)), Sort.by(Sort.Direction.DESC, "id")));
    }

    @Transactional(readOnly = true)
    public WebsiteProduct get(Long id) {
        return repository.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "官网产品不存在"));
    }

    @Transactional
    public WebsiteProduct createDraft(DraftFields fields) {
        validate(fields);
        if (repository.existsBySlug(fields.slug())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "官网产品 URL 标识已存在");
        }
        WebsiteProduct product = new WebsiteProduct();
        apply(product, fields);
        WebsiteProduct saved = repository.save(product);
        auditLogService.log("WebsiteProduct", saved.getId(), saved.getName(), "WEBSITE_PRODUCT_CREATE",
                "创建官网产品草稿");
        return saved;
    }

    @Transactional
    public WebsiteProduct updateDraft(Long id, DraftFields fields) {
        validate(fields);
        WebsiteProduct product = get(id);
        if (!"DRAFT".equals(product.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "只有草稿可以在此接口编辑");
        }
        if (repository.existsBySlugAndIdNot(fields.slug(), id)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "官网产品 URL 标识已存在");
        }
        apply(product, fields);
        WebsiteProduct saved = repository.save(product);
        auditLogService.log("WebsiteProduct", saved.getId(), saved.getName(), "WEBSITE_PRODUCT_UPDATE",
                "更新官网产品草稿");
        return saved;
    }

    private static void validate(DraftFields fields) {
        if (fields == null || fields.name() == null || fields.name().isBlank()
                || fields.name().length() > 300) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请填写 300 字以内的官网产品名称");
        }
        if (fields.slug() == null || fields.slug().length() > 200
                || !SLUG.matcher(fields.slug()).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "官网产品 URL 标识只能使用小写字母、数字和连字符");
        }
        if (tooLong(fields.nameEn(), 300) || tooLong(fields.publicModel(), 200)
                || tooLong(fields.seoTitle(), 300) || tooLong(fields.seoDescription(), 500)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "官网产品字段超过长度限制");
        }
    }

    private static boolean tooLong(String value, int max) {
        return value != null && value.length() > max;
    }

    private static void apply(WebsiteProduct product, DraftFields fields) {
        product.setName(fields.name().trim());
        product.setNameEn(fields.nameEn());
        product.setPublicModel(fields.publicModel());
        product.setSlug(fields.slug());
        product.setSummary(fields.summary());
        product.setSummaryEn(fields.summaryEn());
        product.setSeoTitle(fields.seoTitle());
        product.setSeoDescription(fields.seoDescription());
        product.setSortOrder(fields.sortOrder() == null ? 0 : fields.sortOrder());
        product.setFeatured(Boolean.TRUE.equals(fields.featured()));
    }

    public record DraftFields(String name, String nameEn, String publicModel, String slug,
                              String summary, String summaryEn, String seoTitle,
                              String seoDescription, Integer sortOrder, Boolean featured) {
    }
}
