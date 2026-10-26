package com.liqiang.archive.controller;

import com.liqiang.archive.dto.CatalogDtos.ModelSummary;
import com.liqiang.archive.dto.CatalogDtos.ModelDetail;
import com.liqiang.archive.dto.CatalogDtos.ModelView;
import com.liqiang.archive.dto.CatalogDtos.VariantView;
import com.liqiang.archive.dto.CatalogDtos.AssetView;
import com.liqiang.archive.dto.PackagingMoldDtos.MoldSummary;
import com.liqiang.archive.entity.CompanyInfo;
import com.liqiang.archive.entity.CompanyMilestone;
import com.liqiang.archive.entity.KnowledgeArticle;
import com.liqiang.archive.entity.KnowledgeCategory;
import com.liqiang.archive.entity.MediaAccount;
import com.liqiang.archive.entity.WebsiteInquiry;
import com.liqiang.archive.repository.MediaAccountRepository;
import com.liqiang.archive.repository.WebsiteInquiryRepository;
import com.liqiang.archive.security.PublicApi;
import com.liqiang.archive.service.CompanyInfoService;
import com.liqiang.archive.service.InquiryAntiSpamService;
import com.liqiang.archive.service.ProductCatalogService;
import com.liqiang.archive.service.PackagingService;
import com.liqiang.archive.service.PackagingMoldService;
import com.liqiang.archive.service.WebsiteProductCompatibilityService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** 官网公开数据接口。GET 端点为只读展示数据，POST 端点接收询盘。公开 DTO 不包含内部价格、来源文件和账户等敏感档案。 */
@RestController
@RequestMapping("/api/website")
@RequiredArgsConstructor
@PublicApi
public class WebsiteController {

    /** 官网产品中心/列表的完整度门槛：至少通过 2 项（=40 分）才出现在列表中。 */
    static final int WEBSITE_LIST_MIN_SCORE = 40;
    /** 官网首页「核心产品」的完整度门槛：至少通过 3 项（=60 分）才进首页。 */
    static final int WEBSITE_FEATURED_MIN_SCORE = 60;

    private final CompanyInfoService companyInfoService;
    private final ProductCatalogService catalogService;
    private final PackagingService packagingService;
    private final PackagingMoldService packagingMoldService;
    private final WebsiteProductCompatibilityService productCompatibilityService;
    private final WebsiteInquiryRepository inquiryRepository;
    private final InquiryAntiSpamService antiSpamService;
    private final MediaAccountRepository mediaAccountRepository;

    @GetMapping("/home")
    public HomeView home() {
        CompanyInfo company = companyInfoService.getCompanyInfo();
        // 首页产品：后台明确勾选的核心产品优先展示。
        // websiteFeatured 已经表达了运营人员的发布意图，不再用资料完整度二次静默过滤。
        List<PublicProductView> featuredView = catalogService.getWebsiteFeaturedProducts(500).stream()
                .map(PublicProductView::from)
                .filter(p -> p.completenessScore() >= WEBSITE_FEATURED_MIN_SCORE)
                .toList();

        // 全量过列表门槛的已发布产品总数（用全量一次抓取 + 过滤统计，产品量级 <500 无性能问题）
        List<ModelSummary> all = catalogService.searchWebsiteProducts(null, null, null, null, null, null,
                PageRequest.of(0, 500, Sort.by("sortOrder").ascending())).getContent();
        long productCount = all.stream()
                .mapToInt(ModelSummary::completenessScore)
                .filter(s -> s >= WEBSITE_LIST_MIN_SCORE)
                .count();

        List<PublicProductView> products;
        if (!featuredView.isEmpty()) {
            products = featuredView;
        } else {
            // 无精选产品时，回退到按列表门槛取前 8
            products = all.stream()
                        .map(PublicProductView::from)
                    .filter(p -> p.completenessScore() >= WEBSITE_LIST_MIN_SCORE)
                    .limit(6)
                    .toList();
        }
        return new HomeView(
                PublicCompanyView.from(company),
                productCount,
                products,
                companyInfoService.getMilestones().stream()
                        .filter(m -> Boolean.TRUE.equals(m.getWebsiteEnabled()))
                        .map(PublicMilestoneView::from).toList());
    }

