-- =====================================================================
-- 出库回款单一权威 · 平衡守护审计清单（2026-09-07 / P1）
-- =====================================================================
-- 用途（只读，不修改任何数据）：
--   1) 在「历史一次性平衡」前，导出主表/明细对不上的出库清单供财务复核；
--   2) 在「历史一次性平衡」后，核对恒等式已满足并单独展示 AUTO_BALANCE 行。
--
-- 权威口径：sales_outbounds.received_amount（氚云主表最终已收款，2026-08-29 财务确认）。
-- 明细口径：sales_outbound_payment_snapshots 中 legacy_status='CONFIRMED' 的行
--          （与月度/正式提成核算读取口径一致；AUTO_BALANCE 行先排除再计算差额）。
--
-- 建议执行方式（导出 CSV 留档）：
--   mysql -h<host> -u<user> -p<db> --default-character-set=utf8mb4 \
--         -e "source tools/reconcile_outbound_balance_audit_20260907.sql" 2>/dev/null
--   （或使用 Navicat / DataGrip 查询后导出；留档目录：db_cleanup_20260907/ 同级新目录）
-- =====================================================================


-- ---------------------------------------------------------------------
-- A. 差异总览（历史一次性平衡前）：应修单数 / 差额合计 / 示例
-- ---------------------------------------------------------------------
SELECT COUNT(*)                                                       AS drifted_outbounds,
       SUM(CASE WHEN diff < 0 THEN 1 ELSE 0 END)                      AS over_detail_count,   -- 明细合计 > 主表（需负向冲销行）
       ROUND(SUM(ABS(diff)), 2)                                       AS abs_diff_total,
       ROUND(SUM(diff), 2)                                            AS net_diff_total
FROM (
    SELECT o.id,
           COALESCE(o.received_amount, 0)
               - COALESCE(t.sum_amount, 0)                            AS diff
      FROM sales_outbounds o
      LEFT JOIN (
            SELECT ps.sales_outbound_id,
                   COALESCE(SUM(ps.received_amount), 0) AS sum_amount
              FROM sales_outbound_payment_snapshots ps
             WHERE ps.legacy_status='CONFIRMED'
               AND ps.migration_batch <> 'AUTO_BALANCE'
             GROUP BY ps.sales_outbound_id
      ) t ON t.sales_outbound_id = o.id
     WHERE o.outbound_status='CONFIRMED'
) d
WHERE ABS(diff) > 0.01;

-- ---------------------------------------------------------------------
-- B. 差异明细（历史一次性平衡前）：财务逐单复核样例
--    展示：单号 / 主表已收款 / 明细合计 / 差额 / 明细行数 / 发货/业务日期
-- ---------------------------------------------------------------------
SELECT o.id                                AS outbound_id,
       o.outbound_no                       AS outbound_no,
       o.sales_amount                      AS sales_amount,
       COALESCE(o.received_amount, 0)      AS master_received_amount,
       COALESCE(t.sum_amount, 0)           AS detail_sum_amount,
       COALESCE(o.received_amount, 0)
           - COALESCE(t.sum_amount, 0)     AS diff_amount,
       t.line_count                        AS confirmed_detail_count,
       o.ship_date                         AS ship_date,
       o.business_date                     AS business_date,
       DATE(o.source_created_at)           AS source_created_date,
       t.max_receipt_date                  AS max_detail_receipt_date
  FROM sales_outbounds o
  LEFT JOIN (
        SELECT ps.sales_outbound_id,
               COALESCE(SUM(ps.received_amount), 0) AS sum_amount,
               COUNT(*)                             AS line_count,
               MAX(ps.receipt_date)                 AS max_receipt_date
          FROM sales_outbound_payment_snapshots ps
         WHERE ps.legacy_status='CONFIRMED'
           AND ps.migration_batch <> 'AUTO_BALANCE'
         GROUP BY ps.sales_outbound_id
  ) t ON t.sales_outbound_id = o.id
 WHERE o.outbound_status='CONFIRMED'
   AND ABS(COALESCE(o.received_amount,0) - COALESCE(t.sum_amount,0)) > 0.01
 ORDER BY diff_amount DESC;

-- ---------------------------------------------------------------------
-- C. 平衡后恒等式核验（每次跑完平衡后执行）：应为 0 行
-- ---------------------------------------------------------------------
SELECT o.id AS outbound_id, o.outbound_no
  FROM sales_outbounds o
  LEFT JOIN (
        SELECT ps.sales_outbound_id,
               COALESCE(SUM(ps.received_amount), 0) AS sum_amount
          FROM sales_outbound_payment_snapshots ps
         WHERE ps.legacy_status='CONFIRMED'
         GROUP BY ps.sales_outbound_id
  ) t ON t.sales_outbound_id = o.id
 WHERE o.outbound_status='CONFIRMED'
   AND ABS(COALESCE(o.received_amount,0) - COALESCE(t.sum_amount,0)) > 0.01;

-- ---------------------------------------------------------------------
-- D. AUTO_BALANCE 平衡行审计：只应存在迁移/补偿原因生成的行
--    （P1 上线后随导入自动维护；历史一次性回填会一次性批量新增）
-- ---------------------------------------------------------------------
SELECT ps.sales_outbound_id                     AS outbound_id,
       o.outbound_no                            AS outbound_no,
       ps.installment_index                     AS installment_index,
       ps.legacy_receipt_no                     AS balance_receipt_no,
       ps.receipt_date                          AS balance_receipt_date,
       ps.received_amount                       AS balance_amount,
       ps.remark                                AS balance_remark,
       ps.migration_batch                       AS migration_batch,
       DATE(ps.migrated_at)                     AS created_on
  FROM sales_outbound_payment_snapshots ps
  LEFT JOIN sales_outbounds o ON o.id = ps.sales_outbound_id
 WHERE ps.migration_batch = 'AUTO_BALANCE'
 ORDER BY ps.sales_outbound_id;
