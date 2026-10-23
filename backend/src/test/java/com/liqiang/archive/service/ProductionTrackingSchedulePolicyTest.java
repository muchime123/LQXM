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
}
