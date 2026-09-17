-- 本地账号统一使用员工手机号登录，不再维护首次登录强制改密状态。
ALTER TABLE login_accounts
    DROP COLUMN must_change_password,
    DROP COLUMN password_changed_at;
