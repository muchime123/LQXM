CREATE TABLE IF NOT EXISTS media_publish_tasks (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    task_name VARCHAR(200) NOT NULL,
    publish_date DATE NOT NULL,
    platform VARCHAR(50),
    publish_software VARCHAR(100),
    content_title VARCHAR(200),
    content_topic VARCHAR(200),
    content_url VARCHAR(500),
    editor_name VARCHAR(50),
    publisher_name VARCHAR(50),
    note VARCHAR(500),
    account_count INT,
    created_update_count INT,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_publish_task_date (publish_date),
    INDEX idx_publish_task_platform (platform)
);

ALTER TABLE media_account_updates
    ADD COLUMN publish_task_id BIGINT NULL,
    ADD INDEX idx_update_publish_task (publish_task_id);
