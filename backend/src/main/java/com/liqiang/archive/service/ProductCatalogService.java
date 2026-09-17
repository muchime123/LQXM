package com.liqiang.archive.service;

import com.liqiang.archive.dto.CatalogDtos.AssetVariantAssignment;
import com.liqiang.archive.dto.CatalogDtos.AssetMetadataRequest;
import com.liqiang.archive.dto.CatalogDtos.AssetView;
import com.liqiang.archive.dto.CatalogDtos.BatchAssetTagsRequest;
import com.liqiang.archive.dto.CatalogDtos.BatchAssetMetadataRequest;
import com.liqiang.archive.dto.CatalogDtos.BatchAssetVariantRequest;
import com.liqiang.archive.dto.CatalogDtos.BatchDeleteRequest;
import com.liqiang.archive.dto.CatalogDtos.BatchResult;
import com.liqiang.archive.dto.CatalogDtos.BatchUpdateRequest;
import com.liqiang.archive.dto.CatalogDtos.ModelDetail;
import com.liqiang.archive.dto.CatalogDtos.ModelSimpleRow;
import com.liqiang.archive.dto.CatalogDtos.ModelSummary;
import com.liqiang.archive.dto.CatalogDtos.ModelView;
import com.liqiang.archive.dto.CatalogDtos.MoveAssetRequest;
import com.liqiang.archive.dto.CatalogDtos.ReorderItem;
import com.liqiang.archive.dto.CatalogDtos.VideoAssetView;
import com.liqiang.archive.entity.ProductAsset;
import com.liqiang.archive.entity.ProductModel;
import com.liqiang.archive.entity.ProductMachineTier;
import com.liqiang.archive.entity.ProductVariant;
import com.liqiang.archive.repository.ProductAssetRepository;
import com.liqiang.archive.repository.ProductModelRepository;
import com.liqiang.archive.repository.ProductMachineTierRepository;
import com.liqiang.archive.repository.ProductVariantRepository;
import com.liqiang.archive.util.VideoGovernanceRules;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 产品目录核心 CRUD Service：Models / Variants / Assets
 *
 * <p>原 ProductCatalogService 已按业务领域拆分：
 * <ul>
 *   <li>{@link CatalogStatsExportService} — 统计、健康度、导出、重复检测</li>
 *   <li>{@link CatalogTrashService} — 回收站查询、恢复、彻底删除</li>
 *   <li>{@link CatalogImportService} — Excel 导入</li>
 * </ul>
 * 相关 Controller 应直接调用对应的 Service。
 */
@Service
@RequiredArgsConstructor
@SuppressWarnings("null")
public class ProductCatalogService {

    private final ProductModelRepository modelRepository;
    private final ProductVariantRepository variantRepository;
    private final ProductAssetRepository assetRepository;
    private final ProductMachineTierRepository machineTierRepository;
    private final FileStorageService fileStorageService;
    private final AuditLogService auditLogService;
    private final VideoPersonalWorkspaceService videoWorkspaceService;

    private static final Set<String> PRODUCT_ASSET_CATEGORIES = Set.of(
            "main_image", "scene_image", "detail_image", "parameter_image",
            "sku_image", "manual", "device_video", "operation_video",
            "brochure", "drawing", "certificate"
    );

    private static final Set<String> VARIANT_THUMBNAIL_CATEGORIES = Set.of(
            "main_image", "scene_image", "detail_image", "parameter_image", "sku_image"
    );

    /**
     * ProductModel 可批量更新的字段白名单 —— 必须与 applyModelPayload 中的 setter 逐一对应，
     * 否则批量更新接口会被白名单静默拦截。
     * V108 起 V1 旧字段全部删除，仅保留 V2 字段。
     */
    private static final Set<String> MODEL_UPDATEABLE_FIELDS = Set.of(
            // 身份/分类
            "name", "nameEn", "modelCode",
            // V2 字段
            "category", "descriptionCn", "descriptionEn", "status",
            // 店铺链接（V121 重新加回）
            "shopUrl",
            // 适用范围
            "applicationScope",
            // 营销介绍
            "introductionCn", "introductionEn",
            "searchKeywordsCn", "searchKeywordsEn",
            "remark",
            // 资源/排序
            "defaultImage", "sortOrder"
    );

    /**
     * 官网发布字段批量白名单。Boolean 字段（websiteEnabled / websiteFeatured）通过
     * {@link #batchUpdateWebsiteFields(List, Map)} 单独处理，不走通用 {@link #applyModelUpdates}，
     * 因为后者只支持 String setter。websiteSlug 唯一性、精选依赖发布的联动规则在批量方法内统一校验。
     */
    private static final Set<String> WEBSITE_BATCH_FIELDS = Set.of(
            "websiteEnabled", "websiteFeatured", "websiteSlug", "websiteSummary"
    );

    @Transactional(readOnly = true)
    public Page<ModelSummary> search(String keyword, Pageable pageable) {
        return mapToSummaryPage(modelRepository.search(keyword == null ? "" : keyword.trim(), pageable));
    }

    @Transactional(readOnly = true)
    public Page<ModelSummary> searchFiltered(String keyword, String category, String status,
                                              String listFilter, Pageable pageable) {
        return mapToSummaryPage(modelRepository.searchFiltered(keyword, category, status, listFilter, pageable));
    }

