-- =====================================================================
-- P3 生产闭环 ①：工单产出清单 + 收货一体多明细
-- ---------------------------------------------------------------------
-- 要解决的问题（决策 #28 / U15，`完工入库到销售出库的身份断链与闭环设计-20260921.md` §5.2）：
--
--   工单 `product_name_snapshot` 是**品类级**（"手动片膜"），而真正的商品身份（SKU）只在
--   **完工入库时**才第一次出现，且**一张工单产出 1~29 个 SKU**（实测 单 SKU 86.0% /
--   多 SKU 14.0% / 平均 1.22 / 最大 29），其中**模具占入库明细行 27.9%**。
--   所以「车间完工后靠微信告诉业务员入库产品名」**不是流程懒，是数据结构的必然结果** ——
--   车间是唯一知道"实际产出了哪几个 SKU"的人。
--
--   而 ERP 侧此前把这个问题原样复刻并加重了：
--     * 建单 `productName`、收货 `finishedGoodsName` **都是自由文本**；
--     * 收货单是**头部单值**（`quantity` + `finished_goods_name`）→ 一次收货只能记 1 个 SKU，
--       与"一单产出 N 个 SKU"的真实形态不符，只能靠多开几次收货单绕过；
--     * `ensureManufacturingProduct` 按 `MFG-<工单号>` **为每张工单造独立产品身份**
--       → 库存按 (仓,产品,单位) 唯一 → 同一件商品永远聚合不起来。
--
-- 本迁移落地两件结构性改造（Q3 拍板：产出清单**建单必填**）：
--
--   ① `manufacturing_order_outputs` —— 工单产出清单。
--      建单时由业务员声明"这张单要做哪几个 SKU、各多少"，支持 1~N 项（对应真实的 14% 多 SKU）。
--      每项直接指向 `sales_products` 的**标准 SKU（H3-）**，而不是自由文本。
--
--   ② `manufacturing_completion_receipt_items` —— 收货明细（收货单 头 → 主子）。
--      收货时**按清单逐项填实际数量**，不再手打名称；每项落一个成品批次。
--
-- ⚠️ Q1 口径（`MFG-<工单号>`）：**保留机制，但不再是默认产出身份**。
--   产出清单每项优先绑定已有标准 SKU；只有「主数据里确实没有这件货」（新品/非标订做）
--   才允许建 `MFG-` 独立身份并置 `PENDING_REVIEW`。理由：`V320` 的归一策略是
--   「按 (名称,规格,单位) 精确匹配唯一的 H3-」，而历史 4 个 `MFG-` 实测**全部匹配 0 个**
--   （名字来自自由文本，与 SKU 规范名不一致）→ 若继续默认造 `MFG-`，
--   canonical 归一**实际不会发生**，库存仍会分裂，「有货直接出库」依旧判断不出来。
--
-- ⚠️ 头部列兼容：`manufacturing_completion_receipts` 的 `quantity` / `finished_goods_name` /
--   `unit` 保持 NOT NULL，由明细**汇总回填**（主产品的名称与单位 + 全部明细数量之和）。
--   这样既有查询（列表列全表、关键词搜 `finished_goods_name`）、既有前端列、既有测试
--   都不必改，历史读法继续成立。
--
-- ✅ 零迁移风险：ERP 自产数据实测 `manufacturing_orders` 1 行 / `manufacturing_completion_receipts`
--   0 行 / `finished_goods_lots` 0 行 → 没有历史收货数据需要回填或改判。
--
-- 依据：docs/完工入库到销售出库的身份断链与闭环设计-20260921.md §5.2 / §5.3
--       docs/ERP建设现状梳理与下一步路线-20260921.md §5.1（P3）
--       docs/ERP决策登记-20260921.md #28 / U15
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. 工单产出清单
--    一行 = 「这张工单要做的一个 SKU + 计划数量」。`is_primary=1` 有且仅有一行（主产品），
--    它承载工单 `planned_quantity` 的语义与状态机口径；其余行是配套件/模具。
-- ---------------------------------------------------------------------
CREATE TABLE manufacturing_order_outputs (
    id                      BIGINT        NOT NULL AUTO_INCREMENT,
    manufacturing_order_id  BIGINT        NOT NULL COMMENT '所属生产任务',
    line_no                 INT           NOT NULL COMMENT '行号，从 1 起',
    is_primary              TINYINT(1)    NOT NULL DEFAULT 0 COMMENT '是否主产品（有且仅有一行；工单状态机按它判定）',
    sales_product_id        BIGINT        NOT NULL COMMENT '产出 SKU（优先标准 H3-；主数据确无此货才建 MFG- 并待审）',
    canonical_product_id    BIGINT        NULL     COMMENT '库存聚合口径（写入时取自 sales_products.canonical_product_id）',
    product_code_snapshot   VARCHAR(100)  NULL     COMMENT '产品编码快照',
    product_name_snapshot   VARCHAR(500)  NOT NULL COMMENT '产品名称快照',
    specification_snapshot  VARCHAR(1000) NULL,
    unit                    VARCHAR(40)   NOT NULL,
    planned_quantity        DECIMAL(18,6) NOT NULL COMMENT '计划产出数量（>0）',
    received_quantity       DECIMAL(18,6) NOT NULL DEFAULT 0 COMMENT '累计已收货数量（按批次实际值累加）',
    is_mold                 TINYINT(1)    NOT NULL DEFAULT 0 COMMENT '是否模具/工装（Q2 拍板：模具进可售库存）',
    remark                  VARCHAR(500)  NULL,
    -- 生成列只为做「每张工单有且仅有一行主产品」的部分唯一索引：
    -- MySQL 唯一索引里 NULL 之间不冲突，故非主产品行（NULL）可任意多行，
    -- 而主产品行恒为 1 → (工单, 1) 至多一行。应用层也校验，这里是最后一道闸。
    primary_flag            TINYINT       GENERATED ALWAYS AS (IF(is_primary = 1, 1, NULL)) STORED
                            COMMENT '仅主产品行为 1，其余 NULL（供部分唯一索引使用）',
    created_at              DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at              DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_moo_line (manufacturing_order_id, line_no),
    UNIQUE KEY uk_moo_primary (manufacturing_order_id, primary_flag),
    KEY idx_moo_order (manufacturing_order_id),
    KEY idx_moo_product (sales_product_id),
    KEY idx_moo_canonical (canonical_product_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4
  COMMENT = '工单产出清单（P3 ①：建单必填，消灭自由文本产出名）';

-- ---------------------------------------------------------------------
-- 2. 收货明细（收货单 头 → 主子）
--    一行 = 「本次收货里某个产出 SKU 的实际数量 + 它落的成品批次」。
--    `output_id` 软引用产出清单行（不建物理 FK：清单行理论上可被重排，但明细必须留得住）；
--    `sales_product_id` / `canonical_product_id` 冗余一份，保证即使清单行被改也能独立追溯。
-- ---------------------------------------------------------------------
CREATE TABLE manufacturing_completion_receipt_items (
    id                     BIGINT        NOT NULL AUTO_INCREMENT,
    receipt_id             BIGINT        NOT NULL COMMENT '所属收货单（manufacturing_completion_receipts.id）',
    line_no                INT           NOT NULL COMMENT '行号，从 1 起',
    output_id              BIGINT        NULL     COMMENT '对应工单产出清单行（软引用）',
    sales_product_id       BIGINT        NOT NULL COMMENT '产出的 SKU（业务身份）',
    canonical_product_id   BIGINT        NOT NULL COMMENT '库存聚合口径（= sales_products.canonical_product_id）',
    finished_goods_lot_id  BIGINT        NULL     COMMENT '本次明细落的成品批次（finished_goods_lots.id）',
    product_code_snapshot  VARCHAR(100)  NULL,
    product_name_snapshot  VARCHAR(500)  NOT NULL,
    specification_snapshot VARCHAR(1000) NULL,
    unit                   VARCHAR(40)   NOT NULL,
    quantity               DECIMAL(18,6) NOT NULL COMMENT '本次实际收货数量（>0）',
    unit_cost              DECIMAL(18,4) NULL,
    is_mold                TINYINT(1)    NOT NULL DEFAULT 0 COMMENT '是否模具（随产出清单带入，便于按模具口径统计）',
    remark                 VARCHAR(500)  NULL,
    created_at             DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_mcri_line (receipt_id, line_no),
    KEY idx_mcri_receipt (receipt_id),
    KEY idx_mcri_output (output_id),
    KEY idx_mcri_product (sales_product_id),
    KEY idx_mcri_canonical (canonical_product_id),
    KEY idx_mcri_lot (finished_goods_lot_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4
  COMMENT = '完工入库明细（P3 ②：按产出清单逐项确认实际数量）';

-- ---------------------------------------------------------------------
-- 3. 成品批次补一列：产出清单行
--    批次是"收到的这批货"的载体，指向产出清单行后，「工单 → 产出清单 → 批次 → 出库」
--    才是一条完整可追的血缘链（否则只能经收货明细中转到清单行）。
-- ---------------------------------------------------------------------
ALTER TABLE finished_goods_lots
    ADD COLUMN manufacturing_order_output_id BIGINT NULL
        COMMENT '对应工单产出清单行（P3 ①；历史行为 NULL）' AFTER completion_receipt_id,
    ADD KEY idx_fgl_output (manufacturing_order_output_id);

-- ---------------------------------------------------------------------
-- 4. 存量单据兜底：把已有收货单的头部值镜像成一条明细
--    实测线上 `manufacturing_completion_receipts` 为 0 行，本条**不会产生任何数据**；
--    保留它只为「万一有测试残留」时不让旧单在主子结构下变成"没有明细的孤儿"。
--    `output_id` 留空（旧单本来就没有清单），`canonical_product_id` 用产品自身的归一口径。
-- ---------------------------------------------------------------------
INSERT INTO manufacturing_completion_receipt_items
    (receipt_id, line_no, output_id, sales_product_id, canonical_product_id, finished_goods_lot_id,
     product_code_snapshot, product_name_snapshot, specification_snapshot, unit, quantity, unit_cost, remark)
SELECT r.id, 1, NULL, l.sales_product_id, COALESCE(sp.canonical_product_id, sp.id), l.id,
       sp.product_code, r.finished_goods_name, r.specification_snapshot, r.unit, r.quantity, l.unit_cost,
       'V322 由旧头部单值镜像'
FROM manufacturing_completion_receipts r
JOIN finished_goods_lots l ON l.completion_receipt_id = r.id
JOIN sales_products sp ON sp.id = l.sales_product_id
WHERE NOT EXISTS (SELECT 1 FROM manufacturing_completion_receipt_items i WHERE i.receipt_id = r.id);
