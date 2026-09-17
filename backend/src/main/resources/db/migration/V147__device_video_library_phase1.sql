-- 设备视频库第一阶段：视频标题 + 适用范围确认 + 历史数据回填
-- 1) video_title：独立展示标题，默认由文件名清洗生成（去扩展名、去导入时间戳）；
--    original_file_name / file_name 继续保留用于追溯。
-- 2) machine_scope_confirmed：适用范围是否已人工确认。
--    空机型 + confirmed = 产品通用(ALL)；非空机型 = 指定机型(SELECTED)；confirmed = false = 未确认(UNREVIEWED)。

ALTER TABLE product_assets
    ADD COLUMN video_title VARCHAR(500) NULL AFTER demonstration_tags,
    ADD COLUMN machine_scope_confirmed BOOLEAN NOT NULL DEFAULT FALSE AFTER video_title;

CREATE INDEX idx_asset_video_title ON product_assets (video_title);

-- 回填视频标题：去掉扩展名、导入时间戳（10位以上连续数字）、首尾分隔符与空白
UPDATE product_assets
SET video_title = TRIM(BOTH ' -_.' FROM
        REGEXP_REPLACE(
            REGEXP_REPLACE(REGEXP_REPLACE(file_name, '\\.[^.]+$', ''), '[0-9]{10,}', ''),
            '[-_ ]+$', ''))
WHERE category IN ('device_video', 'operation_video')
  AND deleted_at IS NULL;

-- 历史视频内容状态统一置为“待确认”，避免误标为可用
UPDATE product_assets
SET video_content_status = '待确认'
WHERE category IN ('device_video', 'operation_video')
  AND deleted_at IS NULL
  AND (video_content_status IS NULL OR video_content_status = '');
