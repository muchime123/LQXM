-- A tracking task exists only after workbook import or explicit enrollment.
-- Preserve historical follow-ups before removing the broad date-based list rule.
INSERT IGNORE INTO production_tracking_enrollments(production_order_id,origin)
SELECT production_order_id,'MANUAL' FROM production_tracking_state WHERE version>0;

INSERT IGNORE INTO production_tracking_enrollments(production_order_id,origin)
SELECT DISTINCT production_order_id,'MANUAL' FROM production_tracking_entries;

INSERT IGNORE INTO production_tracking_enrollments(production_order_id,origin)
SELECT DISTINCT production_order_id,'WORKBOOK' FROM production_tracking_workbook_rows
WHERE match_status='IMPORTED' AND production_order_id IS NOT NULL;
