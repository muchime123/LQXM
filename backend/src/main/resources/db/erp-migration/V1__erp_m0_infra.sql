-- =====================================================================
-- ERP 独立 schema 基建（工作包 5 / 里程碑 M0）
-- ---------------------------------------------------------------------
-- 依据：docs/ERP技术方案映射-20260917.md §3 公共列、§5.3 幂等接入、§7.1 审计、
--       §9 M0 交付清单（双 DataSource + Flyway(erp_flyway_history) + 公共列基类
--       + ErpErrorCode + idem 模板 + row_change_log）
--
-- 边界（DA1 / DA5 / B20）：本 schema 只承载 ERP 正式数据。
--   * 不建任何指向 product_archive 的物理外键（跨 schema 引用靠应用校验 + 对账任务）。
--   * 不建任何 h3_% 表，不写任何氚云来源数据（历史区→正式区唯一通道为 external_id_map，
--     且仅 CONFIRMED 可引用）。
--
-- 迁移历史表：erp_flyway_history（与主库 flyway_schema_history 分离，互不干预）
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. 请求幂等（契约 §8 / B15）
--    写动作（submit / approve / post / reverse / refund / allocate）统一拦截
--    X-Request-Key：命中且 payload_hash 一致 → 返回原 result_ref；
--    命中但 hash 不一致 → IDEMPOTENCY_PAYLOAD_CONFLICT；
--    未命中 → 在**同一事务**内执行业务并写本表（事务回滚则键不存在，可安全重试）。
--
--    与导入管线的「来源哈希幂等」是两层：请求键管 HTTP 重试，业务唯一键管业务重放。
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS idem_request (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    request_key   VARCHAR(100) NOT NULL COMMENT '调用方请求键（X-Request-Key 或 body.requestKey）',
    action        VARCHAR(64)  NOT NULL COMMENT '动作标识，如 delivery.post / refund.confirm',
    payload_hash  CHAR(64)     NOT NULL COMMENT '请求载荷 sha256（十六进制），用于识别同键不同内容',
    result_ref    VARCHAR(200) NULL COMMENT '首次执行的结果引用（单据 id / 单号），供重试原样返回',
    created_by    BIGINT       NULL COMMENT '发起人 employee id',
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_idem_request (request_key),
    KEY idx_idem_action (action),
    KEY idx_idem_created (created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci
  COMMENT = 'ERP 请求级幂等（HTTP 重试防护）';

-- ---------------------------------------------------------------------
-- 2. 行级变更日志（方案 §7.1：ERP 不用 audit_logs，改 row_change_log）
--    写入路径统一走 Service 基类；绕过基类的原生 SQL 更新不留痕
--    （实施期加 ArchUnit 规则静态检查，见方案 §11.5）。
--    changed_fields 保存 {字段: {from, to}}，与约定 M12 的状态机迁移审计对齐。
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS row_change_log (
    id             BIGINT        NOT NULL AUTO_INCREMENT,
    target_table   VARCHAR(64)   NOT NULL COMMENT '被改动的表名（erp schema 内）',
    target_id      BIGINT        NOT NULL COMMENT '被改动的行主键',
    action         VARCHAR(20)   NOT NULL COMMENT 'INSERT / UPDATE / DELETE',
    changed_fields JSON          NULL COMMENT '{"字段":{"from":…,"to":…}}；INSERT/DELETE 记关键字段快照',
    reason_code    VARCHAR(50)   NULL COMMENT '结构化原因码（调整/冲销等受控流程必填）',
    change_reason  VARCHAR(1000) NULL COMMENT '自然语言原因（停用/合并/关键变更必填）',
    operator_id    BIGINT        NULL COMMENT '操作人 employee id',
    operator_name  VARCHAR(100)  NULL COMMENT '操作人姓名快照（不随主数据改名而变）',
    request_key    VARCHAR(100)  NULL COMMENT '关联请求键，可与 idem_request 对齐',
    trace_id       VARCHAR(64)   NULL COMMENT '链路标识，便于跨表串同一业务动作',
    changed_at     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_rcl_target (target_table, target_id),
    KEY idx_rcl_changed_at (changed_at),
    KEY idx_rcl_operator (operator_id),
    KEY idx_rcl_trace (trace_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci
  COMMENT = 'ERP 行级变更审计（含操作人、原因、前后值）';
