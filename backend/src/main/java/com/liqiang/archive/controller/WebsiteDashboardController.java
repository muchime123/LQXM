package com.liqiang.archive.controller;

import com.liqiang.archive.dto.CatalogDtos.ModelSummary;
import com.liqiang.archive.entity.ProductModel;
import com.liqiang.archive.entity.PackagingModel;
import com.liqiang.archive.entity.PackagingMold;
import com.liqiang.archive.entity.WebsiteSolution;
import com.liqiang.archive.repository.CertificateRepository;
import com.liqiang.archive.repository.CompanyCertificationRepository;
import com.liqiang.archive.repository.MediaAccountRepository;
import com.liqiang.archive.repository.PackagingModelRepository;
import com.liqiang.archive.repository.PackagingMoldRepository;
import com.liqiang.archive.repository.ProductModelRepository;
import com.liqiang.archive.repository.WebsiteBannerRepository;
import com.liqiang.archive.repository.WebsiteInquiryRepository;
import com.liqiang.archive.repository.WebsiteSolutionRepository;
import com.liqiang.archive.security.RequirePermission;
import com.liqiang.archive.service.ProductCatalogService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 官网工作台聚合接口。供后台「官网运营 · 工作台」页面使用，
 * 汇总各档案实体的官网发布数量与内容健康度，均为只读统计。
 */
@RestController
@RequestMapping("/api/website/admin/dashboard")
@RequiredArgsConstructor
@RequirePermission("website.manage")
public class WebsiteDashboardController {

    private final ProductCatalogService catalogService;
    private final ProductModelRepository modelRepository;
    private final PackagingModelRepository packagingModelRepository;
    private final PackagingMoldRepository packagingMoldRepository;
    private final CompanyCertificationRepository certificationRepository;
    private final CertificateRepository certificateRepository;
    private final WebsiteSolutionRepository solutionRepository;
    private final WebsiteInquiryRepository inquiryRepository;
    private final MediaAccountRepository mediaAccountRepository;
    private final WebsiteBannerRepository bannerRepository;

    /** 产品健康度检查可参考的最大条数（产品量级 < 500，全量统计无性能问题）。 */
    private static final int PRODUCT_SCAN_LIMIT = 500;

    @GetMapping
    public WebsiteDashboardView dashboard() {
        // ===== 产品 =====
        List<ModelSummary> published = catalogService.searchWebsiteProducts(null, null, null, null, null, null,
                PageRequest.of(0, PRODUCT_SCAN_LIMIT)).getContent();
        // 英文名称不在 ModelSummary 中，单独取已发布产品映射（缺英文名检查用）
        Map<Long, String> nameEnById = modelRepository.findByWebsiteEnabledTrue().stream()
                .collect(Collectors.toMap(ProductModel::getId,
                        m -> m.getNameEn() == null ? "" : m.getNameEn()));

        long featuredProducts = published.stream()
                .filter(m -> Boolean.TRUE.equals(m.websiteFeatured())).count();
        long incompleteProducts = published.stream()
                .filter(m -> m.completenessScore() < WebsiteController.WEBSITE_LIST_MIN_SCORE).count();
        List<ProductHealthIssue> productIssues = buildProductIssues(published, nameEnById);

        // ===== 资质 / 专利 =====
        long publishedCertifications = certificationRepository.findAllWebsiteEnabled().size();
        LocalDate today = LocalDate.now();
        long expiringCertifications = certificationRepository
                .findExpiringSoon(today, today.plusDays(90)).stream()
                .filter(c -> Boolean.TRUE.equals(c.getWebsiteEnabled()))
                .count();
        long publishedPatents = certificateRepository.countWebsiteEnabled();
        long featuredPatents = certificateRepository.countWebsiteFeatured();

        // ===== 其他档案 =====
        long publishedPackaging = packagingModelRepository.countByWebsiteEnabledTrue();
        long publishedMolds = packagingMoldRepository.countByWebsiteEnabledTrue();
        long publishedSolutions = solutionRepository.countByWebsiteEnabledTrue();
        long officialMediaAccounts = mediaAccountRepository
                .findByWebsiteOfficialTrueOrderByPlatformAscIdAsc().size();
        long publishedBanners = bannerRepository.findAllActive().size();

        // ===== 询盘 =====
        long pendingInquiries = inquiryRepository.countByStatus("pending");
        long recentInquiries = inquiryRepository.countByCreatedAtAfter(LocalDateTime.now().minusMonths(1));
        List<PublishReadinessIssue> readinessIssues = buildReadinessIssues();

        return new WebsiteDashboardView(
                published.size(), featuredProducts, incompleteProducts,
                publishedPackaging, publishedMolds,
                publishedCertifications, expiringCertifications,
                publishedPatents, featuredPatents,
                publishedSolutions, officialMediaAccounts, publishedBanners,
                pendingInquiries, recentInquiries,
                productIssues, readinessIssues);
    }

