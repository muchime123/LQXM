-- V131: 补齐 sales_commission_calculations 缺失的 commission_type / commission_value 列
-- 设计依据：docs/h3-business-domain-design.md §P3.4
-- 规则的 commission_type (PERCENT/FIXED_AMOUNT) 与 commission_value 应在计算明细中冗余快照，
-- 以保证规则后续修改或删除后，历史提成计算记录仍能解释其计算依据（与 rule_name 冗余同理）。

ALTER TABLE sales_commission_calculations
    ADD COLUMN commission_type  VARCHAR(10) NULL COMMENT '冗余规则类型 PERCENT/FIXED_AMOUNT' AFTER rule_name,
    ADD COLUMN commission_value DECIMAL(14,4) NULL COMMENT '冗余规则值（提成比例 % 或固定金额）' AFTER commission_type;
