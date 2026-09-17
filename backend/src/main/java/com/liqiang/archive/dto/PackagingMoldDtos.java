package com.liqiang.archive.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.liqiang.archive.entity.MoldAccessory;
import com.liqiang.archive.entity.MoldVariantLink;
import com.liqiang.archive.entity.PackagingMold;
import com.liqiang.archive.entity.PackagingMoldGroup;
import com.liqiang.archive.entity.PackagingMoldHistory;
import com.liqiang.archive.entity.PackagingVariant;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 包材模具相关 DTO。
 * <p>对应 V50 三张表：PackagingMold / MoldVariantLink / MoldAccessory。
 */
public class PackagingMoldDtos {

    // ====== Mold ======

    public record MoldSummary(
            Long id,
            Long modelId,
            String modelName,
            Long groupId,
            String groupName,
            String moldCode,
            String baseMoldCode,
            String moldTypeCode,
            Integer cavityCount,
            String moldRole,
            String moldCategory,
            String compartmentCount,
            String surfaceStructure,
            String bottomEngraving,
            String engravingType,
            String status,
            Boolean websiteEnabled,
            Integer websiteSortOrder,
            long variantCount,
            long accessoryCount,
            String representativeCaliber,
            String representativeHeight
    ) {
        public static MoldSummary from(PackagingMold m, long variantCount, long accessoryCount,
                                       String caliber, String height) {
            return new MoldSummary(
                    m.getId(),
                    m.getModel() != null ? m.getModel().getId() : null,
                    m.getModel() != null ? m.getModel().getName() : null,
                    m.getGroup() != null ? m.getGroup().getId() : null,
                    m.getGroup() != null ? m.getGroup().getName() : null,
                    m.getMoldCode(),
                    m.getBaseMoldCode(),
                    m.getMoldTypeCode(),
                    m.getCavityCount(),
                    m.getMoldRole(),
                    m.getMoldCategory(),
                    m.getCompartmentCount(),
                    m.getSurfaceStructure(),
                    m.getBottomEngraving(),
                    m.getEngravingType(),
                    m.getStatus(), m.getWebsiteEnabled(), m.getWebsiteSortOrder(),
                    variantCount,
                    accessoryCount,
                    caliber,
                    height
            );
        }
    }

    /** 分页单位为规格族，但 content 仍返回本页规格族下的完整模具列表。 */
    public record MoldGroupPage(
            List<MoldSummary> content,
            long totalElements,
            int totalPages,
            int size,
            int number,
            int numberOfElements,
            boolean first,
            boolean last,
            boolean empty
    ) {}

    public record MoldView(
            Long id,
            Long modelId,
            String modelName,
            Long groupId,
            String groupName,
            String moldCode,
            String baseMoldCode,
            String moldTypeCode,
            Integer cavityCount,
            String moldRole,
            String moldCategory,
            String compartmentCount,
            String surfaceStructure,
            String bottomEngraving,
            String engravingType,
            String rawAccessoriesText,
            String rawCaliber,
            String rawHeight,
            String status,
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
        public static MoldView from(PackagingMold m) {
            return new MoldView(
                    m.getId(),
                    m.getModel() != null ? m.getModel().getId() : null,
                    m.getModel() != null ? m.getModel().getName() : null,
                    m.getGroup() != null ? m.getGroup().getId() : null,
                    m.getGroup() != null ? m.getGroup().getName() : null,
                    m.getMoldCode(),
                    m.getBaseMoldCode(),
                    m.getMoldTypeCode(),
                    m.getCavityCount(),
                    m.getMoldRole(),
                    m.getMoldCategory(),
                    m.getCompartmentCount(),
                    m.getSurfaceStructure(),
                    m.getBottomEngraving(),
                    m.getEngravingType(),
                    m.getRawAccessoriesText(),
                    m.getRawCaliber(),
                    m.getRawHeight(),
                    m.getStatus(), m.getWebsiteEnabled(), m.getWebsiteSortOrder(),
                    m.getRemark(),
                    m.getSourceFile(),
                    m.getSourceSheet(),
                    m.getSourceRowNumber(),
                    m.getImportBatchId(),
                    m.getCreatedAt(),
                    m.getUpdatedAt()
            );
        }
    }

    /**
     * 模具详情：基础信息 + 关联规格列表 + 配件列表。
     */
    public record MoldDetail(
            MoldView mold,
            List<MoldVariantLinkView> variantLinks,
            List<MoldAccessoryView> accessories
    ) {
    }

