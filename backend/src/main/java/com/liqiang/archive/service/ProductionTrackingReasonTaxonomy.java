package com.liqiang.archive.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 逾期 / 延期原因的**归类词典**。
 *
 * <p>【为什么需要它】跟单员登记的原因是自由文本（{@code production_tracking_state.delay_reason}、
 * {@code production_tracking_time_reviews.reason}），没有字典表。苏平的周复盘表里已经把原文手工归纳成
 * 「组装 / 入库 / 工期 / 业务员 / 配件 / 模具」六类 —— 同一件事在库里是「没时间装」「档期排满」
 * 「组装人手不足，请假未补人」三种写法。要让看板自动出原因分布，就必须有这一层「原文 → 类别」的映射。
 *
 * <p>【匹配顺序＝精确度优先】{@link #CATEGORIES} 的排列顺序就是优先级，靠前的先命中。
 * 这条顺序是拿真样本校准过的，改动前先看下面这几个反例：
 * <ul>
 *   <li>「配件外采时间不够」必须命中配件原因，不能落到工期原因 —— 故配件在工期之前；</li>
 *   <li>「工期计算问题」是业务员原因（填单时算错），「工期设置偏短」才是工期原因 —— 故业务员在工期之前；</li>
 *   <li>「入库时间不够」要归入库原因，不能落到工期原因 —— 故入库在工期之前；</li>
 *   <li>「组装人手不足」不含入库字样，自然落到组装原因。</li>
 * </ul>
 *
 * <p>【边界】这里只做归类，不改写原文、不写库：原文榜仍由 {@link ProductionTrackingAnalyticsService}
 * 全量返回（那是归类的输入，也是后续扩充词典的样本来源），本类只在展示层把它并成类别。
 */
public final class ProductionTrackingReasonTaxonomy {
    /** 原因分析里空值的统一写法，与 {@link ProductionTrackingAnalyticsService#firstText} 保持一致。 */
    private static final String UNRECORDED_TEXT="未记录";
    public static final String UNRECORDED="UNRECORDED";
    public static final String OTHER="OTHER";

    private record Category(String key, String label, List<String> keywords) {}

    /** 顺序＝匹配优先级，见类注释；新增类别务必插到比它更具体的类别之后。 */
    private static final List<Category> CATEGORIES = List.of(
            new Category("MOLD", "模具原因", List.of("模具")),
            new Category("PARTS", "配件原因", List.of("配件", "钣金", "外购", "外采", "缺料", "物料", "材料")),
            new Category("SALES", "业务员原因", List.of("业务员", "业务原因", "送单", "订单不确定", "订单创建",
                    "工期计算", "客户改单", "客户变更")),
            new Category("INBOUND", "入库原因", List.of("入库", "入仓", "收货")),
            new Category("ASSEMBLY", "组装原因", List.of("组装", "装配", "没时间装", "装不完", "装不了", "档期",
                    "外加工", "人手不足", "缺人", "请假", "输送带", "产能")),
            new Category("LEAD_TIME", "工期原因", List.of("工期", "交期", "时间来不及", "时间不够", "周期",
                    "天数不够", "休息日", "节假日")));

    /**
     * 展示顺序＝周复盘表里的列举顺序（组装 → 入库 → 工期 → 业务员 → 配件 → 模具），
     * 与上面的匹配优先级是两件事：匹配要「更具体的先命中」，展示要「主因在前」。
     */
    private static final List<String> DISPLAY_ORDER = List.of("ASSEMBLY", "INBOUND", "LEAD_TIME", "SALES",
            "PARTS", "MOLD");

    private ProductionTrackingReasonTaxonomy() {}

    /** 原文 → 类别 key；空、空白或「未记录」一律归 {@link #UNRECORDED}。 */
    public static String classify(String reason) {
        if (reason == null || reason.isBlank() || UNRECORDED_TEXT.equals(reason.trim())) return UNRECORDED;
        var text = reason.trim();
        for (var category : CATEGORIES)
            for (var keyword : category.keywords())
                if (text.contains(keyword)) return category.key();
        return OTHER;
    }

    public static String labelOf(String key) {
        for (var category : CATEGORIES) if (category.key().equals(key)) return category.label();
        if (UNRECORDED.equals(key)) return "原因未记录";
        if (OTHER.equals(key)) return "其他原因";
        return key;
    }

    /** 名单用途（下钻参数白名单）。 */
    public static java.util.Set<String> keys() {
        var keys = new java.util.LinkedHashSet<String>(CATEGORIES.stream().map(Category::key).toList());
        keys.add(UNRECORDED);
        keys.add(OTHER);
        return java.util.Collections.unmodifiableSet(keys);
    }

    /**
     * 把「原文 → 单数」榜并成「类别 → 单数」榜，并按单数降序（同数按词典顺序稳定排）。
     *
     * @param rateOf 占比的分母＝传入榜的单数之和（即逾期总数 / 延期总数），
     *               与周复盘表「合计＝各原因之和」的写法一致；分母为 0 时占比返回 null。
     */
    public static List<Map<String, Object>> aggregate(List<Map<String, Object>> rawReasons) {
        var counts = new LinkedHashMap<String, Integer>();
        var samples = new LinkedHashMap<String, List<Map<String, Object>>>();
        long total = 0;
        for (var entry : rawReasons) {
            var name = String.valueOf(entry.get("name"));
            var count = ((Number) entry.get("count")).intValue();
            var key = classify(name);
            counts.merge(key, count, Integer::sum);
            samples.computeIfAbsent(key, unused -> new ArrayList<>()).add(entry);
            total += count;
        }
        // 占比的分母在下面的 lambda 里要用，故先定影 —— 否则累加着的 total 进不了 lambda
        final long totalOrders = total;
        var result = new ArrayList<Map<String, Object>>();
        counts.forEach((key, count) -> {
            var item = new LinkedHashMap<String, Object>();
            item.put("key", key);
            item.put("label", labelOf(key));
            item.put("count", count);
            item.put("rate", totalOrders == 0 ? null : Math.round(count * 1000.0 / totalOrders) / 10.0);
            var tops = samples.get(key).stream()
                    .sorted(Comparator.<Map<String, Object>, Integer>comparing(
                            row -> ((Number) row.get("count")).intValue()).reversed())
                    .limit(3).map(row -> String.valueOf(row.get("name"))).toList();
            item.put("samples", tops);
            result.add(item);
        });
        // 先按单数降序，同数时按词典顺序 —— 让「组装 / 入库 / 工期 …」的排列稳定，不随原文写法跳动
        final Comparator<Map<String, Object>> byCount =
                Comparator.comparing(row -> ((Number) row.get("count")).intValue());
        result.sort(byCount.reversed().thenComparingInt(row -> orderOf(String.valueOf(row.get("key")))));
        return result;
    }

    /** 单数相同时按展示顺序排，保证「组装 / 入库 / 工期 …」的位置稳定，不随原文写法跳动。 */
    private static int orderOf(String key) {
        int index = DISPLAY_ORDER.indexOf(key);
        if (index >= 0) return index;
        return OTHER.equals(key) ? DISPLAY_ORDER.size() : DISPLAY_ORDER.size() + 1;
    }
}
