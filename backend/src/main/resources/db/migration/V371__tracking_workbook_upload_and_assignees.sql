-- Allow multiple uploaded versions of the same workshop workbook while retaining row provenance.
ALTER TABLE production_tracking_workbook_rows ADD COLUMN source_hash CHAR(64) NULL;
UPDATE production_tracking_workbook_rows
SET source_hash=JSON_UNQUOTE(JSON_EXTRACT(payload_json,'$.sourceHash'));
ALTER TABLE production_tracking_workbook_rows MODIFY source_hash CHAR(64) NOT NULL;
ALTER TABLE production_tracking_workbook_rows DROP INDEX uk_ptwr_sheet_row;
ALTER TABLE production_tracking_workbook_rows
  ADD UNIQUE KEY uk_ptwr_source_row(source_hash,sheet_name,sheet_row),
  ADD KEY idx_ptwr_source_hash(source_hash);

-- Existing follow-up tasks: workshop assignment rule requested on 2026-10-08.
SET @tracking_sealer_assignee=(SELECT id FROM employees WHERE name='陈苏平' AND status='ACTIVE' ORDER BY id LIMIT 1);
SET @tracking_other_assignee=(SELECT id FROM employees WHERE name='胡健' AND status='ACTIVE' ORDER BY id LIMIT 1);

INSERT INTO production_tracking_entries(
  production_order_id,version,content,before_json,after_json,created_by_employee_id,created_by_name
)
SELECT t.production_order_id,t.version+1,'按车间分配跟单员',
       JSON_OBJECT('assigneeEmployeeId',t.assignee_employee_id,'assigneeName',t.assignee_name),
       JSON_OBJECT('assigneeEmployeeId',
         CASE WHEN p.production_line LIKE '%封口%' THEN @tracking_sealer_assignee ELSE @tracking_other_assignee END,
         'assigneeName',CASE WHEN p.production_line LIKE '%封口%' THEN '陈苏平' ELSE '胡健' END),
       0,'系统配置'
FROM production_tracking_state t
JOIN production_orders p ON p.id=t.production_order_id
WHERE CASE WHEN p.production_line LIKE '%封口%' THEN @tracking_sealer_assignee ELSE @tracking_other_assignee END IS NOT NULL
  AND (t.assignee_employee_id IS NULL OR t.assignee_employee_id<>
       CASE WHEN p.production_line LIKE '%封口%' THEN @tracking_sealer_assignee ELSE @tracking_other_assignee END);

UPDATE production_tracking_state t JOIN production_orders p ON p.id=t.production_order_id
SET t.assignee_employee_id=CASE WHEN p.production_line LIKE '%封口%' THEN @tracking_sealer_assignee ELSE @tracking_other_assignee END,
    t.assignee_name=CASE WHEN p.production_line LIKE '%封口%' THEN '陈苏平' ELSE '胡健' END,
    t.version=t.version+1,
    t.last_followup_at=NOW()
WHERE CASE WHEN p.production_line LIKE '%封口%' THEN @tracking_sealer_assignee ELSE @tracking_other_assignee END IS NOT NULL
  AND (t.assignee_employee_id IS NULL OR t.assignee_employee_id<>
       CASE WHEN p.production_line LIKE '%封口%' THEN @tracking_sealer_assignee ELSE @tracking_other_assignee END);
