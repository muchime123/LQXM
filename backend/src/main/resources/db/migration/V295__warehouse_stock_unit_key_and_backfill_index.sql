-- V295: 仓库明细台账业务键补单位维度 + 合并建档回填索引
-- 1) 库存台账允许同仓库同编号的多单位行（箱/个）并存：先清理历史重复行，再加唯一约束
-- 2) sales_outbound_items 的 TRIM(product_code) 函数索引：加速合并建档后 promoteProductResolution 的历史回填

-- 清理重复行：同 仓库+编号+单位（NULL 安全）只保留最小 id
DELETE s FROM h3_warehouse_stock s
JOIN h3_warehouse_stock k
  ON k.id < s.id
 AND k.product_code = s.product_code
 AND k.warehouse <=> s.warehouse
 AND k.unit <=> s.unit;

ALTER TABLE h3_warehouse_stock
  ADD UNIQUE KEY uk_h3ws_wh_code_unit (warehouse, product_code, unit),
  ADD KEY idx_h3ws_code_unit (product_code, unit);

ALTER TABLE sales_outbound_items
  ADD KEY idx_soi_trim_code ((TRIM(product_code)));
