-- 官网产品多维选型：与内部自由文本说明分离，供后台规范维护和官网组合筛选。
ALTER TABLE product_models
    ADD COLUMN website_application_tags JSON NULL COMMENT '包装产品/行业标签' AFTER website_summary,
    ADD COLUMN website_container_tags JSON NULL COMMENT '包装容器标签' AFTER website_application_tags,
    ADD COLUMN website_process_tags JSON NULL COMMENT '包装工艺标签' AFTER website_container_tags,
    ADD COLUMN website_capacity_level VARCHAR(50) NULL COMMENT '目标产能级别' AFTER website_process_tags,
    ADD INDEX idx_product_website_capacity (website_enabled, website_capacity_level);

-- 对已有档案做保守初始化：仅根据名称和明确的适用说明添加标签，后台可继续校正。
UPDATE product_models SET website_application_tags = JSON_ARRAY() WHERE website_application_tags IS NULL;
UPDATE product_models SET website_container_tags = JSON_ARRAY() WHERE website_container_tags IS NULL;
UPDATE product_models SET website_process_tags = JSON_ARRAY() WHERE website_process_tags IS NULL;

UPDATE product_models SET website_application_tags = JSON_ARRAY_APPEND(website_application_tags, '$', '鲜食与预制菜')
WHERE CONCAT_WS(' ', name, applicable_product, applicable_industry) REGEXP '预制菜|鲜食|中央厨房';
UPDATE product_models SET website_application_tags = JSON_ARRAY_APPEND(website_application_tags, '$', '生鲜肉类')
WHERE CONCAT_WS(' ', name, applicable_product, applicable_industry) REGEXP '生鲜|肉类|牛肉|猪肉|禽肉';
UPDATE product_models SET website_application_tags = JSON_ARRAY_APPEND(website_application_tags, '$', '熟食与餐饮')
WHERE CONCAT_WS(' ', name, applicable_product, applicable_industry) REGEXP '熟食|餐饮|快餐|外卖';
UPDATE product_models SET website_application_tags = JSON_ARRAY_APPEND(website_application_tags, '$', '烘焙食品')
WHERE CONCAT_WS(' ', name, applicable_product, applicable_industry) REGEXP '烘焙|面包|糕点';
UPDATE product_models SET website_application_tags = JSON_ARRAY_APPEND(website_application_tags, '$', '果蔬农产品')
WHERE CONCAT_WS(' ', name, applicable_product, applicable_industry) REGEXP '果蔬|水果|蔬菜|农产品';

UPDATE product_models SET website_container_tags = JSON_ARRAY_APPEND(website_container_tags, '$', '托盒/餐盒')
WHERE CONCAT_WS(' ', name, applicable_product, applicable_scope) REGEXP '托盒|餐盒|盒装|盒型';
UPDATE product_models SET website_container_tags = JSON_ARRAY_APPEND(website_container_tags, '$', '杯/碗')
WHERE CONCAT_WS(' ', name, applicable_product, applicable_scope) REGEXP '杯|碗';
UPDATE product_models SET website_container_tags = JSON_ARRAY_APPEND(website_container_tags, '$', '桶/罐')
WHERE CONCAT_WS(' ', name, applicable_product, applicable_scope) REGEXP '桶|罐';
UPDATE product_models SET website_container_tags = JSON_ARRAY_APPEND(website_container_tags, '$', '袋装产品')
WHERE CONCAT_WS(' ', name, applicable_product, applicable_scope) REGEXP '袋装|包装袋';

UPDATE product_models SET website_process_tags = JSON_ARRAY_APPEND(website_process_tags, '$', '普通封口')
WHERE CONCAT_WS(' ', name, equipment_category, packaging_method_cn) REGEXP '封口' AND CONCAT_WS(' ', name, equipment_category) NOT REGEXP '气调|真空';
UPDATE product_models SET website_process_tags = JSON_ARRAY_APPEND(website_process_tags, '$', '气调保鲜')
WHERE CONCAT_WS(' ', name, equipment_category, packaging_method_cn) REGEXP '气调|保鲜';
UPDATE product_models SET website_process_tags = JSON_ARRAY_APPEND(website_process_tags, '$', '真空包装')
WHERE CONCAT_WS(' ', name, equipment_category, packaging_method_cn) REGEXP '真空';
UPDATE product_models SET website_process_tags = JSON_ARRAY_APPEND(website_process_tags, '$', '热收缩')
WHERE CONCAT_WS(' ', name, equipment_category, packaging_method_cn) REGEXP '热收缩|收缩';
UPDATE product_models SET website_process_tags = JSON_ARRAY_APPEND(website_process_tags, '$', '贴体包装')
WHERE CONCAT_WS(' ', name, equipment_category, packaging_method_cn) REGEXP '贴体';

UPDATE product_models SET website_capacity_level = '手动/小批量'
WHERE CONCAT_WS(' ', automation_level, operation_mode, machine_type) REGEXP '手动|小型';
UPDATE product_models SET website_capacity_level = '半自动/中等产能'
WHERE website_capacity_level IS NULL AND CONCAT_WS(' ', automation_level, operation_mode) REGEXP '半自动|气动';
UPDATE product_models SET website_capacity_level = '全自动/高产能'
WHERE website_capacity_level IS NULL AND CONCAT_WS(' ', automation_level, operation_mode, machine_type) REGEXP '全自动|连续';
UPDATE product_models SET website_capacity_level = '产线级'
WHERE CONCAT_WS(' ', name, product_line_name, machine_type) REGEXP '产线|流水线';
