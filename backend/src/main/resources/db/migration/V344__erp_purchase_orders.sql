-- =====================================================================
-- V344: 外采采购订单（BUY 履约骨架）
--
-- 决策（LQ 2026-09-24 拍板）：
--   1) 方案 A：采购到货入本地库存账（product_inventory_balances + 流水），
--      与自产完工入库同套余额表，出库走标准扣减；采购价落 PO 明细做权威源。
--   2) 代发型（DROP_SHIP）：供应商直发客户，不写本地库存账；
--      销售出库过账时按 fulfillment_mode 豁免扣库存（P3 在 SalesOutboundService 实现）。
--   3) 一行销售需求对应一个采购订单（1:1，仿 manufacturing_orders）。
--
-- 与 erp_sales_orders 对称；挂 erp_sales_order_items.id 做行级追溯。
-- 采购订单是本地动作单（DRAFT→CONFIRMED→RECEIVING→CLOSED/CANCELLED），
-- 与只读氚云台账 purchase_receipts（V305）隔离：后者只落已入库事实，不写本地 PO。
--
-- 迁移目标库：product_archive（与 V340-V343 同库；erp schema 仅 m0 infra 空壳）。
-- =====================================================================

-- ---------- 1. 采购订单主表 ----------
CREATE TABLE erp_purchase_orders (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  po_no VARCHAR(50) NOT NULL UNIQUE COMMENT '采购订单号 CGDD%08d',
  status VARCHAR(20) NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT/CONFIRMED/RECEIVING/CLOSED/CANCELLED',
  supplier_id BIGINT NULL COMMENT '关联 h3_supplier_catalog.id；未解析为空',
  supplier_name_snapshot VARCHAR(190) NOT NULL COMMENT '供应商名称快照',
  warehouse_id BIGINT NULL COMMENT '入库型目标仓库；代发可空',
  fulfillment_mode VARCHAR(20) NOT NULL DEFAULT 'WAREHOUSE' COMMENT 'WAREHOUSE 入库型 / DROP_SHIP 代发型',
  sales_order_item_id BIGINT NULL COMMENT '1:1 追溯销售订单明细（仿 manufacturing_orders）',
  expected_arrival_date DATE NULL,
  total_amount DECIMAL(18,4) NULL,
  remark VARCHAR(1000) NULL,
  confirmed_at DATETIME NULL,
  confirmed_by_employee_id BIGINT NULL,
  created_by_employee_id BIGINT NULL,
  created_by_name VARCHAR(100) NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_epo_sales_order_item (sales_order_item_id) COMMENT '1:1 一行销售需求对应一个采购订单',
  KEY idx_epo_supplier (supplier_id),
  KEY idx_epo_status (status),
  KEY idx_epo_warehouse (warehouse_id),
  CONSTRAINT fk_epo_sales_order_item FOREIGN KEY (sales_order_item_id) REFERENCES erp_sales_order_items(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='外采采购订单（与销售订单对称，行级追溯）';

-- ---------- 2. 采购订单明细 ----------
CREATE TABLE erp_purchase_order_items (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  po_id BIGINT NOT NULL,
  line_no INT NOT NULL,
  sales_product_id BIGINT NULL COMMENT '标准品有档案；临时外采品为空',
  product_name_snapshot VARCHAR(500) NOT NULL,
  specification_snapshot VARCHAR(1000) NULL,
  unit VARCHAR(40) NOT NULL,
  quantity DECIMAL(18,6) NOT NULL,
  purchase_price DECIMAL(18,4) NULL COMMENT '采购价权威源（PO 明细）',
  amount DECIMAL(18,4) NULL,
  received_quantity DECIMAL(18,6) NOT NULL DEFAULT 0 COMMENT '累计到货入库量',
  remark VARCHAR(500) NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_epoi_po_line (po_id, line_no),
  KEY idx_epoi_product (sales_product_id),
  CONSTRAINT fk_epoi_po FOREIGN KEY (po_id) REFERENCES erp_purchase_orders(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='外采采购订单明细';

-- ---------- 3. 权限码注册 ----------
INSERT IGNORE INTO system_permissions(permission_code,permission_name) VALUES
('purchase_order.read.self','查看本人采购订单'),
('purchase_order.read.department','查看本部门采购订单'),
('purchase_order.read.all','查看全部采购订单'),
('purchase_order.manage','管理采购订单');

-- ---------- 4. 功能目录与归类 ----------
-- ⚠️ category='PROCUREMENT' 是**新增分类**，必须同时在前端
-- RolePermissionView.vue 的 CATEGORY_LABELS / CATEGORY_ORDER 里登记：
-- 该页按 CATEGORY_ORDER 过滤分组（CATEGORY_ORDER.filter(c=>groups.has(c))），
-- 未登记的 category 不会报错，只会**整块不显示**——功能授权页看不见即等于没授权入口。
INSERT IGNORE INTO system_features(feature_code,feature_name,category,sort_order) VALUES
('PURCHASE_ORDER','采购订单','PROCUREMENT',145);

INSERT IGNORE INTO system_feature_actions(feature_code,action_kind,permission_code,variant,sort_order) VALUES
('PURCHASE_ORDER','VIEW','purchase_order.read.self','SELF',1),
('PURCHASE_ORDER','VIEW','purchase_order.read.department','DEPARTMENT',2),
('PURCHASE_ORDER','VIEW','purchase_order.read.all','ALL',3),
('PURCHASE_ORDER','EXECUTE','purchase_order.manage',NULL,4);

-- ---------- 5. 角色授权（执行层 system_role_permissions + 配置层 system_role_feature_grants） ----------
-- 两层都要写：执行层决定「实际能不能点」，配置层决定「角色权限页显示成什么」。
-- 只写一层会出现「页面上有勾但实际上被拒」或反过来的静默分叉（V337 已固化此纪律）。
--   ADMIN / BOSS      → 全量（ALL）
--   SALES_MANAGER     → 部门范围读 + 管理（外采由销售侧发起）
-- 其余角色留空，由 LQ 在「角色权限」页按需勾选。
INSERT IGNORE INTO system_role_permissions(role_id,permission_id)
SELECT r.id,p.id FROM system_roles r JOIN system_permissions p
  ON p.enabled=TRUE AND p.permission_code IN
     ('purchase_order.read.self','purchase_order.read.department','purchase_order.read.all','purchase_order.manage')
 WHERE r.role_code IN ('ADMIN','BOSS');

INSERT IGNORE INTO system_role_permissions(role_id,permission_id)
SELECT r.id,p.id FROM system_roles r JOIN system_permissions p
  ON p.enabled=TRUE AND p.permission_code IN
     ('purchase_order.read.department','purchase_order.read.all','purchase_order.manage')
 WHERE r.role_code='SALES_MANAGER';

INSERT INTO system_role_feature_grants(role_id,feature_code,data_scope,selected_actions)
SELECT r.id,'PURCHASE_ORDER','ALL',
       'VIEW,purchase_order.read.self,purchase_order.read.department,purchase_order.read.all,purchase_order.manage'
  FROM system_roles r
 WHERE r.role_code IN ('ADMIN','BOSS')
   AND NOT EXISTS(SELECT 1 FROM system_role_feature_grants g
                   WHERE g.role_id=r.id AND g.feature_code='PURCHASE_ORDER');

INSERT INTO system_role_feature_grants(role_id,feature_code,data_scope,selected_actions)
SELECT r.id,'PURCHASE_ORDER','DEPARTMENT',
       'VIEW,purchase_order.read.department,purchase_order.read.all,purchase_order.manage'
  FROM system_roles r
 WHERE r.role_code='SALES_MANAGER'
   AND NOT EXISTS(SELECT 1 FROM system_role_feature_grants g
                   WHERE g.role_id=r.id AND g.feature_code='PURCHASE_ORDER');
