-- ============================================================================
-- ERP S0 沙箱验证骨架（隔离 schema：erp_s0_verify）
-- ============================================================================
-- 用途：把「ERP 建设前业务蓝图」T01–T18 与「单据契约」B01–B20 从合成案例表
--       变成可执行验证资产。本 schema 与 product_archive 完全隔离。
--
-- 定位：**这不是 ERP 生产表结构**，是 S0 假设下的最小可运行骨架，
--       只求「能证明规则自洽」，不求「生产可用」。
--
-- S0 假设（来自契约 §1）：
--   单一公司 / 同币种 / 标准商品 / 固定单位换算 / 零超量容差 /
--   禁止负库存 / 人工确认往来项目 / 订单不自动占用库存
--
-- 数量与金额一律 DECIMAL（定点），禁用浮点 —— 对应设计输入 C5（浮点残差）。
-- ============================================================================

DROP DATABASE IF EXISTS erp_s0_verify;
CREATE DATABASE erp_s0_verify DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
USE erp_s0_verify;

-- ---------------------------------------------------------------------------
-- 0. 主数据（最小子集）
-- ---------------------------------------------------------------------------
CREATE TABLE company (
  id            BIGINT       NOT NULL,
  code          VARCHAR(64)  NOT NULL,
  name          VARCHAR(200) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_company_code (code)
) ENGINE=InnoDB;

CREATE TABLE warehouse (
  id            BIGINT       NOT NULL,
  company_id    BIGINT       NOT NULL,
  code          VARCHAR(64)  NOT NULL,
  name          VARCHAR(200) NOT NULL,
  active        TINYINT(1)   NOT NULL DEFAULT 1,
  PRIMARY KEY (id),
  UNIQUE KEY uk_wh_company_code (company_id, code)
) ENGINE=InnoDB;

CREATE TABLE party (
  id            BIGINT       NOT NULL,
  code          VARCHAR(64)  NOT NULL,
  display_name  VARCHAR(200) NOT NULL,
  kind          VARCHAR(20)  NOT NULL,          -- ORGANIZATION / PERSON
  lifecycle     VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
  PRIMARY KEY (id),
  UNIQUE KEY uk_party_code (code)
) ENGINE=InnoDB;

-- 往来方在某个公司下的交易角色；应收应付按公司隔离（契约 T16）
CREATE TABLE party_company_role (
  id            BIGINT       NOT NULL,
  party_id      BIGINT       NOT NULL,
  company_id    BIGINT       NOT NULL,
  role          VARCHAR(20)  NOT NULL,          -- CUSTOMER / SUPPLIER
  PRIMARY KEY (id),
  UNIQUE KEY uk_role_party_company (party_id, company_id, role)
) ENGINE=InnoDB;

-- 单位换算按 SKU 定义（字典 §4）：不存在全局「箱→个」
CREATE TABLE sku (
  id              BIGINT       NOT NULL,
  code            VARCHAR(64)  NOT NULL,
  name            VARCHAR(200) NOT NULL,
  base_unit       VARCHAR(20)  NOT NULL,
  inventory_managed TINYINT(1) NOT NULL DEFAULT 1,
  PRIMARY KEY (id),
  UNIQUE KEY uk_sku_code (code)
) ENGINE=InnoDB;

CREATE TABLE sku_conversion (
  id              BIGINT        NOT NULL,
  sku_id          BIGINT        NOT NULL,
  from_unit       VARCHAR(20)   NOT NULL,
  to_unit         VARCHAR(20)   NOT NULL,
  numerator       DECIMAL(24,9) NOT NULL,
  denominator     DECIMAL(24,9) NOT NULL,
  revision        INT           NOT NULL DEFAULT 1,
  effective_from  DATE          NOT NULL,
  effective_to    DATE          NULL,
  status          VARCHAR(20)   NOT NULL DEFAULT 'ACTIVE',
  PRIMARY KEY (id),
  KEY idx_conv_sku (sku_id, from_unit, effective_from)
) ENGINE=InnoDB;

