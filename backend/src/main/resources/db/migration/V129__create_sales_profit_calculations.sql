-- V129: 销售利润逐单对账表（P3.3 财务逐单验证利润）
-- 设计依据：docs/h3-business-domain-design.md §P3 第 3 项
-- 核心约束：
--   1. 一条 sales_outbound_item 对应一条对账记录（UNIQUE）
--   2. 仅对 line_type='PRODUCT' 且有 sales_product_id 关联的明细计算
--   3. 成本快照取 ship_date 当天或之前最近的（LEFT JOIN 取 MAX(snapshot_date <= ship_date)）
--   4. 仅对 status='ACTIVE' 产品的成本快照生效；缺快照时 calculated_gross_profit 为 NULL，状态置 PENDING
--   5. 与 sales_outbound_items.source_profit（氚云源毛利）对账，差异 > 阈值（默认 1% 或 1 元）置 DIFFERENCE

CREATE TABLE sales_profit_calculations (
    id                          BIGINT PRIMARY KEY AUTO_INCREMENT,
    sales_outbound_item_id      BIGINT NOT NULL COMMENT '关联销售出库明细',
    sales_outbound_id           BIGINT NULL COMMENT '冗余出库主单 id，便于按出库聚合查询',
    sales_product_id            BIGINT NULL COMMENT '关联销售产品主数据',
    ship_date                   DATE NULL COMMENT '冗余出库发货日',
    outbound_no                 VARCHAR(100) NULL COMMENT '冗余出库编号',
    customer_name               VARCHAR(300) NULL COMMENT '冗余客户名',
    salesperson_name            VARCHAR(100) NULL COMMENT '冗余业务员',
    product_code                VARCHAR(100) NULL COMMENT '冗余产品编号',
    product_name                VARCHAR(300) NULL COMMENT '冗余产品名称',
    quantity                    DECIMAL(14,4) NULL COMMENT '冗余出库数量',
    sales_unit_price            DECIMAL(14,4) NULL COMMENT '冗余销售单价',
    sales_total                 DECIMAL(14,4) NULL COMMENT '冗余销售总额',
    source_purchase_unit_price  DECIMAL(14,4) NULL COMMENT '氚云源采购单价（来自出库明细）',
    source_gross_profit         DECIMAL(14,4) NULL COMMENT '氚云源毛利（来自出库明细 source_profit）',
    snapshot_date_used         DATE NULL COMMENT '计算时采用的成本快照日期',
    cost_snapshot_id            BIGINT NULL COMMENT '计算时采用的成本快照 id',
    snapshot_purchase_unit_price DECIMAL(14,4) NULL COMMENT '快照采购单价',
    snapshot_cost_unit_price   DECIMAL(14,4) NULL COMMENT '快照标准成本单价',
    calculated_gross_profit    DECIMAL(14,4) NULL COMMENT '按快照计算的毛利 = sales_total - 快照成本*数量',
    calculated_gross_margin    DECIMAL(7,4) NULL COMMENT '计算毛利率 % = profit / sales_total * 100',
    profit_difference          DECIMAL(14,4) NULL COMMENT '差异 = calculated - source',
    profit_difference_pct      DECIMAL(7,4) NULL COMMENT '差异率 % = difference / NULLIF(source,0) * 100',
    has_difference             TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否差异行（差异>阈值=1）',
    verification_status        VARCHAR(20) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/CONFIRMED/DIFFERENCE/REVIEWED',
    verified_by                VARCHAR(100) NULL COMMENT '复核人',
    verified_at                DATETIME NULL COMMENT '复核时间',
    review_note                VARCHAR(1000) NULL COMMENT '复核备注',
    calculated_at              DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '最近计算时间',
    created_at                 DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at                 DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_spc_item (sales_outbound_item_id),
    KEY idx_spc_outbound (sales_outbound_id),
    KEY idx_spc_product (sales_product_id),
    KEY idx_spc_ship_date (ship_date),
    KEY idx_spc_status (verification_status),
    KEY idx_spc_difference (has_difference)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='销售利润逐单对账记录';
