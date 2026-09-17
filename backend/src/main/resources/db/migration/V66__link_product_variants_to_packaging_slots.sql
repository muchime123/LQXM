CREATE TABLE product_variant_packaging_slots (
    id BIGINT NOT NULL AUTO_INCREMENT,
    product_variant_id BIGINT NOT NULL,
    slot_index INT NOT NULL,
    packaging_variant_id BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_pvps_product_variant
        FOREIGN KEY (product_variant_id) REFERENCES product_variants(id) ON DELETE CASCADE,
    CONSTRAINT fk_pvps_packaging_variant
        FOREIGN KEY (packaging_variant_id) REFERENCES packaging_variants(id) ON DELETE RESTRICT,
    CONSTRAINT uk_pvps_variant_slot UNIQUE (product_variant_id, slot_index),
    INDEX idx_pvps_packaging_variant (packaging_variant_id)
);
