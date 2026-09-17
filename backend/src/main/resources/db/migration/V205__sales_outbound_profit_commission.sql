-- ================================================================================
-- 销售出库利润与提成主链（方案：docs/current-sales-profit-design.md）
--  1. 费用归单目标扩展：expense_allocations 支持 target_type=SALES_OUTBOUND
--     （PENDING/CONFIRMED/REJECTED/REVERSED + match_method/confidence/confirmed_by/confirmed_at）
--  2. 订单利润：sales_outbound_profits（每张出库一条当前结果）
--  3. 利润调整：sales_outbound_profit_adjustments
--  4. 订单提成：sales_outbound_commissions（每张出库一条试算/可发/已发结果）
-- 原则：不修改已执行迁移；旧 business_orders 链只读冻结，新代码不再产生运行时依赖。
-- ================================================================================

-- ---------- 1) 费用归单扩展 SALES_OUTBOUND 目标 ----------
ALTER TABLE expense_allocations DROP CHECK chk_ea_target;
ALTER TABLE expense_allocations ADD CONSTRAINT chk_ea_target CHECK (target_type IN ('BUSINESS_ORDER','DEPARTMENT','COMPANY','CORP_PAYMENT','SALES_OUTBOUND'));

ALTER TABLE expense_allocations DROP CHECK chk_ea_status;
ALTER TABLE expense_allocations ADD CONSTRAINT chk_ea_status CHECK (allocation_status IN ('DRAFT','PENDING','CONFIRMED','REJECTED','REVERSED'));

ALTER TABLE expense_allocations
    ADD COLUMN match_method   VARCHAR(20)  NULL COMMENT '关联方式：MANUAL=人工选择 / EXACT_OUTBOUND_NO=唯一完整出库编号识别 / RECOMMENDED=模糊推荐（不得自动确认）' AFTER target_name_snapshot,
    ADD COLUMN confidence     INT          NULL COMMENT '推荐置信度 0~100；EXACT_OUTBOUND_NO 为 100' AFTER match_method,
    ADD COLUMN confirmed_by   VARCHAR(100) NULL COMMENT '确认人（PENDING→CONFIRMED 时记录）' AFTER confidence,
    ADD COLUMN confirmed_at   DATETIME     NULL COMMENT '确认时间' AFTER confirmed_by;

