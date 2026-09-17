-- 规格族持久化标准键：同系列、同标准口径只能存在一个有效规格族。
ALTER TABLE packaging_mold_groups
    ADD COLUMN normalized_key VARCHAR(160) NULL AFTER name;

UPDATE packaging_mold_groups
SET normalized_key = CASE
    WHEN length_mm REGEXP '^[0-9]+([.][0-9]+)?$'
         AND width_mm REGEXP '^[0-9]+([.][0-9]+)?$'
        THEN CONCAT(
            'RECT:',
            CAST(LEAST(CAST(length_mm AS DECIMAL(12,3)), CAST(width_mm AS DECIMAL(12,3))) AS CHAR),
            'x',
            CAST(GREATEST(CAST(length_mm AS DECIMAL(12,3)), CAST(width_mm AS DECIMAL(12,3))) AS CHAR)
        )
    WHEN diameter_mm REGEXP '^[0-9]+([.][0-9]+)?$'
        THEN CONCAT('ROUND:', CAST(CAST(diameter_mm AS DECIMAL(12,3)) AS CHAR))
    ELSE CONCAT('NAME:', LOWER(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(TRIM(name), ' ', ''), 'x', '×'), 'X', '×'), '*', '×'), '＊', '×')))
END
WHERE normalized_key IS NULL;

-- 先把历史重复组下的模具迁移到最早的规格族，再软删除重复组。
UPDATE packaging_molds m
INNER JOIN (
    SELECT duplicate_group.id AS duplicate_id, keep_group.keep_id
    FROM packaging_mold_groups duplicate_group
    INNER JOIN (
        SELECT model_id, normalized_key, MIN(id) AS keep_id
        FROM packaging_mold_groups
        WHERE deleted_at IS NULL AND normalized_key IS NOT NULL
        GROUP BY model_id, normalized_key
        HAVING COUNT(*) > 1
    ) keep_group
        ON keep_group.model_id = duplicate_group.model_id
        AND keep_group.normalized_key = duplicate_group.normalized_key
        AND duplicate_group.id <> keep_group.keep_id
    WHERE duplicate_group.deleted_at IS NULL
) merge_map ON merge_map.duplicate_id = m.group_id
SET m.group_id = merge_map.keep_id;

UPDATE packaging_mold_groups duplicate_group
INNER JOIN (
    SELECT g.id AS duplicate_id
    FROM packaging_mold_groups g
    INNER JOIN (
        SELECT model_id, normalized_key, MIN(id) AS keep_id
        FROM packaging_mold_groups
        WHERE deleted_at IS NULL AND normalized_key IS NOT NULL
        GROUP BY model_id, normalized_key
        HAVING COUNT(*) > 1
    ) keep_group
        ON keep_group.model_id = g.model_id
        AND keep_group.normalized_key = g.normalized_key
        AND g.id <> keep_group.keep_id
    WHERE g.deleted_at IS NULL
) merge_map ON merge_map.duplicate_id = duplicate_group.id
SET duplicate_group.deleted_at = CURRENT_TIMESTAMP;

CREATE INDEX idx_pkg_mold_group_normalized_key
    ON packaging_mold_groups (normalized_key);

CREATE UNIQUE INDEX uk_pkg_mold_group_model_normalized
    ON packaging_mold_groups (
        model_id,
        normalized_key,
        (CASE WHEN deleted_at IS NULL THEN 0 END)
    );
