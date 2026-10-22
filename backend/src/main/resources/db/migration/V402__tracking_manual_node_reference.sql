-- Per-order node forecasts entered by follow-up staff. Product/general benchmarks remain unchanged.
ALTER TABLE production_tracking_milestones
  ADD COLUMN manual_reference_date DATE NULL,
  ADD COLUMN manual_reference_reason VARCHAR(300) NULL;
