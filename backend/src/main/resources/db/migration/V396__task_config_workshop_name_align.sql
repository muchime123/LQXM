-- 车间名副本与外键对齐：让「副本」不再是第二个真相。
--
-- 背景：manufacturing_task_config_items 同时存 workshop_id（外键）与 workshop_name（副本）。
-- 副本靠 ManufacturingTaskConfigController.updateWorkshopMaster 里的 UPDATE 手工级联，
-- 漏一处（导入、脚本、直接改库）就会与外键漂移，而旧的匹配口径恰好读副本 ⇒ 静默错配到别的车间。
--
-- 2026-10-20 起匹配口径一律走 workshop_id 反查 manufacturing_workshops：
--   · ProductionTrackingService.benchmarksForOrder（参考日期）
--   · ProductionTrackingService.refreshProductBenchmarkCandidates（历史样本）
--   · ManufacturingTaskConfigController.list / duration-coverage（历史工单数、覆盖率）
-- 副本降级为展示兜底（list 里 COALESCE(w.workshop_name, c.workshop_name)）。
-- 本次把缺失与漂移的副本按外键写正，使任何残留的旧口径读到的值与新口径一致。
-- 幂等：可重复执行（第二次无行可改）。

UPDATE manufacturing_task_config_items c
JOIN manufacturing_workshops w ON w.id = c.workshop_id
SET c.workshop_name = w.workshop_name
WHERE c.workshop_id IS NOT NULL
  AND (c.workshop_name IS NULL
       OR c.workshop_name = ''
       OR c.workshop_name <> w.workshop_name);
