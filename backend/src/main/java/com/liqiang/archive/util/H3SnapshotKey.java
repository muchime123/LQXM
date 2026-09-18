package com.liqiang.archive.util;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 氚云源快照「判等键」的唯一口径 —— {@code source_hash} 的取值依据就来自这里。
 *
 * <p><b>为什么需要这个类</b>：氚云同一份数据在「网页版导出」与「客户端导出」之间会因为渲染方式不同
 * 产生表面差异，而 {@code unchanged()} 只拿 {@code source_hash} 判等。差异一旦被算进哈希，
 * 每次重导都会把全部行判成 UPDATE（幂等永远不收敛），而数据其实一字未变。
 *
 * <p>已实测确认的漂移有四种，本类负责后两种（前两种在别处已解决）：
 * <table border="1">
 *   <caption>漂移类型与责任归属</caption>
 *   <tr><th>漂移</th><th>例子</th><th>解决位置</th></tr>
 *   <tr><td>列顺序</td><td>「备注」在第 17 列 vs 第 24 列</td><td>{@code hashGroup} 用 TreeMap 排序（已免疫）</td></tr>
 *   <tr><td>空列增删</td><td>导出多带几个全空字段列</td><td>解析层只放非空单元格（已免疫）</td></tr>
 *   <tr><td>表头文本</td><td>{@code 客   户    供应商} vs {@code 客户供应商}</td><td><b>本类 {@link #canonicalKey}</b></td></tr>
 *   <tr><td>值渲染</td><td>{@code 1234.50} vs {@code 1234.5}；{@code 2026/8/31} vs {@code 2026-08-31}</td><td><b>本类 {@link #canonicalValue}</b></td></tr>
 * </table>
 *
 * <p><b>不做什么</b>：本类只改「判等口径」，<b>不改落库内容</b>。{@code h3_import_rows.raw_data}
 * 仍保存原始快照原文（审计需要），解析与写入路径一律不受影响。因此改本类不会改写任何业务字段，
 * 最坏结果只是某一轮把「其实没变」的行报成 UPDATE 并写回同值，下一轮即收敛——
 * 前提是 {@code UNCHANGED} 分支会 {@code touch()} 回写新的 {@code source_hash}（见 confirm 主循环）。
 *
 * <p><b>边界（刻意不归一，宁可多报 UPDATE 也不冒「假 UNCHANGED」的风险）</b>：
 * ① 值的内部空白不折叠（{@code 张  三} ≠ {@code 张三}）；
 * ② 五位整数不做 Excel 日期序列号推断（{@code 45900} 可能是数量）；
 * ③ 表头不剥 {@code (必填)} / {@code (数字)} 后缀——那是跨 kind 的差异，同类内稳定，
 * 且同名重复列在 {@code putHeader} 阶段已被丢弃，在此剥离反而可能制造新的键碰撞；
 * ④ 子表行序保持敏感（行序本身是业务事实）。
 */
public final class H3SnapshotKey {

    private H3SnapshotKey() {}

    /** 氚云导出里表示「空」的占位符，与解析层 {@code !"-".equals(value)} 同口径。 */
    private static final String BLANK_PLACEHOLDER = "-";

    /**
     * 数字形态（{@code matches()} 全匹配）：{@code 1234} / {@code 1,234} / {@code 1,234.50} /
     * {@code 1234.5} / {@code .5} / {@code 1e3}。全角逗号与全角数字已由 NFKC 折成半角。
     */
    private static final Pattern NUMBER = Pattern.compile(
            "[+-]?(?:(?:\\d{1,3}(?:,\\d{3})+|\\d+)(?:\\.\\d+)?|\\.\\d+)(?:[eE][+-]?\\d+)?");

    /**
     * 日期形态（{@code matches()} 全匹配）：{@code 2026-08-31} / {@code 2026/8/31} / {@code 2026.8.31} /
     * {@code 2026年8月31日}，时间部分可有可无：{@code 10:30} / {@code 10:30:00} / {@code 10:30:00.0}。
     * 秒的小数位是必需的——Java {@code Timestamp} 直接 {@code toString()} 会带上 {@code .0}。
     */
    private static final Pattern DATE = Pattern.compile(
            "(\\d{4})[-/.年](\\d{1,2})[-/.月](\\d{1,2})日?"
                    + "(?:[ T](\\d{1,2}):(\\d{2})(?::(\\d{2})(?:\\.\\d+)?)?)?");

    /**
     * 把一行的原始快照（表头 → 单元格文本）折算成判等键。
     *
     * <p>返回 {@link TreeMap}（键有序）且已丢弃空值项，因此列顺序变化、空列增删
     * 都不会改变序列化结果，也就不会改变 {@code source_hash}。
     *
     * @param raw 原始行快照，可为 null
     * @return 归一后的判等键映射，永不为 null
     */
    public static Map<String, String> canonicalize(Map<String, String> raw) {
        Map<String, String> out = new TreeMap<>();
        if (raw == null || raw.isEmpty()) return out;
        // 先按原始键排序再落桶：若两个原始键归一到同一键（「报工人」与「报 工人」），
        // 结果不随导出列顺序而变，保证同一份数据在任何列序下产出同一 key。
        List<String> rawKeys = new ArrayList<>(raw.keySet());
        rawKeys.sort(Comparator.nullsFirst(Comparator.naturalOrder()));
        for (String rawKey : rawKeys) {
            String key = canonicalKey(rawKey);
            if (key == null) continue;
            String value = canonicalValue(raw.get(rawKey));
            if (value == null) continue;
            // 归一后撞键时保留排序靠前者，结果确定，不依赖 Map 迭代顺序。
            out.putIfAbsent(key, value);
        }
        return out;
    }

    /**
     * 表头归一：NFKC（全角→半角、兼容字→标准字）后<b>删除全部空白</b>。
     *
     * <p>删空白而非折叠，是为了与既有的一处补丁同口径：流水日记账源表头
     * 「客   户    供应商」夹带不规则空白，必须归到「客户供应商」才能与代码读取的键对上。
     *
     * @return 归一后的表头；原始为 null/空白 → null
     */
    public static String canonicalKey(String header) {
        if (header == null) return null;
        String v = Normalizer.normalize(header, Normalizer.Form.NFKC)
                .replaceAll("[\\s\\u00a0\\u3000]+", "");
        return v.isEmpty() ? null : v;
    }

    /**
     * 单元格值归一：去首尾空白（含 NBSP / 全角空格，NFKC 已折为半角）→ 统一换行符 →
     * 数字转规范十进制 → 日期转 {@code yyyy-MM-dd} 或 {@code yyyy-MM-ddTHH:mm:ss}。
     *
     * <p>数字归一解决 {@code 1234.50} / {@code 1234.5} / {@code 1,234.5} 互相不等的问题；
     * 日期归一解决 {@code 2026/8/31} 与 {@code 2026-08-31} 互相不等的问题。
     * 零点（{@code 00:00} / {@code 00:00:00}）折叠为纯日期——氚云纯日期字段在部分导出里
     * 会带上无意义的零点时间戳。
     *
     * @return 归一后的值；null / 空白 / {@code "-"} → null（调用方据此丢弃该键）
     */
    public static String canonicalValue(String value) {
        if (value == null) return null;
        String v = Normalizer.normalize(value, Normalizer.Form.NFKC)
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .trim();
        if (v.isEmpty() || BLANK_PLACEHOLDER.equals(v)) return null;
        String number = canonicalNumber(v);
        if (number != null) return number;
        String date = canonicalDateTime(v);
        if (date != null) return date;
        return v;
    }

    /** 数字 → {@code BigDecimal.stripTrailingZeros().toPlainString()}；非数字形态 → null。 */
    public static String canonicalNumber(String value) {
        if (value == null || !NUMBER.matcher(value).matches()) return null;
        try {
            // toPlainString 避免 1000 被写成 1E+3
            return new BigDecimal(value.replace(",", "")).stripTrailingZeros().toPlainString();
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 日期/日期时间 → 规范形态；非日期形态 → null。 */
    public static String canonicalDateTime(String value) {
        if (value == null) return null;
        Matcher m = DATE.matcher(value);
        if (!m.matches()) return null;
        int year = Integer.parseInt(m.group(1));
        int month = Integer.parseInt(m.group(2));
        int day = Integer.parseInt(m.group(3));
        if (month < 1 || month > 12 || day < 1 || day > 31) return null;
        String date = "%04d-%02d-%02d".formatted(year, month, day);
        if (m.group(4) == null) return date;
        int hour = Integer.parseInt(m.group(4));
        int minute = Integer.parseInt(m.group(5));
        String second = m.group(6);
        // 零点折叠为纯日期：纯日期字段在部分导出里会带 00:00(:00) 时间戳
        if (hour == 0 && minute == 0 && (second == null || "00".equals(second))) return date;
        // 秒缺失补 00：部分导出对整分时间会省掉秒，同一时刻不应因此判为变化
        return "%sT%02d:%02d:%s".formatted(date, hour, minute, second == null ? "00" : second);
    }
}
