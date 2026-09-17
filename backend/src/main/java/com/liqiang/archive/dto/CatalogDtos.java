package com.liqiang.archive.dto;

import com.liqiang.archive.entity.ProductAsset;
import com.liqiang.archive.entity.ProductMachineTier;
import com.liqiang.archive.entity.ProductModel;
import com.liqiang.archive.entity.ProductVariant;
import com.liqiang.archive.service.ProductVariantTagPolicy;

import java.util.List;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.time.LocalDateTime;

public class CatalogDtos {

    public record ModelSummary(
            Long id,
            String name,
            String modelCode,
            // ====== V2 字段（V108 起 V1 旧字段已删除） ======
            String category,
            String descriptionCn,
            String descriptionEn,
            String status,
            String shopUrl,
            String defaultImage,
            Long defaultImageAssetId,
            String defaultImageOwnerType,
            Integer sortOrder,
            long variantCount,
            long assetCount,
            long skuImageCount,
            String websiteSlug,
            String websiteSummary,
            List<String> applicationTags,
            List<String> containerTags,
            List<String> packagingProcessTags,
            String capacityLevel,
            Boolean websiteEnabled,
            Boolean websiteFeatured,
            int completenessScore
    ) {
        public static ModelSummary from(ProductModel model, long variantCount, long assetCount, long skuImageCount) {
            return from(model, variantCount, assetCount, skuImageCount, null, null);
        }

        public static ModelSummary from(ProductModel model, long variantCount, long assetCount, long skuImageCount, Long defaultImageAssetId) {
            return from(model, variantCount, assetCount, skuImageCount, defaultImageAssetId, null);
        }

        public static ModelSummary from(ProductModel model, long variantCount, long assetCount, long skuImageCount,
                                        Long defaultImageAssetId, String defaultImageOwnerType) {
            return new ModelSummary(
                    model.getId(),
                    model.getName(),
                    model.getModelCode(),
                    // ====== V2 字段 ======
                    model.getCategory(),
                    model.getDescriptionCn(),
                    model.getDescriptionEn(),
                    model.getStatus(),
                    model.getShopUrl(),
                    model.getDefaultImage(),
                    defaultImageAssetId,
                    defaultImageOwnerType,
                    model.getSortOrder(),
                    variantCount,
                    assetCount,
                    skuImageCount,
                    model.getWebsiteSlug(),
                    model.getWebsiteSummary(),
                    model.getApplicationTags(),
                    model.getContainerTags(),
                    model.getPackagingProcessTags(),
                    model.getCapacityLevel(),
                    model.getWebsiteEnabled(),
                    model.getWebsiteFeatured(),
                    computeCompletenessScore(model, variantCount, assetCount, skuImageCount, defaultImageAssetId)
            );
        }

        /**
         * 官网内容完整度评分（0-100），与 WebsiteController.computeCompletenessScore 保持一致。
         * 5 项各 20 分：主图 / 手写摘要 / 至少1个规格 / 设备类别 / 至少1个素材或SKU图。
         */
        static int computeCompletenessScore(ProductModel model, long variantCount, long assetCount, long skuImageCount, Long defaultImageAssetId) {
            int score = 0;
            if (defaultImageAssetId != null || (model.getDefaultImage() != null && !model.getDefaultImage().isBlank())) {
                score += 20;
            }
            String summary = model.getWebsiteSummary();
            if (summary != null
                    && !summary.isBlank()
                    && !summary.endsWith("，品质保障，值得信赖。")
                    && !summary.endsWith("，品质保障。")) {
                score += 20;
            }
            if (variantCount >= 1) score += 20;
            if (model.getCategory() != null && !model.getCategory().isBlank()) score += 20;
            if (assetCount >= 1 || skuImageCount >= 1) score += 20;
            return score;
        }
    }

