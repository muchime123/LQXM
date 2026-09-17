-- 客户主数据第二阶段：独立收货地址与客户合并历史。
CREATE TABLE IF NOT EXISTS customer_addresses (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    customer_id BIGINT NOT NULL,
    address_name VARCHAR(100) NULL,
    contact_name VARCHAR(100) NULL,
    contact_phone VARCHAR(100) NULL,
    province VARCHAR(100) NULL,
    city VARCHAR(100) NULL,
    district VARCHAR(100) NULL,
    detail_address VARCHAR(500) NOT NULL,
    normalized_address VARCHAR(500) NULL,
    is_default TINYINT(1) NOT NULL DEFAULT 0,
    source VARCHAR(50) NULL,
    remark VARCHAR(500) NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_customer_addresses_customer (customer_id),
    KEY idx_customer_addresses_normalized (normalized_address(191))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='客户收货地址';

INSERT INTO customer_addresses
    (customer_id,address_name,province,city,district,detail_address,normalized_address,is_default,source,remark)
SELECT c.id,'默认地址',c.province,c.city,c.district,c.address,
       LOWER(REPLACE(REPLACE(REPLACE(REPLACE(TRIM(c.address),' ',''),'　',''),',',''),'，','')),
       1,'CUSTOMER_ARCHIVE','从原客户档案地址回填'
FROM customers c
WHERE c.deleted_at IS NULL AND c.address IS NOT NULL AND TRIM(c.address)<>''
  AND NOT EXISTS (SELECT 1 FROM customer_addresses a WHERE a.customer_id=c.id);

CREATE TABLE IF NOT EXISTS customer_merge_history (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    primary_customer_id BIGINT NOT NULL,
    merged_customer_id BIGINT NOT NULL,
    merged_customer_code VARCHAR(20) NULL,
    merged_customer_name VARCHAR(200) NOT NULL,
    merged_address VARCHAR(500) NULL,
    merge_reason VARCHAR(200) NOT NULL,
    merged_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_customer_merge_history_merged (merged_customer_id),
    KEY idx_customer_merge_history_primary (primary_customer_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='客户档案合并历史';

INSERT IGNORE INTO customer_merge_history
    (primary_customer_id,merged_customer_id,merged_customer_code,merged_customer_name,merged_address,merge_reason,merged_at)
SELECT CAST(SUBSTRING_INDEX(c.remark,'客户ID=',-1) AS UNSIGNED),c.id,c.customer_code,c.customer_name,c.address,
       '客户名称主体及完整地址一致',COALESCE(c.deleted_at,NOW())
FROM customers c
WHERE c.deleted_at IS NOT NULL AND c.remark LIKE '%按相同客户名称主体和完整地址合并至客户ID=%';
