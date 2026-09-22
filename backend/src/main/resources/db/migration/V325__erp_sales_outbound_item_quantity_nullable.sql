-- =====================================================================
-- V324 补漏：erp_sales_outbound_items.quantity 放开 NOT NULL
-- ---------------------------------------------------------------------
-- 怎么发现的（全量镜像第一次跑，18,941/18,966 成功，剩 25 张反复失败）：
--   报错 `Column 'quantity' cannot be null`。
--   根因：V324 放开了明细的 sales_product_id / canonical_product_id /
--   product_name_snapshot / unit 四列，**漏了 quantity**——而源明细里有 57 行
--   数量为空（`sales_outbound_items.quantity IS NULL`），落在 25 张单上。
--
-- 为什么这次做的是「系统对拍」而不是再猜一次：
--   把 erp_sales_outbound_items 的全部 NOT NULL 列 × 源列空值率一次对照完，
--   结论是 quantity 是**唯一**遗漏：
--     主表 NOT NULL 残留 → outbound_no(源空0) / status(固定DRAFT) /
--                          outbound_date(源空0) / created_at(源空0)  → 无风险
--     明细 NOT NULL 残留 → outbound_id / line_no / line_type(默认值) /
--                          created_at(默认值)              → 无风险
--                                       quantity(源空 57)  ← 只有这一列
--
-- 为什么放开而不是给占位值：
--   数量是过账扣库存的依据，**造假值比留空危险得多**（留空 = 这张单过不了账、
--   一眼看出待补；造假 = 悄悄扣错库存）。与双轨制纪律一致：源缺什么就留空。
--
-- ⚠️ 只放开**数据库层**。Service 层的手工建单校验
--    （`SalesOutboundService.validateLines` 里「出库数量必须大于0且最多6位小数」）
--    保持不变；过账侧新增 `assertLinesDeductible()` 集中校验，
--    把「缺产品身份 / 缺单位 / 数量为空或非正」变成一句能读懂的话，而不是 NPE。
-- =====================================================================

ALTER TABLE erp_sales_outbound_items
    MODIFY COLUMN quantity DECIMAL(18,6) NULL
        COMMENT '出库数量；氚云有 57 行没填 → 留空（不清洗、不造占位）。⚠️ 过账时必须 > 0';
