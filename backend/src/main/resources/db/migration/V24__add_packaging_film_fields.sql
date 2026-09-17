ALTER TABLE packaging_variants
  ADD COLUMN film_material VARCHAR(120) NULL AFTER material_detail,
  ADD COLUMN film_supply_type VARCHAR(60) NULL AFTER film_material,
  ADD COLUMN film_features VARCHAR(300) NULL AFTER film_supply_type,
  ADD COLUMN price_unit VARCHAR(40) NULL AFTER film_features,
  ADD COLUMN pricing_notes VARCHAR(1000) NULL AFTER price_unit;

UPDATE packaging_models
SET packaging_kind = CASE
  WHEN packaging_kind IN ('container', 'bowl') THEN 'box'
  WHEN packaging_kind = 'sealing_film' THEN 'film'
  ELSE packaging_kind
END
WHERE deleted_at IS NULL;

UPDATE packaging_variants v
JOIN packaging_models m ON m.id = v.model_id
SET v.price_unit = 'kg'
WHERE v.deleted_at IS NULL
  AND m.deleted_at IS NULL
  AND m.packaging_kind = 'film'
  AND (v.price_unit IS NULL OR v.price_unit = '');
