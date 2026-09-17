# 氚云回款链路收敛设计：单一权威来源 + 管线/核算一致性（2026-09-07）

> 状态：**P1 已落地（2026-09-07）**；P2 第一包已落地（2026-09-08：异步收尾 + attribution_date 索引），分片事务/批量写入待继续；P3/P4 按 §7 顺序推进。
> 关联文档：[氚云数据全链路串联文档.md](../../氚云数据全链路串联文档.md)、
> docs/design/monthly-commission-specialized-settlement-plan-20260904.md

---

## 0. 目标

| # | 目标 | 本文对应章节 |
|---|------|------|
| G1 | 消除 `sales_outbounds.received_amount` / `sales_outbound_payment_snapshots` / `receipts` 三套回款数据口径分裂，建立**单一权威来源**与一致性不变量 | §2、§3、§4 |
| G2 | 解决导入编排、快照刷新、提成核算的**性能瓶颈**与**数据更新实时性** | §5、§7(P2/P3) |
| G3 | 保证经销商/零售判定、运费窗口、修正归属、实时参考与正式版本**一致** | §6、§7(P3) |
| G4 | 减少冗余表/字段、合并重复逻辑、简化编排步骤，降低维护成本 | §8、§7(P1/P4) |

**已确认决策 D1（用户拍板）**：一致性不变量以 `sales_outbounds.received_amount`（氚云主表最终已收款，财务现行口径）为**唯一权威**；当与快照明细 SUM 冲突时，由系统**自动补/删一条“平衡差额行”**（`sales_outbound_payment_snapshots`），保证明细合计恒等于主表；差额行金额参与的月度归属，日期由既定规则确定（见 D2）。

---

## 1. 术语与角色说明（先回答“receipts 域是什么”）

用户对 receipts 域不熟悉，先说明：

| 对象 | 是什么 | 当前是否参与回款 | 谁在读/写 |
|---|---|---|---|
| `sales_outbounds` | 氚云「销售出库」台账主表（唯一财务事实） | **唯一权威**（2026-08-29 财务确认） | 导入写入；台账/明细/客户档案/月度汇总全量读取 |
| `sales_outbound_payment_snapshots` | 出库单上内嵌的「收款详细」子表行（一单可多笔 #R1/#R2…），V170 从旧 `receipts.OUTBOUND_EMBEDDED` 迁出 | 月度提成的**回款金额归属载体**（按 `receipt_date` + `legacy_status=CONFIRMED`） | 导入重建写入；月度实时/正式核算读取 |
| `receipts` | 氚云「收款记录 / 多订单结清」两类单据的**主单台账**（CKxx…，自 2026-08-29 起仅存档，不再产生回款/分配） | **否**（台账角色） | RECEIPT/SETTLEMENT 导入写；导入中心“收款/结清”台账只读展示 |
| `receipt_outbound_links`、`settlement_outbound_links` | 收款/结清单 → 覆盖出库单的**血缘关联** | 否（血缘展示） | 随导入写；台账详情抽屉展示（settlement rows） |
| `receipt_allocations` | 旧版“收款→出库核销分配”明细（V170 前回款计算核心，V170 后仅 DIRECT/OUTBOUND_EMBEDDED 两类打标遗留） | **否（历史遗留）** | 已无任何写入方；仅 `SettlementLedgerService` 对账方法读取，前端零调用 |
| `SettlementLedgerService` 的 digest / 未分配池 / 差异清单方法 | 早期“财务对账”查询 | 否 | 全库无 Controller/Service 调用者（仅自身与死路径），前端 `api/` 无对应调用 |

---

## 2. 现状诊断（均已核对代码）

### 2.1 三套回款形态与各自权威

