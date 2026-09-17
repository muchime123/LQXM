-- V108: product_models 删除剩余 44 冗余字段
-- ========================================================================================
-- 范围：
--   一次性删除 V107 未删、V112 不处理的 44 列。
--
-- 删除字段分组（共 44 列）：
--   (1) 旧分类与业务属性（19 列）：V106 已备份 → 合并到 category 或废弃
--       product_line_name / product_line_name_en / equipment_category / equipment_category_en /
--       machine_type / operation_mode / film_material / film_type / automation_level /
--       body_material / mold_material / item_type / shop_url / workshop /
--       applicable_industry / applicable_industry_en / applicable_product / applicable_product_en /
--       default_manual_file
--
--   (2) 机器参数（12 列）：V106 已备份 → 已下沉到 product_machine_tiers
--       voltage / frequency / power / packaging_speed /
--       device_weight_kg / device_size / max_film_width / roll_film_diameter / max_sealing_size /
--       air_pressure_requirement / gas_replacement_rate / residual_oxygen
--
--   (3) 国际站履约（6 列）：V106 已备份 → 未来 shipping_profile 表
--       customs_code / packaging_method_cn / packaging_method_en / shipping_methods /
--       lead_time_stock / lead_time_custom
--
--   (4) 价格（5 列）：V106 已备份 → 未来 pricing_system 表
--       reference_distributor_price / reference_retail_price / package_size / package_weight_kg / pricing_notes
--
--   (5) 导入溯源（1 列）：V106 已备份 → 无读取链路
--       source_row_number
--
--   (6) sale_status（1 列）：V106 已备份 → V107 已改名为 status，V108 直接删除
--
-- 不删除（留到 V112）：
--   - applicable_scope_en（与 applicable_scope 改名 application_scope 同步处理）
--   - applicable_scope（V112 改名为 application_scope）
--
-- 前置条件：
--   - V106 已执行：product_models_v2_backup 表存在且数据完整（49 列已备份，含 sale_status）
--   - V107 已执行：category / description_cn / description_en / status 字段已就位且 status NOT NULL
--   - 业务代码已切换完成（Java 实体 / DTO / Service / 前端 type）：
--       * 不再读 sale_status（改读 status）
--       * 不再读 product_line_name / equipment_category / item_type（改读 category）
--       * 不再读 voltage / frequency / power / ...（如需机器参数改读 machine_tier）
--       * 不再读 dealer_price / retail_price / ...（价格模块已下线）
--   - 官网公开接口 /api/public/v1/products 已切换到新字段（category / description_cn / status）
--
-- 风险：中。一次性删 44 列，MySQL 8.0+ 单 ALTER INPLACE，预计 < 5s（取决于表行数）。
--       如表行数 > 10w 或锁表敏感，可拆为多个 ALTER 分批执行（见末尾"分批执行备选方案"）。
--
-- 回滚方式：见末尾"回滚提示"。
-- ========================================================================================



-- --------------------------------------------------------------------
-- 步骤 1：前置验证（执行前人工核对，Flyway 不暂停）
-- --------------------------------------------------------------------
-- V107 新字段就位：
--   SHOW COLUMNS FROM product_models LIKE 'category';           -- 应存在
--   SHOW COLUMNS FROM product_models LIKE 'description_cn';     -- 应存在
--   SHOW COLUMNS FROM product_models LIKE 'description_en';     -- 应存在
--   SHOW COLUMNS FROM product_models LIKE 'status';             -- 应存在且 NOT NULL
--
-- V106 备份表存在：
--   SHOW TABLES LIKE 'product_models_v2_backup';                -- 应存在
--
-- 业务代码切换完成（应无引用）：
--   grep -rn 'saleStatus\|sale_status' backend/src --include=*.java
--   grep -rn 'productLineName\|product_line_name' backend/src --include=*.java
--   grep -rn 'equipmentCategory\|equipment_category' backend/src --include=*.java
--   grep -rn 'getVoltage\|getFrequency\|getPower' backend/src/main/java/com/liqiang/archive --include=*.java
--   grep -rn 'dealerPrice\|retailPrice\|pricingNotes' backend/src --include=*.java
--
-- 前端 type 切换完成：
--   grep -rn 'saleStatus\|productLineName\|equipmentCategory' frontend/src/types


