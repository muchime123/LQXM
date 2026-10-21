# 数据库瘦身审计（product_archive，2026-10-20 · 修订版 v2）

> **口径**：数字全部取自真库精确计数（`information_schema` 表空间 + `COUNT(*)`），不是转储文件估算。
> **基线**：210 张表 / **6,777 MB**（数据 6,354 MB + 索引 423 MB）。
> **本版修订**：按 LQ 2026-10-20 审核意见重写。清理脚本同步改为 v2（`db-slimming-cleanup-20261020.sql`）。

---

## 一、结论

不是「废表太多」，而是**同一份数据被反复全量重导、导入链路对历史副本零清理**：

| 项目 | 体积 | 占比 |
|---|---|---|
| `h3_import_staging_rows`（待确认暂存行） | 3,070 MB / 407,516 行 | 45.3% |
| `h3_import_rows`（已确认原始快照） | 2,712 MB / 346,694 行 | 40.0% |
| `h3_import_batches`（批次元数据 + preview_json） | 113 MB / 56 批 | 1.7% |
| **导入三件套合计** | **5,895 MB** | **87.0%** |
| 其余 207 张业务表 | 882 MB | 13.0% |

证据：采购入库 10-09～10-20 导了 10 次，每次同一份全量文件（≈11,300 行 × 5.4 KB）；生产任务单自 10-07 起每天一次全量快照（5,863 → 6,024 行）。全仓只有 `H3ResetService`（整库重置）会清，日常导入零清理。

**这正是必须改代码的原因**：只要还是「氚云导出 → 导入」的人工同步方式，重复导入就是正常的（一天多次也可能），不建立自动清理，删完立刻又涨回来。

---

## 二、本次修订（LQ 审核意见的处理）

| # | 问题 | 处理 |
|---|---|---|
| 1 | 报告说「采购入库保留 2 批、生产任务单保留 3 批、销售出库全留」，脚本却是 `PARTITION BY data_type` + `rn > 3`，会误伤其它类型 | 脚本 Part 2 改为**显式枚举** `data_type IN ('PURCHASE_IN','PRODUCTION')`，其它类型（含 OUTBOUND）一律不碰；保留数用 `IF(data_type='PURCHASE_IN',2,3)` 表达 |
| 2 | 原始快照不能只按空间删（旧批次告警明细、来源批次、导入时的新旧字段对照都依赖它） | 改为**三步走**：Step A 核对是否真重复 → Step B 生成待删清单（不删）→ Step C 人工逐批执行；并新增**逐 `external_id` 保护**：每个外部单据至少保留最新一条快照（`loadPreviousSnapshots` 依赖它做新旧值对照） |
| 3 | 清单与真库不一致（Tier2 标 19 实列 27、Tier3 标 25 实列 19） | 脚本 Part 3/4 **取消所有硬编码 DROP**，全部改为查询生成；报告数字按真库重测（见下表） |
| 4 | 不应把 `h3_import_staging_rows` 排除在日常备份外（里面有 `PREVIEW_READY` 在途数据） | 备份章节重写：全量备份 + 在途批次单独备份，**不排除任何表** |

### 真库重测（修正原报告偏差）

| 项目 | 原报告 | 真库实测 |
|---|---|---|
| 遗留/备份类表 | 19 张 / 127 MB | **32 张 / 213 MB**（逐张 `COUNT(*)` 精确计数；其中 2 张是「假遗留」，见下） |
| 0 行表 | 58 张 / 165 MB | **估算 0 行 88 张 → 精确 0 行 81 张**，**一张都不建议 DROP** |
| 暂存行 | 407,516 行 | 407,516 行 ✅（其中 COMPLETED 378,722 + PREVIEW_READY 28,794 + FAILED 0） |

**⚠️ v3 更正（2026-10-20 15:10，Part 3 盘点后）——原报告有两处结论是错的：**

1. **`receipts`(156 MB) / `receipt_allocations`(80 MB) 不是「空表」**，是结算台账的**核心事实表**
   （`SettlementLedgerService` / `H3FactImportService` / `H3ResetService` 等 14+ 处 SQL）。
   0 行只是「已清空、待重导」。它们占的是**空洞**，正确处置是 `ALGORITHM=COPY` 重建，不是 DROP。
   同类的还有 `h3_reset_identity_snapshots`(25 MB) / `h3_production_reports`(21 MB)。
2. **两张「名字像垃圾、实际是活表」必须剔除**：
   - `production_tracking_workbook_archive_rules` —— `ProductionTrackingService` 的 INSERT/UPDATE/SELECT + 审计
   - `product_models_archive` —— `ArchiveScheduler` 每晚 02:00 写入的归档目标表

详细逐表判定见 **`docs/db-part3-legacy-tables-20261020.md`**。

---

## 三、已完成的代码改造（本轮，已落地）