```
① sales_outbounds.received_amount
   导入直写（H3FactImportService saveOutbound / syncUnchangedOutbound:603）
   同时冗余写 source_received_amount（同值双写）
   └─ 消费：台账/明细、订单利润、客户档案、H3BusinessQueryService
② sales_outbound_payment_snapshots（每出库 1..N 行）
   每次导入 DELETE(本批 LIVE_IMPORT) + 逐行 INSERT（H3FactImportService:621-668）
   └─ 消费：月度提成实时 summary(MonthlySalesCommissionService:103-117)
         与正式版本(CommissionCalculationService loadUnsettledSources/summarizeSources:441-442)
③ receipts + receipt_outbound_links + receipt_allocations（台账域）
   saveReceipt(:431)/saveReceiptOutboundLinks(:503，明确不写 receipt_allocations)
   └─ 消费：导入中心 receipt/settlement 台账只读（H3ImportBatchController:100/126 → H3BusinessQueryService）
          + SettlementLedgerService 遗留对账方法（无消费者）
```

**分裂本质**：② 的 `SUM(快照明细)` 与 ① 的 `received_amount` **没有任何一致性约束**。
- V170 迁移注释自述：92,625 条内嵌收据中 **29.9%（27,657）与主表 received_amount 对不上**（`db/migration/V170__*.sql` 头注）。
- 快照表 `received_amount` 列注释写明“合计不一定等于出库.received_amount”（`V170__*.sql:26`）。
- 因此同一张出库单在“台账/订单页/客户档案”（读主表）与“月度提成”（读快照明细）上**可能显示两个回款数**——这是 G1 要根治的问题。

### 2.2 字段级冗余（五轮加列的修补痕迹）

`sales_outbounds` 上由 V150/V166/V174/V175/V197 累积出的派生/冗余列：

| 列 | 实际行为 | 消费方 | 结论 |
|---|---|---|---|
| `source_received_amount` | 与 `received_amount` **同源同值**双写 | 仅写入处 + 台账带出；前端 0 引用 | **可删**（值并入 received_amount） |
| `snapshot_received_amount` | refresh 时 = displayReceived 恒等回写 | 写入处自身 | **可删** |
| `received_source` | 恒 `SNAPSHOT`(≠0)/`NONE`(0) | 同上 | **可删** |
| `received_verified` | **恒 true**（代码注释：子表即财务审核结果） | 同上 | **可删** |
| `received_diff_amount` | diff=快照−received 恒 0 → 恒 NULL | 无 | **可删** |
| `payment_status`（中文：已结清/部分回款/未回款） | 与 `settlement_status`（英文枚举）同一 CASE 语义双写（V197 一次 UPDATE 同时写两列） | 台账展示 | 收敛为 `settlement_status` 一种 |

（出处：`SettlementLedgerService` 各 refresh 方法恒等回写 `:114-130`；`SalesOutboundSettlementService.refreshOutboundSnapshotById:47-67`；V150/V166/V175/V197 迁移注释。）

### 2.3 性能与实时性瓶颈（G2）

1. **确认写入为单一大事务 + 逐行高频 SQL**：
   `confirmFromStaging:1069` 一个 `@Transactional` 内逐 Group 执行 主表 UPSERT、明细/快照 DELETE+逐行 INSERT、利润与订单提成单行重算、`saveImportRow` 每行 JSON 序列化落库。
   每张出库约 **15+ 次 SQL 往返**；千行级文件 = 上万次单行 SQL，且**中间进度更新（5%→95%）都在同一未提交事务内，前端轮询看不到任何变化**（事务提交后才可见），大批次表现如同卡死。
