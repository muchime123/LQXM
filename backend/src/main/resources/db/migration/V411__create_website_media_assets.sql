-- 官网独立媒体登记。文件存于 app.files.root/website 下，不引用内部档案表。
-- 本脚本仅建表；现有文件复制和引用切换由后续受控迁移完成。
CREATE TABLE website_media_assets (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    storage_key VARCHAR(500) NOT NULL,
    original_filename VARCHAR(500) NOT NULL,
    display_name VARCHAR(500),
    media_type VARCHAR(30) NOT NULL,
    mime_type VARCHAR(150) NOT NULL,
    size_bytes BIGINT NOT NULL,
    sha256 CHAR(64) NOT NULL,
    width INT,
    height INT,
    duration_seconds DECIMAL(12, 3),
    alt_text VARCHAR(500),
    caption TEXT,
    status VARCHAR(30) NOT NULL DEFAULT 'DRAFT',
    created_by BIGINT,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at DATETIME,
    CONSTRAINT uk_website_media_storage_key UNIQUE (storage_key),
    CONSTRAINT chk_website_media_size CHECK (size_bytes >= 0),
    CONSTRAINT chk_website_media_status CHECK (status IN ('DRAFT', 'PUBLISHED', 'ARCHIVED')),
    INDEX idx_website_media_status (status, deleted_at),
    INDEX idx_website_media_sha256 (sha256)
);
