package com.liqiang.archive.erp;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.LinkedHashMap;
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
 *
 * <p><b>绑定纪律（首次接入业务写路径时确立）</b>：与 {@code ErpIdempotencyService} 同理，
 * 必须与业务写入绑定同一 DataSource 与事务管理器。主库业务（如制造域）用
 * {@code archiveAuditWriter}，审计表以 {@code erp.row_change_log} 限定名写入同一事务；
 * erp 内业务用 {@code erpAuditWriter}。
 *
 * <p><b>target_table 口径</b>：统一带 schema 限定名（{@code product_archive.manufacturing_operations}
 * / {@code erp.party}）。两侧表名可能重名，不带限定的裸表名会让「改了哪张表」产生歧义。
 */
@Slf4j
public class ErpChangeLogWriter {

    /** 序列化降级标记：changed_fields 无法序列化时写入的固定键，便于事后 SQL 检索这类行并补数。 */
    public static final String SERIALIZATION_ERROR_KEY = "_serializationError";

    private final String logTable;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    /** erp schema 内业务写入的默认绑定（审计表取当前 schema）。 */
    public ErpChangeLogWriter(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this(jdbcTemplate, objectMapper, "row_change_log");
    }

    /**
     * @param logTable 审计表名，允许 {@code 表名} 或 {@code schema.表名} 两种形态（防注入白名单校验）
     */
    public ErpChangeLogWriter(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper, String logTable) {
        if (logTable == null || !logTable.matches("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)?")) {
            throw new ErpBusinessException(ErpErrorCode.INVALID_ARGUMENT, "审计表名不合法：" + logTable);
        }
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.logTable = logTable;
        // 注入的 mapper 未必注册了 Java 8 时间模块（非 Spring 调用方最常见）。缺模块时
        // changed_fields 里只要有一个 LocalDateTime/LocalDate，整条载荷就降级成
        // {_serializationError:…}——审计行还在，但「改前改后是什么」静默丢失。
        // 制造执行段真库验证时实测到该退化（报工 reportedAt / 收货 completedAt 都带时间）。
        // 这里按 classpath 补齐模块：Spring 容器传入的 mapper 已注册全部模块，此调用对其等价于空操作。
        objectMapper.findAndRegisterModules();
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

    /**
     * 拼装 changed_fields：{@code changes("reportNo", no, "quantity", qty, …)}。
     *
     * <p>为什么不用 {@code Map.of}：它的键值都不接受 null，而审计字段天然大量可选
     * （超额原因、备注、冲销前值…），用 {@code Map.of} 会逼调用方为每个可空字段造占位值。
     * 本方法**自动跳过 null 值的键**——「没有值」与「值为空字符串」在审计里必须可区分。
     */
    public static Map<String, Object> changes(Object... keyValues) {
        if (keyValues == null || keyValues.length % 2 != 0) {
            throw new ErpBusinessException(ErpErrorCode.INVALID_ARGUMENT,
                    "changes() 需要成对的键值参数", "changedFields");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            if (keyValues[i] == null) {
                throw new ErpBusinessException(ErpErrorCode.INVALID_ARGUMENT,
                        "changes() 的键不能为空", "changedFields");
            }
            if (keyValues[i + 1] != null) {
                result.put(String.valueOf(keyValues[i]), keyValues[i + 1]);
            }
        }
        return result;
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
                //
                // ⚠️ 注意 java.time 不在此列：构造器已 objectMapper.findAndRegisterModules()，
                // 故 LocalDate/LocalDateTime 不会走到这里（见 ErpChangeLogWriterTest 的回归断言）。
                // 能走到这里的只有「真的无法序列化」的内容（如 getter 抛异常的匿名对象）。
                log.warn("row_change_log 的 changed_fields 序列化失败，降级为文本摘要：{}", e.getMessage());
                changedJson = "{\"" + SERIALIZATION_ERROR_KEY + "\":\"" + e.getClass().getSimpleName() + "\"}";
            }
        }
        jdbcTemplate.update(
                "INSERT INTO " + logTable + "(target_table, target_id, action, changed_fields,"
                        + " reason_code, change_reason, operator_id, operator_name,"
                        + " request_key, trace_id) VALUES(?,?,?,?,?,?,?,?,?,?)",
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
