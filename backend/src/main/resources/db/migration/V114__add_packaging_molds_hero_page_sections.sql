-- 包装耗材与模具中心页纳入统一页面区块管理（可视化编辑器可编辑标题/描述/背景图/显示开关）。
-- 仅当不存在该区块时插入，避免覆盖运营人员已经维护的数据。
INSERT INTO website_page_sections (page_key, section_key, title, subtitle, sort_order, enabled)
SELECT 'PACKAGING', 'hero', NULL, '从托盒与膜材的实际组合出发，围绕密封、展示、储运和生产节拍选择包装耗材。', 1, 1
WHERE NOT EXISTS (
    SELECT 1 FROM website_page_sections
    WHERE page_key = 'PACKAGING' AND section_key = 'hero' AND deleted_at IS NULL
);

INSERT INTO website_page_sections (page_key, section_key, title, subtitle, sort_order, enabled)
SELECT 'MOLDS', 'hero', NULL, '模具连接设备、容器与膜材。我们根据盒型、设备和封切要求完成标准配置或定制开发。', 1, 1
WHERE NOT EXISTS (
    SELECT 1 FROM website_page_sections
    WHERE page_key = 'MOLDS' AND section_key = 'hero' AND deleted_at IS NULL
);
