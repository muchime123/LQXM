-- 审计动作使用稳定的业务动作码。部分关联同步动作超过旧的 30 字符限制，
-- 审计写入失败会回滚实际业务事务，因此统一为动作码预留足够空间。
ALTER TABLE audit_logs
    MODIFY COLUMN action VARCHAR(64) NOT NULL;
