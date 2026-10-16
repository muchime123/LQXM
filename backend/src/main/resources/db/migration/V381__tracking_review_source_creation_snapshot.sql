ALTER TABLE production_tracking_time_reviews
  ADD COLUMN source_created_at DATETIME NULL AFTER source_application_date;
