-- 指定机型本身即代表适用范围已经明确，修复历史关联存在但确认标记仍为 false 的数据。
UPDATE product_assets asset
SET asset.machine_scope_confirmed = TRUE
WHERE asset.category IN ('device_video', 'operation_video')
  AND EXISTS (
      SELECT 1
      FROM product_asset_machine_tier_links link
      WHERE link.asset_id = asset.id
  );
