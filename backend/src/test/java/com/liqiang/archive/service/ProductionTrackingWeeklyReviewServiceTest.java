package com.liqiang.archive.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 周复盘响应的组织契约。
 *
 * <p>【为什么拿真周报当样本】样本就是苏平 10 月第二周周复盘表里的数字（65 / 55 / 7 / 3、84.6%），
 * 原因原文也是他平时登记进系统的原话。故这里断言的是「看板能不能复现出他手工做的那张表」，
 * 而不是某个内部结构的形状 —— 一旦看板口径与周报对不上，这个测试会先红。
 */
@ExtendWith(MockitoExtension.class)
class ProductionTrackingWeeklyReviewServiceTest {
    @Mock private ProductionTrackingAnalyticsService analytics;
    @InjectMocks private ProductionTrackingWeeklyReviewService service;

    private static final String FROM = "2026-10-12", TO = "2026-10-18";

    @Test void periodIsEchoedBackWithItsLength() {
        when(analytics.analyzeUncut(eq(FROM), eq(TO), any())).thenReturn(emptyResult());
        assertEquals(Map.of("from", FROM, "to", TO, "days", 7L),
                service.review(FROM, TO, "").get("period"));
    }

    @Test void historicalPeriodCardsUseTheSameCurrentRowsAsDrilldown() {
        when(analytics.analyzeUncut(eq(FROM), eq(TO), any())).thenReturn(weekTwo());
        var summary = summary(service.review(FROM, TO, ""));
        assertEquals(3, summary.get("open"));
        verify(analytics, never()).readSnapshot(any(), any(), any());
    }

    /** 卡片四数互斥闭合：应完成＝按期＋逾期＋未完工 —— 周报的「合计」就是这么加出来的。 */
    @Test void headlineCardsReproduceTheHandwrittenWeeklyReport() {
        when(analytics.analyzeUncut(any(), any(), any())).thenReturn(weekTwo());
        var summary = summary(service.review(FROM, TO, ""));
        assertEquals(65, summary.get("dueTasks"));
        assertEquals(55, summary.get("onTime"));
        assertEquals(7, summary.get("overdue"));
        assertEquals(3, summary.get("open"));
        assertEquals(84.6, ((Number) summary.get("onTimeAchievementRate")).doubleValue());
        assertEquals(4, summary.get("missingExpected"));
    }

    /** 分档与原因榜都各自闭合到逾期总数 —— 周报里「合计 7」的两种写法。 */
    @SuppressWarnings("unchecked")
    @Test void overdueLevelsAndReasonsBothSumToTheOverdueTotal() {
        when(analytics.analyzeUncut(any(), any(), any())).thenReturn(weekTwo());
        var response = service.review(FROM, TO, "");
        assertEquals(7L, response.get("overdueTotal"));

        var levels = (List<Map<String, Object>>) response.get("overdueLevels");
        assertEquals(7, sumOf(levels), "逾期三档之和＝逾期总数");
        assertEquals("MINOR", levels.get(0).get("key"));
        assertEquals(100.0, ((Number) levels.get(0).get("rate")).doubleValue(), "7 单全部是 1-3 天的轻微逾期");
        assertEquals(0.0, ((Number) levels.get(1).get("rate")).doubleValue(), "中度 0 单也要显示 0.0%，不是样本不足");

        var reasons = (List<Map<String, Object>>) response.get("overdueReasons");
        assertEquals(7, sumOf(reasons), "归并成类别后一笔都不能少");
        assertEquals(List.of("ASSEMBLY", "INBOUND", "SALES", "PARTS", "MOLD"),
                reasons.stream().map(row -> row.get("key")).toList(),
                "组装 2、入库 2 在前；同为 1 单时按周报的列举顺序（业务员 > 配件 > 模具）");
        assertEquals(28.6, ((Number) reasons.get(0).get("rate")).doubleValue());
        assertEquals(List.of("没时间装"), reasons.get(0).get("samples"), "备注列直接给出跟单员填的原文");
    }

    /** 延期是另一回事：它按「协商后把交期往后调」计数，与逾期互不干扰。 */
    @SuppressWarnings("unchecked")
    @Test void extensionReasonsSumToTheExtensionTotalAndRankByCount() {
        when(analytics.analyzeUncut(any(), any(), any())).thenReturn(weekTwo());
        var response = service.review(FROM, TO, "");
        assertEquals(10L, response.get("extensionTotal"));

        var reasons = (List<Map<String, Object>>) response.get("extensionReasons");
        assertEquals(10, sumOf(reasons));
        assertEquals(List.of("SALES", "ASSEMBLY", "PARTS", "LEAD_TIME"),
                reasons.stream().map(row -> row.get("key")).toList(), "按单数降序：业务员 4 > 组装 3 > 配件 2 > 工期 1");
        assertEquals(40.0, ((Number) reasons.get(0).get("rate")).doubleValue());
    }

    private static long sumOf(List<Map<String, Object>> rows) {
        return rows.stream().mapToLong(row -> ((Number) row.get("count")).longValue()).sum();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> summary(Map<String, Object> response) {
        return (Map<String, Object>) response.get("summary");
    }

    /** 10 月第二周周复盘表的数字 + 库里的原因原文。 */
    private static Map<String, Object> weekTwo() {
        var totals = new LinkedHashMap<String, Object>();
        totals.put("dueTasks", 65); totals.put("onTime", 55); totals.put("overdue", 7); totals.put("open", 3);
        totals.put("onTimeAchievementRate", 84.6); totals.put("completionRate", 89.2);
        totals.put("extended", 10); totals.put("extensionDays", 42);
        totals.put("overdueDays", 17); totals.put("averageOverdueDays", 2.4);
        totals.put("openOverdue", 1); totals.put("openOverdueDays", 5);
        totals.put("cancelled", 0); totals.put("stopped", 0);
        totals.put("overdue1to3", 7); totals.put("overdue4to7", 0); totals.put("overdue8plus", 0);
        return Map.of("totals", totals,
                "overdueReasons", List.<Map<String, Object>>of(
                        reason("入库延迟", 2), reason("没时间装", 2), reason("模具延迟", 1),
                        reason("业务员订单创建时间超过订单要求完成时间", 1), reason("差钣金配件", 1)),
                "extensionReasons", List.<Map<String, Object>>of(
                        reason("没时间装，档期排满", 3), reason("订单不确定", 4),
                        reason("时间来不及", 1), reason("配件延迟", 2)),
                "missingExpected", 4, "workshops", List.of(), "asOf", "2026-10-19");
    }

    /** 空周期：占比一律 null，不用 0% 冒充结论。 */
    private static Map<String, Object> emptyResult() {
        var totals = new LinkedHashMap<String, Object>();
        for (var key : List.of("dueTasks", "onTime", "overdue", "open", "extended", "extensionDays",
                "overdueDays", "openOverdue", "openOverdueDays", "cancelled", "stopped",
                "overdue1to3", "overdue4to7", "overdue8plus")) totals.put(key, 0);
        totals.put("onTimeAchievementRate", null); totals.put("completionRate", null);
        totals.put("averageOverdueDays", null);
        return Map.of("totals", totals, "overdueReasons", List.of(), "extensionReasons", List.of(),
                "missingExpected", 0, "workshops", List.of(), "asOf", "2026-10-19");
    }

    private static Map<String, Object> reason(String name, int count) {
        return Map.of("name", name, "count", count);
    }
}
