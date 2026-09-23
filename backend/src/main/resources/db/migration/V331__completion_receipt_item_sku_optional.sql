-- V331 车间交付收货明细的 SKU 列放宽为可空
--
-- 背景（2026-09-22 LQ 拍板，见 docs/ERP决策登记-20260921.md）：
--   新建生产任务单不再要求选择「可售产品（SKU）」—— SKU 是**完工入库的库存身份键**
--   （finished_goods_lots / product_inventory_transactions / product_inventory_balances 的必填列），
--   不是业务员下单时该填的字段；且该主数据本身要重新设计。
--   建单只保留「制程品类 + 产品配置 + 数量 + 单位」，工单与产出清单主行的 SKU 列留空。
--
-- 由此产生的后果（本迁移要承接的那个后果）：
--   产出清单行没有 SKU 时，收货**不落库存账**（无 SKU 即无库存身份），只登记收货事实。
--   而 manufacturing_completion_receipt_items 的 sales_product_id / canonical_product_id
--   原先 NOT NULL → 无 SKU 的收货明细写不进去。本迁移只做一件事：把这两列放宽为可空。
--
-- 影响面：
--   * 纯放宽约束（NOT NULL → NULL），不改类型、不改默认值、不动数据、不动索引、无外键；
--   * 有 SKU 的行（历史工单 + 附加产出行）行为完全不变，填入值照旧；
--   * 库存侧三张表（finished_goods_lots / product_inventory_transactions / product_inventory_balances）
--     **不动** —— 它们的 SKU 列仍保持必填，因为「有 SKU 才落库存账」这条约束就落在这里，
--     SKU 主数据重新设计后再统一处理。

ALTER TABLE manufacturing_completion_receipt_items
  MODIFY COLUMN sales_product_id BIGINT NULL COMMENT '产出的 SKU（业务身份）；为空=该产出行建单时未指定 SKU，收货未落库存账',
  MODIFY COLUMN canonical_product_id BIGINT NULL COMMENT '库存聚合口径（= sales_products.canonical_product_id）；为空=同上';
