-- V120: sales_contracts 拓展为订单表（合同订单 + 普通订单）
-- 合同编号列更名为订单编号（合同订单=ZJLQ...，普通订单=CK...，两类前缀互不冲突）。
-- 新增订单类型 + 销售出库（普通订单）相关列；明细新增出库产品维度列。

ALTER TABLE sales_contracts DROP INDEX uk_sc_contract_no;
ALTER TABLE sales_contracts CHANGE COLUMN contract_no order_no VARCHAR(50) NOT NULL COMMENT '订单编号，如 ZJLQ20260813004134 / CK20260820107631';
ALTER TABLE sales_contracts ADD UNIQUE KEY uk_sc_order_no (order_no);

ALTER TABLE sales_contracts
    ADD COLUMN order_type      VARCHAR(20) NOT NULL DEFAULT 'CONTRACT' COMMENT '订单类型：CONTRACT合同订单/NORMAL普通订单' AFTER order_no,
    ADD COLUMN ship_date       DATE NULL COMMENT '发货日期（普通订单）' AFTER signed_date,
    ADD COLUMN region          VARCHAR(50) NULL COMMENT '地区（普通订单）' AFTER ship_date,
    ADD COLUMN shipping_method VARCHAR(50) NULL COMMENT '运输方式（普通订单）' AFTER region,
    ADD COLUMN is_free_shipping TINYINT(1) NULL COMMENT '是否包邮（普通订单）' AFTER shipping_method,
    ADD COLUMN freight         DECIMAL(14,2) NULL COMMENT '运杂费（普通订单）' AFTER is_free_shipping,
    ADD COLUMN warehouse       VARCHAR(50) NULL COMMENT '出库仓库（普通订单）' AFTER freight,
    ADD COLUMN payment_status  VARCHAR(20) NULL COMMENT '客户付款状态：已结清/未付款/已付款未结清/拆单有余款' AFTER warehouse,
    ADD COLUMN unreceived_amount DECIMAL(14,2) NULL COMMENT '未收款金额（普通订单）' AFTER payment_status,
    ADD COLUMN total_purchase_amount DECIMAL(14,2) NULL COMMENT '采购金额合计（普通订单）' AFTER unreceived_amount,
    ADD COLUMN total_sales_amount DECIMAL(14,2) NULL COMMENT '销售金额合计（普通订单）' AFTER total_purchase_amount,
    ADD COLUMN profit_amount    DECIMAL(14,2) NULL COMMENT '利润金额（普通订单）' AFTER total_sales_amount,
    ADD COLUMN received_amount  DECIMAL(14,2) NULL COMMENT '已收款金额（普通订单）' AFTER profit_amount,
    ADD COLUMN invoiced_amount  DECIMAL(14,2) NULL COMMENT '已开票金额（普通订单）' AFTER received_amount,
    ADD COLUMN uninvoiced_amount DECIMAL(14,2) NULL COMMENT '未开票金额（普通订单）' AFTER invoiced_amount,
    ADD COLUMN invoicer         VARCHAR(50) NULL COMMENT '开票员（普通订单）' AFTER uninvoiced_amount,
    ADD COLUMN business_date    DATE NULL COMMENT '业务日期（普通订单）' AFTER invoicer,
    ADD COLUMN contact_phone    VARCHAR(50) NULL COMMENT '联系方式（普通订单）' AFTER business_date,
    ADD COLUMN customer_address VARCHAR(300) NULL COMMENT '客户详细地址（普通订单）' AFTER contact_phone;

ALTER TABLE sales_contract_items
    ADD COLUMN category      VARCHAR(50) NULL COMMENT '类目（普通订单明细）' AFTER line_total,
    ADD COLUMN product_code  VARCHAR(50) NULL COMMENT '产品编号（普通订单明细）' AFTER category,
    ADD COLUMN purchase_price DECIMAL(14,4) NULL COMMENT '采购单价（普通订单明细）' AFTER product_code,
    ADD COLUMN profit        DECIMAL(14,4) NULL COMMENT '单产品利润（普通订单明细）' AFTER purchase_price,
    ADD COLUMN stock_qty     DECIMAL(14,4) NULL COMMENT '库存数量（普通订单明细）' AFTER profit;