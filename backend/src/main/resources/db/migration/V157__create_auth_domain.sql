-- P7 正式身份认证域
-- 1) login_accounts：管理员本地登录账号（与员工 1:1，用于钉钉故障/开发维护/超管）
-- 2) auth_sessions：登录会话，不透明 Token（DB 只存 SHA-256 哈希），支持撤销与过期

CREATE TABLE login_accounts (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    employee_id BIGINT NOT NULL,
    username VARCHAR(50) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    enabled TINYINT(1) NOT NULL DEFAULT 1,
    last_login_at DATETIME NULL,
    created_by VARCHAR(100) NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_login_accounts_username (username),
    UNIQUE KEY uk_login_accounts_employee (employee_id),
    CONSTRAINT fk_login_accounts_employee FOREIGN KEY (employee_id) REFERENCES employees(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE auth_sessions (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    token_hash CHAR(64) NOT NULL,
    employee_id BIGINT NOT NULL,
    login_type VARCHAR(20) NOT NULL,
    external_user_id VARCHAR(100) NULL,
    ip VARCHAR(64) NULL,
    user_agent VARCHAR(500) NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_active_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at DATETIME NOT NULL,
    revoked_at DATETIME NULL,
    UNIQUE KEY uk_auth_sessions_token (token_hash),
    KEY idx_auth_sessions_employee (employee_id),
    KEY idx_auth_sessions_expires (expires_at),
    CONSTRAINT fk_auth_sessions_employee FOREIGN KEY (employee_id) REFERENCES employees(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
