-- Sales-confirmed external purchase requirements. Existing BUY rows remain readable without a configuration.
ALTER TABLE erp_sales_order_items ADD COLUMN purchase_config_json JSON NULL;