### 3.1 终态批次自动清理暂存行

新服务 `H3StagingCleanupService`，两个调用点已接入：

| 域 | 接入位置 | 时机 |
|---|---|---|
| A 域（业务事实：出库/收款/结算/采购/生产任务…） | `H3PostImportAsyncService.run` | 编排跑完、批次切 `COMPLETED`/`PARTIAL_FAILED` **之后** |
| B 域（产品信息/仓库明细/经销商台账） | `ProductInventoryLedgerImportService.confirm` | `@Transactional` 事务 **afterCommit** |

**清理判据（三道闸，缺一不删）**：
1. 批次状态 ∈ `COMPLETED` / `PARTIAL_FAILED` —— 状态机已禁止再次确认，暂存对业务无作用
2. `preview_json` 非空 —— 暂存唯一的残留用途是预览缓存缺失时的回退路径，preview_json 在则该路径永不触发
3. `UPLOADED / PARSING / PREVIEW_READY / CONFIRMING / FAILED` 一律不动 —— 在途数据、可重试数据

**安全设计**：
- 清理在业务事务**提交之后**执行（A 域两条语句各自提交；B 域显式 `afterCommit`）。确认失败时暂存必须还在，否则这批数据无法重试。
- 分批 DELETE（每批 5,000 行），避免长事务把元数据锁拖死（`loadPreviousSnapshots` 的历史教训）。
- 清理任何异常只记 WARN，不向上抛、不影响已落定的批次终态。
- 开关：`h3.import.staging-cleanup.enabled`（默认 `true`），排查期可整体停用。
- 提供 `purgeAllTerminalBatches(maxBatches, dryRun)` 供存量治理（与自动清理同一套判据）。

### 3.2 重复文件检测（只提示，不拦截）

- 判据：**同 `data_type` + 同文件内容 SHA-256（不是文件名）**，且历史批次状态为 `COMPLETED`/`PARTIAL_FAILED`
- 返回值：`StageAccepted.duplicateHint = { batchId, fileName, completedAt }`，未命中为 `null`
- 前端展示（`H3ImportPreviewDialog` 顶部提示条 / 生产任务单导入弹窗 toast）：
  > 该文件曾于 **今天 09:51** 导入（批次 #285）
  > 你仍然可以继续导入。系统会按当前数据更新业务记录：新增的插入、有变化的更新、无变化的不动。
- **不改变任何同步规则**：仍然建新批次、重新解析、逐条 CREATE/UPDATE/UNCHANGED 比对；未出现在本次文件里的记录不做删除（氚云导出可能带筛选条件）。

> 与原有 `hashHit` 的区别：`hashHit` 是「同一文件在**未确认**状态下重传 → 复用解析结果、不重复解析」；`duplicateHint` 是「这个文件**以前确认过** → 照常导入，只是告知」。

---

## 四、生产跟单是否需要原始快照（LQ 关注点，已核实）

**结论：不依赖，可以安全治理。**

- 9 个跟单服务（`ProductionTrackingService` / `ProductionTrackingAnalyticsService` / `ProductionTrackingWeeklyReviewService` / `ProductionTrackingRoundTripService` / `ProductionOutboundTraceService` …）对 `h3_import_rows`、`h3_import_staging_rows`、`h3_import_batches` 的引用数为 **0**。
- 跟单读的是**业务事实表** `h3_production_orders` 及其工序/节点相关表，以及本地跟踪表；工序变化与参考完成时间的样本来自跟单自己的表，不是导入快照。
- 全仓消费 `h3_import_rows` 的只有 4 处：`H3FactImportService`（写入 + 批次告警明细重算 + 新旧字段对照）、`H3BusinessQueryService`（来源追溯只读）、`CommissionCalculationService`（提成出库变化跟踪）、`H3ResetService`（整库重置）。
- ⚠️ 但结论有前提：**如果将来要让跟单基于「原始导入快照」做工序历史变化分析，Tier 1-2 必须整体作废**。当前系统没有「每次导入的工序变化差异表」，所以快照确实是唯一的历史来源——只是现在没有任何功能在用它。

---

## 五、分级处置方案（按 LQ 批准的优先级）

| 优先级 | 动作 | 状态 | 回收 |
|---|---|---|---|
| **P1** | 代码：终态批次自动清理暂存行 | ✅ 已完成并编译通过 | 从源头止住（每批 ≈300MB 不再累积） |
| **P1** | 代码：重复文件哈希提示 | ✅ 已完成 | — |
| **P2** | 存量：清理已终态批次暂存行 | 脚本 Part 1 可直接执行 | ≈2.9 GB |
| **P3** | 快照治理：`PURCHASE_IN` 留 2 批 / `PRODUCTION` 留 3 批 | 脚本 Part 2，三步确认后人工执行 | ≈1.1 GB |
| **P4** | 遗留表 / 空表 DROP | **Part 3 已盘点完成**；零风险段已执行（-412 MB） | 遗留表 213 MB 待 LQ 拍板 |
| **P4** | 空洞表 `ALGORITHM=COPY` 回收 | ✅ **已执行（9 张 / -348 MB）** | 见 Part 3 第四、六节 |

