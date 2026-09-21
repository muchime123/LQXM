-- =====================================================================
-- ERP 正式销售出库单（P2：第一张 ERP 自建业务单据）
-- ---------------------------------------------------------------------
-- 为什么新建表而不复用 `sales_outbounds`（U16）：
--   主库 `sales_outbounds` / `sales_outbound_items` 已被**氚云台账**占用
--   （18,966 / 35,159 行，100% `external_source='H3YUN'`）。台账表带
--   `import_batch_id` / `source_hash` 等导入专用列，下次重导会覆盖；且台账行
--   **没有流程状态、没有录单人**。若 ERP 正式单挤进同一张表：
--     * 「今年出库多少」会把台账全年历史与 ERP 上线后增量混算；
--     * 「这张单走完流程没」「谁录的」无法回答。
--   —— 这正是决策 #11 要隔离的问题。
--
-- 落点选择（U16 解法）：**主库 + `erp_` 前缀**，而非 `erp` schema。理由：
--   ① 出库单、库存扣减、库存流水必须**同一事务**。库存账
--      （`product_inventory_balances` / `product_inventory_transactions`）在主库，
--      同库同连接才能真正原子；跨 schema 虽同实例也支持，但绑错池不报错、只静默失效
--      （见 `ErpContractConfig` 的绑定纪律）。
--   ② 与既有的 ERP 自产业务（制造域 `manufacturing_*`）一致——它同样在主库、
--      同样绑 `archiveIdempotencyService` / `archiveAuditWriter`。
--   ③ 隔离目的（不与台账混表/混名）由 `erp_` 前缀达成，与 schema 隔离等效。
--   ⚠️ 与决策 #11 的字面表述（独立 erp schema）不同，属**隔离手段调整**，已登记。
--
-- 依据：docs/完工入库到销售出库的身份断链与闭环设计-20260921.md §5
--       docs/ERP建设现状梳理与下一步路线-20260921.md §5.1（P2）/ §2.1
--       docs/ERP决策登记-20260921.md #28 / U15（Q2 模具进可售库存、Q3 建单必填）
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. 出库单主表
--    状态机：DRAFT（草稿，不动库存）→ POSTED（已过账，扣库存+写流水）→ REVERSED（已冲销，回补库存）
--    与制造域同款：单号先用 PENDING 占位插入、拿到 id 后回填 `XSCK%08d`——
--    这样单号恒等于主键，天然唯一，且不依赖任何序列/日期并发。
-- ---------------------------------------------------------------------
CREATE TABLE erp_sales_outbounds (
    id                        BIGINT        NOT NULL AUTO_INCREMENT,
    outbound_no               VARCHAR(50)   NOT NULL COMMENT '出库单号 XSCK########（= 主键，回填生成）',
    status                    VARCHAR(20)   NOT NULL COMMENT 'DRAFT 草稿 / POSTED 已过账 / REVERSED 已冲销',
    customer_id               BIGINT        NULL     COMMENT '客户档案 id（软引用，不建物理 FK：客户可能合并）',
    customer_name_snapshot    VARCHAR(300)  NOT NULL COMMENT '客户名称快照（不随主数据改名而变）',
    salesperson_id            BIGINT        NULL     COMMENT '业务员 employee id（行级数据范围依据）',
    salesperson_name_snapshot VARCHAR(100)  NULL     COMMENT '业务员姓名快照',
    warehouse_id              BIGINT        NOT NULL COMMENT '发货仓库',
    sales_order_ref           VARCHAR(100)  NULL     COMMENT '关联销售订单/合同号；暂存原文，P3 接订单表后改为外键',
    outbound_date             DATE          NOT NULL COMMENT '出库日期（业务日期）',
    total_amount              DECIMAL(18,4) NULL     COMMENT '出库金额合计（明细 amount 求和，仅供展示；成本/毛利口径未定见 #10）',
    remark                    VARCHAR(1000) NULL,
    posted_at                 DATETIME      NULL     COMMENT '过账时间',
    posted_by_employee_id     BIGINT        NULL,
    posted_by_name            VARCHAR(100)  NULL,
    reversed_at               DATETIME      NULL,
    reversed_by_employee_id   BIGINT        NULL,
    reversed_by_name          VARCHAR(100)  NULL,
    reversal_reason           VARCHAR(500)  NULL     COMMENT '冲销原因（必填）',
    created_by_employee_id    BIGINT        NULL,
    created_by_name           VARCHAR(100)  NULL,
    created_at                DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at                DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_eso_no (outbound_no),
    KEY idx_eso_date (outbound_date, status),
    KEY idx_eso_customer (customer_id),
    KEY idx_eso_salesperson (salesperson_id),
    KEY idx_eso_warehouse (warehouse_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'ERP 正式销售出库单（台账见 sales_outbounds，两者不混用）';

-- ---------------------------------------------------------------------
-- 2. 出库单明细
--    同时存业务身份与归一口径（#28 Q1）：
--      sales_product_id   = 这笔货挂在哪个身份上（可能是 MFG-<工单号>）
--      canonical_product_id = 库存扣减按哪个 SKU 聚合（H3- 标准 SKU）
--    过账时以 canonical 扣库存，故同一件商品的多次生产/多张单不会把库存拆散。
-- ---------------------------------------------------------------------
CREATE TABLE erp_sales_outbound_items (
    id                      BIGINT        NOT NULL AUTO_INCREMENT,
    outbound_id             BIGINT        NOT NULL,
    line_no                 INT           NOT NULL COMMENT '行号，从 1 起',
    sales_product_id        BIGINT        NOT NULL COMMENT '业务身份（可能是 MFG-<工单号>，见 #28 Q1）',
    canonical_product_id    BIGINT        NOT NULL COMMENT '库存聚合口径（= sales_products.canonical_product_id）',
    product_code_snapshot   VARCHAR(100)  NULL     COMMENT '产品编码快照',
    product_name_snapshot   VARCHAR(500)  NOT NULL COMMENT '产品名称快照',
    specification_snapshot  VARCHAR(1000) NULL,
    unit                    VARCHAR(40)   NOT NULL,
    quantity                DECIMAL(18,6) NOT NULL COMMENT '出库数量（正数）',
    unit_price              DECIMAL(18,4) NULL,
    amount                  DECIMAL(18,4) NULL,
    remark                  VARCHAR(500)  NULL,
    created_at              DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_esoi_line (outbound_id, line_no),
    KEY idx_esoi_outbound (outbound_id),
    KEY idx_esoi_product (sales_product_id),
    KEY idx_esoi_canonical (canonical_product_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'ERP 正式销售出库单明细';

-- ---------------------------------------------------------------------
-- 3. 权限
--    复用既有的 `sales_order.*`（当前项目里「销售出库单」就是销售单据本身，
--    09-21 核查确认项目无独立销售订单实体；另起一套 sales_outbound.* 只会造成
--    两套语义重叠的权限）。已有绑定：
--      manage        → ADMIN / BOSS / SALES_MANAGER
--      read.self     → ADMIN / BOSS / SALESPERSON
--      read.department → ADMIN / BOSS / SALES_MANAGER
--      read.all      → ADMIN / BOSS / FINANCE
--    ⚠️ 缺口：**SALESPERSON 没有 manage** —— 而「有货直接出库」正是业务员的动作。
--       制造域已把 `manufacturing.manage` 给了 SALESPERSON（业务员缺货时下达生产任务），
--       销售侧同理必须补上，否则整条主线对业务员不可用。
-- ---------------------------------------------------------------------
INSERT IGNORE INTO system_role_permissions(role_id, permission_id)
SELECT r.id, p.id
FROM system_roles r
CROSS JOIN system_permissions p
WHERE r.role_code = 'SALESPERSON'
  AND p.permission_code = 'sales_order.manage';
