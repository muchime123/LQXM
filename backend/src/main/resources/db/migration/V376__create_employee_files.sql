-- V376: 员工档案文件表（身份证复印件、劳动合同、学历证明等）
-- 接入文件资产框架，OwnerType = EMPLOYEE
CREATE TABLE employee_files (
    id                  BIGINT PRIMARY KEY AUTO_INCREMENT,
    employee_id         BIGINT NOT NULL,
    category            VARCHAR(50) NOT NULL,
    file_name           VARCHAR(255) NOT NULL,
    original_file_name  VARCHAR(255),
    mime_type           VARCHAR(100),
    file_size           BIGINT,
    tags                VARCHAR(500),
    sort_order          INT DEFAULT 0,
    default_asset       BOOLEAN DEFAULT FALSE,
    deleted_at          DATETIME NULL,
    created_at          DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at          DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_employee_files_employee FOREIGN KEY (employee_id)
        REFERENCES employees(id) ON DELETE CASCADE,
    INDEX idx_employee_files_emp_cat (employee_id, category),
    INDEX idx_employee_files_sort (employee_id, sort_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
