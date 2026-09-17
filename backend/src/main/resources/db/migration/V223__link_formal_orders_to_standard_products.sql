-- 正式业务以标准产品为主，订单差异通过机型档位、设备工装要求和配置改动表达。
-- 这里的 tooling_requirement 是设备使用的成型工装/模具，不属于 packaging_molds 包材模具资产。
ALTER TABLE formal_sales_order_items
    ADD COLUMN product_model_id BIGINT NULL AFTER line_no,
    ADD COLUMN product_machine_tier_id BIGINT NULL AFTER product_model_id,
    ADD COLUMN product_model_name_snapshot VARCHAR(200) NULL AFTER product_machine_tier_id,
    ADD COLUMN machine_tier_snapshot VARCHAR(100) NULL AFTER product_model_name_snapshot,
    ADD COLUMN customization_mode VARCHAR(40) NOT NULL DEFAULT 'STANDARD' AFTER specification,
    ADD COLUMN tooling_requirement VARCHAR(2000) NULL AFTER customization_mode,
    ADD KEY idx_fsoi_product_model (product_model_id),
    ADD KEY idx_fsoi_machine_tier (product_machine_tier_id),
    ADD CONSTRAINT fk_fsoi_product_model FOREIGN KEY (product_model_id) REFERENCES product_models(id),
    ADD CONSTRAINT fk_fsoi_machine_tier FOREIGN KEY (product_machine_tier_id) REFERENCES product_machine_tiers(id);

ALTER TABLE manufacturing_orders
    ADD COLUMN product_model_id BIGINT NULL AFTER product_name_snapshot,
    ADD COLUMN product_machine_tier_id BIGINT NULL AFTER product_model_id,
    ADD COLUMN product_model_name_snapshot VARCHAR(200) NULL AFTER product_machine_tier_id,
    ADD COLUMN machine_tier_snapshot VARCHAR(100) NULL AFTER product_model_name_snapshot,
    ADD COLUMN customization_mode VARCHAR(40) NOT NULL DEFAULT 'STANDARD' AFTER specification_snapshot,
    ADD COLUMN tooling_requirement VARCHAR(2000) NULL AFTER customization_mode,
    ADD KEY idx_mo_product_model (product_model_id),
    ADD KEY idx_mo_machine_tier (product_machine_tier_id),
    ADD CONSTRAINT fk_mo_product_model FOREIGN KEY (product_model_id) REFERENCES product_models(id),
    ADD CONSTRAINT fk_mo_machine_tier FOREIGN KEY (product_machine_tier_id) REFERENCES product_machine_tiers(id);

ALTER TABLE finished_goods_lots
    ADD COLUMN product_model_id BIGINT NULL AFTER formal_sales_order_item_id,
    ADD COLUMN product_machine_tier_id BIGINT NULL AFTER product_model_id,
    ADD COLUMN product_model_name_snapshot VARCHAR(200) NULL AFTER product_machine_tier_id,
    ADD COLUMN machine_tier_snapshot VARCHAR(100) NULL AFTER product_model_name_snapshot,
    ADD COLUMN customization_mode VARCHAR(40) NOT NULL DEFAULT 'STANDARD' AFTER specification_snapshot,
    ADD COLUMN tooling_requirement VARCHAR(2000) NULL AFTER customization_mode,
    ADD KEY idx_fgl_product_model (product_model_id),
    ADD KEY idx_fgl_machine_tier (product_machine_tier_id),
    ADD CONSTRAINT fk_fgl_product_model FOREIGN KEY (product_model_id) REFERENCES product_models(id),
    ADD CONSTRAINT fk_fgl_machine_tier FOREIGN KEY (product_machine_tier_id) REFERENCES product_machine_tiers(id);
