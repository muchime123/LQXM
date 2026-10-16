package com.liqiang.archive.util;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 「任务单产品配置 → SKU 身份」的唯一口径来源（2026-09-23 LQ 拍板，见
 * {@code docs/SKU重设计-任务单配置锚定身份-20260923.md}）。
 *
 * <p>三个键的分工（混用就是氚云的病根 —— 把人手拼的显示名当成了唯一身份）：
 * <ul>
 *   <li><b>显示名</b> {@code displayName}：给人看，允许重复。⚠️ <b>09-28 LQ 拍板：显示名的唯一生成器是配置规则引擎
 *       （{@code ProductConfigurationNaming.namePreview}，形态 {@code 气动卷膜｜方盒200×138mm｜一出四｜220V}），
 *       不是本类</b> —— 本类的 {@link #derive} 只负责派生 {@code skuKey}，其显示名仅供存量对拍，
 *       <b>新链路不得用库存语法拼显示名</b>（决策 {@code #36}：库存名无统一标准，跟着它会一直乱）。</li>
 *   <li><b>身份键</b> {@code skuKey}：给系统认（库存聚合的唯一依据），唯一索引；</li>
 *   <li><b>订单产出标识</b> {@code MFG-{工单号}}：溯源用，由调用方拼，不在本类职责内。</li>
 * </ul>
 *
 * <p><b>为什么这些字段不进身份键</b>（全部有实测依据，勿凭直觉改）：
 * <ul>
 *   <li>产线：2715 个（品类,尺寸,一出N）组里仅 2 组跨产线，品类名已隐含产线；
 *       进键会把同款货拆成两条产线各一份。</li>
 *   <li>形状/材质/可选配置：只把组数从 2717 提到 3709，<b>救不了退化组</b>（最大组仍是 374 张
 *       全空配置的模具单）；进键会因「填了/没填」把同一件货裂开。</li>
 *   <li>电压/插头/品牌/盒子高度：16,319 条库存名里分别只出现 20/6/1/0 次 —— 业务从不靠它们区分。</li>
 * </ul>
 *
 * <p><b>规范化铁律：只统一字形，不还原语义。</b>库存名里真实写着 {@code 2014气动一出四卷膜}、
 * {@code 1812按钮全自动卷膜} —— {@code 2014}/{@code 1812} 是业务人脑压掉乘号的写法（20x14/18x12），
 * <b>不是脏数据</b>。任何「还原成 20x14」「取整小数」的聪明规则都会把新身份键与历史库存名脱钩
 * （实测含小数尺寸的字面命中率本就只有 47.5%，再动语义会更低）。
 *
 * <p>⚠️ <b>与 09-28 决策的边界</b>：新单的尺寸来自规则引擎的结构化录入（形态 {@code 200x138}），
 * 历史自由文本的压号写法（{@code 2014}）与它**不是同一个键** —— 这个「同款可能不聚合」的缺口
 * LQ 09-28 已明示**暂缓处理**（「后续可能会把 200x138 归为 2014 之类的，但是现在还没有设计关联」）。
 * 故本类仍**保持字形不还原**（存量对拍按原字形），归一留给后续映射设计，勿在此处擅自加还原规则。
 *
 * <p><b>非标分支</b>：模具(430 单)/配件(108 单)没有盒子尺寸（374 张模具单共用一个全空配置键），
 * 它们是按图非标工装，身份在图纸/工单上不在配置上 → {@link #derive} 对它们返回
 * {@code skuKey=null}（调用方落 {@code MFG-{工单号}}），显示名只用品类。
 *
 * <p><b>一出N 双语源</b>：氚云台账用中文数字（一出四 1385 条），ERP 建单 JSON 用阿拉伯数字
 * （{@code 1出4}）—— 两个词源必须归一，且<b>只能在这里归一</b>（同一源字段禁自造第二套词表）。
 * 规范形态取台账写法「一出四」，与 99.7% 与库存精确同名的出库侧对齐。
 */
public final class ProductIdentityKeys {

    /** 非标工装品类：没有盒子尺寸，身份走 MFG-{工单号}（U15-Q2：模具也是商品，照样进库存）。 */
    private static final Set<String> NON_STANDARD_CATEGORIES = Set.of("模具", "配件");

    /** 身份键分段分隔符（可读串形态，S2 拍板：出问题能在日志/库存页直接定位）。 */
    public static final String KEY_SEPARATOR = "|";

    /** 订做标记（S1 拍板：保留进显示名，且统一写法为「订做」—— 存量「定做」1717 条并存）。 */
    public static final String CUSTOMIZED_PREFIX = "订做";

    private ProductIdentityKeys() {
    }

    /** 派生结果。{@code skuKey} 为 null 表示非标件，调用方必须落 {@code MFG-{工单号}}。 */
    public record Identity(String displayName, String skuKey, boolean nonStandard) {
    }

    /**
     * 由任务单产品配置派生身份。
     *
     * <p>⚠️ 09-28 起：**调用方只取 {@link Identity#skuKey()}**（显示名走规则引擎，见类注释）。
     * {@link Identity#displayName()} 仅保留给存量对拍脚本，不进入新链路的任何落库列。
     *
     * @param category        制程品类（49 项配置的取值，如「气动卷膜」「LQ310T台式黑白机」）；空 → 非标
     * @param boxSize         盒子尺寸原文（如 {@code 200*138} / {@code 70mm} / {@code 2014} / {@code 203.9x146}）
     * @param moldLayout      模具排列原文（{@code 一出四} 或 {@code 1出4}）
     * @param optionalConfigs 可选配置逐项（原样保留措辞，仅去空白、去重、跳过空项）
     * @param customized      是否订做
     */
    public static Identity derive(String category, String boxSize, String moldLayout,
                                  List<String> optionalConfigs, boolean customized) {
        String rawCat = sqlTrim(category);
        String catKey = normalizeCategory(category);
        String size = normalizeSize(boxSize);
        String layout = normalizeMoldLayout(moldLayout);

        boolean nonStandard = rawCat.isEmpty() || NON_STANDARD_CATEGORIES.contains(rawCat)
                || size.isEmpty() || layout.isEmpty();
        if (nonStandard) {
            return new Identity(rawCat, null, true);
        }

        String opts = joinOptions(optionalConfigs);
        String skuKey = String.join(KEY_SEPARATOR, catKey, size, layout);
        String displayName = buildDisplayName(rawCat, size, layout, opts, customized);
        return new Identity(displayName, skuKey, false);
    }

    /**
     * 尺寸字形规范化（<b>禁还原语义</b>，见类注释）：MySQL TRIM 语义去首尾半角空格 →
     * 删除内部空白（半角/全角/nbsp）→ 去 {@code mm} → {@code *}/{@code ×}/{@code X} → {@code x} →
     * 全角括号转半角。保留小数、保留 {@code 2014} 这类压掉乘号的写法。
     */
    public static String normalizeSize(String raw) {
        String v = sqlTrim(raw);
        if (v.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(v.length());
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (c == ' ' || c == '\u3000' || c == '\u00a0' || c == '\t') {
                continue;
            }
            if (c == '*' || c == '\u00d7') {
                sb.append('x');
            } else if (c == '\uff08') {
                sb.append('(');
            } else if (c == '\uff09') {
                sb.append(')');
            } else if (c == '\u03c6' || c == '\u03a6') { // φ 与 Φ 都归一为大写 Φ（Φ 若落 else 会被小写化）
                sb.append('\u03a6');
            } else if ((c == ',' || c == '\uff0c') && sb.length() > 0
                    && Character.isDigit(sb.charAt(sb.length() - 1))) {
                // 数字后的逗号是分隔符噪声（真库形态：46,mm / φ90，φ80 / 2014x2,光板透明）
                continue;
            } else {
                sb.append(Character.toLowerCase(c));
            }
        }
        // 去 mm：凡紧跟在数字后的「mm」都剥掉（真库形态：70mm / 86mm75mm / 112x112mm84mm）。
        // 只要求前一位是数字，不要求后一位非数字 —— 多段尺寸每段都带单位（86mm75mm = 86x75 压掉乘号）。
        String out = sb.toString();
        while (true) {
            int idx = out.indexOf("mm");
            if (idx < 0) {
                break;
            }
            if (idx > 0 && Character.isDigit(out.charAt(idx - 1))) {
                out = out.substring(0, idx) + out.substring(idx + 2);
            } else {
                break; // 不满足「数字+单位」形态则不再处理，避免无限循环
            }
        }
        return out;
    }

    /**
     * 模具排列归一为中文数字形态。接受 {@code 一出四}（台账）/ {@code 1出4}（ERP JSON）/
     * {@code 2出1}（ERP 真实取值，MO00008768）/ {@code 一出104}（台账异常值 1 条）——
     * <b>两侧都按数字解析</b>（头部不是恒为 1），解析不出时降级为去空白小写原文。
     */
    public static String normalizeMoldLayout(String raw) {
        String v = sqlTrim(raw).replace(" ", "").replace("\u3000", "");
        if (v.isEmpty()) {
            return "";
        }
        int idx = v.indexOf('出');
        if (idx < 0) {
            return v.toLowerCase(Locale.ROOT);
        }
        Integer head = parseCount(v.substring(0, idx));
        Integer tail = parseCount(v.substring(idx + 1));
        if (head == null || tail == null || head <= 0 || tail <= 0) {
            return v.toLowerCase(Locale.ROOT);
        }
        return toChinese(head) + "出" + toChinese(tail);
    }

    /** 品类归一：去首尾/内部空白 + 小写折叠（值域来自 49 项配置下拉，归一只防手抖）。 */
    public static String normalizeCategory(String raw) {
        String v = sqlTrim(raw);
        if (v.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(v.length());
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (c == ' ' || c == '\u3000' || c == '\u00a0' || c == '\t') {
                continue;
            }
            sb.append(Character.toLowerCase(c));
        }
        return sb.toString();
    }

    /** 显示名：{@code [订做]品类-尺寸一出N[+配置…]}；LQ 整机仿库存语法拆出机型号前缀。 */
    private static String buildDisplayName(String cat, String size, String layout, String opts, boolean customized) {
        StringBuilder sb = new StringBuilder();
        if (customized) {
            sb.append(CUSTOMIZED_PREFIX);
        }
        // LQ 整机：库存名语法是 型号-尺寸一出N-型别词（如 LQ310T-230x165一出一台式全自动）
        String model = extractMachineModel(cat);
        if (model != null) {
            String rest = cat.substring(model.length());
            sb.append(model).append('-').append(size).append(layout).append(rest);
        } else {
            sb.append(cat).append('-').append(size).append(layout);
        }
        if (!opts.isEmpty()) {
            sb.append('+').append(opts);
        }
        return sb.toString();
    }

    /** 从品类名前缀提取机型号（{@code LQ310T台式黑白机} → {@code LQ310T}）；非 LQ 开头返回 null。 */
    private static String extractMachineModel(String cat) {
        if (cat.length() < 3
                || (cat.charAt(0) != 'L' && cat.charAt(0) != 'l')
                || (cat.charAt(1) != 'Q' && cat.charAt(1) != 'q')) {
            return null;
        }
        int i = 2;
        while (i < cat.length() && Character.isDigit(cat.charAt(i))) {
            i++;
        }
        if (i == 2) {
            return null;
        }
        if (i < cat.length() && Character.isLetter(cat.charAt(i))) {
            i++; // LQ310T / LQ750D 的尾缀字母
        }
        return cat.substring(0, i);
    }

    private static String joinOptions(List<String> optionalConfigs) {
        if (optionalConfigs == null || optionalConfigs.isEmpty()) {
            return "";
        }
        Set<String> seen = new LinkedHashSet<>();
        for (String raw : optionalConfigs) {
            String v = sqlTrim(raw);
            if (!v.isEmpty()) {
                seen.add(v);
            }
        }
        return String.join("+", seen);
    }

    /** 中文/阿拉伯数字解析（一出N 两个词源的共同入口）。 */
    private static Integer parseCount(String s) {
        if (s.isEmpty()) {
            return null;
        }
        // 纯阿拉伯数字
        if (s.chars().allMatch(c -> c >= '0' && c <= '9')) {
            try {
                return Integer.parseInt(s);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return parseChinese(s);
    }

    /** 中文数字 → int（支持 1..9999，含「十/一百零四/一百二十」形态）。 */
    private static Integer parseChinese(String s) {
        // 便于查表的下标顺序：零一二三四五六七八九
        String digits = "零一二三四五六七八九";
        if (s.equals("十")) {
            return 10;
        }
        int hundred = s.indexOf("百");
        int ten = s.indexOf("十");
        if (hundred < 0 && ten < 0) {
            // 一位数
            if (s.length() != 1) {
                return null;
            }
            int d = digits.indexOf(s.charAt(0));
            return d <= 0 ? null : d;
        }
        if (hundred >= 0) {
            Integer h = hundred == 0 ? 1 : singleDigit(s.substring(0, hundred), digits);
            if (h == null) {
                return null;
            }
            int result = h * 100;
            String rest = s.substring(hundred + 1);
            if (rest.isEmpty()) {
                return result;
            }
            Integer tail = parseChinese(rest);
            return tail == null ? null : result + tail;
        }
        // 十位段
        Integer t = ten == 0 ? 1 : singleDigit(s.substring(0, ten), digits);
        if (t == null) {
            return null;
        }
        int result = t * 10;
        String rest = s.substring(ten + 1);
        if (rest.isEmpty()) {
            return result;
        }
        Integer u = singleDigit(rest, digits);
        return u == null ? null : result + u;
    }

    private static Integer singleDigit(String s, String digits) {
        if (s.length() != 1) {
            return null;
        }
        int d = digits.indexOf(s.charAt(0));
        return d <= 0 ? null : d;
    }

    /** int → 中文数字（1..9999），与 parseChinese 互逆；出界返回阿拉伯原文。 */
    private static String toChinese(int n) {
        if (n <= 0 || n > 9999) {
            return String.valueOf(n);
        }
        String digits = "零一二三四五六七八九";
        List<String> parts = new ArrayList<>();
        int h = n / 100;
        int t = (n % 100) / 10;
        int u = n % 10;
        if (h > 0) {
            parts.add(String.valueOf(digits.charAt(h)));
            parts.add("百");
            if (t == 0 && u > 0) {
                parts.add("零");
            }
        }
        if (t > 0) {
            if (t > 1 || h > 0) {
                parts.add(String.valueOf(digits.charAt(t)));
            }
            parts.add("十");
        }
        if (u > 0) {
            parts.add(String.valueOf(digits.charAt(u)));
        }
        return String.join("", parts);
    }

    /** 与 MySQL {@code TRIM()} 严格对齐：只剥离首尾半角空格 U+0020（与 ProductSourceKey.sqlTrim 同源）。 */
    private static String sqlTrim(String value) {
        if (value == null) {
            return "";
        }
        int start = 0;
        int end = value.length();
        while (start < end && value.charAt(start) == ' ') {
            start++;
        }
        while (end > start && value.charAt(end - 1) == ' ') {
            end--;
        }
        return value.substring(start, end);
    }
}
