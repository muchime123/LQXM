-- Separate available stage definitions from the legacy workshop default flow.
-- Existing definitions keep their previous behavior; newly created definitions
-- enter the dictionary only until explicitly added to the default flow or a product template.
ALTER TABLE production_tracking_stage_defs
  ADD COLUMN default_included TINYINT(1) NOT NULL DEFAULT 0
  COMMENT '1=used by orders without a product process snapshot; 0=dictionary only';

UPDATE production_tracking_stage_defs SET default_included=enabled;
