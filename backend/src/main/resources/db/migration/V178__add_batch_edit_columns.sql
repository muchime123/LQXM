-- ====================================================================
-- V177 批量编辑字段补列
--
-- 背景：
--   P1-D 推广统一批量编辑三件套到销售出库、生产看板、利润核算、费用管理 4 个页面。
--   前端 FieldBatchEditModal 定义的字段中，部分在数据库表中尚无对应列。
--   本脚本补齐这些列，使后端 PUT /batch 端点能够直接 UPDATE。
--
--   字段映射（前端字段 → DB 列）：
--     sales_outbounds.payment_terms (number, 账期天数)
--     production_orders.planned_start_date (date, 计划开工日)
--     production_orders.remark (varchar 1000)
--     business_orders.profit_calculation_status (varchar 20, 利润核算状态)
--     business_orders.profit_period (varchar 20, 会计期间 YYYY-MM)
--     business_orders.remark (varchar 1000)
-- ====================================================================

ALTER TABLE sales_outbounds
    ADD COLUMN payment_terms VARCHAR(50) NULL COMMENT '账期天数或描述（批量编辑入口）' AFTER collection_remark;

ALTER TABLE production_orders
    ADD COLUMN planned_start_date DATE NULL COMMENT '计划开工日期（批量编辑入口）' AFTER application_date,
    ADD COLUMN remark VARCHAR(1000) NULL COMMENT '备注（批量编辑入口）' AFTER department_name;

ALTER TABLE business_orders
    ADD COLUMN profit_calculation_status VARCHAR(20) NULL
        COMMENT '利润核算状态冗余字段：NOT_CALCULATED/CALCULATING/CALCULATED/ERROR（批量编辑入口）' AFTER settlement_status,
    ADD COLUMN profit_period VARCHAR(20) NULL
        COMMENT '会计期间 YYYY-MM（批量编辑入口）' AFTER profit_calculation_status,
    ADD COLUMN remark VARCHAR(1000) NULL
        COMMENT '备注（批量编辑入口）' AFTER profit_period;
