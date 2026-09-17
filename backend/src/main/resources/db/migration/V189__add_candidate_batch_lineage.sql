-- P1 候选批次血缘：两张候选表加 source_batch_id，让前端能按"本次导入产生"过滤候选
-- 血缘语义：source_batch_id = "匹配引擎运行的那次导入批次"，不是"生产/出库各自的导入批次"
-- 历史候选 source_batch_id = NULL，前端归入"无批次血缘"组

ALTER TABLE business_match_candidates
  ADD COLUMN source_batch_id BIGINT NULL COMMENT '生成该候选的导入批次ID（手动触发为NULL）',
  ADD KEY idx_bmc_batch (source_batch_id);

ALTER TABLE receipt_match_candidates
  ADD COLUMN source_batch_id BIGINT NULL COMMENT '生成该候选的导入批次ID',
  ADD KEY idx_rmc_batch (source_batch_id);
