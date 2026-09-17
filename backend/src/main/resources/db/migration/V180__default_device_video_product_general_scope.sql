-- 设备视频适用范围默认即「确认适用于整个产品（所有机型通用）」。
-- 1) 列默认值改为 TRUE，新插入且未显式指定的视频默认产品通用；
-- 2) 回填历史「尚未确认范围」的视频（未关联机型且未确认）为产品通用，
--    仅存在机型差异（绑定了机型档位）的以关联机型为准，不再人工特别调整的不需要手动操作。
ALTER TABLE product_assets
    ALTER COLUMN machine_scope_confirmed SET DEFAULT TRUE;

UPDATE product_assets asset
SET asset.machine_scope_confirmed = TRUE
WHERE asset.category IN ('device_video', 'operation_video')
  AND asset.deleted_at IS NULL
  AND asset.machine_scope_confirmed = FALSE
  AND NOT EXISTS (
      SELECT 1
      FROM product_asset_machine_tier_links link
      WHERE link.asset_id = asset.id
  );
