-- Retire locally created sales/outbound orders and manufacturing creation/approval.
-- Historical ERP tables remain readable for audit; H3 ledgers and tracking are untouched.

DELETE FROM system_role_feature_grants WHERE feature_code = 'SALES_OUTBOUND';

UPDATE system_role_feature_grants
SET selected_actions = TRIM(BOTH ',' FROM
  REPLACE(
    REPLACE(CONCAT(',', selected_actions, ','), ',manufacturing.manage,', ','),
    ',manufacturing.approve,', ','))
WHERE feature_code = 'MANUFACTURING';

DELETE FROM system_role_permissions
WHERE permission_id IN (
  SELECT id FROM system_permissions
  WHERE permission_code LIKE 'sales_order.%'
     OR permission_code IN ('manufacturing.manage', 'manufacturing.approve')
);

DELETE FROM system_feature_actions
WHERE feature_code = 'SALES_OUTBOUND'
   OR permission_code IN ('manufacturing.manage', 'manufacturing.approve');
DELETE FROM system_features WHERE feature_code = 'SALES_OUTBOUND';
DELETE FROM system_permissions
WHERE permission_code LIKE 'sales_order.%'
   OR permission_code IN ('manufacturing.manage', 'manufacturing.approve');
