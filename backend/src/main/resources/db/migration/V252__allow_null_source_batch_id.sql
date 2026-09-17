-- V252: sales_product_source_aliases.source_batch_id 允许 NULL
-- 合并导入功能不依赖 h3_preparation_batches 批次，直接写入别名时需要 NULL
ALTER TABLE sales_product_source_aliases
  MODIFY COLUMN source_batch_id BIGINT NULL DEFAULT NULL,
  DROP CONSTRAINT fk_spsa_batch;
ALTER TABLE sales_product_source_aliases
  ADD CONSTRAINT fk_spsa_batch FOREIGN KEY (source_batch_id) REFERENCES h3_preparation_batches(id) ON DELETE SET NULL;
