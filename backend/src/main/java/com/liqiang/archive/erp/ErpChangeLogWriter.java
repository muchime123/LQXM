package com.liqiang.archive.erp;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * ERP 行级变更日志写入器（技术方案映射 §7.1 / DA7 / M0）。
 *
 * <p>为什么不用现有 {@code audit_logs}：它不记操作人（单人时代遗留结构），
 * 而 ERP 的调整、冲销、主数据停用/合并都要求「谁改的、为什么改、改前改后是什么」。
 *
 * <p>⚠️ 纪律（方案 §11.5）：本类只在**写路径统一走 Service 基类**的前提下有效；
 * 绕过它直接发原生 SQL 的更新不会留痕。M4 将加 ArchUnit 静态规则检查写路径。
 *
 * <p>⚠️ 事务纪律（M0 收口）：本类**不自己开事务**，必须由调用方在同一 ERP 事务内调用，
 * 才能同时满足两条失败语义——业务失败 → 审计行一并回滚；审计写入失败 → 业务写入一并回滚。
 * 若调用方在事务外调用，第一条会退化成「业务失败但审计留了一条假记录」。
 *
 * <p>⚠️ 操作人必填（M0 收口）：{@link Entry#operatorId()} 为 null 直接拒绝。
 * 此前接受可空操作人，等于允许写入无法追责的审计行，与 DA7 的立项理由冲突。
 */
@Slf4j
@Component
public class ErpChangeLogWriter {

    /** 序列化降级标记：changed_fields 无法序列化时写入的固定键，便于事后 SQL 检索这类行并补数。 */
    public static final String SERIALIZATION_ERROR_KEY = "_serializationError";

    private static final String INSERT_SQL = """
            INSERT INTO row_change_log(target_table, target_id, action, changed_fields,
                                       reason_code, change_reason, operator_id, operator_name,
                                       request_key, trace_id)
            VALUES(?,?,?,?,?,?,?,?,?,?)
            """;

    private final JdbcTemplate erpJdbcTemplate;
    private final ObjectMapper objectMapper;

    public ErpChangeLogWriter(@Qualifier("erpJdbcTemplate") JdbcTemplate erpJdbcTemplate,
                              ObjectMapper objectMapper) {
        this.erpJdbcTemplate = erpJdbcTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * 一条变更记录。
     *
     * @param targetTable   erp schema 内的表名
     * @param targetId      被改动行主键
     * @param action        INSERT / UPDATE / DELETE
     * @param changedFields {@code {"字段":{"from":…,"to":…}}}；INSERT/DELETE 记关键字段快照
     * @param reasonCode    结构化原因码（受控流程如调整/冲销必填）
     * @param changeReason  自然语言原因（停用/合并/关键变更必填）
     * @param operatorId    操作人员工 id（必填，见 {@link ErpActor}）
     * @param operatorName  操作人姓名快照（不随主数据改名而变）
     */
    public record Entry(String targetTable,
                        long targetId,
                        String action,
                        Map<String, Object> changedFields,
                        String reasonCode,
                        String changeReason,
                        Long operatorId,
                        String operatorName,
                        String requestKey,
                        String traceId) {
    }

    /** 用发起人身份构造审计行，避免调用方自己拼 operatorId/operatorName 时漏填。 */
    public static Entry of(ErpActor actor, String targetTable, long targetId, String action,
                           Map<String, Object> changedFields, String reasonCode, String changeReason,
                           String requestKey, String traceId) {
        return new Entry(targetTable, targetId, action, changedFields, reasonCode, changeReason,
                actor == null ? null : actor.employeeId(),
                actor == null ? null : actor.name(),
                requestKey, traceId);
    }

    public void write(Entry entry) {
        if (entry == null) throw new ErpBusinessException(ErpErrorCode.INVALID_ARGUMENT, "缺少审计记录内容");
        if (entry.operatorId() == null || entry.operatorId() <= 0) {
            throw new ErpBusinessException(ErpErrorCode.INVALID_ARGUMENT,
                    "审计记录必须携带操作人：不接受可空操作人", "operatorId");
        }
        requireToken(entry.targetTable(), 64, "targetTable");
        requireToken(entry.action(), 20, "action");
        if (entry.targetId() <= 0) {
            throw new ErpBusinessException(ErpErrorCode.INVALID_ARGUMENT, "审计目标行主键必须为正数", "targetId");
        }
        String changedJson = null;
        if (entry.changedFields() != null && !entry.changedFields().isEmpty()) {
            try {
                changedJson = objectMapper.writeValueAsString(entry.changedFields());
            } catch (Exception e) {
                // 审计写入不得因序列化失败而吞掉业务结果：降级为摘要文本并告警
                // （该行可用 WHERE JSON_EXTRACT(changed_fields,'$._serializationError') IS NOT NULL 检索后补数）
                log.warn("row_change_log 的 changed_fields 序列化失败，降级为文本摘要：{}", e.getMessage());
                changedJson = "{\"" + SERIALIZATION_ERROR_KEY + "\":\"" + e.getClass().getSimpleName() + "\"}";
            }
        }
        erpJdbcTemplate.update(INSERT_SQL,
                entry.targetTable(), entry.targetId(), entry.action(), changedJson,
                entry.reasonCode(), entry.changeReason(), entry.operatorId(), entry.operatorName(),
                entry.requestKey(), entry.traceId());
    }

    private static void requireToken(String value, int maxLength, String field) {
        if (value == null || value.isBlank() || value.length() > maxLength
                || !value.equals(value.strip()) || value.chars().anyMatch(Character::isISOControl)) {
            throw new ErpBusinessException(ErpErrorCode.INVALID_ARGUMENT,
                    field + " 必须非空、无首尾空白或控制字符，且长度不超过 " + maxLength, field);
        }
    }
}
