-- V109: product_variants 删除 65 冗余字段
-- ========================================================================================
-- 范围：
--   一次性删除 product_variants 的 65 冗余字段。
--   所有 65 列已在 V106 备份至 product_variants_v2_backup。
--
-- 删除字段分组（共 65 列）：
--   (1) 导入溯源与继承链（4 列）：source_row_number / origin / based_on_variant_id / sku_image
--   (2) 旧辅助描述字段（4 列）：box_height / supplement / applicable_product / power_formula
--   (3) 机器参数：与 ProductModel 同名重复字段（11 列）：voltage / frequency / power /
--       packaging_speed / output_count / device_size / device_weight_kg / package_size /
--       package_weight_kg / max_film_width / roll_film_diameter
--   (4) 机器配置扩展（5 列）：optional_device / equipment_size_spec / worktable_height_mm /
--       film_or_box_size / box_or_mold_shape
--   (5) 温度/切割/材质（5 列）：temp_range / cutting_method / cover_material /
--       custom_mold_supported / heating_method
--   (6) 机器组件配置 27 列：plc_control_system / touch_screen_config / dealer_price /
--       retail_price / temperature_module / air_switch / ac_relay / intermediate_relay /
--       solid_state_relay / switching_power_supply / button_switch / solenoid_valve_config /
--       vacuum_pump / cylinder / servo_motor / vacuum_chamber / air_shaft / breaker /
--       roll_film_motor / phase_sequence_protector / proximity_switch / air_pressure_gauge /
--       oil_water_separator / sensor_photoelectric / film_photoelectric / motor_config /
--       display_config
--   (7) 规格表/真空/气调（9 列）：specification_table / vacuum_supported /
--       gas_fresh_keeping_description / residual_oxygen / mouth_spec /
--       air_pressure_requirement / customs_code / gas_replacement_rate / ultimate_vacuum
--
-- 不删除（V2 保留 15 列）：
--   id / model_id / machine_tier_id / variant_name / spec_key / baseline /
--   mold_size_type / mold_length_mm / mold_width_mm / mold_diameter_mm / mold_size_label /
--   remark / deleted_at / created_at / updated_at
--   （sort_order 由 V110 新增）
--
-- 前置条件：
--   - V106 已执行：product_variants_v2_backup 表存在且数据完整（65 列已备份）
--   - 业务代码已切换完成（Java 实体 / DTO / Service / 前端 type）：
--       * 不再读 voltage / frequency / power / ... （机器参数已下沉到 machine_tier）
--       * 不再读 plc_control_system / touch_screen_config / ... （机器配置不再展示）
--       * 不再读 dealer_price / retail_price （价格模块已下线）
--       * 不再读 origin / based_on_variant_id （继承链机制废弃）
--       * 不再读 sku_image （由 product_assets 关联 variant_id 表达）
--       * 不再读 specification_table （由 machine_tier + mold_* 结构化表达）
--   - 前端 CatalogVariant 接口已删除对应字段
--
-- !!! 特殊处理：based_on_variant_id 可能存在 FK 约束（自引用 ProductVariant）
-- !!! DBA 执行前必须先检查并删除约束（如存在），否则 ALTER DROP COLUMN 会失败：
-- !!!   SELECT CONSTRAINT_NAME FROM information_schema.KEY_COLUMN_USAGE
-- !!!   WHERE TABLE_NAME = 'product_variants'
-- !!!     AND COLUMN_NAME = 'based_on_variant_id'
-- !!!     AND TABLE_SCHEMA = DATABASE();
-- !!! 若返回结果，先执行：
-- !!!   ALTER TABLE product_variants DROP FOREIGN KEY <约束名>;
-- !!! （V2 已废弃继承链机制，FK 不再需要恢复）
--
-- 风险：中。一次性删 65 列，MySQL 8.0+ 单 ALTER INPLACE。
--       表行数大可拆为 6 批执行（见末尾"分批执行备选方案"）。
--
-- 回滚方式：见末尾"回滚提示"。
-- ========================================================================================