-- ---------- 2) 订单利润 ----------
CREATE TABLE IF NOT EXISTS sales_outbound_profits (
    id                       BIGINT        PRIMARY KEY AUTO_INCREMENT,
    sales_outbound_id        BIGINT        NOT NULL COMMENT '唯一核算单元 = sales_outbounds.id',
    sales_amount             DECIMAL(14,2) NOT NULL DEFAULT 0 COMMENT '氚云最终销售额（主表）',
    purchase_amount          DECIMAL(14,2) NOT NULL DEFAULT 0 COMMENT '氚云最终采购成本（主表）',
    product_gross_profit     DECIMAL(14,2) NOT NULL DEFAULT 0 COMMENT '产品毛利 = 销售额 - 采购成本',
    confirmed_expense_amount DECIMAL(14,2) NOT NULL DEFAULT 0 COMMENT '已确认订单费用合计（CONFIRMED 归集到本出库）',
    adjustment_amount        DECIMAL(14,2) NOT NULL DEFAULT 0 COMMENT '利润调整净额',
    accounting_profit        DECIMAL(14,2) NOT NULL DEFAULT 0 COMMENT '订单实际利润 = 产品毛利 - 订单费用 + 调整',
    non_commissionable_amount DECIMAL(14,2) NOT NULL DEFAULT 0 COMMENT '不参与提成金额',
    commissionable_profit    DECIMAL(14,2) NOT NULL DEFAULT 0 COMMENT '可计提利润 = MAX(实际利润 - 不参与提成金额, 0)',
    calculation_status       VARCHAR(20)   NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING=待计算 / CALCULATED=已计算 / ERROR=成本异常',
    cost_abnormal            VARCHAR(200)  NULL COMMENT '成本异常标记（采购成本为空/零/负数时记录）',
    formula_version          VARCHAR(20)   NOT NULL DEFAULT 'V1',
    calculated_at            DATETIME      NULL,
    created_at               DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at               DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_sop_outbound (sales_outbound_id),
    KEY idx_sop_status (calculation_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='销售出库订单利润（每张出库一条当前结果）';

-- ---------- 3) 利润调整 ----------
CREATE TABLE IF NOT EXISTS sales_outbound_profit_adjustments (
    id                  BIGINT         PRIMARY KEY AUTO_INCREMENT,
    sales_outbound_id   BIGINT         NOT NULL COMMENT '关联销售出库',
    adjustment_type     VARCHAR(50)    NOT NULL COMMENT '调整类型：如 折让/返利/质量扣款/手工修正',
    amount              DECIMAL(14,2)  NOT NULL COMMENT '调整金额（负数=扣减利润）',
    direction           VARCHAR(10)    NOT NULL DEFAULT 'PLUS' COMMENT 'PLUS=增加利润 / MINUS=减少利润',
    affects_commission  TINYINT(1)     NOT NULL DEFAULT 1 COMMENT '是否参与提成计算',
    reason              VARCHAR(500)   NOT NULL COMMENT '调整原因（必填，审计）',
    status              VARCHAR(20)    NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE=生效 / REVERSED=已撤销',
    created_by          VARCHAR(100)   NULL,
    created_at          DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    reversed_by         VARCHAR(100)   NULL,
    reversed_at         DATETIME       NULL,
    KEY idx_sopa_outbound (sales_outbound_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='销售出库利润调整（人工干预留痕）';

-- ---------- 4) 订单提成 ----------
CREATE TABLE IF NOT EXISTS sales_outbound_commissions (
    id                     BIGINT         PRIMARY KEY AUTO_INCREMENT,
    sales_outbound_id      BIGINT         NOT NULL COMMENT '唯一核算单元 = sales_outbounds.id',
    employee_id            BIGINT         NULL COMMENT '业务员（sales_outbounds.employee_id）',
    profit_id              BIGINT         NULL COMMENT 'sales_outbound_profits.id',
    rule_id                BIGINT         NULL COMMENT '提成规则 id（business_commission_rules.id）',
    commissionable_profit  DECIMAL(14,2)  NOT NULL DEFAULT 0 COMMENT '可计提利润',
    commission_rate        DECIMAL(7,4)   NOT NULL DEFAULT 0 COMMENT '提成比例（%）',
    theoretical_commission DECIMAL(14,2)  NOT NULL DEFAULT 0 COMMENT '理论提成 = 可计提利润 × 比例',
    receipt_ratio_pct      DECIMAL(7,2)   NOT NULL DEFAULT 0 COMMENT '回款率（已收/应收）',
    available_commission   DECIMAL(14,2)  NOT NULL DEFAULT 0 COMMENT '当前可发提成（结合回款条件）',
    settled_commission     DECIMAL(14,2)  NOT NULL DEFAULT 0 COMMENT '已发提成（结算单金额）',
    status                 VARCHAR(20)    NOT NULL DEFAULT 'TRIAL' COMMENT 'TRIAL=试算 / WAITING_RULE=未配置规则 / NOT_AVAILABLE=未满足回款条件 / AVAILABLE=可结算 / SETTLED=已结算',
    formula_version        VARCHAR(20)    NOT NULL DEFAULT 'V1',
    calculated_at          DATETIME       NULL,
    created_at             DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at             DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_soc_outbound (sales_outbound_id),
    KEY idx_soc_employee (employee_id),
    KEY idx_soc_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='销售出库订单提成（每张出库一条）';

-- ---------- 5) 新链权限码与角色分配（方案 §13） ----------
INSERT IGNORE INTO system_permissions(permission_code,permission_name) VALUES
 ('sales_order.read.self','查看本人销售订单'),
 ('sales_order.read.department','查看本部门销售订单'),
 ('sales_order.read.all','查看全部销售订单'),
 ('sales_order.manage','管理销售订单'),
 ('order_expense.read.self','查看本人订单费用'),
 ('order_expense.read.department','查看本部门订单费用'),
 ('order_expense.manage','确认订单费用'),
 ('order_profit.read.self','查看本人订单利润'),
 ('order_profit.read.department','查看本部门订单利润'),
 ('order_profit.read.all','查看全部订单利润'),
 ('order_profit.manage','管理订单利润调整'),
 ('order_commission.read.self','查看本人订单提成'),
 ('order_commission.read.department','查看本部门订单提成'),
 ('order_commission.read.all','查看全部订单提成'),
 ('order_commission.manage','管理提成规则'),
 ('order_commission.settle','结算订单提成');

INSERT IGNORE INTO system_role_permissions SELECT r.id,p.id FROM system_roles r CROSS JOIN system_permissions p
 WHERE r.role_code IN ('ADMIN','BOSS');
INSERT IGNORE INTO system_role_permissions SELECT r.id,p.id FROM system_roles r JOIN system_permissions p
 ON p.permission_code IN ('sales_order.read.department','sales_order.manage','order_expense.read.department','order_profit.read.department','order_commission.read.department')
 WHERE r.role_code='SALES_MANAGER';
INSERT IGNORE INTO system_role_permissions SELECT r.id,p.id FROM system_roles r JOIN system_permissions p
 ON p.permission_code IN ('sales_order.read.self','order_expense.read.self','order_profit.read.self','order_commission.read.self')
 WHERE r.role_code='SALESPERSON';
INSERT IGNORE INTO system_role_permissions SELECT r.id,p.id FROM system_roles r JOIN system_permissions p
 ON p.permission_code IN ('sales_order.read.all','order_expense.manage','order_profit.read.all','order_profit.manage','order_commission.read.all','order_commission.manage','order_commission.settle')
 WHERE r.role_code='FINANCE';
