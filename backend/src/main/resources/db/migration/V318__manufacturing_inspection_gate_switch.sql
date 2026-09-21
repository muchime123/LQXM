-- V318: 制造工单检验闸门开关化（消除"没有岗位去推的必经状态"）
-- 现象：全部必做工序报工完成后，工单被无条件置为 PENDING_INSPECTION；
--       唯一出口 ManufacturingExecutionService.inspect() 只接受 PENDING_INSPECTION/PENDING_REINSPECTION，
--       且强制要求填写检验员 + 合格数量，无任何旁路
--       → 现场没有检验岗时，工单永久停在待检验，完工入库整段不通。
-- 活体证据：MO00000018 三道工序（生产加工/整机装配/调试检验）全部 COMPLETED、3 条报工 VALID，
--       自 2026-09-04 完工起滞留 PENDING_INSPECTION 17 天，inspection_at 为 NULL，manufacturing_inspections 全表 0 行。
-- 根因：7 条制程模板的末道工序均为检验类（DEBUG_INSPECTION / MOLD_TRIAL_INSPECTION / COMPLETION_CONFIRM），
--       把"设计阶段补出来的可选动作"写成了硬闸门（实际业务只有"组装调试"，无签字打勾式出厂检验）。
-- 处置：复用既有列 manufacturing_operations.requires_inspection 作为唯一政策开关（当前 14 道工序全为 0 = 不设人工检验）。
--       全部必做工序完成时：存在 requires_inspection=1 的工序 → 维持 PENDING_INSPECTION；
--                            不存在            → 直接置 PENDING_RECEIPT，并以 inspection_source='AUTO' 标记来源。
-- 口径：自动确认 ≠ 检验合格。不写 manufacturing_inspections（未发生检验事实），
--       inspection_at / inspection_by_* 保持 NULL；页面与对外表述应为"末道工序已完成"。
--       将来恢复检验：把对应工序的 requires_inspection 置 1 即可，无需改代码、无需重建工单。

ALTER TABLE manufacturing_orders
  ADD COLUMN inspection_source VARCHAR(20) NULL COMMENT '检验来源：AUTO=本单无人工检验工序时系统自动确认放行；MANUAL=人工检验通过；NULL=尚未进入检验环节' AFTER inspection_status;

-- 修复历史滞留单：全部必做工序已完成、但工单上不存在需要人工检验的工序 → 推进到待入库
UPDATE manufacturing_orders m
SET m.status='PENDING_RECEIPT',
    m.inspection_status='NOT_REQUIRED',
    m.inspection_source='AUTO'
WHERE m.status IN ('PENDING_INSPECTION','PENDING_REINSPECTION')
  AND NOT EXISTS (SELECT 1 FROM manufacturing_order_operations o
                    JOIN manufacturing_operations op ON op.id=o.operation_id
                   WHERE o.manufacturing_order_id=m.id AND o.required=1 AND op.requires_inspection=1)
  AND NOT EXISTS (SELECT 1 FROM manufacturing_order_operations o
                   WHERE o.manufacturing_order_id=m.id AND o.required=1 AND o.status<>'COMPLETED');
