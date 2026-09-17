package com.liqiang.archive.service;

import com.liqiang.archive.dto.H3FactImportDtos.OrchestrationStep;
import com.liqiang.archive.service.H3FactImportService.FactType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 销售出库导入后先完成确定性主档关联，再执行回款单一权威一致性守护；
 * 其余事实类型不触发编排。
 */
class H3ImportOrchestratorServiceTest {

    @Test
    void outboundPlanOrganizesRelationsBeforeRebalance() {
        assertEquals(List.of(
                        OrchestrationStep.LINK_OUTBOUND_EMPLOYEE_WAREHOUSE,
                        OrchestrationStep.LINK_OUTBOUND_CUSTOMERS,
                        OrchestrationStep.CREATE_SAFE_OUTBOUND_CUSTOMERS,
                        OrchestrationStep.RESOLVE_SAFE_MULTI_PHONE_CUSTOMERS,
                        OrchestrationStep.LINK_OUTBOUND_PRODUCTS,
                        OrchestrationStep.REBALANCE_OUTBOUND_SNAPSHOTS),
                H3ImportOrchestratorService.stepsFor(FactType.OUTBOUND));
    }

    @Test
    void nonOutboundFactTypesHaveNoSteps() {
        assertTrue(H3ImportOrchestratorService.stepsFor(FactType.RECEIPT).isEmpty());
        assertTrue(H3ImportOrchestratorService.stepsFor(FactType.SETTLEMENT).isEmpty());
        assertTrue(H3ImportOrchestratorService.stepsFor(FactType.PRODUCTION).isEmpty());
    }

    @Test
    void safeMsgFallsBackToClassForBlankMessage() {
        Exception blank = new IllegalStateException("   ");
        assertEquals("IllegalStateException", H3ImportOrchestratorService.safeMsg(blank));
        assertEquals("null", H3ImportOrchestratorService.safeMsg(null));
    }
}
