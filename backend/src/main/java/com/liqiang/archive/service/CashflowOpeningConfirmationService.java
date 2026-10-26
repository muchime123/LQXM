package com.liqiang.archive.service;

import com.liqiang.archive.infra.ActorResolver;
import com.liqiang.archive.infra.IdempotencyService;
import com.liqiang.archive.security.CurrentUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/** Confirms an opening amount against an externally retained finance voucher. */
@Service
@RequiredArgsConstructor
public class CashflowOpeningConfirmationService {
    private static final Set<String> EVIDENCE_TYPES = Set.of("BANK_STATEMENT", "CASH_COUNT", "OTHER");

    private final JdbcTemplate jdbc;
    private final IdempotencyService idempotency;
    private final ActorResolver actors;
    private final CurrentUserService users;
    private final AuditLogService audit;

    public record Request(String evidenceType, LocalDate balanceAsOfDate, BigDecimal verifiedBalance,
                          String evidenceRef, String note) {}

    public List<Map<String, Object>> history(long openingId) {
        if (openingId <= 0) throw new IllegalArgumentException("期初记录无效");
        if (!users.can("cashflow.read")) throw new SecurityException("没有资金日记账查看权限");
        var scope = users.current().featureScopes().get("CASHFLOW");
        if (scope == null || !"ALL".equals(scope.scope()))
            throw new SecurityException("当前资金数据范围不允许查看账户期初凭据");
        Integer exists = jdbc.queryForObject("""
                SELECT COUNT(*) FROM cashflow_account_opening o
                  JOIN h3_cash_accounts a ON a.id=o.account_id AND a.external_source='H3YUN'
                 WHERE o.id=?
                """, Integer.class, openingId);
        if (exists == null || exists == 0)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "年度期初不存在");
        return jdbc.queryForList("""
                SELECT id,opening_amount_snapshot AS openingAmountSnapshot,
                       evidence_type AS evidenceType,balance_as_of_date AS balanceAsOfDate,
                       verified_balance AS verifiedBalance,evidence_ref AS evidenceRef,
                       note,confirmed_by AS confirmedBy,confirmed_by_name AS confirmedByName,
                       confirmed_at AS confirmedAt
                  FROM cashflow_opening_confirmation
                 WHERE opening_id=? ORDER BY id DESC
                """, openingId);
    }

    public Map<String, Object> confirm(long openingId, Request request, String requestKey) {
        if (openingId <= 0 || request == null) throw new IllegalArgumentException("期初或核对请求无效");
        if (request.evidenceType() == null || !EVIDENCE_TYPES.contains(request.evidenceType()))
            throw new IllegalArgumentException("核对凭据类型无效");
        if (request.balanceAsOfDate() == null) throw new IllegalArgumentException("余额截至日期不能为空");
        BigDecimal verified = request.verifiedBalance();
        if (verified == null || verified.scale() > 4 || verified.precision() - verified.scale() > 14)
            throw new IllegalArgumentException("核对余额超出 DECIMAL(18,4) 范围或小数超过 4 位");
        String ref = requiredText(request.evidenceRef(), 255, "外部凭据编号");
        String note = requiredText(request.note(), 255, "核对说明");

        // Idempotency replay must not bypass either permission or data-scope checks.
        if (!users.can("cashflow.opening.confirm")) throw new SecurityException("没有年度期初财务确认权限");
        var scope = users.current().featureScopes().get("CASHFLOW");
        if (scope == null || !"ALL".equals(scope.scope()))
            throw new SecurityException("当前资金数据范围不允许确认账户期初");
        var actor = actors.current();
        Map<String, Object> payload = Map.of("openingId", openingId,
                "evidenceType", request.evidenceType(), "balanceAsOfDate", request.balanceAsOfDate(),
                "verifiedBalance", verified, "evidenceRef", ref, "note", note);

        Supplier<String> work = () -> {
            List<Map<String, Object>> rows = jdbc.queryForList("""
                    SELECT o.id,o.account_id,o.fiscal_year,o.amount,o.active,o.finance_confirmed,
                           a.account_name
                      FROM cashflow_account_opening o
                      JOIN h3_cash_accounts a ON a.id=o.account_id AND a.external_source='H3YUN'
                     WHERE o.id=? FOR UPDATE
                    """, openingId);
            if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "年度期初不存在");
            Map<String, Object> row = rows.getFirst();
            if (!CashflowOpeningService.isActive(row.get("active")))
                throw new ResponseStatusException(HttpStatus.CONFLICT, "候选期初尚未生效");
            if (CashflowOpeningService.isActive(row.get("finance_confirmed")))
                throw new ResponseStatusException(HttpStatus.CONFLICT, "该期初已经财务确认");
            int year = ((Number) row.get("fiscal_year")).intValue();
            LocalDate asOf = request.balanceAsOfDate();
            if (!asOf.equals(LocalDate.of(year - 1, 12, 31)) && !asOf.equals(LocalDate.of(year, 1, 1)))
                throw new IllegalArgumentException("余额截至日期须为上年末或本年初");
            BigDecimal amount = (BigDecimal) row.get("amount");
            if (amount.compareTo(verified) != 0)
                throw new ResponseStatusException(HttpStatus.CONFLICT, "凭据余额与当前期初金额不一致");

            int updated = jdbc.update("""
                    UPDATE cashflow_account_opening
                       SET finance_confirmed=1,confirmed_by=?,confirmed_at=NOW()
                     WHERE id=? AND active=1 AND finance_confirmed=0 AND amount=?
                    """, actor.employeeId(), openingId, amount);
            if (updated != 1) throw new ResponseStatusException(HttpStatus.CONFLICT, "期初状态已变化，请重新核对");
            jdbc.update("""
                    INSERT INTO cashflow_opening_confirmation
                    (opening_id,opening_amount_snapshot,evidence_type,balance_as_of_date,
                     verified_balance,evidence_ref,note,confirmed_by,confirmed_by_name,request_key)
                    VALUES(?,?,?,?,?,?,?,?,?,?)
                    """, openingId, amount, request.evidenceType(), asOf, verified, ref,
                    note, actor.employeeId(), actor.name(), requestKey);
            Long evidenceId = jdbc.queryForObject(
                    "SELECT id FROM cashflow_opening_confirmation WHERE BINARY request_key=BINARY ?",
                    Long.class, requestKey);
            audit.log("cashflow_account_opening", openingId, (String) row.get("account_name"),
                    "finance_confirm", "actorId=" + actor.employeeId() + "; year=" + year
                            + "; amount=" + amount + "; evidenceId=" + evidenceId);
            return String.valueOf(evidenceId);
        };
        String evidenceId;
        try {
            evidenceId = idempotency.execute(actor, requestKey, "cashflow.opening.confirm", payload, work);
        } catch (ResponseStatusException conflict) {
            if (conflict.getStatusCode().value() != 409) throw conflict;
            // The first transaction has ended before this read; same-key races can now replay.
            evidenceId = idempotency.execute(actor, requestKey, "cashflow.opening.confirm", payload, work);
        }
        Map<String, Object> result = new LinkedHashMap<>(jdbc.queryForMap("""
                SELECT o.id AS openingId,o.account_id AS accountId,o.fiscal_year AS fiscalYear,
                       o.amount,o.active,o.finance_confirmed AS financeConfirmed,
                       o.confirmed_by AS confirmedBy,o.confirmed_at AS confirmedAt,
                       c.id AS evidenceId,c.evidence_type AS evidenceType,
                       c.balance_as_of_date AS balanceAsOfDate,c.evidence_ref AS evidenceRef
                  FROM cashflow_opening_confirmation c
                  JOIN cashflow_account_opening o ON o.id=c.opening_id
                 WHERE c.id=? AND c.opening_id=?
                """, Long.parseLong(evidenceId), openingId));
        return result;
    }

    private static String requiredText(String raw, int max, String label) {
        if (raw == null || raw.isBlank()) throw new IllegalArgumentException(label + "不能为空");
        String value = raw.trim();
        if (value.length() > max || value.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException(label + "长度或内容不合法");
        return value;
    }
}
