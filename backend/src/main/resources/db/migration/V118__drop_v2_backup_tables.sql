-- V118: 清理 V106 创建的 V2 迁移备份表
-- V106 备份了 product_models 和 product_variants 的 V1 待删字段
-- V108/V109 已完成 DROP COLUMN，业务代码已全面切换到 V2，备份表不再需要

DROP TABLE IF EXISTS product_models_v2_backup;
DROP TABLE IF EXISTS product_variants_v2_backup;