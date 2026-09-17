-- V121: product_models 重新加回 shop_url（店铺链接）字段
-- ========================================================================================
-- 背景：
--   V108 在产品 V2 切换时删除了 product_models.shop_url 列，前端 ProductDetailPanel 的
--   「店铺链接」跳转按钮也被同步移除。V118 又删除了 V106 的备份表 product_models_v2_backup，
--   原 shop_url 数据已无法恢复。本脚本重新加回列，由用户在产品编辑器侧栏「官网发布」面板
--   填写后保存生效。
--
-- 范围：
--   ALTER TABLE product_models ADD COLUMN shop_url VARCHAR(500) NULL
--   - 长度 500 与 V1 时代一致，足以存放淘宝/天猫/京东/1688/Shopify 等电商长链接
--   - 可空，不破坏现有产品行
--   - 不加唯一约束（一个产品可对应一个店铺详情页）
--
-- 风险：低。单列 ADD COLUMN，MySQL 8.0+ INPLACE，瞬时报元数据锁，无数据变更。
-- ========================================================================================

ALTER TABLE product_models
    ADD COLUMN shop_url VARCHAR(500) NULL AFTER status;
