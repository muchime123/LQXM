-- 销售出库提成核算：单据级变化跟踪 + 手动核算版本（2026-08-31 财务确认口径）
--
-- 背景：销售出库数据可能被修正（收款/运杂费变化），已结算过提成的「已生效」出库单
--       发生变化时，应记录单据级变化，在下一次核算（结账）时与本期提成一起结算，
--       而不是「导入前后总额做差」（旧方案 V215 已废弃，本脚本将其表删除）。
--
-- 核心机制：
--   1) commission_outbound_baselines：每张已生效（CONFIRMED）出库单的提成基线
--      （该单收款合计 + 运杂费，按经销/零售拆分）。导入时用「当前值 vs 基线」逐单对比。
--   2) commission_outbound_changes：已生效单的变化明细（单据级，财务可下钻核对），
--      核算版本生成时按业务员汇总为「修正提成」，然后标记为已处理。
--   3) commission_calculation_versions / _items：财务手动「核算结账」生成的版本
--      （每版本 = 本期正常提成 + 修正提成），支持版本间逐业务员对比。
--
-- 幂等：变化明细按 (batch_id, outbound_id) 唯一；同一批次的单只可能产生一条变化记录。

-- ============ 废弃旧方案 ============
DROP TABLE IF EXISTS commission_import_deltas;