-- ---------------------------------------------------------------------------
-- 1. 单据（最小子集：采购订单/采购入库/销售订单/销售出库/退货/调拨/盘点）
-- ---------------------------------------------------------------------------
CREATE TABLE doc (
  id                BIGINT        NOT NULL,
  doc_type          VARCHAR(30)   NOT NULL,   -- PURCHASE_ORDER/PURCHASE_IN/SALES_ORDER/
                                              -- SALES_OUT/SALES_RETURN/PURCHASE_RETURN/
                                              -- TRANSFER_OUT/TRANSFER_IN/STOCKTAKE
  doc_no            VARCHAR(64)   NOT NULL,
  company_id        BIGINT        NOT NULL,
  party_role_id     BIGINT        NULL,
  business_date     DATE          NOT NULL,
  approval_status   VARCHAR(20)   NOT NULL DEFAULT 'DRAFT',   -- DRAFT/SUBMITTED/APPROVED/CANCELLED
  posting_status    VARCHAR(20)   NOT NULL DEFAULT 'UNPOSTED',-- UNPOSTED/POSTED
  source_doc_id     BIGINT        NULL,       -- 来源单据（退货→原单，分配→订单）
  rule_version      VARCHAR(32)   NOT NULL DEFAULT 'S0',
  -- 调拨专用：调出/调入仓（契约 §6.2 两腿同源）
  from_warehouse_id BIGINT        NULL,
  to_warehouse_id   BIGINT        NULL,
  -- 盘点专用：账面截点
  snapshot_at       DATETIME      NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_doc_no (company_id, doc_type, doc_no)
) ENGINE=InnoDB;

CREATE TABLE doc_line (
  id                BIGINT        NOT NULL,
  doc_id            BIGINT        NOT NULL,
  line_no           INT           NOT NULL,
  sku_id            BIGINT        NOT NULL,
  warehouse_id      BIGINT        NULL,
  quantity          DECIMAL(24,9) NOT NULL,   -- 交易数量（正数；方向由单据类型决定）
  unit              VARCHAR(20)   NOT NULL,   -- 交易单位
  base_quantity     DECIMAL(24,9) NOT NULL,   -- 基本单位数量（= 数量 × 比率）
  conversion_rev    INT           NULL,
  unit_price        DECIMAL(24,9) NOT NULL DEFAULT 0,
  net_amount        DECIMAL(24,9) NOT NULL DEFAULT 0,
  -- 执行量（来自下游分配）
  executed_base_qty DECIMAL(24,9) NOT NULL DEFAULT 0,
  closed_base_qty   DECIMAL(24,9) NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_doc_line (doc_id, line_no),
  KEY idx_line_sku (sku_id)
) ENGINE=InnoDB;

-- ---------------------------------------------------------------------------
-- 2. 库存事件与余额（契约 §6.1）
-- ---------------------------------------------------------------------------
-- 余额键：货权公司 + 仓库 + SKU + 基本单位（+ 质量状态）；不拼接展示名
CREATE TABLE stock_balance (
  id              BIGINT        NOT NULL AUTO_INCREMENT,
  company_id      BIGINT        NOT NULL,
  warehouse_id    BIGINT        NOT NULL,
  sku_id          BIGINT        NOT NULL,
  base_unit       VARCHAR(20)   NOT NULL,
  quality_status  VARCHAR(20)   NOT NULL DEFAULT 'AVAILABLE', -- AVAILABLE/INSPECT/IN_TRANSIT/RETURNED
  base_quantity   DECIMAL(24,9) NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_balance (company_id, warehouse_id, sku_id, base_unit, quality_status),
  KEY idx_balance_sku (sku_id)
) ENGINE=InnoDB;

