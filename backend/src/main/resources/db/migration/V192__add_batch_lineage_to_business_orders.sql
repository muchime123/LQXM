-- P2-E 候选批次血缘：business_orders 批次链路字段
--
-- 用途：业务单上记录其候选来源批次与裁决确认批次，便于：
--   1) 业务单详情抽屉展示「批次链路」区块（来源/匹配/时间/操作人）
--   2) 批次血缘追溯抽屉从批次维度反向查到所有受影响的业务单
--   3) 撤销/重算候选时识别哪些业务单已通过本批次合并（不可逆，仅做提示）
--
-- 字段语义：
--   source_batch_id     生成候选的批次（rebuildCandidates 写入 source_batch_id 时同步回填到候选涉及的业务单）
--                       候选生成不修改业务单时为 NULL（候选只是建议，未触发业务单创建/更新）
--   match_batch_id     候选确认时所在批次（doDecide 时回填，表示业务单通过该批次完成匹配）
--   source_batch_at    source_batch_id 对应批次的完成时间，用于排序展示
--   match_batch_at     match_batch_id 对应批次的完成时间，便于追溯确认时间
--   match_operator     裁决操作人（与 match_batch_id 同时回填，复用候选 decided_by）
--
-- 注意：业务单可能被多个批次先后影响（先候选确认，后又被同对 SUPERSEDED 但已 CONFIRMED 保留），
--       字段只记录最近一次 match_batch_id（最新匹配批次）。

ALTER TABLE business_orders
  ADD COLUMN source_batch_id BIGINT NULL DEFAULT NULL COMMENT '候选来源批次 ID（生成候选时回填，便于血缘追溯）' AFTER match_status,
  ADD COLUMN match_batch_id BIGINT NULL DEFAULT NULL COMMENT '匹配裁决批次 ID（候选确认时回填，记录业务单通过哪批次完成匹配）' AFTER source_batch_id,
  ADD COLUMN source_batch_at DATETIME NULL DEFAULT NULL COMMENT '来源批次完成时间' AFTER match_batch_id,
  ADD COLUMN match_batch_at DATETIME NULL DEFAULT NULL COMMENT '匹配批次完成时间' AFTER source_batch_at,
  ADD COLUMN match_operator VARCHAR(100) NULL DEFAULT NULL COMMENT '匹配裁决操作人（与 match_batch_id 同时回填）' AFTER match_batch_at,
  ADD KEY idx_bo_source_batch (source_batch_id),
  ADD KEY idx_bo_match_batch (match_batch_id);
