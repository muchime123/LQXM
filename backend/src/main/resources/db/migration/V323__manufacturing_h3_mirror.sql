-- 氚云生产任务单「全量镜像」到业务工单：双轨制的物理基础。
--
-- 双轨口径（LQ 拍板 2026-09-22）：
--   源轨道 = production_orders（氚云导入，**只读**，导入中心台账展示氚云原样）
--   业务轨道 = manufacturing_orders（**可写**，所有业务处理落这里）
--   关联键 = manufacturing_orders.external_source/external_id（唯一索引 uk_manufacturing_external 已就位，V238 建的）
--
-- 为什么是两张表而不是一张：改源表 → 台账不再忠实于氚云；且下次重导会覆盖业务改动。

-- ------------------------------------------------------------------
-- 1) 恢复「氚云工单 ←→ 业务工单」的反向关联
--    V287 曾在「生产模块整体撤回等待重建」时退役这两列；P3 就是那次重建，故恢复。
-- ------------------------------------------------------------------
ALTER TABLE production_orders
    ADD COLUMN linked_manufacturing_order_id BIGINT NULL AFTER product_order_external_id,
    ADD COLUMN linked_manufacturing_order_no VARCHAR(50) NULL AFTER linked_manufacturing_order_id,
    ADD KEY idx_po_linked_manufacturing (linked_manufacturing_order_id);

-- ------------------------------------------------------------------
-- 2) 镜像行允许「不完整」——这是业务事实，不是缺陷
--    氚云工单本就没有产品 SKU、没有单位；客户名也只有 64% 有值。
--    强行 NOT NULL 会逼着镜像造假值（占位符），反而污染数据；由业务侧「补数据」补齐才是正解。
--    ⚠️ Service 层（ManufacturingService.doCreate）的手工建单校验保持不变，这里只放开数据库层。
-- ------------------------------------------------------------------
ALTER TABLE manufacturing_orders
    MODIFY customer_name_snapshot VARCHAR(300) NULL,
    MODIFY unit VARCHAR(40) NULL;

ALTER TABLE manufacturing_order_outputs
    MODIFY sales_product_id BIGINT NULL,
    MODIFY unit VARCHAR(40) NULL;

-- ------------------------------------------------------------------
-- 3) 业务工单留存氚云侧原文（只读对照，便于「台账 vs 业务」对拍）
-- ------------------------------------------------------------------
ALTER TABLE manufacturing_orders
    ADD COLUMN source_status_raw VARCHAR(50) NULL COMMENT '氚云原状态：已生效/进行中/草稿/已取消',
    ADD COLUMN source_progress_raw VARCHAR(20) NULL COMMENT '氚云生产进度原文，如 33.33%',
    ADD COLUMN source_warehoused_quantity DECIMAL(18,6) NULL COMMENT '氚云已入库数量快照',
    ADD COLUMN source_mirror_pending VARCHAR(300) NULL COMMENT '镜像待补原因，空=完整（如「品类未配置制程」）';

-- 按待补原因检索（页面「待补数据」筛选）
ALTER TABLE manufacturing_orders
    ADD KEY idx_mo_source_pending (source_mirror_pending);

-- 按氚云工单号检索（台账反查业务单）
ALTER TABLE manufacturing_orders
    ADD KEY idx_mo_h3_work_order_no (h3_work_order_no);
