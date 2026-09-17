-- 员工组织主数据收口：公司主体与工作部门分离。
-- 氚云的“总公司--子公司”路径只表示公司层级，不再作为工作部门名称。

CREATE TABLE organization_companies (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  company_code VARCHAR(50) NULL,
  name VARCHAR(200) NOT NULL,
  parent_id BIGINT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_organization_companies_name(name),
  UNIQUE KEY uk_organization_companies_code(company_code),
  KEY idx_organization_companies_parent(parent_id),
  CONSTRAINT fk_organization_companies_parent FOREIGN KEY(parent_id)
    REFERENCES organization_companies(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='公司主体主数据（总公司/子公司）';

INSERT INTO organization_companies(company_code,name,parent_id)
VALUES ('LQ','浙江利强包装科技有限公司',NULL);

INSERT INTO organization_companies(company_code,name,parent_id)
SELECT src.company_code,src.name,root_company.id
FROM (
  SELECT 'HDDS' company_code,'安徽盒多多塑业有限公司' name UNION ALL
  SELECT 'WZQZ','温州强子科技有限公司' UNION ALL
  SELECT 'WZSC','温州数创企业管理顾问有限公司' UNION ALL
  SELECT 'WZYM','温州永铭智能设备有限公司' UNION ALL
  SELECT 'ZJYQ','浙江艺强机械有限公司'
) src
JOIN organization_companies root_company ON root_company.company_code='LQ';

ALTER TABLE departments
  ADD COLUMN company_id BIGINT NULL COMMENT '所属公司；车间归属未确认时允许为空' AFTER parent_id,
  ADD COLUMN ownership_status VARCHAR(20) NOT NULL DEFAULT 'UNMAPPED'
    COMMENT 'CONFIRMED/UNMAPPED/CONFLICT' AFTER company_id,
  ADD COLUMN unit_type VARCHAR(30) NOT NULL DEFAULT 'DEPARTMENT'
    COMMENT 'DEPARTMENT/WORKSHOP/LEGACY_ORGANIZATION' AFTER ownership_status,
  ADD KEY idx_departments_company(company_id),
  ADD KEY idx_departments_ownership(ownership_status),
  ADD CONSTRAINT fk_departments_company FOREIGN KEY(company_id)
    REFERENCES organization_companies(id) ON DELETE SET NULL;

ALTER TABLE employees
  ADD COLUMN company_id BIGINT NULL COMMENT '员工所属公司主体' AFTER department_id,
  ADD KEY idx_employees_company_id(company_id),
  ADD CONSTRAINT fk_employees_company FOREIGN KEY(company_id)
    REFERENCES organization_companies(id) ON DELETE SET NULL;

-- 旧数据中以公司名或氚云完整路径建出的“部门”保留用于历史引用，但标记为遗留组织节点。
UPDATE departments d
SET d.unit_type='LEGACY_ORGANIZATION',
    d.company_id=(SELECT c.id FROM organization_companies c
                  WHERE c.name=SUBSTRING_INDEX(d.name,'--',-1) COLLATE utf8mb4_0900_ai_ci LIMIT 1),
    d.ownership_status='CONFIRMED'
WHERE d.name LIKE '%--%'
   OR EXISTS(SELECT 1 FROM organization_companies c WHERE c.name=d.name COLLATE utf8mb4_0900_ai_ci);

-- 员工名单中的真实工作部门。暂不猜测车间属于哪家子公司。
INSERT IGNORE INTO departments(name,level_no,status,source_system,ownership_status,unit_type)
VALUES
  ('办公室',1,'ACTIVE','EMPLOYEE_ROSTER','UNMAPPED','DEPARTMENT'),
  ('利强生产车间',1,'ACTIVE','DINGTALK','UNMAPPED','WORKSHOP'),
  ('半自动气调机',1,'ACTIVE','EMPLOYEE_ROSTER','UNMAPPED','WORKSHOP'),
  ('全自动气调机',1,'ACTIVE','EMPLOYEE_ROSTER','UNMAPPED','WORKSHOP'),
  ('加工中心',1,'ACTIVE','EMPLOYEE_ROSTER','UNMAPPED','WORKSHOP'),
  ('雕刻',1,'ACTIVE','EMPLOYEE_ROSTER','UNMAPPED','WORKSHOP'),
  ('封口机',1,'ACTIVE','EMPLOYEE_ROSTER','UNMAPPED','WORKSHOP');

UPDATE departments
SET unit_type=CASE WHEN name='办公室' THEN 'DEPARTMENT' ELSE 'WORKSHOP' END,
    ownership_status=CASE WHEN company_id IS NULL THEN 'UNMAPPED' ELSE 'CONFIRMED' END
WHERE name IN ('办公室','利强生产车间','半自动气调机','全自动气调机','加工中心','雕刻','封口机');

-- 已有员工若部门字符串正好是公司名/氚云公司路径，只回填所属公司，不再把它解释为工作部门。
UPDATE employees e
JOIN organization_companies c ON c.name=SUBSTRING_INDEX(e.department,'--',-1) COLLATE utf8mb4_0900_ai_ci
SET e.company_id=c.id,
    e.department_id=NULL,
    e.department=NULL
WHERE e.department LIKE '%--%'
   OR e.department=c.name COLLATE utf8mb4_0900_ai_ci;

ALTER TABLE employee_external_mappings
  ADD COLUMN external_status VARCHAR(50) NULL COMMENT '源系统账号/员工状态，不等同内部在职状态' AFTER external_department,
  ADD COLUMN external_position VARCHAR(200) NULL COMMENT '源系统职位' AFTER external_status,
  ADD COLUMN external_roles VARCHAR(500) NULL COMMENT '氚云应用角色，不作为内部职位或权限' AFTER external_position,
  ADD COLUMN last_seen_at DATETIME NULL COMMENT '最近一次在源快照中出现时间' AFTER external_roles;
