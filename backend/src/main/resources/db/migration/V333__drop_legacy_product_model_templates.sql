-- V333: 删除旧「按产品型号」制程模板（LQ 2026-09-23 定：截图里 7 条全部删除）
--
-- 背景：V332 已按氚云《产品零部件制程》重建「一条产线一条制程」并停用旧模型；
-- 本迁移把停用的旧 7 条**硬删**（LQ 明确「删除这几个」），同时收拢「制程选择」下拉的候选：
--   - 称重机车间（H3_WORKSHOP_WEIGHING，0 道工序，氚云标「是否有制程=否」）→ 停用；
--     「无制程」语义由「无需报工」（H3_NO_REPORT，通用）承载。
--   - 两条（5楼）老厂遗留路线 → 停用（LQ 09-23 明确不再使用）。
-- 旧 11 条工序**不删**：manufacturing_order_operations.operation_id 有硬外键
-- （fk_order_operation_operation）钉着 4 张测试工单的工序行；已 active=0，界面默认不可见。
--
-- 幂等性：全部 DELETE/UPDATE，可安全复跑（含 Flyway 补跑场景）。

-- 1. 在产工单对旧模板明细的软引用置空（工序快照 operation_name_snapshot 保留，展示不受影响）
UPDATE manufacturing_order_operations o
JOIN manufacturing_process_template_operations d ON d.id = o.source_template_operation_id
JOIN manufacturing_process_templates t ON t.id = d.template_id
SET o.source_template_operation_id = NULL
WHERE t.template_code IN ('STANDARD_SEALER','CUSTOM_SEALER','RETURN_MOLD','MOLD_ONLY',
                          'SEMI_AUTO_GAS','FULL_AUTO_GAS','ACCESSORY');

-- 2. 工单对旧模板的直接引用置空
UPDATE manufacturing_orders mo
JOIN manufacturing_process_templates t ON t.id = mo.process_template_id
SET mo.process_template_id = NULL
WHERE t.template_code IN ('STANDARD_SEALER','CUSTOM_SEALER','RETURN_MOLD','MOLD_ONLY',
                          'SEMI_AUTO_GAS','FULL_AUTO_GAS','ACCESSORY');

-- 3. 删除旧模板明细
DELETE d FROM manufacturing_process_template_operations d
JOIN manufacturing_process_templates t ON t.id = d.template_id
WHERE t.template_code IN ('STANDARD_SEALER','CUSTOM_SEALER','RETURN_MOLD','MOLD_ONLY',
                          'SEMI_AUTO_GAS','FULL_AUTO_GAS','ACCESSORY');

-- 4. 删除旧模板
DELETE FROM manufacturing_process_templates
WHERE template_code IN ('STANDARD_SEALER','CUSTOM_SEALER','RETURN_MOLD','MOLD_ONLY',
                        'SEMI_AUTO_GAS','FULL_AUTO_GAS','ACCESSORY');

-- 5. 收拢制程选择候选：称重机（无制程语义由「无需报工」承载）与两条 5 楼老厂路线停用
UPDATE manufacturing_process_templates SET active = 0
WHERE template_code IN ('H3_WORKSHOP_WEIGHING','H3_LEGACY_RETURN_F5','H3_LEGACY_CUSTOM_F5');
