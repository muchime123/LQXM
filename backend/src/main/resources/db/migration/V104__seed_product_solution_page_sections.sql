-- 产品中心与解决方案页面纳入统一页面区块管理。
INSERT INTO website_page_sections (page_key, section_key, title, subtitle, sort_order, enabled) VALUES
('PRODUCTS', 'hero', '查看包装设备，找到适合生产需求的产品', '按设备类别或产品名称查找包装设备，进入产品详情查看适用范围与具体信息。', 1, 1),
('PRODUCTS', 'catalog', '全部包装设备', '可按设备类型、包装产品、容器、工艺与目标产能筛选。', 2, 1),
('SOLUTIONS', 'hero', '从生产场景出发，找到可借鉴的包装方案', '按产品与生产场景进入，查看问题诊断、工艺判断与设备选型参考。', 1, 1),
('SOLUTIONS', 'scenes', '按生产场景查找', '先选择与您接近的产品和生产环境，再查看该场景下的工艺判断与实用内容。这里不是另一份设备目录。', 2, 1),
('SOLUTIONS', 'articles', '实用方案内容', '围绕生产问题、工艺判断与设备选型整理的方案文章。', 3, 1)
ON DUPLICATE KEY UPDATE page_key = VALUES(page_key);
