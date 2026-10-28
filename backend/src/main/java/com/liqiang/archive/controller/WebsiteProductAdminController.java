package com.liqiang.archive.controller;

import com.liqiang.archive.entity.WebsiteProduct;
import com.liqiang.archive.security.RequirePermission;
import com.liqiang.archive.service.WebsiteProductService;
import com.liqiang.archive.service.WebsiteProductService.DraftFields;
import com.liqiang.archive.service.WebsiteProductReviewService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 官网产品独立管理接口；公开官网在完成迁移与对拍前继续使用原有读取链路。 */
@RestController
@RequestMapping("/api/website/admin/products")
@RequirePermission("website.manage")
@RequiredArgsConstructor
public class WebsiteProductAdminController {
    private final WebsiteProductService service;
    private final WebsiteProductReviewService reviewService;

    @GetMapping
    public Page<ProductView> list(@RequestParam(defaultValue = "0") int page,
                                  @RequestParam(defaultValue = "20") int size) {
        return service.list(page, size).map(ProductView::from);
    }

    @GetMapping("/{id}")
    public ProductView get(@PathVariable Long id) {
        return ProductView.from(service.get(id));
    }

    /** Full website-owned content for editorial review; never served by the public controller. */
    @GetMapping("/{id}/review")
    public WebsiteProductReviewService.ReviewView review(@PathVariable Long id) {
        return reviewService.get(id);
    }

    @PostMapping
    public ProductView createDraft(@RequestBody DraftFields fields) {
        return ProductView.from(service.createDraft(fields));
    }

    @PutMapping("/{id}")
    public ProductView updateDraft(@PathVariable Long id, @RequestBody DraftFields fields) {
        return ProductView.from(service.updateDraft(id, fields));
    }

    public record ProductView(Long id, Long categoryId, String name, String nameEn, String publicModel,
                              Long heroMediaId, Long cardMediaId,
                              String slug, String summary, String summaryEn, String seoTitle,
                              String seoDescription, Integer sortOrder, Boolean featured,
                              String status) {
        static ProductView from(WebsiteProduct product) {
            return new ProductView(product.getId(), product.getCategoryId(), product.getName(), product.getNameEn(),
                    product.getPublicModel(), product.getHeroMediaId(), product.getCardMediaId(),
                    product.getSlug(), product.getSummary(),
                    product.getSummaryEn(), product.getSeoTitle(), product.getSeoDescription(),
                    product.getSortOrder(), product.getFeatured(), product.getStatus());
        }
    }
}
