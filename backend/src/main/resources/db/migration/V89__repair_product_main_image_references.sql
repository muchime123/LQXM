-- 修复历史导入时被重复转码的默认主图引用。
-- 同一产品已存在正常中文文件名的有效素材，切换默认引用即可，无需复制或删除物理文件。
UPDATE product_models
SET default_image = '243-利强连续式封口机-主图_01.jpg'
WHERE id = 85
  AND default_image = '243-Ã¥ÂÂ©Ã¥Â¼ÂºÃ¨Â¿ÂÃ§Â»Â­Ã¥Â¼ÂÃ¥Â°ÂÃ¥ÂÂ£Ã¦ÂÂº-Ã¤Â¸Â»Ã¥ÂÂ¾_01.jpg';

UPDATE product_assets
SET default_asset = CASE WHEN id = 5096 THEN 1 ELSE 0 END
WHERE model_id = 85
  AND category = 'main_image'
  AND deleted_at IS NULL;
