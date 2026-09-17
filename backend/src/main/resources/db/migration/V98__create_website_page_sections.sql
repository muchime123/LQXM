-- 官网页面区块内容表。
-- 将官网首页/关于我们等页面的标题、副标题、描述等展示文案纳入后台管理，
-- 替代前端硬编码的展示内容。一个 (page_key, section_key) 对应一个展示区块。
CREATE TABLE website_page_sections (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    page_key VARCHAR(50) NOT NULL COMMENT '页面标识：HOME/ABOUT/CONTACT',
    section_key VARCHAR(100) NOT NULL COMMENT '区块标识（页面内唯一）',
    title VARCHAR(300) NULL COMMENT '区块标题',
    subtitle VARCHAR(1000) NULL COMMENT '副标题/区块描述',
    content TEXT NULL COMMENT '结构化内容（JSON，供列表型/复合型区块）',
    image_url VARCHAR(500) NULL COMMENT '区块主图地址',
    sort_order INT NOT NULL DEFAULT 0 COMMENT '排序',
    enabled TINYINT(1) NOT NULL DEFAULT 1 COMMENT '是否在官网展示',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_page_section (page_key, section_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='官网页面区块内容';

-- 种子数据：把当前官网首页/关于我们页硬编码的区块文案搬入后台，
-- 迁移后可由后台「官网运营 · 页面内容」维护。
INSERT INTO website_page_sections (page_key, section_key, title, subtitle, sort_order, enabled) VALUES
('HOME', 'strength',    '扎根制造现场，提供设备、模具与耗材配套', NULL, 1, 1),
('HOME', 'products',    '核心设备', '从实际产品出发，选择更适合您的包装方式与生产节奏。', 2, 1),
('HOME', 'showcase',    '设备与模具，由我们自己制造', '从设备装配、模具配套到整机测试，我们在自己的生产现场完成关键制造与交付环节。', 3, 1),
('HOME', 'process',     '合作流程', '从需求确认到交付支持，四个环节依次推进。', 4, 1),
('HOME', 'industries',  '按应用场景选择方案', '不同产品对保鲜、产能与封装方式有不同要求，从实际场景开始选择。', 5, 1),
('HOME', 'cases',       '典型配置参考', '以下为常见应用条件下的配置思路，具体设备、模具与工艺参数需结合样品评估。', 6, 1),
('HOME', 'service',     '服务与支持', '涵盖试封、调试、培训、维护等环节，支持设备长期稳定运行。', 7, 1),
('ABOUT', 'intro',      '从包装设备制造出发，把模具与耗材一起考虑', NULL, 1, 1),
('ABOUT', 'manufacturing', '厂房设备', '通过生产、装配与调试现场，展示设备从制造到交付验证的主要环节。', 2, 1),
('ABOUT', 'culture',    '企业文化', '以客户实际需求为起点，以稳定制造、持续改进和长期服务作为合作基础。', 3, 1),
('ABOUT', 'credentials', '企业荣誉与资质认证', NULL, 4, 1),
('ABOUT', 'patents',    '专利与知识产权', NULL, 5, 1),
('ABOUT', 'contact',    '联系利强', '您可以直接电话、邮件或通过官方账号联系我们；方案和样品需求请使用全站咨询弹窗提交。', 6, 1);