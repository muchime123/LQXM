-- P2-A 候选批次血缘增强：批次维度候选统计缓存
-- 给 h3_import_batches 加 5 个候选统计字段，由编排结束后 BatchCandidateStatsService.refresh(batchId) 回填。
-- 物理字段而非 preview_json：批次列表是高频查询页，避免每次反序列化 JSON；可建索引支持排序。
ALTER TABLE h3_import_batches
  ADD COLUMN candidate_total INT NULL COMMENT '该批次生成的候选总数（生产↔出库 + 收款兜底，含已裁决）',
  ADD COLUMN candidate_pending INT NULL COMMENT '当前待处理候选数',
  ADD COLUMN candidate_confirmed INT NULL COMMENT '已确认候选数',
  ADD COLUMN candidate_rejected INT NULL COMMENT '已驳回候选数',
  ADD COLUMN candidate_stats_updated_at DATETIME NULL COMMENT '统计刷新时间';
