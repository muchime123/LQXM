-- V20: 根据最新价格表 (Sealing_Machine_Price_List.pdf) 更新产品定价
-- 2026-07-08 | 来源：国际站产品价格 PDF (3页)
-- 内容：气调机/贴体机/空压机/制氮机 + 片膜机 + 铝盒封口机
-- 操作：1) 更新已有产品定价 2) 新增缺失产品 3) 结构化加价规则

-- 安全检查：确保 pricing_addons / pricing_notes 列存在（JPA ddl-auto 管理，手动迁移场景需补建）
DROP PROCEDURE IF EXISTS v20_add_column_if_missing;
DELIMITER //
CREATE PROCEDURE v20_add_column_if_missing(
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

CALL v20_add_column_if_missing('product_models', 'pricing_addons', 'LONGTEXT COMMENT "选配加价清单JSON"');
CALL v20_add_column_if_missing('product_models', 'pricing_notes', 'LONGTEXT COMMENT "定价说明文本"');

DROP PROCEDURE IF EXISTS v20_add_column_if_missing;

-- ============================================================
-- Part 1: 更新已有产品的型号编码、参考经销价、参考零售价
-- ============================================================

-- ID=65: 利强手动片膜封口机 → 圆形手动片膜机 (φ142以下起步价)
UPDATE product_models SET
  reference_distributor_price = '650',
  reference_retail_price = '1200',
  pricing_notes = '圆形手动片膜机定价表：φ142以下 650/1200；φ160-φ197 750/1300；φ220φ250 850/1500；φ270φ280 1100/1800；φ320 1200/2000。\n方形手动片膜机定价表：90*90,112*112 750/1300；161*112,165*65,184*128 800/1500；220*150,221*168 900/1600；278*197,330*225 1300/2000；370*270不带托杯 1700/2400；370*270带托杯 1800/2600；八角型175*175 900/1500。\n模具费用：370*270机型套模具150元/个，280机型250模具免费，现有模型方圆模具100元/个，小模具50元/个。\n非标定制：铝箔盒材质按口径范围内收费，其它材质按以前价格。',
  updated_at = NOW()
WHERE id = 65;

-- ID=66: 利强手压半自动卷膜封口机 → 铝盒半自动标准款 (14*14起步价)
UPDATE product_models SET
  reference_distributor_price = '1350',
  reference_retail_price = '1800',
  pricing_notes = '半自动标准款定价表：\n方形：14*14~20*14 1350/1800；22*13~22*16 1900/2300；26*17 2100/2600；30*20 2800/3200。\n圆形：Φ95 800/1400；Φ120~Φ180 1400/1800。\n加价规则：不锈钢外壳+100；自动卷膜+300；方形加易撕口+100；圆形加易撕口+300；一出二+500；一出四+800；不规则盒子+200；铝箔光电+100；色带打码机+1500；打孔+50；非标+200。',
  updated_at = NOW()
WHERE id = 66;

-- ID=68: 利强手推自动卷膜封口机 → 手推自动卷膜 STZ2216
UPDATE product_models SET
  model_code = 'STZ2216',
  reference_distributor_price = '2600',
  reference_retail_price = '3100',
  pricing_notes = '手推自动卷膜定价表：STZ2216 2600/3100；STZ3020 2800/3300。\n加价规则：非标尺寸+200；铝箔光电+100；走时间+50；打孔+50。',
  updated_at = NOW()
WHERE id = 68;

-- ID=72: 利强按钮自动卷膜封口机 → 按钮全自动 STZ2216
UPDATE product_models SET
  model_code = 'STZ2216',
  reference_distributor_price = '3100',
  reference_retail_price = '3600',
  pricing_notes = '按钮全自动定价表：STZ2216 3100/3600；STZ3020 3300/3800。\n加价规则：打孔+50。',
  updated_at = NOW()
WHERE id = 72;

-- ID=82: 利强台式气动卷膜封口机 → 台式气动卷膜 LQ250-TS
UPDATE product_models SET
  model_code = 'LQ250-TS',
  reference_distributor_price = '6000',
  reference_retail_price = '8000',
  pricing_notes = '台式气动卷膜定价表：LQ250-TS(250以下) 6000/8000；LQ330-TS(250-330) 7800/9800；LQ450-TS(330-450) 9500/12500；LQ600-TS(450以上) 10500/13800。\n加价规则：卷膜250机型做一出四+800；加不锈钢底座+3000；加铝型材底座+1500；卷膜前后进出+3000；一出二加打码机+2500；一出四加打码+3500；一出六加打码+3700；加充气流式+1500。',
  updated_at = NOW()
WHERE id = 82;

-- ID=83: 利强台式气动片膜封口机 → 台式气动片膜 LQ250P-TS
UPDATE product_models SET
  model_code = 'LQ250P-TS',
  reference_distributor_price = '5000',
  reference_retail_price = '7000',
  pricing_notes = '台式气动片膜定价表：LQ250P-TS(250以下/250一出二+1000) 5000/7000；LQ250P-TS(250-330) 6500/8500；LQ330P-TS(330-450) 7500/9500；LQ450P-TS(450以上) 8500/11000。\n加价规则：气动自吸片膜在常规片膜基础上+1500；加不锈钢底座+3000；加铝型材底座+1500；卷膜前后进出+3000；一出二加打码机+2500；一出四加打码+3500；一出六加打码+3700；加充气流式+1500。',
  updated_at = NOW()
WHERE id = 83;

-- ID=92: LQ750L利强连续式全自动气调卷膜封口机 → 连续式气调机 LQ750L (普封)
UPDATE product_models SET
  model_code = 'LQ750L',
  reference_distributor_price = '48000',
  reference_retail_price = '68000',
  pricing_notes = '连续式气调机LQ750系列：\nLQ750L(普封,2014一出四) 48000/68000\nLQ750LQ(充气款,2014一出四) 68000/98000\nLQ750LZ(真空充气100泵,2014一出四) 78000/108000\n\n连续式气调机LQ450系列：\nLQ450L(普封,2617一出二) 35000/52000\nLQ450LQ(充气款,2617一出二) 42000/62000\nLQ450LZ(真空充气63泵,2617一出二) 48000/68000\n\n连续式气调机LQ380系列：\nLQ380A(普通封口,2216一出四) 68000/88000\nLQ380AQ(充气款,2216一出四) 78000/108000\nLQ380AZ(真空充气100泵,2216一出四) 88000/118000\n\n选配加价：进盒3米输送带+18000；出盒四变一+5000；单排出盒1.5米+1500；在线喷码机+1500。',
  updated_at = NOW()
WHERE id = 92;

-- ID=110: LQ260气调机 → 落地式气调机 LQ260Q (充气款)
UPDATE product_models SET
  model_code = 'LQ260Q',
  reference_distributor_price = '12000',
  reference_retail_price = '17000',
  pricing_notes = '落地式气调机定价表：\n充气款：LQ260Q(2014一出二,控制板) 12000/17000；LQ310Q(2216一出二,控制板) 16000/22000；LQ370Q(2014一出四,控制板) 24000/35000；LQ450Q(3020一出四,PLC) 35000/40000\n真空充气：LQ260Z(20泵,2014一出二,控制板) 15000/19000；LQ310Z(20泵,2216一出二,PLC) 18000/22000；LQ310Z(40泵,2216一出二,PLC) 21000/25000；LQ370Z(40泵,2014一出四,控制板) 32000/40000；LQ370Z(63泵,2014一出四,PLC) 34000/42000；LQ450Q(100泵,3020一出四,PLC) 40000/48000',
  updated_at = NOW()
WHERE id = 110;

-- ID=170: LQ380A全自动流水线气调保鲜机 → 连续式气调机 LQ380A (普通封口)
UPDATE product_models SET
  model_code = 'LQ380A',
  reference_distributor_price = '68000',
  reference_retail_price = '88000',
  pricing_notes = '连续式气调机LQ380系列：\nLQ380A(普通封口,2216一出四) 68000/88000\nLQ380AQ(充气款,2216一出四) 78000/108000\nLQ380AZ(真空充气100泵,2216一出四) 88000/118000\n\n选配加价：在线喷码机+1500。',
  updated_at = NOW()
WHERE id = 170;

-- ID=175: 利强气动自吸片膜封口机 → 气动自吸片膜 (在常规片膜基础上+1500)
UPDATE product_models SET
  reference_distributor_price = '6500',
  reference_retail_price = '8500',
  pricing_notes = '气动自吸片膜在常规片膜基础上+1500。\n参考台式气动片膜定价：LQ250P-TS(250以下) 5000/7000 → 自吸款 6500/8500。',
  updated_at = NOW()
WHERE id = 175;

-- ID=69: 利强手推自动片膜封口机 → 圆形/方形手推自动片膜机
UPDATE product_models SET
  reference_distributor_price = '1700',
  reference_retail_price = '2500',
  pricing_notes = '手推自动片膜机定价表：\n圆形：φ220以下 1700/2500；φ250-φ320 1900/2800\n方形：90*90-221*168 1700/2500；278*197,330*225 1900/2800；八角型175*175 1700/2500\n按钮自动片膜+600元。\n模具费用：370*270机型套模具150元/个，280机型250模具免费，现有模型方圆模具100元/个，小模具50元/个。',
  updated_at = NOW()
WHERE id = 69;

-- ID=73: 利强按钮自动片膜封口机 → 按钮自动片膜 (在手推自动基础上+600)
UPDATE product_models SET
  reference_distributor_price = '2300',
  reference_retail_price = '3100',
  pricing_notes = '按钮自动片膜在手推自动片膜基础上+600元。\n参考：圆形φ220以下 1700/2500 → 按钮自动 2300/3100。',
  updated_at = NOW()
WHERE id = 73;

-- ID=95: LQ450利强立式贴体包装机 → 落地式贴体机
UPDATE product_models SET
  reference_distributor_price = '38000',
  reference_retail_price = '45000',
  pricing_notes = '贴体机定价表：\n台式：LQ280T(10泵,270*180) 6000/9000；LQ350T(20泵,2617一出二) 8000/11000\n落地式：LQ330T(63泵,2617一出二) 38000/45000；LQ480T(100泵,2617一出四) 58000/65000\n新款全自动：LQ310T 3600/4500',
  updated_at = NOW()
WHERE id = 95;

-- ID=76: 利强自吸片膜封口机 → 台式气动片膜自吸款
UPDATE product_models SET
  reference_distributor_price = '6500',
  reference_retail_price = '8500',
  pricing_notes = '气动自吸片膜在常规片膜基础上+1500。\n参考台式气动片膜定价：LQ250P-TS(250以下) 5000/7000 → 自吸款 6500/8500。',
  updated_at = NOW()
WHERE id = 76;

-- ============================================================
-- Part 2: 新增气调机/贴体机/空压机/制氮机产品记录
-- 这些产品在 PDF 中有明确定价但数据库中缺失
-- ============================================================

-- 2.1 台式气调机系列 (TS = 台式)
INSERT INTO product_models (name, name_formula, model_code, equipment_category, product_line_name, product_line_code, machine_type, operation_mode, film_type, item_type, sale_status, reference_distributor_price, reference_retail_price, pricing_notes, output_count, sort_order, created_at, updated_at)
SELECT 'LQ180-4自动吸片膜机', '自动吸片膜机', 'LQ180-4', '封口机', '气调包装机', 'QD', NULL, '全自动', '片膜', '机器', '在售', '11000', '13800', NULL, NULL, 200, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM product_models WHERE name = 'LQ180-4自动吸片膜机');

