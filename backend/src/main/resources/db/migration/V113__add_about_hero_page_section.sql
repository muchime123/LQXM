-- 关于我们页 Hero 纳入页面区块管理（可视化编辑器可编辑标题/描述/背景图/显示开关）。
-- 仅当不存在该区块时插入，避免覆盖运营人员已经维护的数据。
INSERT INTO website_page_sections (page_key, section_key, title, subtitle, sort_order, enabled)
SELECT 'ABOUT', 'hero', NULL, '了解利强的企业定位、制造能力、发展历程，以及公开展示的资质认证与知识产权成果。', 0, 1
WHERE NOT EXISTS (
    SELECT 1 FROM website_page_sections
    WHERE page_key = 'ABOUT' AND section_key = 'hero' AND deleted_at IS NULL
);
