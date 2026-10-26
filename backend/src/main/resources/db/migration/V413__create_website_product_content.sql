-- 官网产品独立内容模型。建表不复制或发布内部档案数据。
-- 来源 ID 仅在后续一次性迁移报告中记录，公开查询不依赖内部表。
CREATE TABLE website_product_categories (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    parent_id BIGINT,
    name VARCHAR(200) NOT NULL,
    name_en VARCHAR(200),
    slug VARCHAR(200) NOT NULL,
    description TEXT,
    seo_title VARCHAR(300),
    seo_description VARCHAR(500),
    sort_order INT NOT NULL DEFAULT 0,
    status VARCHAR(30) NOT NULL DEFAULT 'DRAFT',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at DATETIME,
    CONSTRAINT uk_website_product_category_slug UNIQUE (slug),
    CONSTRAINT fk_website_product_category_parent FOREIGN KEY (parent_id) REFERENCES website_product_categories (id),
    CONSTRAINT chk_website_product_category_status CHECK (status IN ('DRAFT', 'PUBLISHED', 'ARCHIVED')),
    INDEX idx_website_product_category_listing (status, deleted_at, sort_order)
);

CREATE TABLE website_products (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    category_id BIGINT,
    name VARCHAR(300) NOT NULL,
    name_en VARCHAR(300),
    public_model VARCHAR(200),
    slug VARCHAR(200) NOT NULL,
    summary TEXT,
    summary_en TEXT,
    description_html LONGTEXT,
    description_html_en LONGTEXT,
    application_tags JSON,
    technical_parameters JSON,
    seo_title VARCHAR(300),
    seo_description VARCHAR(500),
    hero_media_id BIGINT,
    sort_order INT NOT NULL DEFAULT 0,
    featured BOOLEAN NOT NULL DEFAULT FALSE,
    status VARCHAR(30) NOT NULL DEFAULT 'DRAFT',
    published_at DATETIME,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at DATETIME,
    CONSTRAINT uk_website_product_slug UNIQUE (slug),
    CONSTRAINT fk_website_product_category FOREIGN KEY (category_id) REFERENCES website_product_categories (id),
    CONSTRAINT fk_website_product_hero_media FOREIGN KEY (hero_media_id) REFERENCES website_media_assets (id),
    CONSTRAINT chk_website_product_status CHECK (status IN ('DRAFT', 'PUBLISHED', 'ARCHIVED')),
    INDEX idx_website_product_listing (status, deleted_at, sort_order),
    INDEX idx_website_product_featured (status, featured, sort_order)
);

CREATE TABLE website_product_variants (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    product_id BIGINT NOT NULL,
    name VARCHAR(300) NOT NULL,
    public_sku VARCHAR(200),
    description TEXT,
    dimensions VARCHAR(300),
    technical_parameters JSON,
    sort_order INT NOT NULL DEFAULT 0,
    status VARCHAR(30) NOT NULL DEFAULT 'DRAFT',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at DATETIME,
    CONSTRAINT fk_website_variant_product FOREIGN KEY (product_id) REFERENCES website_products (id),
    CONSTRAINT chk_website_variant_status CHECK (status IN ('DRAFT', 'PUBLISHED', 'ARCHIVED')),
    CONSTRAINT uk_website_variant_product_pair UNIQUE (id, product_id),
    INDEX idx_website_variant_listing (product_id, status, deleted_at, sort_order)
);

CREATE TABLE website_product_media (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    product_id BIGINT NOT NULL,
    variant_id BIGINT,
    media_id BIGINT NOT NULL,
    display_role VARCHAR(30) NOT NULL,
    alt_text VARCHAR(500),
    sort_order INT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_website_product_media_product FOREIGN KEY (product_id) REFERENCES website_products (id),
    CONSTRAINT fk_website_product_media_variant FOREIGN KEY (variant_id, product_id) REFERENCES website_product_variants (id, product_id),
    CONSTRAINT fk_website_product_media_asset FOREIGN KEY (media_id) REFERENCES website_media_assets (id),
    CONSTRAINT chk_website_product_media_role CHECK (display_role IN ('HERO', 'GALLERY', 'DETAIL', 'SCENE', 'PARAMETER', 'VIDEO', 'DOCUMENT')),
    CONSTRAINT uk_website_product_media_assignment UNIQUE (product_id, variant_id, media_id, display_role),
    INDEX idx_website_product_media_order (product_id, display_role, sort_order)
);
