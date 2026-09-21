-- =====================================================================
-- 产品身份归一口径（canonical_product_id）—— 决策 #28 / Q1 拍板落地
-- ---------------------------------------------------------------------
-- 背景：`MFG-<工单号>` 由 V236 / ProductInventoryService#ensureManufacturingProduct
--   按**每张工单**造一个交易产品身份，而 `product_inventory_balances` 按
--   (warehouse_id, sales_product_id, unit) 唯一 → 生产 100 张同型号产品就会在库存里
--   分裂成 100 个 product_id、各 1 件，「有货直接出库」永远判断不出来。
--
-- LQ 拍板（09-21）：**保留 `MFG-<工单号>` 编码**，但**不作为库存聚合维度**。
--   归一方式：`sales_products.canonical_product_id` 指向这件货在库存里算哪个 SKU；
--   库存余额与流水按 canonical 聚合，`MFG-` 仍保留为「订单专属产出标识」可追溯到工单。
--
-- 归一策略（刻意保守，不做模糊匹配）：
--   * 氚云产品（external_id 非空）→ **canonical = 自身**。实测 H3- 之间存在
--     「同名同规格同单位」多编码簇（1 簇 2 行），跨编码归一会在余额表唯一键上撞车，
--     且会把两件不同的货合并——属不可逆的错误合并。
--   * ERP 自建产品（`MFG-`）→ 按 (名称, 规格, 单位) 精确匹配 **唯一** 的氚云产品时归一；
--     匹配 0 个或多个一律回退自身（宁可不合并，不可错合并）。
--     实测现有 4 个 MFG- 全部匹配 0 个（含测试残留"1"），故本次回填对存量数据
--     **零实际改动**，只把机制就位。
--
-- 依据：docs/完工入库到销售出库的身份断链与闭环设计-20260921.md §5.3 Q1
--       docs/ERP决策登记-20260921.md U15 / #28
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. 产品身份加归一口径列
-- ---------------------------------------------------------------------
ALTER TABLE sales_products
    ADD COLUMN canonical_product_id BIGINT NULL COMMENT '库存聚合口径：这件货在库存里算哪个 SKU（H3- 归自身；MFG- 可归一；NULL 视为自身）',
    ADD KEY idx_sp_canonical (canonical_product_id);

-- 默认全部归自身：氚云产品按上面的策略恒为自身，一次性铺底最省事，也让后续判空简单
UPDATE sales_products SET canonical_product_id = id WHERE canonical_product_id IS NULL;

-- MFG- 尝试归一到唯一的氚云产品（精确匹配 名称+规格+单位，命中恰好 1 个才归一）
-- 用派生表包一层，规避 MySQL「不能在 UPDATE 的子查询里引用同一张目标表」
UPDATE sales_products m
JOIN (
    SELECT m2.id AS mid, MIN(h.id) AS hid
    FROM sales_products m2
    JOIN sales_products h
      ON h.deleted_at IS NULL
     AND h.external_id IS NOT NULL
     AND h.product_name = m2.product_name
     AND COALESCE(h.specification, '') = COALESCE(m2.specification, '')
     AND COALESCE(h.unit, '') = COALESCE(m2.unit, '')
    WHERE m2.product_code LIKE 'MFG-%'
      AND m2.deleted_at IS NULL
      AND m2.canonical_product_id = m2.id
    GROUP BY m2.id
    HAVING COUNT(*) = 1
) x ON x.mid = m.id
SET m.canonical_product_id = x.hid;

-- ---------------------------------------------------------------------
-- 2. 库存余额：加归一口径列并切换唯一键
--    切换前实测无重复组（同一 (仓,产品,单位) 仅一行），且回填后 H3- 恒为自身，
--    故 ADD UNIQUE 不会撞车。若将来 MFG- 归一成功，与 H3- 同仓同单位**就应合并**——
--    这正是本次改动的目的：余额行按 canonical 唯一，多张工单产出的同款货累加在一起。
-- ---------------------------------------------------------------------
ALTER TABLE product_inventory_balances
    ADD COLUMN canonical_product_id BIGINT NULL COMMENT '库存聚合口径（= sales_products.canonical_product_id）' AFTER sales_product_id;

UPDATE product_inventory_balances b
JOIN sales_products sp ON sp.id = b.sales_product_id
SET b.canonical_product_id = sp.canonical_product_id;

ALTER TABLE product_inventory_balances
    MODIFY COLUMN canonical_product_id BIGINT NOT NULL COMMENT '库存聚合口径（= sales_products.canonical_product_id）';

ALTER TABLE product_inventory_balances
    DROP INDEX uk_pib_warehouse_product_unit,
    ADD UNIQUE KEY uk_pib_warehouse_canonical_unit (warehouse_id, canonical_product_id, unit),
    ADD KEY idx_pib_canonical (canonical_product_id);

-- ---------------------------------------------------------------------
-- 3. 库存流水：加归一口径列（保留 sales_product_id 作业务身份，供追溯「哪张单产出」）
-- ---------------------------------------------------------------------
ALTER TABLE product_inventory_transactions
    ADD COLUMN canonical_product_id BIGINT NULL COMMENT '库存聚合口径（= sales_products.canonical_product_id）' AFTER sales_product_id;

UPDATE product_inventory_transactions t
JOIN sales_products sp ON sp.id = t.sales_product_id
SET t.canonical_product_id = sp.canonical_product_id;

ALTER TABLE product_inventory_transactions
    MODIFY COLUMN canonical_product_id BIGINT NOT NULL COMMENT '库存聚合口径（= sales_products.canonical_product_id）';

ALTER TABLE product_inventory_transactions
    ADD KEY idx_pit_canonical (warehouse_id, canonical_product_id, occurred_at);
