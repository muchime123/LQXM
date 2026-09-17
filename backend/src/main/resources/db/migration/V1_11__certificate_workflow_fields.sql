-- 专利证书管理功能整合专利工作操作手册 V1.0
-- 新增字段：发票状态、代理机构联系人、流程阶段、审查进度备注
-- 参考：专利工作操作手册 第九章（台账字段）、第八章（申请对接流程）

SET @exists = (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'certificates' AND column_name = 'invoice_status');
SET @sql = IF(@exists = 0, 'ALTER TABLE certificates ADD COLUMN invoice_status VARCHAR(20) COMMENT ''发票状态：未开票/已开票/已归档''', 'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @exists = (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'certificates' AND column_name = 'agency_contact');
SET @sql = IF(@exists = 0, 'ALTER TABLE certificates ADD COLUMN agency_contact VARCHAR(100) COMMENT ''代理机构联系人''', 'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @exists = (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'certificates' AND column_name = 'process_stage');
SET @sql = IF(@exists = 0, 'ALTER TABLE certificates ADD COLUMN process_stage VARCHAR(20) COMMENT ''当前流程阶段：需求提出/方案确认/文件撰写/提交申请/过程跟进/授权归档/已授权维护''', 'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @exists = (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'certificates' AND column_name = 'review_progress');
SET @sql = IF(@exists = 0, 'ALTER TABLE certificates ADD COLUMN review_progress TEXT COMMENT ''审查进度备注（审查意见、补正记录等）''', 'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;