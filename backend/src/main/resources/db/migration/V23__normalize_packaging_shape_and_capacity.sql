UPDATE packaging_models
SET shape_type = CASE id
  WHEN 2 THEN 'square'
  WHEN 3 THEN 'rectangle'
  WHEN 4 THEN 'round'
  WHEN 5 THEN 'irregular'
  WHEN 6 THEN 'rectangle'
  WHEN 7 THEN 'irregular'
  WHEN 9 THEN 'mixed'
  WHEN 10 THEN 'rectangle'
  ELSE shape_type
END
WHERE deleted_at IS NULL
  AND id IN (2, 3, 4, 5, 6, 7, 9, 10);

UPDATE packaging_variants
SET
  length_mm = REGEXP_REPLACE(SUBSTRING_INDEX(variant_name, _utf8mb4 0xC397, 1), '[^0-9.]', ''),
  width_mm = REGEXP_REPLACE(SUBSTRING_INDEX(SUBSTRING_INDEX(variant_name, _utf8mb4 0xC397, 2), _utf8mb4 0xC397, -1), '[^0-9.]', ''),
  height_mm = REGEXP_REPLACE(SUBSTRING_INDEX(SUBSTRING_INDEX(variant_name, _utf8mb4 0xC397, 3), _utf8mb4 0xC397, -1), '[^0-9.]', '')
WHERE deleted_at IS NULL
  AND variant_name REGEXP CONCAT('^[0-9]+', _utf8mb4 0xC397, '[0-9]+', _utf8mb4 0xC397, '[0-9]+mm')
  AND model_id <> 7
  AND (length_mm IS NULL OR width_mm IS NULL OR height_mm IS NULL);

UPDATE packaging_variants
SET shape_type = CASE
  WHEN length_mm REGEXP '^[0-9.]+$'
    AND width_mm REGEXP '^[0-9.]+$'
    AND CAST(length_mm AS DECIMAL(10, 2)) = CAST(width_mm AS DECIMAL(10, 2))
    THEN 'square'
  ELSE 'rectangle'
END
WHERE deleted_at IS NULL
  AND model_id = 2
  AND length_mm IS NOT NULL
  AND width_mm IS NOT NULL;

UPDATE packaging_variants
SET shape_type = 'rectangle'
WHERE deleted_at IS NULL
  AND model_id = 2
  AND variant_name LIKE CONCAT('%', _utf8mb4 0xE4B889E6988EE6B2BBE79B92, '%')
  AND (shape_type IS NULL OR shape_type = '');

UPDATE packaging_variants
SET shape_type = 'rectangle'
WHERE deleted_at IS NULL
  AND model_id IN (3, 6, 10);

UPDATE packaging_variants
SET shape_type = CASE
  WHEN length_mm REGEXP '^[0-9.]+$'
    AND width_mm REGEXP '^[0-9.]+$'
    AND CAST(length_mm AS DECIMAL(10, 2)) = CAST(width_mm AS DECIMAL(10, 2))
    THEN 'round'
  WHEN length_mm IS NOT NULL AND width_mm IS NOT NULL THEN 'oval'
  ELSE 'round'
END
WHERE deleted_at IS NULL
  AND model_id = 4;

UPDATE packaging_variants
SET shape_type = 'irregular'
WHERE deleted_at IS NULL
  AND model_id = 5;

UPDATE packaging_variants
SET
  shape_type = 'round',
  top_diameter_mm = REGEXP_REPLACE(SUBSTRING_INDEX(variant_name, _utf8mb4 0xC397, 1), '[^0-9.]', ''),
  height_mm = REGEXP_REPLACE(SUBSTRING_INDEX(SUBSTRING_INDEX(variant_name, _utf8mb4 0xC397, 2), _utf8mb4 0xC397, -1), '[^0-9.]', ''),
  length_mm = NULL,
  width_mm = NULL
WHERE deleted_at IS NULL
  AND model_id = 7
  AND variant_name REGEXP CONCAT('^[0-9]+', _utf8mb4 0xC397, '[0-9]+mm');

