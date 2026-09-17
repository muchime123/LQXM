ALTER TABLE customers
    ADD COLUMN address_tidy_status VARCHAR(20) NOT NULL DEFAULT 'NORMAL' COMMENT '地址整理状态：NORMAL / UNRECOGNIZED';

CREATE INDEX idx_customers_address_tidy_status ON customers(address_tidy_status);
