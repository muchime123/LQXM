CREATE TABLE historical_sales_organizations (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 name VARCHAR(200) NOT NULL UNIQUE,
 status VARCHAR(20) NOT NULL DEFAULT 'INACTIVE',
 reason VARCHAR(500) NOT NULL,
 created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);
ALTER TABLE sales_outbounds ADD COLUMN historical_organization_id BIGINT NULL,
 ADD INDEX idx_outbound_historical_org (historical_organization_id),
 ADD CONSTRAINT fk_outbound_historical_org FOREIGN KEY (historical_organization_id) REFERENCES historical_sales_organizations(id);
