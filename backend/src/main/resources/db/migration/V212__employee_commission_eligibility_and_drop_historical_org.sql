-- ================================================================================
-- 员工提成资格与历史身份三分设计（docs/design/commission-eligibility-and-history-identity-design.md）
--  1. employees：employee_kind（NATURAL/HISTORICAL）+ commission_eligibility（UNKNOWN/PARTICIPATE/EXCLUDE）
--  2. sales_outbound_commissions：订单级提成资格例外 eligibility_*（优先级：订单例外 > 员工默认）
--  3. salesperson_identity_candidates：历史身份映射依据与处理日志
--  4. 历史组织概念移除（V211 引入，仅有数据、无生效逻辑）：
--     建档（幂等）→ 归属迁移 → 断言 → 清理列/表 → 写映射依据
--  5. 权限码 employee_commission.manage（ADMIN / BOSS / FINANCE）
-- 原则：幂等；不修改已执行迁移；已结算提成记录不受影响。
-- ================================================================================

-- ---------- 1) 员工提成资格字段 ----------
ALTER TABLE employees
    ADD COLUMN employee_kind VARCHAR(20) NOT NULL DEFAULT 'NATURAL'
        COMMENT 'NATURAL 自然人员工 / HISTORICAL 历史档案（历史员工、代用身份）' AFTER status,
    ADD COLUMN commission_eligibility VARCHAR(20) NOT NULL DEFAULT 'UNKNOWN'
        COMMENT 'UNKNOWN 待确认 / PARTICIPATE 参与计提 / EXCLUDE 不参与计提' AFTER employee_kind,
    ADD COLUMN commission_eligibility_reason VARCHAR(500) NULL
        COMMENT '提成资格说明；EXCLUDE 必填原因；UNKNOWN 可填待办说明' AFTER commission_eligibility,
    ADD KEY idx_employees_eligibility (commission_eligibility);

-- 存量在职员工资格统一 UNKNOWN（保守口径，由老板在员工名册按员工/批量确认，不预设参与或排除）

-- ---------- 2) 订单级提成资格例外 ----------
ALTER TABLE sales_outbound_commissions
    ADD COLUMN eligibility_override VARCHAR(20) NULL
        COMMENT 'NULL 继承员工设置 / PARTICIPATE 此单参与 / EXCLUDE 此单不参与' AFTER status,
    ADD COLUMN eligibility_override_reason VARCHAR(500) NULL
        COMMENT '订单级例外原因（必填）' AFTER eligibility_override,
    ADD COLUMN eligibility_status VARCHAR(20) NOT NULL DEFAULT 'UNKNOWN'
        COMMENT '生效资格（含继承结果）：UNKNOWN / PARTICIPATE / EXCLUDE' AFTER eligibility_override_reason,
    ADD COLUMN eligibility_source VARCHAR(20) NOT NULL DEFAULT 'EMPLOYEE_DEFAULT'
        COMMENT 'EMPLOYEE_DEFAULT 员工默认 / ORDER_OVERRIDE 订单例外' AFTER eligibility_status,
    ADD KEY idx_soc_eligibility (eligibility_status);

-- 存量提成行 eligibility_status=UNKNOWN（资格维度尚未生效，不影响现有计算）

-- ---------- 3) 历史身份映射依据与处理日志 ----------
CREATE TABLE IF NOT EXISTS salesperson_identity_candidates (
    id              BIGINT          PRIMARY KEY AUTO_INCREMENT,
    source_system   VARCHAR(20)     NOT NULL COMMENT '来源系统：H3YUN / DINGTALK',
    source_name     VARCHAR(100)    NOT NULL COMMENT '来源姓名（原样保留）',
    employee_id     BIGINT          NULL COMMENT '确认绑定的员工（employees.id）',
    order_count     INT             NOT NULL DEFAULT 0 COMMENT '候选订单数',
    min_ship_date   DATE            NULL COMMENT '候选订单最早出库日期',
    max_ship_date   DATE            NULL COMMENT '候选订单最晚出库日期',
    total_amount    DECIMAL(14,2)   NOT NULL DEFAULT 0 COMMENT '候选订单销售额合计',
    status          VARCHAR(20)     NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING 待处理 / CONFIRMED 已确认 / CONFLICT 冲突 / SKIPPED 跳过',
    mapping_basis   VARCHAR(500)    NULL COMMENT '映射依据（人工填写，审计）',
    resolved_by     VARCHAR(100)    NULL COMMENT '处理人',
    resolved_at     DATETIME        NULL COMMENT '处理时间',
    created_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_sic_source_name (source_system, source_name),
    KEY idx_sic_employee (employee_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='历史身份映射依据与处理日志';

-- ---------- 4) 历史组织概念移除 ----------
-- 4.1 建档：梅奥德行政、李燕燕（历史员工，资格待确认；不创建登录账号、不编造缺失信息；幂等）
INSERT INTO employees (name, status, employee_kind, commission_eligibility, remark)
SELECT '梅奥德行政', 'RESIGNED', 'HISTORICAL', 'UNKNOWN',
       '历史子公司业务代用身份，非自然人员工；历史组织概念移除迁移建档'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM employees WHERE name = '梅奥德行政' COLLATE utf8mb4_unicode_ci);

INSERT INTO employees (name, status, employee_kind, commission_eligibility, remark)
SELECT '李燕燕', 'RESIGNED', 'HISTORICAL', 'UNKNOWN',
       '历史员工，其他资料待补充；历史组织概念移除迁移建档'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM employees WHERE name = '李燕燕' COLLATE utf8mb4_unicode_ci);

