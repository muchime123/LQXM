-- V106: V2 备份死字段
-- ======================================================================
-- 目的：在执行 V107-V112 字段删除/改名前，将待删字段的全量数据备份到独立表，
--      保留 30 天后由 DBA 手动清理。
--
-- 备份范围：
--   1. product_models_v2_backup：备份 product_models 待删 49 列
--      - 旧分类与业务字段（19 列，合并到 category 或废弃）
--      - 合并到 description_cn/en 后删除（4 列）
--      - 机器参数（12 列，已下沉到 product_machine_tiers）
--      - 国际站履约（6 列，未来 shipping_profile 表）
--      - 价格（5 列，未来 pricing_system 表）
--      - 导入溯源（1 列，无读取链路）
--      - sale_status（1 列，V107 改名 status 后 V108 删除）
--      - applicable_scope_en（1 列，V112 删除）
--
--   2. product_variants_v2_backup：备份 product_variants 待删 65 列
--      - 导入溯源与继承链（4 列）
--      - 旧辅助描述（4 列）
--      - 机器参数：与 ProductModel 同名重复字段（11 列）
--      - 机器配置扩展（5 列）
--      - 温度/切割/材质（5 列）
--      - 机器组件配置 27 列（未来 machine_configurations 表）
--      - 规格表/真空/气调（9 列）
--
-- 不备份的字段（说明）：
--   - 智能选型标签（application_tags/container_tags/packaging_process_tags）：V2 保留
--   - 官网发布字段（website_*）：V2 保留
--   - 营销字段（introduction_*/search_keywords_*）：V2 保留
--   - product_variant_packaging_links 改名字段：仅改名 compatibility_status→relation_type
--     与 override_reason→remark，数据保留在列中改名，可逆，无需独立备份
--
-- 风险：低，仅 CREATE TABLE + INSERT SELECT + ALTER 加索引，不修改原表。
--
-- 回滚提示：
--   本脚本本身不可逆（备份表创建后无法自动 DROP）。
--   如 V107-V112 执行失败需回滚原表字段：
--     1. 按对应 V_xxx 脚本顶部注释的"回滚 ALTER 语句"恢复原字段；
--     2. UPDATE product_models pm
--        JOIN product_models_v2_backup b ON b.model_id = pm.id
--        SET pm.<列名> = b.<列名> WHERE pm.<列名> IS NULL;
--     3. UPDATE product_variants pv
--        JOIN product_variants_v2_backup b ON b.variant_id = pv.id
--        SET pv.<列名> = b.<列名> WHERE pv.<列名> IS NULL;
--   备份表保留 30 天后由 DBA 手动 DROP TABLE。
--
-- 验证：
--   执行后人工核对以下查询行数应一致：
--     SELECT COUNT(*) FROM product_models;
--     SELECT COUNT(DISTINCT model_id) FROM product_models_v2_backup;
--     SELECT COUNT(*) FROM product_variants;
--     SELECT COUNT(DISTINCT variant_id) FROM product_variants_v2_backup;
-- ======================================================================


-- --------------------------------------------------------------------
-- 1. product_models_v2_backup：备份 product_models 待删 49 列
-- --------------------------------------------------------------------
CREATE TABLE product_models_v2_backup AS
SELECT
    id AS model_id,
    -- (1) 旧分类与业务字段（19 列）—— 合并到新字段 category / 废弃
    product_line_name,
    product_line_name_en,
    equipment_category,
    equipment_category_en,
    machine_type,
    operation_mode,
    film_material,
    film_type,
    automation_level,
    body_material,
    mold_material,
    item_type,
    shop_url,
    workshop,
    applicable_industry,
    applicable_industry_en,
    applicable_product,
    applicable_product_en,
    default_manual_file,
    -- (2) 合并到 description_cn/en 后删除（4 列）
    equipment_advantages,
    equipment_advantages_en,
    core_config_description,
    core_config_description_en,
    -- (3) 机器参数（12 列）—— 已下沉到 product_machine_tiers
    voltage,
    frequency,
    power,
    packaging_speed,
    device_weight_kg,
    device_size,
    max_film_width,
    roll_film_diameter,
    max_sealing_size,
    air_pressure_requirement,
    gas_replacement_rate,
    residual_oxygen,
    -- (4) 国际站履约（6 列）—— 未来 shipping_profile 表
    customs_code,
    packaging_method_cn,
    packaging_method_en,
    shipping_methods,
    lead_time_stock,
    lead_time_custom,
    -- (5) 价格（5 列）—— 未来 pricing_system 表
    reference_distributor_price,
    reference_retail_price,
    package_size,
    package_weight_kg,
    pricing_notes,
    -- (6) 导入溯源（1 列）
    source_row_number,
    -- (7) sale_status（1 列，V107 改名 status 后 V108 删除）
    sale_status,
    -- (8) applicable_scope_en（1 列，V112 删除，applicable_scope 改名 application_scope）
    applicable_scope_en,
    -- 元数据：备份时刻
    NOW() AS snapshot_at