INSERT INTO product_models (name, name_formula, model_code, equipment_category, product_line_name, product_line_code, machine_type, operation_mode, film_type, item_type, sale_status, reference_distributor_price, reference_retail_price, pricing_notes, output_count, sort_order, created_at, updated_at)
SELECT 'LQ260Q-TS台式气调机', '台式气调机-充气款', 'LQ260Q-TS', '气调机', '气调包装机', 'QD', NULL, '台式电动', '卷膜', '机器', '在售', '8000', '13800', '台式气调机系列：LQ260Q-TS(充气款,2216一出二,控制板) 8000/13800；LQ370Q-TS(2014一出四,控制板) 9000/14800；LQ450Q-TS(2216一出四,控制板) 10000/21000；LQ600Q-TS(3020一出四,控制板) 11000/24000', '2216一出二', 201, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM product_models WHERE name = 'LQ260Q-TS台式气调机');

INSERT INTO product_models (name, name_formula, model_code, equipment_category, product_line_name, product_line_code, machine_type, operation_mode, film_type, item_type, sale_status, reference_distributor_price, reference_retail_price, pricing_notes, output_count, sort_order, created_at, updated_at)
SELECT 'LQ370Q-TS台式气调机', '台式气调机', 'LQ370Q-TS', '气调机', '气调包装机', 'QD', NULL, '台式电动', '卷膜', '机器', '在售', '9000', '14800', '台式气调机系列：LQ260Q-TS(充气款,2216一出二,控制板) 8000/13800；LQ370Q-TS(2014一出四,控制板) 9000/14800；LQ450Q-TS(2216一出四,控制板) 10000/21000；LQ600Q-TS(3020一出四,控制板) 11000/24000', '2014一出四', 202, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM product_models WHERE name = 'LQ370Q-TS台式气调机');

