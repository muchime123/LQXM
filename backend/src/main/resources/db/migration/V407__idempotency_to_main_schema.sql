-- =====================================================================
-- 2026-10-23：请求幂等表迁回主库（erp.idem_request → product_archive.request_idempotency）
-- ---------------------------------------------------------------------
-- 背景：生产跟单等写动作经通用幂等模板防重复提交（原绑定 erp schema 的 idem_request）。
-- ERP 独立 schema 下线后，幂等能力保留并成为系统通用基础能力，表迁到主库。
--
-- 迁移策略：
--   1) 在主库创建 product_archive.request_idempotency（结构对齐 erp.idem_request）；
--   2) 若遗留 erp.idem_request 仍存在（本次为存量库），把历史请求键一并搬入，
--      使旧请求的重试仍能命中（防重复提交不回退）；若不存在（全新部署），则跳过搬数。
--
-- 原则：历史 Flyway 不回删（erp-migration/V1 保留至备份后删除 erp schema），只用本迁移做承接。
-- =====================================================================
CREATE TABLE IF NOT EXISTS request_idempotency (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    request_key   VARCHAR(100) NOT NULL COMMENT '调用方请求键（X-Request-Key 或 body.requestKey）',
    action        VARCHAR(64)  NOT NULL COMMENT '动作标识，如 tracking.enroll',
    payload_hash  CHAR(64)     NOT NULL COMMENT '请求载荷 sha256（十六进制），用于识别同键不同内容',
    result_ref    VARCHAR(200) NULL COMMENT '首次执行的结果引用（单据 id / 单号），供重试原样返回',
    created_by    BIGINT       NULL COMMENT '发起人 employee id',
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_request_idempotency (request_key),
    KEY idx_idem_action (action),
    KEY idx_idem_created (created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '系统通用请求级幂等（HTTP 重试防护）';

-- 存量搬数：仅当遗留 erp.idem_request 存在时执行（全新部署该表不存在，跳过）。
SET @has_legacy_idem = (
    SELECT COUNT(*) FROM information_schema.tables
    WHERE table_schema = 'erp' AND table_name = 'idem_request');
SET @migrate_sql = IF(@has_legacy_idem > 0,
    'INSERT INTO request_idempotency(request_key, action, payload_hash, result_ref, created_by, created_at)
     SELECT request_key, action, payload_hash, result_ref, created_by, created_at FROM erp.idem_request',
    'SELECT 1');
PREPARE migrate_stmt FROM @migrate_sql;
EXECUTE migrate_stmt;
DEALLOCATE PREPARE migrate_stmt;
