-- V268: 下线 V128-V131 建立、现已无运行时代码消费的旧销售利润/提成核算链。
-- 当前唯一核算事实为氚云销售出库 sales_outbounds；月度提成使用现行 monthly_* 领域。
-- sales_products 及 sales_product_source_aliases 仍被库存台账合并使用，因此保留。
-- 先完整归档，再删除原表，保留审计和人工追查能力。

CREATE TABLE IF NOT EXISTS sales_product_cost_snapshots_archive_20260907 AS SELECT * FROM sales_product_cost_snapshots;
CREATE TABLE IF NOT EXISTS sales_profit_calculations_archive_20260907 AS SELECT * FROM sales_profit_calculations;
CREATE TABLE IF NOT EXISTS sales_commission_rules_archive_20260907 AS SELECT * FROM sales_commission_rules;
CREATE TABLE IF NOT EXISTS sales_commission_calculations_archive_20260907 AS SELECT * FROM sales_commission_calculations;
CREATE TABLE IF NOT EXISTS sales_commission_settlements_archive_20260907 AS SELECT * FROM sales_commission_settlements;

DROP TABLE IF EXISTS sales_commission_calculations;
DROP TABLE IF EXISTS sales_commission_settlements;
DROP TABLE IF EXISTS sales_commission_rules;
DROP TABLE IF EXISTS sales_profit_calculations;
DROP TABLE IF EXISTS sales_product_cost_snapshots;