INSERT INTO product_models (name, name_formula, model_code, equipment_category, product_line_name, product_line_code, machine_type, operation_mode, film_type, item_type, sale_status, reference_distributor_price, reference_retail_price, pricing_notes, output_count, sort_order, created_at, updated_at)
SELECT 'LQ450Q-TS台式气调机', '台式气调机', 'LQ450Q-TS', '气调机', '气调包装机', 'QD', 'LQ450', '台式电动', '卷膜', '机器', '在售', '10000', '21000', '台式气调机系列：LQ260Q-TS(充气款,2216一出二,控制板) 8000/13800；LQ370Q-TS(2014一出四,控制板) 9000/14800；LQ450Q-TS(2216一出四,控制板) 10000/21000；LQ600Q-TS(3020一出四,控制板) 11000/24000', '2216一出四', 203, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM product_models WHERE name = 'LQ450Q-TS台式气调机');

INSERT INTO product_models (name, name_formula, model_code, equipment_category, product_line_name, product_line_code, machine_type, operation_mode, film_type, item_type, sale_status, reference_distributor_price, reference_retail_price, pricing_notes, output_count, sort_order, created_at, updated_at)
SELECT 'LQ600Q-TS台式气调机', '台式气调机', 'LQ600Q-TS', '气调机', '气调包装机', 'QD', NULL, '台式电动', '卷膜', '机器', '在售', '11000', '24000', '台式气调机系列：LQ260Q-TS(充气款,2216一出二,控制板) 8000/13800；LQ370Q-TS(2014一出四,控制板) 9000/14800；LQ450Q-TS(2216一出四,控制板) 10000/21000；LQ600Q-TS(3020一出四,控制板) 11000/24000', '3020一出四', 204, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM product_models WHERE name = 'LQ600Q-TS台式气调机');

-- 2.2 落地式气调机系列 (充气款 + 真空充气)
INSERT INTO product_models (name, name_formula, model_code, equipment_category, product_line_name, product_line_code, machine_type, operation_mode, film_type, item_type, sale_status, reference_distributor_price, reference_retail_price, pricing_notes, output_count, sort_order, created_at, updated_at)
SELECT 'LQ310Q落地式气调机', '落地式气调机-充气款', 'LQ310Q', '气调机', '气调包装机', 'QD', NULL, '台式电动', '卷膜', '机器', '在售', '16000', '22000', '落地式气调机充气款：LQ260Q(2014一出二,控制板) 12000/17000；LQ310Q(2216一出二,控制板) 16000/22000；LQ370Q(2014一出四,控制板) 24000/35000；LQ450Q(3020一出四,PLC) 35000/40000', '2216一出二', 205, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM product_models WHERE name = 'LQ310Q落地式气调机');

INSERT INTO product_models (name, name_formula, model_code, equipment_category, product_line_name, product_line_code, machine_type, operation_mode, film_type, item_type, sale_status, reference_distributor_price, reference_retail_price, pricing_notes, output_count, sort_order, created_at, updated_at)
SELECT 'LQ370Q落地式气调机', '落地式气调机-充气款', 'LQ370Q', '气调机', '气调包装机', 'QD', NULL, '台式电动', '卷膜', '机器', '在售', '24000', '35000', '落地式气调机充气款：LQ260Q(2014一出二,控制板) 12000/17000；LQ310Q(2216一出二,控制板) 16000/22000；LQ370Q(2014一出四,控制板) 24000/35000；LQ450Q(3020一出四,PLC) 35000/40000', '2014一出四', 206, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM product_models WHERE name = 'LQ370Q落地式气调机');

INSERT INTO product_models (name, name_formula, model_code, equipment_category, product_line_name, product_line_code, machine_type, operation_mode, film_type, item_type, sale_status, reference_distributor_price, reference_retail_price, pricing_notes, output_count, sort_order, created_at, updated_at)
SELECT 'LQ450Q落地式气调机', '落地式气调机-充气款', 'LQ450Q', '气调机', '气调包装机', 'QD', 'LQ450', '台式电动', '卷膜', '机器', '在售', '35000', '40000', '落地式气调机充气款：LQ260Q(2014一出二,控制板) 12000/17000；LQ310Q(2216一出二,控制板) 16000/22000；LQ370Q(2014一出四,控制板) 24000/35000；LQ450Q(3020一出四,PLC) 35000/40000', '3020一出四', 207, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM product_models WHERE name = 'LQ450Q落地式气调机');