2. **编排矩阵已萎缩但留有死代码 + 状态被错误打回**：
   - `stepsFor`（`H3ImportOrchestratorService:68-76`）：OUTBOUND → 仅 `MARK_OUTBOUND_PROFITS_PENDING`；RECEIPT/SETTLEMENT/PRODUCTION → 空。
   - `runStep` 中 `REFRESH_OUTBOUND_SNAPSHOTS / INCREMENTAL_MATCH / INVALIDATE_LINKS / REFRESH_BUSINESS_AGG` 四个分支**不可达死代码**（`:189-248`）。
   - `MARK_OUTBOUND_PROFITS_PENDING`（`:171-188`）把刚在导入事务里 **recalculate 成 CALCULATED 的利润整批打回 PENDING**，而全库**没有消费者会把 PENDING 重新算回**（调度器无此任务）→ 利润状态可能永久停在 PENDING。
   - `syncUnchangedOutbound`（`H3FactImportService:597-609`）**已无任何调用者**（UNCHANGED 分支在 confirm 循环里只 `touch()`），其中对派生列刷新/重算的调用随之失效；`refreshOutboundSnapshotById` 的调用者也仅剩上述死路径（orchestrator:198 / syncUnchanged:606）。
   → **结论：派生列（settlement_status/received_source 等）在现行导入链路里实际从不刷新**，与“数据更新实时性”目标严重不符。
3. **月度参考页每次现算全表**：
   `MonthlySalesCommissionService.summary:103-147` 无缓存逐请求聚合；运杂费/销售额 SQL 用 `COALESCE(o.ship_date,o.business_date)` 函数包裹做范围过滤（`:128,143`），**单列索引 `idx_so_ship_date` 无法使用**，大表下全扫。

### 2.4 口径一致性核对（G3）

| 项 | 判定 | 问题 |
|---|---|---|
| 经销商/零售判定（dealer_name 非空 OR 毛利率<15%） | **已集中**于 `CommissionPolicy.DEALER_CASE`，月度两引擎同串复用 | — |
| 运费窗口（结束月上一自然月 / CUSTOM） | **已集中** `CommissionPolicy.resolveFreightWindow`，双引擎复用 | — |
| 提成比例（含历史追溯 `rateHistory` / `rateAt`） | **已集中** `CommissionPolicy.loadRateHistory/loadRates/rateAt` | — |
| 实时 summary vs 正式 calculateVersion | 同一口径常量，但**两套不同实现**：实时=全量现算聚合；正式=先 `loadUnsettledSources` 锁行再 `summarizeSources`（`:441-442`）+ 修正变化并入（`:473-513`） | 代码级公式重复，历史出现过口径漂移；页面无法向用户解释“实时 ≠ 已结账” |
| 修正归属（trackOutboundChanges / commission_outbound_changes） | 单据级 before/after 基线唯一实现 | 与 base 双算防护已做（`:489-513`），需保留 |
| “已结清”判定 | 至少 3 处近似实现：`SettlementLedgerService.settlementStatusOf`（0.9999 容差）、`buildOutboundLedger:124-125`（中文档位）、`SalesOutboundProfitService.READINESS_CASE` | 阈值/语义微漂，应收敛到一处 |

### 2.5 冗余清单（G4）

- **表**：`receipt_allocations`（无写入、无前端消费）；财务对账遗留查询方法族（digest/未分配池/差异清单，无消费者）。
- **列**：§2.2 的 6 列 + 合并 `payment_status`。
- **代码/服务**：`syncUnchangedOutbound`（无调用）；编排 `runStep` 4 个不可达分支；`rejectStoppedFactTypes`（已为空方法）；`SalesOutboundSettlementService` 实例方法（仅被死路径调用，静态 normalize 保留）；`SettlementLedgerService` 与 `H3BusinessQueryService` 职责重叠（读端点只走后者，前者被后者 import 静态谓词——谓词可内联收口）。
- **潜在**：`sales_outbound_commissions` 订单级试算提成（`available_commission` 恒 0、状态恒 TRIAL/WAITING_RULE，月度提成体系独立后无人消费）——列 P4 单独决策。

---

## 3. 目标架构：单一权威模型

### 3.1 一致性不变量（唯一事实，两种投影）

