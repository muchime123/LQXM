-- ============================================================
-- V19: 修复 packaging_models.advantages 列类型
-- MySQL 9.7 中 TEXT 类型对应 LONGVARCHAR，而 JPA @Lob 期望 LONGTEXT (CLOB)
-- ============================================================

ALTER TABLE packaging_models
MODIFY COLUMN advantages LONGTEXT;