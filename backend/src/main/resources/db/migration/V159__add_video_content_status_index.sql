-- 视频状态字段常用于批量治理筛选，加索引。
-- 原 V150__video_status_state_machine_align.sql 因版本号与
-- V150__sales_outbound_h3_snapshot_and_outbound_status.sql 冲突被拆分；
-- 数据迁移（旧词→新词、NULL 回填）已由 V147/V151 完成，此处仅补建索引。
CREATE INDEX idx_asset_video_content_status ON product_assets (video_content_status);