-- 真空充气系列
INSERT INTO product_models (name, name_formula, model_code, equipment_category, product_line_name, product_line_code, machine_type, operation_mode, film_type, item_type, sale_status, reference_distributor_price, reference_retail_price, pricing_notes, output_count, sort_order, created_at, updated_at)
SELECT 'LQ260Z落地式真空气调机', '落地式气调机-真空充气20泵', 'LQ260Z', '气调机', '气调包装机', 'QD', NULL, '台式电动', '卷膜', '机器', '在售', '15000', '19000', '落地式真空充气：LQ260Z(20泵,2014一出二,控制板) 15000/19000；LQ310Z(20泵,2216一出二,PLC) 18000/22000；LQ310Z(40泵,2216一出二,PLC) 21000/25000；LQ370Z(40泵,2014一出四,控制板) 32000/40000；LQ370Z(63泵,2014一出四,PLC) 34000/42000；LQ450Q(100泵,3020一出四,PLC) 40000/48000', '2014一出二', 208, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM product_models WHERE name = 'LQ260Z落地式真空气调机');

INSERT INTO product_models (name, name_formula, model_code, equipment_category, product_line_name, product_line_code, machine_type, operation_mode, film_type, item_type, sale_status, reference_distributor_price, reference_retail_price, pricing_notes, output_count, sort_order, created_at, updated_at)
SELECT 'LQ310Z落地式真空气调机', '落地式气调机-真空充气', 'LQ310Z', '气调机', '气调包装机', 'QD', NULL, '台式电动', '卷膜', '机器', '在售', '18000', '22000', '落地式真空充气：LQ260Z(20泵) 15000/19000；LQ310Z(20泵) 18000/22000；LQ310Z(40泵) 21000/25000；LQ370Z(40泵) 32000/40000；LQ370Z(63泵) 34000/42000；LQ450Q(100泵) 40000/48000', '2216一出二', 209, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM product_models WHERE name = 'LQ310Z落地式真空气调机');

INSERT INTO product_models (name, name_formula, model_code, equipment_category, product_line_name, product_line_code, machine_type, operation_mode, film_type, item_type, sale_status, reference_distributor_price, reference_retail_price, pricing_notes, output_count, sort_order, created_at, updated_at)
SELECT 'LQ370Z落地式真空气调机', '落地式气调机-真空充气', 'LQ370Z', '气调机', '气调包装机', 'QD', NULL, '台式电动', '卷膜', '机器', '在售', '32000', '40000', '落地式真空充气：LQ260Z(20泵) 15000/19000；LQ310Z(20泵) 18000/22000；LQ310Z(40泵) 21000/25000；LQ370Z(40泵) 32000/40000；LQ370Z(63泵) 34000/42000；LQ450Q(100泵) 40000/48000', '2014一出四', 210, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM product_models WHERE name = 'LQ370Z落地式真空气调机');

-- 2.3 连续式气调机系列
INSERT INTO product_models (name, name_formula, model_code, equipment_category, product_line_name, product_line_code, machine_type, operation_mode, film_type, item_type, sale_status, reference_distributor_price, reference_retail_price, pricing_notes, output_count, sort_order, created_at, updated_at)
SELECT 'LQ450L连续式气调机', '连续式气调机-普封', 'LQ450L', '气调机', '气调包装机', 'QD', NULL, '全自动', '卷膜', '机器', '在售', '35000', '52000', '连续式气调机LQ450系列：LQ450L(普封) 35000/52000；LQ450LQ(充气款) 42000/62000；LQ450LZ(真空充气63泵) 48000/68000\n选配加价：进盒3米输送带+18000；出盒四变一+5000；单排出盒1.5米+1500；在线喷码机+1500', '2617一出二', 211, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM product_models WHERE name = 'LQ450L连续式气调机');

INSERT INTO product_models (name, name_formula, model_code, equipment_category, product_line_name, product_line_code, machine_type, operation_mode, film_type, item_type, sale_status, reference_distributor_price, reference_retail_price, pricing_notes, output_count, sort_order, created_at, updated_at)
SELECT 'LQ450LQ连续式气调机', '连续式气调机-充气款', 'LQ450LQ', '气调机', '气调包装机', 'QD', NULL, '全自动', '卷膜', '机器', '在售', '42000', '62000', '连续式气调机LQ450系列：LQ450L(普封) 35000/52000；LQ450LQ(充气款) 42000/62000；LQ450LZ(真空充气63泵) 48000/68000\n选配加价：进盒3米输送带+18000；出盒四变一+5000；单排出盒1.5米+1500；在线喷码机+1500', '2617一出二', 212, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM product_models WHERE name = 'LQ450LQ连续式气调机');

INSERT INTO product_models (name, name_formula, model_code, equipment_category, product_line_name, product_line_code, machine_type, operation_mode, film_type, item_type, sale_status, reference_distributor_price, reference_retail_price, pricing_notes, output_count, sort_order, created_at, updated_at)
SELECT 'LQ450LZ连续式气调机', '连续式气调机-真空充气63泵', 'LQ450LZ', '气调机', '气调包装机', 'QD', NULL, '全自动', '卷膜', '机器', '在售', '48000', '68000', '连续式气调机LQ450系列：LQ450L(普封) 35000/52000；LQ450LQ(充气款) 42000/62000；LQ450LZ(真空充气63泵) 48000/68000\n选配加价：进盒3米输送带+18000；出盒四变一+5000；单排出盒1.5米+1500；在线喷码机+1500', '2617一出二', 213, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM product_models WHERE name = 'LQ450LZ连续式气调机');

INSERT INTO product_models (name, name_formula, model_code, equipment_category, product_line_name, product_line_code, machine_type, operation_mode, film_type, item_type, sale_status, reference_distributor_price, reference_retail_price, pricing_notes, output_count, sort_order, created_at, updated_at)
SELECT 'LQ750LQ连续式气调机', '连续式气调机-充气款', 'LQ750LQ', '气调机', '气调包装机', 'QD', 'LQ750L', '全自动', '卷膜', '机器', '在售', '68000', '98000', '连续式气调机LQ750系列：LQ750L(普封) 48000/68000；LQ750LQ(充气款) 68000/98000；LQ750LZ(真空充气100泵) 78000/108000', '2014一出四', 214, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM product_models WHERE name = 'LQ750LQ连续式气调机');

