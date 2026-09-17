-- V110: product_variants 新增 sort_order + machine_tier_id 改 NOT NULL
-- ========================================================================================
-- 范围：
--   1. 新增 sort_order INT NOT NULL DEFAULT 0
--      用于 variant 在 machine_tier 内的排序，所有行默认 0，业务按需调整
--   2. machine_tier_id 改 NOT NULL
--      V2 要求每个 variant 必须属于一个 machine_tier
--      存量 NULL 行需要先回填，无法回填的行软删除
--
-- 数据回填策略：
--   步骤 2.1：每个 NULL 行的 machine_tier_id 设为该 product_model 下最小的 machine_tier_id
--            （启发式策略，业务上应 DBA 核对回填是否准确）
--   步骤 2.2：找不到 machine_tier 的 NULL 行（即 product_model 下没有 machine_tier），
--            同时做两件事:
--            (a) machine_tier_id 设为 product_machine_tiers 全局 MIN(id) (满足 FK + NOT NULL)
--                行已软删除, 跨 model 引用无业务影响
--            (b) deleted_at = COALESCE(deleted_at, NOW())  (活跃行软删除, 已软删除行保留原时间戳)
--   步骤 2.3：极端边界 - product_machine_tiers 表为空时, 2.2 无法设 FK, 改为硬删除 (罕见, 仅全新部署)
--
-- !!! TODO(DBA) 执行前先核对 NULL 行数和分布：
-- !!!   SELECT COUNT(*) FROM product_variants WHERE machine_tier_id IS NULL;
-- !!!   SELECT model_id, COUNT(*) FROM product_variants
-- !!!   WHERE machine_tier_id IS NULL GROUP BY model_id;
-- !!!   SELECT model_id, COUNT(*) FROM product_machine_tiers
-- !!!   GROUP BY model_id;
-- !!! 如果 NULL 行数较多或业务上有更精确的回填规则（如：哪个机型档位最适合这些 variant），
-- !!! DBA 应在 V110 执行前手动回填，V110 默认策略"最小 machine_tier_id"是兜底启发式。
--
-- 风险：高。涉及存量数据回填和软删除，部分操作不可逆。
--       - 步骤 2.1 回填的 machine_tier_id 被覆盖为 min_tier_id，无法精确恢复为 NULL
--         （但 V1 允许 NULL，回滚后保持非 NULL 也无业务影响）
--       - 步骤 2.2 设 machine_tier_id 为全局 MIN(id) + 软删除活跃行：
--         deleted_at 由备份表 original_deleted_at 精确恢复；
--         machine_tier_id 回滚时置 NULL（原值即 NULL）。
--       - 步骤 2.3 硬删除的行（极端边界）：备份表保留 variant_id, 回滚时需手工 INSERT 全量数据。
-- 回滚方式：见末尾"回滚提示"。
-- ========================================================================================


-- --------------------------------------------------------------------
-- 步骤 1：前置验证（执行前人工核对，Flyway 不暂停）
-- --------------------------------------------------------------------
-- 当前 machine_tier_id NULL 行数：
--   SELECT COUNT(*) FROM product_variants WHERE machine_tier_id IS NULL;
-- NULL 行按 model_id 分布：
--   SELECT model_id, COUNT(*)
--   FROM product_variants
--   WHERE machine_tier_id IS NULL
--   GROUP BY model_id;
-- 各 product_model 下 machine_tier 分布：
--   SELECT model_id, COUNT(*)
--   FROM product_machine_tiers
--   GROUP BY model_id;
-- 找不到 machine_tier 的 product_model（这些 model 下的 NULL 行将被软删除）：
--   SELECT DISTINCT pv.model_id
--   FROM product_variants pv
--   LEFT JOIN product_machine_tiers t ON t.model_id = pv.model_id
--   WHERE pv.machine_tier_id IS NULL AND t.id IS NULL;