```
┌────────────────────────────────────────────────────────────────┐
│ 唯一事实 = 销售出库单据                                       │
│   权威金额  received_amount（氚云主表最终已收款）              │
│   按日期归属明细 = sales_outbound_payment_snapshots 行集       │
│   约束：received_amount ≡ SUM(CONFIRMED 明细行)                │
└───────────────┬───────────────────────────────────────────────┘
                │ 写入方（导入/人工补录）必须经 Reconcile 守护
                ▼
   展示口径            月度归属口径
   outbounds.*         ps.receipt_date ∈ 期间 的 SUM
   （不再有派生列）      （与主表恒等 ⇒ 两口径永不打架）
```

- 主表数值**永不被下行改写**（权威不变）。
- 明细行由氚云内嵌“收款详细”重建；**当两者冲突**时执行**平衡策略**（用户决策 D1）：

**平衡差额行规则**（写入 `sales_outbound_payment_snapshots`，不修改任何原始行）：
1. `diff = received_amount − SUM(CONFIRMED 明细行)`；
2. `diff = 0` → 无需动作（并把历史遗留对不上的行修正到一致：见迁移策略）；
3. `diff > 0`（主表多收/明细缺行）→ **插入 1 条 `line_kind='AUTO_BALANCE'` 行**，金额 = diff；
4. `diff < 0`（明细合计超出主表）→ **插入 1 条负数 AUTO_BALANCE 行**（冲销），**不删原始行**（保源可追溯）；
5. AUTO_BALANCE 行 `receipt_date`（D2 默认值）：
   `COALESCE(本批文件最新明细行 receipt_date, ship_date, business_date, source_created_at 日期, 导入日)`；
6. 若该出库**完全无明细行且主表 >0** → 同样建 AUTO_BALANCE 行（保证月度提成不再漏单）；
7. AUTO_BALANCE 行打标 `migration_batch='AUTO_BALANCE'` + `remark='主表自动平衡'`，全链路可视可审计；`legacy_status` 随出库 `outbound_status`（CONFIRMED→CONFIRMED）。

**执行点**：
- 导入出库行写完后（本批内统一一次）；
- 任何导致出库状态/金额变化的后续路径（员工回填、重导入）复用同一入口；
- 提供一次性对账 SQL（§9）让运维随时核对恒等式。

### 3.2 读取路径不变，但数字必然一致

月度汇总/正式核算**仍按快照明细的 receipt_date 归属**（这是回款“发生日归属”的唯一可行载体，主表金额没有日期维度）——在恒等式约束下，它与主表权威金额自动一致。台账/订单/客户档案读主表，两者不再可能打架。

### 3.3 派生状态收敛（实时性修复）

所有“结算类派生状态”改为**单一写入函数** `settlementStateOf(outbound)`（纯函数：`sales_amount / received_amount / outstanding_amount / 员工归属` → `settlement_status` 等），在导入事务提交后**批量、幂等**刷新本批 `writtenFactIds`（SQL 批量 UPDATE，而非逐行 refresh），并删除全冗余列。任何页面如需“实时”值 = 直接读列；列由批次管线保证新鲜（含历史一次性回填）。

### 3.4 编排收敛

- 删除不可达的 4 个 `runStep` 分支与 `stepsFor` 多步框架 → 退化为真实需要的**两件异步事**（见 §7-P2）：
  1. 本批出库派生状态批量刷新（含平衡/恒等校验）；
  2. 已生效出库修正变化跟踪（沿用 trackOutboundChanges，改为批量 SQL 驱动）。
- **修复利润打回 PENDING 的伪步骤**：删除 `MARK_OUTBOUND_PROFITS_PENDING`；利润在导入事务内算完即为 CALCULATED，不再被打回；若业务上确需“待核算”态，由前端以“批次未完成/未确认”等独立信号表达，不再污染利润状态。

### 3.5 月度核算单一内核

将回款/运杂费/销售额聚合 + 经销商判定 + 比例追溯提取为**无状态内核**（如 `CommissionKernel`，可先作为 `CommissionPolicy` 内的一组纯函数扩展）：

