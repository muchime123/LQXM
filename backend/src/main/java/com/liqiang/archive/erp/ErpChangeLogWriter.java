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
 */
@Slf4j
@Component
public class ErpChangeLogWriter {

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

    public void write(Entry entry) {
        String changedJson = null;
        if (entry.changedFields() != null && !entry.changedFields().isEmpty()) {
            try {
                changedJson = objectMapper.writeValueAsString(entry.changedFields());
            } catch (Exception e) {
                // 审计写入不得因序列化失败而吞掉业务结果：降级为摘要文本并告警
                log.warn("row_change_log 的 changed_fields 序列化失败，降级为文本摘要：{}", e.getMessage());
                changedJson = "{\"_serializationError\":\"" + e.getClass().getSimpleName() + "\"}";
            }
        }
        erpJdbcTemplate.update(INSERT_SQL,
                entry.targetTable(), entry.targetId(), entry.action(), changedJson,
                entry.reasonCode(), entry.changeReason(), entry.operatorId(), entry.operatorName(),
                entry.requestKey(), entry.traceId());
    }
}
