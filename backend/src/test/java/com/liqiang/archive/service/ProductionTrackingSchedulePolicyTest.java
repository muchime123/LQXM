package com.liqiang.archive.service;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ProductionTrackingSchedulePolicyTest {
    private static final List<String> CODES=List.of("MATERIAL","MOLD","OXIDATION","ASSEMBLY","WAREHOUSE");
    private static final Map<String,BigDecimal> DAYS=Map.of("MATERIAL",BigDecimal.ZERO,
            "MOLD",new BigDecimal("3"),"ASSEMBLY",new BigDecimal("2"),"WAREHOUSE",BigDecimal.ZERO);
    private static final LocalDate TODAY=LocalDate.parse("2026-10-22");
    private static final LocalDate DEADLINE=LocalDate.parse("2026-10-26");

    @Test void unscheduledSuggestsLatestStartWithoutPredictingNodes() {
        var result=ProductionTrackingSchedulePolicy.calculate(CODES,DAYS,Map.of("OXIDATION",false),
                Map.of(),Map.of(),Map.of(),null,DEADLINE,TODAY);
        assertEquals(LocalDate.parse("2026-10-21"),result.latestStartDate());
        assertEquals("SCHEDULE_LATE",result.risk());
        assertNull(result.predictedFinishDate());
        assertFalse(result.targetDates().containsKey("OXIDATION"));
        assertEquals(LocalDate.parse("2026-10-24"),result.targetDates().get("MOLD"));
    }

    @Test void completedNodeRelaysFromActualDateAndRecalculatesPressure() {
        var result=ProductionTrackingSchedulePolicy.calculate(CODES,DAYS,Map.of("OXIDATION",false),
                Map.of("MATERIAL","DONE"),Map.of("MATERIAL",LocalDate.parse("2026-10-22")),
                Map.of(),TODAY,DEADLINE,TODAY);
        assertEquals(LocalDate.parse("2026-10-27"),result.predictedFinishDate());
        assertEquals(-1L,result.slackDays());
        assertEquals("PRESSURE",result.risk());
        assertFalse(result.targetDates().containsKey("MATERIAL"));
    }

    @Test void manualForecastOverridesOneNodeButMissingLaterIntervalBlocksCompletePrediction() {
        var withManual=ProductionTrackingSchedulePolicy.calculate(CODES,DAYS,Map.of("OXIDATION",false),
                Map.of(),Map.of(),Map.of("MOLD",LocalDate.parse("2026-10-24")),TODAY,DEADLINE,TODAY);
        assertEquals(DEADLINE,withManual.predictedFinishDate());
        assertEquals("NO_BUFFER",withManual.risk());
        var incomplete=ProductionTrackingSchedulePolicy.calculate(CODES,Map.of("MATERIAL",BigDecimal.ZERO),
                Map.of("OXIDATION",false),Map.of(),Map.of(),Map.of(),TODAY,DEADLINE,TODAY);
        assertNull(incomplete.latestStartDate());
        assertNull(incomplete.predictedFinishDate());
        assertEquals("UNKNOWN",incomplete.risk());
    }

    @Test void unscheduledThresholdAndMissingDeadlineStayDistinct() {
        var due=ProductionTrackingSchedulePolicy.calculate(CODES,DAYS,Map.of("OXIDATION",false),
                Map.of(),Map.of(),Map.of(),null,DEADLINE,LocalDate.parse("2026-10-19"));
        assertEquals("SCHEDULE_DUE",due.risk());
        var normal=ProductionTrackingSchedulePolicy.calculate(CODES,DAYS,Map.of("OXIDATION",false),
                Map.of(),Map.of(),Map.of(),null,DEADLINE,LocalDate.parse("2026-10-18"));
        assertEquals("SCHEDULE_NORMAL",normal.risk());
        var noDeadline=ProductionTrackingSchedulePolicy.calculate(CODES,DAYS,Map.of("OXIDATION",false),
                Map.of(),Map.of(),Map.of(),null,null,TODAY);
        assertEquals("NO_BASELINE",noDeadline.risk());
        assertNull(noDeadline.latestStartDate());
    }

    @Test void optionalStageRequiresItsOwnIntervalOnlyWhenApplicable() {
        var required=ProductionTrackingSchedulePolicy.calculate(CODES,DAYS,Map.of("OXIDATION",true),
                Map.of(),Map.of(),Map.of(),TODAY,DEADLINE,TODAY);
        assertNull(required.predictedFinishDate());
        assertNull(required.latestStartDate());
    }

    // ---- P0-1: 入库后的木箱节点不参与交期计算（#39 口径：排产→入库） ----

    @Test void woodenCrateAfterWarehouseExcludedFromDeliveryPeriod() {
        var machineCodes=List.of("MATERIAL","MOLD","ASSEMBLY","WAREHOUSE","WOODEN_CRATE");
        var daysWithCrate=Map.of("MATERIAL",BigDecimal.ZERO,"MOLD",new BigDecimal("3"),
                "ASSEMBLY",new BigDecimal("2"),"WAREHOUSE",BigDecimal.ZERO,
                "WOODEN_CRATE",new BigDecimal("2"));
        var result=ProductionTrackingSchedulePolicy.calculate(machineCodes,daysWithCrate,
                Map.of("WOODEN_CRATE",true),Map.of(),Map.of(),Map.of(),
                TODAY,DEADLINE,TODAY);
        // 木箱不参与：预测完成仍=10-27（与不含木箱一致），不变成 10-29
        assertEquals(LocalDate.parse("2026-10-27"),result.predictedFinishDate());
        assertEquals(-1L,result.slackDays());
        assertEquals("PRESSURE",result.risk());
        assertFalse(result.targetDates().containsKey("WOODEN_CRATE"));
    }

    @Test void woodenCrateDoesNotAffectUnscheduledLatestStart() {
        var machineCodes=List.of("MATERIAL","MOLD","ASSEMBLY","WAREHOUSE","WOODEN_CRATE");
        var daysWithCrate=Map.of("MATERIAL",BigDecimal.ZERO,"MOLD",new BigDecimal("3"),
                "ASSEMBLY",new BigDecimal("2"),"WAREHOUSE",BigDecimal.ZERO,
                "WOODEN_CRATE",new BigDecimal("2"));
        var result=ProductionTrackingSchedulePolicy.calculate(machineCodes,daysWithCrate,
                Map.of("WOODEN_CRATE",true),Map.of(),Map.of(),Map.of(),
                null,DEADLINE,TODAY);
        // 倒排不含木箱：latestStart 仍=10-21（与不含木箱一致）
        assertEquals(LocalDate.parse("2026-10-21"),result.latestStartDate());
        assertEquals("SCHEDULE_LATE",result.risk());
    }

    // ---- 节点提前完成 → 预测提前 ----

    @Test void earlyNodeCompletionAdvancesPredictedFinishAndRelievesPressure() {
        var result=ProductionTrackingSchedulePolicy.calculate(CODES,DAYS,Map.of("OXIDATION",false),
                Map.of("MATERIAL","DONE"),Map.of("MATERIAL",LocalDate.parse("2026-10-20")),
                Map.of(),TODAY,DEADLINE,TODAY);
        // 10-20 + 3 MOLD + 2 ASSEMBLY + 0 WAREHOUSE = 10-25 → slack +1 → ON_TRACK
        assertEquals(LocalDate.parse("2026-10-25"),result.predictedFinishDate());
        assertEquals(1L,result.slackDays());
        assertEquals("ON_TRACK",result.risk());
    }

    // ---- 人工预计晚于倒排目标 → 交期压力，但不修改车间交期 ----

    @Test void manualForecastLaterThanCountdownShowsPressureWithoutModifyingDeadline() {
        var result=ProductionTrackingSchedulePolicy.calculate(CODES,DAYS,Map.of("OXIDATION",false),
                Map.of(),Map.of(),Map.of("MOLD",LocalDate.parse("2026-10-28")),
                TODAY,DEADLINE,TODAY);
        // 人工 MOLD=10-28 → +2 ASSEMBLY +0 WAREHOUSE = 10-30 → slack -4 → PRESSURE
        assertEquals(LocalDate.parse("2026-10-30"),result.predictedFinishDate());
        assertEquals(-4L,result.slackDays());
        assertEquals("PRESSURE",result.risk());
        // 倒排目标不变（MOLD 目标仍=10-24，deadline 仍=10-26）
        assertEquals(LocalDate.parse("2026-10-24"),result.targetDates().get("MOLD"));
    }

    // ---- 车间交期调整 → 倒排目标随之更新 ----

    @Test void adjustedDeadlineUpdatesCountdownTargets() {
        var newDeadline=LocalDate.parse("2026-10-30");
        var result=ProductionTrackingSchedulePolicy.calculate(CODES,DAYS,Map.of("OXIDATION",false),
                Map.of(),Map.of(),Map.of(),null,newDeadline,TODAY);
        // 倒排：WAREHOUSE=10-30, ASSEMBLY=10-30, MOLD=10-28, MATERIAL=10-25
        assertEquals(LocalDate.parse("2026-10-25"),result.latestStartDate());
        assertEquals(LocalDate.parse("2026-10-28"),result.targetDates().get("MOLD"));
        assertEquals(LocalDate.parse("2026-10-30"),result.targetDates().get("WAREHOUSE"));
        // 10-22 < 10-25-2=10-23 → SCHEDULE_NORMAL
        assertEquals("SCHEDULE_NORMAL",result.risk());
    }
}