    public record ModelView(
            Long id,
            String name,
            String nameEn,
            String modelCode,
            // ====== V2 字段（V108 起 V1 旧字段已删除） ======
            String category,
            String descriptionCn,
            String descriptionEn,
            String status,
            String shopUrl,
            String applicationScope,
            String defaultImage,
            Integer sortOrder,
            String remark,
            String introductionCn,
            String introductionEn,
            String searchKeywordsCn,
            String searchKeywordsEn,
            Boolean websiteEnabled,
            String websiteSlug,
            String websiteSummary,
            List<String> applicationTags,
            List<String> containerTags,
            List<String> packagingProcessTags,
            String capacityLevel,
            java.time.LocalDateTime createdAt,
            java.time.LocalDateTime updatedAt
    ) {
        public static ModelView from(ProductModel model) {
            return new ModelView(
                    model.getId(),
                    model.getName(),
                    model.getNameEn(),
                    model.getModelCode(),
                    // ====== V2 字段 ======
                    model.getCategory(),
                    model.getDescriptionCn(),
                    model.getDescriptionEn(),
                    model.getStatus(),
                    model.getShopUrl(),
                    model.getApplicationScope(),
                    model.getDefaultImage(),
                    model.getSortOrder(),
                    model.getRemark(),
                    model.getIntroductionCn(),
                    model.getIntroductionEn(),
                    model.getSearchKeywordsCn(),
                    model.getSearchKeywordsEn(),
                    model.getWebsiteEnabled(),
                    model.getWebsiteSlug(),
                    model.getWebsiteSummary(),
                    model.getApplicationTags(),
                    model.getContainerTags(),
                    model.getPackagingProcessTags(),
                    model.getCapacityLevel(),
                    model.getCreatedAt(),
                    model.getUpdatedAt()
            );
        }
    }

    public record ModelDetail(
            ModelView model,
            List<VariantView> variants,
            List<AssetView> assets,
            long variantCount,
            long assetCount
    ) {
    }

    public record ModelSimpleRow(
            Long id,
            String name,
            String category,
            String defaultImage
    ) {
    }

    public record PackagingSpecOption(
            Long id, Long modelId, String modelName, String variantName,
            String shapeType, String apertureKey, String apertureLabel,
            String heightMm, String capacityMl, String dimensionLabel
    ) {}

    public record MachineTierPackagingAssignment(Long machineTierId, List<Long> packagingVariantIds) {}

    public record ProductPackagingCompatibilityMatrix(
            List<PackagingSpecOption> options,
            List<Long> productPackagingVariantIds,
            List<MachineTierPackagingAssignment> machineTierAssignments
    ) {}

    public record ProductPackagingCompatibilityRequest(
            Long machineTierId,
            List<Long> packagingVariantIds
    ) {}

    public record VariantView(
            Long id,
            String variantName,
            String specKey,
            Integer sortOrder,
            Boolean baseline,
            String moldSizeType,
            String moldLengthMm,
            String moldWidthMm,
            String moldDiameterMm,
            String moldSizeLabel,
            String remark,
            Long machineTierId,
            String machineTierCode,
            String machineTierLabel
    ) {
        public static VariantView from(ProductVariant variant) {
            ProductMachineTier tier = variant.getMachineTier();
            return new VariantView(
                    variant.getId(),
                    variant.getVariantName(),
                    variant.getSpecKey(),
                    variant.getSortOrder(),
                    variant.getBaseline(),
                    variant.getMoldSizeType(),
                    variant.getMoldLengthMm(),
                    variant.getMoldWidthMm(),
                    variant.getMoldDiameterMm(),
                    variant.getMoldSizeLabel(),
                    variant.getRemark(),
                    tier == null ? null : tier.getId(),
                    tier == null ? null : tier.getTierCode(),
                    tier == null ? null : tier.getTierLabel()
            );
        }
    }

