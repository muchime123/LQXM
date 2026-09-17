-- V177 员工统一兜底桥：按姓名补齐 employee_id 覆盖
-- 背景：V176 仅按 salesperson_id（company_team_members.id）桥接；团队成员表未覆盖的业务员
--      （员工花名册存在但团队表无）留下 employee_id 缺口（探查：sales_outbounds 5711 / receipts 996 / business_orders 402）。
-- 处理：按姓名直接匹配 employees（utf8mb4_unicode_ci；事实表 salesperson_name 为 utf8mb4_0900_ai_ci，需 COLLATE 对齐），
--      姓名取 ACTIVE 中 id 最小员工（已探查 employees 无重名，确定性有保证）。
-- 幂等：仅更新 employee_id IS NULL 的行；可安全重复执行。
-- 注：无法归因的孤儿姓名（employees 中不存在）保持 NULL，避免错误归因。

UPDATE sales_outbounds so
JOIN employees e
  ON e.name = so.salesperson_name COLLATE utf8mb4_unicode_ci
 AND e.id = (SELECT MIN(e2.id) FROM employees e2 WHERE e2.name = e.name AND e2.status = 'ACTIVE')
SET so.employee_id = e.id
WHERE so.employee_id IS NULL
  AND so.salesperson_name IS NOT NULL AND so.salesperson_name <> '';

UPDATE receipts r
JOIN employees e
  ON e.name = r.salesperson_name COLLATE utf8mb4_unicode_ci
 AND e.id = (SELECT MIN(e2.id) FROM employees e2 WHERE e2.name = e.name AND e2.status = 'ACTIVE')
SET r.employee_id = e.id
WHERE r.employee_id IS NULL
  AND r.salesperson_name IS NOT NULL AND r.salesperson_name <> '';

UPDATE production_orders po
JOIN employees e
  ON e.name = po.salesperson_name COLLATE utf8mb4_unicode_ci
 AND e.id = (SELECT MIN(e2.id) FROM employees e2 WHERE e2.name = e.name AND e2.status = 'ACTIVE')
SET po.employee_id = e.id
WHERE po.employee_id IS NULL
  AND po.salesperson_name IS NOT NULL AND po.salesperson_name <> '';

UPDATE business_orders bo
JOIN employees e
  ON e.name = bo.salesperson_name_snapshot COLLATE utf8mb4_unicode_ci
 AND e.id = (SELECT MIN(e2.id) FROM employees e2 WHERE e2.name = e.name AND e2.status = 'ACTIVE')
SET bo.employee_id = e.id
WHERE bo.employee_id IS NULL
  AND bo.salesperson_name_snapshot IS NOT NULL AND bo.salesperson_name_snapshot <> '';

-- 团队成员表兜底（V176 已回填，幂等保护）
UPDATE company_team_members m
JOIN employees e ON e.name = m.name COLLATE utf8mb4_unicode_ci
SET m.employee_id = e.id
WHERE m.employee_id IS NULL;
