-- 将 V92 的官网专属命名调整为产品档案通用选型属性，官网只是其中一个使用方。
ALTER TABLE product_models
    RENAME COLUMN website_application_tags TO application_tags,
    RENAME COLUMN website_container_tags TO container_tags,
    RENAME COLUMN website_process_tags TO packaging_process_tags,
    RENAME COLUMN website_capacity_level TO capacity_level,
    RENAME INDEX idx_product_website_capacity TO idx_product_capacity;
