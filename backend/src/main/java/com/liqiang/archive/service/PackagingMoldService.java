package com.liqiang.archive.service;

import com.liqiang.archive.dto.PackagingMoldDtos.BatchResult;
import com.liqiang.archive.dto.PackagingMoldDtos.MoldAccessoryPayload;
import com.liqiang.archive.dto.PackagingMoldDtos.MoldAccessoryView;
import com.liqiang.archive.dto.PackagingMoldDtos.MoldByVariantView;
import com.liqiang.archive.dto.PackagingMoldDtos.MoldDetail;
import com.liqiang.archive.dto.PackagingMoldDtos.VariantMoldSummary;
import com.liqiang.archive.dto.PackagingMoldDtos.MoldStats;
import com.liqiang.archive.dto.PackagingMoldDtos.MoldDataQualityReport;
import com.liqiang.archive.dto.PackagingMoldDtos.DataQualityIssueRef;
import com.liqiang.archive.dto.PackagingMoldDtos.MoldHistoryView;
import com.liqiang.archive.dto.PackagingMoldDtos.MoldGroupPage;
import com.liqiang.archive.dto.PackagingMoldDtos.MoldPayload;
import com.liqiang.archive.dto.PackagingMoldDtos.MoldSummary;
import com.liqiang.archive.dto.PackagingMoldDtos.MoldVariantLinkPayload;
import com.liqiang.archive.dto.PackagingMoldDtos.MoldVariantLinkView;
import com.liqiang.archive.dto.PackagingMoldDtos.MoldView;
import com.liqiang.archive.entity.MoldAccessory;
import com.liqiang.archive.entity.MoldVariantLink;
import com.liqiang.archive.entity.PackagingModel;
import com.liqiang.archive.entity.PackagingMold;
import com.liqiang.archive.entity.PackagingMoldGroup;
import com.liqiang.archive.entity.PackagingMoldHistory;
import com.liqiang.archive.entity.PackagingVariant;
import com.liqiang.archive.repository.MoldAccessoryRepository;
import com.liqiang.archive.repository.MoldVariantLinkRepository;
import com.liqiang.archive.repository.PackagingModelRepository;
import com.liqiang.archive.repository.PackagingMoldGroupRepository;
import com.liqiang.archive.repository.PackagingMoldHistoryRepository;
import com.liqiang.archive.repository.PackagingMoldRepository;
import com.liqiang.archive.repository.PackagingVariantRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 包材模具资产台账服务。
 * <p>负责 PackagingMold / MoldVariantLink / MoldAccessory 三表的业务逻辑，
 * 所有写操作均记录审计日志。
 */
@Service
@RequiredArgsConstructor
public class PackagingMoldService {
    private static final Set<String> BATCH_EDITABLE_FIELDS = Set.of(
            "baseMoldCode", "moldTypeCode", "cavityCount", "moldRole", "moldCategory",
            "compartmentCount", "surfaceStructure", "bottomEngraving", "engravingType",
            "rawAccessoriesText", "rawCaliber", "rawHeight", "status", "websiteEnabled", "websiteSortOrder", "remark");

    private final PackagingMoldRepository moldRepo;
    private final MoldVariantLinkRepository linkRepo;
    private final MoldAccessoryRepository accessoryRepo;
    private final PackagingModelRepository modelRepo;
    private final PackagingVariantRepository variantRepo;
    private final PackagingMoldGroupRepository groupRepo;
    private final PackagingMoldHistoryRepository historyRepo;
    private final AuditLogService auditLogService;

    // ====== 状态机定义 ======

    /**
     * 模具状态流转白名单。
     * <p>in_use / repair / stopped 三者互通；unverified 可流向任意状态，任意状态也可标记为 unverified。
     * 同状态（from == to）不视为变更，不记录历史。
     */
    private static final Map<String, Set<String>> ALLOWED_TRANSITIONS = Map.of(
            "in_use", Set.of("repair", "stopped", "unverified"),
            "repair", Set.of("in_use", "stopped", "unverified"),
            "stopped", Set.of("in_use", "repair", "unverified"),
            "unverified", Set.of("in_use", "repair", "stopped", "unverified")
    );

    /** 进入这些状态时建议填写备注原因（不强制，仅作为历史记录的 change_reason 提示）。 */
    private static final Set<String> REMARK_SUGGESTED_TARGETS = Set.of("stopped", "repair");

    /** 合法状态枚举值集合，用于 create 时校验。 */
    private static final Set<String> VALID_STATUSES = Set.of("in_use", "stopped", "repair", "unverified");

    /**
     * 需要记录变更历史的关键字段定义。
     * <p>每项: (fieldKey, fieldLabel, getter)。status 单独走 status_change 类型，其余为 field_update。
     */
    private record FieldDiffDef(String key, String label,
                                 java.util.function.Function<PackagingMold, String> getter) {
    }

    private static final List<FieldDiffDef> TRACKED_FIELDS = List.of(
            new FieldDiffDef("cavityCount", "出模数", m -> m.getCavityCount() == null ? "" : m.getCavityCount().toString()),
            new FieldDiffDef("moldCategory", "模具分类", PackagingMold::getMoldCategory),
            new FieldDiffDef("engravingType", "刻字类型", PackagingMold::getEngravingType),
            new FieldDiffDef("bottomEngraving", "杯底刻字", PackagingMold::getBottomEngraving),
            new FieldDiffDef("surfaceStructure", "表面结构", PackagingMold::getSurfaceStructure),
            new FieldDiffDef("moldRole", "模具角色", PackagingMold::getMoldRole),
            new FieldDiffDef("moldTypeCode", "盒型代码", PackagingMold::getMoldTypeCode)
    );

    // ====== 模具搜索与详情 ======

    @Transactional(readOnly = true)
    public Page<MoldSummary> search(String keyword, Long modelId, String moldRole,
                                     String moldTypeCode, String status, String engravingType,
                                     Boolean hasVariant, Pageable pageable) {
        Page<PackagingMold> page = moldRepo.searchFiltered(
                keyword, modelId, moldRole, moldTypeCode, status, engravingType, hasVariant, pageable);
        return new PageImpl<>(summarizeMolds(page.getContent()), pageable, page.getTotalElements());
    }

