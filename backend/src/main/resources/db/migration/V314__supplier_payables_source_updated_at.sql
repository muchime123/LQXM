-- V314: 供应商应付台账补系统列（V313 漏建 source_updated_at）
-- loadExistingFacts 统一读 id/external_id/source_hash/source_updated_at 四列，
-- 人工台账无源侧修改时间 → 恒 NULL，幂等判定退化为 source_hash 全量比对（符合预期）。
ALTER TABLE h3_supplier_payables
  ADD COLUMN source_updated_at DATETIME NULL COMMENT '源侧修改时间（人工台账无此列，恒 NULL）' AFTER source_hash;
