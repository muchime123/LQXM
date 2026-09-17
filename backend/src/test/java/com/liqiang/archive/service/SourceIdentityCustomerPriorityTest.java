package com.liqiang.archive.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SourceIdentityCustomerPriorityTest {

    @Test
    void nameDecisionCannotConsumeAnUnmatchedNewPhone() {
        assertFalse(SourceIdentityService.canReuseNameDecision(
                42L, List.of("13800138000"), List.of()));
    }

    @Test
    void nameDecisionMayBeReusedWhenPhoneConfirmsSameCustomer() {
        assertTrue(SourceIdentityService.canReuseNameDecision(
                42L, List.of("13800138000"), List.of(42L)));
    }

    @Test
    void nameDecisionRemainsAvailableWhenSourceHasNoValidPhone() {
        assertTrue(SourceIdentityService.canReuseNameDecision(42L, List.of(), List.of()));
    }
}
