package com.liqiang.archive.service;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ProductionTrackingAnalyticsServiceTest {
    private static final LocalDate TODAY=LocalDate.of(2026,10,15);
    private static LocalDate d(String value){return LocalDate.parse(value);}

    @SuppressWarnings("unchecked")
    @Test void separatesDeliveryPerformanceOpenRiskRescheduleAndStoppedOrders(){
        var rows=List.of(
                new ProductionTrackingAnalyticsService.OrderRow(1,"封口机",d("2026-09-01"),d("2026-09-12"),d("2026-09-12"),
                        d("2026-09-15"),d("2026-09-15"),d("2026-09-14"),false,"CAPACITY","产线占用",null,null,d("2026-09-14"),null),
                new ProductionTrackingAnalyticsService.OrderRow(2,"封口机",d("2026-09-02"),d("2026-09-12"),d("2026-09-12"),
                        null,d("2026-09-12"),d("2026-09-17"),false,null,null,"待核实",null,null,d("2026-09-17")),
                new ProductionTrackingAnalyticsService.OrderRow(3,"封口机",d("2026-10-01"),null,null,
                        null,d("2026-10-10"),null,false,null,null,null,null,null,null),
                new ProductionTrackingAnalyticsService.OrderRow(4,"其他",d("2026-10-01"),null,d("2026-10-15"),
                        null,d("2026-10-15"),null,true,null,null,null,null,null,null));
        var result=ProductionTrackingAnalyticsService.summarize(rows,TODAY);
        var totals=(Map<String,Object>)result.get("totals");
        assertEquals(4,totals.get("total"));
        assertEquals(1,totals.get("stopped"));
        assertEquals(2,totals.get("comparable"));
        assertEquals(1,totals.get("onTime"));
        assertEquals(1,totals.get("overdue"));
        assertEquals(50.0,totals.get("onTimeRate"));
        assertEquals(1,totals.get("open"));
        assertEquals(1,totals.get("openOverdue"));
        assertEquals(3,totals.get("dueTasks"));
        assertEquals(66.7,totals.get("completionRate"));
        assertEquals(33.3,totals.get("onTimeAchievementRate"));
        assertEquals(0,totals.get("openDueToday"));
        assertEquals(1,totals.get("extended"));
        assertEquals(0,totals.get("extensionReasonMissing"));
        assertEquals(0,totals.get("overdueReasonMissing"));
        assertEquals(5.0,totals.get("averageOverdueDays"));
        assertEquals(0,totals.get("deliveredNoBaseline"));
        assertEquals(1,totals.get("receiptOnly"));
        assertEquals(1,totals.get("manualOnly"));
        assertEquals(0.0,totals.get("requestedOnTimeRate"));
        assertEquals(2,((List<?>)result.get("months")).size());
    }

    @SuppressWarnings("unchecked")
    @Test void leavesRateUndefinedWhenThereAreNoComparableDeliveries(){
        var result=ProductionTrackingAnalyticsService.summarize(List.of(
                new ProductionTrackingAnalyticsService.OrderRow(5,"车间",d("2026-10-15"),null,null,null,null,null,
                        false,null,null,null,null,null,null)),TODAY);
        var totals=(Map<String,Object>)result.get("totals");
        assertNull(totals.get("onTimeRate"));
        assertNull(totals.get("requestedOnTimeRate"));
        assertEquals(1,totals.get("openNoBaseline"));
        var missing=new ProductionTrackingAnalyticsService.OrderRow(5,"车间",d("2026-10-15"),null,null,null,null,null,
                false,null,null,null,null,null,null);
        assertEquals("MISSING_EXPECTED",ProductionTrackingAnalyticsService.status(missing));
        assertEquals(true,ProductionTrackingAnalyticsService.matches(missing,"missingExpected",null,TODAY));
    }

    @Test void drilldownUsesTheSameBoundariesAndReasonRulesAsSummary(){
        var late=new ProductionTrackingAnalyticsService.OrderRow(6,"封口机",d("2026-09-01"),d("2026-09-10"),
                d("2026-09-10"),d("2026-09-13"),d("2026-09-13"),d("2026-09-18"),false,
                "CAPACITY","产能紧张","客户验收延迟",null,d("2026-09-18"),null);
        assertEquals(true,ProductionTrackingAnalyticsService.matches(late,"extended",null,TODAY));
        assertEquals(true,ProductionTrackingAnalyticsService.matches(late,"overdue4to7",null,TODAY));
        assertEquals(true,ProductionTrackingAnalyticsService.matches(late,"extensionReason","产能紧张",TODAY));
        assertEquals(true,ProductionTrackingAnalyticsService.matches(late,"overdueReason","客户验收延迟",TODAY));
        assertEquals(true,ProductionTrackingAnalyticsService.matches(late,"riskCategory","CAPACITY",TODAY));
        assertEquals(false,ProductionTrackingAnalyticsService.matches(late,"onTime",null,TODAY));
        assertEquals(false,ProductionTrackingAnalyticsService.matches(late,"overdueReasonMissing",null,TODAY));
        var pending=new ProductionTrackingAnalyticsService.OrderRow(7,"封口机",d("2026-10-01"),null,null,
                null,d("2026-10-15"),null,false,null,null,null,null,null,null);
        assertEquals(true,ProductionTrackingAnalyticsService.matches(pending,"openDueSoon",null,TODAY));
        assertEquals(false,ProductionTrackingAnalyticsService.matches(pending,"openOverdue",null,TODAY));
    }

    /**
     * 逾期原因是跟单员自由填写的原文，榜必须全量返回：
     * 一旦截断，「实际逾期」分桶数就会大于原因之和（差额凭空消失，且看不到长尾写法）。
     */
    @SuppressWarnings("unchecked")
    @Test void overdueReasonRankingIsNotTruncated(){
        var rows=new ArrayList<ProductionTrackingAnalyticsService.OrderRow>();
        for(int i=1;i<=30;i++){
            rows.add(new ProductionTrackingAnalyticsService.OrderRow(i,"封口机",d("2026-09-01"),d("2026-09-10"),
                    d("2026-09-10"),null,d("2026-09-10"),d("2026-09-20"),false,null,null,"原因写法"+i,null,null,null));
        }
        var result=ProductionTrackingAnalyticsService.summarize(rows,TODAY);
        var reasons=(List<Map<String,Object>>)result.get("overdueReasons");
        assertEquals(30,reasons.size()); // 30 种不同写法各 1 单，全部要出现
        assertEquals(30,reasons.stream().mapToInt(reason->((Number)reason.get("count")).intValue()).sum());
        var totals=(Map<String,Object>)result.get("totals");
        assertEquals(30,totals.get("overdue"));
        assertEquals(0,totals.get("overdueReasonMissing"));
    }

    @Test void dateBasisUsesOnlyCurrentWorkshopExpectedDateWithInclusiveBounds(){
        var from=d("2026-10-01");var to=d("2026-10-31");
        // 唯一分析视角：跟单员预计 → 源车间预计，不能回退到业务员要求日。
        // 区间两端都自然日包含；baseline 缺失的工单不纳入（没有承诺日可比）。
        var baselineSql=new StringBuilder(" WHERE 1=1");var baselineParams=new ArrayList<Object>();
        ProductionTrackingAnalyticsService.appendDateBasis(baselineSql,baselineParams,from,to,"baseline");
        assertEquals(List.of(from,to),baselineParams);
        assertEquals(true,baselineSql.toString().contains("COALESCE(t.forecast_finish_date,p.expected_completion_date)"));
        assertEquals(false,baselineSql.toString().contains("required_completion_date"));
        assertEquals(true,baselineSql.toString().contains("IS NOT NULL"));
        assertEquals(true,baselineSql.toString().contains(">=?"));
        assertEquals(true,baselineSql.toString().contains("<=?"));
        // from 留空、to 填：只取上界；to 留空、from 填：只取下界。
        var lowerOnly=new StringBuilder(" WHERE 1=1");var lowerParams=new ArrayList<Object>();
        ProductionTrackingAnalyticsService.appendDateBasis(lowerOnly,lowerParams,from,null,"baseline");
        assertEquals(List.of(from),lowerParams);
        assertEquals(false,lowerOnly.toString().contains("<=?"));
        var upperOnly=new StringBuilder(" WHERE 1=1");var upperParams=new ArrayList<Object>();
        ProductionTrackingAnalyticsService.appendDateBasis(upperOnly,upperParams,null,to,"baseline");
        assertEquals(List.of(to),upperParams);
        assertEquals(false,upperOnly.toString().contains(">=?"));
    }

    @SuppressWarnings("unchecked")
    @Test void cancelledAndStoppedAreExcludedFromDeliveryRatesButCountedSeparately(){
        var rows=List.of(
                new ProductionTrackingAnalyticsService.OrderRow(10,"A",d("2026-09-01"),null,d("2026-09-28"),
                        d("2026-10-08"),d("2026-10-08"),d("2026-10-15"),false,null,null,null,null,null,null),
                new ProductionTrackingAnalyticsService.OrderRow(11,"A",d("2026-09-01"),null,d("2026-10-05"),
                        null,d("2026-10-05"),null,true,null,null,null,null,null,null),
                new ProductionTrackingAnalyticsService.OrderRow(12,"A",d("2026-09-01"),null,d("2026-10-05"),
                        null,d("2026-10-05"),null,false,true,null,null,null,null,null,null));
        var result=ProductionTrackingAnalyticsService.summarize(rows,TODAY);
        var totals=(Map<String,Object>)result.get("totals");
        assertEquals(1,totals.get("dueTasks"));
        assertEquals(1,totals.get("cancelled"));
        assertEquals(1,totals.get("stopped"));
        assertEquals(1,totals.get("overdue"));
        assertEquals(0,totals.get("open"));
        assertEquals("LATE",ProductionTrackingAnalyticsService.status(rows.get(0)));
        assertEquals("STOPPED",ProductionTrackingAnalyticsService.status(rows.get(1)));
        assertEquals(false,ProductionTrackingAnalyticsService.matches(rows.get(2),"dueTasks",null,TODAY));
        assertEquals(false,ProductionTrackingAnalyticsService.matches(rows.get(1),"open",null,TODAY));
        assertEquals(true,ProductionTrackingAnalyticsService.matches(rows.get(1),"stopped",null,TODAY));
        var months=(List<Map<String,Object>>)result.get("months");
        assertEquals(1,months.size());
        assertEquals("2026-10",months.get(0).get("name"));
    }

    @SuppressWarnings("unchecked")
    @Test void unfinishedStatusChangesWithTodayButDoesNotBecomeLateCompletion(){
        var row=new ProductionTrackingAnalyticsService.OrderRow(20,"A",d("2026-10-01"),null,d("2026-10-10"),
                null,d("2026-10-10"),null,false,null,null,null,null,null,null);
        var before=(Map<String,Object>)ProductionTrackingAnalyticsService.summarize(List.of(row),d("2026-10-09")).get("totals");
        var on=(Map<String,Object>)ProductionTrackingAnalyticsService.summarize(List.of(row),d("2026-10-10")).get("totals");
        var after=(Map<String,Object>)ProductionTrackingAnalyticsService.summarize(List.of(row),d("2026-10-11")).get("totals");
        assertEquals(1,before.get("openNotDue"));
        assertEquals(1,on.get("openDueToday"));
        assertEquals(1,after.get("openOverdue"));
        assertEquals(0,after.get("overdue"));
        assertEquals(1,after.get("dueTasks"));
    }
}
