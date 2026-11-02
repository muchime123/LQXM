-- The online node dictionary was withdrawn. Its rows duplicate code-managed
-- stage definitions; read-only audit found no dictionary-only node.
-- Remove the unused sealer DRAWING binding left by the trial product template,
-- but preserve it if any milestone or task snapshot actually references it.
DELETE d FROM production_tracking_stage_defs d
WHERE d.workshop_kind='SEALER' AND d.stage_code='DRAWING' AND d.default_included=0
  AND NOT EXISTS (SELECT 1 FROM production_tracking_milestones m
                  JOIN production_orders p ON p.id=m.production_order_id
                  WHERE m.stage_code=d.stage_code AND p.production_line LIKE '%封口%')
  AND NOT EXISTS (SELECT 1 FROM production_tracking_process_snapshot_nodes n
                  JOIN production_orders p ON p.id=n.production_order_id
                  WHERE n.stage_code=d.stage_code AND p.production_line LIKE '%封口%');

DROP TABLE IF EXISTS production_tracking_stage_dictionary;
