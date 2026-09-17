ALTER TABLE packaging_models
    ADD COLUMN website_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN website_sort_order INT NOT NULL DEFAULT 0;

ALTER TABLE packaging_molds
    ADD COLUMN website_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN website_sort_order INT NOT NULL DEFAULT 0;

CREATE INDEX idx_packaging_models_website ON packaging_models (website_enabled, website_sort_order);
CREATE INDEX idx_packaging_molds_website ON packaging_molds (website_enabled, website_sort_order);