    public record AssetView(
            Long id,
            Long variantId,
            String category,
            String fileName,
            String tags,
            String contentTags,
            String specificationTags,
            String effectiveTags,
            String applicationTags,
            String videoContentType,
            String videoUsageStatus,
            Long machineTierId,
            String machineTierLabel,
            List<Long> machineTierIds,
            List<String> machineTierLabels,
            String featureTags,
            String demonstrationTags,
            String videoUsageChannels,
            String videoContentStatus,
            String sourceColumn,
            Integer sourceRowNumber,
            String sourceFile,
            String sourceSheet,
            Long importBatchId,
            Integer sortOrder,
            Boolean defaultAsset,
            String coverFileName,
            Boolean websiteVisible,
            Integer websiteSortOrder
    ) {
        public static AssetView from(ProductAsset asset) {
            String contentTags = contentTagsFor(asset);
            String specificationTags = "";
            String effectiveTags = contentTags;
            ProductMachineTier tier = asset.getMachineTier();
            List<ProductMachineTier> scopeTiers = scopeTiers(asset);
            return new AssetView(
                    asset.getId(),
                    null,
                    asset.getCategory(),
                    asset.getFileName(),
                    contentTags,
                    contentTags,
                    specificationTags,
                    effectiveTags,
                    asset.getApplicationTags(),
                    asset.getVideoContentType(),
                    asset.getVideoUsageStatus(),
                    tier == null ? null : tier.getId(),
                    tier == null ? null : firstNonBlank(tier.getTierLabel(), tier.getTierCode()),
                    scopeTiers.stream().map(ProductMachineTier::getId).toList(),
                    scopeTiers.stream().map(item -> firstNonBlank(item.getTierLabel(), item.getTierCode())).toList(),
                    asset.getFeatureTags(),
                    asset.getDemonstrationTags(),
                    asset.getVideoUsageChannels(),
                    asset.getVideoContentStatus(),
                    asset.getSourceColumn(),
                    asset.getSourceRowNumber(),
                    asset.getSourceFile(),
                    asset.getSourceSheet(),
                    asset.getImportBatchId(),
                    asset.getSortOrder(),
                    asset.getDefaultAsset(),
                    asset.getCoverFileName(),
                    asset.getWebsiteVisible(),
                    asset.getWebsiteSortOrder()
            );
        }

        private static String contentTagsFor(ProductAsset asset) {
            Set<String> structuredTags = new LinkedHashSet<>();
            addTags(structuredTags, asset.getApplicationTags());
            addTags(structuredTags, asset.getVideoContentType());
            addTags(structuredTags, asset.getFeatureTags());
            addTags(structuredTags, asset.getDemonstrationTags());
            addTags(structuredTags, asset.getVideoUsageChannels());
            addTags(structuredTags, asset.getVideoContentStatus());

            Set<String> contentTags = new LinkedHashSet<>();
            addTags(contentTags, ProductVariantTagPolicy.contentTags(asset.getTags()));
            contentTags.removeAll(structuredTags);
            return String.join("，", contentTags);
        }

        private static List<ProductMachineTier> scopeTiers(ProductAsset asset) {
            if (asset.getMachineTiers() != null && !asset.getMachineTiers().isEmpty()) {
                return asset.getMachineTiers().stream()
                        .sorted(java.util.Comparator.comparing(ProductMachineTier::getSortOrder)
                                .thenComparing(ProductMachineTier::getId))
                        .toList();
            }
            return asset.getMachineTier() == null ? List.of() : List.of(asset.getMachineTier());
        }

        private static void addTags(Set<String> target, String value) {
            if (value == null || value.isBlank()) return;
            for (String token : value.split("[,，;；|｜\\n\\r]+")) {
                String normalized = token.trim();
                if (!normalized.isBlank()) target.add(normalized);
            }
        }

        private static String firstNonBlank(String... values) {
            for (String value : values) if (value != null && !value.isBlank()) return value;
            return null;
        }
    }

    public record CatalogStats(long modelCount, long variantCount, long assetCount) {
    }

    public record ImportResult(int models, int variants, int assets, int skippedRows) {
    }

    public record ModelCountRow(Long modelId, long variantCount, long assetCount, long skuImageCount) {
    }

    public record BatchUpdateRequest(List<Long> ids, Map<String, String> updates) {
    }

    public record BatchDeleteRequest(List<Long> ids) {
    }

    public record MoveVariantRequest(Long targetModelId) {
    }

    public record MoveAssetRequest(Long targetModelId, Long targetVariantId, String targetCategory) {
    }

