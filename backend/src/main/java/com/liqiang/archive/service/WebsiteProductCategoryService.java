package com.liqiang.archive.service;

import com.liqiang.archive.entity.WebsiteProductCategory;
import com.liqiang.archive.repository.WebsiteProductCategoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.regex.Pattern;

/** 官网独立产品分类草稿，不从内部分类表同步。 */
@Service
@RequiredArgsConstructor
public class WebsiteProductCategoryService {
    private static final Pattern SLUG = Pattern.compile("[a-z0-9]+(?:-[a-z0-9]+)*");

    private final WebsiteProductCategoryRepository repository;
    private final AuditLogService auditLogService;

    @Transactional(readOnly = true)
    public List<WebsiteProductCategory> list() {
        return repository.findByDeletedAtIsNullOrderBySortOrderAscIdAsc();
    }

    @Transactional
    public WebsiteProductCategory createDraft(CategoryFields fields) {
        validate(fields);
        if (repository.existsBySlug(fields.slug())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "官网分类 URL 标识已存在");
        }
        WebsiteProductCategory category = new WebsiteProductCategory();
        category.setName(fields.name().trim());
        category.setSlug(fields.slug());
        category.setSortOrder(fields.sortOrder() == null ? 0 : fields.sortOrder());
        WebsiteProductCategory saved = repository.save(category);
        auditLogService.log("WebsiteProductCategory", saved.getId(), saved.getName(),
                "WEBSITE_PRODUCT_CATEGORY_CREATE", "创建官网产品分类草稿");
        return saved;
    }

    @Transactional
    public WebsiteProductCategory updateDraft(Long id, CategoryFields fields) {
        WebsiteProductCategory category = repository.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "官网分类不存在"));
        if (!"DRAFT".equals(category.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "只能修改官网分类草稿");
        }
        validate(fields);
        if (!category.getSlug().equals(fields.slug()) && repository.existsBySlug(fields.slug())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "官网分类 URL 标识已存在");
        }
        category.setName(fields.name().trim());
        category.setSlug(fields.slug());
        category.setSortOrder(fields.sortOrder() == null ? 0 : fields.sortOrder());
        WebsiteProductCategory saved = repository.save(category);
        auditLogService.log("WebsiteProductCategory", saved.getId(), saved.getName(),
                "WEBSITE_PRODUCT_CATEGORY_UPDATE", "更新官网产品分类草稿");
        return saved;
    }

    private static void validate(CategoryFields fields) {
        if (fields == null || fields.name() == null || fields.name().isBlank()
                || fields.name().length() > 200 || fields.slug() == null
                || fields.slug().length() > 200 || !SLUG.matcher(fields.slug()).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请填写有效的官网分类名称和 URL 标识");
        }
    }

    public record CategoryFields(String name, String slug, Integer sortOrder) {
    }
}
