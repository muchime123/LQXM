-- 添加业务体系相关字段（兼容已有字段）
SET @exists = (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'company_info' AND column_name = 'business_model');
SET @sql = IF(@exists = 0, 'ALTER TABLE company_info ADD COLUMN business_model TEXT AFTER `values`', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @exists = (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'company_info' AND column_name = 'service_capabilities');
SET @sql = IF(@exists = 0, 'ALTER TABLE company_info ADD COLUMN service_capabilities TEXT AFTER business_model', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @exists = (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'company_info' AND column_name = 'customer_types');
SET @sql = IF(@exists = 0, 'ALTER TABLE company_info ADD COLUMN customer_types TEXT AFTER service_capabilities', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @exists = (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'company_info' AND column_name = 'application_scenarios');
SET @sql = IF(@exists = 0, 'ALTER TABLE company_info ADD COLUMN application_scenarios TEXT AFTER customer_types', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 填充初始示例数据
UPDATE company_info SET
    business_model = '直销为主：为大中型包装企业提供设备直售及耗材持续供应\n代理为辅：在重点区域发展授权代理商，覆盖中小客户群体',
    service_capabilities = '研发设计：拥有自主研发团队，可定制化开发非标设备\n生产制造：自有生产基地，年产能达500台套\n售后服务：提供设备安装调试、操作培训、远程诊断及定期巡检',
    customer_types = '国内客户：覆盖华东、华南、华北主要包装产业带\n海外客户：产品出口东南亚、中东、南美等地区\nOEM/ODM：为知名品牌提供代工及联合研发服务',
    application_scenarios = '食品包装：自动化包装线、真空包装、气调包装\n医药包装：药品泡罩包装、瓶装线、无菌包装\n日化包装：洗发水/护肤品灌装线、贴标机\n电子包装：精密仪器防静电包装、缓冲包装方案'
WHERE id = 1;