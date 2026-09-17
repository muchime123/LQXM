package com.liqiang.archive.dto;

import com.liqiang.archive.entity.PackagingAsset;
import com.liqiang.archive.entity.PackagingModel;
import com.liqiang.archive.entity.PackagingVariant;
import com.liqiang.archive.entity.PackagingVariantHistory;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public class PackagingDtos {

    public record ModelSummary(
            Long id,
            String name,
            String nameEn,
            String modelCode,
            String packagingKind,
            String filmForm,
            String shapeType,
            String material,
            String colorStyle,
            String capacityMl,
            String applicable,
            String moq,
            String unitPrice,
            String leadTime,
            Boolean customizable,
            String status,
            String tags,
            String defaultImage,
            Long defaultImageAssetId,
            Integer sortOrder,
            Boolean websiteEnabled,
            Integer websiteSortOrder,
            long variantCount,
            long assetCount
    ) {
        public static ModelSummary from(PackagingModel m, long variantCount, long assetCount) {
            return from(m, variantCount, assetCount, null);
        }

        public static ModelSummary from(PackagingModel m, long variantCount, long assetCount, Long defaultImageAssetId) {
            return new ModelSummary(
                    m.getId(), m.getName(), m.getNameEn(), m.getModelCode(),
                    m.getPackagingKind(), m.getFilmForm(), m.getShapeType(),
                    m.getMaterial(), m.getColorStyle(), m.getCapacityMl(),
                    m.getApplicable(), m.getMoq(), m.getUnitPrice(), m.getLeadTime(),
                    m.getCustomizable(), m.getStatus(), m.getTags(),
                    m.getDefaultImage(), defaultImageAssetId, m.getSortOrder(),
                    m.getWebsiteEnabled(), m.getWebsiteSortOrder(),
                    variantCount, assetCount
            );
        }
    }

    public record ModelView(
            Long id,
            String name,
            String nameEn,
            String modelCode,
            String packagingKind,
            String filmForm,
            String shapeType,
            String material,
            String colorStyle,
            String capacityMl,
            String applicable,
            String advantages,
            String moq,
            String unitPrice,
            String leadTime,
            Boolean customizable,
            String status,
            String tags,
            String defaultImage,
            Integer sortOrder,
            Boolean websiteEnabled,
            Integer websiteSortOrder,
            String remark,
            String sourceFile,
            String sourceSheet,
            Integer sourceRowNumber,
            Long importBatchId,
            LocalDateTime createdAt,
            LocalDateTime updatedAt
    ) {
        public static ModelView from(PackagingModel m) {
            return new ModelView(
                    m.getId(), m.getName(), m.getNameEn(), m.getModelCode(),
                    m.getPackagingKind(), m.getFilmForm(), m.getShapeType(),
                    m.getMaterial(), m.getColorStyle(), m.getCapacityMl(),
                    m.getApplicable(), m.getAdvantages(),
                    m.getMoq(), m.getUnitPrice(), m.getLeadTime(),
                    m.getCustomizable(), m.getStatus(), m.getTags(),
                    m.getDefaultImage(), m.getSortOrder(), m.getWebsiteEnabled(), m.getWebsiteSortOrder(), m.getRemark(),
                    m.getSourceFile(), m.getSourceSheet(), m.getSourceRowNumber(),
                    m.getImportBatchId(), m.getCreatedAt(), m.getUpdatedAt()
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

    public record VariantView(
            Long id,
            String variantName,
            String capacityMl,
            String shapeType,
            String lengthMm,
            String widthMm,
            String heightMm,
            String topDiameterMm,
            String bottomDiameterMm,
            String weightG,
            String boxesPerRoll,
            String cartonSize,
            String cartonWeight,
            String cartonVolume,
            String variantMoq,
            String materialDetail,
            String filmMaterial,
            String filmSupplyType,
            String filmFeatures,
            String priceUnit,
            String pricingNotes,
            String dealerPrice,
            String retailPrice,
            String dealerPriceUsd,
            String retailPriceUsd,
            String peelability,
            String thicknessUm,
            BigDecimal tier5099,
            BigDecimal tier100299,
            BigDecimal tier300499,
            BigDecimal tier500999,
            BigDecimal tier10001999,
            BigDecimal tier2000Up,
            BigDecimal plateFee,
            Integer sortOrder,
            String remark,
            LocalDateTime createdAt,
            LocalDateTime updatedAt
    ) {
        public static VariantView from(PackagingVariant v) {
            return new VariantView(
                    v.getId(), v.getVariantName(),
                    v.getCapacityMl(), v.getShapeType(),
                    v.getLengthMm(), v.getWidthMm(), v.getHeightMm(),
                    v.getTopDiameterMm(), v.getBottomDiameterMm(),
                    v.getWeightG(), v.getBoxesPerRoll(),
                    v.getCartonSize(), v.getCartonWeight(), v.getCartonVolume(), v.getVariantMoq(),
                    v.getMaterialDetail(),
                    v.getFilmMaterial(), v.getFilmSupplyType(), v.getFilmFeatures(),
                    v.getPriceUnit(), v.getPricingNotes(),
                    v.getDealerPrice(), v.getRetailPrice(),
                    v.getDealerPriceUsd(), v.getRetailPriceUsd(),
                    v.getPeelability(), v.getThicknessUm(),
                    v.getTier5099(), v.getTier100299(), v.getTier300499(),
                    v.getTier500999(), v.getTier10001999(), v.getTier2000Up(),
                    v.getPlateFee(),
                    v.getSortOrder(), v.getRemark(),
                    v.getCreatedAt(), v.getUpdatedAt()
            );
        }
    }

    public record AssetView(
            Long id,
            String ownerType,
            Long variantId,
            String category,
            String fileName,
            String tags,
            Integer sortOrder,
            Boolean defaultAsset,
            String sourceFile,
            LocalDateTime createdAt,
            String coverFileName
    ) {
        public static AssetView from(PackagingAsset a) {
            return new AssetView(
                    a.getId(),
                    a.getVariant() != null ? "packaging_variant" : "packaging_model",
                    a.getVariant() != null ? a.getVariant().getId() : null,
                    a.getCategory(), a.getFileName(), a.getTags(),
                    a.getSortOrder(), a.getDefaultAsset(),
                    a.getSourceFile(), a.getCreatedAt(),
                    a.getCoverFileName()
            );
        }
    }

    public record BatchDeleteRequest(List<Long> ids) {}
    public record BatchResult(int affected) {}
    public record MoveVariantRequest(Long targetModelId) {}
    public record AssetTagsRequest(String tags) {}
    public record ReorderItem(Long id, Integer sortOrder) {}
    public record ReorderRequest(List<ReorderItem> items) {}
    public record BatchUpdateRequest(List<Long> ids, Map<String, Object> payload) {}

    public record VariantHistoryView(
            Long id,
            Long variantId,
            Long modelId,
            String fieldKey,
            String fieldLabel,
            String oldValue,
            String newValue,
            String changeType,
            String changeSource,
            String changeReason,
            String sourceFile,
            String sourceSheet,
            String changedBy,
            LocalDateTime createdAt
    ) {
        public static VariantHistoryView from(PackagingVariantHistory h) {
            return new VariantHistoryView(
                    h.getId(),
                    h.getVariant() != null ? h.getVariant().getId() : null,
                    h.getModel() != null ? h.getModel().getId() : null,
                    h.getFieldKey(),
                    h.getFieldLabel(),
                    h.getOldValue(),
                    h.getNewValue(),
                    h.getChangeType(),
                    h.getChangeSource(),
                    h.getChangeReason(),
                    h.getSourceFile(),
                    h.getSourceSheet(),
                    h.getChangedBy(),
                    h.getCreatedAt()
            );
        }
    }

}
