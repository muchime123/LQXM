-- 车间只报工；跟单/质检岗复核报工、冲正、完工检验并入库。
UPDATE system_permissions SET permission_name='报工冲正（质检或跟单复核）'
 WHERE permission_code='manufacturing.report.override';
UPDATE system_permissions SET permission_name='完工检验并入库（质检或跟单）'
 WHERE permission_code='manufacturing.inspect';

DELETE rp FROM system_role_permissions rp
 JOIN system_roles r ON r.id=rp.role_id
 JOIN system_permissions p ON p.id=rp.permission_id
 WHERE r.role_code IN ('PRODUCTION','PRODUCTION_MANAGER')
   AND p.permission_code IN ('manufacturing.inspect','manufacturing.report.override');
DELETE rp FROM system_role_permissions rp
 JOIN system_roles r ON r.id=rp.role_id
 JOIN system_permissions p ON p.id=rp.permission_id
 WHERE r.role_code='PRODUCTION_TRACER' AND p.permission_code='manufacturing.report';
INSERT IGNORE INTO system_role_permissions(role_id,permission_id)
 SELECT r.id,p.id FROM system_roles r CROSS JOIN system_permissions p
 WHERE r.role_code='PRODUCTION_TRACER'
   AND p.permission_code IN ('manufacturing.inspect','manufacturing.report.override');

UPDATE system_role_feature_grants g JOIN system_roles r ON r.id=g.role_id
 SET g.selected_actions=TRIM(BOTH ',' FROM REPLACE(CONCAT(',',g.selected_actions,','),',manufacturing.inspect,',','))
 WHERE r.role_code IN ('PRODUCTION','PRODUCTION_MANAGER') AND g.feature_code='MANUFACTURING'
   AND FIND_IN_SET('manufacturing.inspect',g.selected_actions)>0;
UPDATE system_role_feature_grants g JOIN system_roles r ON r.id=g.role_id
 SET g.selected_actions=TRIM(BOTH ',' FROM REPLACE(CONCAT(',',g.selected_actions,','),',manufacturing.report.override,',','))
 WHERE r.role_code IN ('PRODUCTION','PRODUCTION_MANAGER') AND g.feature_code='MANUFACTURING'
   AND FIND_IN_SET('manufacturing.report.override',g.selected_actions)>0;
UPDATE system_role_feature_grants g JOIN system_roles r ON r.id=g.role_id
 SET g.selected_actions=TRIM(BOTH ',' FROM REPLACE(CONCAT(',',g.selected_actions,','),',manufacturing.report,',','))
 WHERE r.role_code='PRODUCTION_TRACER' AND g.feature_code='MANUFACTURING'
   AND FIND_IN_SET('manufacturing.report',g.selected_actions)>0;
UPDATE system_role_feature_grants g JOIN system_roles r ON r.id=g.role_id
 SET g.selected_actions=CONCAT_WS(',',NULLIF(g.selected_actions,''),'manufacturing.inspect')
 WHERE r.role_code='PRODUCTION_TRACER' AND g.feature_code='MANUFACTURING'
   AND FIND_IN_SET('manufacturing.inspect',g.selected_actions)=0;
UPDATE system_role_feature_grants g JOIN system_roles r ON r.id=g.role_id
 SET g.selected_actions=CONCAT_WS(',',NULLIF(g.selected_actions,''),'manufacturing.report.override')
 WHERE r.role_code='PRODUCTION_TRACER' AND g.feature_code='MANUFACTURING'
   AND FIND_IN_SET('manufacturing.report.override',g.selected_actions)=0;

-- 尚未入库、当时被自动放行的任务重新进入人工检验；已有入库事实不回写。
UPDATE manufacturing_orders
 SET status='PENDING_INSPECTION',inspection_status='PENDING',inspection_source=NULL
 WHERE status='PENDING_RECEIPT' AND completed_quantity=0
   AND inspection_status='NOT_REQUIRED';
