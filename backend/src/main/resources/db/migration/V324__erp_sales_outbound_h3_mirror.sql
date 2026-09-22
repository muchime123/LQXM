-- =====================================================================
-- 氚云销售出库 → ERP 业务出库单「全量镜像」（双轨制的销售侧接线）
-- ---------------------------------------------------------------------
-- 背景（与生产侧 V323 是同一个结构性问题）：
--   主库 `sales_outbounds`(18,966) / `sales_outbound_items`(35,159) 是**氚云台账**——
--   字段完整（客户/业务员/仓库/金额/毛利/收款/发货方式/明细行），但只读，只作
--   「氚云导入中心」的台账展示；而 ERP 侧唯一能录单、能扣库存、能追溯的
--   `erp_sales_outbounds` 是**空表**（0 行，V321 建表后没有任何单）。
--   结果：真实的 18,966 张出库单在系统里没有任何业务入口，
--   而「销售管理 → 销售出库」页读的正是那张空表。
--
-- 本迁移 = 双轨制的物理基础（销售侧），做两件事：
--   ① 给 `erp_sales_outbounds` 补**来源关联列**——原来这张表是「ERP 自建单」专用，
--      没有任何字段能表达「这一行是从氚云镜像来的」，也没有幂等键。
--   ② 放宽几个 NOT NULL 约束——氚云源数据**客观缺这些值**（实测缺口见下）。
--      镜像的纪律是「源缺什么就留空 + 落待补原因」，不造假值、也不把源数据挡在门外。
--      ⚠️ 只放开**数据库层**。Service 层的手工建单校验
--         （`SalesOutboundService.doCreate` 里的 `required()` / `requireWarehouse()`）
--         一个字都不改——手工建单的字段完整性要求不变。
--
-- 实测源数据缺口（2026-09-22，真库统计）：
--   主表：customer_name 空 13 / warehouse_id 空 2 / customer_id 空 209
--   明细：sales_product_id 空 4,298 → 拆开看 = FREIGHT 4,177（全缺）+ SERVICE 98（全缺）
--                                     + PRODUCT 23（氚云没绑 SKU）
--        unit 空 4,295 / quantity 空 57 / 负数 528（原样保留，不清洗）/ product_name 空 18
--   幂等键：主表 external_id 18,966 唯一、明细 external_id 35,159 唯一 → 可用作唯一索引
--   单号：源单号 100% 是 `CK…` 前缀、18,966 个全唯一，与 ERP 自建的 `XSCK%08d` 不撞
--
-- 依据：docs/完工入库到销售出库的身份断链与闭环设计-20260921.md
--       V321__create_erp_sales_outbound.sql（ERP 正式销售出库单建表）
--       docs/氚云导入全链路设计梳理-20260914.md（双轨制：台账只读 / 业务可写）
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. 主表：补来源关联列 + 幂等唯一索引
--    `uk_eso_external(external_source, external_id)` 里的两个列对 ERP 自建单
--    都是 NULL——MySQL 唯一索引允许多个 NULL 并存，所以自建单不受影响。
-- ---------------------------------------------------------------------
ALTER TABLE erp_sales_outbounds
    ADD COLUMN external_source       VARCHAR(30)   NULL COMMENT '来源系统：H3YUN=氚云镜像；NULL=ERP 自建' AFTER id,
    ADD COLUMN external_id           VARCHAR(100)  NULL COMMENT '来源单 UUID（幂等键，与 external_source 组唯一）' AFTER external_source,
    ADD COLUMN source_outbound_no    VARCHAR(50)   NULL COMMENT '氚云出库单号原文 CK…（对账/溯源用；不改 outbound_no 本列）' AFTER external_id,
    ADD COLUMN source_status_raw     VARCHAR(30)   NULL COMMENT '氚云原状态原文（已生效/草稿/进行中）——ERP 侧不据此改 status' AFTER source_outbound_no,
    ADD COLUMN source_warehouse_name VARCHAR(100)  NULL COMMENT '氚云仓库名原文（warehouse_id 对不上时的兜底展示）' AFTER source_status_raw,
    ADD COLUMN source_ship_date      DATE          NULL COMMENT '氚云发货日期（outbound_date 取业务日期，此列留发货日）' AFTER source_warehouse_name,
    ADD COLUMN source_sales_amount   DECIMAL(14,2) NULL COMMENT '氚云出库金额原文（total_amount 的源值，对账用）' AFTER source_ship_date,
    ADD COLUMN source_mirror_pending VARCHAR(300)  NULL COMMENT '镜像待补原因；非空=该单有客观缺失，待业务侧补齐' AFTER source_sales_amount,
    ADD COLUMN source_mirrored_at    DATETIME      NULL COMMENT '本次镜像落库时间（≠源轨道导入时间）' AFTER source_mirror_pending,
    ADD UNIQUE KEY uk_eso_external (external_source, external_id),
    ADD KEY idx_eso_source_no (source_outbound_no),
    ADD KEY idx_eso_mirror_pending (source_mirror_pending);

