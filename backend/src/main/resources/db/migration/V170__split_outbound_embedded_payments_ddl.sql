-- 六层架构落地 · 第2步：剥离出库内嵌收款（OUTBOUND_EMBEDDED）出正式 receipts 口径
--
-- 现状数据规模（2026-08-26 探查）：
--   OUTBOUND_EMBEDDED 共 92,625 条 receipt / 2.8066 亿元，100% 1:1 对应到单张 sales_outbound。
--   是"出库登记时业务员随手填在出库单身上的 received_amount"的镜像收据，不是银行真实到账。
--   其中 29.9% (27,657 条) 与出库单列 received_amount 已对不上（双方各自改动），正是对账混乱之源。
--   且 10,474 张出库登记了 2~19 笔"分期子收款"（receipt_no 后缀 #R1/#R2…），样例 SUM 正好等于出库 received_amount。
--   MULTI_OUTBOUND + SINGLE_OUTBOUND（合计 6,117 条 / 4,298 万）保留为正式到账单层。
--
-- 本步策略（用户拍板: 3=B 先打标保留，不立即删除）：
--   · 新建 sales_outbound_payment_snapshots，按 (出库, 分期序号) 存 92,625 条 OUTBOUND_EMBEDDED，
--     追溯主键 legacy_receipt_id 保留，原 receipts 行打 legacy_migrated_tag 标记后不物理删除；
--   · receipt_allocations 加 allocation_origin 列，对应 92,625 条镜像 allocation 打
--     'OUTBOUND_EMBEDDED'，后续 SettlementLedgerService 统一过滤白名单（DIRECT）。

CREATE TABLE sales_outbound_payment_snapshots (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT 'PK',
    sales_outbound_id BIGINT NOT NULL COMMENT '对应销售出库ID（FK -> sales_outbounds.id）',
    installment_index INT NOT NULL DEFAULT 1 COMMENT '第 N 笔登记：对应 CKxxx#Rn 的 n，1 张出库允许多笔分期登记',
    legacy_receipt_id BIGINT NOT NULL COMMENT '追溯：原 receipts.id（迁移后对应行已打 migrated 标记）',
    legacy_external_source VARCHAR(30) NOT NULL COMMENT '追溯：原 external_source = H3YUN_OUTBOUND_EMBED',
    legacy_external_id VARCHAR(100) NOT NULL COMMENT '追溯：原 external_id = OE:...',
    legacy_receipt_no VARCHAR(50) NOT NULL COMMENT '追溯：原 receipt_no = CKxxxxxxxx#R1 / #R2 ...',
    legacy_status VARCHAR(30) NULL COMMENT '追溯：原 receipts.status（CONFIRMED/PENDING）',
    receipt_date DATE NULL COMMENT '登记收款日期（原 receipts.receipt_date）',
    received_amount DECIMAL(14,2) NOT NULL COMMENT '本笔登记金额；全出库合计不一定等于出库.received_amount（29.9% 历史偏差）',
    payment_method VARCHAR(100) NULL COMMENT '支付方式（原 receipts.payment_method，基本 NULL）',
    source_created_by VARCHAR(100) NULL COMMENT '源单登记业务员（原 receipts.source_created_by）',
    source_created_at DATETIME NULL COMMENT '源系统创建时间（原 receipts.source_created_at）',
    remark VARCHAR(1000) NULL COMMENT '备注（原 receipts.remark）',
    migration_batch VARCHAR(30) NOT NULL DEFAULT 'V170' COMMENT '迁移批次标识（V170+ 之后迁移补录用更高版本号）',
    migrated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '迁移执行时间戳',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '本表行创建时间',
    UNIQUE KEY uk_so_ps_installment (sales_outbound_id, installment_index) COMMENT '1 张出库的第 n 笔登记唯一',
    UNIQUE KEY uk_so_ps_legacy_receipt (legacy_receipt_id) COMMENT '追溯：旧 receipts.id 唯一映射',
    KEY idx_so_ps_date (receipt_date) COMMENT '按收款日期查询（内嵌款自身的账龄追溯）'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='出库内嵌收款快照（原 receipts.OUTBOUND_EMBEDDED 迁出，不计入正式银行回款口径）';

-- receipt_allocations 打来源标签，供统一回款服务白名单过滤
-- DIRECT            = MULTI_OUTBOUND / SINGLE_OUTBOUND（真到账单分配 = 正式回款）
-- OUTBOUND_EMBEDDED = OUTBOUND_EMBEDDED（出库内嵌镜像分配 = 默认不计入正式回款）
ALTER TABLE receipt_allocations
    ADD COLUMN allocation_origin VARCHAR(30) NOT NULL DEFAULT 'DIRECT'
        COMMENT 'DIRECT=银行到账单分配（默认计入正式回款）；OUTBOUND_EMBEDDED=出库内嵌镜像分配（默认不计入）'
    AFTER allocation_status,
    ADD KEY idx_ra_origin (allocation_origin);

-- receipts 行级迁移标记：MIGRATED_OUT_TO_SNAPSHOT = 已迁至 sales_outbound_payment_snapshots
-- 保留行本身用于追溯，但所有页面 / 统计消费端必须按 legacy_migrated_tag IS NULL 过滤（=正式回款白名单）
ALTER TABLE receipts
    ADD COLUMN legacy_migrated_tag VARCHAR(50) NULL
        COMMENT 'NULL=正式回款；MIGRATED_OUT_TO_SNAPSHOT=已迁至 sales_outbound_payment_snapshots（排除）'
    AFTER source_hash,
    ADD KEY idx_receipts_migrated_tag (legacy_migrated_tag);