UPDATE packaging_variants
SET
  shape_type = 'square',
  length_mm = REGEXP_REPLACE(SUBSTRING_INDEX(variant_name, _utf8mb4 0xC397, 1), '[^0-9.]', ''),
  width_mm = REGEXP_REPLACE(SUBSTRING_INDEX(SUBSTRING_INDEX(variant_name, _utf8mb4 0xC397, 2), _utf8mb4 0xC397, -1), '[^0-9.]', ''),
  height_mm = REGEXP_REPLACE(SUBSTRING_INDEX(SUBSTRING_INDEX(variant_name, _utf8mb4 0xC397, 3), _utf8mb4 0xC397, -1), '[^0-9.]', ''),
  top_diameter_mm = NULL,
  bottom_diameter_mm = NULL
WHERE deleted_at IS NULL
  AND model_id = 7
  AND (
    variant_name LIKE CONCAT('%', _utf8mb4 0xE696B9E69DAF, '%')
    OR (
      variant_name REGEXP CONCAT('^[0-9]+', _utf8mb4 0xC397, '[0-9]+', _utf8mb4 0xC397, '[0-9]+mm')
      AND CAST(REGEXP_REPLACE(SUBSTRING_INDEX(variant_name, _utf8mb4 0xC397, 1), '[^0-9.]', '') AS DECIMAL(10, 2))
        = CAST(REGEXP_REPLACE(SUBSTRING_INDEX(SUBSTRING_INDEX(variant_name, _utf8mb4 0xC397, 2), _utf8mb4 0xC397, -1), '[^0-9.]', '') AS DECIMAL(10, 2))
    )
  );

UPDATE packaging_variants
SET
  shape_type = 'round',
  top_diameter_mm = REGEXP_REPLACE(SUBSTRING_INDEX(variant_name, _utf8mb4 0xC397, 1), '[^0-9.]', ''),
  bottom_diameter_mm = REGEXP_REPLACE(SUBSTRING_INDEX(SUBSTRING_INDEX(variant_name, _utf8mb4 0xC397, 2), _utf8mb4 0xC397, -1), '[^0-9.]', ''),
  height_mm = REGEXP_REPLACE(SUBSTRING_INDEX(SUBSTRING_INDEX(variant_name, _utf8mb4 0xC397, 3), _utf8mb4 0xC397, -1), '[^0-9.]', ''),
  length_mm = NULL,
  width_mm = NULL
WHERE deleted_at IS NULL
  AND model_id = 7
  AND variant_name REGEXP CONCAT('^[0-9]+', _utf8mb4 0xC397, '[0-9]+', _utf8mb4 0xC397, '[0-9]+mm')
  AND CAST(REGEXP_REPLACE(SUBSTRING_INDEX(variant_name, _utf8mb4 0xC397, 1), '[^0-9.]', '') AS DECIMAL(10, 2))
    <> CAST(REGEXP_REPLACE(SUBSTRING_INDEX(SUBSTRING_INDEX(variant_name, _utf8mb4 0xC397, 2), _utf8mb4 0xC397, -1), '[^0-9.]', '') AS DECIMAL(10, 2));

UPDATE packaging_variants
SET shape_type = CASE
  WHEN variant_name LIKE 'Y%' THEN 'round'
  WHEN variant_name LIKE 'T%' THEN 'rectangle'
  WHEN variant_name LIKE 'C%' OR variant_name LIKE 'F%' THEN 'square'
  ELSE shape_type
END
WHERE deleted_at IS NULL
  AND model_id = 9;

UPDATE packaging_models m
JOIN (
  SELECT
    model_id,
    MIN(CAST(capacity_ml AS DECIMAL(10, 2))) AS min_capacity,
    MAX(CAST(capacity_ml AS DECIMAL(10, 2))) AS max_capacity
  FROM packaging_variants
  WHERE deleted_at IS NULL
    AND capacity_ml REGEXP '^[0-9]+([.][0-9]+)?$'
  GROUP BY model_id
) r ON r.model_id = m.id
SET m.capacity_ml = CASE
  WHEN r.min_capacity = r.max_capacity THEN CONCAT(TRIM(TRAILING '.' FROM TRIM(TRAILING '0' FROM CAST(r.min_capacity AS CHAR))), 'ml')
  ELSE CONCAT(
    TRIM(TRAILING '.' FROM TRIM(TRAILING '0' FROM CAST(r.min_capacity AS CHAR))),
    '~',
    TRIM(TRAILING '.' FROM TRIM(TRAILING '0' FROM CAST(r.max_capacity AS CHAR))),
    'ml'
  )
END
WHERE m.deleted_at IS NULL;
