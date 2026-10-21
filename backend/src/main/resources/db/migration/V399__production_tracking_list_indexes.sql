-- 生产跟单列表提速（2026-10-21）
--
-- 背景：跟单列表默认首屏（无任何筛选）实测 440~480ms，其中两笔是纯排序代价：
--   ① production_orders 走 uk_po_external(external_source='H3YUN') 取全部 6038 行，
--      再对全部行做 Sort(source_created_at DESC, id DESC) 才 LIMIT 30 —— 22ms。
--      该条件在全表 6038/6038 行上恒真，选择性为零，「走索引」本身没有收益，
--      反而让优化器失去按排序列顺序扫的能力。补 (external_source, source_created_at, id)
--      覆盖索引后，优化器可直接反向扫索引取前 30 行，免排序（实测 22ms → 3ms）。
--   ② sales_outbounds.business_date 无任何索引，MAX/ORDER BY 只能排 10.9 万行（40~55ms）。
--      outboundCoverageThrough() 每次列表都会读一次「台账最新出库日」，补索引后走反向覆盖扫。
--
-- 语义：两个索引都只影响访问路径，不改变任何查询结果。业务口径零变化。
--
-- 幂等：先查 information_schema 再决定是否创建，重复执行安全（并行会话可能已手工建过）。

SET @has_po_idx := (
    SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'production_orders'
      AND INDEX_NAME = 'idx_po_source_created');
SET @sql := IF(@has_po_idx = 0,
    'CREATE INDEX idx_po_source_created ON production_orders (external_source, source_created_at, id)',
    'SELECT ''idx_po_source_created 已存在，跳过''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @has_so_idx := (
    SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sales_outbounds'
      AND INDEX_NAME = 'idx_so_business_date');
SET @sql := IF(@has_so_idx = 0,
    'CREATE INDEX idx_so_business_date ON sales_outbounds (business_date)',
    'SELECT ''idx_so_business_date 已存在，跳过''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