    /**
     * 以规格族为分页单位。一个规格族下的全部模具始终在同一页，避免普通模具分页把规格族截断。
     * 未分配规格族的模具沿用前端语义，统一归入“未分组”。
     */
    @Transactional(readOnly = true)
    public MoldGroupPage searchByGroup(String keyword, Long modelId, String moldRole,
                                        String moldTypeCode, String status, String engravingType,
                                        Boolean hasVariant, Pageable pageable) {
        List<PackagingMold> allMolds = moldRepo.searchFiltered(
                keyword, modelId, moldRole, moldTypeCode, status, engravingType, hasVariant, Pageable.unpaged()).getContent();

        Map<String, List<PackagingMold>> groups = new LinkedHashMap<>();
        allMolds.stream()
                .sorted(java.util.Comparator
                        .comparing((PackagingMold mold) -> mold.getGroup() == null ? 1 : 0)
                        .thenComparing(mold -> mold.getGroup() == null ? "未分组" : mold.getGroup().getName(),
                                java.text.Collator.getInstance(java.util.Locale.CHINA))
                        .thenComparing(PackagingMold::getMoldCode, java.util.Comparator.nullsLast(String::compareToIgnoreCase)))
                .forEach(mold -> {
                    String groupKey = mold.getGroup() == null ? "ungrouped" : "group:" + mold.getGroup().getId();
                    groups.computeIfAbsent(groupKey, ignored -> new ArrayList<>()).add(mold);
                });

        List<List<PackagingMold>> groupList = new ArrayList<>(groups.values());
        int fromIndex = Math.min((int) pageable.getOffset(), groupList.size());
        int toIndex = Math.min(fromIndex + pageable.getPageSize(), groupList.size());
        List<PackagingMold> pageMolds = groupList.subList(fromIndex, toIndex).stream().flatMap(List::stream).toList();

        List<MoldSummary> content = summarizeMolds(pageMolds);
        int totalGroups = groupList.size();
        int totalPages = totalGroups == 0 ? 0 : (int) Math.ceil((double) totalGroups / pageable.getPageSize());
        return new MoldGroupPage(
                content,
                totalGroups,
                totalPages,
                pageable.getPageSize(),
                pageable.getPageNumber(),
                content.size(),
                pageable.getPageNumber() == 0,
                pageable.getPageNumber() + 1 >= totalPages,
                content.isEmpty()
        );
    }

    private List<MoldSummary> summarizeMolds(List<PackagingMold> molds) {
        if (molds.isEmpty()) {
            return List.of();
        }
        // 批量预取 variantLink 与 accessory 计数，避免 N+1
        List<Long> moldIds = molds.stream().map(PackagingMold::getId).toList();
        Map<Long, Long> variantCountMap = countVariantLinksByMoldIds(moldIds);
        Map<Long, Long> accessoryCountMap = countAccessoriesByMoldIds(moldIds);

        // 口径/高度优先从模具自身 rawCaliber / rawHeight（导入原始值）读取；
        // 当模具本身没有值时才回退到关联规格汇总（已有规格但导入漏填的场景）。
        Map<Long, String[]> linkedDims = loadRepresentativeDimensions(molds);

        return molds.stream().map(m -> {
            long vc = variantCountMap.getOrDefault(m.getId(), 0L);
            long ac = accessoryCountMap.getOrDefault(m.getId(), 0L);

            String caliber = normalizeCaliberDisplay(m.getRawCaliber());
            String height = normalizeHeightDisplay(m.getRawHeight());

            if (caliber == null || height == null) {
                String[] fallback = linkedDims.getOrDefault(m.getId(), new String[]{null, null});
                if (caliber == null) caliber = fallback[0];
                if (height == null) height = fallback[1];
            }

            return MoldSummary.from(m, vc, ac, caliber, height);
        }).toList();
    }

    /**
     * 为模具列表批量加载代表性规格的口径和高度。
     * <p>口径：取第一个关联规格的尺寸（矩形显示 "长×宽"，圆形显示 "C直径"）。
     * 高度：汇总所有关联规格的高度，去重后用 " / " 连接（如 "25 / 35 / 45 / 55"）。
     */
    private Map<Long, String[]> loadRepresentativeDimensions(List<PackagingMold> molds) {
        Map<Long, String[]> result = new java.util.HashMap<>();

        if (molds.isEmpty()) {
            return result;
        }

        List<Long> moldIds = molds.stream().map(PackagingMold::getId).toList();
        List<MoldVariantLink> allLinks = linkRepo.findByMoldIdIn(moldIds);

        if (allLinks.isEmpty()) {
            return result;
        }

        // 按模具ID分组所有链接
        Map<Long, List<MoldVariantLink>> linksByMoldId = allLinks.stream()
                .collect(Collectors.groupingBy(link -> link.getMold().getId()));

        // 收集所有规格ID
        List<Long> allVariantIds = allLinks.stream()
                .map(link -> link.getVariant().getId())
                .distinct()
                .toList();

        if (allVariantIds.isEmpty()) {
            return result;
        }

        // 批量查询规格
        Map<Long, PackagingVariant> variantMap = variantRepo.findAllById(allVariantIds).stream()
                .collect(Collectors.toMap(PackagingVariant::getId, v -> v, (a, b) -> a));

        // 为每个模具生成口径（首规格）和高度（所有规格汇总）
        for (PackagingMold mold : molds) {
            List<MoldVariantLink> moldLinks = linksByMoldId.getOrDefault(mold.getId(), List.of());
            if (moldLinks.isEmpty()) continue;

            // 口径：取第一个规格
            MoldVariantLink firstLink = moldLinks.get(0);
            PackagingVariant firstVariant = variantMap.get(firstLink.getVariant().getId());
            String caliber = firstVariant != null ? buildCaliberDisplay(firstVariant) : null;

            // 高度：汇总所有规格，去重后用 " / " 连接，每个值带 mm 后缀
            Set<String> heights = new HashSet<>();
            for (MoldVariantLink link : moldLinks) {
                PackagingVariant v = variantMap.get(link.getVariant().getId());
                if (v != null && v.getHeightMm() != null && !v.getHeightMm().isBlank()) {
                    heights.add(v.getHeightMm().trim() + "mm");
                }
            }
            String height = heights.isEmpty() ? null : String.join(" / ", heights);

            if (caliber != null || height != null) {
                result.put(mold.getId(), new String[]{caliber, height});
            }
        }

        return result;
    }

