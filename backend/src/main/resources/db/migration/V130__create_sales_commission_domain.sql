-- V130: 销售提成规则、计算与结算（P3.4 提成规则、计算与结算）
-- 设计依据：docs/h3-business-domain-design.md §P3 第 4 项
-- 三张表：
--   1. sales_commission_rules         提成规则（业务员 × 类目 × 毛利率阶梯）
--   2. sales_commission_calculations  按对账记录维度的提成计算（关联 sales_profit_calculations）
--   3. sales_commission_settlements    按月 × 业务员的结算单

CREATE TABLE sales_commission_rules (
    id                BIGINT PRIMARY KEY AUTO_INCREMENT,
    rule_name         VARCHAR(100) NOT NULL COMMENT '规则名称',
    salesperson_name  VARCHAR(100) NULL COMMENT '适用业务员，NULL=全员通用',
    product_category  VARCHAR(100) NULL COMMENT '适用产品类目，NULL=所有类目',
    margin_min        DECIMAL(5,2) NOT NULL DEFAULT 0 COMMENT '毛利率下限（含，%）',
    margin_max        DECIMAL(5,2) NOT NULL DEFAULT 100 COMMENT '毛利率上限（不含，%）',
    commission_type   VARCHAR(10) NOT NULL DEFAULT 'PERCENT' COMMENT 'PERCENT=按利润百分比 / FIXED_AMOUNT=固定金额',
    commission_value  DECIMAL(14,4) NOT NULL COMMENT '提成比例 % 或固定金额（元）',
    effective_from    DATE NOT NULL COMMENT '生效起始日',
    effective_to      DATE NULL COMMENT '生效结束日，NULL=长期',
    priority          INT NOT NULL DEFAULT 100 COMMENT '优先级，数值小优先',
    status            VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/DISABLED',
    description       VARCHAR(500) NULL,
    created_at        DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_scr_status (status),
    KEY idx_scr_salesperson (salesperson_name),
    KEY idx_scr_category (product_category)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='销售提成规则';

CREATE TABLE sales_commission_calculations (
    id                          BIGINT PRIMARY KEY AUTO_INCREMENT,
    sales_profit_calculation_id BIGINT NOT NULL COMMENT '关联销售利润对账记录',
    sales_outbound_item_id      BIGINT NULL COMMENT '冗余出库明细 id',
    salesperson_name            VARCHAR(100) NULL COMMENT '冗余业务员',
    ship_date                   DATE NULL COMMENT '冗余发货日',
    outbound_no                 VARCHAR(100) NULL COMMENT '冗余出库编号',
    customer_name               VARCHAR(300) NULL COMMENT '冗余客户名',
    product_code                VARCHAR(100) NULL,
    product_name                VARCHAR(300) NULL,
    product_category            VARCHAR(100) NULL COMMENT '冗余产品类目',
    calculated_gross_profit    DECIMAL(14,4) NULL COMMENT '冗余计算利润（来自对账）',
    calculated_gross_margin    DECIMAL(7,4) NULL COMMENT '冗余毛利率 %',
    commission_rule_id         BIGINT NULL COMMENT '命中的规则 id',
    rule_name                  VARCHAR(100) NULL COMMENT '冗余规则名',
    commission_basis           DECIMAL(14,4) NULL COMMENT '提成基数（利润或 1）',
    commission_rate            DECIMAL(7,4) NULL COMMENT '提成比例 %',
    commission_amount          DECIMAL(14,4) NULL COMMENT '提成金额',
    settlement_period          VARCHAR(20) NULL COMMENT '结算周期标识 YYYY-MM',
    settlement_id              BIGINT NULL COMMENT '关联结算单 id',
    settled_at                 DATETIME NULL COMMENT '结算时间',
    calculated_at              DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_at                 DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at                 DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_scc_profit (sales_profit_calculation_id),
    KEY idx_scc_salesperson (salesperson_name),
    KEY idx_scc_period (settlement_period),
    KEY idx_scc_settlement (settlement_id),
    KEY idx_scc_ship_date (ship_date),
    KEY idx_scc_rule (commission_rule_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='销售提成计算明细';

CREATE TABLE sales_commission_settlements (
    id                BIGINT PRIMARY KEY AUTO_INCREMENT,
    period            VARCHAR(20) NOT NULL COMMENT '周期标识 YYYY-MM',
    salesperson_name  VARCHAR(100) NOT NULL COMMENT '业务员',
    total_orders      INT NOT NULL DEFAULT 0 COMMENT '订单数',
    total_sales       DECIMAL(14,4) NOT NULL DEFAULT 0 COMMENT '总销售额',
    total_profit      DECIMAL(14,4) NOT NULL DEFAULT 0 COMMENT '总利润',
    total_commission  DECIMAL(14,4) NOT NULL DEFAULT 0 COMMENT '总提成',
    status            VARCHAR(20) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/CONFIRMED/PAID',
    confirmed_at      DATETIME NULL,
    confirmed_by      VARCHAR(100) NULL,
    paid_at           DATETIME NULL,
    paid_by           VARCHAR(100) NULL,
    payment_note      VARCHAR(500) NULL,
    created_at        DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_settlement (period, salesperson_name),
    KEY idx_settlement_status (status),
    KEY idx_settlement_period (period)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='销售提成结算单';
