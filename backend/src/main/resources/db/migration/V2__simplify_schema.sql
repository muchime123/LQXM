-- ============================================
-- 阶段2：数据模型精简迁移脚本
-- 执行前请备份数据库！
-- 兼容 MySQL 9.7：使用 PREPARE/EXECUTE 模式替代 DROP COLUMN IF EXISTS
-- ============================================

USE product_archive;

-- 1) media_account_updates 表：移除不再需要的字段
-- 先删除包含 title 字段的唯一约束（删除字段前必须先删除依赖该字段的索引/约束）
SET @exists = (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'media_account_updates' AND index_name = 'uk_updates_account_type_title_date');
SET @sql = IF(@exists > 0, 'ALTER TABLE media_account_updates DROP INDEX uk_updates_account_type_title_date', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @exists = (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'media_account_updates' AND column_name = 'update_type');
SET @sql = IF(@exists > 0, 'ALTER TABLE media_account_updates DROP COLUMN update_type', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @exists = (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'media_account_updates' AND column_name = 'title');
SET @sql = IF(@exists > 0, 'ALTER TABLE media_account_updates DROP COLUMN title', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @exists = (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'media_account_updates' AND column_name = 'content_url');
SET @sql = IF(@exists > 0, 'ALTER TABLE media_account_updates DROP COLUMN content_url', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @exists = (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'media_account_updates' AND column_name = 'note');
SET @sql = IF(@exists > 0, 'ALTER TABLE media_account_updates DROP COLUMN note', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @exists = (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'media_account_updates' AND column_name = 'operator');
SET @sql = IF(@exists > 0, 'ALTER TABLE media_account_updates DROP COLUMN operator', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 2) media_accounts 表：移除冗余缓存字段
SET @exists = (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'media_accounts' AND column_name = 'update_count');
SET @sql = IF(@exists > 0, 'ALTER TABLE media_accounts DROP COLUMN update_count', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @exists = (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'media_accounts' AND column_name = 'last_update_type');
SET @sql = IF(@exists > 0, 'ALTER TABLE media_accounts DROP COLUMN last_update_type', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @exists = (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'media_accounts' AND column_name = 'last_update_date');
SET @sql = IF(@exists > 0, 'ALTER TABLE media_accounts DROP COLUMN last_update_date', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 3) media_accounts 表：likes_count / followers_count / video_count 从 VARCHAR → BIGINT
-- 先将非数值数据转为0，再修改类型
UPDATE media_accounts SET likes_count = '0' WHERE likes_count IS NOT NULL AND likes_count REGEXP '[^0-9]' = 1;
UPDATE media_accounts SET followers_count = '0' WHERE followers_count IS NOT NULL AND followers_count REGEXP '[^0-9]' = 1;
UPDATE media_accounts SET video_count = '0' WHERE video_count IS NOT NULL AND video_count REGEXP '[^0-9]' = 1;

ALTER TABLE media_accounts MODIFY COLUMN likes_count BIGINT;
ALTER TABLE media_accounts MODIFY COLUMN followers_count BIGINT;
ALTER TABLE media_accounts MODIFY COLUMN video_count BIGINT;

-- 4) media_account_snapshots 表：同样从 VARCHAR → BIGINT
UPDATE media_account_snapshots SET likes_count = '0' WHERE likes_count IS NOT NULL AND likes_count REGEXP '[^0-9]' = 1;
UPDATE media_account_snapshots SET followers_count = '0' WHERE followers_count IS NOT NULL AND followers_count REGEXP '[^0-9]' = 1;
UPDATE media_account_snapshots SET video_count = '0' WHERE video_count IS NOT NULL AND video_count REGEXP '[^0-9]' = 1;

ALTER TABLE media_account_snapshots MODIFY COLUMN likes_count BIGINT;
ALTER TABLE media_account_snapshots MODIFY COLUMN followers_count BIGINT;
ALTER TABLE media_account_snapshots MODIFY COLUMN video_count BIGINT;
