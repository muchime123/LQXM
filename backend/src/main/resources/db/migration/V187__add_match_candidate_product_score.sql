-- 生产↔出库匹配优化（P1/P3/P5）：候选表增加产品相似度与出库数量快照
-- 产品相似度用于：候选生成门槛、置信度连续计分、候选列表排序与展示
ALTER TABLE business_match_candidates
    ADD COLUMN product_score INT NOT NULL DEFAULT 0 COMMENT '产品相似度 0-100（生产产品名与出库明细 bigram 命中率）' AFTER match_reason,
    ADD COLUMN outbound_qty_total DECIMAL(16,2) NULL COMMENT '候选出库明细数量合计（匹配快照）' AFTER product_score,
    ADD COLUMN outbound_item_count INT NOT NULL DEFAULT 0 COMMENT '候选出库明细条数（匹配快照）' AFTER outbound_qty_total;

CREATE INDEX idx_bmc_status_score ON business_match_candidates(status, product_score);
