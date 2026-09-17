-- =====================================================================
-- V160: 氚云业务匹配增强（生产↔出库匹配信号/可配置评分/裁决闭环 + 收款对账治理）
-- 对应设计: 阶段A 匹配信号增强 / 阶段B 裁决工作台 / 阶段C 收款分配治理 / 阶段D 质量
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. 匹配评分特征配置表（权重线上可调，不再硬编码进 SQL）
-- ---------------------------------------------------------------------
CREATE TABLE h3_match_feature (
    id          BIGINT PRIMARY KEY AUTO_INCREMENT,
    feature_key VARCHAR(40)  NOT NULL COMMENT '特征键: BASE/SAME_CUSTOMER_ID/SAME_SALESPERSON/PRODUCT_ALIGN/QUANTITY_ALIGN/TIME_PROXIMITY',
    label       VARCHAR(100) NOT NULL COMMENT '中文名',
    score       INT          NOT NULL DEFAULT 0 COMMENT '命中加分（BASE 为起步分）',
    enabled     TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '是否启用',
    sort        INT          NOT NULL DEFAULT 0,
    updated_by  VARCHAR(100) NULL,
    updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_hmf_key (feature_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='生产-出库匹配评分特征配置';

INSERT INTO h3_match_feature (feature_key, label, score, enabled, sort) VALUES
    ('BASE',              '基础分（同客户名+时间窗口）', 55, 1, 1),
    ('SAME_CUSTOMER_ID',  '客户档案 ID 等值',            25, 1, 2),
    ('SAME_SALESPERSON',  '归属业务员等值',              20, 1, 3),
    ('PRODUCT_ALIGN',     '产品名归一化互含',            20, 1, 4),
    ('QUANTITY_ALIGN',    '数量对齐（误差<=5%）',        25, 1, 5),
    ('TIME_PROXIMITY',    '时间接近（30天内）',          10, 1, 6),
    ('OUT_WINDOW',        '超窗提示（仅标记,基础分降级）', 0, 1, 7),
    ('CUSTOMER_HINT',     '客户产品匹配记忆提权',       15, 1, 8);

-- ---------------------------------------------------------------------
-- 2. 客户-产品匹配记忆（人工确认 3 次后自动提权，降低重复人工裁决）
-- ---------------------------------------------------------------------
CREATE TABLE h3_customer_product_hints (
    id               BIGINT PRIMARY KEY AUTO_INCREMENT,
    customer_id      BIGINT       NULL COMMENT '客户档案 ID（可为空，用归一化名兜底）',
    customer_name    VARCHAR(300) NULL,
    product_name     VARCHAR(300) NOT NULL COMMENT '生产/出库侧产品名',
    hit_count        INT          NOT NULL DEFAULT 0 COMMENT '人工确认命中次数',
    outbound_count   INT          NOT NULL DEFAULT 0,
    last_outbound_id BIGINT       NULL,
    enabled          TINYINT(1)   NOT NULL DEFAULT 1,
    updated_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_hcph (customer_id, product_name),
    KEY idx_hcph_name (customer_name, product_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='客户产品匹配记忆';

-- ---------------------------------------------------------------------
-- 3. 收款-出库金额兜底候选（REFER_UNRESOLVED 的二级人工裁决）
-- ---------------------------------------------------------------------
CREATE TABLE receipt_match_candidates (
    id                   BIGINT PRIMARY KEY AUTO_INCREMENT,
    receipt_id           BIGINT NOT NULL,
    receipt_allocation_id BIGINT NOT NULL COMMENT '指向未解析的 receipt_allocations',
    sales_outbound_id    BIGINT NOT NULL,
    confidence           INT          NOT NULL DEFAULT 0,
    match_reason         VARCHAR(500) NULL,
    status               VARCHAR(20)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/CONFIRMED/REJECTED',
    decided_by           VARCHAR(100) NULL,
    decided_at           DATETIME     NULL,
    feedback_notes       VARCHAR(500) NULL,
    created_at           DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at           DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_rmc_pair (receipt_allocation_id, sales_outbound_id),
    KEY idx_rmc_status (status), KEY idx_rmc_receipt (receipt_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='收款-出库金额兜底匹配候选';

-- ---------------------------------------------------------------------
-- 4. 生产-出库匹配候选补审计字段（裁决人/时间/备注，支持反馈闭环）
-- ---------------------------------------------------------------------
ALTER TABLE business_match_candidates
    ADD COLUMN decided_by     VARCHAR(100) NULL COMMENT '裁决人（确认/拒绝）' AFTER status,
    ADD COLUMN decided_at     DATETIME     NULL COMMENT '裁决时间' AFTER decided_by,
    ADD COLUMN feedback_notes VARCHAR(500) NULL COMMENT '裁决备注' AFTER decided_at;

-- ---------------------------------------------------------------------
-- 5. 导入批次质量统计列（阶段D4：数据健康度）
-- ---------------------------------------------------------------------
ALTER TABLE h3_import_batches
    ADD COLUMN missing_customer_count INT NOT NULL DEFAULT 0 COMMENT '缺客户名行数' AFTER failed_count,
    ADD COLUMN missing_date_count     INT NOT NULL DEFAULT 0 COMMENT '缺日期行数' AFTER missing_customer_count,
    ADD COLUMN missing_quantity_count INT NOT NULL DEFAULT 0 COMMENT '缺数量行数' AFTER missing_date_count,
    ADD COLUMN normal_compat_count    INT NOT NULL DEFAULT 0 COMMENT 'NORMAL 兼容解析行数' AFTER missing_quantity_count;

-- ---------------------------------------------------------------------
-- 6. 出库单实收口径修正配套列：记录氚云权威"已收款金额"与系统分配的差异
--    （source_received_amount 已于 V150 提供，此处仅补差异告警列）
-- ---------------------------------------------------------------------
ALTER TABLE sales_outbounds
    ADD COLUMN received_diff_amount DECIMAL(14,2) NULL COMMENT '权威值-系统分配差异（>0.01 告警）' AFTER source_received_amount;
