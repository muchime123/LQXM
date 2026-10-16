-- Stable identifiers and draft/published naming rules. No stock or product-identity changes.
ALTER TABLE manufacturing_task_config_items
  ADD COLUMN config_code VARCHAR(40) NULL,
  ADD COLUMN product_type VARCHAR(20) NULL,
  ADD COLUMN option_group_code VARCHAR(30) NULL,
  ADD COLUMN sort_order INT NOT NULL DEFAULT 0;
UPDATE manufacturing_task_config_items
 SET config_code=CONCAT(IF(config_type='PRODUCT_NAME','BP-','OPT-'),LPAD(id,8,'0')),
     product_type=CASE WHEN config_type<>'PRODUCT_NAME' THEN NULL
                       WHEN config_name='配件' THEN 'PART'
                       WHEN config_name='模具' OR config_name LIKE '%式模具' OR config_name LIKE '%真空模具' THEN 'MOLD'
                       ELSE 'MACHINE' END,
     option_group_code=CASE WHEN config_type='OPTIONAL_CONFIG' THEN 'UNCLASSIFIED' ELSE NULL END;
ALTER TABLE manufacturing_task_config_items MODIFY config_code VARCHAR(40) NOT NULL,
  ADD UNIQUE KEY uk_task_config_code(config_code);
CREATE TABLE manufacturing_product_config_rules (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 base_product_id BIGINT NOT NULL,
 version_no INT NOT NULL,
 status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
 draft_revision INT NOT NULL DEFAULT 1,
 rules_json JSON NOT NULL,
 definition_snapshot_json JSON NULL,
 created_by BIGINT NULL,
 published_by BIGINT NULL,
 published_at DATETIME NULL,
 created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
 UNIQUE KEY uk_product_rule_version(base_product_id,version_no),
 KEY idx_product_rule_status(base_product_id,status),
 CONSTRAINT fk_product_config_rule_base FOREIGN KEY(base_product_id) REFERENCES manufacturing_task_config_items(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