INSERT INTO product_models (name, name_formula, model_code, equipment_category, product_line_name, product_line_code, machine_type, operation_mode, film_type, item_type, sale_status, reference_distributor_price, reference_retail_price, pricing_notes, output_count, sort_order, created_at, updated_at)
SELECT 'LQ750LZ连续式气调机', '连续式气调机-真空充气100泵', 'LQ750LZ', '气调机', '气调包装机', 'QD', 'LQ750L', '全自动', '卷膜', '机器', '在售', '78000', '108000', '连续式气调机LQ750系列：LQ750L(普封) 48000/68000；LQ750LQ(充气款) 68000/98000；LQ750LZ(真空充气100泵) 78000/108000', '2014一出四', 215, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM product_models WHERE name = 'LQ750LZ连续式气调机');

INSERT INTO product_models (name, name_formula, model_code, equipment_category, product_line_name, product_line_code, machine_type, operation_mode, film_type, item_type, sale_status, reference_distributor_price, reference_retail_price, pricing_notes, output_count, sort_order, created_at, updated_at)
SELECT 'LQ380AQ连续式气调机', '连续式气调机-充气款', 'LQ380AQ', '气调机', '气调包装机', 'QD', 'LQ380A', '全自动', '卷膜', '机器', '在售', '78000', '108000', '连续式气调机LQ380系列：LQ380A(普通封口) 68000/88000；LQ380AQ(充气款) 78000/108000；LQ380AZ(真空充气100泵) 88000/118000\n选配加价：在线喷码机+1500', '2216一出四', 216, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM product_models WHERE name = 'LQ380AQ连续式气调机');

INSERT INTO product_models (name, name_formula, model_code, equipment_category, product_line_name, product_line_code, machine_type, operation_mode, film_type, item_type, sale_status, reference_distributor_price, reference_retail_price, pricing_notes, output_count, sort_order, created_at, updated_at)
SELECT 'LQ380AZ连续式气调机', '连续式气调机-真空充气100泵', 'LQ380AZ', '气调机', '气调包装机', 'QD', 'LQ380A', '全自动', '卷膜', '机器', '在售', '88000', '118000', '连续式气调机LQ380系列：LQ380A(普通封口) 68000/88000；LQ380AQ(充气款) 78000/108000；LQ380AZ(真空充气100泵) 88000/118000\n选配加价：在线喷码机+1500', '2216一出四', 217, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM product_models WHERE name = 'LQ380AZ连续式气调机');

-- 2.4 贴体机系列
INSERT INTO product_models (name, name_formula, model_code, equipment_category, product_line_name, product_line_code, machine_type, operation_mode, film_type, item_type, sale_status, reference_distributor_price, reference_retail_price, pricing_notes, output_count, sort_order, created_at, updated_at)
SELECT 'LQ280T台式贴体机', '台式贴体机-10泵', 'LQ280T', '贴体机', '贴体包装机', 'TT', NULL, '台式电动', '片膜', '机器', '在售', '6000', '9000', '贴体机定价表：台式LQ280T(10泵,270*180) 6000/9000；LQ350T(20泵,2617一出二) 8000/11000\n落地式LQ330T(63泵,2617一出二) 38000/45000；LQ480T(100泵,2617一出四) 58000/65000\n新款全自动LQ310T 3600/4500', '270*180', 218, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM product_models WHERE name = 'LQ280T台式贴体机');

INSERT INTO product_models (name, name_formula, model_code, equipment_category, product_line_name, product_line_code, machine_type, operation_mode, film_type, item_type, sale_status, reference_distributor_price, reference_retail_price, pricing_notes, output_count, sort_order, created_at, updated_at)
SELECT 'LQ350T台式贴体机', '台式贴体机-20泵', 'LQ350T', '贴体机', '贴体包装机', 'TT', NULL, '台式电动', '片膜', '机器', '在售', '8000', '11000', '贴体机定价表：台式LQ280T(10泵) 6000/9000；LQ350T(20泵) 8000/11000\n落地式LQ330T(63泵) 38000/45000；LQ480T(100泵) 58000/65000\n新款全自动LQ310T 3600/4500', '2617一出二', 219, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM product_models WHERE name = 'LQ350T台式贴体机');

INSERT INTO product_models (name, name_formula, model_code, equipment_category, product_line_name, product_line_code, machine_type, operation_mode, film_type, item_type, sale_status, reference_distributor_price, reference_retail_price, pricing_notes, output_count, sort_order, created_at, updated_at)
SELECT 'LQ480T落地式贴体机', '落地式贴体机-100泵', 'LQ480T', '贴体机', '贴体包装机', 'TT', NULL, '台式电动', '片膜', '机器', '在售', '58000', '65000', '贴体机定价表：台式LQ280T(10泵) 6000/9000；LQ350T(20泵) 8000/11000\n落地式LQ330T(63泵) 38000/45000；LQ480T(100泵) 58000/65000\n新款全自动LQ310T 3600/4500', '2617一出四', 220, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM product_models WHERE name = 'LQ480T落地式贴体机');

INSERT INTO product_models (name, name_formula, model_code, equipment_category, product_line_name, product_line_code, machine_type, operation_mode, film_type, item_type, sale_status, reference_distributor_price, reference_retail_price, pricing_notes, output_count, sort_order, created_at, updated_at)
SELECT 'LQ310T新款全自动贴体机', '新款全自动贴体机', 'LQ310T', '贴体机', '贴体包装机', 'TT', NULL, '全自动', '片膜', '机器', '在售', '3600', '4500', '贴体机定价表：台式LQ280T(10泵) 6000/9000；LQ350T(20泵) 8000/11000\n落地式LQ330T(63泵) 38000/45000；LQ480T(100泵) 58000/65000\n新款全自动LQ310T 3600/4500', NULL, 221, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM product_models WHERE name = 'LQ310T新款全自动贴体机');

