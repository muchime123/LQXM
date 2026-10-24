package com.liqiang.archive.service;

import com.liqiang.archive.infra.ActorResolver;
import com.liqiang.archive.infra.IdempotencyService;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.math.BigDecimal;

/** 年度期初管理的只读基础；写入须经过独立权限、幂等和审计。 */
@Service
@RequiredArgsConstructor
public class CashflowOpeningService {
    private final JdbcTemplate jdbc;
    private final IdempotencyService idempotency;
    private final ActorResolver actors;
    private final AuditLogService audit;

    public Map<String, Object> createManual(long accountId, int year, BigDecimal amount,
                                            String basis, String note, String requestKey) {
        if (accountId <= 0 || year < 2027 || year > 9999) throw new IllegalArgumentException("人工期初仅支持 2027 年及以后");
        if (amount == null || amount.scale() > 4 || amount.precision() > 18)
            throw new IllegalArgumentException("金额精度无效");
        if (basis == null || basis.isBlank() || basis.trim().length() > 255)
            throw new IllegalArgumentException("期初依据不能为空且不得超过 255 字");
        if (note != null && note.length() > 255) throw new IllegalArgumentException("备注不得超过 255 字");
        var actor = actors.current();
        var payload = Map.of("accountId", accountId, "year", year, "amount", amount,
                "basis", basis.trim(), "note", note == null ? "" : note);
        idempotency.execute(actor, requestKey, "cashflow.opening.manual", payload, () -> {
            var names = jdbc.queryForList("SELECT account_name FROM h3_cash_accounts WHERE id=? AND external_source='H3YUN' AND status='ACTIVE'", String.class, accountId);
            if (names.isEmpty()) throw new IllegalArgumentException("账户不存在或已停用");
            try {
                jdbc.update("""
                        INSERT INTO cashflow_account_opening(account_id,fiscal_year,amount,source,active,finance_confirmed,basis,note)
                        VALUES(?,?,?,'MANUAL_OPENING',1,0,?,?)
                        """, accountId, year, amount, basis.trim(), note);
            } catch (DuplicateKeyException e) {
                throw new IllegalStateException("该账户该年度已有期初，不能覆盖；请走更正流程");
            }
            Long openingId = jdbc.queryForObject("SELECT id FROM cashflow_account_opening WHERE account_id=? AND fiscal_year=?", Long.class, accountId, year);
            audit.log("cashflow_account_opening", openingId, names.getFirst(), "manual_create",
                    "actorId=" + actor.employeeId() + "; year=" + year + "; amount=" + amount + "; basis=" + basis.trim());
            return String.valueOf(openingId);
        });
        return get(accountId, year);
    }

    public Map<String, Object> get(long accountId, int year) {
        if (accountId <= 0 || year < 2026 || year > 9999) throw new IllegalArgumentException("账户或年度无效");
        var rows = jdbc.queryForList("""
                SELECT a.id AS accountId,a.account_name AS accountName,a.status AS accountStatus,
                       o.id AS openingId,o.amount,o.source,o.active,o.finance_confirmed AS financeConfirmed,
                       o.basis,o.note,o.confirmed_by AS confirmedBy,o.confirmed_at AS confirmedAt,
                       o.created_at AS createdAt
                  FROM h3_cash_accounts a
                  LEFT JOIN cashflow_account_opening o ON o.account_id=a.id AND o.fiscal_year=?
                 WHERE a.id=? AND a.external_source='H3YUN'
                """, year, accountId);
        if (rows.isEmpty()) throw new IllegalArgumentException("账户不存在");
        Map<String, Object> result = new LinkedHashMap<>(rows.getFirst());
        result.put("fiscalYear", year);
        result.put("openingStatus", result.get("openingId") == null ? "MISSING"
                : ((Number) result.get("active")).intValue() == 1 ? "ACTIVE" : "CANDIDATE");
        return result;
    }
}
