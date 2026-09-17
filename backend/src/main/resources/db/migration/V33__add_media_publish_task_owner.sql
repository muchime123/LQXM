ALTER TABLE media_publish_tasks
    ADD COLUMN publish_owner VARCHAR(100) NULL,
    ADD INDEX idx_publish_task_owner (publish_owner);

UPDATE media_publish_tasks
SET publish_owner = publisher_name
WHERE publish_owner IS NULL
  AND publisher_name IS NOT NULL;