-- 2.5 辅机：制氮机/空压机
INSERT INTO product_models (name, name_formula, model_code, equipment_category, product_line_name, product_line_code, machine_type, operation_mode, film_type, item_type, sale_status, reference_distributor_price, reference_retail_price, pricing_notes, sort_order, created_at, updated_at)
SELECT '制氮机3立方(加冷干机)', '制氮机-3立方', '3立方', '辅机', '制氮机', 'N2', NULL, NULL, NULL, '机器', '在售', '6500', '8000', '制氮机定价：3立方(加冷干机) 6500/8000；5立方 9000/10000', 222, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM product_models WHERE name = '制氮机3立方(加冷干机)');

INSERT INTO product_models (name, name_formula, model_code, equipment_category, product_line_name, product_line_code, machine_type, operation_mode, film_type, item_type, sale_status, reference_distributor_price, reference_retail_price, pricing_notes, sort_order, created_at, updated_at)
SELECT '制氮机5立方', '制氮机-5立方', '5立方', '辅机', '制氮机', 'N2', NULL, NULL, NULL, '机器', '在售', '9000', '10000', '制氮机定价：3立方(加冷干机) 6500/8000；5立方 9000/10000', 223, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM product_models WHERE name = '制氮机5立方');

INSERT INTO product_models (name, name_formula, model_code, equipment_category, product_line_name, product_line_code, machine_type, operation_mode, film_type, item_type, sale_status, reference_distributor_price, reference_retail_price, pricing_notes, sort_order, created_at, updated_at)
SELECT '无油静音空压机1.5kw', '无油静音空压机-1.5kw', '1.5kw', '辅机', '空压机', 'KYJ', NULL, NULL, NULL, '机器', '在售', '1100', '1300', '空压机定价：1.5kw 1100/1300；2.2kw 1500/1800；4.4kw 3000/3500；螺杆式7.5kw(加储气罐加冷干机) 13000/15000', 224, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM product_models WHERE name = '无油静音空压机1.5kw');

INSERT INTO product_models (name, name_formula, model_code, equipment_category, product_line_name, product_line_code, machine_type, operation_mode, film_type, item_type, sale_status, reference_distributor_price, reference_retail_price, pricing_notes, sort_order, created_at, updated_at)
SELECT '无油静音空压机2.2kw', '无油静音空压机-2.2kw', '2.2kw', '辅机', '空压机', 'KYJ', NULL, NULL, NULL, '机器', '在售', '1500', '1800', '空压机定价：1.5kw 1100/1300；2.2kw 1500/1800；4.4kw 3000/3500；螺杆式7.5kw 13000/15000', 225, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM product_models WHERE name = '无油静音空压机2.2kw');

INSERT INTO product_models (name, name_formula, model_code, equipment_category, product_line_name, product_line_code, machine_type, operation_mode, film_type, item_type, sale_status, reference_distributor_price, reference_retail_price, pricing_notes, sort_order, created_at, updated_at)
SELECT '无油静音空压机4.4kw', '无油静音空压机-4.4kw', '4.4kw', '辅机', '空压机', 'KYJ', NULL, NULL, NULL, '机器', '在售', '3000', '3500', '空压机定价：1.5kw 1100/1300；2.2kw 1500/1800；4.4kw 3000/3500；螺杆式7.5kw 13000/15000', 226, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM product_models WHERE name = '无油静音空压机4.4kw');

INSERT INTO product_models (name, name_formula, model_code, equipment_category, product_line_name, product_line_code, machine_type, operation_mode, film_type, item_type, sale_status, reference_distributor_price, reference_retail_price, pricing_notes, sort_order, created_at, updated_at)
SELECT '螺杆式空压机7.5kw', '螺杆式空压机-7.5kw(加储气罐加冷干机)', '7.5kw', '辅机', '空压机', 'KYJ', NULL, NULL, NULL, '机器', '在售', '13000', '15000', '空压机定价：1.5kw 1100/1300；2.2kw 1500/1800；4.4kw 3000/3500；螺杆式7.5kw(加储气罐加冷干机) 13000/15000', 227, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM product_models WHERE name = '螺杆式空压机7.5kw');

-- ============================================================
-- Part 3: 为有选配加价的产品设置 pricing_addons JSON
-- 格式匹配 PricingStructure DTO: {baseTiers, addons, appliesTo}
-- ============================================================

-- ID=92: LQ750L 连续式气调机 (普封) - 选配加价
UPDATE product_models SET
  pricing_addons = '{"baseTiers":[{"type":"distributor","label":"经销价","value":"48000","currency":"CNY"},{"type":"retail","label":"零售价","value":"68000","currency":"CNY"}],"addons":[{"category":"选配","name":"进盒3米输送带","price":"18000","note":""},{"category":"选配","name":"出盒四变一","price":"5000","note":""},{"category":"选配","name":"单排出盒1.5米","price":"1500","note":""},{"category":"选配","name":"在线喷码机","price":"1500","note":""}],"appliesTo":{"regions":[],"conditions":"","expiryDate":null}}',
  updated_at = NOW()
WHERE id = 92;

-- ID=170: LQ380A 连续式气调机 - 选配加价
UPDATE product_models SET
  pricing_addons = '{"baseTiers":[{"type":"distributor","label":"经销价","value":"68000","currency":"CNY"},{"type":"retail","label":"零售价","value":"88000","currency":"CNY"}],"addons":[{"category":"选配","name":"在线喷码机","price":"1500","note":""}],"appliesTo":{"regions":[],"conditions":"","expiryDate":null}}',
  updated_at = NOW()
WHERE id = 170;

-- ID=82: 台式气动卷膜封口机 - 加价规则
UPDATE product_models SET
  pricing_addons = '{"baseTiers":[{"type":"distributor","label":"经销价","value":"6000","currency":"CNY"},{"type":"retail","label":"零售价","value":"8000","currency":"CNY"}],"addons":[{"category":"加价规则","name":"卷膜250机型做一出四","price":"800","note":"在一出二基础上"},{"category":"底座","name":"不锈钢底座","price":"3000","note":""},{"category":"底座","name":"铝型材底座","price":"1500","note":""},{"category":"选配","name":"卷膜前后进出","price":"3000","note":""},{"category":"打码机","name":"一出二加打码机","price":"2500","note":""},{"category":"打码机","name":"一出四加打码","price":"3500","note":""},{"category":"打码机","name":"一出六加打码","price":"3700","note":""},{"category":"选配","name":"加充气流式","price":"1500","note":""}],"appliesTo":{"regions":[],"conditions":"","expiryDate":null}}',
  updated_at = NOW()
