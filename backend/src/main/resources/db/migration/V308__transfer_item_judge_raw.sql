-- V308 (2026-09-17): P2 库存调拨 —— 补落「调拨明细.判断（隐）」原文列
--
-- 依据：docs/氚云调拨与开票导入设计-20260917.md §2.1 / §5-待拍板-3
--   「调拨明细.判断（隐）」实测取值恒 0，语义未知 → **存原文不解读**（猜错写入比不存更糟）。
-- V307 首发时漏落此列；此处以增量迁移补齐（不改 V307，避免 Flyway checksum 校验失败）。
--
-- 幂等影响：新增列不参与 source_hash（hash 由解析层 values 决定），
--   但要让已入库行补上值，需 DELETE 主子表后重导一次（见落地文档 §9 踩坑记录 2）。
ALTER TABLE h3_inventory_transfer_items
  ADD COLUMN judge_raw VARCHAR(16) NULL COMMENT '调拨明细.判断（隐）原文，0/1 语义未知不解读' AFTER out_stock_quantity;
