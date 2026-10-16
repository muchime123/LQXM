-- 跟单结案确认：跟单员可在节点未全部完成时主动确认结束；状态按「closed_at」覆盖为「跟单已完成」，
-- 后续只要保留本次结案原因、结案人、结案时间，节点记录与生产交付结果保持原状。
-- 不改氚云生产任务单；语义与 V388 的本地停止处置对齐，但用「已完成」而非「停止」。
ALTER TABLE production_tracking_state
  ADD COLUMN closed_at DATETIME NULL,
  ADD COLUMN closed_by_name VARCHAR(100) NULL,
  ADD COLUMN closed_note VARCHAR(500) NULL;
