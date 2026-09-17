-- 员工手机号账号：首次登录/管理员重置后强制修改初始密码。
ALTER TABLE login_accounts
    ADD COLUMN must_change_password TINYINT(1) NOT NULL DEFAULT 0 AFTER enabled,
    ADD COLUMN password_changed_at DATETIME NULL AFTER must_change_password;

-- 既有账号视为已经完成密码设置，避免上线后强制所有老账号改密。
UPDATE login_accounts SET must_change_password = 0 WHERE must_change_password = 0;