    /**
     * 创建/更新模具的载荷。
     * <p>moldCode 变更时需保证唯一；modelId 必填。groupId 可选，携带时必须与 modelId 属于同一系列。
     * 字段 {@code bottomEngraving} 历史拼写为 {@code bottomEnraving}（漏 g），通过 {@link JsonAlias}
     * 兼容旧客户端写入，序列化统一使用正确拼写。
     */
    public record MoldPayload(
            Long modelId,
            Long groupId,
            String moldCode,
            String baseMoldCode,
            String moldTypeCode,
            Integer cavityCount,
            String moldRole,
            String moldCategory,
            String compartmentCount,
            String surfaceStructure,
            @JsonAlias("bottomEnraving") String bottomEngraving,
            String engravingType,
            String rawAccessoriesText,
            String rawCaliber,
            String rawHeight,
            String status,
            Boolean websiteEnabled,
            Integer websiteSortOrder,
            String remark,
            String sourceFile,
            String sourceSheet,
            Integer sourceRowNumber,
            Long importBatchId
    ) {
        public void applyToEntity(PackagingMold m, com.liqiang.archive.entity.PackagingModel model,
                                   PackagingMoldGroup group) {
            m.setModel(model);
            m.setGroup(group);
            m.setMoldCode(moldCode);
            m.setBaseMoldCode(baseMoldCode);
            m.setMoldTypeCode(moldTypeCode);
            m.setCavityCount(cavityCount);
            if (moldRole != null) {
                m.setMoldRole(moldRole);
            }
            m.setMoldCategory(moldCategory);
            m.setCompartmentCount(compartmentCount);
            m.setSurfaceStructure(surfaceStructure);
            m.setBottomEngraving(bottomEngraving);
            m.setEngravingType(engravingType);
            m.setRawAccessoriesText(rawAccessoriesText);
            m.setRawCaliber(rawCaliber);
            m.setRawHeight(rawHeight);
            if (status != null) {
                m.setStatus(status);
            }
            if (websiteEnabled != null) m.setWebsiteEnabled(websiteEnabled);
            if (websiteSortOrder != null) m.setWebsiteSortOrder(websiteSortOrder);
            m.setRemark(remark);
            m.setSourceFile(sourceFile);
            m.setSourceSheet(sourceSheet);
            m.setSourceRowNumber(sourceRowNumber);
            m.setImportBatchId(importBatchId);
        }
    }

    // ====== Variant Link ======

    public record MoldVariantLinkView(
            Long id,
            Long moldId,
            Long variantId,
            String variantName,
            String variantHeight,
            String variantCapacity,
            String relationType,
            Boolean isDefault,
            String remark,
            LocalDateTime createdAt
    ) {
        public static MoldVariantLinkView from(MoldVariantLink l) {
            PackagingVariant v = l.getVariant();
            return new MoldVariantLinkView(
                    l.getId(),
                    l.getMold() != null ? l.getMold().getId() : null,
                    v != null ? v.getId() : null,
                    v != null ? v.getVariantName() : null,
                    v != null ? v.getHeightMm() : null,
                    v != null ? v.getCapacityMl() : null,
                    l.getRelationType(),
                    l.getIsDefault(),
                    l.getRemark(),
                    l.getCreatedAt()
            );
        }
    }

    /**
     * 按规格的模具汇总摘要，用于规格列表页快速显示：默认模具 + 其他模具数量。
     */
    public record VariantMoldSummary(
            Long variantId,
            Long defaultMoldId,
            String defaultMoldCode,
            String defaultMoldStatus,
            Long totalMoldCount
    ) {
    }

    /**
     * 按规格反查模具时的精简视图，携带关联关系信息。
     */
    public record MoldByVariantView(
            Long linkId,
            Long moldId,
            String moldCode,
            String baseMoldCode,
            String moldTypeCode,
            Integer cavityCount,
            String moldRole,
            String status,
            String relationType,
            Boolean isDefault
    ) {
        public static MoldByVariantView from(MoldVariantLink l) {
            PackagingMold m = l.getMold();
            return new MoldByVariantView(
                    l.getId(),
                    m != null ? m.getId() : null,
                    m != null ? m.getMoldCode() : null,
                    m != null ? m.getBaseMoldCode() : null,
                    m != null ? m.getMoldTypeCode() : null,
                    m != null ? m.getCavityCount() : null,
                    m != null ? m.getMoldRole() : null,
                    m != null ? m.getStatus() : null,
                    l.getRelationType(),
                    l.getIsDefault()
            );
        }
    }

    public record MoldVariantLinkPayload(
            Long moldId,
            Long variantId,
            String relationType,
            Boolean isDefault,
            String remark
    ) {
        public void applyToEntity(MoldVariantLink l,
                                   PackagingMold mold,
                                   PackagingVariant variant) {
            l.setMold(mold);
            l.setVariant(variant);
            if (relationType != null) {
                l.setRelationType(relationType);
            }
            if (isDefault != null) {
                l.setIsDefault(isDefault);
            }
            l.setRemark(remark);
        }
    }

    // ====== Accessory ======

