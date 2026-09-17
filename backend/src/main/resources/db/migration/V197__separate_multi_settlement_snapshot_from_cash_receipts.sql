-- 多订单结清导出的“已收款金额”是各出库单截至该时点的累计快照，
-- 不是一笔新的银行到账。将历史分配迁出正式核销口径，避免与收款记录重复计入。
UPDATE receipt_allocations ra
JOIN receipts r ON r.id = ra.receipt_id
SET ra.allocation_origin = 'SETTLEMENT_SNAPSHOT'
WHERE r.receipt_type = 'MULTI_OUTBOUND'
  AND ra.allocation_origin <> 'SETTLEMENT_SNAPSHOT';

-- 保持历史来源单据可追溯，但显式标明它们不是可供财务再次核销的到账余额。
UPDATE receipts r
SET r.unallocated_amount = 0
WHERE r.receipt_type = 'MULTI_OUTBOUND';

-- 对受影响出库立即重建显示金额：正式分配优先；没有正式分配时才显示氚云快照，
-- 但 received_verified=0，确保它不会被误解为财务确认到账。
UPDATE sales_outbounds o
JOIN (
    SELECT DISTINCT sales_outbound_id
    FROM receipt_allocations
    WHERE allocation_origin = 'SETTLEMENT_SNAPSHOT' AND sales_outbound_id IS NOT NULL
) affected ON affected.sales_outbound_id = o.id
LEFT JOIN (
    SELECT ra.sales_outbound_id, SUM(ra.allocated_amount) AS amount
    FROM receipt_allocations ra
    JOIN receipts r ON r.id = ra.receipt_id
    WHERE ra.allocation_status = 'CONFIRMED'
      AND ra.allocation_origin = 'DIRECT'
      AND r.legacy_migrated_tag IS NULL
      AND COALESCE(r.receipt_type, '') <> 'MULTI_OUTBOUND'
    GROUP BY ra.sales_outbound_id
) fact ON fact.sales_outbound_id = o.id
LEFT JOIN (
    SELECT sales_outbound_id, SUM(received_amount) AS amount
    FROM sales_outbound_payment_snapshots
    WHERE legacy_status = 'CONFIRMED'
    GROUP BY sales_outbound_id
) embedded ON embedded.sales_outbound_id = o.id
SET o.received_amount = LEAST(COALESCE(o.sales_amount, 0),
        CASE WHEN COALESCE(fact.amount, 0) > 0 THEN fact.amount
             ELSE GREATEST(COALESCE(embedded.amount, 0), COALESCE(o.source_received_amount, 0)) END),
    o.snapshot_received_amount = GREATEST(COALESCE(embedded.amount, 0), COALESCE(o.source_received_amount, 0)),
    o.received_source = CASE WHEN COALESCE(fact.amount, 0) > 0 THEN 'DIRECT'
                             WHEN GREATEST(COALESCE(embedded.amount, 0), COALESCE(o.source_received_amount, 0)) > 0 THEN 'H3_SNAPSHOT'
                             ELSE 'NONE' END,
    o.received_verified = CASE WHEN COALESCE(fact.amount, 0) > 0 THEN 1 ELSE 0 END,
    o.outstanding_amount = GREATEST(COALESCE(o.sales_amount, 0) - LEAST(COALESCE(o.sales_amount, 0),
        CASE WHEN COALESCE(fact.amount, 0) > 0 THEN fact.amount
             ELSE GREATEST(COALESCE(embedded.amount, 0), COALESCE(o.source_received_amount, 0)) END), 0),
    o.payment_status = CASE
        WHEN COALESCE(o.sales_amount, 0) > 0 AND LEAST(COALESCE(o.sales_amount, 0), CASE WHEN COALESCE(fact.amount, 0) > 0 THEN fact.amount ELSE GREATEST(COALESCE(embedded.amount, 0), COALESCE(o.source_received_amount, 0)) END) >= o.sales_amount THEN '已结清'
        WHEN LEAST(COALESCE(o.sales_amount, 0), CASE WHEN COALESCE(fact.amount, 0) > 0 THEN fact.amount ELSE GREATEST(COALESCE(embedded.amount, 0), COALESCE(o.source_received_amount, 0)) END) > 0 THEN '部分回款'
        ELSE '未回款' END,
    o.settlement_status = CASE
        WHEN COALESCE(o.sales_amount, 0) > 0 AND LEAST(COALESCE(o.sales_amount, 0), CASE WHEN COALESCE(fact.amount, 0) > 0 THEN fact.amount ELSE GREATEST(COALESCE(embedded.amount, 0), COALESCE(o.source_received_amount, 0)) END) >= o.sales_amount THEN 'SETTLED'
        WHEN LEAST(COALESCE(o.sales_amount, 0), CASE WHEN COALESCE(fact.amount, 0) > 0 THEN fact.amount ELSE GREATEST(COALESCE(embedded.amount, 0), COALESCE(o.source_received_amount, 0)) END) > 0 THEN 'PARTIAL'
        ELSE 'UNSETTLED' END;

