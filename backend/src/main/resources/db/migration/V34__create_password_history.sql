CREATE TABLE IF NOT EXISTS password_history (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    entry_id BIGINT NOT NULL,
    platform VARCHAR(200),
    url VARCHAR(500),
    account VARCHAR(300),
    password_encrypted TEXT,
    notes VARCHAR(500),
    sort_order INT NOT NULL DEFAULT 0,
    change_type VARCHAR(20) NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);

DROP PROCEDURE IF EXISTS create_index_if_not_exists;
CREATE PROCEDURE create_index_if_not_exists(IN p_table VARCHAR(200), IN p_index VARCHAR(200), IN p_columns VARCHAR(500))
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.statistics
        WHERE table_schema = DATABASE()
        AND table_name = p_table
        AND index_name = p_index
    ) THEN
        SET @sql = CONCAT('CREATE INDEX ', p_index, ' ON ', p_table, '(', p_columns, ')');
        PREPARE stmt FROM @sql;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
    END IF;
END;

CALL create_index_if_not_exists('password_history', 'idx_history_entry_id', 'entry_id');
CALL create_index_if_not_exists('password_history', 'idx_history_created_at', 'created_at');

DROP PROCEDURE IF EXISTS create_index_if_not_exists;
