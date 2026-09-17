-- V21: Add packaging specification fields to packaging_variants
-- Adds carton size/weight/volume and per-variant MOQ for complete spec data from PDF catalog

ALTER TABLE packaging_variants
    ADD COLUMN carton_size   VARCHAR(60) NULL AFTER boxes_per_roll,
    ADD COLUMN carton_weight VARCHAR(60) NULL AFTER carton_size,
    ADD COLUMN carton_volume VARCHAR(60) NULL AFTER carton_weight,
    ADD COLUMN variant_moq   VARCHAR(60) NULL AFTER carton_volume;
