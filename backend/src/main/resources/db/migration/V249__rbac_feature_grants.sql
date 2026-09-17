-- =====================================================================
-- V249 角色「功能 × 数据范围 × 操作」授权模型
--
-- 背景：原 RBAC 直接把 90 个权限码平铺在角色上勾选（role_permissions），
-- 数据范围是"角色级单值"（system_roles.data_scope），无法针对单一业务
-- 功能分别控制数据范围（全部/本部门/本人/自定义员工部门）与操作权限。
--
-- 本迁移在保留"权限码"执行层（@RequirePermission + role_permissions，
-- 拦截器零改动）之上，新增配置层：
--   system_features            业务功能目录（如：生产制造 / 正式销售订单…）
--   system_feature_actions     权限码归类：功能 × 动作（查看/新增/编辑/删除/执行）
--                              其中"查看"支持范围变体码（read.self/department/all）
--   system_role_feature_grants 角色 × 功能 的授权行：
--                              数据范围 + 自定义员工/部门名单 + 勾选的动作
-- 角色授权以 grants 为准，保存时由后端把 grants 展开回写 role_permissions。
-- =====================================================================

-- ---------- 1. 功能目录 ----------
CREATE TABLE system_features (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    feature_code VARCHAR(50)  NOT NULL UNIQUE,
    feature_name VARCHAR(100) NOT NULL,
    category VARCHAR(50) NOT NULL DEFAULT 'PLATFORM',
    sort_order INT NOT NULL DEFAULT 0,
    enabled TINYINT(1) NOT NULL DEFAULT 1,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
) COMMENT='权限功能目录';

INSERT INTO system_features(feature_code, feature_name, category, sort_order) VALUES
('BUSINESS','销售业务总览（旧）','SALES',10),
('SALES_OUTBOUND','销售出库与核销（旧）','SALES',20),
('ORDER_EXPENSE','销售出库费用（旧）','SALES',30),
('ORDER_PROFIT','销售出库利润（旧）','SALES',40),
('ORDER_COMMISSION','销售出库提成（旧）','SALES',50),
('FORMAL_SALES_ORDER','正式销售订单','SALES',60),
('SALES_SHIPMENT','正式销售出库','SALES',70),
('FORMAL_RECEIPT','正式收款登记','FINANCE',80),
('FORMAL_PROFIT','正式利润核算','FINANCE',90),
('EXPENSE','费用报销（旧）','FINANCE',100),
('PROFIT','业务利润（旧）','FINANCE',110),
('COMMISSION','提成规则（旧）','FINANCE',120),
('MONTHLY_COMMISSION','月度提成核算','FINANCE',130),
('ORDER_ACCEPTANCE','销售订单财务验收','FINANCE',140),
('MANUFACTURING','生产制造','MFG',150),
('MATERIAL_INVENTORY','原材料档案与库存','INVENTORY',160),
('PRODUCT_INVENTORY','产品库存','INVENTORY',170),
('PRODUCT_SOURCE','氚云产品来源解析','DATA',180),
('PRODUCT_CENTER','产品中心','PLATFORM',200),
('PACKAGING','包材管理','PLATFORM',210),
('MOLD','模具管理','PLATFORM',220),
('COMPANY','公司信息','PLATFORM',230),
('CERTIFICATE','知识产权','PLATFORM',240),
('OFFICE_ASSET','物品资产','PLATFORM',250),
('MEDIA','自媒体账号','PLATFORM',260),
('FILE_LIBRARY','文件素材库','PLATFORM',270),
('WEBSITE','官网运营','PLATFORM',280),
('CUSTOMER','客户档案与合同','PLATFORM',290),
('SYSTEM_CONFIG','平台配置','PLATFORM',300),
('EMPLOYEE_ADMIN','员工与权限','PLATFORM',310),
('H3_INTEGRATION','氚云数据集成','PLATFORM',320),
('DINGTALK_INTEGRATION','钉钉集成','PLATFORM',330);

-- ---------- 2. 权限码 → 功能 × 动作 归类 ----------
-- action_kind: VIEW 查看 / CREATE 新增 / UPDATE 编辑 / DELETE 删除 / EXECUTE 其他操作
-- variant: 查看动作的范围变体码（read.self / read.department / read.all），非查看动作为 NULL
CREATE TABLE system_feature_actions (
    feature_code    VARCHAR(50)  NOT NULL,
    action_kind     VARCHAR(16)  NOT NULL,
    permission_code VARCHAR(100) NOT NULL,
    variant         VARCHAR(16)  NULL,
    sort_order      INT NOT NULL DEFAULT 0,
    PRIMARY KEY (feature_code, permission_code),
    UNIQUE KEY uk_sfa_permission (permission_code),
    CONSTRAINT fk_sfa_feature FOREIGN KEY (feature_code) REFERENCES system_features(feature_code),
    CONSTRAINT fk_sfa_permission FOREIGN KEY (permission_code) REFERENCES system_permissions(permission_code)
) COMMENT='权限码按功能与动作归类（执行层仍以 system_permissions / role_permissions 为准）';

