-- V306 (2026-09-17): P1 其他入库 / 其他出库事实层
-- 设计依据：docs/氚云新增7表ERP适用性梳理与导入设计-20260917.md §2.4
-- 决策（LQ 2026-09-17 拍板）：与采购入库同口径——只落事实层，不动 product_inventory_balances
--   与库存流水（余额仍由台账快照合并驱动，L3 冻结）。
-- 同构合并：两表明细字段高度一致（产品编号/选择产品/规格/单位/数量/库存数量/采购价格/类目/数据ID），
--   且**明细子表前缀在两份导出件里都叫「其他出库明细.」**（氚云表单复制串名），无法靠前缀区分，
--   故合并为一张表 + direction 位，靠主表字段「选择入库仓库」/「选择出库仓库」识别类型。
-- 系统列与 A 域事实表同款（external_source/external_id/source_hash/source_updated_at/last_synced_at），
--   供 H3FactImportService 统一管线（loadExistingFacts/unchanged/touch）直接复用。

-- ============ 其他入/出库主表（direction 区分方向） ============
CREATE TABLE IF NOT EXISTS h3_other_stock_movements (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  direction VARCHAR(8) NOT NULL COMMENT 'IN=其他入库 / OUT=其他出库',
  external_source VARCHAR(32) NOT NULL DEFAULT 'H3YUN',
  external_id VARCHAR(64) NOT NULL COMMENT '单号（主表数据ID列位于子表列之后，会被表头首命中吞掉，统一取单号）',
  source_no VARCHAR(64) NOT NULL COMMENT 'QTRK…（入库）/ QTCK…（出库）',
  movement_date DATE NULL COMMENT '入库日期 / 出库日期',
  warehouse_id BIGINT NULL COMMENT '关联 warehouses（同名唯一才绑）',
  warehouse_name VARCHAR(64) NULL COMMENT '选择入库仓库 / 选择出库仓库',
  keeper VARCHAR(64) NULL COMMENT '仓管员',
  salesman VARCHAR(64) NULL COMMENT '业务员（仅其他出库有）',
  total_quantity DECIMAL(18, 4) NULL COMMENT '主表合计数量（仅其他出库有）',
  total_amount DECIMAL(18, 2) NULL COMMENT '主表合计金额（仅其他出库有）',
  remark VARCHAR(1000) NULL,
  data_status VARCHAR(32) NULL COMMENT '已生效/草稿（草稿不进正式口径）',
  source_title VARCHAR(255) NULL,
  owner_name VARCHAR(190) NULL,
  department_name VARCHAR(190) NULL COMMENT '所属部门=业务主体（浙江利强/安徽盒多多/温州永铭）',
  created_by_name VARCHAR(190) NULL,
  source_created_at DATETIME NULL,
  source_updated_at DATETIME NULL,
  source_hash VARCHAR(64) NULL,
  last_synced_at DATETIME NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_osm_external (external_source, external_id),
  UNIQUE KEY uk_osm_no (source_no),
  KEY idx_osm_dir_date (direction, movement_date),
  KEY idx_osm_warehouse (warehouse_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='氚云其他入库/其他出库（事实层，2026-09-17 决策：不动库存余额）';

-- ============ 明细（两表共用，前缀「其他出库明细.」） ============
CREATE TABLE IF NOT EXISTS h3_other_stock_movement_items (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  movement_id BIGINT NOT NULL,
  source_item_id VARCHAR(64) NULL COMMENT '其他出库明细.数据ID(UUID)，行级幂等键；空值行存 NULL',
  line_no INT NULL,
  product_code VARCHAR(64) NULL,
  product_name VARCHAR(255) NULL,
  category VARCHAR(190) NULL COMMENT '类目',
  spec VARCHAR(255) NULL,
  unit VARCHAR(32) NULL,
  quantity DECIMAL(18, 4) NULL COMMENT '入库数量 / 出库数量(必填)',
  stock_quantity DECIMAL(18, 4) NULL COMMENT '库存数量快照',
  purchase_price DECIMAL(18, 4) NULL COMMENT '采购价格',
  amount DECIMAL(18, 2) NULL COMMENT '采购总价（仅其他出库有；入库侧由数量×单价口径推算，不落库）',
  remark VARCHAR(500) NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_osmi_item (source_item_id),
  KEY idx_osmi_movement (movement_id),
  KEY idx_osmi_code (product_code),
  CONSTRAINT fk_osmi_movement FOREIGN KEY (movement_id) REFERENCES h3_other_stock_movements (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='氚云其他入库/其他出库产品明细（按单据整单删除重插，行级 UUID 幂等）';
