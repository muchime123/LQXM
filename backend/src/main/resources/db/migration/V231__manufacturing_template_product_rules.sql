CREATE TABLE manufacturing_process_template_rules (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  template_id BIGINT NOT NULL,
  product_model_id BIGINT NOT NULL,
  customization_mode VARCHAR(40) NULL,
  priority INT NOT NULL DEFAULT 100,
  active TINYINT(1) NOT NULL DEFAULT 1,
  remark VARCHAR(500) NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  KEY idx_mptr_template (template_id),
  KEY idx_mptr_product (product_model_id,active),
  CONSTRAINT fk_mptr_template FOREIGN KEY (template_id) REFERENCES manufacturing_process_templates(id),
  CONSTRAINT fk_mptr_product FOREIGN KEY (product_model_id) REFERENCES product_models(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='标准产品与制程模板推荐规则';
