-- 车间生产人员只报工：新建「车间报工」角色并绑定生产类岗位员工。
--
-- 依据（2026-09-23 LQ 拍板）：
--   ① 报工由车间生产人员提交 → 本迁移建角色并绑定；
--   ② 冲正与「完工检验并入库」由跟单（未来可含质检）提交 → 见 V334；
--   ③ 车间人员一并不能冲正：本角色不授予 manufacturing.report.override / manufacturing.inspect。
--
-- ⚠️ 为什么数据范围必须是 ALL，而不是 SELF/DEPARTMENT：
--   manufacturing_orders 的可见性由 ManufacturingService.requireVisible 决定，它按
--   「工单业务员本人（m.salesperson_id）/ 业务员所在部门（e.department）」过滤
--   （DataScopeSupport.appendByEmployeeId，featureCode=MANUFACTURING）。
--   车间工人既不是业务员、所属部门（封口机 / 全自动气调机 / 半自动气调机 / 雕刻）也与
--   业务员部门（办公室）不重叠 → SELF 与 DEPARTMENT 下任务详情一律 0 行，返回
--   「生产任务不存在或无权查看」，报工弹窗根本打不开。
--   车间执行看板（executionBoard）本身不做范围过滤，故 ALL 与看板现状一致。
--   如需「车间只能看本车间」，属独立改造（按 m.workshop_type 过滤），不在本迁移范围。
--
-- ⚠️ 成员绑定取自 employees.position 源文本（生产人员 / 车间文员 / 报工-XX车间）——
--   这是迁移时点的快照。position 由外部导入覆盖，后续岗位变动不会自动同步角色，
--   需在「角色权限」页人工维护。

INSERT INTO system_roles(role_code,role_name,data_scope,built_in)
SELECT 'SHOPFLOOR_REPORTER','车间报工','SELF',0 FROM DUAL
WHERE NOT EXISTS(SELECT 1 FROM system_roles WHERE role_code='SHOPFLOOR_REPORTER');

INSERT IGNORE INTO system_role_permissions(role_id,permission_id)
SELECT r.id,p.id FROM system_roles r JOIN system_permissions p
WHERE r.role_code='SHOPFLOOR_REPORTER' AND p.enabled=TRUE
  AND p.permission_code IN ('manufacturing.report','manufacturing.read.self','manufacturing.issue.report');

INSERT INTO system_role_feature_grants(role_id,feature_code,data_scope,selected_actions)
SELECT r.id,'MANUFACTURING','ALL','VIEW,manufacturing.read.self,manufacturing.report,manufacturing.issue.report'
FROM system_roles r
WHERE r.role_code='SHOPFLOOR_REPORTER'
  AND NOT EXISTS(SELECT 1 FROM system_role_feature_grants g
                 WHERE g.role_id=r.id AND g.feature_code='MANUFACTURING');

INSERT IGNORE INTO employee_roles(employee_id,role_id)
SELECT e.id,r.id FROM employees e JOIN system_roles r
WHERE r.role_code='SHOPFLOOR_REPORTER' AND e.status='ACTIVE'
  AND (FIND_IN_SET('生产人员',e.position)>0
       OR FIND_IN_SET('车间文员',e.position)>0
       OR e.position LIKE '%报工-%');
