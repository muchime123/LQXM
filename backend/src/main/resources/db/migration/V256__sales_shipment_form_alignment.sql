-- 销售开单弹窗按氚云「销售出库」表单对齐（V255 去订单化之后，出库单=唯一销售单据）：
-- 补「参考单号/经销商/要求交期」三个单头字段（参考单号 sales_order_ref 列已存在，本次仅补后两列）。
-- 收款（定金/尾款/其他）继续以内嵌 formal_receipts 承载，开单时即可一并登记（收款时间可早于出库日），无需新增表。
-- 数据口径参考氚云源：客户名称/选择经销商/业务员(必填)/销售出库编号/订单备注 + 内嵌「收款详细」子表。

ALTER TABLE formal_sales_shipments
  ADD COLUMN dealer_name VARCHAR(300) NULL
    COMMENT '经销商/渠道商名称（走经销商渠道时选填，纯文本快照）' AFTER sales_order_ref,
  ADD COLUMN delivery_date DATE NULL
    COMMENT '要求交期（客户要求的发货日期，开单时选填，仅记录参考）' AFTER shipped_at;