WHERE id = 82;

-- ID=83: 台式气动片膜封口机 - 加价规则
UPDATE product_models SET
  pricing_addons = '{"baseTiers":[{"type":"distributor","label":"经销价","value":"5000","currency":"CNY"},{"type":"retail","label":"零售价","value":"7000","currency":"CNY"}],"addons":[{"category":"加价规则","name":"气动自吸片膜","price":"1500","note":"在常规片膜基础上"},{"category":"底座","name":"不锈钢底座","price":"3000","note":""},{"category":"底座","name":"铝型材底座","price":"1500","note":""},{"category":"选配","name":"卷膜前后进出","price":"3000","note":""},{"category":"打码机","name":"一出二加打码机","price":"2500","note":""},{"category":"打码机","name":"一出四加打码","price":"3500","note":""},{"category":"打码机","name":"一出六加打码","price":"3700","note":""},{"category":"选配","name":"加充气流式","price":"1500","note":""}],"appliesTo":{"regions":[],"conditions":"","expiryDate":null}}',
  updated_at = NOW()
WHERE id = 83;

-- ID=66: 半自动卷膜封口机 - 铝盒加价规则
UPDATE product_models SET
  pricing_addons = '{"baseTiers":[{"type":"distributor","label":"经销价","value":"1350","currency":"CNY"},{"type":"retail","label":"零售价","value":"1800","currency":"CNY"}],"addons":[{"category":"外壳","name":"不锈钢外壳","price":"100","note":""},{"category":"功能","name":"自动卷膜","price":"300","note":""},{"category":"易撕口","name":"方形加易撕口","price":"100","note":""},{"category":"易撕口","name":"圆形加易撕口","price":"300","note":""},{"category":"一出","name":"一出二","price":"500","note":""},{"category":"一出","name":"一出四","price":"800","note":""},{"category":"非标","name":"不规则盒子","price":"200","note":""},{"category":"选配","name":"铝箔光电","price":"100","note":""},{"category":"选配","name":"色带打码机","price":"1500","note":""},{"category":"选配","name":"打孔","price":"50","note":""},{"category":"非标","name":"非标定制","price":"200","note":""}],"appliesTo":{"regions":[],"conditions":"","expiryDate":null}}',
  updated_at = NOW()
WHERE id = 66;

-- ID=68: 手推自动卷膜封口机 - 加价规则
UPDATE product_models SET
  pricing_addons = '{"baseTiers":[{"type":"distributor","label":"经销价","value":"2600","currency":"CNY"},{"type":"retail","label":"零售价","value":"3100","currency":"CNY"}],"addons":[{"category":"非标","name":"非标尺寸","price":"200","note":""},{"category":"选配","name":"铝箔光电","price":"100","note":""},{"category":"选配","name":"走时间","price":"50","note":""},{"category":"选配","name":"打孔","price":"50","note":""}],"appliesTo":{"regions":[],"conditions":"","expiryDate":null}}',
  updated_at = NOW()
WHERE id = 68;

-- ID=72: 按钮自动卷膜封口机 - 加价规则
UPDATE product_models SET
  pricing_addons = '{"baseTiers":[{"type":"distributor","label":"经销价","value":"3100","currency":"CNY"},{"type":"retail","label":"零售价","value":"3600","currency":"CNY"}],"addons":[{"category":"选配","name":"打孔","price":"50","note":""}],"appliesTo":{"regions":[],"conditions":"","expiryDate":null}}',
  updated_at = NOW()
WHERE id = 72;

-- ID=65: 手动片膜封口机 - 模具费用
UPDATE product_models SET
  pricing_addons = '{"baseTiers":[{"type":"distributor","label":"经销价","value":"650","currency":"CNY"},{"type":"retail","label":"零售价","value":"1200","currency":"CNY"}],"addons":[{"category":"模具","name":"370*270机型套模具","price":"150","note":"元/个"},{"category":"模具","name":"280机型250模具","price":"0","note":"免费"},{"category":"模具","name":"现有模型方圆模具","price":"100","note":"元/个"},{"category":"模具","name":"小模具","price":"50","note":"元/个"}],"appliesTo":{"regions":[],"conditions":"铝箔盒材质按口径范围内收费，其它材质按以前价格","expiryDate":null}}',
  updated_at = NOW()
WHERE id = 65;

-- ID=69: 手推自动片膜封口机 - 模具费用 + 按钮自动加价
UPDATE product_models SET
  pricing_addons = '{"baseTiers":[{"type":"distributor","label":"经销价","value":"1700","currency":"CNY"},{"type":"retail","label":"零售价","value":"2500","currency":"CNY"}],"addons":[{"category":"升级","name":"按钮自动片膜","price":"600","note":""},{"category":"模具","name":"370*270机型套模具","price":"150","note":"元/个"},{"category":"模具","name":"280机型250模具","price":"0","note":"免费"},{"category":"模具","name":"现有模型方圆模具","price":"100","note":"元/个"},{"category":"模具","name":"小模具","price":"50","note":"元/个"}],"appliesTo":{"regions":[],"conditions":"铝箔盒材质按口径范围内收费，其它材质按以前价格","expiryDate":null}}',
  updated_at = NOW()
WHERE id = 69;

-- ID=110: LQ260气调机 - 落地式气调机系列加价
UPDATE product_models SET
  pricing_addons = '{"baseTiers":[{"type":"distributor","label":"经销价","value":"12000","currency":"CNY"},{"type":"retail","label":"零售价","value":"17000","currency":"CNY"}],"addons":[],"appliesTo":{"regions":[],"conditions":"落地式充气款系列：LQ260Q 12000/17000；LQ310Q 16000/22000；LQ370Q 24000/35000；LQ450Q 35000/40000；真空充气LQ260Z 15000/19000；LQ310Z 18000/22000；LQ370Z 32000/40000","expiryDate":null}}',
  updated_at = NOW()
