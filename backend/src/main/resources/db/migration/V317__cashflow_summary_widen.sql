-- V317: 放宽流水日记账摘要列宽（第 18 类 confirm 首次实测踩坑）
-- 现象：confirm 抛 DataIntegrityViolationException: Data too long for column 'summary' at row 1
--       → 整个 confirm 事务回滚，h3_cashflow_entries 0 行、h3_cashier_log 0 行
-- 根因：V315 建表时 summary VARCHAR(255)，源「于帅帅杭州银行」sheet 有 1 行多行备注摘要 262 字
--       （实测全表仅此 1 行超 255：`1.13温州～乐清孙先生…货拉拉74.43元` 6 行拼接）
-- 处置：ALTER 放宽到 1000（银行摘要属自由多行文本，255 无余量）。V315 已应用 → 不可改文件，只能新增迁移。

ALTER TABLE h3_cashflow_entries MODIFY COLUMN summary VARCHAR(1000) NULL;