-- --------------------------------------------------------------------
-- 步骤 1：前置验证（执行前人工核对，Flyway 不暂停）
-- --------------------------------------------------------------------
-- V106 备份表存在：
--   SHOW TABLES LIKE 'product_variants_v2_backup';                -- 应存在
--
-- based_on_variant_id FK 约束检查：
--   SELECT CONSTRAINT_NAME FROM information_schema.KEY_COLUMN_USAGE
--   WHERE TABLE_NAME = 'product_variants'
--     AND COLUMN_NAME = 'based_on_variant_id'
--     AND TABLE_SCHEMA = DATABASE();
--   若返回结果，先执行：ALTER TABLE product_variants DROP FOREIGN KEY <约束名>;
--
-- 业务代码切换完成（应无引用）：
--   grep -rn 'getVoltage\|getFrequency\|getPower\|getPackagingSpeed' backend/src/main/java/com/liqiang/archive --include=*.java
--   grep -rn 'getOutputCount\|getDeviceSize\|getDeviceWeightKg' backend/src/main/java/com/liqiang/archive --include=*.java
--   grep -rn 'getPlcControlSystem\|getVacuumPump\|getServoMotor\|getCylinder' backend/src/main/java/com/liqiang/archive --include=*.java
--   grep -rn 'getDealerPrice\|getRetailPrice' backend/src/main/java/com/liqiang/archive --include=*.java
--   grep -rn 'getOrigin\|getBasedOnVariant\|getSkuImage' backend/src/main/java/com/liqiang/archive --include=*.java
--   grep -rn 'getSpecificationTable\|getVacuumSupported\|getUltimateVacuum' backend/src/main/java/com/liqiang/archive --include=*.java
--   grep -rn 'getMouthSpec\|getCustomsCode\|getGasReplacementRate' backend/src/main/java/com/liqiang/archive --include=*.java


-- --------------------------------------------------------------------
-- 步骤 2：删除 65 列（单个 ALTER，MySQL 8.0+ INPLACE）
-- --------------------------------------------------------------------
ALTER TABLE product_variants
    -- (1) 导入溯源与继承链（4 列）
    DROP COLUMN source_row_number,
    DROP COLUMN origin,
    DROP COLUMN based_on_variant_id,
    DROP COLUMN sku_image,
    -- (2) 旧辅助描述字段（4 列）
    DROP COLUMN box_height,
    DROP COLUMN supplement,
    DROP COLUMN applicable_product,
    DROP COLUMN power_formula,
    -- (3) 机器参数：与 ProductModel 同名重复字段（11 列）
    DROP COLUMN voltage,
    DROP COLUMN frequency,
    DROP COLUMN power,
    DROP COLUMN packaging_speed,
    DROP COLUMN output_count,
    DROP COLUMN device_size,
    DROP COLUMN device_weight_kg,
    DROP COLUMN package_size,
    DROP COLUMN package_weight_kg,
    DROP COLUMN max_film_width,
    DROP COLUMN roll_film_diameter,
    -- (4) 机器配置扩展（5 列）
    DROP COLUMN optional_device,
    DROP COLUMN equipment_size_spec,
    DROP COLUMN worktable_height_mm,
    DROP COLUMN film_or_box_size,
    DROP COLUMN box_or_mold_shape,
    -- (5) 温度/切割/材质（5 列）
    DROP COLUMN temp_range,
    DROP COLUMN cutting_method,
    DROP COLUMN cover_material,
    DROP COLUMN custom_mold_supported,
    DROP COLUMN heating_method,
    -- (6) 机器组件配置 27 列
    DROP COLUMN plc_control_system,
    DROP COLUMN touch_screen_config,
    DROP COLUMN dealer_price,
    DROP COLUMN retail_price,
    DROP COLUMN temperature_module,
    DROP COLUMN air_switch,
    DROP COLUMN ac_relay,
    DROP COLUMN intermediate_relay,
    DROP COLUMN solid_state_relay,
    DROP COLUMN switching_power_supply,
    DROP COLUMN button_switch,
    DROP COLUMN solenoid_valve_config,
    DROP COLUMN vacuum_pump,
    DROP COLUMN cylinder,
    DROP COLUMN servo_motor,
    DROP COLUMN vacuum_chamber,
    DROP COLUMN air_shaft,
    DROP COLUMN breaker,
    DROP COLUMN roll_film_motor,
    DROP COLUMN phase_sequence_protector,
    DROP COLUMN proximity_switch,
    DROP COLUMN air_pressure_gauge,
    DROP COLUMN oil_water_separator,
    DROP COLUMN sensor_photoelectric,
    DROP COLUMN film_photoelectric,
    DROP COLUMN motor_config,
    DROP COLUMN display_config,
    -- (7) 规格表/真空/气调（9 列）
    DROP COLUMN specification_table,
    DROP COLUMN vacuum_supported,
    DROP COLUMN gas_fresh_keeping_description,
    DROP COLUMN residual_oxygen,
    DROP COLUMN mouth_spec,
    DROP COLUMN air_pressure_requirement,
    DROP COLUMN customs_code,
    DROP COLUMN gas_replacement_rate,
    DROP COLUMN ultimate_vacuum;


