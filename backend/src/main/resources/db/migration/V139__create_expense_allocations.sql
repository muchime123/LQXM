-- P6.2 费用归集：expense_claims → 多目标（业务单 / 部门 / 公司 / 对公付款）分配
-- 设计参考：§3.8 receipt_allocations 模式（来源 id + 目标 id + allocated_amount + allocation_status + 快照字段）
-- 服务层强校验：
--   1. 同 claim 下所有 allocation_status='CONFIRMED' 的 allocated_amount 合计 ≤ COALESCE(claim.approved_amount, claim.total_amount, 0)
--   2. allocation_ratio_pct（若非空）× claim 金额 ≈ allocated_amount（允许 ±0.01 舍入差）
--   3. target_type ∈ {BUSINESS_ORDER, DEPARTMENT, COMPANY, CORP_PAYMENT}，对应 target_id 语义不同
-- 撤销不是物理删：UPDATE allocation_status='REVERSED' 并加 remark；不允许直接删除已 CONFIRMED 的分配（改 DB 层权限由 Service 拦截）

CREATE TABLE IF NOT EXISTS expense_allocations (
    id                      BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '内部分配 ID',
    -- ====== 来源：报销单 & 明细项 ======
    expense_claim_id        BIGINT       NOT NULL COMMENT '报销单主表 ID（必传；即使按明细分配也保留 claim 聚合索引）',
    expense_item_id         BIGINT       NULL COMMENT '报销单明细 ID；NULL=按 claim 全单整体归集（P6.2 先支持 claim 粒度，item 留扩展）',
    -- ====== 目标：多态 4 类 ======
    target_type             VARCHAR(30)  NOT NULL COMMENT '归集目标类型：BUSINESS_ORDER=统一业务单（利润核算用）/ DEPARTMENT=部门费用（管理会计）/ COMPANY=公司承担（期间费用）/ CORP_PAYMENT=对公付款（待对账核销）',
    target_id               BIGINT       NOT NULL COMMENT '目标对象内部 ID：BUSINESS_ORDER=business_orders.id / DEPARTMENT=部门ID / COMPANY=公司ID / CORP_PAYMENT=对公付款记录ID（若独立表）',
    target_no_snapshot      VARCHAR(100) NULL COMMENT '目标编号快照（业务单号/部门编号/对公付款流水号，便于列表展示不联表）',
    target_name_snapshot    VARCHAR(300) NULL COMMENT '目标名称快照（客户名/部门名/公司主体名，展示用）',
    -- ====== 分配量：比例与金额共存；UI 可以二选一录入，Service 自动补另一项 ======
    allocation_ratio_pct    DECIMAL(9,4) NULL COMMENT '分配百分比 0~100（例 33.3333 表示 33.3333%）；P6.2e 前端录入比例时自动换算金额，服务层校验',
    allocated_amount        DECIMAL(14,2) NOT NULL COMMENT '分配金额（核定金额维度；必须 ≥0）；所有已确认分配合计不得超过 claim.approved_amount',
    -- ====== 状态 & 备注 ======
    allocation_status       VARCHAR(20)  NOT NULL DEFAULT 'CONFIRMED' COMMENT '分配状态：DRAFT=草稿（未保存的临时行，P6.2e UI 态）/ CONFIRMED=已确认生效 / REVERSED=已撤销/红冲',
    remark                  VARCHAR(500) NULL COMMENT '备注：撤销原因、分摊说明等',
    -- ====== 审计 ======
    created_by              VARCHAR(100) NULL COMMENT '操作人（前端 X-User-Name 头）',
    created_at              DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at              DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    -- 外键
    CONSTRAINT fk_ea_claim FOREIGN KEY (expense_claim_id) REFERENCES expense_claims(id) ON DELETE CASCADE,
    CONSTRAINT fk_ea_item  FOREIGN KEY (expense_item_id)  REFERENCES expense_items(id)  ON DELETE SET NULL,
    -- 约束 & 索引
    CONSTRAINT chk_ea_amount CHECK (allocated_amount >= 0),
    CONSTRAINT chk_ea_ratio CHECK (allocation_ratio_pct IS NULL OR (allocation_ratio_pct >= 0 AND allocation_ratio_pct <= 100)),
    CONSTRAINT chk_ea_target CHECK (target_type IN ('BUSINESS_ORDER','DEPARTMENT','COMPANY','CORP_PAYMENT')),
    CONSTRAINT chk_ea_status CHECK (allocation_status IN ('DRAFT','CONFIRMED','REVERSED')),
    KEY idx_ea_claim (expense_claim_id),
    KEY idx_ea_item (expense_item_id),
    KEY idx_ea_target (target_type, target_id),
    KEY idx_ea_status (allocation_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='费用归集分配（1 条报销单 → 多业务/部门/公司/对公付款分摊）';
