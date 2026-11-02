-- 源生产任务单（氚云《生产任务单》）里有「是否需要额外模具」字段，此前导入 production_orders
-- 时丢弃了，生产跟单里也一直没展示。本脚本补列 + 从保留的导入快照回填能回填的部分。
--
-- 列语义：氚云该字段取值为「是 / 否」，是品类级生产参数，不参与库存身份，
-- 故直接落 VARCHAR 列（与 optional_configuration / production_requirement 同属「源任务单原文」一类）。
--
-- ⚠️ 回填范围有限：h3_import_rows 仅保留最近几个 PRODUCTION 批次的原始快照（snapshot-cleanup），
-- 且多数导出件里该字段为空（JSON 中缺键）。完整历史数据请由业务重新全量导入氚云生产任务单文件补齐。

DROP PROCEDURE IF EXISTS v432_add_extra_mold;
CREATE PROCEDURE v432_add_extra_mold()
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                   WHERE TABLE_SCHEMA = DATABASE()
                     AND TABLE_NAME = 'production_orders'
                     AND COLUMN_NAME = 'extra_mold_required') THEN
        ALTER TABLE production_orders
            ADD COLUMN extra_mold_required VARCHAR(20) NULL
            COMMENT '源生产任务单：是否需要额外模具（是/否）';
    END IF;
END;
CALL v432_add_extra_mold();
DROP PROCEDURE IF EXISTS v432_add_extra_mold;

-- 从保留的最近批次原始快照回填（仅限 JSON 里真的带该键的工单）。
UPDATE production_orders p
JOIN h3_import_rows r ON r.external_id = p.external_id
JOIN h3_import_batches b ON b.id = r.batch_id AND b.data_type = 'PRODUCTION'
SET p.extra_mold_required = NULLIF(JSON_UNQUOTE(JSON_EXTRACT(r.raw_data, '$.main."是否需要额外模具"')), '')
WHERE r.raw_data IS NOT NULL
  AND JSON_EXTRACT(r.raw_data, '$.main."是否需要额外模具"') IS NOT NULL;
