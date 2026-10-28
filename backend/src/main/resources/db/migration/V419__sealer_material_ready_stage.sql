-- 封口机新增「物料配齐」节点（2026-10-27 跟单员需求）：排在氧化之后、组装之前，**必经**节点。
--
-- 节点序列本身不落库（唯一源＝ ProductionTrackingPolicy.SEALER_STAGES），本脚本只处理数据侧的两件事：
--   ① 补车间通用参考工期：缺这一行 ⇒ 该节点的「参考完成 / 目标完成」推不出来，倒排链当场断掉；
--   ② 存量工单回填：组装及之后已经开工的单，物料必然已配齐，补一条 DONE 记录；
--      不回填的话，九月以来几百张封口机单会在节点上线当天集体退回「跟单中」并冒出逾期提醒。
--
-- 新序列：备料 → 模具生产 → 氧化/喷特氟龙(按需) → 物料配齐 → 组装 → 入库 → 木箱(按需)
-- 封口机必经节点数 4 → 5（「x/y 已完成」的 y 随之变化）。
--
-- ⚠️ 回填 completed_date 是**推算值**（取该单最早的后置已完成节点日期），不是跟单员登记的事实，
--    它会进入「刷新候选」的历史样本统计。故产品级（production_tracking_product_benchmarks）的
--    物料配齐间隔请以人工发布为准，不要直接批量采用 P50。
--    回填标记写在 note 上，可识别也可一键清除：
--      UPDATE production_tracking_milestones SET note=NULL
--       WHERE stage_code='MATERIAL_READY' AND note='节点新增回填：该单当时已开工组装';

-- ① 车间通用参考工期：占位 0 天（与备料、入库同一口径：排产当日即应齐套），待积累实测样本后按中位数发布。
-- 用 INSERT IGNORE：管理员若已先手工配过值，不被覆盖。
INSERT IGNORE INTO production_tracking_node_benchmarks(workshop_kind,stage_code,typical_days,sample_count,baseline_note,computed_at) VALUES
    ('SEALER','MATERIAL_READY',0.0,0,'节点新增时的占位值：待积累实测样本后按中位数发布','2026-10-27');

-- ② 存量回填：封口机工单，且组装/入库/木箱任一节点已开工（stage_status<>'PENDING'）⇒ 已越过物料配齐。
-- 完成日期取该单最早的后置已完成节点日期（组装为直接后继）；组装仍在进行中时为 NULL。
-- INSERT IGNORE：已存在的行不覆盖（跟单员后来改过的不动），重跑幂等。
INSERT IGNORE INTO production_tracking_milestones(production_order_id,stage_code,stage_status,completed_date,note)
SELECT p.id,'MATERIAL_READY','DONE',
       (SELECT MIN(m2.completed_date) FROM production_tracking_milestones m2
          WHERE m2.production_order_id=p.id
            AND m2.stage_code IN ('ASSEMBLY','WAREHOUSE','WOODEN_CRATE')
            AND m2.stage_status='DONE' AND m2.completed_date IS NOT NULL),
       '节点新增回填：该单当时已开工组装'
FROM production_orders p
WHERE p.production_line LIKE '%封口%'
  AND EXISTS (SELECT 1 FROM production_tracking_milestones m
               WHERE m.production_order_id=p.id
                 AND m.stage_code IN ('ASSEMBLY','WAREHOUSE','WOODEN_CRATE')
                 AND m.stage_status<>'PENDING');
