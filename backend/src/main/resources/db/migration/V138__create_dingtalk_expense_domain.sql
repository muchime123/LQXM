-- P6.1 钉钉日常报销同步接入：只读事实层 + 同步审计
-- 核心边界（P6.1 只做这些，其余留 P6.2）：
--   1. expense_claims  / expense_items    ← 钉钉日常报销只读镜像（不允许业务层直接改）
--   2. dingtalk_sync_runs                  ← 每次同步的审计记录（成功数/失败数/错误日志）
--   3. P6.1 不建 expense_allocations（费用归集表）→ P6.2 再上；也不做对公付款类数据
-- 设计参考：
--   - 与 V127 H3 fact 表一致的 source 追溯字段链：external_source/external_id + source_created_by/at + source_hash + last_synced_at
--   - 员工关联：employee_id 指向 employees（允许 NULL = 未匹配到员工花名册）；同时保留 dingtalk_user_id 原始键 + submitter_name 快照（P6.1 同步时按 DINGTALK userId 找 mapping，失败时再按姓名兜底，命中则写入 employee_external_mappings，但不会创建新员工）
--   - 所有 CREATE TABLE IF NOT EXISTS，Flyway 重跑安全

-- ============================================================
-- 1. 报销单主表（1 条钉钉报销审批单 = 1 条 claim）
-- ============================================================
CREATE TABLE IF NOT EXISTS expense_claims (
    id                          BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '内部报销单 ID',
    external_source             VARCHAR(30)  NOT NULL DEFAULT 'DINGTALK' COMMENT '来源系统：DINGTALK 钉钉 / MANUAL 人工录入 / OTHER',
    external_id                 VARCHAR(100) NOT NULL COMMENT '外部单据 ID：钉钉 = processInstanceId（审批实例 ID）',
    claim_no                    VARCHAR(100) NULL COMMENT '单据编号：钉钉审批单号 or 自定义',
    title                       VARCHAR(300) NULL COMMENT '标题 / 事由摘要',
    -- 归属人（谁发生的这笔费用，默认 = 审批提单人）
    employee_id                 BIGINT       NULL COMMENT '关联 employees.id；NULL 表示未匹配到员工花名册（需管理员手动归位）',
    dingtalk_user_id            VARCHAR(100) NULL COMMENT '钉钉提单人 userId，兜底匹配 & 溯源用',
    submitter_name_snapshot     VARCHAR(100) NULL COMMENT '提单人姓名快照（展示 & 兜底匹配）',
    submitter_dept_snapshot     VARCHAR(200) NULL COMMENT '提单人部门快照',
    -- 审批与分类
    approval_status             VARCHAR(30)  NOT NULL DEFAULT 'PENDING' COMMENT '审批状态：APPROVED 已通过 / REJECTED 已拒绝 / PENDING 审批中 / CANCELED 已撤销 / TERMINATED 终止',
    expense_category            VARCHAR(50)  NULL COMMENT '费用大类：TRAVEL 差旅 / OFFICE 办公 / ENTERTAIN 业务招待 / TRANSPORT 交通 / MEAL 餐饮 / OTHER 其他 / CORP_PAY 对公付款（P6.2再使用）',
    claim_date                  DATE         NULL COMMENT '报销发生日（取明细最早发生日；若无则审批发起日）',
    approval_finished_date      DATE         NULL COMMENT '审批最终完成日',
    -- 金额
    total_amount                DECIMAL(14,2) NULL COMMENT '申请总金额',
    approved_amount             DECIMAL(14,2) NULL COMMENT '核定总金额（审批通过的金额）',
    currency                    VARCHAR(10)  NOT NULL DEFAULT 'CNY' COMMENT '币种，默认人民币',
    -- 附件 & 备注
    remark                      VARCHAR(1000) NULL COMMENT '备注',
    attachment_info             LONGTEXT     NULL COMMENT '附件信息（JSON 数组：钉钉钉附件 ID/URL/文件名）',
    -- 来源追溯（与 H3 fact 表同构）
    department_name             VARCHAR(200) NULL COMMENT '提单人部门名（冗余）',
    source_created_by           VARCHAR(100) NULL COMMENT '来源系统创建人',
    source_created_at           DATETIME     NULL COMMENT '来源系统创建时间（审批发起时间）',
    source_updated_at           DATETIME     NULL COMMENT '来源系统最后更新时间（审批状态变更时间）',
    source_hash                 CHAR(64)     NULL COMMENT '来源数据内容哈希（SHA-256），用于判断是否需要更新',
    last_synced_at              DATETIME     NULL COMMENT '最后一次同步到本地的时间',
    -- 审计 & 时间戳
    created_at                  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at                  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_ec_employee FOREIGN KEY (employee_id) REFERENCES employees(id) ON DELETE SET NULL,
    UNIQUE KEY uk_ec_external (external_source, external_id),
    KEY idx_ec_employee (employee_id),
    KEY idx_ec_dingtalk_user (dingtalk_user_id),
    KEY idx_ec_status (approval_status),
    KEY idx_ec_category (expense_category),
    KEY idx_ec_claim_date (claim_date),
    KEY idx_ec_finished_date (approval_finished_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='费用报销单（主表，钉钉同步只读事实层）';


-- ============================================================
-- 2. 报销单明细（1 条 claim 可能对应 N 条消费明细；钉钉报销平台 = 多明细项）
-- ============================================================
CREATE TABLE IF NOT EXISTS expense_items (
    id                      BIGINT PRIMARY KEY AUTO_INCREMENT,
    expense_claim_id        BIGINT       NOT NULL COMMENT '关联 expense_claims.id',
    external_id             VARCHAR(100) NULL COMMENT '外部明细 ID（钉钉明细消费项 ID）',
    sequence_no             INT          NULL COMMENT '序号（同一 claim 内顺序）',
    expense_type            VARCHAR(50)  NULL COMMENT '明细费用类型：差旅/住宿/机票/高铁/出租/餐饮/办公用品/招待/… （钉钉原始类型）',
    category_snapshot       VARCHAR(50)  NULL COMMENT '归集分类快照（与 claim.expense_category 保持一致或细分）',
    item_date               DATE         NULL COMMENT '该条消费的发生日期',
    item_desc               VARCHAR(500) NULL COMMENT '消费摘要 / 说明',
    -- 金额
    amount                  DECIMAL(14,2) NULL COMMENT '明细金额',
    tax_amount              DECIMAL(14,2) NULL COMMENT '税额（可选；钉钉部分消费有）',
    -- 票据
    invoice_no              VARCHAR(100) NULL COMMENT '发票号（若有）',
    receipt_type            VARCHAR(50)  NULL COMMENT '票据类型：发票 / 收据 / 其他',
    -- 费用承担归属（P6.1 只读同步不做归集；P6.2 通过 expense_allocations 表建立多业务分摊关系）
    -- 注意：P6.1 不要在这里直接放 business_order_id，因为 ChatGPT 明确要求 UI 支持 1 条费用 → 多笔 allocation，键必须在独立关联表
    remark                  VARCHAR(1000) NULL COMMENT '该明细行备注',
    attachment_info         LONGTEXT     NULL COMMENT '明细级附件（发票照片等）',
    created_at              DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at              DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_ei_claim FOREIGN KEY (expense_claim_id) REFERENCES expense_claims(id) ON DELETE CASCADE,
    KEY idx_ei_claim (expense_claim_id),
    KEY idx_ei_external (external_id),
    KEY idx_ei_item_date (item_date),
    KEY idx_ei_type (expense_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='费用报销明细（钉钉消费明细项，只读同步）';


-- ============================================================
-- 3. 钉钉同步运行审计（每次手动 / 定时同步 = 1 条 run，记录成功数/失败数/错误明细）
-- ============================================================
CREATE TABLE IF NOT EXISTS dingtalk_sync_runs (
    id                  BIGINT PRIMARY KEY AUTO_INCREMENT,
    sync_type           VARCHAR(40)  NOT NULL DEFAULT 'DAILY_REIMBURSEMENT' COMMENT '同步类型：DAILY_REIMBURSEMENT 日常报销 / EMPLOYEE_ROSTER 员工花名册（P6.1只做前者）',
    status              VARCHAR(20)  NOT NULL DEFAULT 'RUNNING' COMMENT 'RUNNING / SUCCESS / PARTIAL / FAILED / SKIPPED',
    triggered_by        VARCHAR(40)  NOT NULL DEFAULT 'MANUAL' COMMENT 'MANUAL 手动 / SCHEDULED 定时 / API 外部触发',
    triggered_by_name   VARCHAR(100) NULL COMMENT '触发操作人姓名（若有）',
    -- 时间
    started_at          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    finished_at         DATETIME     NULL,
    duration_ms         BIGINT       NULL COMMENT '运行耗时毫秒',
    -- 计数器
    fetched_count       INT          NOT NULL DEFAULT 0 COMMENT '本次从钉钉拉到的记录数',
    created_count       INT          NOT NULL DEFAULT 0 COMMENT '本地新增记录数',
    updated_count       INT          NOT NULL DEFAULT 0 COMMENT '本地更新记录数（source_hash 变化）',
    unchanged_count     INT          NOT NULL DEFAULT 0 COMMENT '无变化跳过数',
    failed_count        INT          NOT NULL DEFAULT 0 COMMENT '处理失败条数',
    mapping_missed      INT          NOT NULL DEFAULT 0 COMMENT '员工身份未匹配数（提醒管理员去员工映射页处理）',
    -- 错误
    error_summary       VARCHAR(500) NULL COMMENT '首个错误概要（列表展示用）',
    error_details       LONGTEXT     NULL COMMENT '错误明细（JSON 数组：每条 external_id+错误消息+栈）',
    -- 同步范围参数（下次重跑同范围用）
    range_date_from     DATE         NULL,
    range_date_to       DATE         NULL,
    extra_params        VARCHAR(1000) NULL COMMENT '附加参数 JSON（预留）',
    created_at          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_dsr_type_started (sync_type, started_at),
    KEY idx_dsr_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='钉钉同步运行审计（每次同步 1 条，含错误明细）';
