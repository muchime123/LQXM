-- 收款分配关系唯一约束：同一收款单不能重复分配到同一出库单
-- 先清理已知的 22 组重复关系（保留 id 最小的），再加唯一索引

-- 1. 删除重复的 receipt_allocations（同 receipt_id + sales_outbound_id 保留最早一条）
DELETE ra1 FROM receipt_allocations ra1
INNER JOIN receipt_allocations ra2
  ON ra1.receipt_id = ra2.receipt_id
 AND ra1.sales_outbound_id = ra2.sales_outbound_id
 AND ra1.id > ra2.id
WHERE ra1.sales_outbound_id IS NOT NULL;

-- 2. 加唯一索引（仅对 sales_outbound_id IS NOT NULL 的行生效）
CREATE UNIQUE INDEX idx_receipt_alloc_unique
  ON receipt_allocations(receipt_id, sales_outbound_id);

-- 3. 为 receipt_allocations.sales_outbound_id 补索引（已有则跳过）
-- 注意：不建外键，因为 receipt_allocations 允许 sales_outbound_id = NULL（导入顺序问题）
