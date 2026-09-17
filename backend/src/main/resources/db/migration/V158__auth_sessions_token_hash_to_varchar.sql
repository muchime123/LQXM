-- 修复 auth_sessions.token_hash 列类型：CHAR(64) → VARCHAR(64)
-- V157 建表时用 CHAR(64)，但 JPA 实体 @Column(length=64) 默认映射为 VARCHAR(64)，
-- Hibernate schema 验证不通过，导致启动失败。改为 VARCHAR(64) 保持一致。
ALTER TABLE auth_sessions
    MODIFY COLUMN token_hash VARCHAR(64) NOT NULL;
