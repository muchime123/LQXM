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
                new ProductionTrackingAnalyticsService.OrderRow(4,"其他",d("2026-10-01"),null,null,
                        null,null,null,true,null,null,null,null,null,null));
        var result=ProductionTrackingAnalyticsService.summarize(rows,TODAY);
        var totals=(Map<String,Object>)result.get("totals");
        assertEquals(4,totals.get("total"));
        assertEquals(1,totals.get("stopped"));
        assertEquals(2,totals.get("comparable"));
        assertEquals(1,totals.get("onTime"));
        assertEquals(1,totals.get("overdue"));
        assertEquals(50.0,totals.get("onTimeRate"));
        assertEquals(1,totals.get("openOverdue"));
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

    @Test void dateBasisKeepsCarryoverAndUsesAuthoritativeInboundForFiltering(){
        var from=d("2026-10-01");var to=d("2026-10-31");
        var activeSql=new StringBuilder(" WHERE 1=1");var activeParams=new ArrayList<Object>();
        ProductionTrackingAnalyticsService.appendDateBasis(activeSql,activeParams,from,to,"active");
        assertEquals(List.of(to,from,from),activeParams);
        assertEquals(true,activeSql.toString().contains("COALESCE(DATE(p.source_created_at),p.application_date)<=?"));
        assertEquals(true,activeSql.toString().contains("COALESCE(ms.warehouse_in_date,ib.first_in)>=?"));
        assertEquals(false,activeSql.toString().contains("p.source_created_at>=?"));

        var inboundSql=new StringBuilder(" WHERE 1=1");var inboundParams=new ArrayList<Object>();
        ProductionTrackingAnalyticsService.appendDateBasis(inboundSql,inboundParams,from,to,"inbound");
        assertEquals(List.of(from,to),inboundParams);
        assertEquals(true,inboundSql.toString().contains("COALESCE(ms.warehouse_in_date,ib.first_in) IS NOT NULL"));
        assertEquals(true,inboundSql.toString().contains("COALESCE(ms.warehouse_in_date,ib.first_in)<=?"));

        var submittedSql=new StringBuilder(" WHERE 1=1");var submittedParams=new ArrayList<Object>();
        ProductionTrackingAnalyticsService.appendDateBasis(submittedSql,submittedParams,from,to,"submitted");
        assertEquals(List.of(from,d("2026-11-01")),submittedParams);
        assertEquals(true,submittedSql.toString().contains("p.source_created_at>=?"));
    }
}
