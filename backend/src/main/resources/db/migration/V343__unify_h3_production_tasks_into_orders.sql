-- =====================================================================
-- V343: 氚云生产任务接入本地工单表（两类任务合一；LQ 2026-09-24 拍板）
--
-- 背景：车间执行长期两页两链——「本地生产任务 · 车间执行」管 manufacturing_orders
-- （7 张），「车间执行 · 氚云生产任务」管 production_orders（5,691 张），互不可见，
-- 动作也各一套。LQ 要求：氚云任务必须能做本地任务相同的各项处理，两者合在一起。
--
-- 与 2026-09-22「双轨制镜像」（已由 V327 全撤）的区别——那次失败的实测结论是：
--   ① 镜像单不可操作（护栏锁死 → 只读的脏数据复制品）；
--   ② 生产侧 4,613 张是已完成历史单，镜像过来毫无用处。
-- 本次前提已变：
--   ① 本地执行链（建单 / 报工 / 检验入库 / 冲正 / 收货）已建成并活体验证
--      （#30/#31、V331/V333/V337），氚云任务接进来是**有动作可做的**；
--   ② 氚云生产任务导入已于 2026-09-17 停止（7 天无新增、无同步），是准一次性快照，
--      不存在「源持续变更、物化行持续漂移」的问题。
--
-- 原则：
-- * 只写业务表；production_orders / production_order_components 一行不动（只读台账）。
-- * 幂等可重跑：任务头走 uk_manufacturing_external(external_source,external_id) upsert；
--   子表（工序行 / 产出行）只为「尚无子行的氚云来源单」补插，绝不删改已有行。
-- * 可整批回退：与 V327 同款的 DELETE ... WHERE external_source='H3YUN' 即可撤回。
-- * 已入库的 5,210 张**不补造本地完工入库单**——入库事实已在氚云台账，重复补造会让
--   库存翻倍（#27 纪律）。它们只作为「已完结任务」出现在任务池与看板里。
-- * 来源进度直接写入工序行 reported_quantity（= 累计已报工）：本地模型里该列语义就是
--   「累计生产事实」，来源与本地都是事实提供方；不另开一套基线列，避免两套口径。
--
-- 映射依据（2026-09-24 实测）：
-- * work_order_no 5,691/5,691 唯一且非空 → order_no
-- * external_id 5,691/5,691 唯一 → external_id + external_source='H3YUN'
-- * 组件名 10 种，14,367 行里仅 6 行 component_name 为空 → 与 manufacturing_operations
--   .operation_name 精确匹配（H3_* 那批工序本就是为氚云组件名建的），对不上的行跳过
-- * 氚云无单位字段 → unit 取 '台'（LQ 拍板：全链路先按台）
-- =====================================================================

-- ---------- 1. 任务头：氚云生产任务 → 本地工单（幂等 upsert） ----------
INSERT INTO manufacturing_orders (
    order_no, external_source, external_id, h3_work_order_no,
    task_type, priority, customization_mode,
    planned_quantity, completed_quantity, unit,
    product_name_snapshot, customer_id, customer_name_snapshot,
    salesperson_id, salesperson_name_snapshot,
    workshop_type, due_date, expected_finish_date,
    status, inspection_status, production_completed_at,
    source_status_raw, source_progress_raw, source_warehoused_quantity,
    created_by_name, created_at, updated_at
)
SELECT
    p.work_order_no,
    'H3YUN',
    p.external_id,
    p.work_order_no,
    'SALES_ORDER',
    'NORMAL',
    'STANDARD',
    COALESCE(p.required_quantity, 0),
    COALESCE(p.completed_quantity, p.warehoused_quantity, 0),
    '台',
    COALESCE(NULLIF(TRIM(REPLACE(REPLACE(p.product_name, '\r', ' '), '\n', ' ')), ''),
             '(氚云产品名缺失)'),
    p.customer_id,
    p.customer_name,
    p.salesperson_id,
    p.salesperson_name,
    p.production_line,
    p.required_completion_date,
    p.expected_completion_date,
    CASE
        WHEN p.status = '已取消' THEN 'CANCELLED'
        -- ⚠️「来源已入库」判据必须与氚云执行域单一来源：数量>0 **或** 标记=1。
        --    只用标记会漏 17 张（数量已入但标记未置），与看板口径分裂（实测 5,227 vs 5,210）。
        WHEN p.warehousing_completed = 1 OR COALESCE(p.warehoused_quantity, 0) > 0 THEN 'COMPLETED'
        WHEN x.component_count > 0 AND x.done_count = x.component_count THEN 'PENDING_INSPECTION'
        WHEN COALESCE(x.done_count, 0) > 0 OR COALESCE(p.completed_quantity, 0) > 0 THEN 'IN_PRODUCTION'
        ELSE 'RELEASED'
    END,
    CASE
        WHEN p.status = '已取消' THEN 'NOT_REQUIRED'
        WHEN p.warehousing_completed = 1 OR COALESCE(p.warehoused_quantity, 0) > 0 THEN 'PASSED'
        WHEN x.component_count > 0 AND x.done_count = x.component_count THEN 'PENDING'
        ELSE 'NOT_REQUIRED'
    END,
    p.actual_completion_date,
    p.status,
    p.production_progress,
    p.warehoused_quantity,
    '氚云导入',
    COALESCE(p.source_created_at, p.created_at),
    NOW()
