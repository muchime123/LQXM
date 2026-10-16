-- Keep successive workbook snapshots and make pending/review work explicit.
ALTER TABLE production_tracking_workbook_rows
  ADD COLUMN superseded_at DATETIME NULL,
  ADD COLUMN superseded_by_hash CHAR(64) NULL,
  ADD COLUMN reviewed_at DATETIME NULL,
  ADD COLUMN reviewed_by_employee_id BIGINT NULL;

CREATE INDEX idx_ptwr_review_queue ON production_tracking_workbook_rows(match_status,created_at,id);

-- Older uploads could leave PENDING rows visible alongside a newer complete snapshot.
-- Keep every row for provenance; only the newest uploaded hash of each sheet stays actionable.
UPDATE production_tracking_workbook_rows old_row
JOIN (
  SELECT sheet_name,MAX(id) latest_id
  FROM (SELECT sheet_name,id FROM production_tracking_workbook_rows) snapshot
  GROUP BY sheet_name
) latest ON latest.sheet_name=old_row.sheet_name
JOIN production_tracking_workbook_rows newest ON newest.id=latest.latest_id
SET old_row.match_status='SUPERSEDED',
    old_row.superseded_at=NOW(),
    old_row.superseded_by_hash=newest.source_hash,
    old_row.match_note='已有较新版本表格'
WHERE old_row.source_hash<>newest.source_hash
  AND old_row.match_status IN ('PENDING','REVIEW');
