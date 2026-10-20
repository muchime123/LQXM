package com.liqiang.archive.service;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 卡片与下钻的一致性契约。
 *
 * <p>{@code summarize()} 用累加计数器算卡片数字，{@code matches()} 用逐行过滤算下钻条数 ——
 * 这两者是同一批判据的<b>两份独立实现</b>。只改一处就会变成「卡片 39、点进去 41」。
 * 本测试穷尽遍历 {@code BUCKETS}（遍历的是服务里那份真名单，不另抄一份），逐个断言两者相等。
 */
class ProductionTrackingAnalyticsConsistencyTest {
    private static final LocalDate TODAY=LocalDate.of(2026,10,19);

    /** 区间外单独计数，不进 summarize 的 rows（见 missingExpectedStaysOutOfTotals）。 */
    private static final Set<String> WITHOUT_CARD=Set.of("missingExpected");
    /**
     * 这五个都要带原因（原文或类别）参数才成立，卡片上由原因榜代替 ——
     * 对拍见 reasonRankingsMatchTheirOwnDrilldowns 与 reasonCategoryRankingsMatchTheirOwnDrilldowns。
     */
    private static final Set<String> REASON_BUCKETS=Set.of("extensionReason","overdueReason","riskCategory",
            "extensionReasonCategory","overdueReasonCategory");

    @SuppressWarnings("unchecked")
    private static Map<String,Object> totals(List<ProductionTrackingAnalyticsService.OrderRow> rows){
        return (Map<String,Object>)ProductionTrackingAnalyticsService.summarize(rows,TODAY).get("totals");
    }

    private static long num(Map<String,Object> totals,String key){
        var value=totals.get(key);
        assertNotNull(value,"totals 缺少 "+key);
        return ((Number)value).longValue();
    }

    private static long drilldown(List<ProductionTrackingAnalyticsService.OrderRow> rows,String bucket,String reason){
        return rows.stream().filter(row->ProductionTrackingAnalyticsService.matches(row,bucket,reason,TODAY)).count();
    }

    private static long drilldownCategory(List<ProductionTrackingAnalyticsService.OrderRow> rows,String bucket,String category){
        return rows.stream().filter(row->ProductionTrackingAnalyticsService.matches(row,bucket,null,category,TODAY)).count();
    }

    private static LocalDate d(String value){return value==null?null:LocalDate.parse(value);}

    /**
     * 覆盖组合：按期 / 逾期三档 / 取消 / 停止 / 未完工四种到期状态 / 缺基准（已完工与未完工）
     * / 延期（有原因与无原因）/ 提前 / 入库来源三种 / 有业务员要求日与没有。
     */
    private static ProductionTrackingAnalyticsService.OrderRow row(long id,String submitted,String requested,
            String original,String revised,String baseline,String delivered,boolean stopped,boolean cancelled,
            String risk,String delayReason,String overdueReason,String commitmentReason,String receipt,String manual){
        return new ProductionTrackingAnalyticsService.OrderRow(id,"车间A",d(submitted),d(requested),
                d(original),d(revised),d(baseline),d(delivered),stopped,cancelled,risk,delayReason,overdueReason,
                commitmentReason,d(receipt),d(manual));
    }

