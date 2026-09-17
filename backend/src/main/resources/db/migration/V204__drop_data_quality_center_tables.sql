-- ====================================================================
-- V204 清理数据质量中心遗留的审计/对账表
--
-- 背景：
--   1. 数据质量中心（DataQualityCenter）已整体下线，前端页面、路由、
--      后端审计服务（BusinessRelationAudit / 收款对账 / 结算快照对账）均已删除。
--   2. 氚云数据源仅保留销售出库导入链路（H3ImportCenter），
--      收款/结清文件已停止导入，以下审计、对账类表在代码中已无任何读写。
--
-- 说明：
--   business_relation_audit / business_relation_audit_run（V153 业务关联审计）
--   receipt_reconciliation（V155 收款金额源数据对账）
--   settlement_snapshot_reconciliations / settlement_receipt_candidates /
--   settlement_exception_reviews（V198-V200 多订单结清快照对账链路）
-- 原建表脚本保留（Flyway 历史不可篡改），此处仅清理数据表本身。
-- ====================================================================

DROP TABLE IF EXISTS business_relation_audit_run;
DROP TABLE IF EXISTS business_relation_audit;
DROP TABLE IF EXISTS receipt_reconciliation;
DROP TABLE IF EXISTS settlement_exception_reviews;
DROP TABLE IF EXISTS settlement_receipt_candidates;
DROP TABLE IF EXISTS settlement_snapshot_reconciliations;
