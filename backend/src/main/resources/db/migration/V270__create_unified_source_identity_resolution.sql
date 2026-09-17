-- 氚云身份解析第一阶段：客户/员工/产品/仓库统一来源身份与待处理队列。
CREATE TABLE source_identity_aliases (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  source_system VARCHAR(30) NOT NULL DEFAULT 'H3YUN',
  entity_type VARCHAR(30) NOT NULL COMMENT 'CUSTOMER/EMPLOYEE/PRODUCT/WAREHOUSE',
  source_key VARCHAR(500) NOT NULL COMMENT '稳定外部ID；没有ID时使用规范化名称',
  source_name VARCHAR(500) NULL,
  normalized_name VARCHAR(500) NULL,
  target_id BIGINT NULL COMMENT '对应正式业务对象ID（按entity_type解释）',
  resolution_status VARCHAR(20) NOT NULL DEFAULT 'REVIEW' COMMENT 'AUTO/CONFIRMED/REVIEW/IGNORED',
  issue_code VARCHAR(50) NULL,
  confidence DECIMAL(5,4) NULL,
  first_batch_id BIGINT NULL,
  last_batch_id BIGINT NULL,
  occurrence_count BIGINT NOT NULL DEFAULT 1,
  resolution_note VARCHAR(1000) NULL,
  resolved_by_employee_id BIGINT NULL,
  resolved_by_name VARCHAR(100) NULL,
  resolved_at DATETIME NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_source_identity (source_system,entity_type,source_key),
  KEY idx_source_identity_queue (resolution_status,entity_type),
  KEY idx_source_identity_target (entity_type,target_id),
  KEY idx_source_identity_name (entity_type,normalized_name(191))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='外部来源到正式业务身份的统一解析结果';

-- 已确认的员工外部身份。
INSERT IGNORE INTO source_identity_aliases
  (source_system,entity_type,source_key,source_name,normalized_name,target_id,
   resolution_status,confidence,resolution_note,resolved_at)
SELECT source_system,'EMPLOYEE',external_user_id,external_name,
       LOWER(REPLACE(REPLACE(TRIM(COALESCE(external_name,'')),' ',''),'　','')),
       employee_id,'CONFIRMED',1.0000,'由员工外部映射迁移',mapped_at
FROM employee_external_mappings
WHERE source_system='H3YUN';

-- 已解析的产品别名。V260 已移除旧 source_batch_id，使用当前表中仍保留的字段。
INSERT IGNORE INTO source_identity_aliases
  (source_system,entity_type,source_key,source_name,normalized_name,target_id,
   resolution_status,issue_code,confidence,resolution_note,resolved_at)
SELECT source_system,'PRODUCT',
       COALESCE(NULLIF(source_product_code,''),external_id),
       source_name,
       LOWER(REPLACE(REPLACE(TRIM(COALESCE(source_name,'')),' ',''),'　','')),
       sales_product_id,
       CASE WHEN resolution_status='RESOLVED' AND sales_product_id IS NOT NULL
            THEN 'CONFIRMED' ELSE 'REVIEW' END,
       issue_code,
       CASE WHEN resolution_status='RESOLVED' AND sales_product_id IS NOT NULL THEN 1.0000 ELSE NULL END,
       resolution_note,
       CASE WHEN resolution_status='RESOLVED' AND sales_product_id IS NOT NULL THEN last_seen_at ELSE NULL END
FROM sales_product_source_aliases;

-- 从已落库销售事实沉淀现有客户绑定。仅同一规范化名称唯一指向一个客户时确认。
INSERT IGNORE INTO source_identity_aliases
  (source_system,entity_type,source_key,source_name,normalized_name,target_id,
   resolution_status,confidence,resolution_note,resolved_at)
SELECT 'H3YUN','CUSTOMER',
       LOWER(REPLACE(REPLACE(TRIM(o.customer_name),' ',''),'　','')),
       MIN(o.customer_name),
       LOWER(REPLACE(REPLACE(TRIM(o.customer_name),' ',''),'　','')),
       MIN(o.customer_id),'CONFIRMED',1.0000,'由历史销售出库唯一客户绑定迁移',NOW()
FROM sales_outbounds o
WHERE o.external_source LIKE 'H3%' AND o.customer_id IS NOT NULL
  AND o.customer_name IS NOT NULL AND TRIM(o.customer_name)<>''
GROUP BY LOWER(REPLACE(REPLACE(TRIM(o.customer_name),' ',''),'　',''))
HAVING COUNT(DISTINCT o.customer_id)=1;

-- 仓库名精确一致时作为已确认映射。
INSERT IGNORE INTO source_identity_aliases
  (source_system,entity_type,source_key,source_name,normalized_name,target_id,
   resolution_status,confidence,resolution_note,resolved_at)
SELECT 'H3YUN','WAREHOUSE',
       LOWER(REPLACE(REPLACE(TRIM(s.warehouse),' ',''),'　','')),
       MIN(s.warehouse),
       LOWER(REPLACE(REPLACE(TRIM(s.warehouse),' ',''),'　','')),
       MIN(w.id),'CONFIRMED',1.0000,'由仓库明细与正式仓库精确名称迁移',NOW()
FROM h3_warehouse_stock s
JOIN warehouses w ON TRIM(w.warehouse_name)=TRIM(s.warehouse) AND w.active=1
WHERE s.warehouse IS NOT NULL AND TRIM(s.warehouse)<>''
GROUP BY LOWER(REPLACE(REPLACE(TRIM(s.warehouse),' ',''),'　',''))
HAVING COUNT(DISTINCT w.id)=1;

INSERT IGNORE INTO system_permissions(permission_code,permission_name)
VALUES ('integration.h3.identity.resolve','处理氚云身份映射');

INSERT IGNORE INTO system_role_permissions(role_id,permission_id)
SELECT r.id,p.id FROM system_roles r CROSS JOIN system_permissions p
WHERE r.role_code IN ('ADMIN','BOSS')
  AND p.permission_code='integration.h3.identity.resolve';
