package com.liqiang.archive.service;

import com.liqiang.archive.dto.PackagingMoldImportDtos.ImportError;
import com.liqiang.archive.dto.PackagingMoldImportDtos.MoldImportConfirmRequest;
import com.liqiang.archive.dto.PackagingMoldImportDtos.MoldImportConfirmResult;
import com.liqiang.archive.dto.PackagingMoldImportDtos.MoldImportPreview;
import com.liqiang.archive.dto.PackagingMoldImportDtos.MoldImportRow;
import com.liqiang.archive.dto.PackagingMoldImportDtos.VariantMatchCandidate;
import com.liqiang.archive.entity.MoldAccessory;
import com.liqiang.archive.entity.MoldVariantLink;
import com.liqiang.archive.entity.MoldImportBatch;
import com.liqiang.archive.entity.MoldImportBatchItem;
import com.liqiang.archive.entity.PackagingModel;
import com.liqiang.archive.entity.PackagingMold;
import com.liqiang.archive.entity.PackagingMoldGroup;
import com.liqiang.archive.entity.PackagingVariant;
import com.liqiang.archive.repository.MoldAccessoryRepository;
import com.liqiang.archive.repository.MoldVariantLinkRepository;
import com.liqiang.archive.repository.MoldImportBatchRepository;
import com.liqiang.archive.repository.MoldImportBatchItemRepository;
import com.liqiang.archive.repository.PackagingModelRepository;
import com.liqiang.archive.repository.PackagingMoldGroupRepository;
import com.liqiang.archive.repository.PackagingMoldRepository;
import com.liqiang.archive.repository.PackagingVariantRepository;
import lombok.RequiredArgsConstructor;
import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 包材模具 Excel 预览导入服务 v2。
 * <p>核心变化：不再创建 PackagingModel/PackagingVariant，改为：
 * <ul>
 *   <li>用户选择目标包材系列（PackagingModel）</li>
 *   <li>Excel 产品名 → PackagingMoldGroup（规格族）</li>
 *   <li>用尺寸容差匹配已有 PackagingVariant</li>
 *   <li>创建 MoldVariantLink 连接模具与已有规格</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class PackagingMoldImportService {

    private final PackagingMoldRepository moldRepo;
    private final PackagingMoldGroupRepository groupRepo;
    private final PackagingModelRepository modelRepo;
    private final PackagingVariantRepository variantRepo;
    private final MoldVariantLinkRepository linkRepo;
    private final MoldAccessoryRepository accessoryRepo;
    private final PackagingMoldService moldService;
    private final AuditLogService auditLogService;
    private final MoldImportBatchRepository importBatchRepo;
    private final MoldImportBatchItemRepository importBatchItemRepo;

    private final DataFormatter formatter = new DataFormatter();

    private static final Pattern MOLD_CODE_PATTERN = Pattern.compile(
            "^([A-Za-z]+)?(\\d+)(?:-(\\d+)([A-Za-z]{1,4}))?$"
    );

    // 尺寸容差（严格匹配）
    private static final double DIMENSION_TOLERANCE = 2.0;   // 长宽/直径 ±2mm
    private static final double HEIGHT_TOLERANCE = 0.5;       // 高度 ±0.5mm
    // 宽松容差（模糊匹配候选，超出严格容差但在此范围内仍会显示，得分较低）
    private static final double DIMENSION_TOLERANCE_LOOSE = 10.0; // 模具口径与成品外尺寸允许 ±10mm 近似候选
    private static final double HEIGHT_TOLERANCE_LOOSE = 2.0;       // 高度 ±2mm

    /**
     * 模具规格匹配时排除的包材类型。
     * <p>盒多多工厂仅生产打包装(container)，不生产铝箔碗(bowl)与封口膜(sealing_film)，
     * 因此模具规格表匹配时无需考虑这两类规格。
     * 注意：bowl 类型中 PP 材质的圆碗是本厂产品，只排除铝箔材质的 bowl。
     */
    private static final Set<String> EXCLUDED_PACKAGING_KINDS = Set.of("sealing_film");

    /** bowl 类型中需要排除的材质关键词（铝箔碗排除，PP 圆碗保留） */
    private static final Set<String> BOWL_EXCLUDED_MATERIALS = Set.of("铝箔", "纯铝", "镀铝");

    /**
     * 模具规格匹配时排除的材质关键词。
     * <p>盒多多工厂不生产铝箔材质产品，因此铝箔盒系列的规格也不参与匹配。
     */
    private static final Set<String> EXCLUDED_MATERIAL_KEYWORDS = Set.of("铝箔", "纯铝", "镀铝");

    /**
     * 模具规格匹配时排除的系列名关键词（匹配 PackagingModel.name）。
     * <p>盒多多工厂不生产贴体盘等产品，系列名包含以下关键词时全部排除。
     */
    private static final Set<String> EXCLUDED_MODEL_NAME_KEYWORDS = Set.of("贴体");

    /**
     * 系列业务类别：用于按产品名关键词+尺寸识别 Excel 行所属系列。
     * <p>业务规则：
     * <ul>
     *   <li>打包盒系列：三明治盒、大闸蟹盒、鱼盒等（方盒/矩形盒）</li>
     *   <li>泡壳系列：月饼盒、蛋黄酥盒子</li>
     *   <li>圆碗系列：C 前缀或单个数字口径的圆碗/圆杯</li>
     *   <li>酱料盒系列：小方盒/小圆盒（酱料类）</li>
     * </ul>
     * <p>核心原则：口径形状（圆/方）优先于产品名关键词。
     * 口径为圆（C 前缀或单个数字）→ 圆碗系列；
     * 口径为方（长×宽）→ 打包盒系列。
     */
    private enum SeriesCategory {
        PACKING_BOX,   // 打包盒系列（含大型盒：三明治/大闸蟹/鱼盒等）
        BLISTER,       // 泡壳系列（月饼/蛋黄酥）
        ROUND_BOWL,    // 圆碗系列（C 前缀或单个数字口径）
        SAUCE_CUP,     // 酱料盒系列（小方盒/小圆盒）
        UNKNOWN        // 未识别
    }

    /** 产品名关键词 → 系列类别。带 [ROUND] 前缀的仅圆碗口径生效，[SQUARE] 前缀的仅方盒口径生效。 */
    private static final Map<SeriesCategory, List<String>> PRODUCT_NAME_KEYWORDS = Map.of(
            SeriesCategory.PACKING_BOX, List.of("[SQUARE]三明治", "[SQUARE]打包盒", "[SQUARE]大闸蟹", "[SQUARE]鱼盒", "[SQUARE]蟹盒"),
            SeriesCategory.BLISTER, List.of("[SQUARE]月饼", "[SQUARE]蛋黄酥", "[SQUARE]泡壳"),
            SeriesCategory.ROUND_BOWL, List.of("[ROUND]圆碗", "[ROUND]圆杯"),
            SeriesCategory.SAUCE_CUP, List.of("[ROUND]酱料", "[ROUND]调料")
    );

    /** PackagingModel.name 关键词 → 系列类别（用于反向识别已有系列） */
    private static final Map<SeriesCategory, List<String>> MODEL_NAME_KEYWORDS = Map.of(
            SeriesCategory.PACKING_BOX, List.of("打包盒", "三明治", "大闸蟹", "鱼盒", "蟹盒", "大型"),
            SeriesCategory.BLISTER, List.of("泡壳", "月饼"),
            SeriesCategory.ROUND_BOWL, List.of("圆碗", "圆杯", "圆桶", "圆盒", "汤碗"),
            SeriesCategory.SAUCE_CUP, List.of("酱料", "调料")
    );

    /** 跨系列回退罚分系数：非目标系列的候选得分乘以该系数 */
    private static final double CROSS_SERIES_PENALTY = 0.7;

    // ====== 预览 ======

    @Transactional(readOnly = true)
    public MoldImportPreview preview(MultipartFile file, Long modelId) throws IOException {
        // 模式判断：modelId == null → 全系列自动匹配
        boolean autoMatch = (modelId == null);

        PackagingModel model = null;
        List<PackagingVariant> existingVariants;
        Map<Long, PackagingModel> variantModelMap = new HashMap<>(); // variantId → model
        List<PackagingModel> allModels = new ArrayList<>();

        if (autoMatch) {
            // 加载全部系列
            allModels = modelRepo.findAll();
            existingVariants = variantRepo.findAll();
            for (PackagingVariant v : existingVariants) {
                variantModelMap.put(v.getId(), v.getModel());
            }
        } else {
            model = modelRepo.findById(modelId)
                    .orElseThrow(() -> new IllegalArgumentException("包材系列不存在: " + modelId));
            allModels = List.of(model);
            existingVariants = variantRepo.findByModelIdOrderBySortOrderAscIdAsc(modelId);
            for (PackagingVariant v : existingVariants) {
                variantModelMap.put(v.getId(), model);
            }
        }

        // 盒多多工厂仅生产打包装，模具规格匹配时排除铝箔碗、封口膜、铝箔材质、贴体系列等规格
        existingVariants = existingVariants.stream()
                .filter(v -> {
                    PackagingModel m = variantModelMap.get(v.getId());
                    if (m == null) return true;
                    if (EXCLUDED_PACKAGING_KINDS.contains(m.getPackagingKind())) return false;
                    // bowl 类型：只排除铝箔材质的 bowl（铝箔碗），PP 圆碗保留
                    if ("bowl".equals(m.getPackagingKind())) {
                        String mat = m.getMaterial();
                        if (mat != null) {
                            for (String kw : BOWL_EXCLUDED_MATERIALS) {
                                if (mat.contains(kw)) return false;
                            }
                        }
                    }
                    String material = m.getMaterial();
                    if (material != null) {
                        for (String kw : EXCLUDED_MATERIAL_KEYWORDS) {
                            if (material.contains(kw)) return false;
                        }
                    }
                    String modelName = m.getName();
                    if (modelName != null) {
                        for (String kw : EXCLUDED_MODEL_NAME_KEYWORDS) {
                            if (modelName.contains(kw)) return false;
                        }
                    }
                    return true;
                })
                .toList();

        // 预取已有模号（仅查 moldCode 列，避免全量加载实体）
        Set<String> existingMoldCodes = new HashSet<>();
        moldRepo.findAllMoldCodes().forEach(existingMoldCodes::add);

        ZipSecureFile.setMaxFileCount(10_000);

        try (Workbook workbook = new XSSFWorkbook(file.getInputStream())) {
            Sheet sheet = workbook.getSheetAt(0);
            String sheetName = workbook.getSheetName(0);
            Row headerRow = sheet.getRow(0);

            Map<String, Integer> colMap = new HashMap<>();
            List<String> headers = new ArrayList<>();
            if (headerRow != null) {
                for (Cell cell : headerRow) {
                    String raw = formatter.formatCellValue(cell).trim();
                    headers.add(raw);
                    String key = mapHeaderKey(raw);
                    if (key != null) {
                        colMap.putIfAbsent(key, cell.getColumnIndex());
                    }
                }
            }

            List<MoldImportRow> rows = new ArrayList<>();
            List<ImportError> errors = new ArrayList<>();
            int totalRows = 0;

            String lastProductName = "";
            String lastCaliber = "";

            Map<String, Integer> seenInThisImport = new HashMap<>();

            for (int rowIndex = 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
                Row row = sheet.getRow(rowIndex);
                if (row == null) continue;
                if (isEmptyRow(row, colMap)) continue;
                totalRows++;

                String productName = getCell(row, colMap, "productName");
                if (productName.isBlank()) {
                    productName = lastProductName;
                } else {
                    lastProductName = productName;
                }

                String caliber = getCell(row, colMap, "caliber");
                if (caliber.isBlank()) {
                    // 先从产品名提取尺寸（如 "1914方盒" → "19×14"），再用 lastCaliber 兜底
                    caliber = extractCaliberFromName(productName);
                    if (caliber.isBlank()) {
                        caliber = lastCaliber;
                    }
                } else {
                    lastCaliber = caliber;
                }

                String moldCode = getCell(row, colMap, "moldCode").trim();
                String heights = getCell(row, colMap, "height");
                String capacity = getCell(row, colMap, "capacity");
                String accessories = getCell(row, colMap, "accessories");
                String bottomEngraving = getCell(row, colMap, "engraving");
                String compartment = getCell(row, colMap, "compartment");
                String surface = getCell(row, colMap, "surface");
                String category = getCell(row, colMap, "category");

                List<String> heightList = splitHeights(heights);
                List<String> capacityList = splitByPlus(capacity);
                int expandCount = Math.max(heightList.size(), capacityList.size());
                if (expandCount == 0) expandCount = 1;

                List<String> accessoryList = splitByPlus(accessories);
                ParsedMoldCode parsed = parseMoldCode(moldCode);
                boolean misaligned = detectMisalignment(compartment, capacity);

                for (int i = 0; i < expandCount; i++) {
                    String h = i < heightList.size() ? heightList.get(i) : "";
                    String c = i < capacityList.size() ? cleanCapacity(capacityList.get(i)) : "";

                    String disposition;
                    String issue = null;

                    if (moldCode.isBlank()) {
                        disposition = "unverified";
                        issue = "无模号";
                    } else if (parsed.numericOnly) {
                        disposition = "unverified";
                        issue = "纯数字模号，旧编号待确认";
                    } else if (misaligned) {
                        disposition = "unverified";
                        issue = "疑似错列（容量/格数列异常）";
                    } else if (existingMoldCodes.contains(moldCode)) {
                        disposition = "merge";
                        issue = "模号已存在，将合并配件/关联";
                    } else if (seenInThisImport.containsKey(moldCode)) {
                        disposition = "merge";
                        issue = "同次导入内重复模号";
                    } else {
                        disposition = "new";
                    }

                    seenInThisImport.put(moldCode, rowIndex);

                    // 尺寸匹配已有规格
                    String lengthMm = parseLength(caliber);
                    String widthMm = parseWidth(caliber);
                    String topDiameterMm = parseDiameter(caliber);

                    // 识别该行所属的业务系列类别（关键词优先，形状兜底）
                    SeriesCategory targetCategory = identifySeriesCategory(productName, lengthMm, widthMm, topDiameterMm);

                    // 先确定系列（按 targetCategory 优先，不受规格候选影响）
                    PackagingModel preferredModel = findModelByCategory(targetCategory, allModels);

                    // 1. 规格匹配
                    List<VariantMatchCandidate> candidates = matchVariants(
                            existingVariants, variantModelMap,
                            lengthMm, widthMm, topDiameterMm, h, targetCategory);

                    // 2. 系列确定：优先用 targetCategory 匹配到的系列，
                    //    规格候选的系列仅在 preferredModel 为 null 时作为兜底
                    Long matchedModelId = null;
                    String matchedModelName = null;
                    if (preferredModel != null) {
                        matchedModelId = preferredModel.getId();
                        matchedModelName = preferredModel.getName();
                    } else if (!candidates.isEmpty()) {
                        matchedModelId = candidates.get(0).modelId();
                        matchedModelName = candidates.get(0).modelName();
                    }

                    String variantMatch;
                    Long selectedVariantId = null;
                    if (candidates.isEmpty()) {
                        variantMatch = "no_match";
                    } else if (candidates.size() == 1) {
                        variantMatch = "matched";
                        selectedVariantId = candidates.get(0).variantId();
                    } else {
                        variantMatch = "multi_match";
                        // 候选已按得分降序排列：默认选择最高分，用户可在预览中改选后确认。
                        selectedVariantId = candidates.get(0).variantId();
                    }

                    rows.add(new MoldImportRow(
                            rowIndex + 1,
                            productName,
                            moldCode,
                            parsed.baseCode,
                            parsed.typeCode,
                            parsed.cavityCount,
                            category,
                            compartment,
                            caliber,
                            lengthMm,
                            widthMm,
                            topDiameterMm,
                            h,
                            heights,
                            c,
                            surface,
                            accessories,
                            accessoryList,
                            bottomEngraving,
                            classifyEngraving(bottomEngraving),
                            buildRawRowText(row),
                            disposition,
                            issue,
                            variantMatch,
                            candidates,
                            selectedVariantId,
                            matchedModelId,
                            matchedModelName
                    ));
                }

                if (moldCode.isBlank()) {
                    errors.add(new ImportError(rowIndex + 1, "模号", "模号为空", "warning"));
                }
                if (misaligned) {
                    errors.add(new ImportError(rowIndex + 1, "格数/容量",
                            "疑似错列：容量值出现在格数列", "warning"));
                }
            }

            int newCount = (int) rows.stream().filter(r -> "new".equals(r.disposition())).count();
            int mergeCount = (int) rows.stream().filter(r -> "merge".equals(r.disposition())).count();
            int unverifiedCount = (int) rows.stream().filter(r -> "unverified".equals(r.disposition())).count();
            int matchedCount = (int) rows.stream().filter(r -> "matched".equals(r.variantMatch())).count();
            int multiMatchCount = (int) rows.stream().filter(r -> "multi_match".equals(r.variantMatch())).count();
            int noMatchCount = (int) rows.stream().filter(r -> "no_match".equals(r.variantMatch())).count();

            // 行数阈值告警：展开后行数过多时提示用户缩小范围，避免全量匹配性能退化
            if (rows.size() > 500) {
                errors.add(new ImportError(null, "行数",
                        "展开后共 " + rows.size() + " 行，超过 500 行阈值。建议限定单一系列导入以提升匹配性能",
                        "warning"));
            }

            return new MoldImportPreview(
                    autoMatch ? null : model.getId(),
                    autoMatch ? "全系列自动匹配" : model.getName(),
                    file.getOriginalFilename(),
                    sheetName,
                    totalRows,
                    rows.size(),
                    newCount,
                    mergeCount,
                    unverifiedCount,
                    matchedCount,
                    multiMatchCount,
                    noMatchCount,
                    headers,
                    rows,
                    errors
            );
        }
    }

    // ====== 确认落库 ======

    @Transactional
    public MoldImportConfirmResult confirm(MoldImportPreview preview, MoldImportConfirmRequest request) {
        Long modelId = preview.modelId();
        boolean autoMatch = (modelId == null);

        // 全局 model（非 autoMatch 模式）；autoMatch 模式下按行获取
        PackagingModel globalModel = null;
        if (!autoMatch) {
            globalModel = modelRepo.findById(modelId)
                    .orElseThrow(() -> new IllegalArgumentException("包材系列不存在: " + modelId));
        }

        // autoMatch 模式下按 matchedModelId 缓存 model
        Map<Long, PackagingModel> modelCache = new HashMap<>();

        Long batchId = request.importBatchId() != null
                ? request.importBatchId()
                : System.currentTimeMillis();
        String sourceFile = preview.fileName();
        String sourceSheet = preview.sheetName();
        if (importBatchRepo.existsById(batchId)) {
            throw new IllegalArgumentException("导入批次已存在，请勿重复提交: " + batchId);
        }
        MoldImportBatch importBatch = new MoldImportBatch();
        importBatch.setId(batchId);
        importBatch.setSourceFile(sourceFile);
        importBatch.setSourceSheet(sourceSheet);
        importBatch.setStatus("running");
        importBatchRepo.save(importBatch);

        Set<Integer> selectedIndexes = request.rowIndexes() != null
                ? new HashSet<>(request.rowIndexes())
                : null;
        Map<Integer, Long> overrides = request.variantOverrides() != null
                ? request.variantOverrides()
                : Map.of();
        Map<Integer, Long> modelOverridesMap = request.modelOverrides() != null
                ? request.modelOverrides()
                : Map.of();

        // 按模号聚合
        Map<String, List<MoldImportRow>> rowsByMold = new LinkedHashMap<>();
        int skipped = 0;
        for (int i = 0; i < preview.rows().size(); i++) {
            MoldImportRow row = preview.rows().get(i);
            if ("unverified".equals(row.disposition())) {
                skipped++;
                continue;
            }
            if (selectedIndexes != null && !selectedIndexes.contains(i)) {
                skipped++;
                continue;
            }
            if (row.moldCode() == null || row.moldCode().isBlank()) {
                skipped++;
                continue;
            }
            rowsByMold.computeIfAbsent(row.moldCode(), k -> new ArrayList<>()).add(row);
        }

        int createdMolds = 0;
        int mergedMolds = 0;
        int createdGroups = 0;
        int createdVariantLinks = 0;
        int createdAccessories = 0;
        List<String> issues = new ArrayList<>();

        // 预取已存在模具（@SQLRestriction 已过滤软删除记录）
        Map<String, PackagingMold> existingMolds = new HashMap<>();
        if (!rowsByMold.isEmpty()) {
            for (PackagingMold m : moldRepo.findByMoldCodeIn(rowsByMold.keySet())) {
                existingMolds.put(m.getMoldCode(), m);
            }
            // 同名软删除模具优先恢复并合并，保留原 ID、历史、规格关联和配件。
            List<PackagingMold> deletedCollisions = moldRepo.findDeletedByMoldCodeIn(rowsByMold.keySet());
            for (PackagingMold deleted : deletedCollisions) {
                Long deletedMoldId = deleted.getId();
                moldRepo.restoreById(deletedMoldId);
                linkRepo.restoreByMoldId(deletedMoldId);
                accessoryRepo.restoreByMoldId(deletedMoldId);
                deleted.setDeletedAt(null);
                existingMolds.put(deleted.getMoldCode(), deleted);
                issues.add("已恢复同名历史模具 " + deleted.getMoldCode() + " #" + deletedMoldId
                        + "，并保留原规格关联与配件");
                recordBatchItem(batchId, "MOLD", deletedMoldId, "RESTORE", deleted.getMoldCode());
            }
        }

        // 预取 merge 模具的现有配件
        List<Long> mergeMoldIds = existingMolds.values().stream()
                .map(PackagingMold::getId).toList();
        Map<Long, Set<String>> existingAccTypesByMold = new HashMap<>();
        if (!mergeMoldIds.isEmpty()) {
            for (MoldAccessory a : accessoryRepo.findByMoldIdIn(mergeMoldIds)) {
                existingAccTypesByMold
                        .computeIfAbsent(a.getMold().getId(), k -> new HashSet<>())
                        .add(a.getAccessoryType());
            }
        }

        // 按 productName + modelId 缓存 group
        Map<String, PackagingMoldGroup> groupCache = new HashMap<>();
        Map<Long, List<PackagingMoldGroup>> groupsByModel = new HashMap<>();

        for (Map.Entry<String, List<MoldImportRow>> entry : rowsByMold.entrySet()) {
            String moldCode = entry.getKey();
            List<MoldImportRow> moldRows = entry.getValue();
            MoldImportRow firstRow = moldRows.get(0);

            // 同一模号的所有展开行必须归属同一系列和同一规格族，不能再静默取第一行。
            Set<Long> effectiveModelIds = new HashSet<>();
            Set<String> groupSignatures = new HashSet<>();
            Set<String> productNames = new HashSet<>();
            for (MoldImportRow row : moldRows) {
                int rowGlobalIdx = preview.rows().indexOf(row);
                Long effectiveModelId = modelOverridesMap.get(rowGlobalIdx);
                if (effectiveModelId == null) {
                    effectiveModelId = globalModel != null ? globalModel.getId() : row.matchedModelId();
                }
                if (effectiveModelId != null) effectiveModelIds.add(effectiveModelId);
                groupSignatures.add(groupSignature(row));
                productNames.add(normalizeGroupName(row.productName()).toLowerCase());
            }
            if (effectiveModelIds.size() != 1 || groupSignatures.size() != 1 || productNames.size() != 1) {
                issues.add("模号 " + moldCode + " 的展开行存在系列、产品名称或口径冲突，已整组跳过");
                skipped += moldRows.size();
                continue;
            }

            // 确定该行所属的 PackagingModel
            PackagingModel model = globalModel;
            // 优先检查用户是否手动覆盖了系列
            Long effectiveModelId = effectiveModelIds.iterator().next();
            if (globalModel == null || !globalModel.getId().equals(effectiveModelId)) {
                model = modelCache.get(effectiveModelId);
                if (model == null) {
                    model = modelRepo.findById(effectiveModelId)
                            .orElseThrow(() -> new IllegalArgumentException("包材系列不存在: " + effectiveModelId));
                    modelCache.put(effectiveModelId, model);
                }
            }

            // 查找或创建规格族
            String groupName = normalizeGroupName(firstRow.productName());
            if (groupName == null || groupName.isBlank()) {
                groupName = "未命名规格族";
            }
            String groupCacheKey = model.getId() + ":" + groupSignature(firstRow);
            PackagingMoldGroup group = groupCache.get(groupCacheKey);
            if (group == null) {
                List<PackagingMoldGroup> modelGroups = groupsByModel.computeIfAbsent(model.getId(),
                        id -> groupRepo.findByModelIdOrderByNameAsc(id));
                group = groupRepo.findByModelIdAndNormalizedKey(model.getId(), groupSignature(firstRow)).orElse(null);
                if (group == null) group = findGroupBySignature(modelGroups, firstRow);
                if (group == null) {
                    group = groupRepo.findByModelIdAndName(model.getId(), groupName).orElse(null);
                }
                if (group == null) {
                    group = new PackagingMoldGroup();
                    group.setModel(model);
                    group.setName(groupName);
                    group.setNormalizedKey(groupSignature(firstRow));
                    // 从口径推断形状和标准尺寸
                    applyCaliberToGroup(group, firstRow.caliber());
                    group = groupRepo.save(group);
                    modelGroups.add(group);
                    createdGroups++;
                    recordBatchItem(batchId, "MOLD_GROUP", group.getId(), "CREATE", group.getName());
                }
                if (group.getNormalizedKey() == null || group.getNormalizedKey().isBlank()) {
                    group.setNormalizedKey(groupSignature(firstRow));
                    group = groupRepo.save(group);
                }
                groupCache.put(groupCacheKey, group);
            }

            // 查找或创建模具
            PackagingMold mold = existingMolds.get(moldCode);
            boolean isMerge = mold != null;
            if (mold == null) {
                mold = new PackagingMold();
                mold.setMoldCode(moldCode);
                mold.setModel(model);
                mold.setMoldRole("main");
                mold.setStatus("in_use");
                createdMolds++;
            } else {
                mergedMolds++;
            }

            // 填充字段
            mold.setGroup(group);
            final PackagingMold moldRef = mold;
            final PackagingModel modelRef = model;
            if (isMerge) {
                // merge 场景：补填空字段时记录变更历史（change_source=import）
                moldService.mergeWithHistory(moldRef, () ->
                        applyRowToMold(moldRef, modelRef, firstRow, sourceFile, sourceSheet, batchId, true), "import");
            } else {
                applyRowToMold(mold, model, firstRow, sourceFile, sourceSheet, batchId, false);
                moldRepo.save(mold);
            }

            // 创建配件
            Set<String> existingAccTypes = isMerge
                    ? existingAccTypesByMold.getOrDefault(mold.getId(), Set.of())
                    : Set.of();

            // 处理配件列表（解析成功的）
            if (firstRow.accessoryList() != null && !firstRow.accessoryList().isEmpty()) {
                for (String acc : firstRow.accessoryList()) {
                    String accType = mapAccessoryType(acc);
                    if (existingAccTypes.contains(accType)) continue;
                    MoldAccessory accessory = new MoldAccessory();
                    accessory.setMold(mold);
                    accessory.setAccessoryType(accType);
                    accessory.setRemark(acc);
                    accessory.setSourceFile(sourceFile);
                    accessory.setSourceSheet(sourceSheet);
                    accessory.setSourceRowNumber(firstRow.sourceRowNumber());
                    accessory.setImportBatchId(batchId);
                    accessoryRepo.save(accessory);
                    createdAccessories++;
                    recordBatchItem(batchId, "ACCESSORY", accessory.getId(), "CREATE", moldCode + " / " + accType);
                }
            }

            // 如果解析列表为空但原始文本有内容，创建一个 "其他" 类型的配件记录
            if ((firstRow.accessoryList() == null || firstRow.accessoryList().isEmpty())
                    && firstRow.accessories() != null && !firstRow.accessories().isBlank()
                    && !isNoAccessoriesText(firstRow.accessories())) {
                if (mold.getRawAccessoriesText() == null) {
                    mold.setRawAccessoriesText(firstRow.accessories());
                }
                // 创建一条配件记录，标记为 "other" 类型，保留原始文本信息
                if (!existingAccTypes.contains("other")) {
                    MoldAccessory accessory = new MoldAccessory();
                    accessory.setMold(mold);
                    accessory.setAccessoryType("other");
                    accessory.setRemark("原始配件: " + firstRow.accessories());
                    accessory.setSourceFile(sourceFile);
                    accessory.setSourceSheet(sourceSheet);
                    accessory.setSourceRowNumber(firstRow.sourceRowNumber());
                    accessory.setImportBatchId(batchId);
                    accessoryRepo.save(accessory);
                    createdAccessories++;
                    recordBatchItem(batchId, "ACCESSORY", accessory.getId(), "CREATE", moldCode + " / other");
                }
            } else if (firstRow.accessories() != null && !firstRow.accessories().isBlank()) {
                // 有解析列表也保存原始文本
                if (mold.getRawAccessoriesText() == null) {
                    mold.setRawAccessoriesText(firstRow.accessories());
                }
            }

            // 保存模具的 rawAccessoriesText（如果需要）
            if (mold.getRawAccessoriesText() != null) {
                moldRepo.save(mold);
            }
            recordBatchItem(batchId, "MOLD", mold.getId(), isMerge ? "MERGE" : "CREATE", moldCode);

            // 创建 MoldVariantLink
            for (int rowIdx = 0; rowIdx < moldRows.size(); rowIdx++) {
                MoldImportRow row = moldRows.get(rowIdx);
                // 找到该行在 preview.rows 中的全局索引
                int globalIdx = preview.rows().indexOf(row);
                Long variantId = overrides.containsKey(globalIdx)
                        ? overrides.get(globalIdx)
                        : row.selectedVariantId();

                if (variantId != null) {
                    // 检查是否已存在关联
                    List<MoldVariantLink> existingLinks = linkRepo.findByMoldAndVariant(mold.getId(), variantId);
                    if (existingLinks.isEmpty()) {
                        PackagingVariant variant = variantRepo.findById(variantId).orElse(null);
                        // 规格必须属于当前系列（禁止跨系列关联）
                        if (variant != null && variant.getModel().getId().equals(model.getId())) {
                            MoldVariantLink link = new MoldVariantLink();
                            link.setMold(mold);
                            link.setVariant(variant);
                            link.setRelationType("main");
                            link.setIsDefault(false);
                            link.setSourceFile(sourceFile);
                            link.setSourceSheet(sourceSheet);
                            link.setSourceRowNumber(row.sourceRowNumber());
                            link.setImportBatchId(batchId);
                            linkRepo.save(link);
                            createdVariantLinks++;
                            recordBatchItem(batchId, "VARIANT_LINK", link.getId(), "LINK", moldCode + " → " + variant.getVariantName());
                        } else if (variant != null) {
                            issues.add("模号 " + moldCode + " 行 " + row.sourceRowNumber()
                                    + " 规格不属于当前系列，已跳过规格关联");
                        }
                    }
                } else if ("multi_match".equals(row.variantMatch())) {
                    issues.add("模号 " + moldCode + " 行 " + row.sourceRowNumber()
                            + " 存在多个候选规格，未人工确认，已跳过规格关联");
                } else if ("no_match".equals(row.variantMatch())) {
                    issues.add("模号 " + moldCode + " 行 " + row.sourceRowNumber()
                            + " 无匹配规格（高度 " + row.heightMm() + "mm），需手动关联");
                }
            }

            if (isMerge) {
                auditLogService.log("PackagingMold", mold.getId(), moldCode,
                        "PKG_MOLD_IMPORT_MERGE", "导入合并模具 " + moldCode);
            } else {
                auditLogService.log("PackagingMold", mold.getId(), moldCode,
                        "PKG_MOLD_IMPORT_CREATE", "导入创建模具 " + moldCode + " → 规格族 " + groupName);
            }
        }

        // 批级汇总日志（单模级日志已在上方打出，此处做批次级汇总便于按批次追溯）
        if (createdMolds + mergedMolds > 0) {
            String msg = String.format("批次 #%d：%s（%s），新建 %d 模 / 合并 %d 模 / 新建规格族 %d / 新建关联 %d / 跳过 %d",
                    batchId, sourceFile, (sourceSheet == null ? "-" : sourceSheet),
                    createdMolds, mergedMolds, createdGroups, createdVariantLinks, skipped);
            auditLogService.log("PackagingMoldImport", null,
                    (sourceFile == null ? "mold-import" : sourceFile),
                    "PKG_MOLD_IMPORT_BATCH", msg);
        }

        importBatch.setStatus("completed");
        importBatch.setMoldCount(createdMolds + mergedMolds);
        importBatch.setVariantLinkCount(createdVariantLinks);
        importBatch.setAccessoryCount(createdAccessories);
        importBatchRepo.save(importBatch);

        return new MoldImportConfirmResult(batchId, createdMolds, mergedMolds,
                createdGroups, createdVariantLinks, createdAccessories, skipped, issues);
    }

    private void recordBatchItem(Long batchId, String entityType, Long entityId,
                                 String operationType, String entityLabel) {
        MoldImportBatchItem item = new MoldImportBatchItem();
        item.setBatchId(batchId);
        item.setEntityType(entityType);
        item.setEntityId(entityId);
        item.setOperationType(operationType);
        item.setEntityLabel(entityLabel);
        if ("MERGE".equals(operationType) || "RESTORE".equals(operationType)) {
            item.setBeforeSnapshot("{\"state\":\"existing\"}");
        }
        item.setAfterSnapshot("{\"entityId\":" + (entityId == null ? "null" : entityId)
                + ",\"label\":\"" + jsonEscape(entityLabel) + "\"}");
        importBatchItemRepo.save(item);
    }

    private String jsonEscape(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\r", "\\r").replace("\n", "\\n");
    }


    // ====== 尺寸匹配 ======

    private List<VariantMatchCandidate> matchVariants(
            List<PackagingVariant> existingVariants,
            Map<Long, PackagingModel> variantModelMap,
            String rowLength, String rowWidth, String rowDiameter, String rowHeight,
            SeriesCategory targetCategory) {

        Double hLen = parseDim(rowLength);
        Double hWidth = parseDim(rowWidth);
        Double hDia = parseDim(rowDiameter);
        Double hHeight = parseDim(rowHeight);

        if (hLen == null && hWidth == null && hDia == null && hHeight == null) {
            return List.of();
        }

        List<VariantMatchCandidate> primaryCandidates = new ArrayList<>();   // 同系列类别
        List<VariantMatchCandidate> fallbackCandidates = new ArrayList<>();   // 跨系列回退

        boolean excelHasSquare = hLen != null || hWidth != null;
        boolean excelHasRound = hDia != null;
        // 业务规则：口径格式唯一决定形状
        //   单个数字（含 C 前缀）→ 圆杯（直径）
        //   长×宽（含分隔符 ×*xX＊）→ 方盒
        // 因此：纯圆杯口径禁止匹配方盒规格，纯方盒口径禁止匹配圆杯规格
        boolean pureRound = excelHasRound && !excelHasSquare;
        boolean pureSquare = excelHasSquare && !excelHasRound;

        for (PackagingVariant v : existingVariants) {
            Double vLen = parseDim(v.getLengthMm());
            Double vWidth = parseDim(v.getWidthMm());
            Double vDia = parseDim(v.getTopDiameterMm());
            Double vHeight = parseDim(v.getHeightMm());

            double score = 0;
            List<String> diffs = new ArrayList<>();
            int matchedDims = 0;         // 命中严格容差的维度数
            int looseDims = 0;           // 仅命中宽松容差的维度数
            boolean shapeMatched = false;

            // ====== 方盒匹配（长/宽）======
            if (excelHasSquare) {
                boolean variantHasSquare = vLen != null || vWidth != null;

                if (variantHasSquare) {
                    // 长宽均完整时按短边/长边归一化，允许模具表与规格表交换长宽方向。
                    Double compareHLen = hLen;
                    Double compareHWidth = hWidth;
                    Double compareVLen = vLen;
                    Double compareVWidth = vWidth;
                    if (hLen != null && hWidth != null && vLen != null && vWidth != null) {
                        compareHLen = Math.max(hLen, hWidth);
                        compareHWidth = Math.min(hLen, hWidth);
                        compareVLen = Math.max(vLen, vWidth);
                        compareVWidth = Math.min(vLen, vWidth);
                    }
                    if (compareHLen != null && compareVLen != null) {
                        double delta = Math.abs(compareVLen - compareHLen);
                        ScoreResult r = scoreDim(delta, DIMENSION_TOLERANCE, DIMENSION_TOLERANCE_LOOSE, 40, "length_mm");
                        score += r.score;
                        if (r.tag != null) diffs.add(r.tag);
                        if (r.strict) matchedDims++;
                        else if (r.loose) looseDims++;
                    }
                    if (compareHWidth != null && compareVWidth != null) {
                        double delta = Math.abs(compareVWidth - compareHWidth);
                        ScoreResult r = scoreDim(delta, DIMENSION_TOLERANCE, DIMENSION_TOLERANCE_LOOSE, 40, "width_mm");
                        score += r.score;
                        if (r.tag != null) diffs.add(r.tag);
                        if (r.strict) matchedDims++;
                        else if (r.loose) looseDims++;
                    }
                    // 判定形状匹配：严格任一维度命中，或宽松至少 1 个维度命中且另一维度无数据/不冲突
                    boolean bothPresent = (hLen != null && hWidth != null);
                    if (bothPresent) {
                        if (matchedDims >= 2 || (matchedDims >= 1 && looseDims >= 1) || looseDims >= 2) {
                            shapeMatched = true;
                        }
                    } else {
                        if (matchedDims >= 1 || looseDims >= 1) shapeMatched = true;
                    }
                }
                // 纯方盒口径：禁止交叉匹配到圆杯规格
            }

            // ====== 圆杯匹配（直径）======
            if (excelHasRound && !shapeMatched) {
                boolean variantHasRound = vDia != null;

                if (variantHasRound) {
                    double delta = Math.abs(vDia - hDia);
                    ScoreResult r = scoreDim(delta, DIMENSION_TOLERANCE, DIMENSION_TOLERANCE_LOOSE, 50, "top_diameter_mm");
                    score += r.score;
                    if (r.tag != null && !r.strict) diffs.add(r.tag);
                    if (r.strict || r.loose) {
                        shapeMatched = true;
                        if (r.strict) matchedDims++; else looseDims++;
                    }
                }
                // 纯圆杯口径：禁止交叉匹配到方盒规格
            }

            // ====== 高度匹配（宽松容差也能加分）======
            if (hHeight != null && vHeight != null) {
                double delta = Math.abs(vHeight - hHeight);
                ScoreResult r = scoreDim(delta, HEIGHT_TOLERANCE, HEIGHT_TOLERANCE_LOOSE, 20, "height_mm");
                // Excel 已明确高度时，高度是生产规格的必要条件。超出宽松容差的规格
                // 不得进入候选，避免仅凭长宽一致把不同高度的盒子自动关联到同一模具。
                if (!r.strict && !r.loose) {
                    continue;
                }
                score += r.score;
                if (!r.strict && r.loose) {
                    diffs.add("height_mm~loose");
                }
                if (r.strict) matchedDims++; else if (r.loose) looseDims++;
            } else if (hHeight != null) {
                // Excel 有高度、现有规格无高度时无法证明是同一生产规格，不纳入候选。
                continue;
            }

            // ====== 形状过滤：纯圆杯口径只匹配圆杯规格，纯方盒口径只匹配方盒规格 ======
            if (pureRound && !isRoundVariant(v)) continue;
            if (pureSquare && !isSquareVariant(v)) continue;

            // ====== 最终判定：形状匹配 且 至少 1 个维度命中（严格或宽松）且得分 > 0 ======
            boolean anyDimHit = (matchedDims + looseDims) >= 1;
            if (shapeMatched && anyDimHit && score > 0) {
                PackagingModel vm = variantModelMap.get(v.getId());
                SeriesCategory variantCategory = vm != null ? classifyModelByName(vm.getName()) : SeriesCategory.UNKNOWN;

                // 系列类别匹配判定
                boolean categoryMatched = (targetCategory == SeriesCategory.UNKNOWN)
                        || (variantCategory == SeriesCategory.UNKNOWN)
                        || (targetCategory == variantCategory);

                if (!categoryMatched) {
                    // 跨系列回退：得分打折，标记 cross_series
                    score *= CROSS_SERIES_PENALTY;
                    diffs.add("cross_series");
                }

                VariantMatchCandidate candidate = new VariantMatchCandidate(
                        v.getId(),
                        v.getVariantName(),
                        Math.round(score * 100.0) / 100.0,
                        diffs.toArray(new String[0]),
                        false,
                        vm != null ? vm.getId() : null,
                        vm != null ? vm.getName() : null,
                        v.getLengthMm(),
                        v.getWidthMm(),
                        v.getTopDiameterMm(),
                        v.getHeightMm()
                );

                if (categoryMatched) {
                    primaryCandidates.add(candidate);
                } else {
                    fallbackCandidates.add(candidate);
                }
            }
        }

        // 优先返回同系列类别的候选；同系列为空时回退到跨系列候选
        List<VariantMatchCandidate> result;
        if (!primaryCandidates.isEmpty()) {
            result = primaryCandidates;
        } else {
            result = fallbackCandidates;
        }

        result.sort((a, b) -> Double.compare(b.score(), a.score()));
        // 候选过多时截断，避免前端列表过长
        return result.size() > 10 ? result.subList(0, 10) : result;
    }

    private boolean isRoundVariant(PackagingVariant v) {
        return parseDim(v.getTopDiameterMm()) != null;
    }

    private boolean isSquareVariant(PackagingVariant v) {
        Double l = parseDim(v.getLengthMm());
        Double w = parseDim(v.getWidthMm());
        return l != null || w != null;
    }

    /**
     * 识别 Excel 行所属的业务系列类别。
     * <p>核心规则：
     * <ol>
     *   <li>关键词匹配（按形状过滤）：产品名关键词 + 口径形状一致性</li>
     *   <li>形状兜底：关键词未命中时，口径形状直接决定系列
     *     <ul>
     *       <li>长×宽（方盒）→ 打包盒系列</li>
     *       <li>C 前缀或单个数字（圆碗）→ 圆碗系列</li>
     *     </ul>
     *   </li>
     * </ol>
     */
    private SeriesCategory identifySeriesCategory(String productName,
                                                   String lengthMm, String widthMm, String diameterMm) {
        boolean caliberRound = (diameterMm != null && parseDim(diameterMm) != null)
                && (lengthMm == null || parseDim(lengthMm) == null)
                && (widthMm == null || parseDim(widthMm) == null);
        boolean caliberSquare = (lengthMm != null && parseDim(lengthMm) != null)
                || (widthMm != null && parseDim(widthMm) != null);

        // 1. 关键词匹配（按形状过滤）
        if (productName != null && !productName.isBlank()) {
            for (Map.Entry<SeriesCategory, List<String>> entry : PRODUCT_NAME_KEYWORDS.entrySet()) {
                for (String rawKeyword : entry.getValue()) {
                    // 解析关键词形状前缀
                    boolean keywordRound = rawKeyword.startsWith("[ROUND]");
                    boolean keywordSquare = rawKeyword.startsWith("[SQUARE]");
                    String keyword = rawKeyword.substring(rawKeyword.indexOf(']') + 1);

                    // 形状一致性检查：圆碗口径只匹配 [ROUND] 关键词，方盒口径只匹配 [SQUARE] 关键词
                    if (keywordRound && !caliberRound) continue;
                    if (keywordSquare && !caliberSquare) continue;

                    if (productName.contains(keyword)) {
                        return entry.getKey();
                    }
                }
            }
        }

        // 2. 形状兜底：关键词未命中时，口径形状直接决定系列
        if (caliberRound) {
            // 圆碗口径（C 前缀或单个数字）→ 圆碗系列
            return SeriesCategory.ROUND_BOWL;
        }
        if (caliberSquare) {
            // 方盒口径（长×宽）→ 打包盒系列
            return SeriesCategory.PACKING_BOX;
        }
        return SeriesCategory.UNKNOWN;
    }

    /** 按 PackagingModel.name 关键词反向识别系列类别 */
    private SeriesCategory classifyModelByName(String modelName) {
        if (modelName == null || modelName.isBlank()) return SeriesCategory.UNKNOWN;
        for (Map.Entry<SeriesCategory, List<String>> entry : MODEL_NAME_KEYWORDS.entrySet()) {
            for (String kw : entry.getValue()) {
                if (modelName.contains(kw)) {
                    return entry.getKey();
                }
            }
        }
        return SeriesCategory.UNKNOWN;
    }

    /**
     * 根据系列类别找到对应的 PackagingModel。
     * <p>优先匹配名称含关键词的系列；多个候选时返回第一个。
     */
    private PackagingModel findModelByCategory(SeriesCategory category, List<PackagingModel> models) {
        if (category == null || category == SeriesCategory.UNKNOWN || models == null || models.isEmpty()) {
            return null;
        }
        // 先排除不生产的系列
        List<PackagingModel> filtered = models.stream()
                .filter(m -> {
                    if (EXCLUDED_PACKAGING_KINDS.contains(m.getPackagingKind())) return false;
                    // bowl 类型：只排除铝箔材质的 bowl（铝箔碗），PP 圆碗保留
                    if ("bowl".equals(m.getPackagingKind())) {
                        String mat = m.getMaterial();
                        if (mat != null) {
                            for (String kw : BOWL_EXCLUDED_MATERIALS) {
                                if (mat.contains(kw)) return false;
                            }
                        }
                    }
                    String material = m.getMaterial();
                    if (material != null) {
                        for (String kw : EXCLUDED_MATERIAL_KEYWORDS) {
                            if (material.contains(kw)) return false;
                        }
                    }
                    String name = m.getName();
                    if (name != null) {
                        for (String kw : EXCLUDED_MODEL_NAME_KEYWORDS) {
                            if (name.contains(kw)) return false;
                        }
                    }
                    return true;
                })
                .toList();

        // 按类别匹配
        for (PackagingModel m : filtered) {
            if (classifyModelByName(m.getName()) == category) {
                return m;
            }
        }
        // 类别匹配不到时，按形状兜底找第一个可用系列（圆碗→bowl类型，方盒→非bowl的container）
        return filtered.isEmpty() ? null : filtered.get(0);
    }

    /** 单维度三级评分：严格命中 / 宽松命中 / 未命中 */
    private static class ScoreResult {
        double score;
        String tag;
        boolean strict;
        boolean loose;
        ScoreResult(double score, String tag, boolean strict, boolean loose) {
            this.score = score; this.tag = tag; this.strict = strict; this.loose = loose;
        }
    }

    private ScoreResult scoreDim(double delta, double strictTol, double looseTol,
                                  double maxScore, String diffTag) {
        if (delta <= 0.01) {
            return new ScoreResult(maxScore, null, true, false);
        }
        if (delta <= strictTol) {
            double s = maxScore * (1 - delta / strictTol);
            return new ScoreResult(s, diffTag, true, false);
        }
        if (delta <= looseTol) {
            // 宽松命中：最多给一半分数
            double ratio = (delta - strictTol) / (looseTol - strictTol);
            double s = maxScore * 0.5 * (1 - ratio);
            return new ScoreResult(s, diffTag + "~loose", false, true);
        }
        // 超出宽松容差：记 diff，但不给分也不扣分（由调用方决定是否整体排除）
        return new ScoreResult(0, diffTag, false, false);
    }

    private Double parseDim(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return Double.parseDouble(s.trim().replaceAll("[^0-9.]", ""));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ====== 解析辅助 ======

    private static class ParsedMoldCode {
        String baseCode;
        String typeCode;
        Integer cavityCount;
        boolean numericOnly;

        ParsedMoldCode(String baseCode, String typeCode, Integer cavityCount, boolean numericOnly) {
            this.baseCode = baseCode;
            this.typeCode = typeCode;
            this.cavityCount = cavityCount;
            this.numericOnly = numericOnly;
        }
    }

    private ParsedMoldCode parseMoldCode(String code) {
        if (code == null || code.isBlank()) {
            return new ParsedMoldCode(null, null, null, false);
        }
        Matcher m = MOLD_CODE_PATTERN.matcher(code.trim());
        if (!m.matches()) {
            return new ParsedMoldCode(null, null, null, false);
        }
        String prefix = m.group(1);
        String number = m.group(2);
        String cavity = m.group(3);
        String typeCode = m.group(4);

        boolean numericOnly = prefix == null || prefix.isEmpty();
        String baseCode = (prefix != null ? prefix : "") + number;

        Integer cavityCount = cavity != null ? Integer.parseInt(cavity) : null;
        return new ParsedMoldCode(baseCode, typeCode, cavityCount, numericOnly);
    }

    private List<String> splitByPlus(String value) {
        if (value == null || value.isBlank()) return List.of();
        String trimmed = value.trim();
        // 如果文本本身表示 "无配件"，直接返回空列表
        if (isNoAccessoriesText(trimmed)) return List.of();
        // 先按 "+"、"＋"、"、"、"，"、";"、"；"、" / "、"、" 等分隔符分割
        String[] parts = trimmed.split("[+＋、，;；]|\\s*/\\s*");
        List<String> result = Arrays.stream(parts)
                .map(String::trim)
                .filter(s -> !s.isEmpty() && !s.equals("-") && !s.equals("无") && !s.equals("空"))
                .toList();
        // 如果分割后只有一项，检查是否包含 "和"、"及"、"与" 等连接词
        if (result.size() == 1) {
            String single = result.get(0);
            // 用 "和"、"及"、"与" 分割，但要注意 "及" 可能是单词的一部分
            String[] subParts = single.split("\\s*(?:和|及|与)\\s*");
            if (subParts.length > 1) {
                List<String> subResult = Arrays.stream(subParts)
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .toList();
                if (!subResult.isEmpty()) {
                    return subResult;
                }
            }
        }
        return result;
    }

    /**
     * 高度列专用拆分。模具表中常用 35*50、35×50、35+50 表示同一模号的多个高度；
     * 该规则不能复用到口径列，否则 180*120 会被错误拆成两个高度。
     */
    private List<String> splitHeights(String value) {
        if (value == null || value.isBlank()) return List.of();
        return Arrays.stream(value.trim().split("\\s*(?:[+＋*＊xX×、，;；/]|\\s+)\\s*"))
                .map(String::trim)
                .filter(part -> !part.isEmpty() && !part.equals("-") && !part.equals("无"))
                .toList();
    }

    /**
     * 判断配件文本是否表示 "无配件" 的含义。
     */
    private boolean isNoAccessoriesText(String text) {
        if (text == null || text.isBlank()) return true;
        String trimmed = text.trim();
        return trimmed.equals("无") || trimmed.equals("空") || trimmed.equals("-")
                || trimmed.equals("/") || trimmed.equals("\\") || trimmed.equals("N/A")
                || trimmed.equalsIgnoreCase("null") || trimmed.equalsIgnoreCase("none");
    }

    private String cleanCapacity(String value) {
        if (value == null) return "";
        return value.trim().replaceAll("(?i)\\s*ml\\s*", "").trim();
    }

    private String classifyEngraving(String text) {
        if (text == null || text.isBlank()) return "blank";
        String t = text.trim();
        if (t.equals("空白") || t.equals("无") || t.equals("-")) return "blank";
        if (t.contains("通用") || t.contains("标准")) return "generic";
        if (t.contains("循环") || t.contains("食品级") || t.contains("可回收")) return "functional";
        if (t.contains("www") || t.contains("http") || t.contains(".com") || t.contains("专版")) {
            return "brand_custom";
        }
        return "generic";
    }

    private String parseLength(String caliber) {
        if (caliber == null) return null;
        // 方盒才解析长宽：必须显式含分隔符（× * x X ＊）
        // 单个数字（如 119、C119）一律视为圆杯直径，不作为方盒边长
        String[] parts = caliber.split("[*×xX＊]");
        if (parts.length >= 2) {
            String v = parts[0].trim().replaceAll("[^0-9.]", "");
            return v.isEmpty() ? null : v;
        }
        return null;
    }

    private String parseWidth(String caliber) {
        if (caliber == null) return null;
        // 方盒才解析长宽：必须显式含分隔符（× * x X ＊）
        String[] parts = caliber.split("[*×xX＊]");
        if (parts.length >= 2) {
            String v = parts[1].trim().replaceAll("[^0-9.]", "");
            return v.isEmpty() ? null : v;
        }
        return null;
    }

    private String parseDiameter(String caliber) {
        if (caliber == null) return null;
        String trimmed = caliber.trim();
        // C 前缀圆杯：C119 → 119
        if (trimmed.toUpperCase().startsWith("C")) {
            String numOnly = trimmed.substring(1).replaceAll("[^0-9.]", "");
            return numOnly.isEmpty() ? null : numOnly;
        }
        // 不含分隔符的纯数字（如 119）→ 圆杯直径
        if (!trimmed.matches(".*[×*xX＊].*")) {
            String numOnly = trimmed.replaceAll("[^0-9.]", "");
            if (!numOnly.isEmpty()) {
                return numOnly;
            }
        }
        // 含分隔符（方盒长×宽）不作为直径
        return null;
    }

    private boolean detectMisalignment(String compartment, String capacity) {
        if (compartment == null || compartment.isBlank()) return false;
        String c = compartment.trim();
        if (c.matches("\\d+") && (capacity == null || capacity.isBlank())) {
            return true;
        }
        return false;
    }

    private String mapAccessoryType(String text) {
        String t = text.trim();
        if (t.contains("杯胆")) return "cup_liner";
        if (t.contains("杯底") || t.contains("底")) return "cup_bottom";
        if (t.contains("拉伸头")) return "drawing_head";
        if (t.contains("压料环")) return "press_ring";
        return "other";
    }

    private void applyCaliberToGroup(PackagingMoldGroup group, String caliber) {
        if (caliber == null || caliber.isBlank()) return;
        String length = parseLength(caliber);
        String width = parseWidth(caliber);
        String diameter = parseDiameter(caliber);
        if (length != null && width != null) {
            group.setShapeType("square");
            group.setLengthMm(length);
            group.setWidthMm(width);
        } else if (diameter != null) {
            group.setShapeType("round");
            group.setDiameterMm(diameter);
        }
    }

    /** 规格族身份优先由标准形状和口径决定，名称仅在没有可解析尺寸时兜底。 */
    private String groupSignature(MoldImportRow row) {
        Double length = parseDim(row.lengthMm());
        Double width = parseDim(row.widthMm());
        Double diameter = parseDim(row.topDiameterMm());
        if (length != null && width != null) {
            double min = Math.min(length, width);
            double max = Math.max(length, width);
            return "RECT:" + canonicalDim(min) + "x" + canonicalDim(max);
        }
        if (diameter != null) return "ROUND:" + canonicalDim(diameter);
        return "NAME:" + normalizeGroupName(row.productName()).toLowerCase();
    }

    private PackagingMoldGroup findGroupBySignature(List<PackagingMoldGroup> groups, MoldImportRow row) {
        String target = groupSignature(row);
        for (PackagingMoldGroup group : groups) {
            String signature;
            Double length = parseDim(group.getLengthMm());
            Double width = parseDim(group.getWidthMm());
            Double diameter = parseDim(group.getDiameterMm());
            if (length != null && width != null) {
                signature = "RECT:" + canonicalDim(Math.min(length, width)) + "x"
                        + canonicalDim(Math.max(length, width));
            } else if (diameter != null) {
                signature = "ROUND:" + canonicalDim(diameter);
            } else {
                signature = "NAME:" + normalizeGroupName(group.getName()).toLowerCase();
            }
            if (target.equals(signature)) return group;
        }
        return null;
    }

    private String normalizeGroupName(String name) {
        if (name == null) return "";
        return name.trim()
                .replaceAll("\\s+", "")
                .replace('x', '×')
                .replace('X', '×')
                .replace('*', '×')
                .replace('＊', '×');
    }

    private String canonicalDim(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }

    // ====== 表头映射 ======

    private String mapHeaderKey(String header) {
        if (header == null) return null;
        String h = header.trim();
        // 口径相关优先（避免 "产品口径" 被 "产品" 先匹配）
        if (h.contains("口径") || h.contains("口部") || h.contains("尺寸") || h.contains("长宽")
                || h.contains("规格") || h.contains("大小") || h.contains("直径")) return "caliber";
        // 产品名称类
        if (h.contains("产品") || h.contains("名称")) return "productName";
        // 模号类
        if (h.contains("模号") || h.contains("模具编号") || h.equals("模具")) return "moldCode";
        // 高度
        if (h.contains("高度") || h.contains("高")) return "height";
        // 容量
        if (h.contains("容量")) return "capacity";
        // 格数
        if (h.contains("格数") || h.contains("单格") || h.contains("双格")) return "compartment";
        // 配件 - 扩展匹配：配件/附件/组成/包含/零部件
        if (h.contains("配件") || h.contains("附件") || h.equals("组成") || h.equals("包含")
                || h.contains("零部件") || h.contains("配套")) return "accessories";
        // 杯底刻字
        if (h.contains("刻字") || h.contains("杯底")) return "engraving";
        // 表面/结构
        if (h.contains("表面") || h.contains("结构") || h.contains("筋") || h.contains("瓦楞")) return "surface";
        // 外形/类型
        if (h.contains("外形") || h.contains("类型") || h.contains("类别")) return "category";
        return null;
    }

    // ====== 通用辅助 ======

    private String getCell(Row row, Map<String, Integer> colMap, String key) {
        Integer idx = colMap.get(key);
        if (idx == null) return "";
        Cell cell = row.getCell(idx);
        return cell == null ? "" : formatter.formatCellValue(cell).trim();
    }

    private boolean isEmptyRow(Row row, Map<String, Integer> colMap) {
        for (String key : List.of("productName", "moldCode", "height", "capacity")) {
            if (!getCell(row, colMap, key).isBlank()) return false;
        }
        return true;
    }

    private String buildRawRowText(Row row) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < row.getLastCellNum(); i++) {
            if (i > 0) sb.append(" | ");
            Cell cell = row.getCell(i);
            sb.append(cell == null ? "" : formatter.formatCellValue(cell).trim());
        }
        return sb.length() > 500 ? sb.substring(0, 497) + "..." : sb.toString();
    }

    /**
     * 从产品名中提取口径尺寸。
     * <p>支持格式：
     * <ul>
     *   <li>"1414方盒" → "141×141"</li>
     *   <li>"2015长方" → "201×15" 或 "20×15"（优先取前两位+后两位）</li>
     *   <li>"C90圆杯" → "C90"</li>
     *   <li>"C118圆杯" → "C118"</li>
     * </ul>
     */
    private String extractCaliberFromName(String name) {
        if (name == null || name.isBlank()) return "";
        String n = name.trim();

        // C90 / C118 圆杯
        Matcher cm = Pattern.compile("^(C\\d+)").matcher(n);
        if (cm.find()) {
            return cm.group(1);
        }

        // 纯数字圆杯：119 / 127 等
        Matcher rm = Pattern.compile("^(\\d{2,4})").matcher(n);
        if (rm.find() && !n.substring(rm.end()).matches(".*[方长长正方矩圆碗杯].*")) {
            return rm.group(1);
        }

        // 方盒/长方：前导数字 + 形状词
        Matcher sm = Pattern.compile("^(\\d{3,5})(方盒|长方|长形|正方形|矩形)").matcher(n);
        if (sm.find()) {
            String num = sm.group(1);
            if (num.length() == 4) {
                // 4 位数字 = 2+2，单位 cm，需 ×10 转 mm
                // 1914 → 19cm×14cm → 190mm×140mm
                int a = Integer.parseInt(num.substring(0, 2)) * 10;
                int b = Integer.parseInt(num.substring(2, 4)) * 10;
                return a + "×" + b;
            }
            if (num.length() == 5) {
                // 5 位数字 = 3+2，单位 mm
                // 20151 → 201×151
                return num.substring(0, 3) + "×" + num.substring(3, 5);
            }
            if (num.length() == 3) {
                // 3 位数字 = 正方形，单位 mm
                // 141方盒 → 141×141
                return num + "×" + num;
            }
            return num;
        }

        // 圆碗/圆杯：前导数字 + 圆碗/圆杯
        Matcher roundM = Pattern.compile("^(\\d{2,4})(圆碗|圆杯|圆盒)").matcher(n);
        if (roundM.find()) {
            return roundM.group(1);
        }

        return "";
    }

    private void applyRowToMold(PackagingMold mold, PackagingModel model, MoldImportRow row,
                                 String sourceFile, String sourceSheet, Long batchId, boolean isMerge) {
        mold.setModel(model);
        if (mold.getBaseMoldCode() == null) {
            mold.setBaseMoldCode(row.baseMoldCode());
        }
        if (mold.getMoldTypeCode() == null) {
            mold.setMoldTypeCode(row.moldTypeCode());
        }
        if (mold.getCavityCount() == null) {
            mold.setCavityCount(row.cavityCount());
        }
        if (mold.getMoldCategory() == null) {
            mold.setMoldCategory(row.moldCategory());
        }
        if (mold.getCompartmentCount() == null) {
            mold.setCompartmentCount(row.compartmentCount());
        }
        if (mold.getSurfaceStructure() == null) {
            mold.setSurfaceStructure(row.surfaceStructure());
        }
        if (mold.getBottomEngraving() == null) {
            mold.setBottomEngraving(row.bottomEngraving());
        }
        if (mold.getEngravingType() == null) {
            mold.setEngravingType(row.engravingType());
        }
        if (mold.getRawAccessoriesText() == null) {
            mold.setRawAccessoriesText(row.accessories());
        }
        if (mold.getRawCaliber() == null) {
            mold.setRawCaliber(row.caliber());
        }
        if (mold.getRawHeight() == null) {
            mold.setRawHeight(row.rawHeightText());
        }
        mold.setSourceFile(sourceFile);
        mold.setSourceSheet(sourceSheet);
        if (row.sourceRowNumber() != null) {
            mold.setSourceRowNumber(row.sourceRowNumber());
        }
        mold.setImportBatchId(batchId);
    }
}