-- 业务单生命周期必须使用正式核销金额；不能直接汇总所有 allocation。
UPDATE business_orders bo
JOIN (
    SELECT l.business_order_id,
           COALESCE(SUM(CASE WHEN o.outbound_status='CONFIRMED' THEN o.sales_amount ELSE 0 END), 0) AS receivable,
           COALESCE(SUM(CASE WHEN o.outbound_status='CONFIRMED' THEN formal.amount ELSE 0 END), 0) AS received
    FROM business_order_links l
    JOIN sales_outbounds o ON l.object_type='SALES_OUTBOUND' AND o.id=l.object_id
    JOIN (
        SELECT DISTINCT l2.business_order_id
        FROM business_order_links l2
        JOIN receipt_allocations ra2 ON l2.object_type='SALES_OUTBOUND' AND ra2.sales_outbound_id=l2.object_id
        WHERE l2.link_status='ACTIVE' AND ra2.allocation_origin='SETTLEMENT_SNAPSHOT'
    ) affected_bo ON affected_bo.business_order_id=l.business_order_id
    LEFT JOIN (
        SELECT ra.sales_outbound_id, SUM(ra.allocated_amount) AS amount
        FROM receipt_allocations ra
        JOIN receipts r ON r.id=ra.receipt_id
        WHERE ra.allocation_status='CONFIRMED'
          AND ra.allocation_origin='DIRECT'
          AND r.legacy_migrated_tag IS NULL
          AND COALESCE(r.receipt_type, '') <> 'MULTI_OUTBOUND'
        GROUP BY ra.sales_outbound_id
    ) formal ON formal.sales_outbound_id=o.id
    WHERE l.link_status='ACTIVE'
    GROUP BY l.business_order_id
) agg ON agg.business_order_id=bo.id
SET bo.receivable_amount=agg.receivable,
    bo.received_amount=agg.received,
    bo.settlement_status=CASE WHEN agg.receivable>0 AND agg.received>=agg.receivable THEN 'SETTLED'
                              WHEN agg.received>0 THEN 'PARTIAL' ELSE 'UNSETTLED' END,
    bo.lifecycle_status=CASE WHEN agg.receivable>0 AND agg.received>=agg.receivable THEN 'SETTLED'
                             WHEN agg.received>0 THEN 'PARTIALLY_PAID'
                             WHEN agg.receivable>0 THEN 'OUTBOUNDED' ELSE bo.lifecycle_status END;

-- 旧提成快照可能基于重复回款生成；先失效，下一次进入核算流程时按正式回款重建。
UPDATE business_commission_snapshots cs
JOIN (
    SELECT DISTINCT l.business_order_id
    FROM business_order_links l
    JOIN receipt_allocations ra ON l.object_type='SALES_OUTBOUND' AND ra.sales_outbound_id=l.object_id
    WHERE l.link_status='ACTIVE' AND ra.allocation_origin='SETTLEMENT_SNAPSHOT'
) affected ON affected.business_order_id=cs.business_order_id
SET cs.is_current=0
WHERE cs.is_current=1;
