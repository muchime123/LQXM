-- 官网独立发展历程；不复制内部档案记录。
CREATE TABLE website_company_milestones (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    milestone_year INT NOT NULL,
    title VARCHAR(200) NOT NULL,
    description TEXT,
    sort_order INT NOT NULL DEFAULT 0,
    status VARCHAR(30) NOT NULL DEFAULT 'DRAFT',
    published_at DATETIME,
    deleted_at DATETIME,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT chk_website_company_milestone_status CHECK (status IN ('DRAFT', 'PUBLISHED')),
    INDEX idx_website_company_milestone_public (status, deleted_at, milestone_year, sort_order)
);
