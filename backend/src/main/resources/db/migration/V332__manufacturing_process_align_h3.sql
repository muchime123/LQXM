-- ============================================================================
-- 制程与工序按氚云设计对齐（2026-09-23）
-- ----------------------------------------------------------------------------
-- 权威源：氚云导出件三张
--   《制程表》          → 工序字典（11 条）
--   《产品零部件制程》  → 制程路线（7 条）+ 路线明细（22 行）
--   《任务单配置项》    → 已一致（119 条，本迁移不动）
-- 模型订正：氚云是「一条产线 = 一条制程（有序工序列表）」，
--           不是「一个产品型号 = 一条制程」。
-- 旧数据：一律停用不删（旧 10 条工序 + 旧 7 条路线），
--         保留 4 张在产工单的工序快照与 source_template_operation_id 引用。
-- 幂等键：external_source='H3YUN' + external_id（氚云 UUID）；全部可重复执行。
-- 未决：制程路线与「产品名称」的对应关系氚云里无字段承载
--       （manufacturing_task_config_items.process_template_id 保持 NULL）。
-- ============================================================================

-- ===== 1. 工序字典：氚云《制程表》11 条 =====
-- 1.1 4 条同名复用：补氚云身份与排序（名字已存在，受 operation_name 唯一键约束）
UPDATE manufacturing_operations SET external_source='H3YUN', external_id='80db3204-b69d-4366-8115-cc6992166644', sort_order=20 WHERE operation_name='组装调试';
UPDATE manufacturing_operations SET external_source='H3YUN', external_id='6b5041c9-3913-4aab-b524-53d2162cb6f3', sort_order=40 WHERE operation_name='采购/下料';
UPDATE manufacturing_operations SET external_source='H3YUN', external_id='49f31fc8-262e-4d45-808f-f51392d09efc', sort_order=60, source_inventory_quantity=2 WHERE operation_name='模具加工';
UPDATE manufacturing_operations SET external_source='H3YUN', external_id='4c036d72-bbb6-402a-be03-cc1a2d9ab08d', sort_order=90 WHERE operation_name='控制板面板';

-- 1.2 7 条新建（氚云有、库里无）
INSERT INTO manufacturing_operations (external_source, external_id, operation_code, operation_name, operation_category, active, sort_order)
SELECT 'H3YUN','774864eb-75f1-4792-9839-a619537be50f','H3_ARRANGE_ASSEMBLY','安排组装','ASSEMBLY',1,10 FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM manufacturing_operations WHERE external_source='H3YUN' AND external_id='774864eb-75f1-4792-9839-a619537be50f');
INSERT INTO manufacturing_operations (external_source, external_id, operation_code, operation_name, operation_category, active, sort_order)
SELECT 'H3YUN','dfe24cd7-b95d-47f2-91bd-9ae1af6e1c31','H3_DESIGN_DRAWING','设计/画图','DESIGN',1,30 FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM manufacturing_operations WHERE external_source='H3YUN' AND external_id='dfe24cd7-b95d-47f2-91bd-9ae1af6e1c31');
INSERT INTO manufacturing_operations (external_source, external_id, operation_code, operation_name, operation_category, active, sort_order)
SELECT 'H3YUN','b1ccbb18-a057-4117-9fe3-cc4af0d48a45','H3_SHEET_METAL_SHELL','钣金加工/机壳','FABRICATION',1,50 FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM manufacturing_operations WHERE external_source='H3YUN' AND external_id='b1ccbb18-a057-4117-9fe3-cc4af0d48a45');
INSERT INTO manufacturing_operations (external_source, external_id, operation_code, operation_name, operation_category, active, sort_order, source_inventory_quantity)
SELECT 'H3YUN','b608e9e1-02a4-4372-817d-efa3a092193b','H3_SURFACE_OXIDATION','表面处理/氧化','SURFACE',1,70,2 FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM manufacturing_operations WHERE external_source='H3YUN' AND external_id='b608e9e1-02a4-4372-817d-efa3a092193b');
INSERT INTO manufacturing_operations (external_source, external_id, operation_code, operation_name, operation_category, active, sort_order, source_inventory_quantity)
SELECT 'H3YUN','d3d714cb-f351-4eb3-9506-5fcd981beb8e','H3_SURFACE_POWDER','表面处理/喷塑','SURFACE',1,80,2 FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM manufacturing_operations WHERE external_source='H3YUN' AND external_id='d3d714cb-f351-4eb3-9506-5fcd981beb8e');
INSERT INTO manufacturing_operations (external_source, external_id, operation_code, operation_name, operation_category, active, sort_order)
SELECT 'H3YUN','792217d9-a438-4fc7-a8c1-e999f90f8765','H3_COMPONENT_ASSEMBLY_ALL','零部件组装/所有组件','ASSEMBLY',1,100 FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM manufacturing_operations WHERE external_source='H3YUN' AND external_id='792217d9-a438-4fc7-a8c1-e999f90f8765');
INSERT INTO manufacturing_operations (external_source, external_id, operation_code, operation_name, operation_category, active, sort_order)
SELECT 'H3YUN','2ce67ca7-1ad9-4062-8706-123fdfdbbe0e','H3_ELECTRIC_AIR','电和气安装','ELECTRICAL',1,110 FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM manufacturing_operations WHERE external_source='H3YUN' AND external_id='2ce67ca7-1ad9-4062-8706-123fdfdbbe0e');

