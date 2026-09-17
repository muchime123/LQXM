-- P2-C 候选批次血缘：跨批次去重与 SUPERSEDED 状态
-- 给 business_match_candidates 和 receipt_match_candidates 的 status 字段注释扩展 SUPERSEDED 取值。
-- status 取值现在为：PENDING（待裁决）/CONFIRMED（已确认）/REJECTED（已驳回）/SUPERSEDED（被新批次替代）。
-- SUPERSEDED 是逻辑状态，由 rebuildCandidates 在新批次 INSERT 后自动给同对的旧批次 PENDING 候选打标。
ALTER TABLE business_match_candidates
  MODIFY COLUMN status VARCHAR(20) NOT NULL DEFAULT 'PENDING'
  COMMENT '候选状态：PENDING 待裁决 / CONFIRMED 已确认 / REJECTED 已驳回 / SUPERSEDED 被新批次替代';

ALTER TABLE receipt_match_candidates
  MODIFY COLUMN status VARCHAR(20) NOT NULL DEFAULT 'PENDING'
  COMMENT '候选状态：PENDING 待裁决 / CONFIRMED 已确认 / REJECTED 已驳回 / SUPERSEDED 被新批次替代';
