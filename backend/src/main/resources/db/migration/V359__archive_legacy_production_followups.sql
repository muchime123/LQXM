-- Retire the two legacy follow-up paths without discarding potentially useful manual history.
-- V328/V329 are historical Flyway migrations and must remain unchanged.
-- The archived tables are intentionally kept for mapping into the redesigned follow-up domain.

RENAME TABLE manufacturing_order_follow_ups TO manufacturing_order_follow_ups_archive_20261007;
RENAME TABLE production_followup_logs TO production_followup_logs_archive_20261007;
RENAME TABLE production_followup_records TO production_followup_records_archive_20261007;

-- Preserve manually entered snapshots before removing unused legacy columns.
CREATE TABLE manufacturing_followup_order_snapshot_archive_20261007 AS
SELECT id AS manufacturing_order_id, delay_reason, merchandiser_employee_id, merchandiser_name
FROM manufacturing_orders
WHERE delay_reason IS NOT NULL OR merchandiser_employee_id IS NOT NULL OR merchandiser_name IS NOT NULL;

CREATE TABLE manufacturing_followup_operation_snapshot_archive_20261007 AS
SELECT id AS operation_id, manufacturing_order_id, manual_completed_date,
       manual_completed_by_employee_id, manual_completed_by_name, manual_completed_at
FROM manufacturing_order_operations
WHERE manual_completed_date IS NOT NULL OR manual_completed_at IS NOT NULL;

ALTER TABLE manufacturing_orders
  DROP COLUMN delay_reason,
  DROP COLUMN merchandiser_employee_id,
  DROP COLUMN merchandiser_name;

ALTER TABLE manufacturing_order_operations
  DROP COLUMN manual_completed_date,
  DROP COLUMN manual_completed_by_employee_id,
  DROP COLUMN manual_completed_by_name,
  DROP COLUMN manual_completed_at;

-- expected_finish_date is retained: V343 populated it from the H3 source date.

-- The abandoned permission has no remaining live handler. A redesigned follow-up
-- feature may introduce its own action when implemented.
UPDATE system_role_feature_grants g
SET g.selected_actions = TRIM(BOTH ',' FROM REPLACE(
    CONCAT(',', COALESCE(g.selected_actions, ''), ','), ',manufacturing.followup,', ','))
WHERE FIND_IN_SET('manufacturing.followup', COALESCE(g.selected_actions, '')) > 0;

DELETE FROM system_role_permissions
WHERE permission_id IN (SELECT id FROM system_permissions WHERE permission_code='manufacturing.followup');

DELETE FROM system_feature_actions WHERE permission_code='manufacturing.followup';
DELETE FROM system_permissions WHERE permission_code='manufacturing.followup';
