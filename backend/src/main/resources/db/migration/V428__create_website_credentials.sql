-- 官网独立资质展示草稿；不引用内部资质或知识产权档案。
CREATE TABLE website_credentials (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(300) NOT NULL,
    category VARCHAR(100),
    public_description TEXT,
    issuer VARCHAR(200),
    valid_until DATE,
    media_id BIGINT,
    sort_order INT NOT NULL DEFAULT 0,
    status VARCHAR(30) NOT NULL DEFAULT 'DRAFT',
    published_at DATETIME,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at DATETIME,
    CONSTRAINT fk_website_credential_media FOREIGN KEY (media_id) REFERENCES website_media_assets(id),
    CONSTRAINT chk_website_credential_status CHECK (status IN ('DRAFT', 'PUBLISHED', 'ARCHIVED')),
    INDEX idx_website_credential_listing (status, deleted_at, sort_order, id)
);
