-- 官网包材公开规格，只依赖官网包材系列，不引用内部 packaging_variants。
CREATE TABLE website_packaging_variants (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    item_id BIGINT NOT NULL,
    name VARCHAR(300) NOT NULL,
    public_sku VARCHAR(160),
    dimensions VARCHAR(300),
    capacity_ml VARCHAR(100),
    description TEXT,
    sort_order INT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at DATETIME,
    CONSTRAINT fk_website_packaging_variant_item FOREIGN KEY (item_id) REFERENCES website_packaging_items(id),
    INDEX idx_website_packaging_variant_item (item_id, deleted_at, sort_order, id)
);