    private static List<ProductionTrackingAnalyticsService.OrderRow> sampleRows(){
        return List.of(
                // 1 按期完工（晚于业务员要求日之前），延期过 5 天，原因「产能紧张」，采购入库单一来源
                row(1,"2026-09-01","2026-09-28","2026-09-20","2026-09-25","2026-09-25","2026-09-24",
                        false,false,"CAPACITY","产能紧张",null,null,"2026-09-24",null),
                // 2 逾期 2 天，人工入库单一来源，有逾期原因，仍早于业务员要求日
                row(2,"2026-09-01","2026-09-10","2026-09-05","2026-09-05","2026-09-05","2026-09-07",
                        false,false,null,null,"客户验收延迟",null,null,"2026-09-07"),
                // 3 逾期 5 天，两个来源都有，逾期原因缺失
                row(3,"2026-09-01","2026-09-03","2026-09-01","2026-09-01","2026-09-01","2026-09-06",
                        false,false,null,null,null,null,"2026-09-06","2026-09-08"),
                // 4 逾期 10 天，交期被提前（advanced），无业务员要求日
                row(4,"2026-09-01",null,"2026-09-10","2026-09-05","2026-09-05","2026-09-15",
                        false,false,null,null,"物流延误",null,"2026-09-15",null),
                // 5 逾期 3 天，逾期原因缺失
                row(5,"2026-09-01","2026-09-30","2026-10-05","2026-10-05","2026-10-05","2026-10-08",
                        false,false,null,null,null,null,"2026-10-08",null),
                // 6 已取消 —— 排除出履约分母
                row(6,"2026-09-01","2026-10-15",null,null,"2026-10-15","2026-10-10",
                        false,true,null,null,null,null,"2026-10-10",null),
                // 7 停止本地跟单但未取消，基准已过 → 计入应完成与逾期未完工
                row(7,"2026-09-01","2026-10-10",null,null,"2026-10-10",null,
                        true,false,null,null,null,null,null,null),
                // 8 基准 = 今天
                row(8,"2026-09-01","2026-10-19",null,null,"2026-10-19",null,
                        false,false,null,null,null,null,null,null),
                // 9 基准 1 天后 → 未到期且临期
                row(9,"2026-09-01","2026-10-20",null,null,"2026-10-20",null,
                        false,false,null,null,null,null,null,null),
                // 10 基准 11 天后 → 未到期且不临期
                row(10,"2026-09-01","2026-10-30",null,null,"2026-10-30",null,
                        false,false,null,null,null,null,null,null),
                // 11 无基准但已完工（区间外，只用于验证兼容字段）
                row(11,"2026-09-01",null,null,null,null,"2026-09-10",
                        false,false,null,null,null,null,"2026-09-10",null),
                // 12 无基准且未完工
                row(12,"2026-09-01",null,null,null,null,null,
                        false,false,null,null,null,null,null,null),
                // 13 无基准且已取消 → 不计入缺基准
                row(13,"2026-09-01",null,null,null,null,null,
                        false,true,null,null,null,null,null,null),
                // 14 延期 25 天却没有记录原因
                row(14,"2026-09-01","2026-10-20","2026-09-25","2026-10-20","2026-10-20",null,
                        false,false,null,null,null,null,null,null),
                // 15 延期 30 天，原因写在 COMMITMENT 核实记录里
                row(15,"2026-09-01","2026-10-25","2026-09-25","2026-10-25","2026-10-25",null,
                        false,false,"MATERIAL",null,null,"材料未到",null,null),
                // 16 按期完工（恰好等于基准），无业务员要求日
                row(16,"2026-09-01",null,"2026-09-15","2026-09-15","2026-09-15","2026-09-15",
                        false,false,null,null,null,null,"2026-09-15",null));
    }

    /** 核心：卡片数字 === 点进去的条数，逐个 bucket 断言。 */
    @Test void everyBucketCardMatchesItsDrilldown(){
        var rows=sampleRows();
        var totals=totals(rows);
        for(String bucket:ProductionTrackingAnalyticsService.BUCKETS){
            if(WITHOUT_CARD.contains(bucket)||REASON_BUCKETS.contains(bucket))continue;
            var card=totals.get(bucket);
            assertNotNull(card,"bucket "+bucket+" 进了名单却没有卡片数字");
            assertEquals(drilldown(rows,bucket,null),((Number)card).longValue(),
                    "bucket "+bucket+" 卡片数字与下钻条数不符");
        }
    }

    /**
     * 缺基准的任务不落在任何日期区间内，卡片上的这个数字是 analyze() 用另一条 SQL 单独数的，
     * 不进 summarize 的 rows —— 所以它不能出现在 totals 里，否则会被当成区间内的正常统计。
     */
    @Test void missingExpectedStaysOutOfTotalsAndCountsOnlyUncancelledOrdersWithoutBaseline(){
        var rows=sampleRows();
        assertFalse(totals(rows).containsKey("missingExpected"));
        var expected=rows.stream().filter(row->!row.cancelled()&&row.baseline()==null).count();
        assertEquals(2,expected);
        assertEquals(expected,drilldown(rows,"missingExpected",null));
    }

    /**
     * 名单里的每个 bucket 都必须有归属：卡片上有数字、原因榜代替，或区间外单独计数。
     * 不能有「点了能出 7 条、卡片上却没这个数」的孤儿 —— 那正是卡片 39、下钻 41 的另一半。
     */
    @Test void everyBucketInTheWhitelistHasSomewhereToShowItsNumber(){
        var totals=totals(sampleRows());
        for(String bucket:ProductionTrackingAnalyticsService.BUCKETS)
            assertTrue(WITHOUT_CARD.contains(bucket)||REASON_BUCKETS.contains(bucket)||totals.containsKey(bucket),
                    "bucket "+bucket+" 能下钻出结果，卡片上却没有对应数字");
    }

