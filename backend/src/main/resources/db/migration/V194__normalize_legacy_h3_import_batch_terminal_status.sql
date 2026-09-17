-- 旧版直连导入在编排前就把批次标记为 COMPLETED，且保留 UPLOAD/0% 进度。
-- 有编排错误的历史批次应显示“部分失败”，其余历史完成批次补齐终态字段。
UPDATE h3_import_batches
SET status = 'PARTIAL_FAILED',
    progress_pct = 100,
    progress_stage = 'DONE',
    completed_at = COALESCE(completed_at, NOW())
WHERE status = 'COMPLETED'
  AND (COALESCE(progress_pct, 0) < 100 OR COALESCE(progress_stage, '') <> 'DONE')
  AND COALESCE(error_message, '') <> '';

UPDATE h3_import_batches
SET progress_pct = 100,
    progress_stage = 'DONE',
    completed_at = COALESCE(completed_at, NOW())
WHERE status = 'COMPLETED'
  AND (COALESCE(progress_pct, 0) < 100 OR COALESCE(progress_stage, '') <> 'DONE');