### P2 存量清理（与代码同一判据）

- 预删：`COMPLETED` 51 批 / 378,722 行（≈2.9 GB）
- 保留：`PREVIEW_READY` 3 批 / 28,794 行（采购入库 2 批在途、生产任务单 1 批在途）——**这批绝不能被删，删了用户当天的工作就白做**
- 执行后暂存表应只剩 28,794 行；`OPTIMIZE TABLE h3_import_staging_rows` 后才真正释放磁盘

### P3 快照治理的影响面（需接受）

| 影响 | 说明 |
|---|---|
| 旧批次「告警明细」页变空 | 该页按 `h3_import_rows.raw_data` 重算；保留批次仍可看 |
| 老单据「来源批次」可能留空 | 仅当该单据只出现在被删批次里；`external_id` 最新一条已被保护 |
| 下次导入的「新旧字段对照」 | 每个 `external_id` 最新一条快照已保护，绝大多数单据不受影响 |
| 台账「行历史」抽屉 | **不受影响**（读 `preview_json`，不读 `h3_import_rows`） |

### 暂缓项（P4，等结构稳定）

- **本地制造域 5 张表**（`manufacturing_orders` / `finished_goods_lots` / `manufacturing_order_outputs` /
  `manufacturing_completion_receipt_items` / `finished_goods_movements`）：LQ 明确等新的生产管理结构稳定后再决定。
  **v3 补充**：实测这 5 张**全部 0 行、合计 1.23 MB、代码零引用**，删了也只省 1.2 MB，
  瘦身价值为零。但它们与 `V367__drop_manufacturing_execution_tables.sql.pending`（全仓唯一 pending 迁移）
  绑在一起 —— 建议单独作为「迁移收口」议题处理，不混在瘦身里。
- **81 张精确 0 行表**：实测逐张核查后**一张都不建议 DROP**。它们分四类（核心事实表待重导 /
  本地制造域 / 客户治理审计链 / 产品包装 JPA 映射），删任一类都会让功能报错或丢失回滚能力。
  真正该做的是其中 12 张的空洞回收 —— 已执行。
- **31 张遗留表**：逐张精确计数 + 代码引用已核查完毕，判定表见 Part 3 文档。
  DROP 语句已备好但**未执行**，等 LQ 确认两个风险：① `bak_20260921_sales_products`(4,920 行)
  是 09-21 之前唯一的销售产品快照 ② Flyway 迁移仍在，`clean` 重放会把这些表重新建出来。

---

## 六、备份策略（修正）

❌ **不要**：`mysqldump --ignore-table=h3_import_staging_rows`
→ 表里有 `PREVIEW_READY`（已上传未确认）批次，排除后库故障恢复时这些批次无法继续确认。

✅ **要**：
1. 全量备份（含三张导入表，`--single-transaction` 不锁表）
2. **在途批次单独备份**（几十 MB）：`--where="batch_id IN (...)"` 只导 `PREVIEW_READY/PARSING/UPLOADED/CONFIRMING` 的暂存行
3. Part 2 执行前，`h3_import_rows` 单独留档

具体命令见脚本 Part 0-B。

---

## 七、执行建议

1. **先发代码**（P1）：重启后端后，后续每次确认都会自动清理，不再新增。
2. **再清存量**（P2）：脚本 Part 1，先 `CALL sp_purge_staging_terminal(5000, 1)` 试算，确认数字为 378,722 后再正式执行。
3. **验证**：导入一次 → 确认 → 观察 `h3_import_staging_rows` 该批次行是否归零；打开历史批次预览与台账行历史，确认可用。
4. **P3/P4 等 P2 稳定运行几天后再议**。

预期与实际：

| 阶段 | 预期 | 实际 |
|---|---|---|
| P2 暂存清理 | 6,777 → 约 3.8 GB | ✅ 3,145 MB（`h3_import_staging_rows` 3,070 → 0.2 MB） |
| P3 快照治理 | 再降 ≈1.1 GB | ✅ `h3_import_rows` 2,956 → 1,384 MB（删 192,133 行） |
| P4 零风险段（已执行） | — | ✅ 再降 **412 MB** → **全库 2,733 MB** |
| P4 遗留表 DROP（待批） | 213 MB | ⏸ 等 LQ |
| P4 本地制造域 | — | ⏸ 仅 1.23 MB，无价值，转「迁移收口」议题 |

**总账：6,777 MB → 2,733 MB（-59.7%），尚未发布、尚未重启服务。**
