-- 销售出库提成核算：修正变化增加「期间归属」与「业务员变更感知」（2026-09-01 优化）
--
-- 背景：
--   1) 修正变化的核算期间此前由「结账时点」决定：calculateVersion 无条件并入所有 PENDING 变化，
--      导致 8 月产生的修正可能被并入 9 月版本（串月）。本次为每条变化记录回款/运杂费的
--      归属日期，结账时按「期间回款（收款日期 ∈ 期间）− 上期运杂费（发货日期 ∈ 上期整月窗口）」
--      口径过滤，与实时汇总 summary 完全一致。
--   2) 业务员变更（换人/清空）此前不感知：金额不变时不产生修正流水，已结账期间的提成会
--      悄悄易主。本次记录原归属业务员，业务员变更时生成「原业务员追回 + 新业务员补给」两条修正。
--
-- 回填：历史变化无法精确追溯归属日期，receipt_date/ship_date 保持 NULL（期间未知 =
-- 并入下次结账，前端标注），不影响存量核算。
--
-- 唯一键变更：原 (batch_id, outbound_id) 唯一约束在「业务员变更」下会产生冲突——
-- 同一单同批次会产生「原业务员追回 + 新业务员补给」两条修正（employee_id 不同），
-- 第二条会被 INSERT IGNORE 静默丢弃。改为按 (batch_id, outbound_id, employee_id) 唯一。

ALTER TABLE commission_outbound_changes
    DROP INDEX uk_coc_batch_outbound,
    ADD UNIQUE KEY uk_coc_batch_outbound_emp (batch_id, outbound_id, employee_id),
    ADD COLUMN receipt_date DATE NULL COMMENT '回款变化归属的收款日期（该单最近收款日期，结账期间过滤用）' AFTER customer_name,
    ADD COLUMN ship_date DATE NULL COMMENT '运杂费变化归属的发货日期（COALESCE(ship_date,business_date)，上期运杂费窗口过滤用）' AFTER receipt_date,
    ADD COLUMN prev_employee_id BIGINT NULL COMMENT '业务员变更前归属 employees.id（换人修正审计）' AFTER employee_name,
    ADD COLUMN prev_employee_name VARCHAR(100) NULL COMMENT '业务员变更前姓名快照' AFTER prev_employee_id,
    ADD KEY idx_coc_status_dates (status, receipt_date, ship_date);
