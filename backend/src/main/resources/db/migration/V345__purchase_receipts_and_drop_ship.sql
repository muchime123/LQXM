-- =====================================================================
-- V345: 外采到货入库（P2 入库型）+ 代发出库标记（P3）
-- ---------------------------------------------------------------------
-- 决策来源（LQ 2026-09-24 拍板，与 V344 同一批）：
--   ① 方案 A——到货入本地库存账（product_inventory_balances + 流水），
--      与自产完工入库同套余额表，出库走标准扣减；
--   ② 代发型 DROP_SHIP——供应商直发客户，**不写本地库存账**，
--      销售出库过账时按 fulfillment_source 豁免扣库存；
--   ③ 一行销售需求对应一个采购订单（1:1，V344 已落 uk_epo_sales_order_item）。
--
-- 迁移目标库：product_archive（与 V340-V344 同库）。
--
-- ⚠️ 为什么到货要单独成单（而不是直接改 PO 上的 received_quantity）：
--   ① 到货是**可分批**的（供应商分两次发货），每次都对应一批入库流水；
--   ② 入库写的是库存账，必须可冲正——冲正要有单据 id 作为库存流水的 source_id，
--      否则「这 200 件是谁冲回去的」无处可查（与 production_execution_completions 同款理由）；
--   ③ 部分到货时 PO 不该结案，received_quantity 只是累计值，不能当单据。
-- =====================================================================

-- ---------- 1. 采购到货单（单据层，携带冲正留痕） ----------
CREATE TABLE IF NOT EXISTS erp_purchase_receipts (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  receipt_no VARCHAR(50) NOT NULL COMMENT '到货单号 CGDH%08d（= 主键，回填生成）',
  po_id BIGINT NOT NULL COMMENT '所属采购订单',
  warehouse_id BIGINT NOT NULL COMMENT '实际入库仓库',
  status VARCHAR(16) NOT NULL DEFAULT 'POSTED' COMMENT 'POSTED 已入库 / REVERSED 已冲正',
  received_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '到货入库时间',
  received_by_employee_id BIGINT NULL,
  received_by_name VARCHAR(100) NULL,
  remark VARCHAR(1000) NULL,
  reversed_at DATETIME NULL,
  reversed_by_employee_id BIGINT NULL,
  reversed_by_name VARCHAR(100) NULL,
  reversal_reason VARCHAR(500) NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_epr_receipt_no (receipt_no),
  KEY idx_epr_po (po_id, received_at),
  CONSTRAINT fk_epr_po FOREIGN KEY (po_id) REFERENCES erp_purchase_orders(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='外采采购到货单（入库型，已写本地库存账；冲正即反向过账）';

-- ---------- 2. 采购到货明细（库存流水的 source_id 指向本表行） ----------
CREATE TABLE IF NOT EXISTS erp_purchase_receipt_items (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  receipt_id BIGINT NOT NULL,
  line_no INT NOT NULL,
  po_item_id BIGINT NOT NULL COMMENT '对应 erp_purchase_order_items.id（冲正时回退累计到货量）',
  sales_product_id BIGINT NOT NULL COMMENT '库存余额必须挂在产品档案上，故到货前该明细必须已挂档案',
  canonical_product_id BIGINT NOT NULL COMMENT '库存聚合口径（= sales_products.canonical_product_id）',
  product_code_snapshot VARCHAR(100) NULL,
  product_name_snapshot VARCHAR(500) NOT NULL,
  specification_snapshot VARCHAR(1000) NULL,
  unit VARCHAR(40) NOT NULL,
  quantity DECIMAL(18,6) NOT NULL COMMENT '本次到货数量（正数）',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_epri_line (receipt_id, line_no),
  KEY idx_epri_po_item (po_item_id),
  KEY idx_epri_product (sales_product_id),
  CONSTRAINT fk_epri_receipt FOREIGN KEY (receipt_id) REFERENCES erp_purchase_receipts(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='外采采购到货明细（每行对应一条 product_inventory_transactions）';

-- ---------- 3. 出库单履约来源标记（P3 代发豁免扣库存的判据） ----------
-- 为什么必须落在出库单上、而不是「过账时反查 PO」：
--   过账的语义边界是**这张出库单**。反查 PO 要走 sales_order_item_id → PO，
--   而当一张出库单的明细来自不同来源时该反查无解；且 PO 事后被改/被取消会让
--   历史出库单的过账语义跟着漂移。单据自身记录「这批货是怎么来的」才是稳定事实。
-- NULL 表示普通单（含全部历史单与氚云镜像单）——行为与今天完全一致。
ALTER TABLE erp_sales_outbounds
  ADD COLUMN fulfillment_source VARCHAR(20) NULL
    COMMENT '履约来源：WAREHOUSE 外采到货入库后发货（扣库存）/ DROP_SHIP 供应商直发客户（过账豁免扣库存）；NULL=普通或历史单';