-- ---------------------------------------------------------------------
-- 2. 主表：放宽 NOT NULL（只放开数据库层，理由见文件头）
-- ---------------------------------------------------------------------
ALTER TABLE erp_sales_outbounds
    MODIFY COLUMN customer_name_snapshot VARCHAR(300) NULL
        COMMENT '客户名称快照（不随主数据改名而变）；氚云有 13 张没填 → 留空并记待补',
    MODIFY COLUMN warehouse_id           BIGINT       NULL
        COMMENT '发货仓库；氚云有 2 张没填 → 留空并记待补';

-- ---------------------------------------------------------------------
-- 3. 明细：补行类型与来源列
--    `line_type` 沿用氚云台账的同名口径（PRODUCT/FREIGHT/SERVICE），
--    这样「哪些行该扣库存」在两侧是同一个判断，不需要第二套约定。
--    默认值 'PRODUCT' 保证 ERP 自建单（只有商品行）无需改动即可插入。
-- ---------------------------------------------------------------------
ALTER TABLE erp_sales_outbound_items
    ADD COLUMN line_type       VARCHAR(20)  NOT NULL DEFAULT 'PRODUCT'
        COMMENT '行类型：PRODUCT 商品 / FREIGHT 运费 / SERVICE 服务费（与氚云台账同名口径）' AFTER line_no,
    ADD COLUMN external_id     VARCHAR(100) NULL
        COMMENT '氚云明细行 UUID（溯源；明细幂等由「父单已镜像则整单跳过」保证）' AFTER line_type,
    ADD COLUMN source_category VARCHAR(100) NULL
        COMMENT '氚云明细「分类」原文（塑料盒/定做膜/模具…）' AFTER external_id,
    ADD KEY idx_esoi_line_type (line_type);

-- ---------------------------------------------------------------------
-- 4. 明细：放宽 NOT NULL
--    ⚠️ `canonical_product_id` 放宽后可空，但**过账时必须非空**（过账按它扣库存）。
--       这条约束由 Service 层在过账前校验，不靠数据库——因为镜像进来的历史单
--       本来就该停在草稿态，允许「先看到、后补全」。
-- ---------------------------------------------------------------------
ALTER TABLE erp_sales_outbound_items
    MODIFY COLUMN sales_product_id      BIGINT       NULL
        COMMENT '业务身份；运费/服务费行与 23 行商品行氚云没绑 SKU → 留空',
    MODIFY COLUMN canonical_product_id  BIGINT       NULL
        COMMENT '库存聚合口径；同上留空。⚠️ 过账时必须非空',
    MODIFY COLUMN product_name_snapshot VARCHAR(500) NULL
        COMMENT '产品名称快照；氚云有 18 行没填 → 留空',
    MODIFY COLUMN unit                  VARCHAR(40)  NULL
        COMMENT '单位；氚云有 4,295 行没填（多为运费行）→ 留空';