FROM product_models;

ALTER TABLE product_models_v2_backup
    ADD COLUMN backup_id BIGINT NOT NULL AUTO_INCREMENT FIRST,
    ADD PRIMARY KEY (backup_id),
    ADD INDEX idx_pm_v2_backup_model_id (model_id),
    ADD INDEX idx_pm_v2_backup_snapshot_at (snapshot_at);


-- --------------------------------------------------------------------
-- 2. product_variants_v2_backup：备份 product_variants 待删 65 列
-- --------------------------------------------------------------------
CREATE TABLE product_variants_v2_backup AS
SELECT
    id AS variant_id,
    model_id,
    -- (1) 导入溯源与继承链（4 列）
    source_row_number,
    origin,
    based_on_variant_id,
    sku_image,
    -- (2) 旧辅助描述字段（4 列）
    box_height,
    supplement,
    applicable_product,
    power_formula,
    -- (3) 机器参数：与 ProductModel 同名重复字段（11 列）
    voltage,
    frequency,
    power,
    packaging_speed,
    output_count,
    device_size,
    device_weight_kg,
    package_size,
    package_weight_kg,
    max_film_width,
    roll_film_diameter,
    -- (4) 机器配置扩展（5 列）
    optional_device,
    equipment_size_spec,
    worktable_height_mm,
    film_or_box_size,
    box_or_mold_shape,
    -- (5) 温度/切割/材质（5 列）
    temp_range,
    cutting_method,
    cover_material,
    custom_mold_supported,
    heating_method,
    -- (6) 机器组件配置 27 列 —— 未来 machine_configurations 表
    plc_control_system,
    touch_screen_config,
    dealer_price,
    retail_price,
    temperature_module,
    air_switch,
    ac_relay,
    intermediate_relay,
    solid_state_relay,
    switching_power_supply,
    button_switch,
    solenoid_valve_config,
    vacuum_pump,
    cylinder,
    servo_motor,
    vacuum_chamber,
    air_shaft,
    breaker,
    roll_film_motor,
    phase_sequence_protector,
    proximity_switch,
    air_pressure_gauge,
    oil_water_separator,
    sensor_photoelectric,
    film_photoelectric,
    motor_config,
    display_config,
    -- (7) 规格表/真空/气调（9 列）
    specification_table,
    vacuum_supported,
    gas_fresh_keeping_description,
    residual_oxygen,
    mouth_spec,
    air_pressure_requirement,
    customs_code,
    gas_replacement_rate,
    ultimate_vacuum,
    -- 元数据：备份时刻
    NOW() AS snapshot_at
FROM product_variants;

ALTER TABLE product_variants_v2_backup
    ADD COLUMN backup_id BIGINT NOT NULL AUTO_INCREMENT FIRST,
    ADD PRIMARY KEY (backup_id),
    ADD INDEX idx_pv_v2_backup_variant_id (variant_id),
    ADD INDEX idx_pv_v2_backup_model_id (model_id),
    ADD INDEX idx_pv_v2_backup_snapshot_at (snapshot_at);