-- 4.2 归属迁移：梅奥德行政历史组织订单回填员工（现状 643 张全部命中）
UPDATE sales_outbounds o
JOIN employees e ON e.name = '梅奥德行政' COLLATE utf8mb4_unicode_ci
SET o.employee_id = e.id
WHERE o.historical_organization_id = 1 AND o.employee_id IS NULL;

-- 4.3 断言：历史组织订单均已绑定员工（归属迁移无遗漏）、无 EXCLUDED/已结算提成行依赖（不满足则中止迁移）
DROP PROCEDURE IF EXISTS tmp_v212_assert_no_historical_org_deps;
DELIMITER //
CREATE PROCEDURE tmp_v212_assert_no_historical_org_deps()
BEGIN
    DECLARE v_unbound INT;
    DECLARE v_excluded_rows INT;
    DECLARE v_settled_rows INT;
    SELECT COUNT(*) INTO v_unbound FROM sales_outbounds
      WHERE historical_organization_id IS NOT NULL AND employee_id IS NULL;
    SELECT COUNT(*) INTO v_excluded_rows FROM sales_outbound_commissions WHERE status = 'EXCLUDED';
    SELECT COUNT(*) INTO v_settled_rows
      FROM sales_outbound_commissions c
      JOIN sales_outbounds o ON o.id = c.sales_outbound_id
     WHERE c.status = 'SETTLED' AND o.historical_organization_id IS NOT NULL;
    IF v_unbound > 0 OR v_excluded_rows > 0 OR v_settled_rows > 0 THEN
        SIGNAL SQLSTATE '45000'
          SET MESSAGE_TEXT = 'V212 assertion failed: historical org orders unbound or EXCLUDED/SETTLED dependency exists';
    END IF;
END //
DELIMITER ;
CALL tmp_v212_assert_no_historical_org_deps();
DROP PROCEDURE IF EXISTS tmp_v212_assert_no_historical_org_deps;

-- 4.4 清理历史组织列与表
ALTER TABLE sales_outbounds DROP FOREIGN KEY fk_outbound_historical_org;
ALTER TABLE sales_outbounds DROP INDEX idx_outbound_historical_org;
ALTER TABLE sales_outbounds DROP COLUMN historical_organization_id;
DROP TABLE IF EXISTS historical_sales_organizations;

-- 4.5 写映射依据（审计留痕）
INSERT INTO salesperson_identity_candidates
    (source_system, source_name, employee_id, order_count, min_ship_date, max_ship_date,
     total_amount, status, mapping_basis, resolved_by, resolved_at)
SELECT 'H3YUN', e.name, e.id,
       (SELECT COUNT(*) FROM sales_outbounds o WHERE o.employee_id = e.id AND o.external_source = 'H3YUN'),
       (SELECT MIN(o.ship_date) FROM sales_outbounds o WHERE o.employee_id = e.id AND o.external_source = 'H3YUN'),
       (SELECT MAX(o.ship_date) FROM sales_outbounds o WHERE o.employee_id = e.id AND o.external_source = 'H3YUN'),
       (SELECT COALESCE(SUM(o.sales_amount),0) FROM sales_outbounds o WHERE o.employee_id = e.id AND o.external_source = 'H3YUN'),
       'CONFIRMED', '历史组织概念移除迁移（2026-08）', 'V212_migration', NOW()
FROM employees e WHERE e.name = '梅奥德行政' COLLATE utf8mb4_unicode_ci;

INSERT INTO salesperson_identity_candidates
    (source_system, source_name, employee_id, order_count, min_ship_date, max_ship_date,
     total_amount, status, mapping_basis, resolved_by, resolved_at)
SELECT 'H3YUN', e.name, e.id,
       (SELECT COUNT(*) FROM sales_outbounds o
         WHERE o.external_source = 'H3YUN' AND o.employee_id IS NULL AND TRIM(o.salesperson_name) COLLATE utf8mb4_unicode_ci = e.name),
       (SELECT MIN(o.ship_date) FROM sales_outbounds o
         WHERE o.external_source = 'H3YUN' AND o.employee_id IS NULL AND TRIM(o.salesperson_name) COLLATE utf8mb4_unicode_ci = e.name),
       (SELECT MAX(o.ship_date) FROM sales_outbounds o
         WHERE o.external_source = 'H3YUN' AND o.employee_id IS NULL AND TRIM(o.salesperson_name) COLLATE utf8mb4_unicode_ci = e.name),
       (SELECT COALESCE(SUM(o.sales_amount),0) FROM sales_outbounds o
         WHERE o.external_source = 'H3YUN' AND o.employee_id IS NULL AND TRIM(o.salesperson_name) COLLATE utf8mb4_unicode_ci = e.name),
       'CONFIRMED', '历史员工建档映射（2026-08）', 'V212_migration', NOW()
FROM employees e WHERE e.name = '李燕燕' COLLATE utf8mb4_unicode_ci;

-- ---------- 5) 权限码与角色分配 ----------
INSERT IGNORE INTO system_permissions(permission_code, permission_name) VALUES
 ('employee_commission.manage', '管理员工提成资格');

INSERT IGNORE INTO system_role_permissions
SELECT r.id, p.id FROM system_roles r CROSS JOIN system_permissions p
 WHERE r.role_code IN ('ADMIN','BOSS') AND p.permission_code = 'employee_commission.manage';
INSERT IGNORE INTO system_role_permissions
SELECT r.id, p.id FROM system_roles r CROSS JOIN system_permissions p
 WHERE r.role_code = 'FINANCE' AND p.permission_code = 'employee_commission.manage';
