ALTER TABLE product_assets
    ADD COLUMN application_tags VARCHAR(500) NULL COMMENT '视频适用对象，逗号分隔' AFTER tags,
    ADD COLUMN video_content_type VARCHAR(40) NULL COMMENT '视频内容类型' AFTER application_tags,
    ADD COLUMN video_usage_status VARCHAR(40) NULL COMMENT '视频使用状态' AFTER video_content_type;

CREATE INDEX idx_product_asset_video_metadata
    ON product_assets (category, video_content_type, video_usage_status, deleted_at);
