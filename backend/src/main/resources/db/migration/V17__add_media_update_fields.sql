ALTER TABLE media_account_updates
    ADD COLUMN update_type VARCHAR(20) COMMENT '更新类型：发视频、发图文、发笔记、直播',
    ADD COLUMN title VARCHAR(200) COMMENT '更新标题',
    ADD COLUMN content_url VARCHAR(500) COMMENT '内容链接',
    ADD COLUMN note VARCHAR(500) COMMENT '备注',
    ADD COLUMN operator VARCHAR(50) COMMENT '操作人';

CREATE INDEX idx_update_type ON media_account_updates(update_type);