-- ============ 1. 已生效出库单提成基线 ============
CREATE TABLE IF NOT EXISTS commission_outbound_baselines (
    id                BIGINT PRIMARY KEY AUTO_INCREMENT,
    outbound_id       BIGINT NOT NULL COMMENT '销售出库单 sales_outbounds.id',
    employee_id       BIGINT NOT NULL COMMENT '业务员 employees.id（基线时点）',
    employee_name     VARCHAR(100) NOT NULL COMMENT '业务员姓名快照',
    receipt_total     DECIMAL(14,2) NOT NULL DEFAULT 0 COMMENT '该单已生效累计收款基线',
    receipt_dealer    DECIMAL(14,2) NOT NULL DEFAULT 0 COMMENT '经销商收款基线',
    receipt_retail    DECIMAL(14,2) NOT NULL DEFAULT 0 COMMENT '零售收款基线',
    freight_total     DECIMAL(14,2) NOT NULL DEFAULT 0 COMMENT '该单运杂费基线',
    freight_dealer    DECIMAL(14,2) NOT NULL DEFAULT 0 COMMENT '经销商运杂费基线',
    freight_retail    DECIMAL(14,2) NOT NULL DEFAULT 0 COMMENT '零售运杂费基线',
    updated_at        DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_cob_outbound (outbound_id),
    KEY idx_cob_employee (employee_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='已生效销售出库单的提成基线（导入时对比识别修正变化）';

-- ============ 2. 已生效单变化明细（修正流水） ============
CREATE TABLE IF NOT EXISTS commission_outbound_changes (
    id                    BIGINT PRIMARY KEY AUTO_INCREMENT,
    batch_id              BIGINT NOT NULL COMMENT '导入批次 h3_import_batches.id',
    outbound_id           BIGINT NOT NULL COMMENT '销售出库单 sales_outbounds.id',
    outbound_no           VARCHAR(100) NOT NULL COMMENT '出库单号',
    customer_name         VARCHAR(255) NULL COMMENT '客户名称',
    employee_id           BIGINT NOT NULL COMMENT '业务员 employees.id（当前归属）',
    employee_name         VARCHAR(100) NOT NULL COMMENT '业务员姓名快照',
    recorded_at           DATETIME NOT NULL COMMENT '变化识别时点',
    source                VARCHAR(50) NOT NULL DEFAULT 'IMPORT' COMMENT '来源：IMPORT=氚云导入',
    before_receipt_dealer DECIMAL(14,2) NOT NULL DEFAULT 0 COMMENT '变化前经销商收款',
    after_receipt_dealer  DECIMAL(14,2) NOT NULL DEFAULT 0 COMMENT '变化后经销商收款',
    before_receipt_retail DECIMAL(14,2) NOT NULL DEFAULT 0 COMMENT '变化前零售收款',
    after_receipt_retail  DECIMAL(14,2) NOT NULL DEFAULT 0 COMMENT '变化后零售收款',
    before_freight_dealer DECIMAL(14,2) NOT NULL DEFAULT 0 COMMENT '变化前经销商运杂费',
    after_freight_dealer  DECIMAL(14,2) NOT NULL DEFAULT 0 COMMENT '变化后经销商运杂费',
    before_freight_retail DECIMAL(14,2) NOT NULL DEFAULT 0 COMMENT '变化前零售运杂费',
    after_freight_retail  DECIMAL(14,2) NOT NULL DEFAULT 0 COMMENT '变化后零售运杂费',
    dealer_rate           DECIMAL(6,4) NOT NULL COMMENT '变化识别时点经销商比例',
    retail_rate           DECIMAL(6,4) NOT NULL COMMENT '变化识别时点零售比例',
    change_commission     DECIMAL(14,2) NOT NULL DEFAULT 0 COMMENT '本单变化提成（负数=修正需抵扣）',
    remark                VARCHAR(500) NULL COMMENT '来源说明',
    status                VARCHAR(20) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING=未核算 / PROCESSED=已并入某核算版本',
    version_id            BIGINT NULL COMMENT '并入的核算版本 commission_calculation_versions.id',
    UNIQUE KEY uk_coc_batch_outbound (batch_id, outbound_id),
    KEY idx_coc_employee_status (employee_id, status),
    KEY idx_coc_status (status),
    KEY idx_coc_recorded (recorded_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='已生效销售出库单的变化明细（导入时识别，核算版本消费）';

-- ============ 3. 核算版本 ============
CREATE TABLE IF NOT EXISTS commission_calculation_versions (
    id            BIGINT PRIMARY KEY AUTO_INCREMENT,
    period        VARCHAR(7) NOT NULL COMMENT '核算期间 yyyy-MM（本期正常提成归属月）',
    version_no    VARCHAR(20) NOT NULL COMMENT '版本号（同期间内递增，如 2026-08-1）',
    remark        VARCHAR(500) NULL COMMENT '核算备注',
    status        VARCHAR(20) NOT NULL DEFAULT 'FINAL' COMMENT 'FINAL=已结账',
    calculated_at DATETIME NOT NULL COMMENT '核算时点',
    created_by    BIGINT NULL COMMENT '操作人 employees.id',
    created_by_name VARCHAR(100) NULL COMMENT '操作人姓名',
    UNIQUE KEY uk_ccv_period_no (period, version_no),
    KEY idx_ccv_period (period)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='销售出库提成核算版本（财务手动核算结账生成）';

-- ============ 4. 版本明细（每业务员一行） ============
CREATE TABLE IF NOT EXISTS commission_calculation_items (
    id                   BIGINT PRIMARY KEY AUTO_INCREMENT,
    version_id           BIGINT NOT NULL COMMENT '核算版本 commission_calculation_versions.id',
    employee_id          BIGINT NOT NULL COMMENT '业务员 employees.id',
    employee_name        VARCHAR(100) NOT NULL COMMENT '业务员姓名快照',
    dealer_rate          DECIMAL(6,4) NOT NULL COMMENT '核算时点经销商比例',
    retail_rate          DECIMAL(6,4) NOT NULL COMMENT '核算时点零售比例',
    base_receipt_dealer  DECIMAL(14,2) NOT NULL DEFAULT 0 COMMENT '本期正常经销商回款',
    base_receipt_retail  DECIMAL(14,2) NOT NULL DEFAULT 0 COMMENT '本期正常零售回款',
    base_freight_dealer  DECIMAL(14,2) NOT NULL DEFAULT 0 COMMENT '本期正常经销商运杂费（上月扣减）',
    base_freight_retail  DECIMAL(14,2) NOT NULL DEFAULT 0 COMMENT '本期正常零售运杂费（上月扣减）',
    base_commission      DECIMAL(14,2) NOT NULL DEFAULT 0 COMMENT '本期正常提成',
    adj_receipt_dealer   DECIMAL(14,2) NOT NULL DEFAULT 0 COMMENT '修正经销商回款（已生效单变化合计）',
    adj_receipt_retail   DECIMAL(14,2) NOT NULL DEFAULT 0 COMMENT '修正零售回款',
    adj_freight_dealer   DECIMAL(14,2) NOT NULL DEFAULT 0 COMMENT '修正经销商运杂费',
    adj_freight_retail   DECIMAL(14,2) NOT NULL DEFAULT 0 COMMENT '修正零售运杂费',
    adj_commission       DECIMAL(14,2) NOT NULL DEFAULT 0 COMMENT '修正提成（负数=抵扣）',
    total_commission     DECIMAL(14,2) NOT NULL DEFAULT 0 COMMENT '本期应发提成 = 正常 + 修正',
    UNIQUE KEY uk_cci_version_emp (version_id, employee_id),
    KEY idx_cci_employee (employee_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='核算版本明细（每业务员一行，正常+修正提成）';