-- 1.3 旧的 10 条工序停用（氚云无对应；按名列举，避免误伤后续新增）
UPDATE manufacturing_operations SET active=0
WHERE operation_name IN ('设计确认','生产加工','整机装配','安装适配','机壳加工','表面处理',
                         '部件组装','电气与气路安装','调试检验','试模检验','完成确认');

-- ===== 2. 制程路线：氚云《产品零部件制程》7 条 =====
-- 旧 7 条（按产品型号命名）停用
UPDATE manufacturing_process_templates SET active=0
WHERE template_code IN ('STANDARD_SEALER','CUSTOM_SEALER','RETURN_MOLD','MOLD_ONLY','SEMI_AUTO_GAS','FULL_AUTO_GAS','ACCESSORY');

-- 在产 3 条（氚云「是否有制程=是」且在用）
INSERT INTO manufacturing_process_templates (external_source, external_id, template_code, template_name, workshop_type, description, active, version)
SELECT 'H3YUN','2e4dd87e-038a-4725-af9f-f34314b419c6','H3_WORKSHOP_SEALER','封口机车间','封口机车间','氚云《产品零部件制程》：1 道工序（模具加工，尾道）',1,1 FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM manufacturing_process_templates WHERE external_source='H3YUN' AND external_id='2e4dd87e-038a-4725-af9f-f34314b419c6');
INSERT INTO manufacturing_process_templates (external_source, external_id, template_code, template_name, workshop_type, description, active, version)
SELECT 'H3YUN','b2a3dcbb-09f1-4b70-8aee-07993f4f822d','H3_SEMI_AUTO_GAS','半自动气调机','半自动气调机车间','氚云《产品零部件制程》：8 道工序',1,1 FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM manufacturing_process_templates WHERE external_source='H3YUN' AND external_id='b2a3dcbb-09f1-4b70-8aee-07993f4f822d');
INSERT INTO manufacturing_process_templates (external_source, external_id, template_code, template_name, workshop_type, description, active, version)
SELECT 'H3YUN','36b86fbc-382b-4fa4-8c14-3c5ff5a332d6','H3_FULL_AUTO_GAS','全自动气调机','全自动气调机车间','氚云《产品零部件制程》：8 道工序',1,1 FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM manufacturing_process_templates WHERE external_source='H3YUN' AND external_id='36b86fbc-382b-4fa4-8c14-3c5ff5a332d6');

