CREATE TABLE IF NOT EXISTS product_manual_versions (
    id BIGINT NOT NULL AUTO_INCREMENT,
    model_id BIGINT NOT NULL,
    variant_id BIGINT NULL,
    version_number INT NOT NULL,
    status VARCHAR(20) NOT NULL,
    title VARCHAR(200) NULL,
    markdown LONGTEXT NULL,
    template_version VARCHAR(100) NULL,
    product_version VARCHAR(50) NULL,
    source_action VARCHAR(30) NULL,
    created_at DATETIME NOT NULL,
    published_at DATETIME NULL,
    PRIMARY KEY (id),
    INDEX idx_manual_versions_model (model_id, version_number)
);
