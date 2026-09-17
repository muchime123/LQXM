-- V9: 恢复并补充产品模型字段
-- 2026-06-29 | 来源：LQ.xlsx 初始数据 + fields.js 规划字段
-- 注意：JPA ddl-auto:update 会自动新建列；本脚本用于手动迁移场景

DROP PROCEDURE IF EXISTS add_column_if_missing;
DELIMITER //
CREATE PROCEDURE add_column_if_missing(
  IN tbl VARCHAR(128), IN col VARCHAR(128), IN col_def VARCHAR(512)
)
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = tbl AND COLUMN_NAME = col
  ) THEN
    SET @sql = CONCAT('ALTER TABLE ', tbl, ' ADD COLUMN ', col, ' ', col_def);
    PREPARE stmt FROM @sql;
    EXECUTE stmt;
    DEALLOCATE PREPARE stmt;
  END IF;
END//
DELIMITER ;

CALL add_column_if_missing('product_models', 'supplement', 'VARCHAR(200) COMMENT "产品版本补充描述"');
CALL add_column_if_missing('product_models', 'reference_distributor_price', 'VARCHAR(50) COMMENT "参考经销价"');
CALL add_column_if_missing('product_models', 'reference_retail_price', 'VARCHAR(50) COMMENT "参考零售价"');
CALL add_column_if_missing('product_models', 'output_count', 'VARCHAR(50) COMMENT "一模几出"');
CALL add_column_if_missing('product_models', 'power_formula', 'VARCHAR(200) COMMENT "电源公式"');
CALL add_column_if_missing('product_models', 'equipment_size_spec', 'VARCHAR(100) COMMENT "设备尺寸规格"');
CALL add_column_if_missing('product_models', 'worktable_height_mm', 'VARCHAR(100) COMMENT "工作台高度mm"');
CALL add_column_if_missing('product_models', 'package_size', 'VARCHAR(100) COMMENT "包装尺寸"');
CALL add_column_if_missing('product_models', 'package_weight_kg', 'VARCHAR(100) COMMENT "包装重量kg"');
CALL add_column_if_missing('product_models', 'temp_range', 'VARCHAR(100) COMMENT "工作温度/温控范围"');
CALL add_column_if_missing('product_models', 'heating_method', 'VARCHAR(100) COMMENT "加热方式"');
CALL add_column_if_missing('product_models', 'plc_control_system', 'VARCHAR(100) COMMENT "PLC/控制系统"');
CALL add_column_if_missing('product_models', 'touch_screen_config', 'VARCHAR(100) COMMENT "触摸屏配置"');
CALL add_column_if_missing('product_models', 'cutting_method', 'VARCHAR(100) COMMENT "切膜方式"');
CALL add_column_if_missing('product_models', 'cover_material', 'VARCHAR(100) COMMENT "覆盖材料"');
CALL add_column_if_missing('product_models', 'custom_mold_supported', 'VARCHAR(100) COMMENT "可否定制模具"');
CALL add_column_if_missing('product_models', 'vacuum_supported', 'VARCHAR(100) COMMENT "是否支持抽真空"');
CALL add_column_if_missing('product_models', 'vacuum_pump', 'VARCHAR(100) COMMENT "真空泵"');
CALL add_column_if_missing('product_models', 'vacuum_chamber', 'VARCHAR(100) COMMENT "真空室"');
CALL add_column_if_missing('product_models', 'ultimate_vacuum', 'VARCHAR(100) COMMENT "极限真空度"');
CALL add_column_if_missing('product_models', 'gas_fresh_keeping_description', 'VARCHAR(500) COMMENT "气调保鲜说明"');
CALL add_column_if_missing('product_models', 'cylinder', 'VARCHAR(100) COMMENT "气缸"');
CALL add_column_if_missing('product_models', 'air_shaft', 'VARCHAR(100) COMMENT "气胀轴"');
CALL add_column_if_missing('product_models', 'air_pressure_gauge', 'VARCHAR(100) COMMENT "空压表配置"');
CALL add_column_if_missing('product_models', 'oil_water_separator', 'VARCHAR(100) COMMENT "油水分离器配置"');
CALL add_column_if_missing('product_models', 'optional_device', 'VARCHAR(500) COMMENT "选配装置"');

DROP PROCEDURE IF EXISTS add_column_if_missing;