-- 销售业务总览（旧）
INSERT INTO system_feature_actions(feature_code, action_kind, permission_code, variant, sort_order) VALUES
('BUSINESS','VIEW','business.read.self','SELF',1),
('BUSINESS','VIEW','business.read.department','DEPARTMENT',2),
('BUSINESS','VIEW','business.read.all','ALL',3),
('BUSINESS','EXECUTE','business.manage',NULL,4);

-- 销售出库与核销（旧）
INSERT INTO system_feature_actions(feature_code, action_kind, permission_code, variant, sort_order) VALUES
('SALES_OUTBOUND','VIEW','sales_order.read.self','SELF',1),
('SALES_OUTBOUND','VIEW','sales_order.read.department','DEPARTMENT',2),
('SALES_OUTBOUND','VIEW','sales_order.read.all','ALL',3),
('SALES_OUTBOUND','EXECUTE','sales_order.manage',NULL,4);

-- 销售出库费用（旧）：无 read.all 变体
INSERT INTO system_feature_actions(feature_code, action_kind, permission_code, variant, sort_order) VALUES
('ORDER_EXPENSE','VIEW','order_expense.read.self','SELF',1),
('ORDER_EXPENSE','VIEW','order_expense.read.department','DEPARTMENT',2),
('ORDER_EXPENSE','EXECUTE','order_expense.manage',NULL,3);

-- 销售出库利润（旧）
INSERT INTO system_feature_actions(feature_code, action_kind, permission_code, variant, sort_order) VALUES
('ORDER_PROFIT','VIEW','order_profit.read.self','SELF',1),
('ORDER_PROFIT','VIEW','order_profit.read.department','DEPARTMENT',2),
('ORDER_PROFIT','VIEW','order_profit.read.all','ALL',3),
('ORDER_PROFIT','EXECUTE','order_profit.manage',NULL,4);

-- 销售出库提成（旧）
INSERT INTO system_feature_actions(feature_code, action_kind, permission_code, variant, sort_order) VALUES
('ORDER_COMMISSION','VIEW','order_commission.read.self','SELF',1),
('ORDER_COMMISSION','VIEW','order_commission.read.department','DEPARTMENT',2),
('ORDER_COMMISSION','VIEW','order_commission.read.all','ALL',3),
('ORDER_COMMISSION','EXECUTE','order_commission.manage',NULL,4),
('ORDER_COMMISSION','EXECUTE','order_commission.settle',NULL,5);

-- 正式销售订单
INSERT INTO system_feature_actions(feature_code, action_kind, permission_code, variant, sort_order) VALUES
('FORMAL_SALES_ORDER','VIEW','formal_sales_order.read.self','SELF',1),
('FORMAL_SALES_ORDER','VIEW','formal_sales_order.read.department','DEPARTMENT',2),
('FORMAL_SALES_ORDER','VIEW','formal_sales_order.read.all','ALL',3),
('FORMAL_SALES_ORDER','EXECUTE','formal_sales_order.manage',NULL,4);

-- 正式销售出库
INSERT INTO system_feature_actions(feature_code, action_kind, permission_code, variant, sort_order) VALUES
('SALES_SHIPMENT','VIEW','sales_shipment.read.self','SELF',1),
('SALES_SHIPMENT','VIEW','sales_shipment.read.department','DEPARTMENT',2),
('SALES_SHIPMENT','VIEW','sales_shipment.read.all','ALL',3),
('SALES_SHIPMENT','EXECUTE','sales_shipment.manage',NULL,4),
('SALES_SHIPMENT','DELETE','sales_shipment.reverse',NULL,5);

-- 正式收款登记
INSERT INTO system_feature_actions(feature_code, action_kind, permission_code, variant, sort_order) VALUES
('FORMAL_RECEIPT','VIEW','formal_receipt.read.self','SELF',1),
('FORMAL_RECEIPT','VIEW','formal_receipt.read.department','DEPARTMENT',2),
('FORMAL_RECEIPT','VIEW','formal_receipt.read.all','ALL',3),
('FORMAL_RECEIPT','EXECUTE','formal_receipt.manage',NULL,4),
('FORMAL_RECEIPT','DELETE','formal_receipt.reverse',NULL,5);

