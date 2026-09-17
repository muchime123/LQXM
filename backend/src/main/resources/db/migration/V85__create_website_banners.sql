-- 官网Banner管理表
-- 用于管理首页轮播图、公告条等展示内容

CREATE TABLE website_banners (
    id              BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    position        VARCHAR(30)  NOT NULL COMMENT '展示位置: HERO(首页主图) / SIDE(侧边) / ANNOUNCEMENT(公告)',
    title           VARCHAR(200) NOT NULL COMMENT '主标题',
    subtitle        VARCHAR(200) NULL     COMMENT '副标题',
    description     TEXT         NULL     COMMENT '描述文本',
    image_url       VARCHAR(500) NULL     COMMENT '图片URL',
    image_asset_id  BIGINT       NULL     COMMENT '关联文件资产ID',
    button_text     VARCHAR(100) NULL     COMMENT '按钮文本',
    button_url      VARCHAR(500) NULL     COMMENT '按钮链接',
    secondary_button_text VARCHAR(100) NULL COMMENT '次要按钮文本',
    secondary_button_url VARCHAR(500) NULL COMMENT '次要按钮链接',
    badge           VARCHAR(100) NULL     COMMENT '标签文字(如: NEW / HOT)',
    sort_order      INT          NOT NULL DEFAULT 0 COMMENT '排序(升序)',
    website_enabled TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '是否在官网展示',
    start_date      DATE         NULL     COMMENT '生效开始日期',
    end_date        DATE         NULL     COMMENT '生效结束日期',
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME     NULL     NULL ON UPDATE CURRENT_TIMESTAMP,
    deleted_at      DATETIME     NULL     NULL COMMENT '软删除时间',
    INDEX idx_wb_position (position, website_enabled, sort_order),
    INDEX idx_wb_dates (start_date, end_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='官网Banner管理';

-- 初始化首页Hero Banner数据
INSERT INTO website_banners (position, title, subtitle, description, image_url, button_text, button_url, badge, sort_order, website_enabled) VALUES
('HERO', '让每一份产品锁住新鲜，高效抵达', 'ONE-STOP PACKAGING PARTNER', '从设备、模具到包装耗材，利强为食品与消费品企业提供可落地、可定制、可持续运行的包装解决方案。', '/images/hero-machine.png', '探索产品', '/products', '高效气调保鲜', 1, 1);

-- 初始化服务行业公告
INSERT INTO website_banners (position, title, description, sort_order, website_enabled) VALUES
('ANNOUNCEMENT', '服务多元食品场景', '餐饮连锁 · 预制菜 · 生鲜肉类 · 烘焙食品 · 航空餐 · 日化包装', 1, 1);
