-- Retire local ERP purchase actions. Keep historical purchase tables and H3 source ledgers.
DELETE FROM system_role_feature_grants WHERE feature_code = 'PURCHASE_ORDER';
DELETE FROM system_role_permissions
WHERE permission_id IN (
  SELECT id FROM system_permissions WHERE permission_code LIKE 'purchase_order.%'
);
DELETE FROM system_feature_actions WHERE feature_code = 'PURCHASE_ORDER';
DELETE FROM system_features WHERE feature_code = 'PURCHASE_ORDER';
DELETE FROM system_permissions WHERE permission_code LIKE 'purchase_order.%';
