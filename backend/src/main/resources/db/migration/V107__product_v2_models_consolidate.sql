-- V107: product_models 合并新增字段 + 数据迁移
-- ========================================================================================
-- 范围：
--   1. 新增 4 列（nullable 先行，便于数据迁移）
--      - category          VARCHAR(100)  合并 product_line_name / equipment_category / item_type
--      - description_cn    LONGTEXT      合并 equipment_advantages + core_config_description
--      - description_en    LONGTEXT      合并 equipment_advantages_en + core_config_description_en
--      - status            VARCHAR(50)   枚举化 sale_status → ACTIVE/DISCONTINUED/PREVIEW
--
--   2. 数据迁移（含软删除行，避免 V108 删旧列时丢数据）
--      - category = COALESCE(product_line_name, equipment_category, item_type)
--      - description_cn = CONCAT_WS('\n\n---\n\n', equipment_advantages, core_config_description)
--      - description_en = CONCAT_WS('\n\n---\n\n', equipment_advantages_en, core_config_description_en)
--      - status = CASE sale_status 枚举映射
--
--   3. status 加 NOT NULL + DEFAULT 'ACTIVE'
--
--   4. 删除"已合并到 description_*"的 4 个旧字段：
--      equipment_advantages / equipment_advantages_en / core_config_description / core_config_description_en
--      （数据已在 V106 备份至 product_models_v2_backup）
--
-- 不删除（留到后续脚本）：
--   - product_line_name / equipment_category / item_type（留到 V108，便于 category 回滚）
--   - sale_status（留到 V108，便于 status 双读过渡，业务代码切换完成后再删）
--   - applicable_scope_en（留到 V112，与 applicable_scope 改名 application_scope 同步处理）
--
-- 注：applicable_scope 数据保留在原列（V112 改名 application_scope），不复制到 description_cn。
--     description_cn 只合并 equipment_advantages + core_config_description，避免与 application_scope 数据冗余。
--     （设计文档 2.1/3.1 节对此描述存在矛盾，以本脚本实现为准，已顺手修正设计文档）
--
-- 风险：中。新增 + 数据迁移 + 删 4 列。
-- 回滚方式：见末尾"回滚提示"。
-- ========================================================================================


-- --------------------------------------------------------------------
-- 步骤 1：新增 4 列（nullable 先行）
-- --------------------------------------------------------------------
ALTER TABLE product_models
    ADD COLUMN category VARCHAR(100) NULL
        COMMENT '产品分类（合并 product_line_name/equipment_category/item_type）',
    ADD COLUMN description_cn LONGTEXT NULL
        COMMENT '产品介绍正文（合并 equipment_advantages + core_config_description）',
    ADD COLUMN description_en LONGTEXT NULL
        COMMENT '产品介绍正文英文',
    ADD COLUMN status VARCHAR(50) NULL
        COMMENT '产品状态：ACTIVE/DISCONTINUED/PREVIEW';


-- --------------------------------------------------------------------
-- 步骤 2：数据迁移 - category
-- 合并优先级：product_line_name → equipment_category → item_type（取第一个非空）
-- NULLIF 把空字符串转 NULL，避免空字符串被 COALESCE 当作有效值
-- 不带 deleted_at 过滤：所有行（含软删除）都迁移，避免 V108 删旧列时软删除行丢数据
-- --------------------------------------------------------------------
UPDATE product_models
SET category = COALESCE(
    NULLIF(product_line_name, ''),
    NULLIF(equipment_category, ''),
    NULLIF(item_type, '')
)
WHERE category IS NULL;


-- --------------------------------------------------------------------
-- 步骤 3：数据迁移 - description_cn
-- 合并 equipment_advantages + core_config_description，分块以 \n\n---\n\n 分隔
-- CONCAT_WS 自动跳过 NULL；NULLIF 把空字符串转 NULL，避免空块
-- --------------------------------------------------------------------
UPDATE product_models
SET description_cn = CONCAT_WS(
    '\n\n---\n\n',
    NULLIF(equipment_advantages, ''),
    NULLIF(core_config_description, '')
)
WHERE description_cn IS NULL;


-- --------------------------------------------------------------------
-- 步骤 4：数据迁移 - description_en
-- 合并 equipment_advantages_en + core_config_description_en
-- --------------------------------------------------------------------
UPDATE product_models
SET description_en = CONCAT_WS(
    '\n\n---\n\n',
    NULLIF(equipment_advantages_en, ''),
    NULLIF(core_config_description_en, '')
)
WHERE description_en IS NULL;