-- --------------------------------------------------------------------
-- 步骤 3：孤儿索引检查（自动清理说明）
-- product_variants 仅有 idx_variant_model_id（基于 model_id）和 idx_variant_machine_tier（基于 machine_tier_id），
-- 两列均保留，MySQL DROP COLUMN 时不会触发孤儿索引清理。
-- 验证：
--   SHOW INDEX FROM product_variants;
-- --------------------------------------------------------------------


-- --------------------------------------------------------------------
-- 分批执行备选方案（表行数大时按批拆分，每批获取表锁更短）
-- --------------------------------------------------------------------
-- 第 1 批：导入溯源与继承链 + 旧辅助描述（8 列）
-- ALTER TABLE product_variants
--     DROP COLUMN source_row_number, DROP COLUMN origin,
--     DROP COLUMN based_on_variant_id, DROP COLUMN sku_image,
--     DROP COLUMN box_height, DROP COLUMN supplement,
--     DROP COLUMN applicable_product, DROP COLUMN power_formula;
--
-- 第 2 批：机器参数（11 列）
-- ALTER TABLE product_variants
--     DROP COLUMN voltage, DROP COLUMN frequency, DROP COLUMN power,
--     DROP COLUMN packaging_speed, DROP COLUMN output_count,
--     DROP COLUMN device_size, DROP COLUMN device_weight_kg,
--     DROP COLUMN package_size, DROP COLUMN package_weight_kg,
--     DROP COLUMN max_film_width, DROP COLUMN roll_film_diameter;
--
-- 第 3 批：机器配置扩展 + 温度/切割/材质（10 列）
-- ALTER TABLE product_variants
--     DROP COLUMN optional_device, DROP COLUMN equipment_size_spec,
--     DROP COLUMN worktable_height_mm,
--     DROP COLUMN film_or_box_size, DROP COLUMN box_or_mold_shape,
--     DROP COLUMN temp_range, DROP COLUMN cutting_method,
--     DROP COLUMN cover_material, DROP COLUMN custom_mold_supported,
--     DROP COLUMN heating_method;
--
-- 第 4a 批：机器组件配置前 14 列
-- ALTER TABLE product_variants
--     DROP COLUMN plc_control_system, DROP COLUMN touch_screen_config,
--     DROP COLUMN dealer_price, DROP COLUMN retail_price,
--     DROP COLUMN temperature_module, DROP COLUMN air_switch,
--     DROP COLUMN ac_relay, DROP COLUMN intermediate_relay,
--     DROP COLUMN solid_state_relay, DROP COLUMN switching_power_supply,
--     DROP COLUMN button_switch, DROP COLUMN solenoid_valve_config,
--     DROP COLUMN vacuum_pump, DROP COLUMN cylinder;
--
-- 第 4b 批：机器组件配置后 13 列
-- ALTER TABLE product_variants
--     DROP COLUMN servo_motor, DROP COLUMN vacuum_chamber,
--     DROP COLUMN air_shaft, DROP COLUMN breaker,
--     DROP COLUMN roll_film_motor, DROP COLUMN phase_sequence_protector,
--     DROP COLUMN proximity_switch, DROP COLUMN air_pressure_gauge,
--     DROP COLUMN oil_water_separator, DROP COLUMN sensor_photoelectric,
--     DROP COLUMN film_photoelectric, DROP COLUMN motor_config,
--     DROP COLUMN display_config;
--
-- 第 5 批：规格表/真空/气调（9 列）
-- ALTER TABLE product_variants
--     DROP COLUMN specification_table, DROP COLUMN vacuum_supported,
--     DROP COLUMN gas_fresh_keeping_description, DROP COLUMN residual_oxygen,
--     DROP COLUMN mouth_spec, DROP COLUMN air_pressure_requirement,
--     DROP COLUMN customs_code, DROP COLUMN gas_replacement_rate,
--     DROP COLUMN ultimate_vacuum;