-- --------------------------------------------------------------------
-- 步骤 2：删除 44 列（单个 ALTER，MySQL 8.0+ INPLACE）
-- --------------------------------------------------------------------
ALTER TABLE product_models
    -- (1) 旧分类与业务属性（19 列）
    DROP COLUMN product_line_name,
    DROP COLUMN product_line_name_en,
    DROP COLUMN equipment_category,
    DROP COLUMN equipment_category_en,
    DROP COLUMN machine_type,
    DROP COLUMN operation_mode,
    DROP COLUMN film_material,
    DROP COLUMN film_type,
    DROP COLUMN automation_level,
    DROP COLUMN body_material,
    DROP COLUMN mold_material,
    DROP COLUMN item_type,
    DROP COLUMN shop_url,
    DROP COLUMN workshop,
    DROP COLUMN applicable_industry,
    DROP COLUMN applicable_industry_en,
    DROP COLUMN applicable_product,
    DROP COLUMN applicable_product_en,
    DROP COLUMN default_manual_file,
    -- (2) 机器参数（12 列）
    DROP COLUMN voltage,
    DROP COLUMN frequency,
    DROP COLUMN power,
    DROP COLUMN packaging_speed,
    DROP COLUMN device_weight_kg,
    DROP COLUMN device_size,
    DROP COLUMN max_film_width,
    DROP COLUMN roll_film_diameter,
    DROP COLUMN max_sealing_size,
    DROP COLUMN air_pressure_requirement,
    DROP COLUMN gas_replacement_rate,
    DROP COLUMN residual_oxygen,
    -- (3) 国际站履约（6 列）
    DROP COLUMN customs_code,
    DROP COLUMN packaging_method_cn,
    DROP COLUMN packaging_method_en,
    DROP COLUMN shipping_methods,
    DROP COLUMN lead_time_stock,
    DROP COLUMN lead_time_custom,
    -- (4) 价格（5 列）
    DROP COLUMN reference_distributor_price,
    DROP COLUMN reference_retail_price,
    DROP COLUMN package_size,
    DROP COLUMN package_weight_kg,
    DROP COLUMN pricing_notes,
    -- (5) 导入溯源（1 列）
    DROP COLUMN source_row_number,
    -- (6) sale_status（V107 已改名为 status，业务代码切换完成后删除）
    DROP COLUMN sale_status;


-- --------------------------------------------------------------------
-- 步骤 3：清理孤儿索引（自动）
-- V1 早期为 product_line_name / equipment_category / sale_status / workshop 建过索引：
--   idx_product_line, idx_equip_category, idx_sale_status, idx_workshop
-- MySQL DROP COLUMN 时会自动删除对应索引，无需手动 DROP INDEX
-- 验证：
--   SHOW INDEX FROM product_models;
-- --------------------------------------------------------------------