    public record AssetMetadataRequest(
            String contentTags,
            String specificationAction,
            Long variantId,
            String applicationTags,
            String videoContentType,
            String videoUsageStatus,
            String machineTierAction,
            Long machineTierId,
            List<Long> machineTierIds,
            String featureTags,
            String demonstrationTags,
            String videoTitle,
            Boolean machineScopeConfirmed,
            String videoUsageChannels,
            String videoContentStatus,
            Boolean videoFavorite
    ) {
        public AssetMetadataRequest(String contentTags, String specificationAction, Long variantId) {
            this(contentTags, specificationAction, variantId, null, null, null,
                    null, null, null, null, null, null, null, null, null, null);
        }
    }

    public record BatchAssetTagsRequest(List<Long> ids, String tags, boolean replace) {
    }

    public record BatchAssetMetadataRequest(
            List<Long> ids,
            String contentTags,
            String tagAction,
            String specificationAction,
            Long variantId,
            String applicationTags,
            String videoContentType,
            String videoUsageStatus,
            Boolean replaceVideoMetadata,
            String machineTierAction,
            Long machineTierId,
            List<Long> machineTierIds,
            String featureTags,
            String demonstrationTags,
            String videoUsageChannels,
            String videoContentStatus,
            String applicationTagsAction,
            String featureTagsAction,
            String videoUsageChannelsAction,
            String videoContentStatusAction,
            Boolean machineScopeConfirmed,
            Boolean videoFavorite
    ) {
        public BatchAssetMetadataRequest(List<Long> ids, String contentTags, String tagAction,
                                         String specificationAction, Long variantId) {
            this(ids, contentTags, tagAction, specificationAction, variantId,
                    null, null, null, false, null, null, null, null, null, null, null,
                    null, null, null, null, null, null);
        }
    }

    public record VideoAssetView(
            Long id,
            Long modelId,
            String modelName,
            String modelCode,
            String equipmentCategory,
            Long variantId,
            String variantName,
            String specificationTags,
            String category,
            String fileName,
            String contentTags,
            String applicationTags,
            String videoContentType,
            String videoUsageStatus,
            Long machineTierId,
            String machineTierLabel,
            List<Long> machineTierIds,
            List<String> machineTierLabels,
            String featureTags,
            String demonstrationTags,
            String videoTitle,
            Boolean machineScopeConfirmed,
            String fileHash,
            Integer videoDurationSeconds,
            Integer videoWidth,
            Integer videoHeight,
            Boolean videoFavorite,
            String videoUsageChannels,
            String videoContentStatus,
            Boolean complete,
            List<String> missingItems,
            List<String> allowedNextStatuses,
            String coverFileName,
            LocalDateTime createdAt
    ) {
        public static VideoAssetView from(ProductAsset asset) {
            return from(asset, Boolean.TRUE.equals(asset.getVideoFavorite()));
        }

        public static VideoAssetView from(ProductAsset asset, boolean personalFavorite) {
            List<ProductMachineTier> scopeTiers = AssetView.scopeTiers(asset);
            boolean effectiveScopeConfirmed = com.liqiang.archive.util.VideoGovernanceRules.effectiveScopeConfirmed(
                    Boolean.TRUE.equals(asset.getMachineScopeConfirmed()), !scopeTiers.isEmpty());
            List<String> missingItems = com.liqiang.archive.util.VideoGovernanceRules.missingCompletenessItems(
                    asset.getVideoContentType(), effectiveScopeConfirmed, asset.getApplicationTags(),
                    AssetView.contentTagsFor(asset), asset.getDemonstrationTags(), asset.getVideoUsageChannels());
            boolean complete = missingItems.isEmpty();
            return new VideoAssetView(
                    asset.getId(), asset.getModel().getId(), asset.getModel().getName(),
                    asset.getModel().getModelCode(), asset.getModel().getCategory(),
                    null, null, "",
                    asset.getCategory(), asset.getFileName(), AssetView.contentTagsFor(asset),
                    asset.getApplicationTags(), asset.getVideoContentType(), asset.getVideoUsageStatus(),
                    scopeTiers.stream().findFirst().map(ProductMachineTier::getId).orElse(null),
                    scopeTiers.stream().findFirst().map(item -> AssetView.firstNonBlank(
                            item.getTierLabel(), item.getTierCode())).orElse(null),
                    scopeTiers.stream().map(ProductMachineTier::getId).toList(),
                    scopeTiers.stream().map(item -> AssetView.firstNonBlank(
                            item.getTierLabel(), item.getTierCode())).toList(),
                    asset.getFeatureTags(), asset.getDemonstrationTags(),
                    asset.getVideoTitle(), asset.getMachineScopeConfirmed(),
                    asset.getFileHash(),
                    asset.getVideoDurationSeconds(), asset.getVideoWidth(), asset.getVideoHeight(),
                    personalFavorite,
                    asset.getVideoUsageChannels(), asset.getVideoContentStatus(),
                    complete, missingItems,
                    com.liqiang.archive.util.VideoGovernanceRules.allowedNextStatuses(asset.getVideoContentStatus(), complete),
                    asset.getCoverFileName(), asset.getCreatedAt());
        }
    }

