-- 删除 manufacturing_task_config_items.active（2026-10-21 LQ 定）。
--
-- 为什么删：这张表的两类数据都是氚云生产任务单下拉字典的镜像——
--   PRODUCT_NAME    ← 任务单「产品名称」下拉项
--   OPTIONAL_CONFIG ← 任务单「可选配置」下拉项
-- 能不能选由氚云决定，LQXM 里的开关既不影响氚云、也不会让本地拒单，只会造出「氚云照常在用、
-- 这边显示停用」的假状态。业务消费方在改动前已逐处核查并移除：
--   · 覆盖率 duration-coverage（productMatched / benchmarkMatched）
--   · 跟单参考工期 benchmarksForOrder
--   · 产品级基准矩阵 productBenchmarkMatrix
-- 可选配置在代码侧本来就没有消费方（工单上的 optional_configuration 是自由文本，不查这张表）。
--
-- 数据面（改动前实测）：产品 50 行 + 可选配置 70 行，active 全为 1，没有任何一行处于停用状态
-- ⇒ 删列零信息损失；删列前后覆盖率数字完全一致（6048 单 / 匹配产品 6044 / 命中已发布间隔 1707）。
--
-- 写入侧同步收口：新增配置不再写 active；PUT /task-configs/{id}（改名 + 启停）整体下线，
-- 纠错路径统一为「删除后重新补录」，删除带硬保护（产品：有基准行 / 有历史任务；可选配置：历史任务用过）。

ALTER TABLE manufacturing_task_config_items DROP COLUMN active;
