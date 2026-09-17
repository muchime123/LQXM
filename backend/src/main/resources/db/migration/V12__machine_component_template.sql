-- V12: editable default template for machine component configuration.

CREATE TABLE IF NOT EXISTS machine_component_template_items (
  id BIGINT NOT NULL AUTO_INCREMENT,
  sort_order INT NOT NULL DEFAULT 0,
  component_key VARCHAR(80) NULL,
  category VARCHAR(80) NULL,
  name VARCHAR(100) NULL,
  name_en VARCHAR(150) NULL,
  brand VARCHAR(300) NULL,
  brand_en VARCHAR(300) NULL,
  spec_value VARCHAR(300) NULL,
  remark VARCHAR(500) NULL,
  enabled BIT NOT NULL DEFAULT 1,
  created_at DATETIME(6) NULL,
  updated_at DATETIME(6) NULL,
  PRIMARY KEY (id),
  UNIQUE INDEX uk_machine_component_template_key (component_key),
  INDEX idx_machine_component_template_sort (sort_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO machine_component_template_items
  (sort_order, component_key, category, name, name_en, brand, brand_en, spec_value, enabled, created_at, updated_at)
SELECT d.sort_order, d.component_key, d.category, d.name, d.name_en, d.brand, d.brand_en, d.spec_value, 1, NOW(6), NOW(6)
FROM (
  SELECT 10 sort_order, 'plc' component_key, 'electrical' category, '可编程逻辑控制器' name, 'PLC' name_en, NULL brand, NULL brand_en, NULL spec_value
  UNION ALL SELECT 20, 'touch_screen_mitsubishi', 'electrical', '触摸屏', 'Touch Screen', '日本三菱', 'Mitsubishi, Japan', NULL
  UNION ALL SELECT 30, 'touch_screen_siemens_flexem', 'electrical', '触摸屏', 'Touch Screen', '德国西门子/繁易', 'FLEXEM/Siemens, Germany', NULL
  UNION ALL SELECT 40, 'servo_motor', 'electrical', '伺服电机', 'Servo Motor', '日本三菱', 'Mitsubishi, Japan', NULL
  UNION ALL SELECT 50, 'drive', 'electrical', '驱动', 'Drive', NULL, NULL, NULL
  UNION ALL SELECT 60, 'air_switch_mitsubishi', 'electrical', '空气开关', 'Air Switch', '日本三菱', 'Mitsubishi, Japan', NULL
  UNION ALL SELECT 70, 'air_switch_schneider', 'electrical', '空气开关', 'Air Switch', '法国施耐德', 'Schneider, France', NULL
  UNION ALL SELECT 80, 'solid_state_relay', 'electrical', '固态继电器', 'Solid State Relay', '法国施耐德', 'Schneider, France', NULL
  UNION ALL SELECT 90, 'intermediate_relay', 'electrical', '中间继电器', 'Intermediate Relay', '法国施耐德', 'Schneider, France', NULL
  UNION ALL SELECT 100, 'knob_switch', 'electrical', '旋钮开关', 'Knob Switch', '法国施耐德', 'Schneider, France', NULL
  UNION ALL SELECT 110, 'emergency_stop_switch', 'electrical', '急停开关', 'Emergency Stop Switch', '法国施耐德', 'Schneider, France', NULL
  UNION ALL SELECT 120, 'push_button_switch', 'electrical', '按钮开关', 'Push Button Switch', '法国施耐德', 'Schneider, France', NULL
  UNION ALL SELECT 130, 'ac_contactor', 'electrical', '交流接触器', 'AC Contactor', '法国施耐德', 'Schneider, France', NULL
  UNION ALL SELECT 140, 'proximity_switch', 'sensor', '接近开关', 'Proximity Switch', '日本欧姆龙', 'Omron, Japan', NULL
  UNION ALL SELECT 150, 'photoelectric_switch', 'sensor', '光电开关', 'Photoelectric Switch', '日本欧姆龙', 'Omron, Japan', NULL
  UNION ALL SELECT 160, 'switching_power_supply', 'electrical', '开关电源', 'Switching Power Supply', '台湾明纬', 'Taiwan MEAN WELL', NULL
  UNION ALL SELECT 170, 'temperature_controller', 'electrical', '温控', 'Temperature Controller', '浙江上南', 'Zhejiang Sinny', NULL
  UNION ALL SELECT 180, 'pneumatic_cylinder', 'pneumatic', '气缸', 'Pneumatic Cylinder', '台湾亚德客', 'Taiwan AirTAC', NULL
  UNION ALL SELECT 190, 'oil_water_separator', 'pneumatic', '油水分离器', 'Oil-Water Separator', '台湾亚德客', 'Taiwan AirTAC', NULL
  UNION ALL SELECT 200, 'solenoid_valve', 'pneumatic', '电磁阀', 'Solenoid Valve', '台湾亚德客', 'Taiwan AirTAC', NULL
  UNION ALL SELECT 210, 'control_valve', 'pneumatic', '调节阀', 'Control Valve', '浙江亿口', 'Zhejiang Easun', NULL
  UNION ALL SELECT 220, 'machine_casing', 'material', '机壳', 'Machine Casing', NULL, NULL, '304不锈钢 304SS'
  UNION ALL SELECT 230, 'transmission_chain', 'material', '传动链条', 'Transmission Chain', NULL, NULL, '304不锈钢 304SS'
  UNION ALL SELECT 240, 'spindle_bearing', 'mechanical', '主轴轴承', 'Spindle Bearing', NULL, NULL, 'UCF206'
  UNION ALL SELECT 250, 'air_tank', 'pneumatic', '储气罐', 'Air Tank', NULL, NULL, '30L不锈钢 30L stainless steel'
  UNION ALL SELECT 260, 'upper_heater', 'heating', '上加热', 'Upper Heater', NULL, NULL, 'H62铜 H62 Brass'
  UNION ALL SELECT 270, 'mold', 'material', '模具', 'Mold', NULL, NULL, '耐酸不锈钢 Acid-Resistant Stainless Aluminum'
  UNION ALL SELECT 280, 'circuit_breaker', 'electrical', '断路器', 'Circuit Breaker', '正泰', 'Chint', NULL
  UNION ALL SELECT 290, 'phase_sequence_protector', 'electrical', '相序保护器', 'Phase Sequence Protector', '正泰', 'Chint', NULL
) d
WHERE NOT EXISTS (
  SELECT 1 FROM machine_component_template_items existing
  WHERE existing.component_key = d.component_key
);