    public record AssetVariantAssignment(Long assetId, Long variantId) {
    }

    public record BatchAssetVariantRequest(List<AssetVariantAssignment> assignments) {
    }

    public record BatchResult(int affected) {
    }

    public record ReorderItem(Long id, Integer sortOrder) {
    }

    public record ReorderRequest(List<ReorderItem> items) {
    }

    /** 分布统计项 */
    public record DistributionItem(String name, long count) {
    }

    // ====== 机型档位（ProductMachineTier）======

public record MachineTierView(
            Long id,
            Long modelId,
            String modelName,
            String tierCode,
            String tierLabel,
            java.math.BigDecimal roundDiameterMin,
            java.math.BigDecimal roundDiameterMax,
            java.math.BigDecimal rectLengthMin,
            java.math.BigDecimal rectLengthMax,
            java.math.BigDecimal rectWidthMin,
            java.math.BigDecimal rectWidthMax,
            String machineSize,
            String netWeightKg,
            String cartonSize,
            String woodBoxSize,
            String grossWeightKg,
            Integer sortOrder,
            String remark,
            Long variantCount
    ) {
        public static MachineTierView from(ProductMachineTier tier) {
            return new MachineTierView(
                    tier.getId(),
                    tier.getModel().getId(),
                    tier.getModel().getName(),
                    tier.getTierCode(),
                    tier.getTierLabel(),
                    tier.getRoundDiameterMin(),
                    tier.getRoundDiameterMax(),
                    tier.getRectLengthMin(),
                    tier.getRectLengthMax(),
                    tier.getRectWidthMin(),
                    tier.getRectWidthMax(),
                    tier.getMachineSize(),
                    tier.getNetWeightKg(),
                    tier.getCartonSize(),
                    tier.getWoodBoxSize(),
                    tier.getGrossWeightKg(),
                    tier.getSortOrder(),
                    tier.getRemark(),
                    null
            );
        }

        public static MachineTierView from(ProductMachineTier tier, long variantCount) {
            return new MachineTierView(
                    tier.getId(),
                    tier.getModel().getId(),
                    tier.getModel().getName(),
                    tier.getTierCode(),
                    tier.getTierLabel(),
                    tier.getRoundDiameterMin(),
                    tier.getRoundDiameterMax(),
                    tier.getRectLengthMin(),
                    tier.getRectLengthMax(),
                    tier.getRectWidthMin(),
                    tier.getRectWidthMax(),
                    tier.getMachineSize(),
                    tier.getNetWeightKg(),
                    tier.getCartonSize(),
                    tier.getWoodBoxSize(),
                    tier.getGrossWeightKg(),
                    tier.getSortOrder(),
                    tier.getRemark(),
                    variantCount
            );
        }
    }

    public record MachineTierRequest(
            String tierCode,
            String tierLabel,
            java.math.BigDecimal roundDiameterMin,
            java.math.BigDecimal roundDiameterMax,
            java.math.BigDecimal rectLengthMin,
            java.math.BigDecimal rectLengthMax,
            java.math.BigDecimal rectWidthMin,
            java.math.BigDecimal rectWidthMax,
            String machineSize,
            String netWeightKg,
            String cartonSize,
            String woodBoxSize,
            String grossWeightKg,
            Integer sortOrder,
            String remark
    ) {}

}