-- 停用 4 条：氚云标「是否有制程=否」2 条 + 老厂遗留（5楼）2 条（LQ 2026-09-23 明确已不再使用）
INSERT INTO manufacturing_process_templates (external_source, external_id, template_code, template_name, workshop_type, description, active, version)
SELECT 'H3YUN','d472528e-0abd-4af3-b195-d06cc96e6cbb','H3_WORKSHOP_WEIGHING','称重机车间','称重机车间','氚云标记：是否有制程=否（该产线无工序）',0,1 FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM manufacturing_process_templates WHERE external_source='H3YUN' AND external_id='d472528e-0abd-4af3-b195-d06cc96e6cbb');
INSERT INTO manufacturing_process_templates (external_source, external_id, template_code, template_name, workshop_type, description, active, version)
SELECT 'H3YUN','81d2746f-a69a-4e18-8f01-b463429b2b9a','H3_NO_REPORT','无需报工',NULL,'氚云标记：是否有制程=否（免报工场景）',0,1 FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM manufacturing_process_templates WHERE external_source='H3YUN' AND external_id='81d2746f-a69a-4e18-8f01-b463429b2b9a');
INSERT INTO manufacturing_process_templates (external_source, external_id, template_code, template_name, workshop_type, description, active, version)
SELECT 'H3YUN','16dfc161-f801-40e0-81bb-22fffd1f2956','H3_LEGACY_RETURN_F5','返单封口机/模具加工（5楼）','封口机车间','老厂遗留制程，2026-09-23 起不再使用（氚云仍在库）',0,1 FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM manufacturing_process_templates WHERE external_source='H3YUN' AND external_id='16dfc161-f801-40e0-81bb-22fffd1f2956');
INSERT INTO manufacturing_process_templates (external_source, external_id, template_code, template_name, workshop_type, description, active, version)
SELECT 'H3YUN','710f1edb-060b-4f9e-bad5-9c78ce288808','H3_LEGACY_CUSTOM_F5','订做封口机（5楼）','封口机车间','老厂遗留制程，2026-09-23 起不再使用（氚云仍在库）',0,1 FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM manufacturing_process_templates WHERE external_source='H3YUN' AND external_id='710f1edb-060b-4f9e-bad5-9c78ce288808');

-- ===== 3. 路线明细：氚云子表「工艺制程」22 行 =====
-- 氚云子表三列（数据标题 / 零部件表单 / 零部件名）取值完全相同，均照抄落入 component_name / component_form
INSERT INTO manufacturing_process_template_operations
  (external_source, external_id, template_id, operation_id, component_name, component_form, sequence_no, requirement_coefficient, required, is_final)