-- 正式利润核算
INSERT INTO system_feature_actions(feature_code, action_kind, permission_code, variant, sort_order) VALUES
('FORMAL_PROFIT','VIEW','formal_profit.read.self','SELF',1),
('FORMAL_PROFIT','VIEW','formal_profit.read.department','DEPARTMENT',2),
('FORMAL_PROFIT','VIEW','formal_profit.read.all','ALL',3),
('FORMAL_PROFIT','EXECUTE','formal_profit.manage',NULL,4);

-- 费用报销（旧）：仅本人查看变体
INSERT INTO system_feature_actions(feature_code, action_kind, permission_code, variant, sort_order) VALUES
('EXPENSE','VIEW','expense.read.self','SELF',1),
('EXPENSE','EXECUTE','expense.manage',NULL,2);

-- 业务利润（旧）：仅本人查看变体
INSERT INTO system_feature_actions(feature_code, action_kind, permission_code, variant, sort_order) VALUES
('PROFIT','VIEW','profit.read.self','SELF',1),
('PROFIT','EXECUTE','profit.manage',NULL,2);

-- 提成规则（旧）：仅本人查看变体
INSERT INTO system_feature_actions(feature_code, action_kind, permission_code, variant, sort_order) VALUES
('COMMISSION','VIEW','commission.read.self','SELF',1),
('COMMISSION','EXECUTE','commission.manage',NULL,2),
('COMMISSION','EXECUTE','commission.settle',NULL,3);

-- 月度提成核算
INSERT INTO system_feature_actions(feature_code, action_kind, permission_code, variant, sort_order) VALUES
('MONTHLY_COMMISSION','VIEW','monthly_commission.read.self','SELF',1),
('MONTHLY_COMMISSION','VIEW','monthly_commission.read.department','DEPARTMENT',2),
('MONTHLY_COMMISSION','VIEW','monthly_commission.read.all','ALL',3),
('MONTHLY_COMMISSION','EXECUTE','monthly_commission.manage',NULL,4),
('MONTHLY_COMMISSION','EXECUTE','employee_commission.manage',NULL,5);

-- 销售订单财务验收（无读动作，纯执行）
INSERT INTO system_feature_actions(feature_code, action_kind, permission_code, variant, sort_order) VALUES
('ORDER_ACCEPTANCE','EXECUTE','order_acceptance.manage',NULL,1);

-- 生产制造
INSERT INTO system_feature_actions(feature_code, action_kind, permission_code, variant, sort_order) VALUES
('MANUFACTURING','VIEW','manufacturing.read.self','SELF',1),
('MANUFACTURING','VIEW','manufacturing.read.department','DEPARTMENT',2),
('MANUFACTURING','VIEW','manufacturing.read.all','ALL',3),
('MANUFACTURING','CREATE','manufacturing.manage',NULL,4),
('MANUFACTURING','EXECUTE','manufacturing.receive',NULL,5),
('MANUFACTURING','EXECUTE','manufacturing.report',NULL,6),
('MANUFACTURING','EXECUTE','manufacturing.inspect',NULL,7),
('MANUFACTURING','EXECUTE','manufacturing.schedule',NULL,8),
('MANUFACTURING','EXECUTE','manufacturing.issue.report',NULL,9),
('MANUFACTURING','EXECUTE','manufacturing.issue.manage',NULL,10),
('MANUFACTURING','EXECUTE','manufacturing.material.manage',NULL,11),
('MANUFACTURING','EXECUTE','manufacturing.template.manage',NULL,12),
('MANUFACTURING','DELETE','manufacturing.receive.reverse',NULL,13),
('MANUFACTURING','DELETE','manufacturing.report.override',NULL,14);

-- 原材料档案与库存（单读码，无范围变体）
INSERT INTO system_feature_actions(feature_code, action_kind, permission_code, variant, sort_order) VALUES
('MATERIAL_INVENTORY','VIEW','material_inventory.read',NULL,1),
('MATERIAL_INVENTORY','EXECUTE','material_inventory.manage',NULL,2);

-- 产品库存（单读码，无范围变体）
INSERT INTO system_feature_actions(feature_code, action_kind, permission_code, variant, sort_order) VALUES
('PRODUCT_INVENTORY','VIEW','product_inventory.read',NULL,1),
('PRODUCT_INVENTORY','EXECUTE','product_inventory.manage',NULL,2);

-- 氚云产品来源解析
INSERT INTO system_feature_actions(feature_code, action_kind, permission_code, variant, sort_order) VALUES
('PRODUCT_SOURCE','EXECUTE','sales_product_source.resolve',NULL,1);