    public record MoldAccessoryView(
            Long id,
            Long moldId,
            String accessoryType,
            String accessoryCode,
            Integer quantity,
            String sharedScope,
            String remark,
            LocalDateTime createdAt
    ) {
        public static MoldAccessoryView from(MoldAccessory a) {
            return new MoldAccessoryView(
                    a.getId(),
                    a.getMold() != null ? a.getMold().getId() : null,
                    a.getAccessoryType(),
                    a.getAccessoryCode(),
                    a.getQuantity(),
                    a.getSharedScope(),
                    a.getRemark(),
                    a.getCreatedAt()
            );
        }
    }

    public record MoldAccessoryPayload(
            Long moldId,
            String accessoryType,
            String accessoryCode,
            Integer quantity,
            String sharedScope,
            String remark
    ) {
        public void applyToEntity(MoldAccessory a, PackagingMold mold) {
            a.setMold(mold);
            a.setAccessoryType(accessoryType);
            a.setAccessoryCode(accessoryCode);
            if (quantity != null) {
                a.setQuantity(quantity);
            }
            if (sharedScope != null) {
                a.setSharedScope(sharedScope);
            }
            a.setRemark(remark);
        }
    }

    // ====== 统计 ======

    public record MoldStats(
            long totalMolds,
            long inUseCount,
            long stoppedCount,
            long repairCount,
            long unverifiedCount,
            long mainMoldCount,
            long accessoryMoldCount,
            long variantLinkCount,
            long accessoryItemCount
    ) {
    }

    public record BatchResult(int count) {
    }

    // ====== 变更历史 ======

    /**
     * 模具字段变更历史条目。
     * <p>状态变更（change_type=status_change）与普通字段更新（field_update）共用此视图，
     * 前端可按 change_type 区分展示样式。
     */
    public record MoldHistoryView(
            Long id,
            Long moldId,
            String fieldKey,
            String fieldLabel,
            String oldValue,
            String newValue,
            String changeType,
            String changeSource,
            String changeReason,
            String changedBy,
            LocalDateTime createdAt
    ) {
        public static MoldHistoryView from(PackagingMoldHistory h) {
            return new MoldHistoryView(
                    h.getId(),
                    h.getMold() != null ? h.getMold().getId() : null,
                    h.getFieldKey(),
                    h.getFieldLabel(),
                    h.getOldValue(),
                    h.getNewValue(),
                    h.getChangeType(),
                    h.getChangeSource(),
                    h.getChangeReason(),
                    h.getChangedBy(),
                    h.getCreatedAt()
            );
        }
    }

    // ====== 数据质量审计 ======

    /**
     * 单一违规项的精简引用，按类型分桶返回，便于前端在数据质量页中展示和跳转。
     * 所有 ID 字段均可能为 null（取决于 issue 类型）。
     */
    public record DataQualityIssueRef(
            Long moldId,
            String moldCode,
            Long variantId,
            String variantName,
            Long groupId,
            String groupName,
            Long modelId,
            String modelName,
            Long linkId,
            String detail
    ) {}

    /**
     * 数据质量审计报告，按 issue 类型分桶：
     * <ul>
     *   <li>moldModelGroupMismatch:  模具 .model_id 与模具 .group.model_id 不一致（双归属重复真相）</li>
     *   <li>moldWithoutGroup:        模具有 model_id 但未分配规格族 group_id（包材详情中会落在分组之外）</li>
     *   <li>crossModelLink:          MoldVariantLink 跨越了不同大系列（模具 model ≠ 规格 model）</li>
     *   <li>moldWithoutVariant:      模具未关联任何规格（待关联处理入口）</li>
     *   <li>variantWithoutMold:      规格没有任何模具关联（商品主入口缺资产）</li>
     *   <li>duplicateGroupNames:     同一系列下存在重复规格族名称（V55 已加约束，用于清理遗留或并发竞态诊断）</li>
     *   <li>moldCodeSharedCrossModels: 同一模号被分配到不同的包材大系列</li>
     *   <li>defaultMoldInactive:     默认模具被标记为停用/维修，但仍为该规格的默认模具</li>
     *   <li>moldDeletedVariantReference: 模具关联的规格所属 model 已被软删除（残留引用）</li>
     * </ul>
     */
    public record MoldDataQualityReport(
            List<DataQualityIssueRef> moldModelGroupMismatch,
            List<DataQualityIssueRef> moldWithoutGroup,
            List<DataQualityIssueRef> crossModelLink,
            List<DataQualityIssueRef> moldWithoutVariant,
            List<DataQualityIssueRef> variantWithoutMold,
            List<DataQualityIssueRef> duplicateGroupNames,
            List<DataQualityIssueRef> moldCodeSharedCrossModels,
            List<DataQualityIssueRef> defaultMoldInactive,
            List<DataQualityIssueRef> moldDeletedVariantReference
    ) {}
}
