-- 给自媒体账号增加「官网官方展示」标记。
-- 用于 Contact 页第四个卡片与 Footer 社交图标，从现有自媒体账号管理中挑选标记为官方的账号对外展示。
-- 避免在 CompanyInfo 中重复维护社交链接字段。
ALTER TABLE media_accounts ADD COLUMN website_official BOOLEAN DEFAULT FALSE;
CREATE INDEX idx_media_website_official ON media_accounts(website_official);
