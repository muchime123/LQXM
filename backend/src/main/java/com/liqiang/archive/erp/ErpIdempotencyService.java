package com.liqiang.archive.erp;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * ERP 请求级幂等模板（技术方案映射 §5.3 / DA7 / B15）。
 *
 * <p>写动作（submit / approve / post / reverse / refund / allocate）统一经
 * {@link #execute} 包裹：
 * <ol>
 *   <li>requestKey/action 必填，缺失直接拒绝，不能绕过事务；</li>
 *   <li>键已存在且 action、payload_hash 一致 → 返回首次 result_ref；</li>
 *   <li>键已存在但 hash 不一致 → 抛 {@link ErpErrorCode#IDEMPOTENCY_PAYLOAD_CONFLICT}；</li>
 *   <li>键不存在 → 在**同一事务**内执行业务并写入 idem_request（B15：事务回滚则键也不存在，可安全重试）。</li>
 * </ol>
 *
 * <p>与导入管线的「来源哈希幂等」是两层，二者缺一不可：请求键防 HTTP 重试，
 * 业务唯一键防业务语义上的重复录入。
 */
@Slf4j
@Service
public class ErpIdempotencyService {

    private final JdbcTemplate erpJdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    public ErpIdempotencyService(@Qualifier("erpJdbcTemplate") JdbcTemplate erpJdbcTemplate,
                                 @Qualifier("erpTransactionManager") PlatformTransactionManager erpTransactionManager) {
        this.erpJdbcTemplate = erpJdbcTemplate;
        this.transactionTemplate = new TransactionTemplate(erpTransactionManager);
    }

    /**
     * 幂等执行。{@code work} 为业务事务体，返回结果引用（单据 id / 单号）。
     *
     * 调用方必须先完成身份、动作及数据范围授权（包括重放），业务体只能写同一 ERP 事务资源。
     * 本模板不包裹远程支付或其他不可回滚的外部副作用。
     * @param requestKey 调用方请求键（X-Request-Key 或 body.requestKey），必填，大小写敏感
     * @param action     动作标识，如 {@code delivery.post}
     * @param payload    参与哈希的请求内容（建议传业务入参 DTO）
     * @param work       业务执行体，返回结果引用
     */
    public String execute(String requestKey, String action, Object payload, Supplier<String> work) {
        validateToken(requestKey, 100, "requestKey");
        validateToken(action, 64, "action");
        if (work == null) throw new ErpBusinessException(ErpErrorCode.INVALID_ARGUMENT, "缺少业务执行体");
        String payloadHash = hash(payload);

        List<Map<String, Object>> existing = erpJdbcTemplate.queryForList(
                "SELECT action, payload_hash, result_ref FROM idem_request WHERE BINARY request_key = BINARY ?", requestKey);
        if (!existing.isEmpty()) {
            String storedHash = String.valueOf(existing.get(0).get("payload_hash"));
            Object resultRef = existing.get(0).get("result_ref");
            if (!action.equals(existing.get(0).get("action")) || !storedHash.equals(payloadHash)) {
                throw new ErpBusinessException(ErpErrorCode.IDEMPOTENCY_PAYLOAD_CONFLICT,
                        "请求键 " + requestKey + " 已用于内容不同的另一次提交",
                        null, Map.of("requestKey", requestKey, "action", action));
            }
            log.info("ERP 幂等命中：requestKey={} action={} → 复用结果 {}", requestKey, action, resultRef);
            return resultRef == null ? null : String.valueOf(resultRef);
        }

        return transactionTemplate.execute(status -> {
            String resultRef = work.get();
            try {
                erpJdbcTemplate.update(
                        "INSERT INTO idem_request(request_key, action, payload_hash, result_ref) VALUES(?,?,?,?)",
                        requestKey, action, payloadHash, resultRef);
            } catch (DuplicateKeyException e) {
                // 并发同键：另一线程抢先写入 → 本次业务写入一并回滚，让调用方按键查询结果。
                status.setRollbackOnly();
                throw new ErpBusinessException(ErpErrorCode.IDEMPOTENCY_PAYLOAD_CONFLICT,
                        "同一请求键并发提交，请稍后按请求键查询结果",
                        null, Map.of("requestKey", requestKey, "action", action));
            }
            return resultRef;
        });
    }

    private static void validateToken(String value, int maxLength, String field) {
        if (value == null || value.isBlank() || value.length() > maxLength
                || !value.equals(value.strip()) || value.chars().anyMatch(Character::isISOControl)) {
            throw new ErpBusinessException(ErpErrorCode.INVALID_ARGUMENT,
                    field + " 必须非空、无首尾空白或控制字符，且长度不超过 " + maxLength, field);
        }
    }

    /** 计算规范化载荷的 sha256（十六进制小写）。委托 {@link ErpPayloadHasher}，便于脱离 Spring 上下文单测。 */
    public String hash(Object payload) {
        return ErpPayloadHasher.sha256(payload);
    }
}