```
CommissionKernel.compute(window{curStart,curEnd,prevStart,prevEnd}, sources, rateHistory)
  → perEmployee { sales, receipt, freight, collected, rate, commission, … }
```
- **实时参考 summary**：全量现算走内核；
- **正式版**：`loadUnsettledSources` 锁行后，对**未占用来源**同样走内核，再并入修正（修正金额与 base 双算扣除保持现有逻辑）；
- 预览（previewVersion）与正式（calculateVersion）共用同一计算，消除两套公式实现；
- 实时/正式差异只来源于“修正并入 + 已占用来源冻结”，页面通过 settle 面板明确呈现，而非两套口径的隐性漂移。

### 3.6 台账读层单一化

台账读端点（六类）只保留 `H3BusinessQueryService` 一条链；`SettlementLedgerService` 的静态谓词与回款列计算**内联/迁移**进新收敛模块后，遗留对账方法族删除。

---

## 4. 分阶段实施计划

> 原则：**先收敛、再删列；每阶段独立可上线、可回滚**；所有删除前保留备份（沿用 db_cleanup CSV 留档先例）。不引入 Flyway down，破坏性变更一律前向迁移 + 代码开关/兼容期。

### P1 — 一致性守护 + 派生状态单写（对应 G1/G3/G4 字段收敛准备）

**改动**：
1. 快照行沿用 `migration_batch` 标记区分来源：平衡行以 `migration_batch='AUTO_BALANCE'` 打标（与 `LIVE_IMPORT`/`V171`/`V193` 并列），**不新增 schema 列**；平衡行以 `legacy_external_id='AUTO:{id}'`、`legacy_receipt_no='{单号}#BAL'` 可审计，remark 注明补差/冲销方向。
2. 新增 `OutboundBalanceService`：
   - `reconcileAndRefresh(outboundIds)` 实现 §3.1 平衡/校验（仅处理 `CONFIRMED` 出库，与核算读取口径一致）；
   - 对账/核验 SQL 工具见 §9 与 `tools/reconcile_outbound_balance_audit|backfill_20260907.sql`。
3. 导入确认**事务提交后**由编排单步 `REBALANCE_OUTBOUND_SNAPSHOTS` 对本批 writtenFactIds 执行：删旧平衡行 → 补/冲差额行 → 批量刷新唯一派生状态 `settlement_status`（SQL 单写，取代历史 `refreshOutboundSnapshotById` 逐单刷写）。
4. 删除 `MARK_OUTBOUND_PROFITS_PENDING` 步骤与 4 个不可达分支；`stepsFor` 收口为 OUTBOUND→单步、其余类型为空；修复「利润被导入后打回 PENDING、全库无消费者重算」的编排矛盾（利润仅由导入事务内 recalculate 产出 CALCULATED）。
5. 历史一次性平衡：**不自动执行**，由 `tools/reconcile_outbound_balance_backfill_20260907.sql` 在财务复核后人工执行（先审计留档，再回填，再恒等式核验）。

**P1 落地记录（2026-09-07）**：
- ✅ 已落地：`H3FactImportDtos` 编排枚举收敛为单步；`H3ImportOrchestratorService` 重写（删除不可达 4 步与利润打回步骤）；新增 `OutboundBalanceService`；`BatchCandidateStatsService` 过时注释更新；编排单元测试更新为收敛后契约。
- ✅ 已落地：审计/回填 SQL 工具（`tools/reconcile_outbound_balance_audit_20260907.sql` 只读、`tools/reconcile_outbound_balance_backfill_20260907.sql` 写库需人工执行）。
- ⏳ 未执行：历史一次性平衡回填（等财务复核；回填脚本与线上守护逻辑同源）。
- ⏳ 待办（P2/P3/P4）：长事务分片与批量写入、`attribution_date` 索引、核算单一内核、冗余列/表/死代码清理（含 `syncUnchangedOutbound`、`SalesOutboundSettlementService` 实例方法、`SettlementLedgerService` 遗留方法、`receipt_allocations`）。

