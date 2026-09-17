ALTER TABLE material_items
  ADD COLUMN source_system VARCHAR(30) NULL AFTER id,
  ADD COLUMN source_key VARCHAR(64) NULL AFTER source_system,
  ADD COLUMN source_code VARCHAR(200) NULL AFTER source_key,
  ADD UNIQUE KEY uk_material_source (source_system,source_key);
