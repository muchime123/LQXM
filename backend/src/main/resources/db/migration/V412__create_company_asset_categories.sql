-- 品牌资产子分类管理表
-- 品牌资产文件存储从平铺 company/ 改为按子分类分目录 company/<code>/
-- 子分类由本表管理，可增删改，不再硬编码在代码中

CREATE TABLE IF NOT EXISTS company_asset_categories (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    code        VARCHAR(50) NOT NULL UNIQUE COMMENT '分类英文 slug，如 brand_logo',
    name        VARCHAR(100) NOT NULL COMMENT '分类显示名，如 品牌标识',
    sort_order  INT NOT NULL DEFAULT 0 COMMENT '排序权重，越小越靠前',
    icon        VARCHAR(100) NULL COMMENT '可选图标标识',
    deleted_at  DATETIME NULL COMMENT '软删除时间戳',
    created_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='品牌资产子分类';

-- 预置 10 个分类（与现有 company_assets.category 值对齐）
INSERT INTO company_asset_categories (code, name, sort_order) VALUES
    ('brand_logo',       '品牌标识',   10),
    ('seal_nameplate',   '印章与名称', 20),
    ('factory_scene',    '工厂实景',   30),
    ('team_photo',       '团队照片',   40),
    ('office_env',       '办公环境',   50),
    ('product_showcase', '产品展示',   60),
    ('website_media',    '官网图片',   70),
    ('info_graphic',     '信息图表',   80),
    ('certification',    '资质证书',   90),
    ('promotion',        '宣传资料',   100);
