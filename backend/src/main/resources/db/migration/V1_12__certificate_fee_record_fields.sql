-- 专利缴费记录扩展字段
-- 参考：专利工作操作手册 第五章（缴费流程）、第七章（归档动作）

SET @exists = (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'certificate_fee_records' AND column_name = 'order_no');
SET @sql = IF(@exists = 0, 'ALTER TABLE certificate_fee_records ADD COLUMN order_no VARCHAR(100) COMMENT ''订单编号（专利业务办理系统生成）''', 'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @exists = (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'certificate_fee_records' AND column_name = 'transaction_no');
SET @sql = IF(@exists = 0, 'ALTER TABLE certificate_fee_records ADD COLUMN transaction_no VARCHAR(100) COMMENT ''订单交易号（支付平台返回）''', 'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @exists = (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'certificate_fee_records' AND column_name = 'receipt_title');
SET @sql = IF(@exists = 0, 'ALTER TABLE certificate_fee_records ADD COLUMN receipt_title VARCHAR(200) COMMENT ''票据抬头/公司名称''', 'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @exists = (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'certificate_fee_records' AND column_name = 'invoice_status');
SET @sql = IF(@exists = 0, 'ALTER TABLE certificate_fee_records ADD COLUMN invoice_status VARCHAR(20) COMMENT ''发票状态：未开票/已开票/已归档''', 'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;