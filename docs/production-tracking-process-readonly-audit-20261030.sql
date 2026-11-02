-- 生产跟单制程快照只读验收；先确认 V429/V430/V431 已在目标库执行。
-- 本脚本只含 SELECT，不修改任务、快照或跟单记录。

SELECT version,description,success
FROM flyway_schema_history
WHERE version IN ('429','430','431')
ORDER BY installed_rank;

SELECT source_kind,COUNT(*) AS task_count
FROM production_tracking_process_snapshots
GROUP BY source_kind;

SELECT COUNT(*) AS orders_without_snapshot
FROM production_orders p
LEFT JOIN production_tracking_process_snapshots s ON s.production_order_id=p.id
WHERE s.production_order_id IS NULL;

-- 预期 0：空快照会把 0/0 误判为节点全部完成。
SELECT s.production_order_id,p.work_order_no,s.source_kind
FROM production_tracking_process_snapshots s
JOIN production_orders p ON p.id=s.production_order_id
LEFT JOIN production_tracking_process_snapshot_nodes n ON n.production_order_id=s.production_order_id
GROUP BY s.production_order_id,p.work_order_no,s.source_kind
HAVING COUNT(n.stage_code)=0;

-- 预期 0：不适用节点不能同时保留已完成的执行事实。
SELECT p.work_order_no,n.stage_code,m.stage_status,n.applicability
FROM production_tracking_process_snapshot_nodes n
JOIN production_orders p ON p.id=n.production_order_id
JOIN production_tracking_milestones m ON m.production_order_id=n.production_order_id
 AND m.stage_code=n.stage_code
WHERE n.applicability='NOT_APPLICABLE' AND m.stage_status='DONE';

-- 结案后仍未确认的节点：逐单核实；旧任务未建快照的记录另由 legacy-preview 处理。
SELECT p.work_order_no,n.stage_code,n.stage_label,n.applicability,
       COALESCE(m.stage_status,'PENDING') AS execution_status,
       m.completed_date,m.completion_source
FROM production_tracking_process_snapshots s
JOIN production_orders p ON p.id=s.production_order_id
JOIN production_tracking_state t ON t.production_order_id=s.production_order_id AND t.closed_at IS NOT NULL
JOIN production_tracking_process_snapshot_nodes n ON n.production_order_id=s.production_order_id
LEFT JOIN production_tracking_milestones m ON m.production_order_id=n.production_order_id
 AND m.stage_code=n.stage_code
WHERE n.applicability='PENDING_REVIEW'
   OR (n.applicability='APPLICABLE' AND COALESCE(m.stage_status,'PENDING')<>'DONE')
ORDER BY p.work_order_no,n.sort_no;

-- 预期 0：DONE 且无真实日期必须明确标记 NO_DATE 并附依据。
SELECT p.work_order_no,m.stage_code,m.completion_source,m.note
FROM production_tracking_milestones m
JOIN production_orders p ON p.id=m.production_order_id
WHERE m.stage_status='DONE' AND m.completed_date IS NULL
  AND (m.completion_source IS NULL OR m.completion_source<>'NO_DATE'
       OR m.note IS NULL OR TRIM(m.note)='');
