-- 官网包材系列独立草稿。只保存公开展示字段，不引用内部 packaging_* 档案。
CREATE TABLE website_packaging_items (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    slug VARCHAR(160) NOT NULL,
    name VARCHAR(300) NOT NULL,
    packaging_kind VARCHAR(80),
    material VARCHAR(200),
    shape_type VARCHAR(120),
    capacity_ml VARCHAR(100),
    applicable TEXT,
    customizable BOOLEAN NOT NULL DEFAULT FALSE,
    cover_media_id BIGINT,
    sort_order INT NOT NULL DEFAULT 0,
    status VARCHAR(30) NOT NULL DEFAULT 'DRAFT',
    published_at DATETIME,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at DATETIME,
    CONSTRAINT uk_website_packaging_item_slug UNIQUE (slug),
    CONSTRAINT fk_website_packaging_cover_media FOREIGN KEY (cover_media_id) REFERENCES website_media_assets(id),
    CONSTRAINT chk_website_packaging_item_status CHECK (status IN ('DRAFT', 'PUBLISHED', 'ARCHIVED')),
    INDEX idx_website_packaging_item_listing (status, deleted_at, sort_order, id)
);
