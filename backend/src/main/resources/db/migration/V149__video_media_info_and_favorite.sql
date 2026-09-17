-- 设备视频库第三阶段：媒体信息（时长/分辨率）与收藏
ALTER TABLE product_assets
    ADD COLUMN video_duration_seconds INT NULL AFTER file_hash,
    ADD COLUMN video_width INT NULL AFTER video_duration_seconds,
    ADD COLUMN video_height INT NULL AFTER video_width,
    ADD COLUMN video_favorite BOOLEAN NOT NULL DEFAULT FALSE AFTER video_height;
