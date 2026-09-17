ALTER TABLE media_accounts
    ADD COLUMN publish_owner VARCHAR(100) NULL,
    ADD INDEX idx_media_publish_owner (publish_owner);
