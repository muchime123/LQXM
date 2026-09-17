-- 设备视频库第二阶段：为视频文件建立 SHA-256 摘要，用于精确重复检测
ALTER TABLE product_assets ADD COLUMN file_hash VARCHAR(64) NULL AFTER machine_scope_confirmed;
CREATE INDEX idx_asset_file_hash ON product_assets (file_hash);
