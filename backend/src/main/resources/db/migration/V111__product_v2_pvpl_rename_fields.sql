-- V111: pvpl 改名字段（compatibility_status → relation_type, override_reason → remark）
-- ========================================================================================
-- 范围：
--   1. compatibility_status 改名 relation_type
--      枚举语义从"匹配度"（MATCHED / PARTIAL / NOT_COMPATIBLE）改为"关系类型"（COMPATIBLE / RECOMMENDED / COMMON）
--      CHANGE COLUMN 同时改名 + 改默认值 'MATCHED' → 'COMPATIBLE' + 加 COMMENT
--
--   2. override_reason 改名 remark
--      类型 VARCHAR(500) 不变，旧数据保留
--
-- 枚举值迁移策略（不 DELETE，全部降级为 COMPATIBLE）：
--   MATCHED        → COMPATIBLE（直接换名）
--   PARTIAL        → COMPATIBLE（语义降级，新枚举无"部分兼容"概念）
--   NOT_COMPATIBLE → COMPATIBLE（语义降级，便于业务切换到新枚举）
--   未知值/NULL    → COMPATIBLE（兜底默认）
--
-- !!! TODO(DBA) 执行前先核对实际值分布：
-- !!!   SELECT compatibility_status, COUNT(*)
-- !!!   FROM product_variant_packaging_links
-- !!!   GROUP BY compatibility_status;
-- !!! 如果只有 MATCHED（最常见，V1 业务实际可能只用 MATCHED），V111 可直接执行。
-- !!! 如果有 PARTIAL / NOT_COMPATIBLE，DBA 决定：
-- !!!   - 默认：降级为 COMPATIBLE（V2 不再区分匹配度，V111 默认行为）
-- !!!   - 严格：DELETE 这些行（V111 不做，DBA 执行前手动 DELETE）
-- !!!   - 标记：UPDATE 为 'INCOMPATIBLE'（不在 V2 枚举内，业务自定义）
--
-- 风险：低。纯 CHANGE COLUMN + UPDATE，无 DELETE，无 schema 不可逆操作。
--       唯一不可逆点：PARTIAL / NOT_COMPATIBLE 字面值被步骤 4 UPDATE 覆盖为 COMPATIBLE，
--       精确恢复需生产 mysqldump 全量备份。
-- 回滚方式：见末尾"回滚提示"。
-- ========================================================================================


-- --------------------------------------------------------------------
-- 步骤 1：前置验证（执行前人工核对，Flyway 不暂停）
-- --------------------------------------------------------------------
-- 旧 compatibility_status 值分布：
--   SELECT compatibility_status, COUNT(*)
--   FROM product_variant_packaging_links
--   GROUP BY compatibility_status;
-- 旧 override_reason 非空行数（验证改名后数据保留）：
--   SELECT COUNT(*) FROM product_variant_packaging_links WHERE override_reason IS NOT NULL;
-- 业务代码切换完成（应无引用）：
--   grep -rn 'compatibilityStatus\|compatibility_status' backend/src --include=*.java
--   grep -rn 'overrideReason\|override_reason' backend/src --include=*.java
--   grep -rn 'getCompatibilityStatus\|getOverrideReason' backend/src/main/java/com/liqiang/archive --include=*.java


-- --------------------------------------------------------------------
-- 步骤 2：改名 compatibility_status → relation_type
-- CHANGE COLUMN 同时改名 + 改默认值 + 加 COMMENT
-- 数据保留在列中（MATCHED / PARTIAL / NOT_COMPATIBLE 字面值保留，步骤 4 UPDATE 转换）
-- --------------------------------------------------------------------
ALTER TABLE product_variant_packaging_links
    CHANGE COLUMN compatibility_status relation_type
        VARCHAR(20) NOT NULL DEFAULT 'COMPATIBLE'
        COMMENT '关系类型：COMPATIBLE / RECOMMENDED / COMMON（覆盖原 MATCHED/PARTIAL/NOT_COMPATIBLE）';


-- --------------------------------------------------------------------
-- 步骤 3：改名 override_reason → remark
-- 类型 VARCHAR(500) 不变，旧数据保留
-- --------------------------------------------------------------------
ALTER TABLE product_variant_packaging_links
    CHANGE COLUMN override_reason remark
        VARCHAR(500) NULL;


-- --------------------------------------------------------------------
-- 步骤 4：枚举值迁移
-- 不 DELETE 任何行，所有旧值降级为 COMPATIBLE
-- 业务如有 NOT_COMPATIBLE 关系，可在 V111 后单独处理（DELETE 或 UPDATE 为自定义枚举）
-- --------------------------------------------------------------------
-- 4.1 三个旧枚举值 → COMPATIBLE
UPDATE product_variant_packaging_links
SET relation_type = 'COMPATIBLE'
WHERE relation_type IN ('MATCHED', 'PARTIAL', 'NOT_COMPATIBLE');

-- 4.2 兜底：未知值或 NULL → COMPATIBLE
--     理论 NULL 不存在（列 NOT NULL），但防御性兜底
UPDATE product_variant_packaging_links
SET relation_type = 'COMPATIBLE'
WHERE relation_type NOT IN ('COMPATIBLE', 'RECOMMENDED', 'COMMON')
   OR relation_type IS NULL;


-- --------------------------------------------------------------------
-- 步骤 5：验证查询（执行后人工核对）
-- --------------------------------------------------------------------
-- relation_type 分布（应只有 COMPATIBLE / RECOMMENDED / COMMON）：
--   SELECT relation_type, COUNT(*)
--   FROM product_variant_packaging_links
--   GROUP BY relation_type;
-- remark 非空行数（应与步骤 1 旧 override_reason 一致）：
--   SELECT COUNT(*) FROM product_variant_packaging_links WHERE remark IS NOT NULL;
-- 表结构验证：
--   SHOW COLUMNS FROM product_variant_packaging_links;
--   -- 应包含 relation_type（无 compatibility_status），remark（无 override_reason）


-- --------------------------------------------------------------------
-- 回滚提示
-- --------------------------------------------------------------------
-- 如 V111 失败需回滚（改名操作可逆，数据保留）：
--
--   1. 改名回原字段：
--      ALTER TABLE product_variant_packaging_links
--          CHANGE COLUMN relation_type compatibility_status
--              VARCHAR(20) NOT NULL DEFAULT 'MATCHED';
--      ALTER TABLE product_variant_packaging_links
--          CHANGE COLUMN remark override_reason
--              VARCHAR(500) NULL;
--
--   2. 枚举值回滚（COMPATIBLE → MATCHED，仅恢复 MATCHED，PARTIAL / NOT_COMPATIBLE 无法精确恢复）：
--      UPDATE product_variant_packaging_links
--      SET compatibility_status = 'MATCHED'
--      WHERE compatibility_status = 'COMPATIBLE';
--
--   3. 原始 PARTIAL / NOT_COMPATIBLE 字面值已被步骤 4 UPDATE 覆盖为 COMPATIBLE，
--      无法从本脚本回滚精确恢复。如需精确恢复，从生产 mysqldump 全量备份恢复。
--
--   4. 业务代码无需回滚（V2 切换为新字段后保持，旧字段恢复仅供数据回退后备）。
