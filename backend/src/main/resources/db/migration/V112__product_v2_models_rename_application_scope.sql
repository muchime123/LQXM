-- V112: product_models 改名 application_scope + 删 applicable_scope_en
-- ========================================================================================
-- 范围：
--   1. applicable_scope 改名 application_scope（CHANGE COLUMN，数据保留）
--      V2 统一为单一语言字段（中文），不再有 _en 后缀
--   2. 删除 applicable_scope_en（V106 已备份至 product_models_v2_backup）
--
-- !!! 注：equipment_advantages* 已在 V107 删除，V112 不再处理
-- !!!      （设计文档原稿提到 V112 删 equipment_advantages* 是笔误，已在 V107 处理）
--
-- 前置条件：
--   - V106 已执行：product_models_v2_backup 表存在且 applicable_scope_en 列已备份
--   - V108 已执行：product_models 已删除 44 冗余字段（applicable_scope 和 applicable_scope_en 不在 V108 范围，保留到 V112）
--   - 业务代码已切换完成：
--       * Java entity / DTO / Service 改读 applicationScope（不再读 applicableScope）
--       * 前端 type 改读 applicationScope
--       * 不再读 applicableScopeEn（英文版应用范围已下线）
--
-- 风险：低。改名是可逆操作，删除有 V106 备份。
-- 回滚方式：见末尾"回滚提示"。
-- ========================================================================================


-- --------------------------------------------------------------------
-- 步骤 1：前置验证（执行前人工核对，Flyway 不暂停）
-- --------------------------------------------------------------------
-- 字段存在性：
--   SHOW COLUMNS FROM product_models LIKE 'applicable_scope';     -- 应存在
--   SHOW COLUMNS FROM product_models LIKE 'applicable_scope_en';  -- 应存在
-- V106 备份表存在：
--   SHOW TABLES LIKE 'product_models_v2_backup';                  -- 应存在
-- 业务代码切换完成（应无引用）：
--   grep -rn 'applicableScope\|applicable_scope' backend/src --include=*.java
--   grep -rn 'applicableScopeEn\|applicable_scope_en' backend/src --include=*.java
--   grep -rn 'getApplicableScope\|getApplicableScopeEn' backend/src/main/java/com/liqiang/archive --include=*.java
-- 前端 type 切换完成：
--   grep -rn 'applicableScope\|applicableScopeEn' frontend/src/types


-- --------------------------------------------------------------------
-- 步骤 2：改名 applicable_scope → application_scope
-- CHANGE COLUMN 同时改名 + 加 COMMENT，类型 LONGTEXT 不变
-- 数据保留在列中
-- --------------------------------------------------------------------
ALTER TABLE product_models
    CHANGE COLUMN applicable_scope application_scope
        LONGTEXT NULL
        COMMENT '应用范围（V2 统一单一语言字段，原 applicable_scope 改名）';


-- --------------------------------------------------------------------
-- 步骤 3：删除 applicable_scope_en
-- V106 已备份至 product_models_v2_backup.applicable_scope_en
-- --------------------------------------------------------------------
ALTER TABLE product_models
    DROP COLUMN applicable_scope_en;


-- --------------------------------------------------------------------
-- 步骤 4：验证查询（执行后人工核对）
-- --------------------------------------------------------------------
-- application_scope 已就位：
--   SHOW COLUMNS FROM product_models LIKE 'application_scope';  -- 应存在
-- applicable_scope_en 已删除：
--   SHOW COLUMNS FROM product_models LIKE 'applicable_scope_en';  -- 应不存在
-- 数据保留（应与改名前一致）：
--   SELECT COUNT(*) FROM product_models WHERE application_scope IS NOT NULL;
--   SELECT id, LEFT(application_scope, 100) FROM product_models
--   WHERE application_scope IS NOT NULL LIMIT 5;  -- 抽查内容是否完好


-- --------------------------------------------------------------------
-- 回滚提示
-- --------------------------------------------------------------------
-- 如 V112 失败需回滚（改名可逆，删除有 V106 备份）：
--
--   1. 改名回原字段（数据保留）：
--      ALTER TABLE product_models
--          CHANGE COLUMN application_scope applicable_scope
--              LONGTEXT NULL;
--
--   2. 恢复 applicable_scope_en 列（按原类型 LONGTEXT）：
--      ALTER TABLE product_models
--          ADD COLUMN applicable_scope_en LONGTEXT NULL;
--
--   3. 从 V106 备份恢复 applicable_scope_en 数据：
--      UPDATE product_models pm
--      JOIN product_models_v2_backup b ON b.model_id = pm.id
--      SET pm.applicable_scope_en = b.applicable_scope_en
--      WHERE pm.applicable_scope_en IS NULL
--        AND b.applicable_scope_en IS NOT NULL;
--
--   4. 业务代码无需回滚（V2 切换到 applicationScope 后保持，旧字段恢复仅供数据回退后备）。
