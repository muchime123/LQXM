-- A forecast equal to the workshop completion date is not a postponement.
-- Keep follow-up entries and explanations; only clear the redundant current date.
UPDATE production_tracking_state t
JOIN production_orders p ON p.id=t.production_order_id
SET t.forecast_finish_date=NULL
WHERE t.forecast_finish_date IS NOT NULL
  AND t.forecast_finish_date=COALESCE(p.expected_completion_date,p.required_completion_date);