    /** 各分支互斥且闭合：分之和必须等于分母。 */
    @Test void branchesAreMutuallyExclusiveAndClosed(){
        var t=totals(sampleRows());
        long total=num(t,"total"),cancelled=num(t,"cancelled"),dueTasks=num(t,"dueTasks");
        long delivered=num(t,"delivered"),open=num(t,"open");
        long comparable=num(t,"comparable"),onTime=num(t,"onTime"),overdue=num(t,"overdue");
        long deliveredNoBaseline=num(t,"deliveredNoBaseline");
        long openOverdue=num(t,"openOverdue"),openDueToday=num(t,"openDueToday");
        long openNotDue=num(t,"openNotDue"),openNoBaseline=num(t,"openNoBaseline");

        assertEquals(cancelled+dueTasks,total,"总数 = 已取消 + 应完成");
        assertEquals(delivered+open,dueTasks,"应完成 = 已完工 + 未完工");
        assertEquals(onTime+overdue+deliveredNoBaseline,delivered,"已完工三分支互斥且闭合");
        assertEquals(onTime+overdue,comparable,"可比样本 = 按期 + 逾期");
        assertEquals(openOverdue+openDueToday+openNotDue+openNoBaseline,open,"未完工四分支互斥且闭合");
        assertEquals(num(t,"overdue1to3")+num(t,"overdue4to7")+num(t,"overdue8plus"),overdue,
                "逾期三档按天数互斥且闭合");
        // 逾期分档边界：1-3 / 4-7 / 8+，共 1-7 与 8+ 的切分点要落在正确一侧
        assertEquals(2,num(t,"overdue1to3"));
        assertEquals(1,num(t,"overdue4to7"));
        assertEquals(1,num(t,"overdue8plus"));
        // 临期不是互斥分支，是「今天 + 未来三天内」的重叠子集
        assertEquals(3,num(t,"openDueSoon"));
        assertTrue(num(t,"openDueSoon")<=openDueToday+openNotDue);
        // 入库来源三分类是已完工的子集（两个来源都缺的行不进任何一类）
        assertEquals(delivered,num(t,"receiptOnly")+num(t,"manualOnly")+num(t,"bothSources"));
        // 交期调整：提前与延后互斥，且都属于应完成任务
        assertTrue(num(t,"extended")+num(t,"advanced")<=dueTasks);
    }

    /** 三个主指标的计算式：分母是应完成 / 已完工，不是总数。 */
    @Test void headlineRatesUseDueTasksAndDeliveredAsDenominators(){
        var t=totals(sampleRows());
        // 14 应完成、7 已完工、2 按期 → 50.0 / 14.3 / 28.6
        assertEquals(14,num(t,"dueTasks"));
        assertEquals(7,num(t,"delivered"));
        assertEquals(50.0,((Number)t.get("completionRate")).doubleValue());
        assertEquals(14.3,((Number)t.get("onTimeAchievementRate")).doubleValue());
        assertEquals(28.6,((Number)t.get("completedOnTimeRate")).doubleValue());
    }

    /** 分母为 0 时三个主指标一律返回 null —— 不用 0% 冒充结论。 */
    @Test void headlineRatesAreNullRatherThanZeroPercentWhenTheDenominatorIsEmpty(){
        var allCancelled=List.of(row(30,"2026-09-01","2026-10-10",null,null,"2026-10-10",null,
                false,true,null,null,null,null,null,null));
        var cancelledTotals=totals(allCancelled);
        assertEquals(0,num(cancelledTotals,"dueTasks"));
        assertNull(cancelledTotals.get("completionRate"));
        assertNull(cancelledTotals.get("onTimeAchievementRate"));
        assertNull(cancelledTotals.get("completedOnTimeRate"));
        // 有应完成任务却一单未完工：完工率确实是 0.0，但「干完的里面多少按时」无样本可算
        var noneDelivered=List.of(row(31,"2026-09-01","2026-10-30",null,null,"2026-10-30",null,
                false,false,null,null,null,null,null,null));
        var openTotals=totals(noneDelivered);
        assertEquals(0.0,((Number)openTotals.get("completionRate")).doubleValue());
        assertEquals(0.0,((Number)openTotals.get("onTimeAchievementRate")).doubleValue());
        assertNull(openTotals.get("completedOnTimeRate"));
    }

    /** 改期会把任务搬到另一个月份区间，但不改变它自身的履约状态。 */
    @Test void reschedulingMovesTheOrderBetweenMonthsWithoutChangingItsStatus(){
        var before=row(20,"2026-09-01","2026-10-10","2026-09-20","2026-09-20","2026-09-20","2026-09-25",
                false,false,null,null,null,null,"2026-09-25",null);
        var after=row(20,"2026-09-01","2026-10-10","2026-09-20","2026-10-20","2026-10-20","2026-09-25",
                false,false,null,null,null,null,"2026-09-25",null);
        assertEquals("LATE",ProductionTrackingAnalyticsService.status(before));
        assertEquals("ON_TIME",ProductionTrackingAnalyticsService.status(after));
        @SuppressWarnings("unchecked")
        var monthsBefore=(List<Map<String,Object>>)ProductionTrackingAnalyticsService.summarize(List.of(before),TODAY).get("months");
        @SuppressWarnings("unchecked")
        var monthsAfter=(List<Map<String,Object>>)ProductionTrackingAnalyticsService.summarize(List.of(after),TODAY).get("months");
        assertEquals("2026-09",monthsBefore.get(0).get("name"));
        assertEquals("2026-10",monthsAfter.get(0).get("name"));
        // 卡片上的状态口径随基准变化，但两个月的下钻条数各自等于卡片数
        assertEquals(1,drilldown(List.of(after),"onTime",null));
        assertEquals(1,drilldown(List.of(before),"overdue",null));
    }

