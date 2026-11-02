-- 节点适用性与「已完成但无日期」的来源标记。
--
-- 背景：节点完成有两个互不相干的事实 —— 「节点有没有做完」和「有没有可信的完成日期」。
-- 过去两者被同一个字段表达（completed_date 为空 == 没做完），于是「做完了但没人登记日期」在生产跟单里
-- 只能表现为「未完成」。按需工序（木箱/氧化）尤其明显：源单配置写了要做就得登记，没登记就永久显示为未完成。
--
-- 本迁移只加三列，不改变任何既有行的语义：
--   · 三列全部可空，且默认 NULL = 「没有人工确认过」，此时系统继续沿用源单关键词规则，行为与今天完全一致；
--   · DONE 无日期只能伴随 NO_DATE 与人工依据写入，避免出现无法解释的裸数据。
--
-- ⚠️ 刻意不做的事：
--   1. 不复用 manual_reference_date —— 它是 V402 定义的「跟单员填写的前瞻预计日期」，
--      参与 predictedFinishDate / scheduleRisk 的派生计算，方向与「历史完成日」相反；
--   2. 不新增 reference_date —— 推断日期既不参与工期计算也不参与完成判定，落库的收益低于维护成本，
--      需要时由界面实时按「采购入库日 / 后续节点日」计算展示；
--   3. 不新增 stage_status 枚举值（如 N/A）—— 现有 stage_status<>'DONE' 的判断遍布列表/详情/导入，
--      用独立列表达适用性能把影响面收拢到「分母计算」一处。
--
-- ⚠️ 重新导入氚云数据时：production_tracking_milestones 的 UPSERT 语句不写这三列（见 upsertNode 的列清单），
--    因此源单字段变化不会覆盖人工确认结果。

ALTER TABLE production_tracking_milestones
  ADD COLUMN applicability      VARCHAR(20) NULL COMMENT 'APPLICABLE / NOT_APPLICABLE / PENDING_REVIEW；NULL=沿用源单规则',
  ADD COLUMN applicability_note VARCHAR(300) NULL COMMENT '确认或变更适用性的原因与依据',
  ADD COLUMN completion_source  VARCHAR(20)  NULL COMMENT 'NO_DATE=已完成但无日期 / MANUAL=人工补登记真实日期';

CREATE INDEX idx_ptm_completion_source ON production_tracking_milestones(completion_source);
