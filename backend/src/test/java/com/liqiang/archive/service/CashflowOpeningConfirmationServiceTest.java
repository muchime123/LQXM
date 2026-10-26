package com.liqiang.archive.service;

import com.liqiang.archive.infra.Actor;
import com.liqiang.archive.infra.ActorResolver;
import com.liqiang.archive.infra.IdempotencyService;
import com.liqiang.archive.security.CurrentUserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CashflowOpeningConfirmationServiceTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final IdempotencyService idempotency = mock(IdempotencyService.class);
    private final ActorResolver actors = mock(ActorResolver.class);
    private final CurrentUserService users = mock(CurrentUserService.class);
    private final AuditLogService audit = mock(AuditLogService.class);
    private CashflowOpeningConfirmationService service;

    @BeforeEach
    void setUp() {
        service = new CashflowOpeningConfirmationService(jdbc, idempotency, actors, users, audit);
        when(users.can("cashflow.opening.confirm")).thenReturn(true);
        when(users.current()).thenReturn(new CurrentUserService.CurrentUser(23L, "Finance", "财务部",
                Set.of("FINANCE"), Set.of("cashflow.opening.confirm"), "ALL", false,
                "LOCAL", 1L, null,
                Map.of("CASHFLOW", CurrentUserService.FeatureScope.of("ALL"))));
        when(actors.current()).thenReturn(new Actor(23L, "Finance"));
        when(idempotency.execute(any(), anyString(), eq("cashflow.opening.confirm"), any(), any()))
                .thenAnswer(inv -> ((Supplier<String>) inv.getArgument(4)).get());
    }

    private static CashflowOpeningConfirmationService.Request request(BigDecimal balance) {
        return new CashflowOpeningConfirmationService.Request("BANK_STATEMENT",
                LocalDate.of(2026, 12, 31), balance, "BANK-2026-12-31-001", "已核对原件");
    }

    @Test
    void permissionIsCheckedBeforeIdempotencyReplay() {
        when(users.can("cashflow.opening.confirm")).thenReturn(false);
        assertThrows(SecurityException.class, () -> service.confirm(42, request(new BigDecimal("12.3400")), "key"));
        verifyNoInteractions(idempotency, jdbc);
    }

    @Test
    void mismatchedVoucherBalanceCannotConfirm() {
        when(jdbc.queryForList(contains("FROM cashflow_account_opening"), eq(42L))).thenReturn(List.of(
                Map.of("id", 42L, "account_id", 900L, "fiscal_year", 2027,
                        "amount", new BigDecimal("12.3400"), "active", true,
                        "finance_confirmed", false, "account_name", "测试户")));
        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.confirm(42, request(new BigDecimal("12.3401")), "key"));
        assertEquals(409, error.getStatusCode().value());
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void confirmationWritesStateEvidenceAndAuditWithinWork() {
        when(jdbc.queryForList(contains("FROM cashflow_account_opening"), eq(42L))).thenReturn(List.of(
                Map.of("id", 42L, "account_id", 900L, "fiscal_year", 2027,
                        "amount", new BigDecimal("12.3400"), "active", 1,
                        "finance_confirmed", 0, "account_name", "测试户")));
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        when(jdbc.queryForObject(contains("SELECT id FROM cashflow_opening_confirmation"), eq(Long.class), eq("key")))
                .thenReturn(77L);
        when(jdbc.queryForMap(contains("FROM cashflow_opening_confirmation c"), eq(77L), eq(42L)))
                .thenReturn(Map.of("openingId", 42L, "evidenceId", 77L, "financeConfirmed", true));

        Map<String, Object> result = service.confirm(42, request(new BigDecimal("12.3400")), "key");
        assertEquals(77L, result.get("evidenceId"));
        verify(jdbc).update(contains("UPDATE cashflow_account_opening"), any(Object[].class));
        verify(jdbc).update(contains("INSERT INTO cashflow_opening_confirmation"), any(Object[].class));
        verify(audit).log(eq("cashflow_account_opening"), eq(42L), eq("测试户"),
                eq("finance_confirm"), contains("evidenceId=77"));
    }

    @Test
    void historyRequiresReadPermissionBeforeDatabaseAccess() {
        when(users.can("cashflow.read")).thenReturn(false);
        assertThrows(SecurityException.class, () -> service.history(42));
        verifyNoInteractions(jdbc);
    }

    @Test
    void historyReturnsOnlyRequestedOpeningAfterScopeCheck() {
        when(users.can("cashflow.read")).thenReturn(true);
        when(jdbc.queryForObject(contains("SELECT COUNT(*) FROM cashflow_account_opening"),
                eq(Integer.class), eq(42L))).thenReturn(1);
        when(jdbc.queryForList(contains("FROM cashflow_opening_confirmation"), eq(42L)))
                .thenReturn(List.of(Map.of("id", 77L, "evidenceRef", "BANK-001")));
        assertEquals("BANK-001", service.history(42).getFirst().get("evidenceRef"));
    }
}
