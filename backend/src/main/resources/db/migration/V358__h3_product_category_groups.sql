-- Local interpretation of H3 product categories; source category text remains unchanged.
CREATE TABLE h3_product_category_groups (
    source_category VARCHAR(150) NOT NULL PRIMARY KEY,
    product_group VARCHAR(20) NOT NULL,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