FROM production_orders p
LEFT JOIN (
    SELECT c.production_order_id,
           COUNT(*) AS component_count,
           SUM(CASE WHEN c.completed = 1 OR c.completed_at IS NOT NULL
                     OR (COALESCE(c.required_quantity, 0) > 0
                         AND COALESCE(c.completed_quantity, 0) >= c.required_quantity)
                    THEN 1 ELSE 0 END) AS done_count
      FROM production_order_components c
     WHERE c.component_name IS NOT NULL AND TRIM(c.component_name) <> ''
     GROUP BY c.production_order_id
) x ON x.production_order_id = p.id
WHERE p.external_source = 'H3YUN'
ON DUPLICATE KEY UPDATE
    h3_work_order_no           = VALUES(h3_work_order_no),
    planned_quantity           = VALUES(planned_quantity),
    completed_quantity         = VALUES(completed_quantity),
    unit                       = VALUES(unit),
    product_name_snapshot      = VALUES(product_name_snapshot),
    customer_id                = VALUES(customer_id),
    customer_name_snapshot     = VALUES(customer_name_snapshot),
    salesperson_id             = VALUES(salesperson_id),
    salesperson_name_snapshot  = VALUES(salesperson_name_snapshot),
    workshop_type              = VALUES(workshop_type),
    due_date                   = VALUES(due_date),
    expected_finish_date       = VALUES(expected_finish_date),
    source_status_raw          = VALUES(source_status_raw),
    source_progress_raw        = VALUES(source_progress_raw),
    source_warehoused_quantity = VALUES(source_warehoused_quantity),
    updated_at                 = NOW();

-- ---------- 2. 工序行：氚云制程组件 → 工单工序（只为尚无工序的单补插） ----------
INSERT INTO manufacturing_order_operations (
    manufacturing_order_id, source_template_operation_id, operation_id, sequence_no,
    operation_name_snapshot, requirement_coefficient, required_quantity, reported_quantity,
    execution_round, required, is_final, allow_parallel, status, completed_at, created_at, updated_at
)
SELECT
    m.id,
    NULL,
    o.id,
    COALESCE(c.sequence_no, ROW_NUMBER() OVER (PARTITION BY c.production_order_id ORDER BY c.id)),
    TRIM(c.component_name),
    COALESCE(c.requirement_factor, 1),
    COALESCE(c.required_quantity, 0),
    COALESCE(c.completed_quantity, 0),
    0,
    1,
    COALESCE(c.final_process, 0),
    0,
    CASE WHEN c.completed = 1 OR c.completed_at IS NOT NULL
              OR (COALESCE(c.required_quantity, 0) > 0
                  AND COALESCE(c.completed_quantity, 0) >= c.required_quantity)
         THEN 'COMPLETED' ELSE 'PENDING' END,
    c.completed_at,
    COALESCE(c.created_at, NOW()),
    NOW()
FROM production_order_components c
JOIN production_orders p ON p.id = c.production_order_id
JOIN manufacturing_orders m
     ON m.external_source = 'H3YUN' AND BINARY m.external_id = BINARY p.external_id
JOIN manufacturing_operations o ON o.operation_name = TRIM(c.component_name)
WHERE p.external_source = 'H3YUN'
  AND c.component_name IS NOT NULL AND TRIM(c.component_name) <> ''
  AND NOT EXISTS (SELECT 1 FROM manufacturing_order_operations e WHERE e.manufacturing_order_id = m.id);

-- ---------- 3. 产出行：一单一条主产出（收货按产出清单；已入库量作为已收量） ----------
INSERT INTO manufacturing_order_outputs (
    manufacturing_order_id, line_no, is_primary, product_code_snapshot, product_name_snapshot,
    unit, planned_quantity, received_quantity, is_mold, created_at, updated_at
)
SELECT
    m.id,
    1,
    1,
    p.product_order_external_id,
    COALESCE(NULLIF(TRIM(REPLACE(REPLACE(p.product_name, '\r', ' '), '\n', ' ')), ''),
             '(氚云产品名缺失)'),
    '台',
    COALESCE(p.required_quantity, 0),
    CASE WHEN p.warehousing_completed = 1 OR COALESCE(p.warehoused_quantity, 0) > 0
         THEN COALESCE(p.warehoused_quantity, p.required_quantity, 0) ELSE 0 END,
    0,
    COALESCE(p.source_created_at, p.created_at),
    NOW()
FROM production_orders p
JOIN manufacturing_orders m
     ON m.external_source = 'H3YUN' AND BINARY m.external_id = BINARY p.external_id
WHERE p.external_source = 'H3YUN'
  AND NOT EXISTS (SELECT 1 FROM manufacturing_order_outputs e WHERE e.manufacturing_order_id = m.id);

-- ---------- 4. 回写台账侧反向指针（指向刚接入的工单；V327 曾清零） ----------
UPDATE production_orders p
JOIN manufacturing_orders m
     ON m.external_source = 'H3YUN' AND BINARY m.external_id = BINARY p.external_id
   SET p.linked_manufacturing_order_id = m.id,
       p.linked_manufacturing_order_no = m.order_no
 WHERE p.external_source = 'H3YUN';