    /**
     * 构建规格的口径显示文本。
     * 矩形：显示 "长×宽mm"（如 140×140mm）
     * 圆形：显示 "Ø直径mm"（如 Ø120mm）
     */
    private String buildCaliberDisplay(PackagingVariant variant) {
        // 优先使用 topDiameterMm（圆形口径）
        String diameter = variant.getTopDiameterMm();
        if (diameter != null && !diameter.isBlank()) {
            return "Ø" + diameter.trim() + "mm";
        }
        // 使用 bottomDiameterMm
        String bottomDiameter = variant.getBottomDiameterMm();
        if (bottomDiameter != null && !bottomDiameter.isBlank()) {
            return "Ø" + bottomDiameter.trim() + "mm";
        }
        // 使用 lengthMm × widthMm（矩形口径）
        String length = variant.getLengthMm();
        String width = variant.getWidthMm();
        if (length != null && !length.isBlank() && width != null && !width.isBlank()) {
            return length.trim() + "×" + width.trim() + "mm";
        }
        // 只有 length
        if (length != null && !length.isBlank()) {
            return length.trim() + "mm";
        }
        return null;
    }

    /**
     * 规范化导入原始口径（Excel "产品口径/mm" 列）为卡片显示格式。
     * <p>支持常见写法：141×141 / 141*141 / 141x141 / C120 / Ø120 / 120
     *    结果：141×141mm 或 Ø120mm
     */
    private String normalizeCaliberDisplay(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String s = raw.trim();
        // 已经带 mm 后缀的不重复加
        boolean hasUnit = s.endsWith("mm") || s.endsWith("MM");
        String body = hasUnit ? s.substring(0, s.length() - 2).trim() : s;

        // 圆形口径：Cxxx / Øxxx / Rxxx -> Øxxxmm
        String upper = body.toUpperCase();
        if (upper.startsWith("C") || upper.startsWith("Ø") || upper.startsWith("R") || upper.startsWith("Φ")) {
            String num = body.replaceAll("^[CØRΦ]\\s*", "");
            if (!num.isBlank()) {
                return "Ø" + num + "mm";
            }
        }

        // 方盒口径：长×宽，兼容 * / x / X / ＊ / ×
        if (body.matches(".*[*xX×＊].*")) {
            String[] parts = body.split("[*xX×＊]");
            if (parts.length == 2 && !parts[0].isBlank() && !parts[1].isBlank()) {
                return parts[0].trim() + "×" + parts[1].trim() + "mm";
            }
            // 如 "141*141" 已匹配
        }

        // 只有一个数字 -> 当作口径本身（如 140 -> 140mm）
        if (body.matches("\\d+(\\.\\d+)?")) {
            return body + "mm";
        }

        // 其他无法识别：原样返回 + mm（如有）
        return hasUnit ? raw.trim() : raw.trim() + "mm";
    }

    /**
     * 规范化导入原始高度（Excel "高度/mm" 列）为卡片显示格式。
     * <p>支持分隔符：+ / 、/ ，/ ; / / 和 空格
     *    如 "25+35+45+55" -> "25mm / 35mm / 45mm / 55mm"
     */
    private String normalizeHeightDisplay(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String s = raw.trim();
        if (isNo(s)) return null;

        // 按常见分隔符切分（+ / 、/ ， / ; / / / 空格）
        String[] parts = s.split("[+＋、，;；,/\\s]+");
        Set<String> unique = new HashSet<>();
        List<String> ordered = new ArrayList<>();
        for (String p : parts) {
            String v = p.trim();
            if (v.isEmpty()) continue;
            // 去掉自带的 mm 后缀，统一再加
            if (v.toLowerCase().endsWith("mm")) {
                v = v.substring(0, v.length() - 2).trim();
            }
            if (v.isEmpty()) continue;
            if (unique.add(v)) ordered.add(v);
        }
        if (ordered.isEmpty()) return null;
        if (ordered.size() == 1) return ordered.get(0) + "mm";
        return String.join(" / ", ordered.stream().map(v -> v + "mm").toList());
    }

    private boolean isNo(String s) {
        if (s == null) return true;
        String t = s.trim();
        return t.isEmpty() || t.equals("-") || t.equalsIgnoreCase("无")
                || t.equalsIgnoreCase("n/a") || t.equalsIgnoreCase("none")
                || t.equalsIgnoreCase("null");
    }

