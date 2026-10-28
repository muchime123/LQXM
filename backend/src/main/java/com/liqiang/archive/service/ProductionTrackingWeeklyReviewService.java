package com.liqiang.archive.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 跟单周复盘：把交期达成分析的同一批数字，按「周报的写法」重新组织成一个看板响应。
 *
 * <p>【铁律：不新造口径】所有数字都来自 {@link ProductionTrackingAnalyticsService#analyze}，
 * 本服务只做两件事 —— ① 换一个适合周报的组织方式；② 把原因原文并成类别
 * （见 {@link ProductionTrackingReasonTaxonomy}）。
 * 这样周复盘看板与「生产交期达成分析」抽屉、与列表的交付口径永远一致，不会出现
 * 「同一个区间两处数字不同」这类没有报错的漂移。
 *
 * <p>【区间即周期】周期＝传入的 [from, to] 闭区间，前端按自然周算出周一/周日；
 * 也支持 10.01-10.11 这类跨周的分段周期（月初分段、节假日合并都靠它）。
 * 区间作用在「当前有效车间预计完成日」上，与生产分析抽屉同一套口径。
 *
 * <p>⚠️ 本页卡片与下钻均为当前任务状态；交期调整或停产后历史周期数字会变化。
 * 已固化的历史快照留在归档回看，不用于可下钻的实时周复盘。
 */
@Service
@RequiredArgsConstructor
public class ProductionTrackingWeeklyReviewService {
    private final ProductionTrackingAnalyticsService analytics;

    public Map<String, Object> review(String fromDate, String toDate, String workshop) {
        // 下钻使用 analytics.orders 的实时行，卡片也必须实时取数；旧快照不能与当前明细混用。
        var result = analytics.analyzeUncut(fromDate, toDate, workshop);
        var totals = totals(result);
        var extensionReasons = ProductionTrackingReasonTaxonomy.aggregate(reasons(result, "extensionReasons"));
        var overdueReasons = ProductionTrackingReasonTaxonomy.aggregate(reasons(result, "overdueReasons"));

        var response = new LinkedHashMap<String, Object>();
        response.put("period", Map.of("from", orEmpty(fromDate), "to", orEmpty(toDate),
                "days", days(fromDate, toDate)));
        response.put("asOf", result.get("asOf"));
        response.put("summary", summary(totals, result));
        response.put("overdueTotal", sum(overdueReasons));
        response.put("extensionTotal", sum(extensionReasons));
        response.put("overdueLevels", levels(totals));
        response.put("overdueReasons", overdueReasons);
        response.put("extensionReasons", extensionReasons);
        // 车间下拉：直接取分析侧已算好的车间榜，不另发一条请求
        response.put("workshops", workshopNames(result));
        return response;
    }

    /**
     * 卡片：与周复盘表「本周数据汇总」一一对应。
     * 应完成＝按期＋逾期＋未完工（互斥闭合，由分析侧的 Bucket 保证）。
     */
    private Map<String, Object> summary(Map<String, Object> totals, Map<String, Object> result) {
        var summary = new LinkedHashMap<String, Object>();
        for (var key : List.of("dueTasks", "onTime", "overdue", "open", "onTimeAchievementRate",
                "completionRate", "extended", "extensionDays", "overdueDays", "averageOverdueDays",
                "openOverdue", "openOverdueDays", "cancelled", "stopped"))
            summary.put(key, totals.get(key));
        summary.put("missingExpected", result.get("missingExpected"));
        return summary;
    }

    /** 逾期天数分档：与周复盘表「轻微 1-3 天 / 中度 4-7 天 / 严重 8 天以上」一致。 */
    private List<Map<String, Object>> levels(Map<String, Object> totals) {
        var rows = new ArrayList<Map<String, Object>>();
        rows.add(level("MINOR", "轻微逾期", "1-3 天", intOf(totals, "overdue1to3"), totalOverdue(totals)));
        rows.add(level("MEDIUM", "中度逾期", "4-7 天", intOf(totals, "overdue4to7"), totalOverdue(totals)));
        rows.add(level("SEVERE", "严重逾期", "8 天以上", intOf(totals, "overdue8plus"), totalOverdue(totals)));
        return rows;
    }

    private static Map<String, Object> level(String key, String label, String range, int count, int total) {
        // 用 LinkedHashMap 而不是 Map.of：本周期没有逾期时 rate 是 null（「样本不足」，不是 0%），
        // 而 Map.of 见到 null 值会直接抛 NPE —— 没有逾期正是最需要先看到的那种周期。
        var row = new LinkedHashMap<String, Object>();
        row.put("key", key);
        row.put("label", label);
        row.put("range", range);
        row.put("count", count);
        row.put("rate", total == 0 ? null : Math.round(count * 1000.0 / total) / 10.0);
        return row;
    }

    private static int totalOverdue(Map<String, Object> totals) {
        return intOf(totals, "overdue1to3") + intOf(totals, "overdue4to7") + intOf(totals, "overdue8plus");
    }

    private static long sum(List<Map<String, Object>> rows) {
        return rows.stream().mapToLong(row -> ((Number) row.get("count")).longValue()).sum();
    }

    private static int intOf(Map<String, Object> totals, String key) {
        return ((Number) totals.get(key)).intValue();
    }

    private static String orEmpty(String value) { return value == null ? "" : value; }

    /** 周期天数（含首尾）；任一端缺省时为 0 —— 不限区间不谈天数。 */
    private static long days(String fromDate, String toDate) {
        if (fromDate == null || toDate == null || fromDate.isBlank() || toDate.isBlank()) return 0;
        try {
            return ChronoUnit.DAYS.between(LocalDate.parse(fromDate), LocalDate.parse(toDate)) + 1;
        } catch (RuntimeException e) {
            return 0;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> totals(Map<String, Object> result) {
        return (Map<String, Object>) result.get("totals");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> reasons(Map<String, Object> result, String key) {
        var value = result.get(key);
        return value == null ? List.of() : (List<Map<String, Object>>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<String> workshopNames(Map<String, Object> result) {
        var rows = (List<Map<String, Object>>) result.get("workshops");
        return rows == null ? List.of() : rows.stream().map(row -> String.valueOf(row.get("name"))).toList();
    }
}