-- --------------------------------------------------------------------
-- 回滚提示
-- --------------------------------------------------------------------
-- 如 V109 失败需回滚：
--
--   1. 恢复删除的 65 列（按原类型重建，全部 nullable 避免约束冲突）：
--      ALTER TABLE product_variants
--          -- (1) 导入溯源与继承链（4 列）
--          ADD COLUMN source_row_number INT NULL,
--          ADD COLUMN origin VARCHAR(30) NULL,
--          ADD COLUMN based_on_variant_id BIGINT NULL,
--          ADD COLUMN sku_image VARCHAR(500) NULL,
--          -- (2) 旧辅助描述（4 列）
--          ADD COLUMN box_height VARCHAR(100) NULL,
--          ADD COLUMN supplement VARCHAR(100) NULL,
--          ADD COLUMN applicable_product VARCHAR(300) NULL,
--          ADD COLUMN power_formula VARCHAR(200) NULL,
--          -- (3) 机器参数（11 列）
--          ADD COLUMN voltage VARCHAR(100) NULL,
--          ADD COLUMN frequency VARCHAR(100) NULL,
--          ADD COLUMN power VARCHAR(100) NULL,
--          ADD COLUMN packaging_speed VARCHAR(100) NULL,
--          ADD COLUMN output_count VARCHAR(100) NULL,
--          ADD COLUMN device_size VARCHAR(100) NULL,
--          ADD COLUMN device_weight_kg VARCHAR(100) NULL,
--          ADD COLUMN package_size VARCHAR(100) NULL,
--          ADD COLUMN package_weight_kg VARCHAR(100) NULL,
--          ADD COLUMN max_film_width VARCHAR(100) NULL,
--          ADD COLUMN roll_film_diameter VARCHAR(100) NULL,
--          -- (4) 机器配置扩展（5 列）
--          ADD COLUMN optional_device VARCHAR(200) NULL,
--          ADD COLUMN equipment_size_spec VARCHAR(120) NULL,
--          ADD COLUMN worktable_height_mm VARCHAR(100) NULL,
--          ADD COLUMN film_or_box_size VARCHAR(120) NULL,
--          ADD COLUMN box_or_mold_shape VARCHAR(120) NULL,
--          -- (5) 温度/切割/材质（5 列）
--          ADD COLUMN temp_range VARCHAR(120) NULL,
--          ADD COLUMN cutting_method VARCHAR(100) NULL,
--          ADD COLUMN cover_material VARCHAR(200) NULL,
--          ADD COLUMN custom_mold_supported VARCHAR(100) NULL,
--          ADD COLUMN heating_method VARCHAR(100) NULL,
--          -- (6) 机器组件配置（27 列）
--          ADD COLUMN plc_control_system VARCHAR(200) NULL,
--          ADD COLUMN touch_screen_config VARCHAR(200) NULL,
--          ADD COLUMN dealer_price VARCHAR(100) NULL,
--          ADD COLUMN retail_price VARCHAR(100) NULL,
--          ADD COLUMN temperature_module VARCHAR(100) NULL,
--          ADD COLUMN air_switch VARCHAR(100) NULL,
--          ADD COLUMN ac_relay VARCHAR(100) NULL,
--          ADD COLUMN intermediate_relay VARCHAR(100) NULL,
--          ADD COLUMN solid_state_relay VARCHAR(100) NULL,
--          ADD COLUMN switching_power_supply VARCHAR(100) NULL,
--          ADD COLUMN button_switch VARCHAR(120) NULL,
--          ADD COLUMN solenoid_valve_config VARCHAR(200) NULL,
--          ADD COLUMN vacuum_pump VARCHAR(120) NULL,
--          ADD COLUMN cylinder VARCHAR(120) NULL,
--          ADD COLUMN servo_motor VARCHAR(120) NULL,
--          ADD COLUMN vacuum_chamber VARCHAR(120) NULL,
--          ADD COLUMN air_shaft VARCHAR(120) NULL,
--          ADD COLUMN breaker VARCHAR(120) NULL,
--          ADD COLUMN roll_film_motor VARCHAR(120) NULL,
--          ADD COLUMN phase_sequence_protector VARCHAR(120) NULL,
--          ADD COLUMN proximity_switch VARCHAR(120) NULL,
--          ADD COLUMN air_pressure_gauge VARCHAR(120) NULL,
--          ADD COLUMN oil_water_separator VARCHAR(120) NULL,
--          ADD COLUMN sensor_photoelectric VARCHAR(120) NULL,
--          ADD COLUMN film_photoelectric VARCHAR(120) NULL,
--          ADD COLUMN motor_config VARCHAR(120) NULL,
--          ADD COLUMN display_config VARCHAR(120) NULL,
--          -- (7) 规格表/真空/气调（9 列）
--          ADD COLUMN specification_table LONGTEXT NULL,
--          ADD COLUMN vacuum_supported VARCHAR(100) NULL,
--          ADD COLUMN gas_fresh_keeping_description LONGTEXT NULL,
--          ADD COLUMN residual_oxygen VARCHAR(100) NULL,
--          ADD COLUMN mouth_spec VARCHAR(100) NULL,
--          ADD COLUMN air_pressure_requirement VARCHAR(150) NULL,
--          ADD COLUMN customs_code VARCHAR(100) NULL,
--          ADD COLUMN gas_replacement_rate VARCHAR(120) NULL,
--          ADD COLUMN ultimate_vacuum VARCHAR(100) NULL;
--
--   2. 从 V106 备份恢复 65 列数据：
--      UPDATE product_variants pv
--      JOIN product_variants_v2_backup b ON b.variant_id = pv.id
--      SET pv.source_row_number = b.source_row_number,
--          pv.origin = b.origin,
--          pv.based_on_variant_id = b.based_on_variant_id,
--          pv.sku_image = b.sku_image,
--          pv.box_height = b.box_height,
--          pv.supplement = b.supplement,
--          pv.applicable_product = b.applicable_product,
--          pv.power_formula = b.power_formula,
--          pv.voltage = b.voltage,
--          pv.frequency = b.frequency,
--          pv.power = b.power,
--          pv.packaging_speed = b.packaging_speed,
--          pv.output_count = b.output_count,
--          pv.device_size = b.device_size,
--          pv.device_weight_kg = b.device_weight_kg,
--          pv.package_size = b.package_size,
--          pv.package_weight_kg = b.package_weight_kg,
--          pv.max_film_width = b.max_film_width,
--          pv.roll_film_diameter = b.roll_film_diameter,
--          pv.optional_device = b.optional_device,
--          pv.equipment_size_spec = b.equipment_size_spec,
--          pv.worktable_height_mm = b.worktable_height_mm,
--          pv.film_or_box_size = b.film_or_box_size,
--          pv.box_or_mold_shape = b.box_or_mold_shape,
--          pv.temp_range = b.temp_range,
--          pv.cutting_method = b.cutting_method,
--          pv.cover_material = b.cover_material,
--          pv.custom_mold_supported = b.custom_mold_supported,
--          pv.heating_method = b.heating_method,
--          pv.plc_control_system = b.plc_control_system,
--          pv.touch_screen_config = b.touch_screen_config,
--          pv.dealer_price = b.dealer_price,
--          pv.retail_price = b.retail_price,
--          pv.temperature_module = b.temperature_module,
--          pv.air_switch = b.air_switch,
--          pv.ac_relay = b.ac_relay,
--          pv.intermediate_relay = b.intermediate_relay,
--          pv.solid_state_relay = b.solid_state_relay,
--          pv.switching_power_supply = b.switching_power_supply,
--          pv.button_switch = b.button_switch,
--          pv.solenoid_valve_config = b.solenoid_valve_config,
--          pv.vacuum_pump = b.vacuum_pump,
--          pv.cylinder = b.cylinder,
--          pv.servo_motor = b.servo_motor,
--          pv.vacuum_chamber = b.vacuum_chamber,
--          pv.air_shaft = b.air_shaft,
--          pv.breaker = b.breaker,
--          pv.roll_film_motor = b.roll_film_motor,
--          pv.phase_sequence_protector = b.phase_sequence_protector,
--          pv.proximity_switch = b.proximity_switch,
--          pv.air_pressure_gauge = b.air_pressure_gauge,
--          pv.oil_water_separator = b.oil_water_separator,
--          pv.sensor_photoelectric = b.sensor_photoelectric,
--          pv.film_photoelectric = b.film_photoelectric,
--          pv.motor_config = b.motor_config,
--          pv.display_config = b.display_config,
--          pv.specification_table = b.specification_table,
--          pv.vacuum_supported = b.vacuum_supported,
--          pv.gas_fresh_keeping_description = b.gas_fresh_keeping_description,
--          pv.residual_oxygen = b.residual_oxygen,
--          pv.mouth_spec = b.mouth_spec,
--          pv.air_pressure_requirement = b.air_pressure_requirement,
--          pv.customs_code = b.customs_code,
--          pv.gas_replacement_rate = b.gas_replacement_rate,
--          pv.ultimate_vacuum = b.ultimate_vacuum
--      WHERE pv.source_row_number IS NULL;  -- 用任意一列判断是否需要恢复
--
--   3. based_on_variant_id 的 FK 约束通常无需恢复（V2 已废弃继承链机制）。
--      如确需恢复，按业务需要决定约束名后单独 ADD CONSTRAINT。
