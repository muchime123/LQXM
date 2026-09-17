-- V304: 产品生命周期状态 + 导入覆盖标记（2026-09-17）
--
-- 背景：产品库存域原讨论的「staging 原子替换式重导」被否决——
--   ① 氚云几乎不删产品；② 台账是手动导出的文件，部分导入（只导某仓/某段编号）是常态，
--   「不在本次导出 = 已删除」的推定会把大量未导入行误杀。
-- 定调改为「状态化台账」：产品永不物理删除，用生命周期状态表达；导入缺失只做软提示，
-- 由人决定是否下架。氚云仍是产品事实字段（名称/规格/价）的唯一来源，本列是本地管理列，
-- 导入 upsert 不覆盖（INSERT 走列默认值，UPDATE 语句不含该列）。

-- 1) 生命周期状态（产品信息台账 = 产品粒度；仓库明细行跟编号走，不单独设状态）
ALTER TABLE h3_product_catalog
    ADD COLUMN shelf_status VARCHAR(16) NOT NULL DEFAULT 'ON_SHELF'
        COMMENT '生命周期状态（本地管理，导入不覆盖）：ON_SHELF 在用 / OFF_SHELF 下架',
    ADD COLUMN shelf_updated_at DATETIME NULL
        COMMENT '状态最近变更时间',
    ADD COLUMN shelf_updated_by VARCHAR(64) NULL
        COMMENT '状态最近变更人（员工姓名）';

-- 2) 导入覆盖标记：每次确认导入后，对本次文件覆盖到的行批量刷新（含 UNCHANGED 行——
--    last_synced_at 只在内容变化时刷新，不能当覆盖信号用）。部分导入只覆盖其行，安全。
ALTER TABLE h3_product_catalog
    ADD COLUMN last_seen_at DATETIME NULL COMMENT '最近一次被任意导入批次覆盖的时间',
    ADD COLUMN last_seen_batch_id BIGINT NULL COMMENT '最近覆盖本行的导入批次 id';
ALTER TABLE h3_warehouse_stock
    ADD COLUMN last_seen_at DATETIME NULL COMMENT '最近一次被任意导入批次覆盖的时间',
    ADD COLUMN last_seen_batch_id BIGINT NULL COMMENT '最近覆盖本行的导入批次 id';

-- 3) 存量回填：以 last_synced_at 作为初始覆盖时间（两列均为 NOT NULL 语义的导入时间，
--    历史每行都在某次导入中被写入过）。回填后「30 天未被覆盖」报告从干净状态起步。
UPDATE h3_product_catalog SET last_seen_at = last_synced_at WHERE last_seen_at IS NULL;
UPDATE h3_warehouse_stock  SET last_seen_at = last_synced_at WHERE last_seen_at IS NULL;

-- 4) 覆盖报告查询走 (状态, last_seen_at)，加个辅助索引
CREATE INDEX idx_h3pc_shelf_seen ON h3_product_catalog (shelf_status, last_seen_at);
CREATE INDEX idx_h3ws_seen ON h3_warehouse_stock (last_seen_at);