    @Transactional(readOnly = true)
    public MoldDetail get(Long id) {
        PackagingMold mold = moldRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("模具不存在: " + id));
        List<MoldVariantLink> links = linkRepo.findByMoldIdOrderByCreatedAtAsc(id);
        List<MoldAccessory> accessories = accessoryRepo.findByMoldIdOrderByAccessoryTypeAscIdAsc(id);
        return new MoldDetail(
                MoldView.from(mold),
                links.stream().map(MoldVariantLinkView::from).toList(),
                accessories.stream().map(MoldAccessoryView::from).toList()
        );
    }

    /**
     * 按规格反查关联模具（用于包材规格详情显示「适配模具」）。
     */
    @Transactional(readOnly = true)
    public List<MoldByVariantView> findMoldsByVariant(Long variantId) {
        return linkRepo.findByVariantIdOrderByCreatedAtAsc(variantId).stream()
                .map(MoldByVariantView::from)
                .toList();
    }

    /**
     * 按系列批量查询各规格的模具汇总：默认模具 + 总数，供规格卡片展示。
     */
    @Transactional(readOnly = true)
    public List<VariantMoldSummary> summarizeVariantMoldsByModel(Long modelId) {
        return linkRepo.summarizeByModelId(modelId);
    }

    @Transactional(readOnly = true)
    public Map<String, List<String>> getFilterOptions() {
        return Map.of(
                "moldTypeCodes", moldRepo.findDistinctTypeCodes(),
                "statuses", moldRepo.findDistinctStatuses(),
                "moldRoles", moldRepo.findDistinctRoles()
        );
    }

    @Transactional(readOnly = true)
    public MoldStats getStats() {
        long total = moldRepo.count();
        long inUse = moldRepo.countByStatus("in_use");
        long stopped = moldRepo.countByStatus("stopped");
        long repair = moldRepo.countByStatus("repair");
        long unverified = moldRepo.countByStatus("unverified");
        long mainMold = moldRepo.countByMoldRole("main");
        long accessoryMold = moldRepo.countByMoldRole("accessory");
        long variantLinkCount = linkRepo.count();
        long accessoryItemCount = accessoryRepo.count();
        return new MoldStats(total, inUse, stopped, repair, unverified,
                mainMold, accessoryMold, variantLinkCount, accessoryItemCount);
    }

    /**
     * 运行只读的模具/规格/关联数据质量审计。
     * <p>不会落库也不会修改数据库状态；发现的问题按类型分桶返回，便于前端展示或导出修复清单。
     * 这是 V52/V53 等迁移后用于发现遗留脏数据的通用只读审计入口。
     */
    @Transactional(readOnly = true)
    public MoldDataQualityReport auditQuality() {
        return new MoldDataQualityReport(
                findMoldModelGroupMismatches(),
                findMoldsWithoutGroup(),
                findCrossModelLinks(),
                findMoldsWithoutVariant(),
                findVariantsWithoutMold(),
                findDuplicateGroupNames(),
                findMoldCodeSharedAcrossModels(),
                findDefaultMoldInactive(),
                findMoldDeletedVariantReferences()
        );
    }

    /**
     * 主动修复可自动解决的两类数据质量问题（与 {@link ArchiveScheduler#scanAndFixPackagingMoldIntegrity}
     * 的 05:00 定时任务完全一致，只是改为用户主动触发）：
     * <ol>
     *   <li>双归属不一致：将 mold.model_id 回填为 mold.group.model_id</li>
     *   <li>重复默认模具：保留每个 variant 最早创建的默认关联，其余置为非默认</li>
     * </ol>
     * <p>修复后自动写入审计日志，便于追溯用户手动修复行为。
     *
     * @return [双归属修复条数, 重复默认清除条数]
     */
    @Transactional
    public long[] fixDataQuality() {
        long fixedOwnership = 0;
        long fixedDefaults = 0;

        List<DataQualityIssueRef> mismatches = findMoldModelGroupMismatches();
        if (!mismatches.isEmpty()) {
            fixedOwnership = moldRepo.fixMoldModelFromGroup();
            if (fixedOwnership > 0) {
                auditLogService.log("PackagingMold", null, null,
                        "PKG_MOLD_INTEGRITY_FIX_MANUAL",
                        "用户主动触发双归属自动修复：回填 " + fixedOwnership + " 条 mold.model_id");
            }
        }

        List<DataQualityIssueRef> dupDefaults = findDefaultMoldInactive()
                .isEmpty() ? List.of() : findDuplicateDefaultLinks();
        if (!dupDefaults.isEmpty()) {
            fixedDefaults = linkRepo.fixDuplicateDefaultLinks();
            if (fixedDefaults > 0) {
                auditLogService.log("MoldVariantLink", null, null,
                        "PKG_MOLD_INTEGRITY_FIX_MANUAL",
                        "用户主动触发重复默认模具去重：清除 " + fixedDefaults + " 条多余默认标记");
            }
        }

        return new long[]{fixedOwnership, fixedDefaults};
    }

    /** 重复默认模具扫描（用于 fixDataQuality 触发条件判断） */
    private List<DataQualityIssueRef> findDuplicateDefaultLinks() {
        List<?> rows = linkRepo.findDuplicateDefaultLinks();
        return rows.stream().map(r -> {
            Object[] o = (Object[]) r;
            return new DataQualityIssueRef(
                    null, null, (Long) o[0], null, null, null, null, null, null,
                    "重复默认模具 " + o[1] + " 条"
            );
        }).toList();
    }

    // 1) 模具 model_id 与 mold.group.model_id 不一致（双归属重复真相）
    List<DataQualityIssueRef> findMoldModelGroupMismatches() {
        List<?> rows = moldRepo.findMoldModelGroupMismatchesRaw();
        return rows.stream().map(r -> {
            Object[] o = (Object[]) r;
            return new DataQualityIssueRef(
                    (Long) o[0], (String) o[1], null, null, (Long) o[2], (String) o[3],
                    (Long) o[4], (String) o[5], null,
                    String.format("模具系列#%s vs 规格族系列#%s 不一致", o[4], o[6])
            );
        }).toList();
    }

    // 2) 模具有系列但未分配规格族（包材详情中会落在分组之外）
    List<DataQualityIssueRef> findMoldsWithoutGroup() {
        return moldRepo.findByGroupIdIsNullAndModelIdIsNotNullAndDeletedAtIsNullOrderByMoldCodeAsc().stream()
                .map(m -> new DataQualityIssueRef(
                        m.getId(), m.getMoldCode(), null, null, null, null,
                        m.getModel() != null ? m.getModel().getId() : null,
                        m.getModel() != null ? m.getModel().getName() : null,
                        null, "未分配规格族，包材详情会落入未分组区"))
                .toList();
    }

    // 3) MoldVariantLink 跨越了不同大系列
    List<DataQualityIssueRef> findCrossModelLinks() {
        List<?> rows = linkRepo.findCrossModelLinkRows();
        return rows.stream().map(r -> {
            Object[] o = (Object[]) r;
            return new DataQualityIssueRef(
                    (Long) o[0], (String) o[1], (Long) o[2], (String) o[3], null, null,
                    null, null, (Long) o[4],
                    String.format("模具系列#%s %s ≠ 规格系列#%s %s", o[5], o[6], o[7], o[8])
            );
        }).toList();
    }

    // 4) 模具未关联任何规格
    List<DataQualityIssueRef> findMoldsWithoutVariant() {
        List<PackagingMold> all = moldRepo.findAllWithoutVariantLinks();
        return all.stream()
                .limit(200)
                .map(m -> new DataQualityIssueRef(
                        m.getId(), m.getMoldCode(), null, null,
                        m.getGroup() != null ? m.getGroup().getId() : null,
                        m.getGroup() != null ? m.getGroup().getName() : null,
                        m.getModel() != null ? m.getModel().getId() : null,
                        m.getModel() != null ? m.getModel().getName() : null,
                        null, "该模具尚未关联任何包材规格"))
                .toList();
    }

    // 5) 规格没有任何模具关联
    List<DataQualityIssueRef> findVariantsWithoutMold() {
        List<?> rows = variantRepo.findVariantsWithoutMoldRaw();
        return rows.stream().map(r -> {
            Object[] o = (Object[]) r;
            return new DataQualityIssueRef(
                    null, null,
                    (Long) o[0], (String) o[1],
                    null, null,
                    (Long) o[2], (String) o[3],
                    null, "该规格暂无可用模具关联");
        }).toList();
    }

    // 6) 同一系列下存在重复规格族名称（V55 已加唯一约束，用于清理遗留或竞态诊断）
    List<DataQualityIssueRef> findDuplicateGroupNames() {
        List<?> rows = groupRepo.findDuplicateGroupNamesRaw();
        return rows.stream().map(r -> {
            Object[] o = (Object[]) r;
            return new DataQualityIssueRef(
                    null, null, null, null, null, (String) o[2],
                    (Long) o[0], (String) o[1], null,
                    String.format("该系列下同名规格族共 %d 组（V55 前并发导入遗留）", o[3]));
        }).toList();
    }

    // 7) 同一模号被分配到不同的包材大系列
    List<DataQualityIssueRef> findMoldCodeSharedAcrossModels() {
        List<?> rows = moldRepo.findMoldCodeSharedCrossModelsRaw();
        return rows.stream().map(r -> {
            Object[] o = (Object[]) r;
            String code = (String) o[0];
            Long count = (Long) o[1];
            return new DataQualityIssueRef(
                    null, code, null, null, null, null, null, null, null,
                    String.format("同一模号被分配到 %d 个不同大系列", count)
            );
        }).toList();
    }

    // 8) 默认模具被标记为停用/维修，但仍为该规格的默认模具
    List<DataQualityIssueRef> findDefaultMoldInactive() {
        List<?> rows = linkRepo.findDefaultMoldInactiveRows();
        return rows.stream().map(r -> {
            Object[] o = (Object[]) r;
            return new DataQualityIssueRef(
                    (Long) o[0], (String) o[1], (Long) o[2], (String) o[3], null, null,
                    (Long) o[4], (String) o[5], (Long) o[6],
                    String.format("默认模具状态为 %s，需重新分配默认模具", o[7]));
        }).toList();
    }

    // 9) 模具关联的规格所属 model 已被软删除（残留引用）
    List<DataQualityIssueRef> findMoldDeletedVariantReferences() {
        List<?> rows = linkRepo.findMoldDeletedVariantReferenceRows();
        return rows.stream().map(r -> {
            Object[] o = (Object[]) r;
            return new DataQualityIssueRef(
                    (Long) o[0], (String) o[1], (Long) o[2], (String) o[3], null, null,
                    (Long) o[4], (String) o[5], (Long) o[6],
                    "规格所属包材大系列已被软删除，需解除关联或恢复系列");
        }).toList();
    }

    // ====== 模具 CRUD ======

    @Transactional
    public MoldView create(MoldPayload payload) {
        if (payload.modelId() == null) {
            throw new IllegalArgumentException("modelId 不能为空");
        }
        if (payload.moldCode() == null || payload.moldCode().isBlank()) {
            throw new IllegalArgumentException("moldCode 不能为空");
        }
        if (payload.status() != null && !VALID_STATUSES.contains(payload.status())) {
            throw new IllegalArgumentException(
                    "非法状态值: " + payload.status() + "（合法: in_use/stopped/repair/unverified）");
        }
        if (moldRepo.findByMoldCode(payload.moldCode()).isPresent()) {
            throw new IllegalArgumentException("模号已存在: " + payload.moldCode());
        }
        PackagingModel model = modelRepo.findById(payload.modelId())
                .orElseThrow(() -> new IllegalArgumentException("包材系列不存在: " + payload.modelId()));
        // 规格族归属一致性：携带 groupId 时必须与 modelId 属于同一系列
        PackagingMoldGroup group = resolveGroupConsistent(payload.groupId(), model);
        PackagingMold mold = new PackagingMold();
        payload.applyToEntity(mold, model, group);
        PackagingMold saved = moldRepo.save(mold);
        auditLogService.log("PackagingMold", saved.getId(), saved.getMoldCode(),
                "PKG_MOLD_CREATE", "创建模具 " + saved.getMoldCode());
        return MoldView.from(saved);
    }

    @Transactional
    public MoldView update(Long id, MoldPayload payload) {
        PackagingMold existing = moldRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("模具不存在: " + id));
        // 模号唯一性校验（变更时）
        if (!existing.getMoldCode().equals(payload.moldCode())) {
            moldRepo.findByMoldCode(payload.moldCode()).ifPresent(m -> {
                throw new IllegalArgumentException("模号已存在: " + payload.moldCode());
            });
        }
        PackagingModel model = modelRepo.findById(payload.modelId())
                .orElseThrow(() -> new IllegalArgumentException("包材系列不存在: " + payload.modelId()));
        // 规格族归属一致性 invariant：mold.model_id 必须与 mold.group.model_id 一致。
        // - 携带 groupId：校验与 modelId 同系列，否则拒绝保存
        // - 未携带 groupId：若现有 group 仍与新系列一致则保留，否则清空（旧规格族不再适用）
        PackagingMoldGroup group;
        if (payload.groupId() != null) {
            group = resolveGroupConsistent(payload.groupId(), model);
        } else if (existing.getGroup() != null) {
            Long existingGroupModelId = existing.getGroup().getModel() != null
                    ? existing.getGroup().getModel().getId() : null;
            group = model.getId().equals(existingGroupModelId) ? existing.getGroup() : null;
        } else {
            group = null;
        }
        // 状态流转校验（applyToEntity 之前，用 existing 旧 status）
        String oldStatus = existing.getStatus();
        String newStatus = payload.status() != null ? payload.status() : oldStatus;
        validateStatusTransition(oldStatus, newStatus);
        // 捕获关键字段旧值快照（用于 diff 检测）
        Map<String, String> oldValues = captureTrackedValues(existing);
        payload.applyToEntity(existing, model, group);
        PackagingMold saved = moldRepo.save(existing);
        // 记录字段变更历史（status 单独走 status_change 类型）
        recordChanges(saved, oldValues, oldStatus, saved.getStatus(),
                payload.remark(), "manual");
        auditLogService.log("PackagingMold", saved.getId(), saved.getMoldCode(),
                "PKG_MOLD_UPDATE", "更新模具 " + saved.getMoldCode());
        return MoldView.from(saved);
    }

    /**
     * 查询模具变更历史（按时间倒序）。
     */
    @Transactional(readOnly = true)
    public List<MoldHistoryView> getHistory(Long moldId) {
        return historyRepo.findByMoldIdOrderByCreatedAtDescIdDesc(moldId).stream()
                .map(MoldHistoryView::from)
                .toList();
    }

    /**
     * 导入 merge 场景的字段变更记录入口。
     * <p>导入 merge 只补填空字段（null → 有值），不改变 status。
     * 本方法捕获旧值快照 → 执行 updater → 保存 → 记录历史，供 ImportService 调用，
     * 避免在 ImportService 重复 diff 逻辑。
     *
     * @param mold    已加载的持久化模具（merge 场景）
     * @param updater 执行字段补填 + 保存的回调（通常调用 applyRowToMold）
     * @param source  变更来源（"import" / "migration"）
     */
    @Transactional
    public void mergeWithHistory(PackagingMold mold, Runnable updater, String source) {
        Map<String, String> oldValues = captureTrackedValues(mold);
        String oldStatus = mold.getStatus();
        updater.run();
        PackagingMold saved = moldRepo.save(mold);
        recordChanges(saved, oldValues, oldStatus, saved.getStatus(), null, source);
    }

    @Transactional
    public void delete(Long id) {
        PackagingMold mold = moldRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("模具不存在: " + id));
        mold.setDeletedAt(LocalDateTime.now());
        moldRepo.save(mold);
        // 级联软删除关联的 link 与 accessory
        linkRepo.softDeleteByMoldId(id);
        accessoryRepo.softDeleteByMoldId(id);
        auditLogService.log("PackagingMold", id, mold.getMoldCode(),
                "PKG_MOLD_DELETE", "移入回收站：模具 " + mold.getMoldCode());
    }

    @Transactional
    public BatchResult batchDelete(List<Long> ids) {
        List<PackagingMold> molds = moldRepo.findAllById(ids);
        LocalDateTime now = LocalDateTime.now();
        for (PackagingMold m : molds) {
            m.setDeletedAt(now);
            linkRepo.softDeleteByMoldId(m.getId());
            accessoryRepo.softDeleteByMoldId(m.getId());
        }
        if (!molds.isEmpty()) {
            moldRepo.saveAll(molds);
        }
        int count = molds.size();
        if (count > 0) {
            auditLogService.log("PackagingMold", null, "模具",
                    "PKG_MOLD_BATCH_DELETE", "批量移入回收站：" + count + " 个模具");
        }
        return new BatchResult(count);
    }

    @Transactional
    public BatchResult batchUpdateFields(List<Long> ids, Map<String, Object> fields) {
        if (ids == null || ids.isEmpty()) return new BatchResult(0);
        if (fields == null || fields.isEmpty()) throw new IllegalArgumentException("未指定要修改的字段");
        for (String key : fields.keySet()) {
            if (!BATCH_EDITABLE_FIELDS.contains(key)) throw new IllegalArgumentException("不支持批量修改字段: " + key);
        }
        List<PackagingMold> molds = moldRepo.findAllById(ids);
        for (PackagingMold mold : molds) {
            Map<String, String> oldValues = captureTrackedValues(mold);
            String oldStatus = mold.getStatus();
            applyBatchFields(mold, fields);
            if (fields.containsKey("status")) validateStatusTransition(oldStatus, mold.getStatus());
            validateWebsitePublication(mold);
            PackagingMold saved = moldRepo.save(mold);
            recordChanges(saved, oldValues, oldStatus, saved.getStatus(), saved.getRemark(), "batch");
        }
        if (!molds.isEmpty()) auditLogService.log("PackagingMold", null, "模具", "PKG_MOLD_BATCH_EDIT",
                "批量修改 " + molds.size() + " 个模具，字段：" + fields.keySet());
        return new BatchResult(molds.size());
    }

    private void applyBatchFields(PackagingMold mold, Map<String, Object> fields) {
        fields.forEach((key, value) -> {
            String text = value == null ? null : String.valueOf(value).trim();
            switch (key) {
                case "baseMoldCode" -> mold.setBaseMoldCode(text);
                case "moldTypeCode" -> mold.setMoldTypeCode(text);
                case "cavityCount" -> mold.setCavityCount(text == null || text.isBlank() ? null : Integer.valueOf(text));
                case "moldRole" -> mold.setMoldRole(text);
                case "moldCategory" -> mold.setMoldCategory(text);
                case "compartmentCount" -> mold.setCompartmentCount(text);
                case "surfaceStructure" -> mold.setSurfaceStructure(text);
                case "bottomEngraving" -> mold.setBottomEngraving(text);
                case "engravingType" -> mold.setEngravingType(text);
                case "rawAccessoriesText" -> mold.setRawAccessoriesText(text);
                case "rawCaliber" -> mold.setRawCaliber(text);
                case "rawHeight" -> mold.setRawHeight(text);
                case "status" -> mold.setStatus(text);
                case "websiteEnabled" -> mold.setWebsiteEnabled(value instanceof Boolean b ? b : Boolean.parseBoolean(text));
                case "websiteSortOrder" -> mold.setWebsiteSortOrder(text == null || text.isBlank() ? 0 : Integer.valueOf(text));
                case "remark" -> mold.setRemark(text);
                default -> throw new IllegalArgumentException("不支持批量修改字段: " + key);
            }
        });
    }

    private void validateWebsitePublication(PackagingMold mold) {
        if (!Boolean.TRUE.equals(mold.getWebsiteEnabled())) return;
        if (!"main".equals(mold.getMoldRole())) {
            throw new IllegalArgumentException("模具“" + mold.getMoldCode() + "”不是主模，无法发布到官网");
        }
        if (!"in_use".equals(mold.getStatus())) {
            throw new IllegalArgumentException("模具“" + mold.getMoldCode() + "”当前不在用，无法发布到官网");
        }
    }

    // ====== 模具↔规格 关联 CRUD ======

    @Transactional
    public MoldVariantLinkView addVariantLink(MoldVariantLinkPayload payload) {
        PackagingMold mold = moldRepo.findById(payload.moldId())
                .orElseThrow(() -> new IllegalArgumentException("模具不存在: " + payload.moldId()));
        PackagingVariant variant = variantRepo.findById(payload.variantId())
                .orElseThrow(() -> new IllegalArgumentException("规格不存在: " + payload.variantId()));
        // 禁止跨系列关联：模具所属大系列必须等于规格所属大系列
        assertSameModel(mold, variant);
        // 重复校验
        if (!linkRepo.findByMoldAndVariant(payload.moldId(), payload.variantId()).isEmpty()) {
            throw new IllegalArgumentException("模具与规格已关联");
        }
        // 清理同名软删除关联，避免唯一索引 uk_mold_variant 冲突
        linkRepo.permanentDeleteSoftDeletedByMoldAndVariant(payload.moldId(), payload.variantId());
        // 设置为默认前，先取消同规格下其他默认，配合 V53 部分唯一索引保证每规格最多一个默认模具
        if (Boolean.TRUE.equals(payload.isDefault())) {
            linkRepo.clearDefaultsByVariantId(payload.variantId());
        }
        MoldVariantLink link = new MoldVariantLink();
        payload.applyToEntity(link, mold, variant);
        MoldVariantLink saved = linkRepo.save(link);
        auditLogService.log("MoldVariantLink", saved.getId(), mold.getMoldCode(),
                "PKG_MOLD_LINK_CREATE", "模具 " + mold.getMoldCode() + " 关联规格 #" + payload.variantId());
        return MoldVariantLinkView.from(saved);
    }

    @Transactional
    public MoldVariantLinkView updateVariantLink(Long id, MoldVariantLinkPayload payload) {
        MoldVariantLink existing = linkRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("关联不存在: " + id));
        PackagingMold mold = moldRepo.findById(payload.moldId())
                .orElseThrow(() -> new IllegalArgumentException("模具不存在: " + payload.moldId()));
        PackagingVariant variant = variantRepo.findById(payload.variantId())
                .orElseThrow(() -> new IllegalArgumentException("规格不存在: " + payload.variantId()));
        // 禁止跨系列关联
        assertSameModel(mold, variant);
        // 设置为默认前，先取消同规格下其他默认，配合 V53 部分唯一索引保证每规格最多一个默认模具
        if (Boolean.TRUE.equals(payload.isDefault())) {
            linkRepo.clearDefaultsByVariantId(payload.variantId());
        }
        payload.applyToEntity(existing, mold, variant);
        MoldVariantLink saved = linkRepo.save(existing);
        auditLogService.log("MoldVariantLink", saved.getId(), mold.getMoldCode(),
                "PKG_MOLD_LINK_UPDATE", "更新模具-规格关联 #" + id);
        return MoldVariantLinkView.from(saved);
    }

    @Transactional
    public void removeVariantLink(Long id) {
        MoldVariantLink link = linkRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("关联不存在: " + id));
        link.setDeletedAt(LocalDateTime.now());
        linkRepo.save(link);
        auditLogService.log("MoldVariantLink", id, "模具-规格关联",
                "PKG_MOLD_LINK_DELETE", "解除模具-规格关联 #" + id);
    }

    // ====== 配件 CRUD ======

    @Transactional
    public MoldAccessoryView addAccessory(MoldAccessoryPayload payload) {
        PackagingMold mold = moldRepo.findById(payload.moldId())
                .orElseThrow(() -> new IllegalArgumentException("模具不存在: " + payload.moldId()));
        if (payload.accessoryType() == null || payload.accessoryType().isBlank()) {
            throw new IllegalArgumentException("配件类型不能为空");
        }
        MoldAccessory accessory = new MoldAccessory();
        payload.applyToEntity(accessory, mold);
        MoldAccessory saved = accessoryRepo.save(accessory);
        auditLogService.log("MoldAccessory", saved.getId(), mold.getMoldCode(),
                "PKG_MOLD_ACC_CREATE", "模具 " + mold.getMoldCode() + " 新增配件 " + payload.accessoryType());
        return MoldAccessoryView.from(saved);
    }

    @Transactional
    public MoldAccessoryView updateAccessory(Long id, MoldAccessoryPayload payload) {
        MoldAccessory existing = accessoryRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("配件不存在: " + id));
        PackagingMold mold = moldRepo.findById(payload.moldId())
                .orElseThrow(() -> new IllegalArgumentException("模具不存在: " + payload.moldId()));
        payload.applyToEntity(existing, mold);
        MoldAccessory saved = accessoryRepo.save(existing);
        auditLogService.log("MoldAccessory", saved.getId(), mold.getMoldCode(),
                "PKG_MOLD_ACC_UPDATE", "更新模具配件 #" + id);
        return MoldAccessoryView.from(saved);
    }

    @Transactional
    public void removeAccessory(Long id) {
        MoldAccessory accessory = accessoryRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("配件不存在: " + id));
        accessory.setDeletedAt(LocalDateTime.now());
        accessoryRepo.save(accessory);
        auditLogService.log("MoldAccessory", id, "模具配件",
                "PKG_MOLD_ACC_DELETE", "删除模具配件 #" + id);
    }

    // ====== 私有辅助 ======

    /**
     * 校验模具与规格属于同一包材大系列，禁止跨系列关联。
     * <p>这是「手工关联不能跨系列」的核心约束：1414方盒模具只能关联到同一系列的规格，
     * 不能关联到其他大系列（如另一个包材系列）的规格。
     */
    private void assertSameModel(PackagingMold mold, PackagingVariant variant) {
        Long moldModelId = mold.getModel() != null ? mold.getModel().getId() : null;
        Long variantModelId = variant.getModel() != null ? variant.getModel().getId() : null;
        if (!Objects.equals(moldModelId, variantModelId)) {
            throw new IllegalArgumentException("禁止跨系列关联：模具所属系列与规格所属系列不一致");
        }
    }

    /**
     * 解析规格族并校验与目标系列一致。
     * <p>groupId 为 null 时返回 null（不指定规格族）；非 null 时必须属于 model 系列，
     * 否则拒绝保存，避免产生 mold.model_id 与 mold.group.model_id 不一致的重复真相。
     */
    private PackagingMoldGroup resolveGroupConsistent(Long groupId, PackagingModel model) {
        if (groupId == null) return null;
        PackagingMoldGroup group = groupRepo.findById(groupId)
                .orElseThrow(() -> new IllegalArgumentException("规格族不存在: " + groupId));
        Long groupModelId = group.getModel() != null ? group.getModel().getId() : null;
        if (!Objects.equals(groupModelId, model.getId())) {
            throw new IllegalArgumentException("规格族不属于当前包材系列，拒绝保存");
        }
        return group;
    }

    private Map<Long, Long> countVariantLinksByMoldIds(List<Long> moldIds) {
        if (moldIds.isEmpty()) {
            return Map.of();
        }
        return linkRepo.countByMoldIds(moldIds).stream()
                .collect(Collectors.toMap(arr -> (Long) arr[0], arr -> (Long) arr[1]));
    }

    private Map<Long, Long> countAccessoriesByMoldIds(List<Long> moldIds) {
        if (moldIds.isEmpty()) {
            return Map.of();
        }
        return accessoryRepo.countByMoldIds(moldIds).stream()
                .collect(Collectors.toMap(arr -> (Long) arr[0], arr -> (Long) arr[1]));
    }

    // ====== 状态机与变更历史辅助 ======

    /**
     * 校验状态流转是否在白名单内。
     * <p>from == to 时不视为变更，直接放行；否则 to 必须在 ALLOWED_TRANSITIONS[from] 中。
     * 非法当前状态（不在 4 个枚举内）或非法流转抛 IllegalArgumentException。
     */
    private void validateStatusTransition(String from, String to) {
        if (Objects.equals(from, to)) {
            return;
        }
        Set<String> allowed = ALLOWED_TRANSITIONS.get(from);
        if (allowed == null) {
            throw new IllegalArgumentException("未知的模具当前状态: " + from);
        }
        if (!allowed.contains(to)) {
            throw new IllegalArgumentException(
                    "不允许的状态流转: " + from + " → " + to + "（合法目标: " + allowed + "）");
        }
    }

    /**
     * 捕获关键字段当前值快照（normalize null → ""），用于 update 前后 diff 检测。
     */
    private Map<String, String> captureTrackedValues(PackagingMold m) {
        Map<String, String> values = new java.util.HashMap<>();
        for (FieldDiffDef def : TRACKED_FIELDS) {
            String v = def.getter().apply(m);
            values.put(def.key(), v == null ? "" : v);
        }
        return values;
    }

    /**
     * 对比旧值快照与保存后的新值，记录变更历史。
     * <p>status 单独走 status_change 类型；其余字段走 field_update。
     * 进入 stopped/repair 状态时若 remark 为空，change_reason 记为"（未填写原因）"以便审计追溯。
     */
    private void recordChanges(PackagingMold saved, Map<String, String> oldValues,
                                String oldStatus, String newStatus,
                                String remark, String source) {
        List<PackagingMoldHistory> histories = new ArrayList<>();
        // 1. 状态变更
        if (!Objects.equals(oldStatus, newStatus)) {
            PackagingMoldHistory h = new PackagingMoldHistory();
            h.setMold(saved);
            h.setFieldKey("status");
            h.setFieldLabel("状态");
            h.setOldValue(oldStatus);
            h.setNewValue(newStatus);
            h.setChangeType("status_change");
            h.setChangeSource(source);
            if (newStatus != null && REMARK_SUGGESTED_TARGETS.contains(newStatus)
                    && (remark == null || remark.isBlank())) {
                h.setChangeReason("（未填写原因）");
            } else {
                h.setChangeReason(remark);
            }
            histories.add(h);
        }
        // 2. 关键字段变更
        for (FieldDiffDef def : TRACKED_FIELDS) {
            String oldVal = oldValues.getOrDefault(def.key(), "");
            String newVal = def.getter().apply(saved);
            String normalizedNew = newVal == null ? "" : newVal;
            if (!Objects.equals(oldVal, normalizedNew)) {
                PackagingMoldHistory h = new PackagingMoldHistory();
                h.setMold(saved);
                h.setFieldKey(def.key());
                h.setFieldLabel(def.label());
                h.setOldValue(oldVal.isEmpty() ? null : oldVal);
                h.setNewValue(normalizedNew.isEmpty() ? null : normalizedNew);
                h.setChangeType("field_update");
                h.setChangeSource(source);
                histories.add(h);
            }
        }
        if (!histories.isEmpty()) {
            historyRepo.saveAll(histories);
        }
    }
}
