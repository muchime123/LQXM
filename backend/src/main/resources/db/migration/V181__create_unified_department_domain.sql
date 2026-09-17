-- 统一组织第一阶段：内部部门主数据 + 外部映射 + 员工任职关系

CREATE TABLE departments (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  department_code VARCHAR(50) NULL,
  name VARCHAR(200) NOT NULL,
  parent_id BIGINT NULL,
  level_no INT NOT NULL DEFAULT 1,
  status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
  source_system VARCHAR(20) NOT NULL DEFAULT 'INTERNAL',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_departments_name(name),
  UNIQUE KEY uk_departments_code(department_code),
  KEY idx_departments_parent(parent_id),
  KEY idx_departments_status(status),
  CONSTRAINT fk_departments_parent FOREIGN KEY(parent_id) REFERENCES departments(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='统一部门主数据';

CREATE TABLE department_external_mappings (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  department_id BIGINT NOT NULL,
  source_system VARCHAR(20) NOT NULL,
  external_department_id VARCHAR(100) NOT NULL,
  external_name VARCHAR(200) NULL,
  mapped_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  mapped_by VARCHAR(100) NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_dem_source_external(source_system,external_department_id),
  KEY idx_dem_department(department_id),
  CONSTRAINT fk_dem_department FOREIGN KEY(department_id) REFERENCES departments(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='部门外部系统身份映射';

CREATE TABLE employee_department_assignments (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  employee_id BIGINT NOT NULL,
  department_id BIGINT NOT NULL,
  is_primary TINYINT(1) NOT NULL DEFAULT 1,
  effective_from DATE NULL,
  effective_to DATE NULL,
  assignment_status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
  source_system VARCHAR(20) NOT NULL DEFAULT 'MIGRATION',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_eda_employee_department(employee_id,department_id,effective_from),
  KEY idx_eda_employee_active(employee_id,assignment_status,is_primary),
  KEY idx_eda_department_active(department_id,assignment_status),
  CONSTRAINT fk_eda_employee FOREIGN KEY(employee_id) REFERENCES employees(id) ON DELETE CASCADE,
  CONSTRAINT fk_eda_department FOREIGN KEY(department_id) REFERENCES departments(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='员工部门任职历史';

ALTER TABLE employees ADD COLUMN department_id BIGINT NULL COMMENT '统一部门ID' AFTER department,
  ADD KEY idx_employees_department_id(department_id),
  ADD CONSTRAINT fk_employees_department FOREIGN KEY(department_id) REFERENCES departments(id) ON DELETE SET NULL;

ALTER TABLE expense_claims ADD COLUMN department_id BIGINT NULL COMMENT '费用发生时统一部门ID' AFTER submitter_dept_snapshot,
  ADD KEY idx_ec_department(department_id),
  ADD CONSTRAINT fk_ec_department FOREIGN KEY(department_id) REFERENCES departments(id) ON DELETE SET NULL;

-- 从员工与历史费用快照建立内部部门，名称是回填阶段的唯一匹配键。
INSERT IGNORE INTO departments(name,source_system)
SELECT DISTINCT TRIM(department),'INTERNAL' FROM employees WHERE NULLIF(TRIM(department),'') IS NOT NULL;
INSERT IGNORE INTO departments(name,source_system)
SELECT DISTINCT TRIM(COALESCE(NULLIF(department_name,''),submitter_dept_snapshot)),'DINGTALK'
  FROM expense_claims
 WHERE NULLIF(TRIM(COALESCE(NULLIF(department_name,''),submitter_dept_snapshot)),'') IS NOT NULL;

UPDATE employees e JOIN departments d ON d.name=TRIM(e.department)
   SET e.department_id=d.id WHERE e.department_id IS NULL;
UPDATE expense_claims c JOIN departments d
  ON d.name=TRIM(COALESCE(NULLIF(c.department_name,''),c.submitter_dept_snapshot))
   SET c.department_id=d.id WHERE c.department_id IS NULL;

INSERT IGNORE INTO employee_department_assignments
  (employee_id,department_id,is_primary,effective_from,assignment_status,source_system)
SELECT id,department_id,1,entry_date,'ACTIVE','MIGRATION'
  FROM employees WHERE department_id IS NOT NULL;

-- 暂无可靠钉钉部门 ID 时用 NAME# 哈希作为过渡外部键；后续通讯录同步会绑定真实 deptId。
INSERT IGNORE INTO department_external_mappings
  (department_id,source_system,external_department_id,external_name,mapped_by)
SELECT d.id,'DINGTALK',CONCAT('NAME#',MD5(d.name)),d.name,'V181历史费用快照'
  FROM departments d WHERE d.source_system='DINGTALK';
