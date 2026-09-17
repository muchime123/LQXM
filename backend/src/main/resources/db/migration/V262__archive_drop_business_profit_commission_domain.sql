-- V262: 下线旧「业务中心利润/提成」核算域：归档并清理数据表（2026-09-04）
-- 背景：利润/提成口径已统一为氚云台账销售出库（sales_outbound_profits / sales_outbound_commissions）。
--       旧 business_orders 域的独立核算链（BusinessProfitService / BusinessCommissionService 及
--       ExpenseAllocationService 对 BUSINESS_ORDER 目标的联动重算）已同步下线，以下数据表不再写入。
-- 处理：先按原样归档到 *_archive_20260904（保留全部行供审计/追查），再 DROP 原表。
-- 保留：business_commission_rules（现行销售出库提成的共享规则表，SalesOutboundCommissionService 读取），
--       business_orders / business_order_links（现行匹配与台账血缘使用）。
CREATE TABLE IF NOT EXISTS business_profit_snapshots_archive_20260904 AS SELECT * FROM business_profit_snapshots;
CREATE TABLE IF NOT EXISTS business_profit_adjustments_archive_20260904 AS SELECT * FROM business_profit_adjustments;
CREATE TABLE IF NOT EXISTS business_commission_snapshots_archive_20260904 AS SELECT * FROM business_commission_snapshots;
CREATE TABLE IF NOT EXISTS business_commission_settlements_archive_20260904 AS SELECT * FROM business_commission_settlements;
CREATE TABLE IF NOT EXISTS business_commission_settlement_items_archive_20260904 AS SELECT * FROM business_commission_settlement_items;
DROP TABLE IF EXISTS business_commission_settlement_items;
DROP TABLE IF EXISTS business_commission_settlements;
DROP TABLE IF EXISTS business_commission_snapshots;
DROP TABLE IF EXISTS business_profit_adjustments;
DROP TABLE IF EXISTS business_profit_snapshots;
