-- 产品库存期初预览过账相关字段
-- 1) 批次表增加过账时间和过账数量
ALTER TABLE product_inventory_opening_batches
  ADD COLUMN posted_at DATETIME NULL AFTER updated_at,
  ADD COLUMN posted_count INT NOT NULL DEFAULT 0 AFTER posted_at;

-- 2) 候选项记录过账时间和对应的库存流水号
ALTER TABLE product_inventory_opening_items
  ADD COLUMN posted_at DATETIME NULL AFTER updated_at,
  ADD COLUMN posted_txn_no VARCHAR(50) NULL AFTER posted_at;
