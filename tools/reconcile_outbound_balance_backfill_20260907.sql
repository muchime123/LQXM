-- =====================================================================
-- 出库回款单一权威 · 历史一次性平衡回填（2026-09-07 / P1）—— 写数据，需人工执行
-- =====================================================================
-- 重要：
--   1) 本脚本会**修改数据**（写入 AUTO_BALANCE 平衡行），影响历史月度归属口径，
--      与线上 OutboundBalanceService.reconcileAndRefresh 的平衡逻辑保持一致；
--   2) 【执行前】先运行 tools/reconcile_outbound_balance_audit_20260907.sql
--      导出 A/B 两份差异清单并留档（db_cleanup_20260907/ 同级新目录），经财务复核后再执行；
--   3) 【执行方式】建议：先备份 sales_outbound_payment_snapshots 整表（含 AUTO_BALANCE 行），
--      再在本脚本外层 START TRANSACTION ... COMMIT/ROLLBACK 中运行；
--      执行后运行审计脚本的 C/D 两个结果集确认恒等式成立、平衡行可审计。
--
-- 平衡逻辑（与 Java 一致，决策 D1）：
--   - 权威 = sales_outbounds.received_amount（CONFIRMED 出库）；
--   - 差额 = 主表 − SUM(CONFIRMED 明细，不含 AUTO_BALANCE 行)；
--   - 删除既有 AUTO_BALANCE 行（幂等）后，对 |差额|>0.01 的出库插入 1 条带符号平衡行；
--   - 平衡行日期 = COALESCE(来源行最新收款日期, ship_date, business_date,
--                          DATE(source_created_at), CURDATE())（决策 D2 默认）。
-- =====================================================================

-- 0) 前置核验：确认当前执行环境为 MySQL（本脚本不使用 PostgreSQL/新版本语法）
SELECT VERSION();

-- 1) 清理既有平衡行（保持脚本幂等，可重复执行）
DELETE FROM sales_outbound_payment_snapshots WHERE migration_batch = 'AUTO_BALANCE';

-- 2) 聚合来源明细（金额/日期只计 CONFIRMED，与核算读取口径一致；migration_batch <> 'AUTO_BALANCE'
--    由步骤 1 保证；installment_index 取全部行最大值，避免与同单 PENDING 行的更大 index 冲突）
CREATE TEMPORARY TABLE tmp_reconcile_agg AS
SELECT ps.sales_outbound_id,
       COALESCE(SUM(CASE WHEN ps.legacy_status = 'CONFIRMED' THEN ps.received_amount END), 0) AS sum_amount,
       MAX(CASE WHEN ps.legacy_status = 'CONFIRMED' THEN ps.receipt_date END) AS max_receipt_date,
       COALESCE(MAX(ps.installment_index), 0) AS max_index
  FROM sales_outbound_payment_snapshots ps
 GROUP BY ps.sales_outbound_id;

-- 3) 主表权威值 LEFT JOIN 聚合 → 差额表
CREATE TEMPORARY TABLE tmp_reconcile_diff AS
SELECT o.id                                       AS oid,
       o.outbound_no                              AS outbound_no,
       COALESCE(o.received_amount, 0)             AS received_amount,
       COALESCE(a.sum_amount, 0)                  AS detail_sum_amount,
       a.max_receipt_date                         AS max_receipt_date,
       COALESCE(a.max_index, 0)                   AS max_index,
       o.ship_date                                AS ship_date,
       o.business_date                            AS business_date,
       DATE(o.source_created_at)                  AS source_created_date
  FROM sales_outbounds o
  LEFT JOIN tmp_reconcile_agg a ON a.sales_outbound_id = o.id
 WHERE o.outbound_status = 'CONFIRMED';

-- 4) 插入平衡行（仅 |差额| > 0.01 的出库）
INSERT INTO sales_outbound_payment_snapshots(
    sales_outbound_id, installment_index, legacy_receipt_id, legacy_external_source,
    legacy_external_id, legacy_receipt_no, legacy_status, receipt_date, received_amount,
    payment_method, source_created_by, source_created_at, remark, migration_batch, migrated_at)
SELECT oid,
       max_index + 1,
       NULL,
       'AUTO_BALANCE',
       CONCAT('AUTO:', oid),
       CONCAT(outbound_no, '#BAL'),
       'CONFIRMED',
       COALESCE(max_receipt_date, ship_date, business_date, source_created_date, CURDATE()),
       received_amount - detail_sum_amount,
       NULL,
       NULL,
       NOW(),
       CASE WHEN (received_amount - detail_sum_amount) >= 0
            THEN '主表自动平衡差额（明细不足补差）'
            ELSE '主表自动平衡差额（明细超额冲销）' END,
       'AUTO_BALANCE',
       NOW()
  FROM tmp_reconcile_diff
 WHERE ABS(received_amount - detail_sum_amount) > 0.01;

-- 5) 清理临时表
DROP TEMPORARY TABLE tmp_reconcile_diff;
DROP TEMPORARY TABLE tmp_reconcile_agg;

-- 6) 执行后核验（应返回 0 行，等价于审计脚本 C）
SELECT o.id AS outbound_id, o.outbound_no
  FROM sales_outbounds o
  LEFT JOIN (
        SELECT ps.sales_outbound_id,
               COALESCE(SUM(ps.received_amount), 0) AS sum_amount
          FROM sales_outbound_payment_snapshots ps
         WHERE ps.legacy_status = 'CONFIRMED'
         GROUP BY ps.sales_outbound_id
  ) t ON t.sales_outbound_id = o.id
 WHERE o.outbound_status = 'CONFIRMED'
   AND ABS(COALESCE(o.received_amount, 0) - COALESCE(t.sum_amount, 0)) > 0.01;

-- 核验通过后请在事务外层执行 COMMIT；未确认前可 ROLLBACK。
