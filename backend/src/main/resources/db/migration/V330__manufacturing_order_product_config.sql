-- 生产任务单（业务员下单）产品配置参数快照。
--
-- 氚云《生产任务单》表单里的产品配置区（盒子尺寸/盒子高度/模具排列/盒子形状/
-- 盒子材质/电压/插头/品牌/可选配置/配置说明/是否需要额外模具）是品类级生产参数，
-- 不参与库存身份（身份由 sales_product_id + 产出清单锚定，#28），因此以 JSON
-- 快照落列而不是拆成几十个列；结构化保存，后续跟单/车间执行页可直接解析展示。
--
-- 幂等加列（MySQL 无 ADD COLUMN IF NOT EXISTS）：若该列已被手工提前补到真库
-- （契约测试上下文不跑 Flyway 的场景），重放时静默跳过，不会报 1060。
DROP PROCEDURE IF EXISTS v330_add_product_config_json;
CREATE PROCEDURE v330_add_product_config_json()
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                   WHERE TABLE_SCHEMA = DATABASE()
                     AND TABLE_NAME = 'manufacturing_orders'
                     AND COLUMN_NAME = 'product_config_json') THEN
        ALTER TABLE manufacturing_orders
            ADD COLUMN product_config_json JSON NULL
            COMMENT '产品配置参数快照：盒子尺寸/高度/形状/材质/电压/插头/品牌/模具排列/可选配置/配置说明/是否额外模具（建单时业务员填写，JSON 对象）';
    END IF;
END;
CALL v330_add_product_config_json();
DROP PROCEDURE IF EXISTS v330_add_product_config_json;