WHERE id = 110;

-- ============================================================
-- Part 4: 为新增产品设置 pricing_addons JSON (连续式气调机选配)
-- ============================================================

UPDATE product_models SET
  pricing_addons = '{"baseTiers":[{"type":"distributor","label":"经销价","value":"35000","currency":"CNY"},{"type":"retail","label":"零售价","value":"52000","currency":"CNY"}],"addons":[{"category":"选配","name":"进盒3米输送带","price":"18000","note":""},{"category":"选配","name":"出盒四变一","price":"5000","note":""},{"category":"选配","name":"单排出盒1.5米","price":"1500","note":""},{"category":"选配","name":"在线喷码机","price":"1500","note":""}],"appliesTo":{"regions":[],"conditions":"","expiryDate":null}}',
  updated_at = NOW()
WHERE name = 'LQ450L连续式气调机';

UPDATE product_models SET
  pricing_addons = '{"baseTiers":[{"type":"distributor","label":"经销价","value":"42000","currency":"CNY"},{"type":"retail","label":"零售价","value":"62000","currency":"CNY"}],"addons":[{"category":"选配","name":"进盒3米输送带","price":"18000","note":""},{"category":"选配","name":"出盒四变一","price":"5000","note":""},{"category":"选配","name":"单排出盒1.5米","price":"1500","note":""},{"category":"选配","name":"在线喷码机","price":"1500","note":""}],"appliesTo":{"regions":[],"conditions":"","expiryDate":null}}',
  updated_at = NOW()
WHERE name = 'LQ450LQ连续式气调机';

UPDATE product_models SET
  pricing_addons = '{"baseTiers":[{"type":"distributor","label":"经销价","value":"48000","currency":"CNY"},{"type":"retail","label":"零售价","value":"68000","currency":"CNY"}],"addons":[{"category":"选配","name":"进盒3米输送带","price":"18000","note":""},{"category":"选配","name":"出盒四变一","price":"5000","note":""},{"category":"选配","name":"单排出盒1.5米","price":"1500","note":""},{"category":"选配","name":"在线喷码机","price":"1500","note":""}],"appliesTo":{"regions":[],"conditions":"","expiryDate":null}}',
  updated_at = NOW()
WHERE name = 'LQ450LZ连续式气调机';

UPDATE product_models SET
  pricing_addons = '{"baseTiers":[{"type":"distributor","label":"经销价","value":"68000","currency":"CNY"},{"type":"retail","label":"零售价","value":"98000","currency":"CNY"}],"addons":[],"appliesTo":{"regions":[],"conditions":"LQ750系列：LQ750L 48000/68000；LQ750LQ 68000/98000；LQ750LZ 78000/108000","expiryDate":null}}',
  updated_at = NOW()
WHERE name = 'LQ750LQ连续式气调机';

UPDATE product_models SET
  pricing_addons = '{"baseTiers":[{"type":"distributor","label":"经销价","value":"78000","currency":"CNY"},{"type":"retail","label":"零售价","value":"108000","currency":"CNY"}],"addons":[],"appliesTo":{"regions":[],"conditions":"LQ750系列：LQ750L 48000/68000；LQ750LQ 68000/98000；LQ750LZ 78000/108000","expiryDate":null}}',
  updated_at = NOW()
WHERE name = 'LQ750LZ连续式气调机';

UPDATE product_models SET
  pricing_addons = '{"baseTiers":[{"type":"distributor","label":"经销价","value":"78000","currency":"CNY"},{"type":"retail","label":"零售价","value":"108000","currency":"CNY"}],"addons":[{"category":"选配","name":"在线喷码机","price":"1500","note":""}],"appliesTo":{"regions":[],"conditions":"LQ380系列：LQ380A 68000/88000；LQ380AQ 78000/108000；LQ380AZ 88000/118000","expiryDate":null}}',
  updated_at = NOW()
WHERE name = 'LQ380AQ连续式气调机';

UPDATE product_models SET
  pricing_addons = '{"baseTiers":[{"type":"distributor","label":"经销价","value":"88000","currency":"CNY"},{"type":"retail","label":"零售价","value":"118000","currency":"CNY"}],"addons":[{"category":"选配","name":"在线喷码机","price":"1500","note":""}],"appliesTo":{"regions":[],"conditions":"LQ380系列：LQ380A 68000/88000；LQ380AQ 78000/108000；LQ380AZ 88000/118000","expiryDate":null}}',
  updated_at = NOW()
WHERE name = 'LQ380AZ连续式气调机';

-- ============================================================
-- Part 5: 更新 LQ750L 已有产品的 pricing_addons
-- ============================================================
UPDATE product_models SET
  pricing_addons = '{"baseTiers":[{"type":"distributor","label":"经销价","value":"48000","currency":"CNY"},{"type":"retail","label":"零售价","value":"68000","currency":"CNY"}],"addons":[],"appliesTo":{"regions":[],"conditions":"LQ750系列：LQ750L 48000/68000；LQ750LQ 68000/98000；LQ750LZ 78000/108000","expiryDate":null}}',
  updated_at = NOW()
WHERE id = 92 AND pricing_addons IS NULL;

-- ============================================================
-- Part 6: 更新 LQ380A 已有产品的 pricing_addons (如果尚未设置)
-- ============================================================
UPDATE product_models SET
  pricing_addons = '{"baseTiers":[{"type":"distributor","label":"经销价","value":"68000","currency":"CNY"},{"type":"retail","label":"零售价","value":"88000","currency":"CNY"}],"addons":[{"category":"选配","name":"在线喷码机","price":"1500","note":""}],"appliesTo":{"regions":[],"conditions":"LQ380系列：LQ380A 68000/88000；LQ380AQ 78000/108000；LQ380AZ 88000/118000","expiryDate":null}}',
  updated_at = NOW()
WHERE id = 170 AND pricing_addons IS NULL;
