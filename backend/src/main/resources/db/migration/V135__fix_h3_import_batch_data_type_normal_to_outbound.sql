-- 旧批次数据修复：早期版本中销售出库类型的 data_type 存储为 'NORMAL'，
-- 现在统一为 'OUTBOUND'，确保筛选和标签显示一致。
UPDATE h3_import_batches SET data_type = 'OUTBOUND' WHERE data_type = 'NORMAL';
