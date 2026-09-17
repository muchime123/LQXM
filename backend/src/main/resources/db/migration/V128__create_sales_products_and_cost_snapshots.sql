-- V128: 销售产品主数据与成本时点快照（P3.1 / P3.2）
-- 设计依据：docs/h3-business-domain-design.md §3.6 第二阶段、§P3 第 1-2 项
-- 核心约束：
--   1. sales_products 不复用出库明细 ObjectId 作主键，独立稳定业务编号
--   2. 成本是时点快照，按 snapshot_date 保留历史，覆盖更新
--   3. sales_outbound_items 增 sales_product_id 关联产品主数据，便于按产品聚合销售额/采购额/利润
-- 来源链：MANUAL（手工维护）/ FROM_OUTBOUND_AGGREGATE（从出库明细聚合）/ H3YUN_PRODUCT_IMPORT（待 P3 独立产品导出接入）

CREATE TABLE sales_products (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键',
    product_code    VARCHAR(100) NOT NULL COMMENT '稳定产品编号；无外部编号时系统生成',
    product_name    VARCHAR(300) NOT NULL COMMENT '产品名称',
    specification   VARCHAR(300) NULL COMMENT '规格型号',
    unit            VARCHAR(30) NULL COMMENT '单位',
    category        VARCHAR(100) NULL COMMENT '一级类目',
    sub_category    VARCHAR(100) NULL COMMENT '二级类目',
    source          VARCHAR(30) NOT NULL DEFAULT 'MANUAL' COMMENT '来源：MANUAL/FROM_OUTBOUND_AGGREGATE/H3YUN_PRODUCT_IMPORT',
    external_source VARCHAR(30) NULL COMMENT '外部来源（仅导入产生）',
    external_id     VARCHAR(100) NULL COMMENT '外部 ObjectId（仅导入产生）',
    status          VARCHAR(20) NOT NULL DEFAULT 'PENDING_REVIEW' COMMENT '状态：PENDING_REVIEW/ACTIVE/DISCONTINUED',
    confirmed_by    VARCHAR(100) NULL COMMENT '确认人',
    confirmed_at    DATETIME NULL COMMENT '确认时间',
    source_hash     CHAR(64) NULL COMMENT '导入内容指纹（仅导入产生）',
    last_synced_at  DATETIME NULL COMMENT '最近同步时间（仅导入产生）',
    remark          VARCHAR(1000) NULL,
    deleted_at      DATETIME NULL,
    created_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_sp_code (product_code),
    KEY idx_sp_name (product_name),
    KEY idx_sp_category (category),
    KEY idx_sp_status (status),
    KEY idx_sp_external (external_source, external_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='销售产品主数据';

CREATE TABLE sales_product_cost_snapshots (
    id                  BIGINT PRIMARY KEY AUTO_INCREMENT,
    sales_product_id    BIGINT NOT NULL COMMENT '关联销售产品主数据',
    snapshot_date       DATE NOT NULL COMMENT '成本时点',
    cost_unit_price     DECIMAL(14,4) NULL COMMENT '标准成本单价（财务核定）',
    purchase_unit_price DECIMAL(14,4) NULL COMMENT '实际采购单价（来源取值）',
    currency            VARCHAR(10) NOT NULL DEFAULT 'CNY',
    source              VARCHAR(30) NOT NULL DEFAULT 'MANUAL' COMMENT '来源：MANUAL/PURCHASE_INBOUND_IMPORT/FROM_OUTBOUND_AGGREGATE',
    reference_no        VARCHAR(100) NULL COMMENT '参考单号',
    remark              VARCHAR(1000) NULL,
    source_hash         CHAR(64) NULL,
    created_at          DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_spcs_product_date (sales_product_id, snapshot_date),
    KEY idx_spcs_product (sales_product_id),
    KEY idx_spcs_date (snapshot_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='销售产品成本时点快照';

ALTER TABLE sales_outbound_items
    ADD COLUMN sales_product_id BIGINT NULL COMMENT '关联销售产品主数据' AFTER product_code,
    ADD KEY idx_soi_sales_product (sales_product_id);