-- --------------------------------------------------------------------
-- 分批执行备选方案（如表行数 > 10w 或锁表敏感，按批拆分）
-- 把步骤 2 的单 ALTER 拆为 4 个 ALTER，每批获取表锁更短
-- --------------------------------------------------------------------
-- 第 1 批：旧分类与业务属性（19 列）
-- ALTER TABLE product_models
--     DROP COLUMN product_line_name, DROP COLUMN product_line_name_en,
--     DROP COLUMN equipment_category, DROP COLUMN equipment_category_en,
--     DROP COLUMN machine_type, DROP COLUMN operation_mode,
--     DROP COLUMN film_material, DROP COLUMN film_type,
--     DROP COLUMN automation_level, DROP COLUMN body_material, DROP COLUMN mold_material,
--     DROP COLUMN item_type, DROP COLUMN shop_url, DROP COLUMN workshop,
--     DROP COLUMN applicable_industry, DROP COLUMN applicable_industry_en,
--     DROP COLUMN applicable_product, DROP COLUMN applicable_product_en,
--     DROP COLUMN default_manual_file;
--
-- 第 2 批：机器参数（12 列）
-- ALTER TABLE product_models
--     DROP COLUMN voltage, DROP COLUMN frequency, DROP COLUMN power,
--     DROP COLUMN packaging_speed, DROP COLUMN device_weight_kg, DROP COLUMN device_size,
--     DROP COLUMN max_film_width, DROP COLUMN roll_film_diameter, DROP COLUMN max_sealing_size,
--     DROP COLUMN air_pressure_requirement, DROP COLUMN gas_replacement_rate, DROP COLUMN residual_oxygen;
--
-- 第 3 批：国际站履约 + 价格 + 导入溯源（12 列）
-- ALTER TABLE product_models
--     DROP COLUMN customs_code, DROP COLUMN packaging_method_cn, DROP COLUMN packaging_method_en,
--     DROP COLUMN shipping_methods, DROP COLUMN lead_time_stock, DROP COLUMN lead_time_custom,
--     DROP COLUMN reference_distributor_price, DROP COLUMN reference_retail_price,
--     DROP COLUMN package_size, DROP COLUMN package_weight_kg, DROP COLUMN pricing_notes,
--     DROP COLUMN source_row_number;
--
-- 第 4 批：sale_status（1 列）
-- ALTER TABLE product_models DROP COLUMN sale_status;


