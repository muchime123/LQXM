package com.liqiang.archive.util;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 供应商名称归一化（2026-09-17 P0，见 docs/氚云新增7表ERP适用性梳理与导入设计-20260917.md §2.2）。
 *
 * <p>供应商没有氚云编号，采购入库等单据引用的是「名称 + 空格 + 状态」的数据标题文本
 * （如 {@code 封口机车间    合作中}）。状态一旦从「合作中」变「合作终止」，引用文本与
 * 主表名称就对不上——所以匹配一律走「剥状态后缀 → 归一化」，并把原文落别名表自愈。
 *
 * <p>归一化口径：剥已知状态后缀 → 移除全部空白（含全角空格/不间断空格）→ 小写。
 * 该结果写入 {@code h3_supplier_catalog.normalized_name}（唯一索引）作为自愈匹配键。
 */
public final class SupplierNameNormalizer {

    private SupplierNameNormalizer() {}

    /** 数据标题尾缀：≥2 个空白 + 合作中/合作终止（Hidden_Options_1 枚举）。 */
    private static final Pattern STATUS_SUFFIX =
            Pattern.compile("^(.*?)[\\s\\u00A0\\u3000]{2,}(合作中|合作终止)\\s*$");

    /** 内部车间/产线误入供应商主表的值（与生产工单「产线选择」枚举互证，实测 4 条）。 */
    private static final Set<String> INTERNAL_NAMES =
            Set.of("称重机车间", "封口机车间", "半自动气调机", "全自动气调机");

    /** 串名脏值：单据类型/表单名串入供应商选择列（实测 2 条），导入时标记而非静默接受。 */
    private static final Set<String> DIRTY_NAMES = Set.of("其他入库", "温州市中坚薄膜销售出库单");

    /** 剥「名称 + 状态」尾缀；无尾缀时返回原.trim()。 */
    public static String stripStatusSuffix(String raw) {
        if (raw == null || raw.isBlank()) return raw;
        Matcher m = STATUS_SUFFIX.matcher(raw.trim());
        if (m.matches()) {
            String name = m.group(1).trim();
            return name.isEmpty() ? raw.trim() : name;
        }
        return raw.trim();
    }

    /** 归一化：剥状态后缀 → 移除全部空白 → 小写。结果作为唯一匹配键。 */
    public static String normalize(String raw) {
        if (raw == null || raw.isBlank()) return "";
        String stripped = stripStatusSuffix(raw);
        return stripped.replaceAll("[\\s\\u00A0\\u3000]+", "").toLowerCase(Locale.ROOT);
    }

    /** 是否内部车间/产线误入值。 */
    public static boolean isInternal(String normalizedName) {
        if (normalizedName == null || normalizedName.isBlank()) return false;
        return INTERNAL_NAMES.contains(normalizedName) || normalizedName.endsWith("车间");
    }

    /** 是否串名脏值。 */
    public static boolean isDirty(String normalizedName) {
        return normalizedName != null && DIRTY_NAMES.contains(normalizedName);
    }
}
