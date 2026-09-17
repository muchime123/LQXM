-- V167：调整生产↔销售出库匹配评分权重
-- 原权重下 BASE+SAME_CUSTOMER_ID+SAME_SALESPERSON 已触顶 95，导致产品/数量/时间维度无区分度。
-- 新权重让产品一致性、数量对齐、时间接近真正影响排序，同客户但产品未核对的候选分数显著降低。
UPDATE h3_match_feature SET score = 35, label = '基础分（同客户名 + 180 天窗口）' WHERE feature_key = 'BASE';
UPDATE h3_match_feature SET score = 15, label = '客户档案 ID 等值' WHERE feature_key = 'SAME_CUSTOMER_ID';
UPDATE h3_match_feature SET score = 10, label = '归属业务员等值' WHERE feature_key = 'SAME_SALESPERSON';
UPDATE h3_match_feature SET score = 25, label = '产品名归一化互含' WHERE feature_key = 'PRODUCT_ALIGN';
UPDATE h3_match_feature SET score = 20, label = '数量对齐（误差<=5%）' WHERE feature_key = 'QUANTITY_ALIGN';
UPDATE h3_match_feature SET score = 10, label = '时间接近（30 天内）' WHERE feature_key = 'TIME_PROXIMITY';
UPDATE h3_match_feature SET score = 10, label = '客户产品匹配记忆提权' WHERE feature_key = 'CUSTOMER_HINT';
