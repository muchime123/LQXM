-- 编排阶段名称如 ORCH_REFRESH_OUTBOUND_SNAPSHOTS 超过旧 VARCHAR(30)。
-- 保留完整阶段名，便于前端展示和故障追踪。
ALTER TABLE h3_import_batches
    MODIFY COLUMN progress_stage VARCHAR(64) NOT NULL DEFAULT 'UPLOAD';
