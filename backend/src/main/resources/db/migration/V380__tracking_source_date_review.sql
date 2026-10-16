-- Keep the application date snapshot so a source-date investigation can be rechecked after an H3 correction.
ALTER TABLE production_tracking_time_reviews
  ADD COLUMN source_application_date DATE NULL AFTER source_due_date;
