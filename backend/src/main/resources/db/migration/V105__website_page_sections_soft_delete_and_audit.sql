-- 为 website_page_sections 增加软删除列，并加索引。
-- 与 website_banners 的 deleted_at 保持一致（DATETIME NULL），默认 NULL 表示未删除。
ALTER TABLE website_page_sections
    ADD COLUMN deleted_at DATETIME NULL COMMENT '软删除时间' AFTER updated_at;

-- 若未来列表需要过滤已删除记录，先补齐索引。
CREATE INDEX idx_wps_deleted_at ON website_page_sections (deleted_at);
CREATE INDEX idx_wps_page_deleted ON website_page_sections (page_key, deleted_at, sort_order);
