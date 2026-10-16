-- A review must be rechecked when the imported schedule/report/inbound dates change later.
ALTER TABLE production_tracking_time_reviews
  ADD COLUMN source_schedule_time DATETIME NULL AFTER source_expected_date,
  ADD COLUMN source_report_date DATE NULL AFTER source_schedule_time,
  ADD COLUMN source_inbound_date DATE NULL AFTER source_report_date;
