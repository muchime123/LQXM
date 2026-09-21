package com.liqiang.archive.erp;

import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
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
 *   <li>actor / requestKey / action 必填，缺失直接拒绝，不能绕过事务，也不存在「无键直通」分支；</li>
 *   <li>键已存在且 action、payload_hash 一致 → 返回首次 result_ref，业务体不重复执行；</li>
 *   <li>键已存在但 action 或 payload_hash 不一致 → 抛 {@link ErpErrorCode#IDEMPOTENCY_PAYLOAD_CONFLICT}
 *       （同键换动作同样算冲突，不能借旧键拿到旧动作的结果）；</li>
 *   <li>键不存在 → 在**同一事务**内执行业务并写入 idem_request（B15：事务回滚则键也不存在，可安全重试）。</li>
 * </ol>
 *
 * <p><b>请求键大小写敏感</b>：比较走 {@code BINARY request_key = BINARY ?}，不依赖库默认排序规则
 * （字典 §2「编码是否区分大小写必须显式决定，不能依赖数据库默认排序规则」同理适用）。
 *
 * <p><b>重放不替代授权</b>：命中重放时本类只回放首次结果，不做业务授权判断。
 * 调用方必须在调用前完成身份、动作与数据范围授权（含重放路径），且不得把重放当成
 * 「该用户有权看这个结果」的依据。若重放者与首次发起人不同，本类记 warn 日志但不拒绝——
 * 拒绝与否是授权策略，不是幂等语义，压在这里会让「换人重试」变成静默失败。
 *
 * <p>与导入管线的「来源哈希幂等」是两层，二者缺一不可：请求键防 HTTP 重试，
 * 业务唯一键防业务语义上的重复录入。
 *
 * <p><b>⚠️ 绑定纪律（首次接入业务写路径时发现，勿改回）：本模板必须与「业务写入」绑定
 * 同一个 DataSource 与同一个事务管理器。</b>否则 B15 立刻退化——两条连接各自成事务，
 * 「业务回滚则键也不存在」与「键冲突则业务回滚」两条都失效，表现为并发下业务写进去了、
 * 幂等键没进去（或反之）。制造域等主库业务写路径用 {@code archiveIdempotencyService}
 * （绑定主库，幂等表以 {@code erp.idem_request} 限定名访问，同一 MySQL 实例下仍是一条连接一个事务）；
 * erp schema 内的业务写路径用 {@code erpIdempotencyService}。两者是**同一份实现的两个绑定**，
 * 不复制逻辑（守「唯一实现」不变量）。
 */
@Slf4j
public class ErpIdempotencyService {

    /** 幂等表名：erp 内业务用 {@code idem_request}；主库业务用 {@code erp.idem_request}。 */
    private final String idemTable;
    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;

    /** erp schema 内业务写入的默认绑定（幂等表取当前 schema）。 */
    public ErpIdempotencyService(JdbcTemplate jdbcTemplate, PlatformTransactionManager transactionManager) {
        this(jdbcTemplate, transactionManager, "idem_request");
    }

    /**
     * @param idemTable 幂等表名，允许 {@code 表名} 或 {@code schema.表名} 两种形态（防注入白名单校验）
     */
    public ErpIdempotencyService(JdbcTemplate jdbcTemplate, PlatformTransactionManager transactionManager,
                                 String idemTable) {
        if (idemTable == null || !idemTable.matches("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)?")) {
            throw new ErpBusinessException(ErpErrorCode.INVALID_ARGUMENT, "幂等表名不合法：" + idemTable);
        }
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.idemTable = idemTable;
    }

    /**
     * 幂等执行。{@code work} 为业务事务体，返回结果引用（单据 id / 单号）。
     *
     * 调用方必须先完成身份、动作及数据范围授权（包括重放），业务体只能写同一 ERP 事务资源。
     * 本模板不包裹远程支付或其他不可回滚的外部副作用。
     * @param actor      动作发起人（必填，写入 idem_request.created_by 供追溯；见 {@link ErpActor}）
     * @param requestKey 调用方请求键（X-Request-Key 或 body.requestKey），必填，大小写敏感
     * @param action     动作标识，如 {@code delivery.post}
     * @param payload    参与哈希的请求内容（建议传业务入参 DTO）
     * @param work       业务执行体，返回结果引用
     */
    public String execute(ErpActor actor, String requestKey, String action, Object payload, Supplier<String> work) {
        if (actor == null) {
            throw new ErpBusinessException(ErpErrorCode.INVALID_ARGUMENT,
                    "缺少操作人：写动作必须携带发起人身份，不能匿名提交", "actor");
        }
        validateToken(requestKey, 100, "requestKey");
        validateToken(action, 64, "action");
        if (work == null) throw new ErpBusinessException(ErpErrorCode.INVALID_ARGUMENT, "缺少业务执行体");
        String payloadHash = hash(payload);

        List<Map<String, Object>> existing = jdbcTemplate.queryForList(
                "SELECT action, payload_hash, result_ref, created_by FROM " + idemTable
                        + " WHERE BINARY request_key = BINARY ?",
                requestKey);
        if (!existing.isEmpty()) {
            Map<String, Object> first = existing.get(0);
            String storedHash = String.valueOf(first.get("payload_hash"));
            Object resultRef = first.get("result_ref");
            if (!action.equals(first.get("action")) || !storedHash.equals(payloadHash)) {
                throw new ErpBusinessException(ErpErrorCode.IDEMPOTENCY_PAYLOAD_CONFLICT,
                        "请求键 " + requestKey + " 已用于内容不同的另一次提交",
                        null, Map.of("requestKey", requestKey, "action", action));
            }
            Long firstActor = toLong(first.get("created_by"));
            if (firstActor != null && firstActor.longValue() != actor.employeeId()) {
                // 不拒绝：换人重试是合法场景，是否放行属授权策略。但必须留痕，便于事后核查。
                log.warn("ERP 幂等重放由非首次发起人触发：requestKey={} action={} 首次发起人={} 本次={}",
                        requestKey, action, firstActor, actor.employeeId());
            }
            log.info("ERP 幂等命中：requestKey={} action={} actor={} → 复用结果 {}",
                    requestKey, action, actor.employeeId(), resultRef);
            return resultRef == null ? null : String.valueOf(resultRef);
        }

        return transactionTemplate.execute(status -> {
            String resultRef = work.get();
            try {
                jdbcTemplate.update(
                        "INSERT INTO " + idemTable
                                + "(request_key, action, payload_hash, result_ref, created_by) VALUES(?,?,?,?,?)",
                        requestKey, action, payloadHash, resultRef, actor.employeeId());
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

    private static Long toLong(Object value) {
        if (value == null) return null;
        if (value instanceof Number n) return n.longValue();
        try {
            return Long.valueOf(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
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