SELECT 'H3YUN', v.uuid, t.id, o.id, v.op_name, v.op_name, v.seq, 1.000000, 1, v.is_final
FROM (
            SELECT 'H3_WORKSHOP_SEALER'   AS tpl, '模具加工' AS op_name, 1 AS seq, '84e5a382-fed0-4a54-96b0-3e0ee4fdace6' AS uuid, 1 AS is_final
  UNION ALL SELECT 'H3_LEGACY_RETURN_F5','模具加工',1,'e921d74f-e20d-4fc2-bff9-8afe5dbba8fa',0
  UNION ALL SELECT 'H3_LEGACY_RETURN_F5','安排组装',2,'15a38b20-aa01-4abc-ac2b-6bd88b01aae4',1
  UNION ALL SELECT 'H3_LEGACY_CUSTOM_F5','设计/画图',1,'31a6f91b-0701-4b26-8d4d-b5ec5dd19177',0
  UNION ALL SELECT 'H3_LEGACY_CUSTOM_F5','模具加工',2,'254b9972-5a07-4e68-b1b8-f9aa0f3ea576',0
  UNION ALL SELECT 'H3_LEGACY_CUSTOM_F5','安排组装',3,'002d8df2-7053-45e2-a8ef-d7106f3a9109',1
  UNION ALL SELECT 'H3_SEMI_AUTO_GAS','设计/画图',1,'92f8c26c-034d-4354-aff1-c3c417adba1f',0
  UNION ALL SELECT 'H3_SEMI_AUTO_GAS','采购/下料',2,'cf323885-7fff-4577-a1f6-82188da969be',0
  UNION ALL SELECT 'H3_SEMI_AUTO_GAS','钣金加工/机壳',3,'f3627bc8-03ed-4b6c-8062-c37c7c8bf477',0
  UNION ALL SELECT 'H3_SEMI_AUTO_GAS','模具加工',4,'db0dcbc2-2695-41a4-9821-110b7dde01b4',0
  UNION ALL SELECT 'H3_SEMI_AUTO_GAS','表面处理/氧化',5,'51668a2a-0e22-4f54-a574-84229f28d5ce',0
  UNION ALL SELECT 'H3_SEMI_AUTO_GAS','零部件组装/所有组件',6,'0dd8bd54-cc89-408f-912e-1c74b4b437df',0
  UNION ALL SELECT 'H3_SEMI_AUTO_GAS','电和气安装',7,'b3e7dbd5-3a3d-420b-8eb8-f3568aea3766',0
  UNION ALL SELECT 'H3_SEMI_AUTO_GAS','组装调试',8,'8bd3f216-7ca5-4252-9e6d-f5311079c696',1
  UNION ALL SELECT 'H3_FULL_AUTO_GAS','设计/画图',1,'e24d708b-dc65-474f-be4a-d39628bab026',0
  UNION ALL SELECT 'H3_FULL_AUTO_GAS','采购/下料',2,'0ae712e9-f740-4b4b-8d04-78094b5d6ecc',0
  UNION ALL SELECT 'H3_FULL_AUTO_GAS','钣金加工/机壳',3,'83670a28-3a4c-4d1b-9b56-c0e73d933b6e',0
  UNION ALL SELECT 'H3_FULL_AUTO_GAS','模具加工',4,'46210072-c37b-4a71-85c5-3371f7e7686e',0
  UNION ALL SELECT 'H3_FULL_AUTO_GAS','表面处理/氧化',5,'07052f17-c7c2-4388-a7c0-bd1a891c63a6',0
  UNION ALL SELECT 'H3_FULL_AUTO_GAS','零部件组装/所有组件',6,'abe629e4-9e7e-4804-9077-f86a28136c48',0
  UNION ALL SELECT 'H3_FULL_AUTO_GAS','电和气安装',7,'e554c144-f8bd-466b-8e4a-a588a6843f51',0
  UNION ALL SELECT 'H3_FULL_AUTO_GAS','组装调试',8,'eb8f60b0-6b7f-48fd-b4cc-8eda6af9fb34',1
) v
JOIN manufacturing_process_templates t ON t.template_code = v.tpl
JOIN manufacturing_operations o ON o.operation_name = v.op_name
WHERE NOT EXISTS (
  SELECT 1 FROM manufacturing_process_template_operations x
  WHERE x.external_source='H3YUN' AND x.external_id=v.uuid);

-- ===== 4. 车间默认制程重指（氚云：称重机车间「是否有制程=否」→ 不挂制程） =====
UPDATE manufacturing_workshops SET default_process_template_id=NULL WHERE workshop_name='称重机车间';
UPDATE manufacturing_workshops SET default_process_template_id=(SELECT id FROM manufacturing_process_templates WHERE template_code='H3_WORKSHOP_SEALER') WHERE workshop_name='封口机车间';
UPDATE manufacturing_workshops SET default_process_template_id=(SELECT id FROM manufacturing_process_templates WHERE template_code='H3_SEMI_AUTO_GAS') WHERE workshop_name='半自动气调机车间';
UPDATE manufacturing_workshops SET default_process_template_id=(SELECT id FROM manufacturing_process_templates WHERE template_code='H3_FULL_AUTO_GAS') WHERE workshop_name='全自动气调机车间';
