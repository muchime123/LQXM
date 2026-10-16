-- 本地跟单处置，不改变氚云生产任务单原始状态。
ALTER TABLE production_tracking_state
  ADD COLUMN stopped_at DATETIME NULL,
  ADD COLUMN stopped_reason VARCHAR(500) NULL,
  ADD COLUMN stopped_by_name VARCHAR(100) NULL;
