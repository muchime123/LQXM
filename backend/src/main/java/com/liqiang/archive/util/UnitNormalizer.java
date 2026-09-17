package com.liqiang.archive.util;

import java.util.Locale;
import java.util.Map;

/**
 * 单位归一的唯一口径（库存台账导入 / 合并入库 / 出库关联一律走这里，勿各自实现）。
 *
 * <p>规则：去空白（含全角空格）→ 去尾部「·」噪声 → 别名映射到规范写法。
 * 同一产品的单位变体（{@code Kg} / {@code KG} / {@code 公斤} / {@code 千克}）必须归一到
 * {@code kg}，否则「仓库+编号+单位」业务键会把同一件东西拆成多行（V295 唯一键同理）。
 *
 * <p>为何不写死一张大表：台账真实取值是「台 7138 / kg 4068 / 个 / 箱 / 套 / 只 / 卷 / 片 / 件 /
 * 张 / 块 / 公斤 / 把 / 瓶 / 色 / 根 / 平方 / 条 / 易 撕(带空格)」——绝大多数本来就规范，
 * 需要处理的只有大小写、空格与个别别名，所以用「通用清理 + 少量别名」而不是穷举。
 */
public final class UnitNormalizer {

    private UnitNormalizer() {}

    /** 别名 → 规范写法（key 必须是 normalize 之后的形态：小写、无空白）。 */
    private static final Map<String, String> ALIASES = Map.ofEntries(
            Map.entry("公斤", "kg"),
            Map.entry("千克", "kg"),
            Map.entry("kgs", "kg"),
            Map.entry("kg/箱", "kg"),
            Map.entry("平方米", "平方"),
            Map.entry("平米", "平方"),
            Map.entry("m2", "平方"),
            Map.entry("㎡", "平方"),
            Map.entry("pcs", "个"),
            Map.entry("只装", "只"));

    /** 归一单位；null/空白 → null。 */
    public static String normalize(String unit) {
        if (unit == null) return null;
        String v = unit.replace(" ", "").replace("\u3000", "").replace("\u00a0", "").trim();
        // 氚云导出里偶有「箱··」「台·」这类尾随噪声
        int end = v.length();
        while (end > 0 && isNoise(v.charAt(end - 1))) end--;
        v = v.substring(0, end);
        if (v.isEmpty()) return null;
        String mapped = ALIASES.get(v.toLowerCase(Locale.ROOT));
        if (mapped != null) return mapped;
        // 纯 ASCII 写法统一小写：Kg / KG / kg 必须同键，否则「仓库+编号+单位」会把同物拆成多行
        boolean ascii = true;
        for (int i = 0; i < v.length(); i++) {
            if (v.charAt(i) > 127) { ascii = false; break; }
        }
        return ascii ? v.toLowerCase(Locale.ROOT) : v;
    }

    /** 业务键用：null → ""，其余同 {@link #normalize(String)}。 */
    public static String key(String unit) {
        String n = normalize(unit);
        return n == null ? "" : n;
    }

    private static boolean isNoise(char c) {
        return c == '·' || c == '•' || c == '.' || c == '。' || c == '*' || c == '-';
    }
}
