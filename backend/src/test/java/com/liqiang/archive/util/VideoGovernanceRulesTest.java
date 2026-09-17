package com.liqiang.archive.util;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class VideoGovernanceRulesTest {

    @Test
    void assignedMachineTierConfirmsScope() {
        assertTrue(VideoGovernanceRules.effectiveScopeConfirmed(false, true));
        assertTrue(VideoGovernanceRules.effectiveScopeConfirmed(true, false));
        assertFalse(VideoGovernanceRules.effectiveScopeConfirmed(false, false));
    }

    @Test
    void blankStatusIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> VideoGovernanceRules.validateContentStatus(" "));
    }

    @Test
    void demoCompletenessRequiresApplicationAndDemonstration() {
        assertEquals(List.of("用途标签", "展示参数"),
                VideoGovernanceRules.missingCompletenessItems(
                        "设备运行", true, "", "", "", ""));
        assertTrue(VideoGovernanceRules.isComplete(
                "设备运行", true, "豆腐", "", "一出四", ""));
    }

    @Test
    void incompleteVideoCannotBecomeAvailableOrFeatured() {
        assertThrows(IllegalArgumentException.class,
                () -> VideoGovernanceRules.validateTransition("待确认", "可用", false));
        assertThrows(IllegalArgumentException.class,
                () -> VideoGovernanceRules.validateTransition("可用", "精选", false));
        assertThrows(IllegalArgumentException.class,
                () -> VideoGovernanceRules.validateTransition("可用", "可用", false));
    }

    @Test
    void validTransitionsAreAccepted() {
        assertDoesNotThrow(() -> VideoGovernanceRules.validateTransition("待处理", "待确认", false));
        assertDoesNotThrow(() -> VideoGovernanceRules.validateTransition("待确认", "可用", true));
        assertDoesNotThrow(() -> VideoGovernanceRules.validateTransition("可用", "精选", true));
        assertDoesNotThrow(() -> VideoGovernanceRules.validateTransition("异常", "待确认", false));
    }

    @Test
    void illegalShortcutIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> VideoGovernanceRules.validateTransition("待处理", "精选", true));
    }
}
