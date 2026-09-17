-- P5.1 统一员工体系：employees 主表 + employee_external_mappings 多系统身份映射
-- 设计依据：ChatGPT 总体设计 §六（员工体系是所有模块地基）+ AGENTS.md 命名规范
-- 核心约束：
--   1. employees 是内部统一身份表（替代 company_team_members 作为业务身份源；company_team_members 保留官网展示用）
--   2. employee_external_mappings 一对多：同一员工可同时绑定氚云 / 钉钉 / 系统登录账号
--   3. 从 company_team_members 初始化花名册：幂等（按 name + phone 判重），员工编号 LQ001..按 sort_order
--   4. 自动标记 is_salesperson：姓名出现在 production_orders / sales_outbounds / receipts 任一 fact 表 salesperson_name 中的，默认 1
--   5. 所有 CREATE / INSERT 均 IF NOT EXISTS / NOT EXISTS 防御，Flyway 重跑安全

-- ============================================================
-- 1. 员工主表
-- ============================================================
CREATE TABLE IF NOT EXISTS employees (
    id                  BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '内部员工 ID',
    employee_no         VARCHAR(20) NULL COMMENT '工号，如 LQ001；空时可由 UI 后续分配',
    name                VARCHAR(100) NOT NULL COMMENT '员工姓名',
    gender              VARCHAR(10) NULL COMMENT 'MALE / FEMALE / OTHER / NULL',
    department          VARCHAR(100) NULL COMMENT '部门：办公室 / 半自动气调机 / 全自动气调机 / 加工中心 / 雕刻 / 封口机',
    position            VARCHAR(200) NULL COMMENT '岗位 / 职位',
    phone               VARCHAR(20) NULL COMMENT '手机号',
    email               VARCHAR(100) NULL COMMENT '邮箱',
    entry_date          DATE NULL COMMENT '入职日期',
    leave_date          DATE NULL COMMENT '离职日期',
    status              VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' COMMENT '状态：ACTIVE 在职 / RESIGNED 离职 / ON_LEAVE 停薪留职',
    is_salesperson      TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否业务员（影响提成归属、业务员维度行级权限）',
    remark              VARCHAR(500) NULL,
    created_by          VARCHAR(100) NULL COMMENT '创建人',
    updated_by          VARCHAR(100) NULL COMMENT '最后更新人',
    created_at          DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_employees_name_phone (name, phone),
    UNIQUE KEY uk_employees_employee_no (employee_no),
    KEY idx_employees_department (department),
    KEY idx_employees_status (status),
    KEY idx_employees_is_salesperson (is_salesperson),
    KEY idx_employees_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='统一员工主表（业务身份源）';


-- ============================================================
-- 2. 多系统身份映射（一对多：同一员工可绑定 H3YUN / DINGTALK / SYSTEM 等多个外部账号）
-- ============================================================
CREATE TABLE IF NOT EXISTS employee_external_mappings (
    id                  BIGINT PRIMARY KEY AUTO_INCREMENT,
    employee_id         BIGINT NOT NULL COMMENT '关联 employees.id',
    source_system       VARCHAR(20) NOT NULL COMMENT '来源系统：H3YUN 氚云 / DINGTALK 钉钉 / SYSTEM 系统登录账号',
    external_user_id    VARCHAR(100) NOT NULL COMMENT '外部用户标识：H3YUN=OwnerId / DINGTALK=userId / SYSTEM=login username',
    external_name       VARCHAR(100) NULL COMMENT '外部显示名（可能与本名不同，兜底匹配用）',
    external_department VARCHAR(100) NULL COMMENT '外部系统中的部门名',
    is_primary          TINYINT(1) NOT NULL DEFAULT 0 COMMENT '每个 source_system 下是否主映射（一般 1 个员工每个系统只有一个）',
    mapped_at           DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '映射建立时间',
    mapped_by           VARCHAR(100) NULL COMMENT '映射操作人',
    remark              VARCHAR(500) NULL,
    created_at          DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_eem_employee FOREIGN KEY (employee_id) REFERENCES employees(id) ON DELETE CASCADE,
    UNIQUE KEY uk_eem_emp_source_external (employee_id, source_system, external_user_id),
    UNIQUE KEY uk_eem_source_external (source_system, external_user_id),
    KEY idx_eem_employee (employee_id),
    KEY idx_eem_source (source_system),
    KEY idx_eem_external_name (external_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='员工多系统身份映射（氚云/钉钉/系统登录账号）';


-- ============================================================
-- 3. 从 company_team_members 幂等初始化 employees
--    - 主键策略：name + phone 唯一（同名同姓无手机的允许重复？V54 里每人都有 phone，所以安全）
--    - employee_no = 'LQ' + LPAD(sort_order, 3, '0')
--    - status 全部 ACTIVE（离职名单后续手工维护）
--    - is_salesperson 先 0，下一步再根据 fact 表回填
-- ============================================================
INSERT INTO employees (employee_no, name, department, position, phone, status, is_salesperson, created_by)
SELECT
    CONCAT('LQ', LPAD(src.sort_order, 3, '0')) AS employee_no,
    src.name,
    src.department,
    src.position,
    src.phone,
    'ACTIVE'                                                               AS status,
    0                                                                      AS is_salesperson,
    'V136_INIT_FROM_COMPANY_TEAM_MEMBERS'                                  AS created_by
FROM (
    SELECT name, phone, department, position, sort_order
    FROM company_team_members
    WHERE name IS NOT NULL AND name != ''
      AND phone IS NOT NULL AND phone != ''
) src
WHERE NOT EXISTS (
    SELECT 1 FROM employees e
    WHERE e.name = src.name AND e.phone = src.phone
);
-- 补：company_team_members 中没有 phone 的极少数行（若有）按 name 单字段兜底
INSERT INTO employees (name, department, position, phone, status, is_salesperson, created_by)
SELECT src.name, src.department, src.position, src.phone, 'ACTIVE', 0,
       'V136_INIT_FROM_COMPANY_TEAM_MEMBERS_NO_PHONE'
FROM (
    SELECT name, phone, department, position, sort_order
    FROM company_team_members
    WHERE name IS NOT NULL AND name != ''
      AND (phone IS NULL OR phone = '')
) src
WHERE NOT EXISTS (
    SELECT 1 FROM employees e WHERE e.name = src.name
)
  AND NOT EXISTS (
    SELECT 1 FROM employees e WHERE e.name = src.name AND IFNULL(e.phone, '') = IFNULL(src.phone, '')
);


-- ============================================================
-- 4. 自动标记业务员：姓名出现在 3 张事实表 salesperson_name 列中的员工，is_salesperson 置 1
--    采用 IN (...) 多表 UNION DISTINCT 策略避免 JOIN 索引复杂
--    注：production_orders/sales_outbounds/receipts 表用 utf8mb4_0900_ai_ci，
--        employees 用 utf8mb4_unicode_ci（与 company_team_members 一致），
--        跨表比较时用 COLLATE 统一为 utf8mb4_unicode_ci，避免 ERROR 1267
-- ============================================================
UPDATE employees e
SET e.is_salesperson = 1,
    e.updated_by     = 'V136_AUTO_MARK_SALESPERSON_BY_FACT_TABLES'
WHERE e.status = 'ACTIVE'
  AND e.is_salesperson = 0
  AND e.name COLLATE utf8mb4_unicode_ci IN (
      SELECT DISTINCT salesperson_name COLLATE utf8mb4_unicode_ci FROM (
          SELECT salesperson_name FROM production_orders WHERE salesperson_name IS NOT NULL AND salesperson_name != ''
          UNION
          SELECT salesperson_name FROM sales_outbounds   WHERE salesperson_name IS NOT NULL AND salesperson_name != ''
          UNION
          SELECT salesperson_name FROM receipts         WHERE salesperson_name IS NOT NULL AND salesperson_name != ''
      ) all_salespersons
  );
