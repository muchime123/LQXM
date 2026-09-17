-- V118: 销售合同主表 + 明细（P2）
-- 数据来源：历史销售合同订单 Excel（去重整理版）。每份合同一行主记录 + 多条明细。
-- 统计字段（合同数/累计金额/首购/最近采购）一律不冗余，从本表实时聚合到 customers。

CREATE TABLE sales_contracts (
    id                BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键',
    contract_no       VARCHAR(50) NOT NULL COMMENT '合同编号，如 ZJLQ20260813004134',
    customer_id       BIGINT NULL COMMENT '关联客户 customers.id（无买方或未匹配时为空）',
    customer_name     VARCHAR(200) NULL COMMENT '买方名称快照',
    buyer_address     VARCHAR(300) NULL COMMENT '买方地址',
    salesperson_id    BIGINT NULL COMMENT '关联公司成员 company_team_members.id',
    salesperson_name  VARCHAR(50) NULL COMMENT '业务员姓名快照',
    signed_date       DATE NULL COMMENT '签订时间',
    total_amount      DECIMAL(14,2) NOT NULL DEFAULT 0 COMMENT '合同金额',
    amount_in_words   VARCHAR(200) NULL COMMENT '合同金额大写',
    status            VARCHAR(20) NOT NULL DEFAULT 'EFFECTIVE' COMMENT '状态：EFFECTIVE已生效/DRAFT草稿',
    line_count        INT NOT NULL DEFAULT 0 COMMENT '明细行数',
    remark            VARCHAR(1000) NULL COMMENT '备注',
    deleted_at        DATETIME NULL COMMENT '软删除时间',
    created_at        DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at        DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
    UNIQUE KEY uk_sc_contract_no (contract_no),
    KEY idx_sc_customer (customer_id),
    KEY idx_sc_signed_date (signed_date),
    KEY idx_sc_salesperson (salesperson_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='销售合同主表';

CREATE TABLE sales_contract_items (
    id            BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键',
    contract_id   BIGINT NOT NULL COMMENT '合同ID',
    line_no       INT NOT NULL COMMENT '明细行号',
    product_name  VARCHAR(300) NULL COMMENT '产品名称',
    spec          VARCHAR(300) NULL COMMENT '规格型号',
    quantity      DECIMAL(14,4) NULL COMMENT '数量',
    unit          VARCHAR(20) NULL COMMENT '单位',
    unit_price    DECIMAL(14,4) NULL COMMENT '单价',
    line_total    DECIMAL(14,4) NULL COMMENT '金额小计',
    remark        VARCHAR(300) NULL COMMENT '明细备注',
    created_at    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
    KEY idx_sci_contract (contract_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='销售合同明细';