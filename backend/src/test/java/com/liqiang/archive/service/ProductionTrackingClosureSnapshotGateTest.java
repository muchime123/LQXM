package com.liqiang.archive.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProductionTrackingClosureSnapshotGateTest {
    @Test void completedAndInapplicableNodesNeedNoRepeatConfirmation() {
        var nodes=List.<Map<String,Object>>of(
                Map.of("stage_code","DRAWING","applicability","APPLICABLE"),
                Map.of("stage_code","MOLD","applicability","APPLICABLE"),
                Map.of("stage_code","WOODEN_CRATE","applicability","NOT_APPLICABLE"));
        var saved=List.<Map<String,Object>>of(Map.of("stage_code","DRAWING","stage_status","DONE"));
        assertEquals(List.of("MOLD"),ProductionTrackingService.missingClosureNodes(nodes,saved,Map.of()));
    }

    @Test void pendingApplicabilityNeedsExplicitDecisionEvenIfNodeWasMarkedDone() {
        var nodes=List.<Map<String,Object>>of(Map.of("stage_code","OXIDATION","applicability","PENDING_REVIEW"));
        var saved=List.<Map<String,Object>>of(Map.of("stage_code","OXIDATION","stage_status","DONE"));
        assertEquals(List.of("OXIDATION"),ProductionTrackingService.missingClosureNodes(nodes,saved,Map.of()));
        var confirmed=Map.of("OXIDATION",new ProductionTrackingService.NodeConfirmation(
                "OXIDATION","DONE_NO_DATE",null,"车间确认已完成"));
        assertEquals(List.of(),ProductionTrackingService.missingClosureNodes(nodes,saved,confirmed));
    }
}
