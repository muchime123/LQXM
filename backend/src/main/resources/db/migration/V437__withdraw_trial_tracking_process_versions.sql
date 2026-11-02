-- Withdraw the only trial product process versions. Preserve all task snapshots
-- and never delete a template referenced by a task snapshot.
DELETE e FROM production_tracking_process_template_edges e
JOIN production_tracking_process_templates t ON t.id=e.template_id
JOIN manufacturing_task_config_items c ON c.id=t.product_config_id
WHERE c.config_name='气动转盘封口机'
  AND NOT EXISTS (SELECT 1 FROM production_tracking_process_snapshots s WHERE s.template_id=t.id);

DELETE n FROM production_tracking_process_template_nodes n
JOIN production_tracking_process_templates t ON t.id=n.template_id
JOIN manufacturing_task_config_items c ON c.id=t.product_config_id
WHERE c.config_name='气动转盘封口机'
  AND NOT EXISTS (SELECT 1 FROM production_tracking_process_snapshots s WHERE s.template_id=t.id);

DELETE t FROM production_tracking_process_templates t
JOIN manufacturing_task_config_items c ON c.id=t.product_config_id
WHERE c.config_name='气动转盘封口机'
  AND NOT EXISTS (SELECT 1 FROM production_tracking_process_snapshots s WHERE s.template_id=t.id);
