package com.liqiang.archive.service;

import com.liqiang.archive.repository.WebsiteMediaAssetRepository;
import com.liqiang.archive.repository.WebsiteProductCategoryRepository;
import com.liqiang.archive.repository.WebsiteProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/** Read-only technical gate for switching public product reads to website-owned tables. */
@Service
@RequiredArgsConstructor
public class WebsiteProductCutoverReadinessService {
    private final WebsiteProductRepository products;
    private final WebsiteProductCategoryRepository categories;
    private final WebsiteMediaAssetRepository media;
    private final WebsiteProductPublicationReadinessService productReadiness;

    @Transactional(readOnly = true)
    public CutoverReadinessView check() {
        long published = products.countByDeletedAtIsNullAndStatus("PUBLISHED");
        long drafts = products.countByDeletedAtIsNullAndStatus("DRAFT");
        long featured = products.countByDeletedAtIsNullAndStatusAndFeaturedTrue("PUBLISHED");
        long publishedCategories = categories.countByDeletedAtIsNullAndStatus("PUBLISHED");
        long draftCategories = categories.countByDeletedAtIsNullAndStatus("DRAFT");
        long publishedMedia = media.countByDeletedAtIsNullAndStatus("PUBLISHED");
        long draftMedia = media.countByDeletedAtIsNullAndStatus("DRAFT");
        List<String> blockers = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        List<ProductIssue> productIssues = new ArrayList<>();
        if (published == 0) blockers.add("没有已发布的官网产品，切换后列表会为空");
        if (featured == 0) warnings.add("没有已发布的精选产品，首页将回退显示普通官网产品");
        if (drafts > 0) warnings.add("仍有 " + drafts + " 条官网产品草稿，需与当前公开产品逐项对拍");
        var pageNumber = 0;
        while (true) {
            var page = products.findByDeletedAtIsNullAndStatus("PUBLISHED", PageRequest.of(pageNumber++, 100));
            for (var product : page.getContent()) {
                var result = productReadiness.check(product.getId());
                if (!result.blockers().isEmpty() || !result.warnings().isEmpty()) {
                    productIssues.add(new ProductIssue(product.getId(), product.getName(),
                            result.blockers(), result.warnings()));
                }
            }
            if (!page.hasNext()) break;
        }
        if (productIssues.stream().anyMatch(issue -> !issue.blockers().isEmpty())) {
            blockers.add("部分已发布产品的分类、图片或文件不满足公开读取条件");
        }
        return new CutoverReadinessView(blockers.isEmpty(), published, drafts, featured,
                publishedCategories, draftCategories, publishedMedia, draftMedia,
                List.copyOf(blockers), List.copyOf(warnings), List.copyOf(productIssues));
    }

    public record ProductIssue(Long id, String name, List<String> blockers, List<String> warnings) {}

    public record CutoverReadinessView(boolean technicallyReady, long publishedProducts,
                                       long draftProducts, long publishedFeaturedProducts,
                                       long publishedCategories, long draftCategories,
                                       long publishedMedia, long draftMedia,
                                       List<String> blockers, List<String> warnings,
                                       List<ProductIssue> productIssues) {}
}
