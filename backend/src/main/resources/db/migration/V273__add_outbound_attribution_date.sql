-- P2: materialize the frequently queried sales/freight attribution date.
-- Generated column keeps every import/update path consistent without duplicate application logic.
ALTER TABLE sales_outbounds
  ADD COLUMN attribution_date DATE
    GENERATED ALWAYS AS (COALESCE(ship_date, business_date)) STORED,
  ADD INDEX idx_sales_outbounds_attribution_date (attribution_date, outbound_status, employee_id);
