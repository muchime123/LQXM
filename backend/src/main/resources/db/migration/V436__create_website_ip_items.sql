-- 官网独立知识产权展示记录；无指向内部档案的外键。
CREATE TABLE website_ip_items (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(300) NOT NULL,
    category VARCHAR(50) NOT NULL,
    patent_type VARCHAR(100),
    public_number VARCHAR(150),
    applicant VARCHAR(300),
    legal_status VARCHAR(100),
    media_id BIGINT,
    sort_order INT NOT NULL DEFAULT 0,
    status VARCHAR(30) NOT NULL DEFAULT 'DRAFT',
    published_at DATETIME,
    deleted_at DATETIME,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_website_ip_media FOREIGN KEY (media_id) REFERENCES website_media_assets(id),
    CONSTRAINT chk_website_ip_status CHECK (status IN ('DRAFT', 'PUBLISHED', 'ARCHIVED')),
    INDEX idx_website_ip_public (status, deleted_at, sort_order, id)
);