    /** 跨档案发布前检查，只读取业务档案现有字段，不维护官网专属副本。 */
    private List<PublishReadinessIssue> buildReadinessIssues() {
        List<PublishReadinessIssue> issues = new ArrayList<>();

        List<PackagingModel> packaging = packagingModelRepository.findAll();
        issues.add(readinessIssue("packagingNotForSale", "已发布包材状态异常", "packaging", "/packaging",
                packaging.stream().filter(p -> Boolean.TRUE.equals(p.getWebsiteEnabled()) && !"在售".equals(p.getStatus()))
                        .map(p -> item(p.getId(), p.getName())).toList()));
        issues.add(readinessIssue("packagingReady", "包材已满足条件、尚未发布", "packaging", "/packaging",
                packaging.stream().filter(p -> "在售".equals(p.getStatus()) && !Boolean.TRUE.equals(p.getWebsiteEnabled()))
                        .map(p -> item(p.getId(), p.getName())).toList()));

        List<PackagingMold> molds = packagingMoldRepository.findAll();
        issues.add(readinessIssue("moldNotEligible", "已发布模具状态异常", "mold", "/molds",
                molds.stream().filter(m -> Boolean.TRUE.equals(m.getWebsiteEnabled())
                                && (!"main".equals(m.getMoldRole()) || !"in_use".equals(m.getStatus())))
                        .map(m -> item(m.getId(), m.getMoldCode())).toList()));
        issues.add(readinessIssue("moldReady", "主模已满足条件、尚未发布", "mold", "/molds",
                molds.stream().filter(m -> "main".equals(m.getMoldRole()) && "in_use".equals(m.getStatus())
                                && !Boolean.TRUE.equals(m.getWebsiteEnabled()))
                        .map(m -> item(m.getId(), m.getMoldCode())).toList()));

        List<WebsiteSolution> solutions = solutionRepository.findAll();
        issues.add(readinessIssue("solutionIncomplete", "已发布解决方案资料不完整", "solution", "/website/solutions",
                solutions.stream().filter(s -> Boolean.TRUE.equals(s.getWebsiteEnabled()) && !solutionEligible(s))
                        .map(s -> item(s.getId(), s.getTitle())).toList()));
        issues.add(readinessIssue("solutionReady", "解决方案资料完整、尚未发布", "solution", "/website/solutions",
                solutions.stream().filter(WebsiteDashboardController::solutionEligible)
                        .filter(s -> !Boolean.TRUE.equals(s.getWebsiteEnabled()))
                        .map(s -> item(s.getId(), s.getTitle())).toList()));
        return issues;
    }

    private static boolean solutionEligible(WebsiteSolution solution) {
        if (solution.getTitle() == null || solution.getTitle().isBlank()) return false;
        if (solution.getSection() == WebsiteSolution.SolutionSection.ARTICLE
                && (solution.getSlug() == null || solution.getSlug().isBlank())) return false;
        if (solution.getSection() == WebsiteSolution.SolutionSection.INDUSTRY
                && (solution.getItemKey() == null || solution.getItemKey().isBlank())) return false;
        return solution.getSection() != WebsiteSolution.SolutionSection.ARTICLE
                && solution.getSection() != WebsiteSolution.SolutionSection.INDUSTRY
                || solution.getCoverImageUrl() != null && !solution.getCoverImageUrl().isBlank();
    }