    /**
     * 官网产品中心列表：只返回 website_enabled=true 的产品。
     * 支持关键词 + 分类筛选，复用 mapToSummaryPage 批量预取资产/变体数。
     */
    @Transactional(readOnly = true)
    public Page<ModelSummary> searchWebsiteProducts(String keyword, String category,
                                                     String application, String container, String process,
                                                     String capacity, Pageable pageable) {
        Pageable nativePageable = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize());
        return mapToSummaryPage(modelRepository.searchWebsiteProducts(
                cleanFilter(keyword), cleanFilter(category),
                cleanFilter(application), cleanFilter(container), cleanFilter(process), cleanFilter(capacity), nativePageable));
    }

    private String cleanFilter(String value) {
        return value == null ? "" : value.trim();
    }

    /** 官网已发布产品的分类列表（用于产品中心 Tab）。V108 起合并为 category。 */
    @Transactional(readOnly = true)
    public List<String> getWebsiteCategories() {
        return modelRepository.findWebsiteCategories();
    }

    /**
     * 官网首页精选产品：website_enabled=true AND website_featured=true。
     * 后台通过 website_featured 标记决定哪些产品上首页。
     * 复用 mapToSummaryPage 批量预取资产/变体数。
     */
    @Transactional(readOnly = true)
    public List<ModelSummary> getWebsiteFeaturedProducts(int limit) {
        List<ProductModel> featured = modelRepository.findWebsiteFeaturedProducts(PageRequest.of(0, limit));
        if (featured.isEmpty()) {
            return List.of();
        }
        // 复用 mapToSummaryPage：构造一个内存分页以共享批量预取逻辑
        Page<ProductModel> page = new PageImpl<>(featured, PageRequest.of(0, featured.size()), featured.size());
        return mapToSummaryPage(page).getContent();
    }

    /**
     * 按 slug 查询官网产品详情。slug 不存在或未发布返回空。
     * 复用 get(id) 的详情聚合能力。
     */
    @Transactional(readOnly = true)
    public Optional<ModelDetail> getByWebsiteSlug(String slug) {
        return modelRepository.findByWebsiteSlug(slug)
                .filter(m -> Boolean.TRUE.equals(m.getWebsiteEnabled()))
                .map(m -> get(m.getId()));
    }

    /** 将 Page<ProductModel> 转为 Page<ModelSummary>，批量预取配置/素材/SKU 图数量，避免 N+1 */
    private Page<ModelSummary> mapToSummaryPage(Page<ProductModel> page) {
        List<ProductModel> models = page.getContent();
        if (models.isEmpty()) {
            return page.map(m -> ModelSummary.from(m, 0L, 0L, 0L));
        }
        List<Long> modelIds = models.stream().map(ProductModel::getId).toList();
        Map<Long, Long> variantCountMap = machineTierRepository.countByModelIds(modelIds).stream()
                .collect(Collectors.toMap(arr -> (Long) arr[0], arr -> (Long) arr[1]));
        Map<Long, Map<String, Long>> assetCatMap = new HashMap<>();
        assetRepository.countByModelIdsGroupByCategory(modelIds).forEach(arr -> {
            Long mid = (Long) arr[0];
            String cat = (String) arr[1];
            Long cnt = (Long) arr[2];
            assetCatMap.computeIfAbsent(mid, k -> new HashMap<>()).put(cat, cnt);
        });

        // 批量预取 defaultImage 对应的素材 ID，避免循环内 N+1 查询
        Map<Long, Map<String, ProductAsset>> defaultImageAssetMap = new HashMap<>();
        List<ProductAsset> mainImageAssets = assetRepository.findAllByModelIdsAndCategory(modelIds, "main_image");
        for (ProductAsset asset : mainImageAssets) {
            defaultImageAssetMap.computeIfAbsent(asset.getModel().getId(), k -> new HashMap<>())
                    .put(asset.getFileName(), asset);
        }

        return page.map(m -> {
            Long mid = m.getId();
            long variantCount = variantCountMap.getOrDefault(mid, 0L);
            Map<String, Long> cats = assetCatMap.getOrDefault(mid, Map.of());
            long assetCount = cats.values().stream().mapToLong(Long::longValue).sum();
            long skuImageCount = cats.getOrDefault("sku_image", 0L);
            Long defaultImageAssetId = null;
            String defaultImageOwnerType = null;
            if (m.getDefaultImage() != null && !m.getDefaultImage().isBlank()) {
                Map<String, ProductAsset> modelImageMap = defaultImageAssetMap.get(mid);
                if (modelImageMap != null) {
                    ProductAsset defaultAsset = modelImageMap.get(m.getDefaultImage());
                    if (defaultAsset != null) {
                        defaultImageAssetId = defaultAsset.getId();
                        defaultImageOwnerType = "product_model";
                    }
                }
            }
            return ModelSummary.from(m, variantCount, assetCount, skuImageCount,
                    defaultImageAssetId, defaultImageOwnerType);
        });
    }

    @Transactional(readOnly = true)
    public Map<String, List<String>> getFilterOptions() {
        return Map.of(
                "categories", modelRepository.findDistinctCategories(),
                "statuses", modelRepository.findDistinctStatuses()
        );
    }

    @Transactional(readOnly = true)
    public List<ModelSimpleRow> listAllSimple() {
        return modelRepository.findAll().stream()
                .map(m -> new ModelSimpleRow(
                        m.getId(),
                        m.getName(),
                        m.getCategory(),
                        m.getDefaultImage()
                ))
                .toList();
    }

    @Transactional(readOnly = true)
    public ModelDetail get(Long id) {
        ProductModel model = modelRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("产品主档不存在: " + id));
        List<ProductAsset> assets = assetRepository.findByModelIdOrderBySortOrderAscIdAsc(id);
        int machineTierCount = machineTierRepository.findByModelIdOrderBySortOrderAscIdAsc(id).size();
        return new ModelDetail(ModelView.from(model), List.of(),
                assets.stream().map(AssetView::from).toList(),
                machineTierCount, assets.size());
    }

    @Transactional
    public ModelView createModel(ProductModel payload) {
        ProductModel model = new ProductModel();
        applyModelPayload(model, payload);
        if (model.getName() == null || model.getName().isBlank()) {
            model.setName("未命名主产品");
        }
        if (model.getSortOrder() == null || model.getSortOrder() <= 0) {
            model.setSortOrder(modelRepository.findMaxSortOrder() + 10);
        }
        applyWebsiteLinkageRules(model);
        ProductModel saved = modelRepository.save(model);
        ensureModelDefaultAsset(saved, "main_image", saved.getDefaultImage());
        auditLogService.log("model", saved.getId(), saved.getName(), "create",
                "新建产品：" + saved.getName());
        return ModelView.from(saved);
    }

    @Transactional
    public ModelView updateModel(Long id, ProductModel payload) {
        ProductModel model = modelRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("产品主档不存在: " + id));
        String oldName = model.getName();
        applyModelPayload(model, payload);
        applyWebsiteLinkageRules(model);
        checkWebsiteSlugUniqueness(List.of(model));
        ProductModel saved = modelRepository.save(model);
        ensureModelDefaultAsset(saved, "main_image", saved.getDefaultImage());
        auditLogService.log("model", saved.getId(), saved.getName(), "update",
                "更新产品信息" + (oldName != null && !oldName.equals(saved.getName()) ? "，名称变更：" + oldName + " → " + saved.getName() : ""));
        return ModelView.from(saved);
    }

    @Transactional
    public void deleteModel(Long id) {
        ProductModel model = modelRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("产品主档不存在: " + id));
        String modelName = model.getName();
        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        // 软删除子资源（批量 saveAll 避免 N+1 写入）
        List<ProductAsset> assets = assetRepository.findByModelIdOrderBySortOrderAscIdAsc(id);
        assets.forEach(a -> a.setDeletedAt(now));
        assetRepository.saveAll(assets);
        List<ProductVariant> variants = variantRepository.findByModelIdOrderBySortOrderAsc(id);
        variants.forEach(v -> v.setDeletedAt(now));
        variantRepository.saveAll(variants);
        // 软删除主产品
        model.setDeletedAt(now);
        modelRepository.save(model);
        auditLogService.log("model", id, modelName, "delete",
                "软删除产品：" + modelName + "（含全部配置和素材，可在回收站恢复）");
    }

    private void applyModelPayload(ProductModel model, ProductModel payload) {
        if (payload.getName() != null) model.setName(payload.getName());
        if (payload.getNameEn() != null) model.setNameEn(payload.getNameEn());
        if (payload.getModelCode() != null) model.setModelCode(payload.getModelCode());
        // ====== V2 字段（V108 起 V1 旧字段已删除） ======
        if (payload.getCategory() != null) model.setCategory(payload.getCategory());
        if (payload.getDescriptionCn() != null) model.setDescriptionCn(payload.getDescriptionCn());
        if (payload.getDescriptionEn() != null) model.setDescriptionEn(payload.getDescriptionEn());
        if (payload.getStatus() != null) model.setStatus(payload.getStatus());
        if (payload.getShopUrl() != null) model.setShopUrl(payload.getShopUrl());
        if (payload.getApplicationScope() != null) model.setApplicationScope(payload.getApplicationScope());
        if (payload.getDefaultImage() != null) model.setDefaultImage(payload.getDefaultImage());
        if (payload.getRemark() != null) model.setRemark(payload.getRemark());
        if (payload.getIntroductionCn() != null) model.setIntroductionCn(payload.getIntroductionCn());
        if (payload.getIntroductionEn() != null) model.setIntroductionEn(payload.getIntroductionEn());
        if (payload.getSearchKeywordsCn() != null) model.setSearchKeywordsCn(payload.getSearchKeywordsCn());
        if (payload.getSearchKeywordsEn() != null) model.setSearchKeywordsEn(payload.getSearchKeywordsEn());
        if (payload.getSortOrder() != null) model.setSortOrder(payload.getSortOrder());
        // 官网发布字段
        if (payload.getWebsiteEnabled() != null) model.setWebsiteEnabled(payload.getWebsiteEnabled());
        if (payload.getWebsiteFeatured() != null) model.setWebsiteFeatured(payload.getWebsiteFeatured());
        if (payload.getWebsiteSlug() != null) model.setWebsiteSlug(payload.getWebsiteSlug());
        if (payload.getWebsiteSummary() != null) model.setWebsiteSummary(payload.getWebsiteSummary());
        if (payload.getApplicationTags() != null) model.setApplicationTags(payload.getApplicationTags());
        if (payload.getContainerTags() != null) model.setContainerTags(payload.getContainerTags());
        if (payload.getPackagingProcessTags() != null) model.setPackagingProcessTags(payload.getPackagingProcessTags());
        if (payload.getCapacityLevel() != null) model.setCapacityLevel(payload.getCapacityLevel());
    }

    @Transactional
    public AssetView moveAsset(Long assetId, MoveAssetRequest request) {
        ProductAsset asset = assetRepository.findById(assetId)
                .orElseThrow(() -> new EntityNotFoundException("素材不存在: " + assetId));
        ProductModel targetModel = modelRepository.findById(request.targetModelId())
                .orElseThrow(() -> new EntityNotFoundException("目标产品主档不存在: " + request.targetModelId()));
        ProductModel sourceModel = asset.getModel();
        ProductVariant sourceVariant = asset.getVariant();
        String sourceCategory = asset.getCategory();
        String targetCategory = request.targetCategory() == null || request.targetCategory().isBlank()
                ? sourceCategory
                : request.targetCategory().trim();
        boolean categoryChanged = !sourceCategory.equals(targetCategory);
        boolean variantChanged = !Objects.equals(
                sourceVariant == null ? null : sourceVariant.getId(), request.targetVariantId());
        if (sourceModel.getId().equals(targetModel.getId()) && !categoryChanged && !variantChanged) {
            throw new IllegalArgumentException("素材归属和分类未发生变化");
        }

        final boolean needMoveFile = !sourceModel.getId().equals(targetModel.getId()) || categoryChanged;
        final String moveFromCategory = sourceCategory;
        final String moveToCategory = targetCategory;
        if (needMoveFile) {
            try {
                fileStorageService.moveFile(
                        sourceModel.getId(), moveFromCategory,
                        targetModel.getId(), moveToCategory,
                        asset.getFileName());
            } catch (java.io.IOException e) {
                throw new RuntimeException("物理文件移动失败：" + e.getMessage(), e);
            }
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCompletion(int status) {
                            if (status == STATUS_ROLLED_BACK) {
                                try {
                                    fileStorageService.moveFile(
                                            targetModel.getId(), moveToCategory,
                                            sourceModel.getId(), moveFromCategory,
                                            asset.getFileName());
                                } catch (Exception ignored) {
                                }
                            }
                        }
                    });
        }

        asset.setModel(targetModel);
        asset.setCategory(targetCategory);
        if (request.targetVariantId() != null && canAssociateSpecification(targetCategory)) {
            ProductVariant targetVariant = variantRepository.findById(request.targetVariantId())
                    .orElseThrow(() -> new EntityNotFoundException("目标配置不存在: " + request.targetVariantId()));
            if (!targetVariant.getModel().getId().equals(targetModel.getId())) {
                throw new IllegalArgumentException("目标配置不属于目标产品");
            }
            assignAssetToVariant(asset, targetVariant);
        } else {
            assignAssetToVariant(asset, null);
        }
        asset.setDefaultAsset(false);
        ProductAsset saved = assetRepository.save(asset);

        refreshSourceDefaultAfterAssetMove(sourceModel, sourceVariant, sourceCategory, asset.getFileName());
        refreshTargetDefaultAfterAssetMove(targetModel, targetCategory, saved);

        auditLogService.log("asset", saved.getId(), targetModel.getName(), "move",
                "素材移动：" + asset.getFileName() + " → 产品「" + targetModel.getName() + "」分类「" + targetCategory + "」");
        return AssetView.from(saved);
    }

    @Transactional
    public ProductAsset updateAssetTags(Long assetId, String tags) {
        ProductAsset asset = assetRepository.findById(assetId)
                .orElseThrow(() -> new EntityNotFoundException("素材不存在: " + assetId));
        asset.setTags(normalizeAssetTags(tags));
        ProductAsset saved = assetRepository.save(asset);
        auditLogService.log("asset", saved.getId(), saved.getModel().getName(), "update-tags",
                "更新素材内容标签：" + saved.getFileName());
        return saved;
    }

    /** Updates content tags and specification ownership as independent concerns. */
    @Transactional
    public AssetView updateAssetMetadata(Long assetId, AssetMetadataRequest request) {
        ProductAsset asset = assetRepository.findById(assetId)
                .orElseThrow(() -> new EntityNotFoundException("素材不存在: " + assetId));
        String previousVideoStatus = asset.getVideoContentStatus();
        asset.setTags(normalizeAssetTags(request.contentTags()));
        if (request.machineTierAction() != null) {
            applyMachineTierAction(asset, request.machineTierAction(), request.machineTierIds(), request.machineTierId());
        }
        if (request.videoTitle() != null || request.machineScopeConfirmed() != null) {
            if (request.videoTitle() != null) asset.setVideoTitle(request.videoTitle());
            if (request.machineScopeConfirmed() != null) asset.setMachineScopeConfirmed(request.machineScopeConfirmed());
        }
        if (request.videoFavorite() != null
                && Set.of("device_video", "operation_video").contains(asset.getCategory())) {
            asset.setVideoFavorite(request.videoFavorite());
        }
        if (request.applicationTags() != null
                || request.videoContentType() != null
                || request.videoUsageStatus() != null
                || request.featureTags() != null
                || request.demonstrationTags() != null
                || request.videoUsageChannels() != null
                || request.videoContentStatus() != null) {
            applyVideoMetadata(asset, request.applicationTags(), request.videoContentType(), request.videoUsageStatus(),
                    request.featureTags(), request.demonstrationTags(), request.videoUsageChannels(), request.videoContentStatus());
        }
        validateVideoGovernance(asset, previousVideoStatus, request.videoContentStatus() != null);
        ProductAsset saved = assetRepository.save(asset);
        auditLogService.log("asset", saved.getId(), saved.getModel().getName(), "update-metadata",
                "更新素材内容标签与适用机型：" + saved.getFileName());
        return AssetView.from(saved);
    }

    @Transactional
    public BatchResult updateAssetTagsBatch(BatchAssetTagsRequest request) {
        if (request.ids() == null || request.ids().isEmpty()) return new BatchResult(0);
        String incomingTags = normalizeAssetTags(request.tags());
        List<ProductAsset> assets = assetRepository.findAllById(request.ids());
        for (ProductAsset asset : assets) {
            String previousVideoStatus = asset.getVideoContentStatus();
            String nextTags = request.replace()
                    ? incomingTags
                    : mergeAssetTags(asset.getTags(), incomingTags);
            asset.setTags(normalizeAssetTags(nextTags));
        }
        assetRepository.saveAll(assets);
        auditLogService.log("asset", 0L, "批量", "batch-update-tags",
                "批量更新 " + assets.size() + " 个素材标签");
        return new BatchResult(assets.size());
    }

    @Transactional
    public BatchResult updateAssetMetadataBatch(BatchAssetMetadataRequest request) {
        if (request.ids() == null || request.ids().isEmpty()) return new BatchResult(0);
        String incomingContentTags = normalizeAssetTags(request.contentTags());
        List<ProductAsset> assets = assetRepository.findAllById(request.ids());
        for (ProductAsset asset : assets) {
            String previousVideoStatus = asset.getVideoContentStatus();
            asset.setTags(applyTagAction(asset.getTags(), incomingContentTags, request.tagAction()));
            if (request.applicationTagsAction() != null && !request.applicationTagsAction().isBlank()) {
                asset.setApplicationTags(applyTagAction(asset.getApplicationTags(),
                        normalizeAssetTags(request.applicationTags()), request.applicationTagsAction()));
            }
            if (request.featureTagsAction() != null && !request.featureTagsAction().isBlank()) {
                asset.setFeatureTags(applyTagAction(asset.getFeatureTags(),
                        normalizeAssetTags(request.featureTags()), request.featureTagsAction()));
            }
            if (request.videoUsageChannelsAction() != null && !request.videoUsageChannelsAction().isBlank()) {
                asset.setVideoUsageChannels(applyTagAction(asset.getVideoUsageChannels(),
                        normalizeAssetTags(request.videoUsageChannels()), request.videoUsageChannelsAction()));
            }
            if (request.videoContentStatusAction() != null && !request.videoContentStatusAction().isBlank()
                    && Set.of("device_video", "operation_video").contains(asset.getCategory())) {
                asset.setVideoContentStatus(VideoGovernanceRules.validateContentStatus(normalizeShortValue(request.videoContentStatus())));
            }
            if (request.machineScopeConfirmed() != null
                    && Set.of("device_video", "operation_video").contains(asset.getCategory())) {
                asset.setMachineScopeConfirmed(request.machineScopeConfirmed());
            }
            if (request.videoFavorite() != null
                    && Set.of("device_video", "operation_video").contains(asset.getCategory())) {
                asset.setVideoFavorite(request.videoFavorite());
            }
            if (request.machineTierAction() != null) {
                applyMachineTierAction(asset, request.machineTierAction(), request.machineTierIds(), request.machineTierId());
            }
            if (Boolean.TRUE.equals(request.replaceVideoMetadata())) {
                applyVideoMetadata(asset, request.applicationTags(), request.videoContentType(), request.videoUsageStatus(),
                        request.featureTags(), request.demonstrationTags(), request.videoUsageChannels(), request.videoContentStatus());
            }
            validateVideoGovernance(asset, previousVideoStatus,
                    request.videoContentStatusAction() != null ||
                            (Boolean.TRUE.equals(request.replaceVideoMetadata()) && request.videoContentStatus() != null));
        }

        assetRepository.saveAll(assets);
        auditLogService.log("asset", 0L, "批量", "batch-update-metadata",
                "批量更新 " + assets.size() + " 个素材的内容标签与适用机型");
        return new BatchResult(assets.size());
    }

    @Transactional(readOnly = true)
    public Page<VideoAssetView> searchVideos(String keyword, Long modelId, String productCategory,
                                             String category, List<String> applications, String contentType,
                                             String usageStatus, String usageChannel, String contentStatus,
                                             String featureTag, String demonstrationTag, Long machineTierId,
                                             Boolean hasCover, Boolean incomplete, String scope,
                                             Boolean favorite, String missingItem, Pageable pageable) {
        Set<Long> personalFavoriteIds = videoWorkspaceService.favoriteIds();
        if (Boolean.TRUE.equals(favorite) && personalFavoriteIds.isEmpty()) return Page.empty(pageable);
        Specification<ProductAsset> spec = (root, query, cb) -> {
            List<jakarta.persistence.criteria.Predicate> predicates = new ArrayList<>();
            var tiers = root.joinSet("machineTiers", jakarta.persistence.criteria.JoinType.LEFT);
            query.distinct(true);
            predicates.add(root.get("category").in("device_video", "operation_video"));
            if (favorite != null && favorite) {
                predicates.add(root.get("id").in(personalFavoriteIds));
            }
            if (modelId != null) predicates.add(cb.equal(root.get("model").get("id"), modelId));
            if (productCategory != null && !productCategory.isBlank()) {
                predicates.add(cb.equal(root.get("model").get("category"), productCategory));
            }
            if (machineTierId != null) predicates.add(cb.equal(tiers.get("id"), machineTierId));
            if (scope != null && !scope.isBlank()) {
                switch (scope.toUpperCase()) {
                    case "ALL" -> {
                        predicates.add(cb.isEmpty(root.get("machineTiers")));
                        predicates.add(cb.isTrue(root.get("machineScopeConfirmed")));
                    }
                    case "SELECTED" -> predicates.add(cb.isNotEmpty(root.get("machineTiers")));
                    case "UNREVIEWED" -> {
                        predicates.add(cb.isEmpty(root.get("machineTiers")));
                        predicates.add(cb.isFalse(root.get("machineScopeConfirmed")));
                    }
                    default -> throw new IllegalArgumentException("未知的适用范围筛选: " + scope);
                }
            }
            if (category != null && !category.isBlank()) predicates.add(cb.equal(root.get("category"), category));
            if (applications != null && !applications.isEmpty()) {
                List<String> terms = applications.stream()
                        .filter(term -> term != null && !term.isBlank())
                        .toList();
                if (!terms.isEmpty()) {
                    predicates.add(cb.or(terms.stream()
                            .map(term -> cb.like(cb.lower(root.get("applicationTags")), like(term)))
                            .toArray(jakarta.persistence.criteria.Predicate[]::new)));
                }
            }
            if (contentType != null && !contentType.isBlank()) {
                predicates.add(cb.equal(root.get("videoContentType"), contentType));
            }
            if (usageStatus != null && !usageStatus.isBlank()) {
                predicates.add(cb.equal(root.get("videoUsageStatus"), usageStatus));
            }
            if (usageChannel != null && !usageChannel.isBlank()) {
                predicates.add(cb.like(cb.lower(root.get("videoUsageChannels")), like(usageChannel)));
            }
            if (contentStatus != null && !contentStatus.isBlank()) {
                predicates.add(cb.equal(root.get("videoContentStatus"), contentStatus));
            }
            if (featureTag != null && !featureTag.isBlank()) {
                predicates.add(cb.like(cb.lower(root.get("featureTags")), like(featureTag)));
            }
            if (demonstrationTag != null && !demonstrationTag.isBlank()) {
                predicates.add(cb.like(cb.lower(root.get("demonstrationTags")), like(demonstrationTag)));
            }
            if (hasCover != null) {
                if (hasCover) {
                    predicates.add(cb.isNotNull(root.get("coverFileName")));
                    predicates.add(cb.notEqual(root.get("coverFileName"), ""));
                } else {
                    jakarta.persistence.criteria.Predicate noCover = cb.isNull(root.get("coverFileName"));
                    predicates.add(cb.or(noCover, cb.equal(root.get("coverFileName"), "")));
                }
            }
            if (incomplete != null && incomplete) {
                // 动态完整度：按内容类型分组要求不同字段，规则见 VideoGovernanceRules
                jakarta.persistence.criteria.Path<String> typePath = root.get("videoContentType");
                predicates.add(cb.or(
                        cb.and(cb.isEmpty(root.get("machineTiers")), cb.isFalse(root.get("machineScopeConfirmed"))),
                        typePath.isNull(),
                        cb.equal(typePath, ""),
                        cb.and(typePath.in(VideoGovernanceRules.DEMO_TYPES),
                                cb.or(
                                        root.get("applicationTags").isNull(),
                                        cb.equal(root.get("applicationTags"), ""),
                                        root.get("demonstrationTags").isNull(),
                                        cb.equal(root.get("demonstrationTags"), ""))),
                        cb.and(typePath.in(VideoGovernanceRules.HOWTO_TYPES),
                                cb.or(
                                        root.get("tags").isNull(),
                                        cb.equal(root.get("tags"), ""))),
                        cb.and(typePath.in(VideoGovernanceRules.MAINTENANCE_TYPES),
                                cb.or(
                                        root.get("tags").isNull(),
                                        cb.equal(root.get("tags"), ""))),
                        cb.and(typePath.in(VideoGovernanceRules.PROMO_TYPES),
                                cb.or(
                                        root.get("videoUsageChannels").isNull(),
                                        cb.equal(root.get("videoUsageChannels"), "")))));
            }
            if (missingItem != null && !missingItem.isBlank()) {
                var typePath = root.<String>get("videoContentType");
                var blankApplications = cb.or(root.get("applicationTags").isNull(), cb.equal(root.get("applicationTags"), ""));
                var blankDemonstrations = cb.or(root.get("demonstrationTags").isNull(), cb.equal(root.get("demonstrationTags"), ""));
                var blankTags = cb.or(root.get("tags").isNull(), cb.equal(root.get("tags"), ""));
                var blankChannels = cb.or(root.get("videoUsageChannels").isNull(), cb.equal(root.get("videoUsageChannels"), ""));
                switch (missingItem) {
                    case "视频类型" -> predicates.add(cb.or(typePath.isNull(), cb.equal(typePath, "")));
                    case "适用范围确认" -> predicates.add(cb.and(cb.isEmpty(root.get("machineTiers")), cb.isFalse(root.get("machineScopeConfirmed"))));
                    case "用途标签" -> predicates.add(cb.and(typePath.in(VideoGovernanceRules.DEMO_TYPES), blankApplications));
                    case "展示参数" -> predicates.add(cb.and(typePath.in(VideoGovernanceRules.DEMO_TYPES), blankDemonstrations));
                    case "内容标签" -> predicates.add(cb.and(typePath.in(java.util.stream.Stream.concat(
                            VideoGovernanceRules.HOWTO_TYPES.stream(), VideoGovernanceRules.MAINTENANCE_TYPES.stream()).toList()), blankTags));
                    case "使用渠道" -> predicates.add(cb.and(typePath.in(VideoGovernanceRules.PROMO_TYPES), blankChannels));
                    default -> throw new IllegalArgumentException("未知的缺失项筛选: " + missingItem);
                }
            }
            if (keyword != null && !keyword.isBlank()) {
                String pattern = like(keyword);
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("fileName")), pattern),
                        cb.like(cb.lower(cb.coalesce(root.get("videoTitle"), "")), pattern),
                        cb.like(cb.lower(root.get("tags")), pattern),
                        cb.like(cb.lower(root.get("applicationTags")), pattern),
                        cb.like(cb.lower(root.get("featureTags")), pattern),
                        cb.like(cb.lower(root.get("demonstrationTags")), pattern),
                        cb.like(cb.lower(root.get("videoUsageChannels")), pattern),
                        cb.like(cb.lower(root.get("videoContentStatus")), pattern),
                        cb.like(cb.lower(root.get("model").get("name")), pattern),
                        cb.like(cb.lower(root.get("model").get("modelCode")), pattern),
                        cb.like(cb.lower(tiers.get("tierCode")), pattern),
                        cb.like(cb.lower(tiers.get("tierLabel")), pattern)
                ));
            }
            return cb.and(predicates.toArray(jakarta.persistence.criteria.Predicate[]::new));
        };
        return assetRepository.findAll(spec, pageable)
                .map(asset -> VideoAssetView.from(asset, personalFavoriteIds.contains(asset.getId())));
    }

    @Transactional(readOnly = true)
    public Map<String, Object> videoGovernanceSummary() {
        List<ProductAsset> videos = assetRepository.findAll((root, query, cb) ->
                root.get("category").in("device_video", "operation_video"));
        Map<String, Long> statuses = videos.stream().collect(Collectors.groupingBy(
                asset -> asset.getVideoContentStatus() == null || asset.getVideoContentStatus().isBlank()
                        ? "待处理" : asset.getVideoContentStatus(), LinkedHashMap::new, Collectors.counting()));
        Map<String, Long> missingCounts = new LinkedHashMap<>();
        long complete = 0;
        for (ProductAsset asset : videos) {
            boolean scopeConfirmed = VideoGovernanceRules.effectiveScopeConfirmed(
                    Boolean.TRUE.equals(asset.getMachineScopeConfirmed()), !asset.getMachineTiers().isEmpty());
            List<String> missing = VideoGovernanceRules.missingCompletenessItems(
                    asset.getVideoContentType(), scopeConfirmed, asset.getApplicationTags(), asset.getTags(),
                    asset.getDemonstrationTags(), asset.getVideoUsageChannels());
            if (missing.isEmpty()) complete++;
            for (String item : missing) missingCounts.merge(item, 1L, Long::sum);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", videos.size());
        result.put("complete", complete);
        result.put("incomplete", videos.size() - complete);
        result.put("statuses", statuses);
        result.put("missingItems", missingCounts);
        return result;
    }

    @Transactional(readOnly = true)
    public Map<String, List<String>> videoFilterOptions() {
        List<ProductAsset> videos = assetRepository.findAll((root, query, cb) ->
                root.get("category").in("device_video", "operation_video"));
        return Map.of(
                "applications", distinctTokens(videos.stream().map(ProductAsset::getApplicationTags).toList()),
                "contentTypes", distinctValues(videos.stream().map(ProductAsset::getVideoContentType).toList()),
                "usageStatuses", distinctValues(videos.stream().map(ProductAsset::getVideoUsageStatus).toList()),
                "usageChannels", distinctTokens(videos.stream().map(ProductAsset::getVideoUsageChannels).toList()),
                "contentStatuses", distinctValues(videos.stream().map(ProductAsset::getVideoContentStatus).toList()),
                "features", distinctTokens(videos.stream().map(ProductAsset::getFeatureTags).toList()),
                "demonstrations", distinctTokens(videos.stream().map(ProductAsset::getDemonstrationTags).toList())
        );
    }

    private void applyVideoMetadata(ProductAsset asset, String applicationTags, String contentType, String usageStatus,
                                    String featureTags, String demonstrationTags, String usageChannels,
                                    String contentStatus) {
        if (!Set.of("device_video", "operation_video").contains(asset.getCategory())) return;
        if (applicationTags != null) asset.setApplicationTags(normalizeAssetTags(applicationTags));
        if (contentType != null) asset.setVideoContentType(normalizeShortValue(contentType));
        if (usageStatus != null) asset.setVideoUsageStatus(normalizeShortValue(usageStatus));
        if (featureTags != null) asset.setFeatureTags(normalizeAssetTags(featureTags));
        if (demonstrationTags != null) asset.setDemonstrationTags(normalizeAssetTags(demonstrationTags));
        if (usageChannels != null) asset.setVideoUsageChannels(normalizeAssetTags(usageChannels));
        if (contentStatus != null) asset.setVideoContentStatus(VideoGovernanceRules.validateContentStatus(contentStatus));
    }

    private void validateVideoGovernance(ProductAsset asset, String previousStatus, boolean statusRequested) {
        if (!Set.of("device_video", "operation_video").contains(asset.getCategory())) return;
        if (asset.getVideoContentStatus() == null || asset.getVideoContentStatus().isBlank()) {
            asset.setVideoContentStatus("待处理");
        }
        if (!statusRequested) return;
        boolean scopeConfirmed = VideoGovernanceRules.effectiveScopeConfirmed(
                Boolean.TRUE.equals(asset.getMachineScopeConfirmed()), !asset.getMachineTiers().isEmpty());
        boolean complete = VideoGovernanceRules.isComplete(asset.getVideoContentType(), scopeConfirmed,
                asset.getApplicationTags(), asset.getTags(), asset.getDemonstrationTags(), asset.getVideoUsageChannels());
        VideoGovernanceRules.validateTransition(previousStatus, asset.getVideoContentStatus(), complete);
    }

    /** 视频展示标题与适用范围确认：仅对视频类素材生效。 */
    private void applyVideoTitleAndScope(ProductAsset asset, String videoTitle, Boolean machineScopeConfirmed) {
        if (!Set.of("device_video", "operation_video").contains(asset.getCategory())) return;
        if (videoTitle != null) {
            String normalized = videoTitle.trim();
            if (normalized.length() > 500) throw new IllegalArgumentException("视频标题不能超过500个字符");
            asset.setVideoTitle(normalized);
        }
        if (machineScopeConfirmed != null) asset.setMachineScopeConfirmed(machineScopeConfirmed);
    }

    /**
     * 由文件名清洗生成默认视频标题：去扩展名、去导入/清理时间戳（10位以上连续数字）、
     * 去首尾分隔符与空白。原文件名继续保留用于追溯。
     */
    public static String cleanVideoTitle(String fileName) {
        String source = String.valueOf(fileName == null ? "" : fileName);
        source = source.replaceFirst("\\.[^.]+$", "");
        source = source.replaceAll("[0-9]{10,}", "");
        source = source.replaceAll("[\\-_\\s]+$", "").replaceAll("^[\\-_\\s]+", "");
        return source.strip();
    }

    private static String normalizeShortValue(String value) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim();
        if (normalized.length() > 40) throw new IllegalArgumentException("视频分类或状态不能超过40个字符");
        return normalized;
    }

    private static String like(String value) {
        return "%" + value.trim().toLowerCase(java.util.Locale.ROOT) + "%";
    }

    private static List<String> distinctValues(List<String> values) {
        return values.stream().filter(v -> v != null && !v.isBlank()).map(String::trim)
                .distinct().sorted().toList();
    }

    private static List<String> distinctTokens(List<String> values) {
        return values.stream().filter(Objects::nonNull)
                .flatMap(v -> java.util.Arrays.stream(v.split("[,，]")))
                .map(String::trim).filter(v -> !v.isBlank()).distinct().sorted().toList();
    }

    /**
     * 官网可见性批量更新。支持字段：websiteVisible (Boolean)、websiteSortOrder (Integer)。
     * 字段值统一为字符串，由 service 端解析。单事务 + 审计日志。
     */
    @Transactional
    public BatchResult batchUpdateAssetWebsiteFields(List<Long> ids, Map<String, String> fields) {
        if (ids == null || ids.isEmpty()) return new BatchResult(0);
        Set<String> allowed = Set.of("websiteVisible", "websiteSortOrder");
        for (String key : fields.keySet()) {
            if (!allowed.contains(key)) {
                throw new IllegalArgumentException("不支持批量修改字段: " + key);
            }
        }
        List<ProductAsset> assets = assetRepository.findAllById(ids);
        if (assets.isEmpty()) {
            throw new EntityNotFoundException("未找到指定素材");
        }
        for (ProductAsset asset : assets) {
            String visibleVal = fields.get("websiteVisible");
            if (visibleVal != null) {
                asset.setWebsiteVisible(parseBoolean(visibleVal));
            }
            String orderVal = fields.get("websiteSortOrder");
            if (orderVal != null) {
                try {
                    asset.setWebsiteSortOrder(Integer.parseInt(orderVal.trim()));
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("websiteSortOrder 必须是整数: " + orderVal);
                }
            }
        }
        assetRepository.saveAll(assets);
        auditLogService.log("asset", 0L, "官网批量", "website-batch-update",
                "批量更新 " + assets.size() + " 个素材的官网可见性字段: " + fields.keySet());
        return new BatchResult(assets.size());
    }

    @Transactional
    public BatchResult updateAssetVariantBatch(BatchAssetVariantRequest request) {
        if (request.assignments() == null || request.assignments().isEmpty()) return new BatchResult(0);
        List<Long> assetIds = request.assignments().stream().map(AssetVariantAssignment::assetId).toList();
        List<ProductAsset> assets = assetRepository.findAllById(assetIds);
        java.util.Map<Long, Long> variantMap = request.assignments().stream()
                .collect(Collectors.toMap(AssetVariantAssignment::assetId, AssetVariantAssignment::variantId));
        for (ProductAsset asset : assets) {
            Long variantId = variantMap.get(asset.getId());
            if (variantId != null) {
                ProductVariant targetVariant = variantId > 0
                        ? findVariantForModel(asset.getModel().getId(), variantId)
                        : null;
                assignAssetToVariant(asset, targetVariant);
            }
        }
        assetRepository.saveAll(assets);
        auditLogService.log("asset", 0L, "批量", "batch-update-variant",
                "批量更新 " + assets.size() + " 个素材配置归属");
        return new BatchResult(assets.size());
    }

    private String normalizeAssetTags(String tags) {
        String normalized = ProductVariantTagPolicy.storageTags(tags);
        return normalized.length() > 500 ? normalized.substring(0, 500) : normalized;
    }

    private String mergeAssetTags(String existingTags, String incomingTags) {
        if (incomingTags == null || incomingTags.isBlank()) return normalizeAssetTags(existingTags);
        return normalizeAssetTags((existingTags == null ? "" : existingTags) + "," + incomingTags);
    }

    private String applyTagAction(String existingTags, String incomingTags, String action) {
        String normalizedAction = action == null || action.isBlank() ? "KEEP" : action.trim().toUpperCase();
        return switch (normalizedAction) {
            case "KEEP" -> normalizeAssetTags(existingTags);
            case "REPLACE" -> incomingTags;
            case "APPEND" -> mergeAssetTags(existingTags, incomingTags);
            case "REMOVE" -> removeAssetTags(existingTags, incomingTags);
            default -> throw new IllegalArgumentException("不支持的标签操作: " + action);
        };
    }

    private String removeAssetTags(String existingTags, String removedTags) {
        Set<String> removed = ProductVariantTagPolicy.parse(removedTags).stream()
                .map(value -> value.toLowerCase(java.util.Locale.ROOT)).collect(Collectors.toSet());
        return String.join(",", ProductVariantTagPolicy.parse(existingTags).stream()
                .filter(value -> !removed.contains(value.toLowerCase(java.util.Locale.ROOT))).toList());
    }

    private void applySpecificationAction(ProductAsset asset, String action, Long variantId) {
        String normalizedAction = action == null || action.isBlank() ? "KEEP" : action.trim().toUpperCase();
        if (!canAssociateSpecification(asset.getCategory()) && !"KEEP".equals(normalizedAction)) {
            throw new IllegalArgumentException("当前素材分类不支持关联产品规格");
        }
        switch (normalizedAction) {
            case "KEEP" -> { }
            case "UNLINK" -> asset.setVariant(null);
            case "ASSIGN" -> {
                if (variantId == null) throw new IllegalArgumentException("关联规格时必须选择产品规格");
                asset.setVariant(findVariantForModel(asset.getModel().getId(), variantId));
            }
            default -> throw new IllegalArgumentException("不支持的规格操作: " + action);
        }
    }

    private void applyMachineTierAction(ProductAsset asset, String action, List<Long> machineTierIds, Long legacyMachineTierId) {
        String normalizedAction = action == null || action.isBlank() ? "KEEP" : action.trim().toUpperCase();
        switch (normalizedAction) {
            case "KEEP" -> { }
            case "UNLINK" -> {
                asset.setMachineTier(null);
                asset.getMachineTiers().clear();
                asset.setVariant(null);
            }
            case "ASSIGN" -> {
                LinkedHashSet<Long> ids = new LinkedHashSet<>(machineTierIds == null ? List.of() : machineTierIds);
                if (ids.isEmpty() && legacyMachineTierId != null) ids.add(legacyMachineTierId);
                if (ids.isEmpty()) throw new IllegalArgumentException("关联机型时必须至少选择一个机型档位");
                Map<Long, ProductMachineTier> tierById = machineTierRepository.findAllById(ids).stream()
                        .collect(Collectors.toMap(ProductMachineTier::getId, tier -> tier));
                if (tierById.size() != ids.size()) throw new EntityNotFoundException("选择的机型档位不存在或已删除");
                List<ProductMachineTier> tiers = ids.stream().map(tierById::get).toList();
                for (ProductMachineTier tier : tiers) {
                    if (!Objects.equals(tier.getModel().getId(), asset.getModel().getId())) {
                        throw new IllegalArgumentException("机型档位不属于当前产品");
                    }
                }
                asset.getMachineTiers().clear();
                asset.getMachineTiers().addAll(tiers);
                asset.setMachineTier(tiers.get(0));
                asset.setMachineScopeConfirmed(true);
                asset.setVariant(null);
            }
            default -> throw new IllegalArgumentException("不支持的机型归属操作: " + action);
        }
    }

    private void assignAssetToVariant(ProductAsset asset, ProductVariant targetVariant) {
        asset.setVariant(targetVariant);
    }

    private void refreshSourceDefaultAfterAssetMove(ProductModel sourceModel, ProductVariant sourceVariant,
                                                    String sourceCategory, String fileName) {
        List<ProductAsset> remaining = assetRepository.findByModelIdAndCategoryOrderBySortOrderAscIdAsc(
                sourceModel.getId(), sourceCategory);
        if ("main_image".equals(sourceCategory)) {
            for (ProductAsset a : remaining) {
                a.setDefaultAsset(false);
            }
            if (fileName.equals(sourceModel.getDefaultImage())) {
                if (remaining.isEmpty()) {
                    sourceModel.setDefaultImage("");
                } else {
                    sourceModel.setDefaultImage(remaining.get(0).getFileName());
                    remaining.get(0).setDefaultAsset(true);
                }
            }
            assetRepository.saveAll(remaining);
            modelRepository.save(sourceModel);
        } else if (isVariantAsset(sourceCategory) && sourceVariant != null) {
            List<ProductAsset> variantRemaining = assetRepository.findByVariantIdAndCategoryOrderBySortOrderAscIdAsc(
                    sourceVariant.getId(), sourceCategory);
            for (ProductAsset a : variantRemaining) {
                a.setDefaultAsset(false);
            }
            if (!variantRemaining.isEmpty()) {
                variantRemaining.get(0).setDefaultAsset(true);
            }
            assetRepository.saveAll(variantRemaining);
        }
    }

    private void refreshTargetDefaultAfterAssetMove(ProductModel targetModel, String targetCategory, ProductAsset movedAsset) {
        List<ProductAsset> targetAssets = assetRepository.findByModelIdAndCategoryOrderBySortOrderAscIdAsc(
                targetModel.getId(), targetCategory);
        boolean hasDefault = targetAssets.stream().anyMatch(a -> Boolean.TRUE.equals(a.getDefaultAsset()));
        if (!hasDefault && !targetAssets.isEmpty()) {
            movedAsset.setDefaultAsset(true);
            assetRepository.save(movedAsset);
            if ("main_image".equals(targetCategory)) {
                targetModel.setDefaultImage(movedAsset.getFileName());
                modelRepository.save(targetModel);
            }
        }
    }

    @Transactional
    public void reorderAssets(List<ReorderItem> items) {
        List<Long> ids = items.stream().map(ReorderItem::id).toList();
        List<ProductAsset> assets = assetRepository.findAllById(ids);
        java.util.Map<Long, Integer> orderMap = items.stream()
                .collect(Collectors.toMap(ReorderItem::id, ReorderItem::sortOrder));
        for (ProductAsset asset : assets) {
            Integer newOrder = orderMap.get(asset.getId());
            if (newOrder != null) {
                asset.setSortOrder(newOrder);
            }
        }
        assetRepository.saveAll(assets);
        auditLogService.log("asset", 0L, "素材排序", "reorder",
                "调整 " + assets.size() + " 个素材排序");
    }

    @Transactional
    public BatchResult batchUpdateModels(BatchUpdateRequest request) {
        List<ProductModel> models = modelRepository.findByIdIn(request.ids());
        if (models.isEmpty()) {
            throw new EntityNotFoundException("未找到指定产品");
        }
        for (ProductModel model : models) {
            applyModelUpdates(model, request.updates());
        }
        modelRepository.saveAll(models);
        auditLogService.log("model", 0L, "批量", "batch-update",
                "批量更新 " + models.size() + " 个产品");
        return new BatchResult(models.size());
    }

    /**
     * 官网发布字段批量更新。仅接受 {@link #WEBSITE_BATCH_FIELDS} 内的字段：
     * websiteEnabled / websiteFeatured (Boolean)、websiteSlug / websiteSummary (String)。
     * 单事务 + 联动规则 + 唯一性校验 + 审计日志，任一项失败整批回滚。
     */
    @Transactional
    public BatchResult batchUpdateWebsiteFields(List<Long> ids, Map<String, String> fields) {
        if (ids == null || ids.isEmpty()) {
            return new BatchResult(0);
        }
        // 字段白名单校验
        for (String key : fields.keySet()) {
            if (!WEBSITE_BATCH_FIELDS.contains(key)) {
                throw new IllegalArgumentException("不支持批量修改字段: " + key);
            }
        }
        List<ProductModel> models = modelRepository.findByIdIn(ids);
        if (models.isEmpty()) {
            throw new EntityNotFoundException("未找到指定产品");
        }
        for (ProductModel model : models) {
            applyWebsiteFieldUpdates(model, fields);
        }
        applyWebsiteLinkageRules(models);
        checkWebsiteSlugUniqueness(models);
        modelRepository.saveAll(models);
        auditLogService.log("model", 0L, "官网批量", "website-batch-update",
                "批量更新 " + models.size() + " 个产品的官网发布字段: " + fields.keySet());
        return new BatchResult(models.size());
    }

    /**
     * 应用官网字段到单个 model。Boolean 字段解析 "true"/"false"，空值跳过。
     */
    private void applyWebsiteFieldUpdates(ProductModel model, Map<String, String> fields) {
        fields.forEach((key, value) -> {
            if (value == null) return;
            switch (key) {
                case "websiteEnabled" -> model.setWebsiteEnabled(parseBoolean(value));
                case "websiteFeatured" -> model.setWebsiteFeatured(parseBoolean(value));
                case "websiteSlug" -> model.setWebsiteSlug(value.isBlank() ? null : value.trim());
                case "websiteSummary" -> model.setWebsiteSummary(value.isBlank() ? null : value.trim());
                default -> { /* 白名单已校验，不会进入 */ }
            }
        });
    }

    /**
     * 官网发布联动规则：
     * - websiteFeatured=true 时保证 websiteEnabled=true（精选隐含发布）
     * - websiteEnabled=false 时强制 websiteFeatured=false（取消发布联动取消精选）
     */
    private void applyWebsiteLinkageRules(List<ProductModel> models) {
        for (ProductModel model : models) {
            applyWebsiteLinkageRules(model);
        }
    }

    /**
     * 单条官网发布联动：精选隐含发布；取消发布联动取消精选。
     */
    private void applyWebsiteLinkageRules(ProductModel model) {
        if (Boolean.TRUE.equals(model.getWebsiteFeatured()) && !Boolean.TRUE.equals(model.getWebsiteEnabled())) {
            model.setWebsiteEnabled(true);
        }
        if (!Boolean.TRUE.equals(model.getWebsiteEnabled())) {
            model.setWebsiteFeatured(false);
        }
    }

    /**
     * 官网 URL 标识唯一性校验：覆盖本次批内与其他产品，冲突时抛出异常整批回滚。
     * 案例比较时统一转小写，避免 Automatic-machine 与 automatic-machine 重复。
     */
    private void checkWebsiteSlugUniqueness(List<ProductModel> models) {
        Set<Long> batchIds = models.stream().map(ProductModel::getId).collect(Collectors.toSet());
        Map<String, Long> seen = new HashMap<>();
        for (ProductModel model : models) {
            String slug = model.getWebsiteSlug();
            if (slug == null || slug.isBlank()) continue;
            String key = slug.toLowerCase();
            Long ownerId = seen.get(key);
            if (ownerId != null && !ownerId.equals(model.getId())) {
                throw new IllegalArgumentException("官网 URL 标识 " + slug + " 已被本次批量中的其他产品使用");
            }
            seen.put(key, model.getId());
        }
        for (Map.Entry<String, Long> e : seen.entrySet()) {
            Optional<ProductModel> existing = modelRepository.findByWebsiteSlug(e.getKey());
            if (existing.isPresent() && !batchIds.contains(existing.get().getId())) {
                throw new IllegalArgumentException(
                        "官网 URL 标识 " + e.getKey() + " 已被产品「" + existing.get().getName() + "」使用");
            }
        }
    }

    private static boolean parseBoolean(String value) {
        return "true".equalsIgnoreCase(value) || "1".equals(value) || "yes".equalsIgnoreCase(value);
    }

    @Transactional
    public BatchResult batchDeleteModels(BatchDeleteRequest request) {
        List<Long> modelIds = request.ids();
        if (modelIds.isEmpty()) {
            return new BatchResult(0);
        }
        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        List<ProductModel> models = modelRepository.findByIdIn(modelIds);
        for (ProductModel model : models) {
            assetRepository.findByModelIdOrderBySortOrderAscIdAsc(model.getId())
                    .forEach(a -> { a.setDeletedAt(now); assetRepository.save(a); });
            variantRepository.findByModelIdOrderBySortOrderAsc(model.getId())
                    .forEach(v -> { v.setDeletedAt(now); variantRepository.save(v); });
            model.setDeletedAt(now);
        }
        modelRepository.saveAll(models);
        auditLogService.log("model", 0L, "批量", "batch-delete",
                "批量软删除 " + modelIds.size() + " 个产品");
        return new BatchResult(modelIds.size());
    }

    @Transactional
    public void reorderModels(List<ReorderItem> items) {
        if (items == null || items.isEmpty()) {
            return;
        }
        Map<Long, Integer> orderMap = items.stream()
                .filter(item -> item.id() != null && item.sortOrder() != null)
                .collect(Collectors.toMap(ReorderItem::id, ReorderItem::sortOrder, (a, b) -> b));
        if (orderMap.isEmpty()) {
            return;
        }
        List<ProductModel> models = modelRepository.findByIdIn(new ArrayList<>(orderMap.keySet()));
        for (ProductModel model : models) {
            model.setSortOrder(orderMap.get(model.getId()));
        }
        modelRepository.saveAll(models);
        auditLogService.log("model", 0L, "产品排序", "reorder",
                "调整 " + models.size() + " 个主产品排序");
    }

    private void applyModelUpdates(ProductModel model, Map<String, String> updates) {
        updates.forEach((key, value) -> {
            if (!MODEL_UPDATEABLE_FIELDS.contains(key)) {
                return;
            }
            try {
                String setterName = "set" + Character.toUpperCase(key.charAt(0)) + key.substring(1);
                Method setter = ProductModel.class.getMethod(setterName, String.class);
                setter.invoke(model, value);
            } catch (NoSuchMethodException e) {
                // 字段没有对应的 setter，跳过
            } catch (IllegalAccessException | java.lang.reflect.InvocationTargetException e) {
                // 设置失败，静默跳过（避免批量更新因单个字段失败而全部回滚）
            }
        });
    }

    @Transactional
    public ProductAsset addAsset(Long modelId, AssetPayload payload) {
        ProductModel model = modelRepository.findById(modelId)
                .orElseThrow(() -> new EntityNotFoundException("产品主档不存在: " + modelId));
        ProductVariant variant = findVariantForModel(modelId, payload.variantId());
        ProductAsset existing = findExistingAsset(modelId, payload.category(), payload.fileName(), variant == null ? null : variant.getId());
        if (existing != null) {
            if (isDefaultCandidate(payload.category(), model)) {
                setDefaultAsset(existing.getId());
            }
            return existing;
        }
        ProductAsset asset = new ProductAsset();
        asset.setModel(model);
        asset.setVariant(canAssociateSpecification(payload.category()) ? variant : null);
        asset.setTags("");
        asset.setCategory(payload.category());
        asset.setFileName(payload.fileName());
        asset.setSourceColumn(payload.category());
        asset.setSourceRowNumber(payload.sourceRowNumber() == null ? 0 : payload.sourceRowNumber());
        asset.setSortOrder((int) assetRepository.countByModelId(modelId));
        asset.setDefaultAsset(false);
        if (Set.of("device_video", "operation_video").contains(payload.category())) {
            asset.setVideoTitle(cleanVideoTitle(payload.fileName()));
        }
        ProductAsset saved = assetRepository.save(asset);
        auditLogService.log("asset", saved.getId(), model.getName(), "add",
                "添加素材：" + payload.fileName() + "（分类：" + payload.category() + "）");
        if (isDefaultCandidate(payload.category(), model)) {
            setDefaultAsset(saved.getId());
            return saved;
        }
        return saved;
    }

    private ProductAsset findExistingAsset(Long modelId, String category, String fileName, Long variantId) {
        if (fileName == null || fileName.isBlank()) {
            return null;
        }
        if (canAssociateSpecification(category) && variantId != null) {
            return assetRepository.findFirstByModelIdAndCategoryAndFileNameAndVariantId(modelId, category, fileName, variantId)
                    .orElse(null);
        }
        if (!canAssociateSpecification(category)) {
            return assetRepository.findFirstByModelIdAndCategoryAndFileName(modelId, category, fileName)
                    .orElse(null);
        }
        return null;
    }

    @Transactional
    public ProductAsset setDefaultAsset(Long assetId) {
        ProductAsset asset = assetRepository.findById(assetId)
                .orElseThrow(() -> new EntityNotFoundException("素材不存在: " + assetId));
        List<ProductAsset> sameCategory = assetRepository.findByModelIdAndCategoryOrderBySortOrderAscIdAsc(
                asset.getModel().getId(), asset.getCategory());
        for (ProductAsset item : sameCategory) {
            item.setDefaultAsset(item.getId().equals(asset.getId()));
        }
        assetRepository.saveAll(sameCategory);
        ProductModel model = asset.getModel();
        if ("main_image".equals(asset.getCategory())) {
            model.setDefaultImage(asset.getFileName());
            modelRepository.save(model);
        }
        auditLogService.log("asset", assetId, asset.getModel().getName(), "set-default",
                "设为默认素材：" + asset.getFileName() + "（分类：" + asset.getCategory() + "）");
        return asset;
    }

    @Transactional
    public void deleteAsset(Long assetId, boolean deleteFile) {
        ProductAsset asset = assetRepository.findById(assetId)
                .orElseThrow(() -> new EntityNotFoundException("素材不存在: " + assetId));
        ProductModel model = asset.getModel();
        String category = asset.getCategory();
        String fileName = asset.getFileName();
        asset.setDeletedAt(java.time.LocalDateTime.now());
        assetRepository.save(asset);
        if (deleteFile) {
            fileStorageService.deleteIfExists(model.getId(), model.getName(), category, fileName);
        }
        List<ProductAsset> remaining = assetRepository.findByModelIdAndCategoryOrderBySortOrderAscIdAsc(model.getId(), category);
        if ("main_image".equals(category) && fileName.equals(model.getDefaultImage())) {
            model.setDefaultImage(remaining.isEmpty() ? "" : remaining.get(0).getFileName());
            modelRepository.save(model);
        }
        if (!remaining.isEmpty() && remaining.stream().noneMatch(item -> Boolean.TRUE.equals(item.getDefaultAsset()))) {
            remaining.get(0).setDefaultAsset(true);
            assetRepository.save(remaining.get(0));
        }
        auditLogService.log("asset", assetId, model.getName(), "delete",
                "删除素材：" + fileName + "（分类：" + category + "）" + (deleteFile ? " [同时删除物理文件]" : ""));
    }

    private boolean isVariantAsset(String category) {
        return VARIANT_THUMBNAIL_CATEGORIES.contains(category);
    }

    /** Every product asset can belong to a specification; thumbnail eligibility is a separate concern. */
    private boolean canAssociateSpecification(String category) {
        return PRODUCT_ASSET_CATEGORIES.contains(category);
    }

    private boolean isDefaultCandidate(String category, ProductModel model) {
        return "main_image".equals(category) && (model.getDefaultImage() == null || model.getDefaultImage().isBlank());
    }

    private ProductAsset ensureModelDefaultAsset(ProductModel model, String category, String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return null;
        }
        ProductAsset asset = assetRepository.findFirstByModelIdAndCategoryAndFileName(model.getId(), category, fileName)
                .orElseGet(() -> {
                    ProductAsset created = new ProductAsset();
                    created.setModel(model);
                    created.setCategory(category);
                    created.setFileName(fileName);
                    created.setSourceColumn(category);
                    created.setSourceRowNumber(0);
                    created.setSortOrder((int) assetRepository.countByModelId(model.getId()));
                    created.setDefaultAsset(false);
                    return assetRepository.save(created);
                });
        setDefaultAsset(asset.getId());
        return asset;
    }

    private ProductVariant findVariantForModel(Long modelId, Long variantId) {
        if (variantId == null) {
            return null;
        }
        ProductVariant variant = variantRepository.findById(variantId)
                .orElseThrow(() -> new EntityNotFoundException("产品配置不存在: " + variantId));
        if (!variant.getModel().getId().equals(modelId)) {
            throw new IllegalArgumentException("配置不属于当前主产品");
        }
        return variant;
    }

    public record AssetPayload(String category, String fileName, Long variantId, Integer sourceRowNumber) {
    }
}
