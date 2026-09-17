-- ============================================================
-- 数据修复：历史遗留业务员建档 + 111 张历史出库单归属回填
-- 日期：2026-09-01
-- 背景：出库单 salesperson_name 为李燕燕/陈晓琴/余育强/段燕梅 的 111 张
--       CONFIRMED 出库单（2022-05 ~ 2023-07，销售额 77.3 万）employee_id 为 NULL，
--       其中李燕燕已建档（id=156）但无映射未回填；其余 3 人从未建档。
-- 方案：走系统既定机制 —— 员工档案 + H3YUN 姓名映射（NAME# 占位标识），
--       并镜像 H3EmployeeIdentityService.backfillUnassigned 的回填逻辑。
-- 说明：销售业绩归属到人，历史单不在提成结算期间（仅 2026-08），不影响提成。
-- ============================================================

START TRANSACTION;

-- 1. 李燕燕（id=156）补工号 + 标记业务员（remark 追加来源说明；LQ107 已被李兵占用，用 LQ108）
UPDATE employees
SET employee_no    = 'LQ108',
    is_salesperson = 1,
    remark         = CONCAT(IFNULL(remark, ''), '；[2026-09-01] 补工号并标记业务员（74张历史出库单归属，SQL_DATA_FIX）'),
    updated_by     = 'SQL_DATA_FIX_20260901'
WHERE id = 156 AND name = '李燕燕';

-- 2. 建档 3 名历史业务员（2022-2023 年有业务、此后无业务，按历史员工建档）
INSERT INTO employees (employee_no, name, status, is_salesperson, remark, created_by, updated_by)
VALUES
('LQ109', '陈晓琴', 'RESIGNED', 1, '历史业务员：由出库单姓名补建档（2022-2023年业务 5 单），用于历史销售业绩归属；[2026-09-01] SQL_DATA_FIX', 'SQL_DATA_FIX_20260901', 'SQL_DATA_FIX_20260901'),
('LQ110', '余育强', 'RESIGNED', 1, '历史业务员：由出库单姓名补建档（2022-2023年业务 23 单），用于历史销售业绩归属；[2026-09-01] SQL_DATA_FIX', 'SQL_DATA_FIX_20260901', 'SQL_DATA_FIX_20260901'),
('LQ111', '段燕梅', 'RESIGNED', 1, '历史业务员：由出库单姓名补建档（2022-2023年业务 9 单），用于历史销售业绩归属；[2026-09-01] SQL_DATA_FIX', 'SQL_DATA_FIX_20260901', 'SQL_DATA_FIX_20260901');

-- 3. 添加 H3YUN 姓名映射（external_user_id 用 NAME# 占位，沿用部门过渡映射约定；
--    出库单仅有 salesperson_name，无真实氚云 UserId）
INSERT INTO employee_external_mappings (employee_id, source_system, external_user_id, external_name, is_primary, mapped_by, remark)
SELECT e.id, 'H3YUN', CONCAT('NAME#', e.name), e.name, 0, 'SQL_DATA_FIX_20260901',
       '历史遗留占位映射：出库单仅有姓名无氚云UserId，待真实氚云通讯录导入后升级'
FROM employees e
WHERE e.name IN ('李燕燕', '陈晓琴', '余育强', '段燕梅')
  AND NOT EXISTS (SELECT 1 FROM employee_external_mappings m
                  WHERE m.employee_id = e.id AND m.source_system = 'H3YUN');

-- 4. 回填出库单归属（镜像 H3EmployeeIdentityService.backfillUnassigned：
--    H3YUN 映射 external_name 唯一 → 回填未绑定且未结算提成的出库单）
UPDATE sales_outbounds o
JOIN (
    SELECT TRIM(external_name) AS source_name, MIN(employee_id) AS employee_id
    FROM employee_external_mappings
    WHERE source_system = 'H3YUN'
      AND external_name IS NOT NULL AND TRIM(external_name) <> ''
    GROUP BY TRIM(external_name)
    HAVING COUNT(DISTINCT employee_id) = 1
) m ON TRIM(o.salesperson_name) COLLATE utf8mb4_unicode_ci = m.source_name
SET o.employee_id = m.employee_id, o.updated_at = NOW()
WHERE o.external_source = 'H3YUN' AND o.employee_id IS NULL
  AND NOT EXISTS (SELECT 1 FROM sales_outbound_commissions c
                  WHERE c.sales_outbound_id = o.id AND c.status = 'SETTLED');

COMMIT;

-- ============================================================
-- 验证（修复后）
-- ============================================================
SELECT '-- 修复后员工档案' AS step;
SELECT id, employee_no, name, department, status, is_salesperson, remark
FROM employees
WHERE name IN ('李燕燕', '陈晓琴', '余育强', '段燕梅');

SELECT '-- 修复后映射' AS step;
SELECT employee_id, source_system, external_user_id, external_name, is_primary
FROM employee_external_mappings
WHERE external_name IN ('李燕燕', '陈晓琴', '余育强', '段燕梅');

SELECT '-- 修复后仍未归属的确认出库单' AS step;
SELECT COUNT(*) AS still_unassigned
FROM sales_outbounds
WHERE outbound_status = 'CONFIRMED' AND employee_id IS NULL;

SELECT '-- 修复后4人归属的单据数' AS step;
SELECT o.salesperson_name, e.employee_no, COUNT(*) cnt, SUM(o.sales_amount) sales
FROM sales_outbounds o
LEFT JOIN employees e ON e.id = o.employee_id
WHERE o.salesperson_name IN ('李燕燕', '陈晓琴', '余育强', '段燕梅')
GROUP BY o.salesperson_name, e.employee_no;
