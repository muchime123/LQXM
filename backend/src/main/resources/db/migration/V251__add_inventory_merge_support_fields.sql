-- V251: 产品主数据加非标标识，库存余额表加采购价与库存金额
-- 支持氚云产品信息+仓库明细合并导入功能

-- 1. sales_products 加非标定制标识
ALTER TABLE sales_products
  ADD COLUMN is_customized TINYINT(1) NOT NULL DEFAULT 0 COMMENT '非标定制标识：0=标准品, 1=非标定制' AFTER status;

-- 2. product_inventory_balances 加采购价与库存金额（冗余字段，用于库存展示）
ALTER TABLE product_inventory_balances
  ADD COLUMN purchase_price DECIMAL(18,4) NULL COMMENT '采购参考价（导入时快照）' AFTER quantity,
  ADD COLUMN stock_amount DECIMAL(18,4) NULL COMMENT '库存金额=数量×采购价（冗余快照）' AFTER purchase_price;