-- --------------------------------------------------------------------
-- 步骤 5：数据迁移 - status（枚举化 sale_status）
-- !!! TODO(DBA) 执行前先核对实际值：SELECT DISTINCT sale_status FROM product_models;
-- !!! 按实际值补充 CASE 映射，下列值基于常见中文/英文命名推断
-- ELSE 兜底 ACTIVE：保证所有行有值，便于步骤 7 加 NOT NULL
-- --------------------------------------------------------------------
UPDATE product_models
SET status = CASE
    WHEN sale_status IN ('在售', '在产', '在銷', 'ACTIVE', 'active', 'On Sale') THEN 'ACTIVE'
    WHEN sale_status IN ('停产', '停產', '停售', 'DISCONTINUED', 'discontinued', 'Discontinued') THEN 'DISCONTINUED'
    WHEN sale_status IN ('预告', '預告', '预览', '研发中', 'PREVIEW', 'preview', 'Preview') THEN 'PREVIEW'
    WHEN sale_status IS NULL OR sale_status = '' THEN 'ACTIVE'
    ELSE 'ACTIVE'  -- 未知值默认 ACTIVE，需 DBA 复核
END
WHERE status IS NULL;


-- --------------------------------------------------------------------
-- 步骤 6：验证查询（执行后人工核对，但 Flyway 自动执行流程不会暂停）
-- --------------------------------------------------------------------
-- status 应全非空：
--   SELECT COUNT(*) FROM product_models WHERE status IS NULL;             -- 期望 0
-- status 枚举分布：
--   SELECT status, COUNT(*) FROM product_models GROUP BY status;
-- category 迁移情况：
--   SELECT COUNT(*) FROM product_models WHERE category IS NOT NULL;       -- 期望部分行有值
--   SELECT COUNT(*) FROM product_models WHERE category IS NULL;          -- 三字段都为空的行
-- description_cn/en 迁移情况：
--   SELECT COUNT(*) FROM product_models WHERE description_cn IS NOT NULL;
--   SELECT COUNT(*) FROM product_models WHERE description_en IS NOT NULL;
-- 枚举映射异常的行（需人工核对）：
--   SELECT id, name, sale_status FROM product_models
--   WHERE sale_status IS NOT NULL AND sale_status != ''
--   AND sale_status NOT IN ('在售','在产','在銷','ACTIVE','active','On Sale',
--                           '停产','停產','停售','DISCONTINUED','discontinued','Discontinued',
--                           '预告','預告','预览','研发中','PREVIEW','preview','Preview');


-- --------------------------------------------------------------------
-- 步骤 7：status 加 NOT NULL + DEFAULT
-- 前置条件：步骤 5 已保证所有行 status 非空
-- --------------------------------------------------------------------
ALTER TABLE product_models
    MODIFY COLUMN status VARCHAR(50) NOT NULL DEFAULT 'ACTIVE' COMMENT '产品状态：ACTIVE/DISCONTINUED/PREVIEW';


-- --------------------------------------------------------------------
-- 步骤 8：删除"已合并到 description_*"的 4 个旧字段
-- 数据已在 V106 备份至 product_models_v2_backup
-- --------------------------------------------------------------------
ALTER TABLE product_models
    DROP COLUMN equipment_advantages,
    DROP COLUMN equipment_advantages_en,
    DROP COLUMN core_config_description,
    DROP COLUMN core_config_description_en;


-- --------------------------------------------------------------------
-- 回滚提示
-- --------------------------------------------------------------------
-- 如 V107 失败需回滚：
--
--   1. 恢复删除的 4 列（数据从 V106 备份恢复）：
--      ALTER TABLE product_models
--          ADD COLUMN equipment_advantages LONGTEXT NULL,
--          ADD COLUMN equipment_advantages_en LONGTEXT NULL,
--          ADD COLUMN core_config_description LONGTEXT NULL,
--          ADD COLUMN core_config_description_en LONGTEXT NULL;
--
--      UPDATE product_models pm
--      JOIN product_models_v2_backup b ON b.model_id = pm.id
--      SET pm.equipment_advantages = b.equipment_advantages,
--          pm.equipment_advantages_en = b.equipment_advantages_en,
--          pm.core_config_description = b.core_config_description,
--          pm.core_config_description_en = b.core_config_description_en
--      WHERE pm.equipment_advantages IS NULL OR pm.equipment_advantages_en IS NULL
--         OR pm.core_config_description IS NULL OR pm.core_config_description_en IS NULL;
--
--   2. 撤销 status 的 NOT NULL（如果步骤 7 已执行）：
--      ALTER TABLE product_models MODIFY COLUMN status VARCHAR(50) NULL;
--
--   3. 删除新增的 4 列：
--      ALTER TABLE product_models
--          DROP COLUMN category,
--          DROP COLUMN description_cn,
--          DROP COLUMN description_en,
--          DROP COLUMN status;
--
--   4. sale_status / product_line_name / equipment_category / item_type 等旧字段未删，
--      无需恢复。
