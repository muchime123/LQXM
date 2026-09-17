package com.liqiang.archive.dto;

import java.util.List;
import java.util.Map;

/**
 * 包材模具 Excel 导入相关 DTO。
 * <p>v2: 以 PackagingMoldGroup（规格族）为中心，不再创建 PackagingModel。
 * 导入时用户选择目标包材系列，Excel 产品名成为规格族，规格用尺寸容差匹配已有 Variant。
 */
public class PackagingMoldImportDtos {

    /**
     * 解析后的单行记录。一行 Excel 可能因多高度/多配件展开为多条。
     * <p>disposition: new | merge | unverified
     * <p>variantMatch: matched | multi_match | no_match | pending
     */
    public record MoldImportRow(
            Integer sourceRowNumber,
            String productName,
            String moldCode,
            String baseMoldCode,
            String moldTypeCode,
            Integer cavityCount,
            String moldCategory,
            String compartmentCount,
            String caliber,
            String lengthMm,
            String widthMm,
            String topDiameterMm,
            String heightMm,
            String rawHeightText,
            String capacityMl,
            String surfaceStructure,
            String accessories,
            List<String> accessoryList,
            String bottomEngraving,
            String engravingType,
            String rawRowText,
            String disposition,
            String issue,
            String variantMatch,
            List<VariantMatchCandidate> matchCandidates,
            Long selectedVariantId,
            Long matchedModelId,
            String matchedModelName
    ) {
    }

    public record VariantMatchCandidate(
            Long variantId,
            String variantName,
            double score,
            String[] diffFields,
            boolean isDefault,
            Long modelId,
            String modelName,
            String lengthMm,
            String widthMm,
            String topDiameterMm,
            String heightMm
    ) {
    }

    public record ImportError(
            Integer rowNumber,
            String field,
            String message,
            String severity
    ) {
    }

    public record MoldImportPreview(
            Long modelId,
            String modelName,
            String fileName,
            String sheetName,
            int totalRows,
            int expandedRows,
            int newCount,
            int mergeCount,
            int unverifiedCount,
            int matchedCount,
            int multiMatchCount,
            int noMatchCount,
            List<String> headers,
            List<MoldImportRow> rows,
            List<ImportError> errors
    ) {
    }

    /**
     * 确认落库请求。
     * <p>preview 为前端回传的完整预览结果。
     * rowIndexes 指定要落库的行序号；为空则落库除 unverified 外的所有行。
     * variantOverrides: rowIndex → variantId，用户手动选择的规格（覆盖自动匹配）。
     * modelOverrides: rowIndex → modelId，用户手动选择的系列（覆盖自动匹配）。
     */
    public record MoldImportConfirmRequest(
            MoldImportPreview preview,
            List<Integer> rowIndexes,
            Long importBatchId,
            Map<Integer, Long> variantOverrides,
            Map<Integer, Long> modelOverrides
    ) {
    }

    public record MoldImportConfirmResult(
            Long importBatchId,
            int createdMolds,
            int mergedMolds,
            int createdGroups,
            int createdVariantLinks,
            int createdAccessories,
            int skipped,
            List<String> issues
    ) {
    }

}
