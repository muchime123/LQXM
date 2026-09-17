-- 每个客户最多一个默认地址；NULL 允许同一客户存在多条非默认地址。
ALTER TABLE customer_addresses
  ADD COLUMN default_guard TINYINT GENERATED ALWAYS AS (CASE WHEN is_default=1 THEN 1 ELSE NULL END) STORED,
  ADD UNIQUE KEY uk_customer_address_default (customer_id,default_guard),
  ADD UNIQUE KEY uk_customer_address_normalized (customer_id,normalized_address);
