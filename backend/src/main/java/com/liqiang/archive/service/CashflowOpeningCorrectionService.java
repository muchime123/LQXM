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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/** B-3.3: an opening correction is requested first, then applied atomically. */
@Service
@RequiredArgsConstructor
public class CashflowOpeningCorrectionService {
    private final JdbcTemplate jdbc;
    private final IdempotencyService idempotency;
    private final ActorResolver actors;
    private final CurrentUserService users;
    private final AuditLogService audit;

    public record Request(BigDecimal expectedOldAmount, BigDecimal newAmount, String reason, String evidenceRef) {}

    public Map<String, Object> request(long openingId, Request request, String key) {
        if (openingId <= 0 || request == null) throw new IllegalArgumentException("更正请求无效");
        money(request.expectedOldAmount());
        money(request.newAmount());
        String reason = required(request.reason(), "更正原因");
        String evidence = required(request.evidenceRef(), "外部依据编号");
        authorize(true);
        opening(openingId, false); // Replay still checks that this opening is within the authorized source.
        var actor = actors.current();
        var payload = Map.of("openingId", openingId, "expectedOldAmount", request.expectedOldAmount(),
                "newAmount", request.newAmount(), "reason", reason, "evidenceRef", evidence);
        Supplier<String> work = () -> {
            var row = opening(openingId, true);
            if (!CashflowOpeningService.isActive(row.get("active"))) conflict("候选期初尚未生效");
            BigDecimal old = (BigDecimal) row.get("amount");
            if (old.compareTo(request.expectedOldAmount()) != 0) conflict("期初金额已变化，请重新预览");
            if (old.compareTo(request.newAmount()) == 0) conflict("新旧金额相同，无需更正");
            Integer pending = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM cashflow_opening_correction
                     WHERE opening_id=? AND status='PENDING'
                    """, Integer.class, openingId);
            if (pending != null && pending > 0) conflict("该期初已有待应用更正");
            boolean confirmed = CashflowOpeningService.isActive(row.get("finance_confirmed"));
            Long confirmationId = confirmed ? latestConfirmation(openingId) : null;
            if (confirmed && confirmationId == null) conflict("已确认期初缺少确认历史，请先复核");
            jdbc.update("""
                    INSERT INTO cashflow_opening_correction
                    (opening_id,old_amount,new_amount,original_finance_confirmed,original_confirmation_id,
                     reason,evidence_ref,requested_by,requested_by_name,request_key)
                    VALUES(?,?,?,?,?,?,?,?,?,?)
                    """, openingId, old, request.newAmount(), confirmed ? 1 : 0, confirmationId,
                    reason, evidence, actor.employeeId(), actor.name(), key);
            Long id = jdbc.queryForObject("SELECT id FROM cashflow_opening_correction WHERE BINARY request_key=BINARY ?",
                    Long.class, key);
            audit.log("cashflow_account_opening", openingId, (String) row.get("account_name"),
                    "correction_request", "actorId=" + actor.employeeId() + "; correctionId=" + id
                            + "; old=" + old + "; new=" + request.newAmount() + "; reason=" + reason);
            return String.valueOf(id);
        };
        String id = executeWithReplay(actor, key, "cashflow.opening.correction.request", payload, work);
        return toClient(correction(Long.parseLong(id)));
    }

    public Map<String, Object> apply(long correctionId, String key) {
        if (correctionId <= 0) throw new IllegalArgumentException("更正记录无效");
        authorize(true);
        var candidate = correction(correctionId); // Also checks the linked account's source before replay.
        long openingId = ((Number) candidate.get("openingId")).longValue();
        var actor = actors.current();
        Supplier<String> work = () -> {
            var opening = opening(openingId, true);
            var correction = correction(correctionId, true);
            if (!"PENDING".equals(correction.get("status"))) conflict("该更正已应用");
            if (!CashflowOpeningService.isActive(opening.get("active"))) conflict("期初已停用");
            if (((BigDecimal) opening.get("amount")).compareTo((BigDecimal) correction.get("oldAmount")) != 0
                    || CashflowOpeningService.isActive(opening.get("finance_confirmed"))
                    != CashflowOpeningService.isActive(correction.get("originalFinanceConfirmed")))
                conflict("期初金额或确认状态已变化，请重新申请");
            Long originalId = correction.get("originalConfirmationId") == null ? null
                    : ((Number) correction.get("originalConfirmationId")).longValue();
            if (originalId != null && !java.util.Objects.equals(originalId, latestConfirmation(openingId)))
                conflict("期初确认历史已变化，请重新申请");
            int changed = jdbc.update("""
                    UPDATE cashflow_account_opening
                       SET amount=?,finance_confirmed=0,confirmed_by=NULL,confirmed_at=NULL
                     WHERE id=? AND active=1 AND amount=? AND finance_confirmed=?
                    """, correction.get("newAmount"), openingId, correction.get("oldAmount"),
                    CashflowOpeningService.isActive(correction.get("originalFinanceConfirmed")) ? 1 : 0);
            if (changed != 1) conflict("期初已变化，请重新申请");
            changed = jdbc.update("""
                    UPDATE cashflow_opening_correction
                       SET status='APPLIED',applied_by=?,applied_by_name=?,applied_at=NOW(),apply_request_key=?
                     WHERE id=? AND status='PENDING'
                    """, actor.employeeId(), actor.name(), key, correctionId);
            if (changed != 1) conflict("更正状态已变化");
            audit.log("cashflow_account_opening", openingId, (String) opening.get("account_name"),
                    "correction_apply", "actorId=" + actor.employeeId() + "; correctionId=" + correctionId
                            + "; old=" + correction.get("oldAmount") + "; new=" + correction.get("newAmount"));
            return String.valueOf(correctionId);
        };
        String id = executeWithReplay(actor, key, "cashflow.opening.correction.apply",
                Map.of("correctionId", correctionId), work);
        return toClient(correction(Long.parseLong(id)));
    }

    public List<Map<String, Object>> history(long openingId) {
        if (openingId <= 0) throw new IllegalArgumentException("期初记录无效");
        authorize(false);
        opening(openingId, false);
        return jdbc.queryForList("""
                SELECT id,opening_id AS openingId,old_amount AS oldAmount,new_amount AS newAmount,
                       original_finance_confirmed AS originalFinanceConfirmed,
                       original_confirmation_id AS originalConfirmationId,reason,evidence_ref AS evidenceRef,
                       requested_by AS requestedBy,requested_by_name AS requestedByName,requested_at AS requestedAt,
                       applied_by AS appliedBy,applied_by_name AS appliedByName,applied_at AS appliedAt,status
                  FROM cashflow_opening_correction WHERE opening_id=? ORDER BY id DESC
                """, openingId).stream().map(CashflowOpeningCorrectionService::toClient).toList();
    }

    private Map<String, Object> correction(long id) { return correction(id, false); }

    private Map<String, Object> correction(long id, boolean lock) {
        var rows = jdbc.queryForList("""
                SELECT c.id,c.opening_id AS openingId,c.old_amount AS oldAmount,c.new_amount AS newAmount,
                       c.original_finance_confirmed AS originalFinanceConfirmed,
                       c.original_confirmation_id AS originalConfirmationId,c.reason,c.evidence_ref AS evidenceRef,
                       c.requested_by AS requestedBy,c.requested_by_name AS requestedByName,
                       c.requested_at AS requestedAt,c.applied_by AS appliedBy,
                       c.applied_by_name AS appliedByName,c.applied_at AS appliedAt,c.status
                  FROM cashflow_opening_correction c
                  JOIN cashflow_account_opening o ON o.id=c.opening_id
                  JOIN h3_cash_accounts a ON a.id=o.account_id AND a.external_source='H3YUN'
                 WHERE c.id=?
                """ + (lock ? " FOR UPDATE" : ""), id);
        if (rows.isEmpty()) throw new IllegalArgumentException("更正记录不存在");
        return rows.getFirst();
    }

    private Map<String, Object> opening(long id, boolean lock) {
        var rows = jdbc.queryForList("""
                SELECT o.id,o.amount,o.active,o.finance_confirmed,a.account_name
                  FROM cashflow_account_opening o
                  JOIN h3_cash_accounts a ON a.id=o.account_id AND a.external_source='H3YUN'
                 WHERE o.id=?
                """ + (lock ? " FOR UPDATE" : ""), id);
        if (rows.isEmpty()) throw new IllegalArgumentException("年度期初不存在");
        return rows.getFirst();
    }

    private Long latestConfirmation(long openingId) {
        return jdbc.query("SELECT id FROM cashflow_opening_confirmation WHERE opening_id=? ORDER BY id DESC LIMIT 1",
                rs -> rs.next() ? rs.getLong(1) : null, openingId);
    }

    private String executeWithReplay(com.liqiang.archive.infra.Actor actor, String key, String action,
                                     Object payload, Supplier<String> work) {
        try {
            return idempotency.execute(actor, key, action, payload, work);
        } catch (ResponseStatusException conflict) {
            if (conflict.getStatusCode().value() != 409) throw conflict;
            // Retry outside the rolled-back transaction: same-key races replay; other keys remain 409.
            return idempotency.execute(actor, key, action, payload, work);
        }
    }

    private void authorize(boolean write) {
        if (!users.can(write ? "cashflow.opening.correct" : "cashflow.read"))
            throw new SecurityException("没有年度期初更正权限");
        var scope = users.current().featureScopes().get("CASHFLOW");
        if (scope == null || !"ALL".equals(scope.scope()))
            throw new SecurityException("当前资金数据范围不允许操作账户期初");
    }

    private static void money(BigDecimal value) {
        if (value == null || value.scale() > 4 || value.precision() - value.scale() > 14)
            throw new IllegalArgumentException("金额超出 DECIMAL(18,4) 范围或小数超过 4 位");
    }

    private static String required(String value, String label) {
        if (value == null || value.isBlank() || value.trim().length() > 255
                || value.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException(label + "不能为空且长度不得超过 255 字");
        return value.trim();
    }

    private static void conflict(String message) {
        throw new ResponseStatusException(HttpStatus.CONFLICT, message);
    }

    private static Map<String, Object> toClient(Map<String, Object> source) {
        Map<String, Object> result = new LinkedHashMap<>(source);
        result.put("oldAmount", ((BigDecimal) source.get("oldAmount")).toPlainString());
        result.put("newAmount", ((BigDecimal) source.get("newAmount")).toPlainString());
        return result;
    }
}