    /** 三个原因榜的每一条，条数都要等于按该原因下钻出来的条数。 */
    @SuppressWarnings("unchecked")
    @Test void reasonRankingsMatchTheirOwnDrilldowns(){
        var rows=sampleRows();
        var result=ProductionTrackingAnalyticsService.summarize(rows,TODAY);
        var totals=(Map<String,Object>)result.get("totals");
        for(var reason:(List<Map<String,Object>>)result.get("extensionReasons"))
            assertEquals(((Number)reason.get("count")).longValue(),
                    drilldown(rows,"extensionReason",(String)reason.get("name")),
                    "延期原因「"+reason.get("name")+"」榜条数与下钻不符");
        for(var reason:(List<Map<String,Object>>)result.get("riskCategories"))
            assertEquals(((Number)reason.get("count")).longValue(),
                    drilldown(rows,"riskCategory",(String)reason.get("name")),
                    "风险类别「"+reason.get("name")+"」榜条数与下钻不符");
        for(var reason:(List<Map<String,Object>>)result.get("overdueReasons"))
            assertEquals(((Number)reason.get("count")).longValue(),
                    drilldown(rows,"overdueReason",(String)reason.get("name")),
                    "逾期原因「"+reason.get("name")+"」榜条数与下钻不符");
        // 逾期榜不截断 ⇒ 各原因条数之和 === 逾期总数，一笔都不能少
        var sum=((List<Map<String,Object>>)result.get("overdueReasons")).stream()
                .mapToLong(reason->((Number)reason.get("count")).longValue()).sum();
        assertEquals(num(totals,"overdue"),sum,"逾期榜不截断：原因之和必须等于逾期总数");
    }

    /**
     * 周复盘看板把原因原文归并成六类（组装 / 入库 / 工期 / 业务员 / 配件 / 模具），
     * 归并后的每一条也必须等于按该类别下钻出来的条数 —— 否则就是「看板写 3 单、点进去 5 单」。
     * 这里复用 summarize 全量输出里的原文榜（不截断）当输入，与周复盘服务取的是同一份。
     */
    @Test void reasonCategoryRankingsMatchTheirOwnDrilldownsAndLoseNoOrder(){
        var rows=sampleRows();
        var result=ProductionTrackingAnalyticsService.summarize(rows,TODAY);
        var totals=(Map<String,Object>)result.get("totals");
        // 归并的输入必须是**不截断**的原文榜：抽屉展示的延后原因榜截 12 条，拿它归并会少单
        var overdue=ProductionTrackingReasonTaxonomy.aggregate(
                asEntries(ProductionTrackingAnalyticsService.overdueReasonCounts(rows)));
        for(var item:overdue)
            assertEquals(((Number)item.get("count")).longValue(),
                    drilldownCategory(rows,"overdueReasonCategory",(String)item.get("key")),
                    "逾期原因类别「"+item.get("label")+"」榜条数与下钻不符");
        // 归并只是换个分法，不能改变总数：类别之和 === 逾期总数
        assertEquals(num(totals,"overdue"),
                overdue.stream().mapToLong(item->((Number)item.get("count")).longValue()).sum(),
                "逾期原因类别之和必须等于逾期总数");

        var extension=ProductionTrackingReasonTaxonomy.aggregate(
                asEntries(ProductionTrackingAnalyticsService.extensionReasonCounts(rows)));
        for(var item:extension)
            assertEquals(((Number)item.get("count")).longValue(),
                    drilldownCategory(rows,"extensionReasonCategory",(String)item.get("key")),
                    "延期原因类别「"+item.get("label")+"」榜条数与下钻不符");
        assertEquals(num(totals,"extended"),
                extension.stream().mapToLong(item->((Number)item.get("count")).longValue()).sum(),
                "延期原因类别之和必须等于协商延后总数");
    }

    private static List<Map<String,Object>> asEntries(Map<String,Integer> counts){
        return counts.entrySet().stream()
                .map(e->Map.<String,Object>of("name",e.getKey(),"count",e.getValue())).toList();
    }
}