**影响面**：H3FactImportService / H3ImportOrchestratorService / SalesOutboundSettlementService / SettlementLedgerService / V170 历史数据。
**风险**：月度归属首次变化的单子需财务复核（补偿行日期归属 D2）。**回滚**：功能开关 `app.settlement.balance-enabled=false` 走旧逻辑；AUTO_BALANCE 行可 DELETE 回到旧态（数据可逆）。

### P2 — 导入管线性能与实时性（对应 G2）

**第一包落地记录（2026-09-08）**：
- ✅ 新增 V273：`sales_outbounds.attribution_date` 使用 STORED 生成列自动维护，并建立期间聚合组合索引；
- ✅ `MonthlySalesCommissionService` 与 `CommissionCalculationService` 的期间过滤/排序改用 `attribution_date`；
- ✅ 新增 `H3PostImportAsyncService`，afterCommit 只派发任务，回款平衡编排和变化跟踪在 `h3ImportExecutor` 中执行，终态在收尾完成后写入；
- ⏳ 未完成：确认主事务分片、子表批量写入、批次内客户/员工解析缓存。上述剩余三项完成前，P2 仍为“进行中”。

**改动**：
1. **拆分大事务**：确认流程按 N 行分片提交（每片独立事务 + 进度写实），消除“进度冻结/长事务”。
2. **批量写入**：明细/快照由 DELETE+逐行 INSERT 改为多行 UPSERT（或临时表 + INSERT…SELECT）；客户/员工解析引入**批次内缓存**（先探批内去重 map）。
3. **异步化收尾**：派生刷新 + 修正变化跟踪在 afterCommit 后由独立线程/任务执行（保持与旧策略一致），返回给前端的终态只在真正完成后置位（沿用现有状态机 CONFIRMING→…→COMPLETED，避免编排未跑完先亮已完成）。
4. **索引优化**：`sales_outbounds` 新增并维护 `attribution_date`（=COALESCE(ship_date,business_date)，导入/一次性回填），聚合 SQL 改用该列，命中索引；评估月度聚合预聚表（`monthly_receipt_mv` 增量维护）供参考页。

**影响面**：H3FactImportService、H3ImportBatchController 轮询侧、MonthlySalesCommissionService SQL、V270/V271 迁移。
**风险**：分片提交改变“整批原子性”→ 用批次状态机保证可重试/幂等（现状 CREATE/UPDATE 按 external_id 幂等，天然可重入）。
**回滚**：事务分片大小与异步开关可配置回退。

### P3 — 核算单一内核 + 实时/正式一致（对应 G3）

**改动**：
1. 抽取 `CommissionKernel`（§3.5），`MonthlySalesCommissionService.summary`、`CommissionCalculationService.previewVersion/calculateVersion` 改走内核（先行双跑对比：内核结果 vs 旧实现结果写入日志 diff，灰度确认一致后切换）。
2. “已结清”判定收敛为单函数（`CommissionPolicy`/结算模块），3 处调用改指同一函数；阈值统一。
3. 修正归属（PENDING 修正并入）维持现有语义并补集成测试覆盖“实时=base+修正后=版本”。

**影响面**：CommissionCalculationService（121KB，改动集中在预览/正式共用聚合处）、MonthlySalesCommissionService、SalesOutboundSettlementService/SalesOutboundProfitService 判定引用。
**风险**：核算金额敏感 → 双跑 diff 对比 + 旧版本可作废重算（已有 VOID/RECALCULATION 机制兜底）。
**回滚**：保留旧实现入口开关，diff 异常即切回。

### P4 — 冗余清理收尾（对应 G4）