-- 事件不可原位编辑；纠错生成反向事件（reverses_event_id）
CREATE TABLE stock_event (
  id                BIGINT        NOT NULL AUTO_INCREMENT,
  posting_group_id  VARCHAR(64)   NOT NULL,   -- 同一事务内多腿共享，用于守恒与去重
  doc_id            BIGINT        NOT NULL,
  doc_line_id       BIGINT        NOT NULL,
  event_type        VARCHAR(30)   NOT NULL,   -- RECEIPT/ISSUE/TRANSFER_OUT/TRANSFER_IN/
                                              -- RETURN_IN/RETURN_OUT/ADJUST/REVERSAL
  company_id        BIGINT        NOT NULL,
  warehouse_id      BIGINT        NOT NULL,
  sku_id            BIGINT        NOT NULL,
  base_unit         VARCHAR(20)   NOT NULL,
  quality_status    VARCHAR(20)   NOT NULL DEFAULT 'AVAILABLE',
  signed_quantity   DECIMAL(24,9) NOT NULL,   -- 有符号
  reverses_event_id BIGINT        NULL,
  occurred_at       DATETIME      NOT NULL,
  PRIMARY KEY (id),
  KEY idx_event_group (posting_group_id),
  KEY idx_event_doc (doc_id, doc_line_id)
) ENGINE=InnoDB;

-- ---------------------------------------------------------------------------
-- 3. 往来项目、资金、核销（契约 §7）
-- ---------------------------------------------------------------------------
CREATE TABLE ar_ap_item (
  id              BIGINT        NOT NULL,
  company_id      BIGINT        NOT NULL,
  party_id        BIGINT        NOT NULL,
  direction       VARCHAR(10)   NOT NULL,     -- AR / AP
  currency        VARCHAR(10)   NOT NULL DEFAULT 'CNY',
  source_doc_id   BIGINT        NOT NULL,     -- 唯一来源键含源事件（契约 §7.4）
  original_amount DECIMAL(24,9) NOT NULL,
  adjusted_amount DECIMAL(24,9) NOT NULL DEFAULT 0,
  settled_amount  DECIMAL(24,9) NOT NULL DEFAULT 0,
  rule_version    VARCHAR(32)   NOT NULL DEFAULT 'S0',
  PRIMARY KEY (id),
  UNIQUE KEY uk_item_source (company_id, source_doc_id, direction)
) ENGINE=InnoDB;

CREATE TABLE fund_doc (
  id            BIGINT        NOT NULL,
  company_id    BIGINT        NOT NULL,
  party_id      BIGINT        NOT NULL,
  direction     VARCHAR(10)   NOT NULL,       -- IN(收款) / OUT(付款)
  currency      VARCHAR(10)   NOT NULL DEFAULT 'CNY',
  amount        DECIMAL(24,9) NOT NULL,
  refunded_amount DECIMAL(24,9) NOT NULL DEFAULT 0,
  allocated_amount DECIMAL(24,9) NOT NULL DEFAULT 0,
  held_amount   DECIMAL(24,9) NOT NULL DEFAULT 0,   -- 退款申请占用
  occurred_at   DATETIME      NOT NULL,
  PRIMARY KEY (id)
) ENGINE=InnoDB;

CREATE TABLE settlement_allocation (
  id              BIGINT        NOT NULL,
  fund_id         BIGINT        NOT NULL,
  item_id         BIGINT        NOT NULL,
  amount          DECIMAL(24,9) NOT NULL,
  status          VARCHAR(20)   NOT NULL DEFAULT 'ACTIVE', -- ACTIVE / REVERSED
  reversal_of     BIGINT        NULL,
  PRIMARY KEY (id),
  KEY idx_alloc_fund (fund_id),
  KEY idx_alloc_item (item_id)
) ENGINE=InnoDB;

-- 幂等请求键（契约 §8）：同键同内容返回原结果，同键不同内容冲突
CREATE TABLE idem_request (
  request_key   VARCHAR(128) NOT NULL,
  payload_hash  VARCHAR(64)  NOT NULL,
  result_ref    VARCHAR(128) NOT NULL,
  PRIMARY KEY (request_key)
) ENGINE=InnoDB;

-- 用例执行结果（harness 自用）
CREATE TABLE case_result (
  case_id     VARCHAR(16)  NOT NULL,
  status      VARCHAR(12)  NOT NULL,          -- PASS / FAIL / ERROR
  detail      VARCHAR(1000) NULL,
  PRIMARY KEY (case_id)
) ENGINE=InnoDB;
