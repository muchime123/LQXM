-- V418: h3_import_rows.raw_data 改为可空
-- 目的：UNCHANGED 行不再存完整原始 JSON，只保留行记录（action/external_id/business_id）
-- 业务数据在 sales_outbounds/production_orders 等事实表中已有，原始快照只对 CREATE/UPDATE 有追溯价值
ALTER TABLE h3_import_rows MODIFY COLUMN raw_data LONGTEXT NULL;

-- 同时把已有的 UNCHANGED 行的 raw_data 清空（已在手动清理中做过，这里幂等保护）
UPDATE h3_import_rows SET raw_data = NULL WHERE action = 'UNCHANGED' AND raw_data IS NOT NULL;
