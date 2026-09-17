-- V22: Add shape-specific dimensions for packaging variants.

ALTER TABLE packaging_variants
    ADD COLUMN shape_type         VARCHAR(60) NULL AFTER capacity_ml,
    ADD COLUMN top_diameter_mm    VARCHAR(60) NULL AFTER height_mm,
    ADD COLUMN bottom_diameter_mm VARCHAR(60) NULL AFTER top_diameter_mm;