**改动**（顺序：先代码后 DROP）：
1. 删除 §2.2 六列 + 合并 `payment_status`：先停写（P1 后已无写入方）→ 前端/台账列移除 → 备份导出 → V27x DROP。
2. 下线 `receipt_allocations` 与对账遗留方法族；静态谓词内联进 H3BusinessQueryService。
3. 删除死代码：`syncUnchangedOutbound`、orchestrator 4 死分支、`rejectStoppedFactTypes` 空实现。
4. 对账方法删除前，输出全量差异清单留档（供财务最后一次核对）。
5. （独立决策项 D4）`sales_outbound_commissions` 订单级试算提成的去留：若确认无消费，整链下线；`business_commission_rules` 共享规则表先确认无其它引用。

**影响面**：schema + 台账详情抽屉展示列 + 相关 Service/Controller。
**回滚**：DROP 前置 CSV 备份 + 迁移脚本可重建（列内容已在备份）；死代码删除仅影响维护面。

---

## 5. 遗留/新决策点（评审时拍板）

| 决策 | 选项 | 默认建议 |
|---|---|---|
| D2 | AUTO_BALANCE 行的日期归属规则（§3.1-5） | 上述 COALESCE 链（文件最新明细日 → ship/business → 创建日 → 导入日） |
| D3 | receipts 台账域去留 | **保留台账 + 血缘**（receipts/links 继续随导入存档并在导入中心展示），仅下线 `receipt_allocations` 与对账遗留查询；备选：彻底下线 RECEIPT/SETTLEMENT 导入与 receipts 域（需财务确认不再需要该类单据存档） |
| D4 | `sales_outbound_commissions` 订单级试算提成去留 | P4 内独立决策，默认按“无消费即下线”复核 |

---

## 6. 验收核对清单（每阶段上线前跑）

```sql
-- 9.1 主表=明细恒等式（应为 0 行）
SELECT COUNT(*) FROM sales_outbounds o
WHERE o.outbound_status='CONFIRMED'
  AND ABS(COALESCE(o.received_amount,0)
        - (SELECT COALESCE(SUM(ps.received_amount),0) FROM sales_outbound_payment_snapshots ps
           WHERE ps.sales_outbound_id=o.id AND ps.legacy_status='CONFIRMED')) > 0.01;

-- 9.2 AUTO_BALANCE 行审计（应只含迁移/补偿原因）
SELECT sales_outbound_id, COUNT(*), SUM(received_amount)
FROM sales_outbound_payment_snapshots
WHERE migration_batch='AUTO_BALANCE' GROUP BY sales_outbound_id;

-- 9.3 月度两口径对比（示例：某月回款按主表归属 vs 快照明细归属）
--   → 复核自动化脚本在 P3 双跑对比中实现（实时 vs 内核、实时 vs 版本 diff）

-- 9.4 派生列不再有写入方后，抽查旧列 15 天不再变化（P1 后）：
SELECT MAX(updated_at), COUNT(*) FROM sales_outbounds
WHERE snapshot_received_amount IS NOT NULL OR received_verified IS NOT NULL;
```

---

## 7. 主要风险与缓解

| 风险 | 缓解 |
|---|---|
| 月度归属金额因 AUTO_BALANCE 行改变（历史 29.9% 单子） | 补偿行打标 + 审计报表；P1 仅补不重算旧版本；财务复核后进入 P3 |
| 长事务拆分影响整批原子性 | 状态机 + external_id 幂等写入天然可重入；分片失败批次置 PARTIAL_FAILED 可续传 |
| 正式核算金额敏感 | P3 新旧实现双跑 diff（日志级灰度）→ VOID/RECALCULATION 机制兜底 |
| 列删除影响台账详情展示 | P1 停写、确认前端无引用后，P4 才 DROP；移除前先备份 CSV |

---

## 8. 里程碑顺序（建议）

```
P1（一致性守护） ──> P2（性能/实时性） ──> P3（单一核算内核） ──> P4（冗余清理）
   评审入口         依赖 P1 的单一写入口       依赖 P2 的批量异步        最后执行，风险最小
```