-- --------------------------------------------------------------------
-- 回滚提示
-- --------------------------------------------------------------------
-- 如 V108 失败需回滚：
--
--   1. 恢复删除的 44 列（按原类型重建，全部 nullable 避免约束冲突）：
--      ALTER TABLE product_models
--          -- (1) 旧分类与业务属性（19 列）
--          ADD COLUMN product_line_name VARCHAR(120) NULL,
--          ADD COLUMN product_line_name_en VARCHAR(120) NULL,
--          ADD COLUMN equipment_category VARCHAR(100) NULL,
--          ADD COLUMN equipment_category_en VARCHAR(100) NULL,
--          ADD COLUMN machine_type VARCHAR(100) NULL,
--          ADD COLUMN operation_mode VARCHAR(100) NULL,
--          ADD COLUMN film_material VARCHAR(100) NULL,
--          ADD COLUMN film_type VARCHAR(100) NULL,
--          ADD COLUMN automation_level VARCHAR(100) NULL,
--          ADD COLUMN body_material VARCHAR(100) NULL,
--          ADD COLUMN mold_material VARCHAR(100) NULL,
--          ADD COLUMN item_type VARCHAR(100) NULL,
--          ADD COLUMN shop_url VARCHAR(500) NULL,
--          ADD COLUMN workshop VARCHAR(100) NULL,
--          ADD COLUMN applicable_industry VARCHAR(200) NULL,
--          ADD COLUMN applicable_industry_en VARCHAR(200) NULL,
--          ADD COLUMN applicable_product VARCHAR(300) NULL,
--          ADD COLUMN applicable_product_en VARCHAR(300) NULL,
--          ADD COLUMN default_manual_file VARCHAR(500) NULL,
--          -- (2) 机器参数（12 列）
--          ADD COLUMN voltage VARCHAR(100) NULL,
--          ADD COLUMN frequency VARCHAR(100) NULL,
--          ADD COLUMN power VARCHAR(100) NULL,
--          ADD COLUMN packaging_speed VARCHAR(100) NULL,
--          ADD COLUMN device_weight_kg VARCHAR(100) NULL,
--          ADD COLUMN device_size VARCHAR(100) NULL,
--          ADD COLUMN max_film_width VARCHAR(100) NULL,
--          ADD COLUMN roll_film_diameter VARCHAR(100) NULL,
--          ADD COLUMN max_sealing_size VARCHAR(100) NULL,
--          ADD COLUMN air_pressure_requirement VARCHAR(100) NULL,
--          ADD COLUMN gas_replacement_rate VARCHAR(100) NULL,
--          ADD COLUMN residual_oxygen VARCHAR(100) NULL,
--          -- (3) 国际站履约（6 列）
--          ADD COLUMN customs_code VARCHAR(50) NULL,
--          ADD COLUMN packaging_method_cn VARCHAR(200) NULL,
--          ADD COLUMN packaging_method_en VARCHAR(200) NULL,
--          ADD COLUMN shipping_methods VARCHAR(200) NULL,
--          ADD COLUMN lead_time_stock VARCHAR(100) NULL,
--          ADD COLUMN lead_time_custom VARCHAR(100) NULL,
--          -- (4) 价格（5 列）
--          ADD COLUMN reference_distributor_price VARCHAR(50) NULL,
--          ADD COLUMN reference_retail_price VARCHAR(50) NULL,
--          ADD COLUMN package_size VARCHAR(100) NULL,
--          ADD COLUMN package_weight_kg VARCHAR(100) NULL,
--          ADD COLUMN pricing_notes LONGTEXT NULL,
--          -- (5) 导入溯源（1 列）
--          ADD COLUMN source_row_number INT NULL,
--          -- (6) sale_status（1 列）
--          ADD COLUMN sale_status VARCHAR(100) NULL;
--
--   2. 从 V106 备份恢复 44 列数据（含 sale_status，全部 44 列均由 V106 备份）：
--      UPDATE product_models pm
--      JOIN product_models_v2_backup b ON b.model_id = pm.id
--      SET pm.product_line_name = b.product_line_name,
--          pm.product_line_name_en = b.product_line_name_en,
--          pm.equipment_category = b.equipment_category,
--          pm.equipment_category_en = b.equipment_category_en,
--          pm.machine_type = b.machine_type,
--          pm.operation_mode = b.operation_mode,
--          pm.film_material = b.film_material,
--          pm.film_type = b.film_type,
--          pm.automation_level = b.automation_level,
--          pm.body_material = b.body_material,
--          pm.mold_material = b.mold_material,
--          pm.item_type = b.item_type,
--          pm.shop_url = b.shop_url,
--          pm.workshop = b.workshop,
--          pm.applicable_industry = b.applicable_industry,
--          pm.applicable_industry_en = b.applicable_industry_en,
--          pm.applicable_product = b.applicable_product,
--          pm.applicable_product_en = b.applicable_product_en,
--          pm.default_manual_file = b.default_manual_file,
--          pm.voltage = b.voltage,
--          pm.frequency = b.frequency,
--          pm.power = b.power,
--          pm.packaging_speed = b.packaging_speed,
--          pm.device_weight_kg = b.device_weight_kg,
--          pm.device_size = b.device_size,
--          pm.max_film_width = b.max_film_width,
--          pm.roll_film_diameter = b.roll_film_diameter,
--          pm.max_sealing_size = b.max_sealing_size,
--          pm.air_pressure_requirement = b.air_pressure_requirement,
--          pm.gas_replacement_rate = b.gas_replacement_rate,
--          pm.residual_oxygen = b.residual_oxygen,
--          pm.customs_code = b.customs_code,
--          pm.packaging_method_cn = b.packaging_method_cn,
--          pm.packaging_method_en = b.packaging_method_en,
--          pm.shipping_methods = b.shipping_methods,
--          pm.lead_time_stock = b.lead_time_stock,
--          pm.lead_time_custom = b.lead_time_custom,
--          pm.reference_distributor_price = b.reference_distributor_price,
--          pm.reference_retail_price = b.reference_retail_price,
--          pm.package_size = b.package_size,
--          pm.package_weight_kg = b.package_weight_kg,
--          pm.pricing_notes = b.pricing_notes,
--          pm.source_row_number = b.source_row_number,
--          pm.sale_status = b.sale_status
--      WHERE pm.product_line_name IS NULL;  -- 用任意一列判断是否需要恢复
--

--   3. 业务代码无需回滚（V107 新字段并存，旧字段恢复后可继续使用）。
--      但若业务代码已切到新字段，建议保持新字段，旧字段恢复后仅作回滚后备。