-- --------------------------------------------------------------------
-- 步骤 2.0：备份待处理的 variant_id（步骤 2.2 处理的 NULL+无 tier 行）
-- 捕获 original_deleted_at 供回滚精确恢复, 避免影响其他原因软删除的行
-- 注: 同时覆盖 model_id IS NULL 的孤儿行 (NOT IN 对 NULL 返回 NULL, 需 OR 分支)
-- --------------------------------------------------------------------
CREATE TABLE product_variants_v2_soft_deleted AS
SELECT
    id AS variant_id,
    model_id,
    deleted_at AS original_deleted_at,  -- 原值: NULL=活跃行, 非空=已软删除
    CURRENT_TIMESTAMP(6) AS snapshot_at
FROM product_variants
WHERE machine_tier_id IS NULL
  AND (
      model_id IS NULL
      OR model_id NOT IN (
          SELECT DISTINCT model_id
          FROM product_machine_tiers
          WHERE model_id IS NOT NULL
      )
  );

ALTER TABLE product_variants_v2_soft_deleted
    ADD COLUMN backup_id BIGINT NOT NULL AUTO_INCREMENT FIRST,
    ADD PRIMARY KEY (backup_id),
    ADD INDEX idx_pv_v2_sd_variant_id (variant_id);


-- --------------------------------------------------------------------
-- 步骤 2.1：回填 NULL 行的 machine_tier_id（按"最小 tier id"启发式）
-- 业务上应 DBA 核对回填是否准确；如不准，DBA 应在 V110 执行前手动回填
-- --------------------------------------------------------------------
UPDATE product_variants pv
INNER JOIN (
    SELECT model_id, MIN(id) AS min_tier_id
    FROM product_machine_tiers
    GROUP BY model_id
) t ON t.model_id = pv.model_id
SET pv.machine_tier_id = t.min_tier_id
WHERE pv.machine_tier_id IS NULL;


-- --------------------------------------------------------------------
-- 步骤 2.2：找不到 machine_tier 的 NULL 行，同时设 machine_tier_id + 软删除活跃行
-- 这些行的 product_model 下没有 machine_tier，步骤 2.1 无法回填
-- 必须同时设 machine_tier_id, 否则步骤 3 ALTER MODIFY COLUMN NOT NULL 会失败
--
-- 策略:
--   (a) machine_tier_id = product_machine_tiers 全局 MIN(id)
--       行已软删除/即将软删除, 跨 model 引用无业务影响, 但满足 FK + NOT NULL
--   (b) deleted_at = COALESCE(deleted_at, CURRENT_TIMESTAMP(6))
--       活跃行 (deleted_at IS NULL) 软删除; 已软删除行保留原时间戳
--
-- 备份表 product_variants_v2_soft_deleted 已记录 variant_id + original_deleted_at
-- --------------------------------------------------------------------
UPDATE product_variants pv
CROSS JOIN (SELECT MIN(id) AS global_min_tier_id FROM product_machine_tiers) g
SET pv.machine_tier_id = g.global_min_tier_id,
    pv.deleted_at = COALESCE(pv.deleted_at, CURRENT_TIMESTAMP(6))
WHERE pv.machine_tier_id IS NULL
  AND g.global_min_tier_id IS NOT NULL;


-- --------------------------------------------------------------------
-- 步骤 2.3：极端边界 - product_machine_tiers 表为空时, 步骤 2.2 无法设 FK
-- 此场景仅见于全新部署且无任何机型档位数据, 生产环境通常不会出现
-- DBA 执行前应先核对: SELECT COUNT(*) FROM product_machine_tiers;
-- 如表非空 (正常情况), 此 DELETE 影响 0 行, 无副作用
-- 如表为空, 剩余 NULL 行无法满足 FK, 只能硬删除 (备份表已记录 variant_id)
-- --------------------------------------------------------------------
DELETE FROM product_variants WHERE machine_tier_id IS NULL;


-- --------------------------------------------------------------------
-- 步骤 3：machine_tier_id 改 NOT NULL
-- 步骤 2.1/2.2/2.3 已确保无 NULL 行，此 ALTER 应成功
-- --------------------------------------------------------------------
ALTER TABLE product_variants
    MODIFY COLUMN machine_tier_id BIGINT NOT NULL;


