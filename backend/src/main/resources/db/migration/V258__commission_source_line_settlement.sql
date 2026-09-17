-- 氚云销售出库提成：按来源明细占用，支持任意及重叠日期范围的补充核算。
-- 一条收款明细或一张出库单的运杂费只能被一个有效核算版本占用；版本作废时释放。
CREATE TABLE commission_calculation_source_lines (
    id                      BIGINT PRIMARY KEY AUTO_INCREMENT,
    version_id              BIGINT NOT NULL COMMENT '核算版本 commission_calculation_versions.id',
    employee_id             BIGINT NOT NULL COMMENT '核算时业务员 employees.id',
    employee_name           VARCHAR(100) NOT NULL COMMENT '业务员姓名快照',
    line_type               VARCHAR(20) NOT NULL COMMENT 'RECEIPT=收款 / FREIGHT=运杂费',
    source_id               BIGINT NOT NULL COMMENT 'RECEIPT=payment snapshot id；FREIGHT=sales_outbounds.id',
    outbound_id             BIGINT NOT NULL COMMENT '氚云销售出库 sales_outbounds.id',
    outbound_no             VARCHAR(100) NULL COMMENT '氚云销售出库单号快照',
    customer_name           VARCHAR(255) NULL COMMENT '客户名称快照',
    occurrence_date         DATE NOT NULL COMMENT '收款日期或发货日期',
    channel_type            VARCHAR(20) NOT NULL COMMENT 'DEALER / RETAIL',
    amount                  DECIMAL(14,2) NOT NULL COMMENT '来源金额；运杂费保存正数',
    applied_rate            DECIMAL(8,6) NOT NULL COMMENT '核算时适用提成率快照',
    commission_contribution DECIMAL(16,6) NOT NULL COMMENT '提成贡献；运杂费为负数',
    status                  VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE=有效占用 / RELEASED=版本作废已释放',
    active_source_key       VARCHAR(80) NULL COMMENT '有效占用键 TYPE:sourceId；释放后置 NULL',
    created_at              DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    released_at             DATETIME NULL,
    UNIQUE KEY uk_ccsl_active_source (active_source_key),
    KEY idx_ccsl_version (version_id),
    KEY idx_ccsl_employee (employee_id),
    KEY idx_ccsl_type_date (line_type, occurrence_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='氚云销售出库提成核算来源明细及有效占用关系';

-- 兼容既有 FINAL 版本：按其日期范围回填已经核算过的来源，避免升级后被当成新增数据再次核算。
-- 若历史上存在重叠版本，同一来源归入最早覆盖它的 FINAL 版本；唯一有效键保证只占用一次。
INSERT IGNORE INTO commission_calculation_source_lines(
    version_id,employee_id,employee_name,line_type,source_id,outbound_id,outbound_no,
    customer_name,occurrence_date,channel_type,amount,applied_rate,commission_contribution,
    status,active_source_key)
SELECT v.id,o.employee_id,COALESCE(o.salesperson_name,e.name,CONCAT('业务员#',o.employee_id)),
       'RECEIPT',ps.id,o.id,o.outbound_no,o.customer_name,ps.receipt_date,
       CASE WHEN (o.dealer_name IS NOT NULL AND o.dealer_name<>'')
                  OR (o.sales_amount>0 AND COALESCE(o.source_gross_profit,0)/o.sales_amount<0.15)
            THEN 'DEALER' ELSE 'RETAIL' END,
       ps.received_amount,
       CASE WHEN (o.dealer_name IS NOT NULL AND o.dealer_name<>'')
                  OR (o.sales_amount>0 AND COALESCE(o.source_gross_profit,0)/o.sales_amount<0.15)
            THEN i.dealer_rate ELSE i.retail_rate END,
       ps.received_amount *
       CASE WHEN (o.dealer_name IS NOT NULL AND o.dealer_name<>'')
                  OR (o.sales_amount>0 AND COALESCE(o.source_gross_profit,0)/o.sales_amount<0.15)
            THEN i.dealer_rate ELSE i.retail_rate END,
       'ACTIVE',CONCAT('RECEIPT:',ps.id)
FROM sales_outbound_payment_snapshots ps
JOIN sales_outbounds o ON o.id=ps.sales_outbound_id
LEFT JOIN employees e ON e.id=o.employee_id
JOIN commission_calculation_versions v ON v.status='FINAL'
    AND ps.receipt_date BETWEEN v.start_date AND v.end_date
JOIN commission_calculation_items i ON i.version_id=v.id AND i.employee_id=o.employee_id
WHERE ps.legacy_status='CONFIRMED' AND o.outbound_status='CONFIRMED'
  AND o.employee_id IS NOT NULL
  AND o.external_source IN ('H3YUN','H3YUN_RECOVERED','H3_HISTORICAL_RECOVERED')
ORDER BY v.calculated_at,v.id,ps.id;

INSERT IGNORE INTO commission_calculation_source_lines(
    version_id,employee_id,employee_name,line_type,source_id,outbound_id,outbound_no,
    customer_name,occurrence_date,channel_type,amount,applied_rate,commission_contribution,
    status,active_source_key)
SELECT v.id,o.employee_id,COALESCE(o.salesperson_name,e.name,CONCAT('业务员#',o.employee_id)),
       'FREIGHT',o.id,o.id,o.outbound_no,o.customer_name,COALESCE(o.ship_date,o.business_date),
       CASE WHEN (o.dealer_name IS NOT NULL AND o.dealer_name<>'')
                  OR (o.sales_amount>0 AND COALESCE(o.source_gross_profit,0)/o.sales_amount<0.15)
            THEN 'DEALER' ELSE 'RETAIL' END,
       COALESCE(o.freight,0),
       CASE WHEN (o.dealer_name IS NOT NULL AND o.dealer_name<>'')
                  OR (o.sales_amount>0 AND COALESCE(o.source_gross_profit,0)/o.sales_amount<0.15)
            THEN i.dealer_rate ELSE i.retail_rate END,
       -COALESCE(o.freight,0) *
       CASE WHEN (o.dealer_name IS NOT NULL AND o.dealer_name<>'')
                  OR (o.sales_amount>0 AND COALESCE(o.source_gross_profit,0)/o.sales_amount<0.15)
            THEN i.dealer_rate ELSE i.retail_rate END,
       'ACTIVE',CONCAT('FREIGHT:',o.id)
FROM sales_outbounds o
LEFT JOIN employees e ON e.id=o.employee_id
JOIN commission_calculation_versions v ON v.status='FINAL'
    AND COALESCE(o.ship_date,o.business_date)>=DATE_FORMAT(v.end_date-INTERVAL 1 MONTH,'%Y-%m-01')
    AND COALESCE(o.ship_date,o.business_date)<DATE_FORMAT(v.end_date,'%Y-%m-01')
JOIN commission_calculation_items i ON i.version_id=v.id AND i.employee_id=o.employee_id
WHERE o.outbound_status='CONFIRMED' AND o.employee_id IS NOT NULL AND COALESCE(o.freight,0)<>0
  AND o.external_source IN ('H3YUN','H3YUN_RECOVERED','H3_HISTORICAL_RECOVERED')
ORDER BY v.calculated_at,v.id,o.id;
