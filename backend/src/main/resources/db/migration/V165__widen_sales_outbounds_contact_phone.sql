-- 氚云「联系方式」字段可能同时包含多个电话/微信/备注等混写内容，长度会超过 VARCHAR(100)，
-- 导致导入 confirm 时报：Data too long for column 'contact_phone'（409 Conflict）。
-- 与 V134 中 customer_address/order_remark 等长文本类字段的处理一致，放宽为 TEXT。
ALTER TABLE sales_outbounds MODIFY COLUMN contact_phone TEXT NULL;
