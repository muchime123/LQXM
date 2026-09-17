-- V98: 为 "利强手压片膜封口机（斜头）" 初始化 330/390/500 机型档位及其能力范围
-- 背景：根据业务需求，该产品支持三种机型尺寸，每种机型对应不同的封口能力范围。
-- 此脚本初始化这三个机型的数据，作为后续规格（ProductVariant）绑定的基础。

-- 1. 首先获取目标产品的 ID
SET @product_model_id = (
    SELECT id FROM product_models 
    WHERE name = '利强手压片膜封口机（斜头）' 
    LIMIT 1
);

-- 如果找到了产品，则插入机型数据
INSERT INTO product_machine_tiers (model_id, tier_code, tier_label, 
    round_diameter_min, round_diameter_max, 
    rect_length_min, rect_length_max, rect_width_min, rect_width_max,
    sort_order, remark)
SELECT 
    @product_model_id,
    tier.code,
    tier.label,
    tier.round_min, tier.round_max,
    tier.rect_len_min, tier.rect_len_max, tier.rect_wid_min, tier.rect_wid_max,
    tier.sort_order,
    tier.remark
FROM (
    -- 330 机型：圆形 ≤200mm；方形 ≤221mm(L) × ≤168mm(W)
    SELECT 
        '330' AS code, 
        '330机型' AS label, 
        0.00 AS round_min, 200.00 AS round_max,
        0.00 AS rect_len_min, 221.00 AS rect_len_max, 
        0.00 AS rect_wid_min, 168.00 AS rect_wid_max,
        1 AS sort_order,
        '标准机型，适合小型封口需求' AS remark
    UNION ALL
    -- 390 机型：圆形 200mm+；方形 221mm+(L) × 168mm+(W)，常规到 370mm
    SELECT 
        '390' AS code, 
        '390机型' AS label, 
        200.00 AS round_min, 370.00 AS round_max,
        221.00 AS rect_len_min, 370.00 AS rect_len_max, 
        168.00 AS rect_wid_min, 270.00 AS rect_wid_max,
        2 AS sort_order,
        '中型机型，标准规格上限为 370x270' AS remark
    UNION ALL
    -- 500 机型：圆形最大 410mm；方形最大 410mm(L) × 410mm(W)，支持定做
    SELECT 
        '500' AS code, 
        '500机型' AS label, 
        200.00 AS round_min, 410.00 AS round_max,
        280.00 AS rect_len_min, 410.00 AS rect_len_max, 
        280.00 AS rect_wid_min, 410.00 AS rect_wid_max,
        3 AS sort_order,
        '大型机型，支持定制规格（范围：280-410mm）' AS remark
) AS tier
WHERE @product_model_id IS NOT NULL
  AND NOT EXISTS (
      SELECT 1 FROM product_machine_tiers 
      WHERE model_id = @product_model_id AND tier_code = tier.code
  );