-- 平台管理（单管理动作）
INSERT INTO system_feature_actions(feature_code, action_kind, permission_code, variant, sort_order) VALUES
('PRODUCT_CENTER','EXECUTE','product.manage',NULL,1),
('PACKAGING','EXECUTE','packaging.manage',NULL,1),
('MOLD','EXECUTE','mold.manage',NULL,1),
('COMPANY','EXECUTE','company.manage',NULL,1),
('CERTIFICATE','EXECUTE','certificate.manage',NULL,1),
('OFFICE_ASSET','EXECUTE','officeAsset.manage',NULL,1),
('MEDIA','EXECUTE','media.manage',NULL,1),
('FILE_LIBRARY','EXECUTE','file.manage',NULL,1),
('WEBSITE','EXECUTE','website.manage',NULL,1),
('CUSTOMER','EXECUTE','customer.manage',NULL,1),
('SYSTEM_CONFIG','EXECUTE','system.config.manage',NULL,1),
('EMPLOYEE_ADMIN','EXECUTE','system.employee.manage',NULL,1),
('H3_INTEGRATION','EXECUTE','integration.h3',NULL,1),
('DINGTALK_INTEGRATION','EXECUTE','integration.dingtalk',NULL,1);

-- ---------- 3. 角色授权行（system_role_feature_grants） ----------
CREATE TABLE system_role_feature_grants (
    role_id BIGINT NOT NULL,
    feature_code VARCHAR(50) NOT NULL,
    data_scope VARCHAR(20) NULL COMMENT 'SELF/DEPARTMENT/ALL/CUSTOM；仅对含范围变体的功能有效',
    custom_employee_ids VARCHAR(1000) NULL COMMENT '自定义数据范围：员工 id 列表（JSON 数组）',
    custom_department_names VARCHAR(1000) NULL COMMENT '自定义数据范围：部门名称列表（JSON 数组）',
    selected_actions VARCHAR(1000) NOT NULL COMMENT '逗号分隔：VIEW 表示查看动作，其余为该功能下勾选的 permission_code',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (role_id, feature_code),
    CONSTRAINT fk_srfg_role FOREIGN KEY (role_id) REFERENCES system_roles(id),
    CONSTRAINT fk_srfg_feature FOREIGN KEY (feature_code) REFERENCES system_features(feature_code)
) COMMENT='角色 × 功能授权：数据范围 + 自定义名单 + 操作动作（保存时展开回写 role_permissions）';

-- 存量角色平移：从现有 role_permissions 反推出每个角色在每项功能上的
-- 数据范围（取已拥有 read 变体码的最高级）与勾选动作（查看/其他权限码）。
INSERT INTO system_role_feature_grants(role_id, feature_code, data_scope, selected_actions)
SELECT o.role_id, o.feature_code,
       CASE WHEN SUM(o.variant = 'ALL') > 0 THEN 'ALL'
            WHEN SUM(o.variant = 'DEPARTMENT') > 0 THEN 'DEPARTMENT'
            WHEN SUM(o.variant = 'SELF') > 0 THEN 'SELF'
            ELSE NULL END,
       GROUP_CONCAT(DISTINCT o.token ORDER BY IF(o.token = 'VIEW', 0, o.sort_order + 100) SEPARATOR ',')
FROM (
    SELECT DISTINCT rp.role_id, fa.feature_code, fa.permission_code,
           IF(fa.variant IS NULL, fa.permission_code, 'VIEW') AS token,
           fa.variant, fa.sort_order
    FROM system_role_permissions rp
    JOIN system_permissions sp ON sp.id = rp.permission_id
    JOIN system_feature_actions fa ON fa.permission_code = sp.permission_code
) o
GROUP BY o.role_id, o.feature_code;

-- ---------- 4. 数据治理：生产跟单角色收敛数据范围 ----------
-- 历史配置把生产跟单的"查看生产任务"授予到 ALL（read.all），叠加角色级
-- data_scope=ALL，导致胡健/陈苏平可跨部门看到全部生产任务及其中关联的
-- 客户/业务员信息。新模型下收敛为本部门可见，操作仅保留"查看/报工/异常上报"。
UPDATE system_role_feature_grants g
JOIN system_roles r ON r.id = g.role_id
SET g.data_scope = 'DEPARTMENT'
WHERE r.role_code = 'PRODUCTION_TRACER' AND g.feature_code = 'MANUFACTURING';

-- 保持 role_permissions 物化视图与 grants 一致：生产跟单仅保留 read.department 与报工/异常码
DELETE rp FROM system_role_permissions rp
JOIN system_roles r ON r.id = rp.role_id
WHERE r.role_code = 'PRODUCTION_TRACER'
  AND rp.permission_id IN (SELECT p.id FROM system_permissions p WHERE p.permission_code IN
    ('manufacturing.read.self','manufacturing.read.all','manufacturing.receive','manufacturing.inspect'));

-- 说明：system_roles.data_scope 保留兼容（部分旧代码引用），新配置一律以本表为准。
