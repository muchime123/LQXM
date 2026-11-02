-- 官网独立官方链接；不复制内部自媒体账号。
CREATE TABLE website_social_links (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    platform VARCHAR(50) NOT NULL,
    account_name VARCHAR(200) NOT NULL,
    link VARCHAR(1000) NOT NULL,
    sort_order INT NOT NULL DEFAULT 0,
    status VARCHAR(30) NOT NULL DEFAULT 'DRAFT',
    published_at DATETIME,
    deleted_at DATETIME,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT chk_website_social_link_status CHECK (status IN ('DRAFT', 'PUBLISHED')),
    INDEX idx_website_social_link_public (status, deleted_at, sort_order)
);
