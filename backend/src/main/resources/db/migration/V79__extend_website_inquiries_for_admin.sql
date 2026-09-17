-- V79: 扩展 website_inquiries 表，支持后台询盘管理
-- 新增字段：负责人、跟进记录、下次联系时间、关闭原因、更新时间
-- 这些字段支持询盘从"只能写入"升级为"可处理、可跟进、可关闭"的完整流程

ALTER TABLE website_inquiries
    ADD COLUMN assignee VARCHAR(100) COMMENT '负责人（后台员工）',
    ADD COLUMN follow_up_note TEXT COMMENT '跟进记录',
    ADD COLUMN next_contact_at DATETIME COMMENT '下次联系时间',
    ADD COLUMN closed_reason VARCHAR(500) COMMENT '关闭原因（status=closed 时填写）',
    ADD COLUMN updated_at DATETIME COMMENT '最后更新时间';

-- 为状态查询加索引（后台按状态筛选是高频操作）
CREATE INDEX idx_inquiries_status ON website_inquiries (status, created_at DESC);