    /**
     * 官网首页精选产品列表：独立端点，返回后台明确勾选 website_featured=true 的产品。
     * 用于首页「核心产品」区域，后台通过 website_featured 标记决定哪些产品上首页。
     */
    @GetMapping("/products/featured")
    public List<PublicProductView> featuredProducts(@RequestParam(defaultValue = "500") int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 500));
        return catalogService.getWebsiteFeaturedProducts(500).stream()
                .map(PublicProductView::from)
                .filter(p -> p.completenessScore() >= WEBSITE_FEATURED_MIN_SCORE)
                .limit(safeLimit)
                .toList();
    }

    /**
     * 官网产品中心列表：分页 + 关键词 + 分类筛选。
     * 只返回 website_enabled=true 且完整度分 ≥ WEBSITE_LIST_MIN_SCORE 的产品。
     * 为了保证分页取够 size 条，会先取 (page+1)*size*2 再按门槛过滤后截取，
     * 总量统计由全量（上限 500）过门槛的产品总数提供。
     */
    @GetMapping("/products")
    public PublicProductPage products(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String application,
            @RequestParam(required = false) String container,
            @RequestParam(required = false) String process,
            @RequestParam(required = false) String capacity,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "12") int size) {
        final int fetchSize = Math.max(size * 2, 24);
        PageRequest fetchPageable = PageRequest.of(page, fetchSize,
                Sort.by("sortOrder").ascending().and(Sort.by("id")));
        List<PublicProductView> filtered = catalogService.searchWebsiteProducts(keyword, category,
                        application, container, process, capacity, fetchPageable)
                .getContent().stream()
                .map(PublicProductView::from)
                .filter(p -> p.completenessScore() >= WEBSITE_LIST_MIN_SCORE)
                .limit(size)
                .toList();

        // 总量：全量数据（上限 500）过门槛计数 + 筛选条件已在 searchWebsiteProducts 里生效
        long total = catalogService.searchWebsiteProducts(keyword, category,
                        application, container, process, capacity,
                        PageRequest.of(0, 500)).getContent().stream()
                .mapToInt(ModelSummary::completenessScore)
                .filter(s -> s >= WEBSITE_LIST_MIN_SCORE)
                .count();
        int totalPages = total == 0 ? 0 : (int) Math.ceil((double) total / size);
        return new PublicProductPage(filtered, total, totalPages, page);
    }

    /**
     * 官网产品详情：按 slug 查询。
     * slug 不存在或未发布返回 404。
     */
    @GetMapping("/products/{slug}")
    public ResponseEntity<PublicProductDetail> productDetail(@PathVariable String slug) {
        return catalogService.getByWebsiteSlug(slug)
                .map(detail -> PublicProductDetail.from(detail,
                        productCompatibilityService.getForProduct(detail.model().id())))
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /** 官网产品分类导航：已发布产品的分类列表。V108 起合并 equipmentCategory/productLineName 为 category。 */
    @GetMapping("/product-categories")
    public PublicCategoryTree productCategories() {
        List<String> categories = catalogService.getWebsiteCategories();
        return new PublicCategoryTree(
                categories,
                categories,
                List.of("鲜食与预制菜", "生鲜肉类", "熟食与餐饮", "烘焙食品", "果蔬农产品", "其他产品"),
                List.of("托盒/餐盒", "杯/碗", "桶/罐", "袋装产品", "异形容器"),
                List.of("普通封口", "气调保鲜", "真空包装", "热收缩", "贴体包装"),
                List.of("手动/小批量", "半自动/中等产能", "全自动/高产能", "产线级"));
    }

    /** 官网包材目录：只公开在售档案，剔除价格、来源文件和内部备注。 */
    @GetMapping("/packaging")
    public List<PublicPackagingView> packaging() {
        return packagingService.search(null, null, null, "在售",
                        PageRequest.of(0, 200, Sort.by("sortOrder").ascending().and(Sort.by("id"))))
                .getContent().stream()
                .filter(item -> Boolean.TRUE.equals(item.websiteEnabled()))
                .sorted(java.util.Comparator.comparingInt(item -> item.websiteSortOrder() == null ? 0 : item.websiteSortOrder()))
                .map(PublicPackagingView::from)
                .toList();
    }

    /** 官网模具目录：只公开在用主模，剔除刻字、配件、来源和内部备注。 */
    @GetMapping("/molds")
    public List<PublicMoldView> molds() {
        return packagingMoldService.search(null, null, "main", null, "in_use", null, null,
                        PageRequest.of(0, 300, Sort.by("moldCode").ascending()))
                .getContent().stream()
                .filter(item -> Boolean.TRUE.equals(item.websiteEnabled()))
                .sorted(java.util.Comparator.comparingInt(item -> item.websiteSortOrder() == null ? 0 : item.websiteSortOrder()))
                .map(PublicMoldView::from)
                .toList();
    }

    /**
     * 官网公司详情：返回完整的公开公司信息（比 home 聚合接口字段更多，含工商信息）。
     * 用在「关于我们」和「联系我们」页面，减少重复字段冗余。
     */
    @GetMapping("/company-detail")
    public PublicCompanyView companyDetail() {
        CompanyInfo info = companyInfoService.getCompanyInfo();
        return info != null ? PublicCompanyView.from(info) : null;
    }

    /**
     * 官网里程碑列表：独立端点供「关于我们」页面使用。
     * 与 home 接口返回内容一致，但独立端点无需等待其他字段加载。
     */
    @GetMapping("/milestones")
    public List<PublicMilestoneView> milestones() {
        return companyInfoService.getMilestones().stream()
                .filter(m -> Boolean.TRUE.equals(m.getWebsiteEnabled()))
                .map(PublicMilestoneView::from)
                .toList();
    }

    /**
     * 官网官方展示的自媒体账号列表（标记为 website_official=true 的账号）。
     * 由「自媒体账号管理」后台勾选「官网官方展示」的账号会出现在这里，
     * 用于 Contact 页社交卡片与 Footer 社交图标，避免在 CompanyInfo 中重复维护链接。
     */
    @GetMapping("/official-media-accounts")
    public List<PublicMediaAccountView> officialMediaAccounts() {
        return mediaAccountRepository.findByWebsiteOfficialTrueOrderByPlatformAscIdAsc()
                .stream()
                .map(PublicMediaAccountView::from)
                .toList();
    }

    /**
     * 生成数学验证码。前端展示 question，提交时传回 captchaId + captchaAnswer。
     */
    @GetMapping("/captcha")
    public InquiryAntiSpamService.CaptchaResult captcha() {
        return antiSpamService.generateCaptcha();
    }

    /**
     * 官网询盘提交：接收 Contact 页表单数据，写入 website_inquiries 表。
     * 防刷机制：
     * 1. 蜜罐字段 website — 正常用户不会填写，机器人填充则拒绝
     * 2. 验证码 — captchaId + captchaAnswer 必须匹配
     * 3. IP 限流 — 同一 IP 每小时最多 5 次
     * 4. XSS 过滤 — 所有文本字段入库前 HTML 转义
     */
    @PostMapping("/inquiries")
    public ResponseEntity<Map<String, Object>> submitInquiry(@RequestBody InquiryRequest req,
                                                              HttpServletRequest httpRequest) {
        // 蜜罐字段：非空说明是机器人
        if (req.website() != null && !req.website().isBlank()) {
            return ResponseEntity.ok(Map.of("id", 0, "status", "submitted"));
        }

        // 验证码校验
        if (!antiSpamService.verifyCaptcha(req.captchaId(), req.captchaAnswer())) {
            return ResponseEntity.badRequest().body(Map.of("error", "验证码不正确或已过期", "code", "CAPTCHA_INVALID"));
        }

        // IP 限流
        String clientIp = getClientIp(httpRequest);
        if (!antiSpamService.checkRateLimit(clientIp)) {
            return ResponseEntity.status(429).body(Map.of("error", "提交过于频繁，请稍后再试", "code", "RATE_LIMITED"));
        }

        if (isBlank(req.name()) || isBlank(req.phone()) || isBlank(req.description())) {
            return ResponseEntity.badRequest().body(Map.of("error", "联系人、电话和需求描述为必填项"));
        }
        if (req.name().length() > 100 || req.phone().length() > 50 || req.description().length() > 5000) {
            return ResponseEntity.badRequest().body(Map.of("error", "字段长度超出限制"));
        }

        WebsiteInquiry inquiry = new WebsiteInquiry();
        inquiry.setName(escapeHtml(req.name().trim()));
        inquiry.setCompany(escapeHtml(trimToNull(req.company(), 200)));
        inquiry.setPhone(req.phone().trim());
        inquiry.setEmail(trimToNull(req.email(), 100));
        inquiry.setProductFocus(escapeHtml(trimToNull(req.product(), 100)));
        inquiry.setDescription(escapeHtml(req.description().trim()));
        inquiry.setSourcePage(trimToNull(req.sourcePage(), 200));
        WebsiteInquiry saved = inquiryRepository.save(inquiry);

        return ResponseEntity.ok(Map.of("id", saved.getId(), "status", "submitted"));
    }

    /** 从请求中提取客户端 IP，支持反向代理。 */
    private static String getClientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) return realIp.trim();
        return request.getRemoteAddr();
    }

    /** HTML 转义，防止存储型 XSS。 */
    private static String escapeHtml(String s) {
        if (s == null) return null;
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String trimToNull(String s, int maxLen) {
        if (s == null || s.isBlank()) return null;
        String trimmed = s.trim();
        return trimmed.length() > maxLen ? trimmed.substring(0, maxLen) : trimmed;
    }

    /** 询盘提交请求体。website 为蜜罐字段，captchaId/captchaAnswer 为验证码。 */
    public record InquiryRequest(String name, String company, String phone,
                                  String email, String product, String description,
                                  String sourcePage,
                                  String website,
                                  String captchaId, String captchaAnswer) {}

    public record HomeView(PublicCompanyView company, long productCount, List<PublicProductView> products,
                           List<PublicMilestoneView> milestones) {}

    public record PublicProductPage(List<PublicProductView> items, long total, int totalPages, int page) {}

    public record PublicCategoryTree(List<String> equipmentCategories, List<String> productLines,
                                     List<String> applications, List<String> containers,
                                     List<String> processes, List<String> capacityLevels) {}

    public record PublicPackagingView(Long id, String name, String modelCode, String packagingKind,
                                      String filmForm, String shapeType, String material,
                                      String colorStyle, String capacityMl, String applicable,
                                      Boolean customizable, String tags, Long defaultImageAssetId,
                                      long variantCount) {
        static PublicPackagingView from(com.liqiang.archive.dto.PackagingDtos.ModelSummary item) {
            return new PublicPackagingView(item.id(), item.name(), item.modelCode(), item.packagingKind(),
                    item.filmForm(), item.shapeType(), item.material(), item.colorStyle(),
                    item.capacityMl(), item.applicable(), item.customizable(), item.tags(),
                    item.defaultImageAssetId(), item.variantCount());
        }
    }

    public record PublicMoldView(Long id, String modelName, String groupName, String moldCode,
                                 String moldTypeCode, Integer cavityCount, String moldCategory,
                                 String compartmentCount, String surfaceStructure,
                                 String representativeCaliber, String representativeHeight) {
        static PublicMoldView from(MoldSummary item) {
            return new PublicMoldView(item.id(), item.modelName(), item.groupName(), item.moldCode(),
                    item.moldTypeCode(), item.cavityCount(), item.moldCategory(),
                    item.compartmentCount(), item.surfaceStructure(),
                    item.representativeCaliber(), item.representativeHeight());
        }
    }

    public record PublicCompanyView(String companyName, String companyNameEn, String brandName,
                                    String brandNameEn, String brandSlogan, String mission, String vision,
                                    String values, String corePositioning, String mainBusiness,
                                    String targetIndustries, String advantages, String manufacturingStrengths,
                                    String enterpriseScale,
                                    LocalDate establishmentDate,
                                    String officeAddress,
                                    String factoryAddress, String phone, String email, String website) {
        static PublicCompanyView from(CompanyInfo info) {
            return new PublicCompanyView(info.getCompanyName(), info.getCompanyNameEn(), info.getBrandName(),
                    info.getBrandNameEn(), info.getBrandSlogan(), info.getMission(), info.getVision(),
                    info.getValues(), info.getCorePositioning(), info.getMainBusiness(),
                    info.getTargetIndustries(), info.getAdvantages(),
                    info.getManufacturingStrengths(),
                    info.getEnterpriseScale(),
                    info.getEstablishmentDate(),
                    info.getOfficeAddress(),
                    info.getFactoryAddress(), info.getPhone(), info.getEmail(), info.getWebsite());
        }
    }

    /**
     * 官网公开产品视图。字段为 ModelSummary 子集，剔除 sourceFile/sourceSheet/importBatchId/
     * referenceDistributorPrice 等内部敏感字段，新增 websiteSlug/websiteSummary。
     */
    public record PublicProductView(Long id, String name, String modelCode, String category,
                                    String status, String defaultImage,
                                    Long defaultImageAssetId, long variantCount,
                                    String websiteSlug, String websiteSummary,
                                    List<String> applicationTags, List<String> containerTags,
                                    List<String> packagingProcessTags, String capacityLevel,
                                    int completenessScore) {
        static PublicProductView from(ModelSummary model) {
            return new PublicProductView(model.id(), model.name(), model.modelCode(), model.category(),
                    model.status(), model.defaultImage(),
                    model.defaultImageAssetId(), model.variantCount(),
                    model.websiteSlug(), model.websiteSummary(), model.applicationTags(),
                    model.containerTags(), model.packagingProcessTags(), model.capacityLevel(),
                    model.completenessScore());
        }
    }

    /**
     * 官网产品详情视图。基于 ModelDetail 脱敏：
     * - 剔除 referenceDistributorPrice / referenceRetailPrice / pricingNotes
     * - 剔除 sourceFile / sourceSheet / sourceRowNumber / importBatchId
     * - 剔除 workshop / shopUrl / internal remark
     * 保留：基础信息 + 规格 + 适用行业 + 营销文案 + 图库 + 变体规格。
     */
    public record PublicProductDetail(PublicProductModelView model,
                                      List<PublicVariantView> variants,
                                      List<PublicAssetView> gallery,
                                      long variantCount,
                                      List<PublicCompatiblePackagingView> compatiblePackaging,
                                      List<PublicCompatibleMoldView> compatibleMolds) {
        static PublicProductDetail from(ModelDetail detail,
                                        WebsiteProductCompatibilityService.Snapshot compatibility) {
            ModelView m = detail.model();
            // 官网图库：只展示 website_visible=true 的素材，按 websiteSortOrder 升序排序
            List<PublicAssetView> gallery = detail.assets().stream()
                    .filter(a -> a.websiteVisible() == null || a.websiteVisible())
                    .sorted(java.util.Comparator.comparingInt(a -> 
                            a.websiteSortOrder() == null ? 0 : a.websiteSortOrder()))
                    .map(PublicAssetView::from)
                    .toList();
            return new PublicProductDetail(
                    PublicProductModelView.from(m),
                    detail.variants().stream().map(PublicVariantView::from).toList(),
                    gallery,
                    detail.variantCount(),
                    compatibility.packaging().stream().map(PublicCompatiblePackagingView::from).toList(),
                    compatibility.molds().stream().map(PublicCompatibleMoldView::from).toList());
        }
    }

    /** 官网兼容包材视图：只公开选型所需字段，不包含采购价、供应商及内部备注。 */
    public record PublicCompatiblePackagingView(Long productVariantId, String productVariantName,
                                                Long packagingModelId, String packagingModelName,
                                                String packagingKind, String material, String shapeType,
                                                Long packagingVariantId, String packagingVariantName,
                                                String apertureLabel, String capacityMl, Boolean customizable) {
        static PublicCompatiblePackagingView from(
                WebsiteProductCompatibilityService.CompatiblePackaging item) {
            return new PublicCompatiblePackagingView(item.productVariantId(), item.productVariantName(),
                    item.packagingModelId(), item.packagingModelName(), item.packagingKind(), item.material(),
                    item.shapeType(), item.packagingVariantId(), item.packagingVariantName(),
                    item.apertureLabel(), item.capacityMl(), item.customizable());
        }
    }

    /** 官网可配模具视图：隐藏模具编码，仅保留客户判断适配关系所需的信息。 */
    public record PublicCompatibleMoldView(Long packagingVariantId, String packagingModelName,
                                           String packagingVariantName, Long moldId, String moldCategory,
                                           Integer cavityCount, String relationType, Boolean defaultMold,
                                           String apertureLabel) {
        static PublicCompatibleMoldView from(WebsiteProductCompatibilityService.CompatibleMold item) {
            return new PublicCompatibleMoldView(item.packagingVariantId(), item.packagingModelName(), item.packagingVariantName(),
                    item.moldId(), item.moldCategory(), item.cavityCount(), item.relationType(),
                    item.defaultMold(), item.apertureLabel());
        }
    }

    public record PublicProductModelView(Long id, String name, String nameEn, String modelCode,
                                         String category, String status,
                                         String applicationScope,
                                         String descriptionCn, String descriptionEn,
                                         String introductionCn, String introductionEn,
                                         String websiteSlug, String websiteSummary,
                                         List<String> applicationTags, List<String> containerTags,
                                         List<String> packagingProcessTags, String capacityLevel) {
        static PublicProductModelView from(ModelView m) {
            return new PublicProductModelView(m.id(), m.name(), m.nameEn(), m.modelCode(),
                    m.category(), m.status(),
                    m.applicationScope(),
                    m.descriptionCn(), m.descriptionEn(),
                    m.introductionCn(), m.introductionEn(),
                    m.websiteSlug(), m.websiteSummary(), m.applicationTags(),
                    m.containerTags(), m.packagingProcessTags(), m.capacityLevel());
        }
    }

    /**
     * 官网公开变体视图。剔除 dealerPrice/retailPrice/sourceFile 等内部字段。
     * 保留规格参数 + 模具几何 + 包装尺寸等展示字段。
     */
    public record PublicVariantView(Long id, String variantName, String specKey, Boolean baseline,
                                    String moldSizeType, String moldLengthMm, String moldWidthMm,
                                    String moldDiameterMm, String moldSizeLabel, String remark) {
        static PublicVariantView from(VariantView v) {
            return new PublicVariantView(v.id(), v.variantName(), v.specKey(), v.baseline(),
                    v.moldSizeType(), v.moldLengthMm(), v.moldWidthMm(),
                    v.moldDiameterMm(), v.moldSizeLabel(), v.remark());
        }
    }

    /** 官网公开素材视图。只保留展示所需字段。sortOrder 用于前台图库排序。 */
    public record PublicAssetView(Long id, String category, String fileName, String tags,
                                  String coverFileName, Integer sortOrder) {
        static PublicAssetView from(AssetView a) {
            return new PublicAssetView(a.id(), a.category(), a.fileName(), a.tags(),
                    a.coverFileName(), a.websiteSortOrder());
        }
    }

    public record PublicMilestoneView(Integer year, String title, String description) {
        static PublicMilestoneView from(CompanyMilestone item) {
            return new PublicMilestoneView(item.getYear(), item.getTitle(), item.getDescription());
        }
    }

    /**
     * 官网公开自媒体账号视图。只返回平台、账号名、链接等对外展示字段，
     * 剔除内部运营信息（运营人、发布负责人、粉丝/获赞/视频数、更新时间等）。
     */
    public record PublicMediaAccountView(Long id, String platform, String accountName, String link) {
        static PublicMediaAccountView from(MediaAccount account) {
            return new PublicMediaAccountView(
                    account.getId(),
                    account.getPlatform(),
                    account.getAccountName(),
                    account.getLink());
        }
    }

    /**
     * 官网公开知识库分类视图：含「已发布 + 官网可见」的文章数量。
     */
    public record PublicKnowledgeCategoryView(Long id, String name, String nameEn, long articleCount) {
        static PublicKnowledgeCategoryView from(KnowledgeCategory category, long articleCount) {
            return new PublicKnowledgeCategoryView(category.getId(), category.getName(),
                    category.getNameEn(), articleCount);
        }
    }

    /**
     * 官网公开知识文章卡片视图（列表用）：脱敏，只回摘要，不含全文。
     */
    public record PublicKnowledgeArticleView(Long id, Long categoryId, String categoryName, String title,
                                             String summary, List<String> tags, String author,
                                             Integer viewCount, java.time.LocalDateTime updatedAt) {
        static PublicKnowledgeArticleView from(KnowledgeArticle article, String categoryName) {
            return new PublicKnowledgeArticleView(article.getId(), article.getCategoryId(), categoryName,
                    article.getTitle(), plainSummary(article.getContent()),
                    splitTags(article.getTags()), article.getAuthor(),
                    article.getViewCount(), article.getUpdatedAt());
        }
    }

    /**
     * 官网公开知识文章详情视图：含全文，不含来源文件、内部备注等。
     */
    public record PublicKnowledgeArticleDetailView(Long id, Long categoryId, String categoryName, String title,
                                                   String content, List<String> tags, String author,
                                                   Integer viewCount, java.time.LocalDateTime createdAt,
                                                   java.time.LocalDateTime updatedAt) {
        static PublicKnowledgeArticleDetailView from(KnowledgeArticle article, String categoryName) {
            return new PublicKnowledgeArticleDetailView(article.getId(), article.getCategoryId(), categoryName,
                    article.getTitle(), article.getContent(), splitTags(article.getTags()),
                    article.getAuthor(), article.getViewCount(), article.getCreatedAt(), article.getUpdatedAt());
        }
    }

    /** 官网公开知识库分类：仅包含有「已发布 + 官网可见」文章的分类。 */
    @GetMapping("/knowledge-categories")
    public List<PublicKnowledgeCategoryView> knowledgeCategories() {
        List<KnowledgeCategory> categories = companyInfoService.getKbCategories();
        List<KnowledgeArticle> visibleArticles = companyInfoService.getWebsiteKbArticles(null, null);
        Map<Long, Long> counts = visibleArticles.stream()
                .collect(java.util.stream.Collectors.groupingBy(KnowledgeArticle::getCategoryId, java.util.stream.Collectors.counting()));
        return categories.stream()
                .filter(c -> counts.getOrDefault(c.getId(), 0L) > 0)
                .map(c -> PublicKnowledgeCategoryView.from(c, counts.getOrDefault(c.getId(), 0L)))
                .toList();
    }

    /** 官网知识文章列表：仅返回「已发布 + 官网可见」的文章，支持分类与关键词过滤。 */
    @GetMapping("/knowledge-articles")
    public List<PublicKnowledgeArticleView> knowledgeArticles(
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) String keyword) {
        Map<Long, String> categoryNames = companyInfoService.getKbCategories().stream()
                .collect(java.util.stream.Collectors.toMap(KnowledgeCategory::getId, KnowledgeCategory::getName));
        return companyInfoService.getWebsiteKbArticles(categoryId, keyword).stream()
                .map(a -> PublicKnowledgeArticleView.from(a,
                        categoryNames.getOrDefault(a.getCategoryId(), "技术知识")))
                .toList();
    }

    /** 官网知识文章详情：仅返回「已发布 + 官网可见」的文章，并累加浏览量。 */
    @GetMapping("/knowledge-articles/{id}")
    public ResponseEntity<PublicKnowledgeArticleDetailView> knowledgeArticleDetail(@PathVariable Long id) {
        KnowledgeArticle article = companyInfoService.getWebsiteKbArticleDetail(id);
        if (article == null) {
            return ResponseEntity.notFound().build();
        }
        String categoryName = companyInfoService.getKbCategories().stream()
                .filter(c -> c.getId().equals(article.getCategoryId()))
                .map(KnowledgeCategory::getName)
                .findFirst().orElse("技术知识");
        return ResponseEntity.ok(PublicKnowledgeArticleDetailView.from(article, categoryName));
    }

    /** 从 Markdown 正文提取纯文本摘要（去掉标题/列表标记，取前 140 字符）。 */
    private static String plainSummary(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            return "";
        }
        String text = markdown
                .replaceAll("(?m)^#{1,6}\\s+", "")
                .replaceAll("(?m)^[-*]\\s+", "")
                .replaceAll("[*_`#>|\\[\\]()]", "")
                .replaceAll("\\s+", " ")
                .trim();
        return text.length() > 140 ? text.substring(0, 140) + "…" : text;
    }

    /** 逗号分隔的标签字符串 -> 标签列表。 */
    private static List<String> splitTags(String tags) {
        if (tags == null || tags.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(tags.split("[,，]"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }
}
