-- V319: 制程模板与氚云「部件制程」对账对齐
--
-- 背景：氚云导入中心的「生产任务工单」(production_orders, 5,691) + 「生产报工」(h3_production_reports, 12,252)
--       是现役系统（最新工单/报工均为 2026-09-17）。ERP 制程模板当年确实照氚云部件制程建，
--       但 manufacturing_process_template_operations.component_name 全空 → 两侧无法自动对账，
--       且缺 1 道真实存在的部件制程。本次按实测数据对齐，不改工序语义、不动 is_final/required。
--
-- 实测依据（只读查询，见 docs/氚云生产工单报工与ERP制造域对拍-20260921.md）：
--   · 气调机（半自动 211 单 / 全自动 358 单）：真实制程集合 = 设计/画图 + 采购/下料 + 钣金加工/机壳 +
--     模具加工 + 表面处理/氧化 + 零部件组装/所有组件 + 电和气安装 + 组装调试（8 道）
--     → 与模板 5/6 的 8 道部件制程完全一致（ERP 另有末道「调试检验」，氚云无对应报工）。
--   · 半自动气调机另有 29 单多一道「控制板面板」，全自动气调机 0 单 → 该工序只应加入模板 5。
--   · 封口机车间 4,420 单：真实制程集合 = 模具加工(4,119 单) / 安排组装(1,722) / 设计/画图(1,828) 三种；
--     ⚠️ 无任何一单的制程集合恰好等于某模板 → 封口机默认模板**本次不动**（见文末说明）。
--
-- 一、补工序主数据「控制板面板」
-- 二、加入半自动气调机模板（第 6 道，其后工序顺移）
-- 三、回填 component_name 作为与氚云的唯一对账字段

-- ============ 一、工序主数据 ============
INSERT INTO manufacturing_operations
  (external_source, external_id, operation_code, operation_name, operation_category,
   default_workshop, standard_hours, reporting_unit, requires_inspection, description,
   source_status, active, sort_order)
SELECT NULL, NULL, 'CONTROL_PANEL', '控制板面板', 'FABRICATION',
       NULL, NULL, NULL, 0, '氚云部件制程「控制板面板」；半自动气调机 29 单实测使用，全自动 0 单',
       NULL, 1, 75
  FROM (SELECT 1) d
 WHERE NOT EXISTS (SELECT 1 FROM (SELECT operation_code FROM manufacturing_operations) x
                    WHERE x.operation_code = 'CONTROL_PANEL');

-- ============ 二、半自动气调机模板插入该工序 ============
-- 唯一键 uk_template_operation_sequence(template_id, sequence_no) → 必须倒序顺移，否则中途撞键
UPDATE manufacturing_process_template_operations
   SET sequence_no = sequence_no + 1
 WHERE template_id = 5 AND sequence_no >= 6
 ORDER BY sequence_no DESC;

INSERT INTO manufacturing_process_template_operations
  (external_source, external_id, template_id, operation_id, component_name, component_form,
   sequence_no, requirement_coefficient, required, is_final, allow_parallel, remark)
SELECT NULL, NULL, 5, o.id, '控制板面板', NULL,
       6, 1.000000, 1, 0, 0, '按氚云半自动气调机制程第 6 道补齐（29 单实测）；全自动车间 0 单故不加'
  FROM manufacturing_operations o
 WHERE o.operation_code = 'CONTROL_PANEL'
   AND NOT EXISTS (SELECT 1 FROM (SELECT x2.sequence_no FROM manufacturing_process_template_operations x2
                                   WHERE x2.template_id = 5 AND x2.sequence_no = 6) y);

-- ============ 三、回填 component_name（与氚云部件制程的一一对应） ============
-- 氚云 10 种部件制程中 8 种为确定映射；「安排组装」为按封口机车间 1,722 条报工推断。
-- 氚云无对应报工的工序（生产加工/调试检验/试模检验/完成确认/安装适配）保持 NULL —— 两侧数量本就不等，
-- 强行填值会让对账反过来变成假一致。
UPDATE manufacturing_process_template_operations x
  JOIN manufacturing_operations o ON o.id = x.operation_id
   SET x.component_name = CASE o.operation_code
         WHEN 'DESIGN_CONFIRM'       THEN '设计/画图'
         WHEN 'MOLD_PROCESS'         THEN '模具加工'
         WHEN 'PURCHASE_MATERIAL'    THEN '采购/下料'
         WHEN 'SHELL_PROCESS'        THEN '钣金加工/机壳'
         WHEN 'SURFACE_PROCESS'      THEN '表面处理/氧化'
         WHEN 'COMPONENT_ASSEMBLY'   THEN '零部件组装/所有组件'
         WHEN 'ELECTRIC_AIR_INSTALL' THEN '电和气安装'
         WHEN 'ASSEMBLY_DEBUG'       THEN '组装调试'
         WHEN 'CONTROL_PANEL'        THEN '控制板面板'
       END
 WHERE o.operation_code IN ('DESIGN_CONFIRM','MOLD_PROCESS','PURCHASE_MATERIAL','SHELL_PROCESS',
                            'SURFACE_PROCESS','COMPONENT_ASSEMBLY','ELECTRIC_AIR_INSTALL',
                            'ASSEMBLY_DEBUG','CONTROL_PANEL');

UPDATE manufacturing_process_template_operations x
  JOIN manufacturing_operations o ON o.id = x.operation_id
   SET x.component_name = '安排组装',
       x.remark = CONCAT(COALESCE(x.remark, ''), '【与氚云对账名：安排组装（推断，待车间确认）】')
 WHERE o.operation_code = 'MACHINE_ASSEMBLY';

-- ============ 四、明确「不改」的部分（避免后来者误改） ============
-- 1) 封口机车间默认模板（manufacturing_workshops.default_process_template_id，当前 = 1「普通封口机」）**保持不动**：
--    实测 4,420 单中 4,119 单（93.2%）含「模具加工」→ 模板 2「订做封口机」可 100% 覆盖；
--    但 2,122 单（48%）只报了一道「模具加工」，若默认给 4 道必做工序，这些单会因剩余工序无人报工
--    而永久停在「生产中」——即 V318 已处置过的「没有岗位去推的必经状态」重演。
--    → 改用哪套模板、或把「整机装配/调试检验」置为可选（required=0），须先与车间确认那 2,122 单
--      是否就是「返单换模」。未确认前不盲改。
-- 2) is_final 语义保持「末道 = 调试检验」：氚云把调试并入「组装调试」一道报工，ERP 拆成
--    「组装调试 + 调试检验」两道 —— 这是利强真实存在的出厂前调试动作，不因氚云未报就删除。
--    自动放行逻辑（决策 #16）依赖 requires_inspection，与本列无关。
-- 3) manufacturing_operations.reporting_qr / source_inventory_quantity / default_workshop 仍留空：
--    氚云报工靠扫码，ERP 走页面上报；按决策 #15 氚云不做 ERP 数据层，暂不接二维码。
