-- V307 (2026-09-17): P2 库存调拨事实层
-- 设计依据：docs/氚云新增7表ERP适用性梳理与导入设计-20260917.md §2.5
-- 决策（LQ 2026-09-17 拍板）：与采购入库/其他出入库同口径——只落事实层，不动
--   product_inventory_balances 与库存流水（余额仍由台账快照合并驱动，L3 冻结）。
-- 状态口径（§3 统一底线）：已取消/草稿**单独标记 data_status、不物理丢弃**（保留审计痕迹），
--   正式业务口径只取 data_status='已生效'（实测 216 单）。
-- 与其他入/出库的差异：本表有**两个仓库**（调出/调入）与**两个仓管员**，故不合并方向位。
-- ⚠️ 表头陷阱：主表有两个同名「仓管员」列（E=调出、G=调入），通用表头解析用
--   putIfAbsent（首命中优先）会吞掉调入仓管员 → 解析层把第二列重命名为「仓管员(调入)」
--   （见 H3FactImportService#locateHeader，仅 TRANSFER 生效，不影响其他类型的 source_hash）。
-- 系统列与 A 域事实表同款（external_source/external_id/source_hash/source_updated_at/last_synced_at），
--   供 H3FactImportService 统一管线（loadExistingFacts/unchanged/touch）直接复用。

-- ============ 库存调拨主表 ============
CREATE TABLE IF NOT EXISTS h3_inventory_transfers (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  external_source VARCHAR(32) NOT NULL DEFAULT 'H3YUN',
  external_id VARCHAR(64) NOT NULL COMMENT '流水号 DB…（主表数据ID列位于明细列之后，统一取单号）',
  source_no VARCHAR(64) NOT NULL COMMENT 'DB2026…',
  transfer_date DATE NULL COMMENT '调拨日期',
  out_warehouse_id BIGINT NULL COMMENT '关联 warehouses（同名唯一才绑）',
  out_warehouse_name VARCHAR(64) NULL COMMENT '选择调出仓库',
  out_keeper VARCHAR(64) NULL COMMENT '仓管员（调出侧）',
  in_warehouse_id BIGINT NULL COMMENT '关联 warehouses（同名唯一才绑）',
  in_warehouse_name VARCHAR(64) NULL COMMENT '选择调入仓库',
  in_keeper VARCHAR(64) NULL COMMENT '仓管员(调入)：源表第二列，解析层重命名后落此',
  remark VARCHAR(1000) NULL,
  data_status VARCHAR(32) NULL COMMENT '已生效/草稿/已取消（后两者标记保留、不进正式口径）',
  source_title VARCHAR(255) NULL,
  owner_name VARCHAR(190) NULL,
  department_name VARCHAR(190) NULL COMMENT '所属部门=业务主体',
  created_by_name VARCHAR(190) NULL,
  source_created_at DATETIME NULL,
  source_updated_at DATETIME NULL,
  source_hash VARCHAR(64) NULL,
  last_synced_at DATETIME NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_it_external (external_source, external_id),
  UNIQUE KEY uk_it_no (source_no),
  KEY idx_it_date (transfer_date),
  KEY idx_it_out_wh (out_warehouse_id),
  KEY idx_it_in_wh (in_warehouse_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='氚云库存调拨（事实层，2026-09-17 决策：不动库存余额）';

-- ============ 明细（前缀「调拨明细.」） ============
CREATE TABLE IF NOT EXISTS h3_inventory_transfer_items (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  transfer_id BIGINT NOT NULL,
  source_item_id VARCHAR(64) NULL COMMENT '调拨明细.数据ID(不可修改)，行级幂等键；空值行存 NULL',
  line_no INT NULL COMMENT '调拨明细.序号',
  product_code VARCHAR(64) NULL,
  product_name VARCHAR(255) NULL,
  category VARCHAR(190) NULL COMMENT '类目',
  spec VARCHAR(255) NULL,
  unit VARCHAR(32) NULL,
  quantity DECIMAL(18, 4) NULL COMMENT '调拨数量',
  out_stock_quantity DECIMAL(18, 4) NULL COMMENT '调出仓库数量快照（调拨数量不应超过它）',
  remark VARCHAR(500) NULL COMMENT '调拨明细.产品备注',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_iti_item (source_item_id),
  KEY idx_iti_transfer (transfer_id),
  KEY idx_iti_code (product_code),
  CONSTRAINT fk_iti_transfer FOREIGN KEY (transfer_id) REFERENCES h3_inventory_transfers (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='氚云库存调拨产品明细（按单据整单删除重插，行级 UUID 幂等）';