-- --------------------------------------------------------------------
-- 步骤 4：新增 sort_order INT NOT NULL DEFAULT 0
-- 放 spec_key 后，与 model 的 sort_order 风格一致
-- --------------------------------------------------------------------
ALTER TABLE product_variants
    ADD COLUMN sort_order INT NOT NULL DEFAULT 0 AFTER spec_key;


-- --------------------------------------------------------------------
-- 步骤 5：验证查询（执行后人工核对）
-- --------------------------------------------------------------------
-- machine_tier_id 无 NULL：
--   SELECT COUNT(*) FROM product_variants WHERE machine_tier_id IS NULL;  -- 应为 0
-- sort_order 已就位：
--   SHOW COLUMNS FROM product_variants LIKE 'sort_order';  -- 应存在，default 0
-- 步骤 2.2 处理的行数（应与备份表一致）：
--   SELECT COUNT(*) FROM product_variants_v2_soft_deleted;
--   SELECT COUNT(*) FROM product_variants pv
--   JOIN product_variants_v2_soft_deleted b ON b.variant_id = pv.id
--   WHERE pv.machine_tier_id = (SELECT MIN(id) FROM product_machine_tiers);  -- 应一致
--   -- 活跃行被软删除后 deleted_at 应非空:
--   SELECT COUNT(*) FROM product_variants_v2_soft_deleted WHERE original_deleted_at IS NULL;
--   -- 已软删除行原 deleted_at 应被保留:
--   SELECT COUNT(*) FROM product_variants_v2_soft_deleted WHERE original_deleted_at IS NOT NULL;
-- machine_tier_id 关联完整性（活行）：
--   SELECT COUNT(*) FROM product_variants pv
--   LEFT JOIN product_machine_tiers t ON t.id = pv.machine_tier_id
--   WHERE pv.deleted_at IS NULL AND t.id IS NULL;  -- 应为 0


-- --------------------------------------------------------------------
-- 回滚提示
-- --------------------------------------------------------------------
-- 如 V110 失败需回滚：
--
--   1. 删除新增的 sort_order 列：
--      ALTER TABLE product_variants DROP COLUMN sort_order;
--
--   2. machine_tier_id 改回 nullable：
--      ALTER TABLE product_variants
--          MODIFY COLUMN machine_tier_id BIGINT NULL;
--
--   3. 步骤 2.1 回填的行 machine_tier_id 已被覆盖为 min_tier_id，无法精确恢复为 NULL。
--      但 V1 允许 NULL，回滚后保持非 NULL 也无业务影响（V1 entity nullable=false 仅在 Java 层，
--      DB 层 V94 加列时即 nullable）。如需精确恢复 NULL，从生产 mysqldump 全量备份恢复。
--
--   4. 步骤 2.2 处理的行（machine_tier_id 被设为全局 MIN + deleted_at 调整）:
--      (a) machine_tier_id 置回 NULL（V1 允许 NULL, 原值即 NULL）:
--          UPDATE product_variants pv
--          JOIN product_variants_v2_soft_deleted b ON b.variant_id = pv.id
--          SET pv.machine_tier_id = NULL;
--
--      (b) deleted_at 按备份表 original_deleted_at 精确恢复:
--          - original_deleted_at IS NULL 的行（原活跃行）: 恢复为 NULL
--          - original_deleted_at IS NOT NULL 的行（原已软删除）: 恢复为原时间戳
--          UPDATE product_variants pv
--          JOIN product_variants_v2_soft_deleted b ON b.variant_id = pv.id
--          SET pv.deleted_at = b.original_deleted_at;
--
--   5. 步骤 2.3 硬删除的行（极端边界, 仅 product_machine_tiers 为空时）:
--      备份表仅记录 variant_id, 无法自动恢复全行, 需从生产 mysqldump 全量备份恢复。
--      (生产环境正常情况下此步骤影响 0 行, 无需关注)
--
--   6. 业务代码无需回滚（V110 不涉及业务字段重命名，machine_tier_id 和 sort_order 是新约束/新字段）。
