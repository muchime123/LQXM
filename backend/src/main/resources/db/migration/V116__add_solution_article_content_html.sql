-- V116: website_solutions 增加富文本正文字段
-- 方案文章正文由"结构化小节"演进为富文本 HTML（h2/h3 标题自动生成侧边目录）。
-- content_sections 保留以兼容旧数据：content_html 为空时阅读端回退渲染旧结构。
ALTER TABLE website_solutions
    ADD COLUMN content_html TEXT NULL COMMENT '富文本正文 HTML（h2/h3 自动生成目录）' AFTER content_sections;