    private static PublishReadinessItem item(Long id, String name) {
        return new PublishReadinessItem(id, name);
    }

    private static PublishReadinessIssue readinessIssue(String key, String label, String domain, String target,
                                                        List<PublishReadinessItem> items) {
        return new PublishReadinessIssue(key, label, domain, target, items.size(), items.stream().limit(5).toList());
    }

    /** 官网产品健康度检查：仅针对已发布产品（website_enabled=true）。 */
    private static List<ProductHealthIssue> buildProductIssues(List<ModelSummary> published,
                                                               Map<Long, String> nameEnById) {
        Map<String, List<ModelSummary>> bySlug = published.stream()
                .filter(m -> m.websiteSlug() != null && !m.websiteSlug().isBlank())
                .collect(Collectors.groupingBy(ModelSummary::websiteSlug));

        List<ProductHealthIssue> issues = new ArrayList<>();
        issues.add(issue("belowListThreshold", "完整度不足（低于 40 分不上官网）",
                published.stream().filter(m -> m.completenessScore() < WebsiteController.WEBSITE_LIST_MIN_SCORE).toList()));
        issues.add(issue("missingMainImage", "缺少主图",
                published.stream().filter(m -> m.defaultImageAssetId() == null
                        && (m.defaultImage() == null || m.defaultImage().isBlank())).toList()));
        issues.add(issue("missingSummary", "缺少官网摘要",
                published.stream().filter(m -> isBlankSummary(m.websiteSummary())).toList()));
        issues.add(issue("missingEnName", "缺少英文名称",
                published.stream().filter(m -> nameEnById.getOrDefault(m.id(), "").isBlank()).toList()));
        issues.add(issue("missingCategory", "缺少设备类别",
                published.stream().filter(m -> m.category() == null || m.category().isBlank()).toList()));
        issues.add(issue("noVariant", "缺少规格参数",
                published.stream().filter(m -> m.variantCount() < 1).toList()));
        issues.add(issue("missingSlug", "缺少 Slug",
                published.stream().filter(m -> m.websiteSlug() == null || m.websiteSlug().isBlank()).toList()));
        issues.add(issue("duplicateSlug", "Slug 冲突",
                bySlug.values().stream().filter(l -> l.size() > 1).flatMap(List::stream).toList()));
        return issues;
    }

    private static boolean isBlankSummary(String summary) {
        return summary == null || summary.isBlank()
                || summary.endsWith("，品质保障，值得信赖。")
                || summary.endsWith("，品质保障。");
    }

    private static ProductHealthIssue issue(String key, String label, List<ModelSummary> items) {
        return new ProductHealthIssue(key, label, items.size(),
                items.stream().limit(5)
                        .map(m -> new ProductIssueItem(m.id(), m.name(), m.modelCode(),
                                m.category(), m.websiteSlug()))
                        .toList());
    }

    /** 官网工作台聚合数据。 */
    public record WebsiteDashboardView(
            long publishedProducts,
            long featuredProducts,
            long incompleteProducts,
            long publishedPackaging,
            long publishedMolds,
            long publishedCertifications,
            long expiringCertifications,
            long publishedPatents,
            long featuredPatents,
            long publishedSolutions,
            long officialMediaAccounts,
            long publishedBanners,
            long pendingInquiries,
            long recentInquiries,
            List<ProductHealthIssue> productIssues,
            List<PublishReadinessIssue> readinessIssues) {
    }

    /** 产品健康度问题分组。 */
    public record ProductHealthIssue(String key, String label, long count, List<ProductIssueItem> samples) {
    }

    /** 健康度问题示例产品（最多 5 条，供后台跳转定位）。 */
    public record ProductIssueItem(Long id, String name, String modelCode,
                                   String category, String websiteSlug) {
    }

    public record PublishReadinessIssue(String key, String label, String domain, String target,
                                        long count, List<PublishReadinessItem> samples) {
    }

    public record PublishReadinessItem(Long id, String name) {
    }
}
