# 生产跟单 · 口径与实现专题（2026-10-10）

> 本文是 `/.workbuddy/memory/MEMORY.md` 里「生产跟单」一节的展开版（原先占用过多篇幅，10-10 拆出）。
> 改动 `ProductionTrackingService.java` / `ProductionTrackingView.vue` / `routes.ts` 前先读本文。
> 真库：`product_archive`。范围基线（2026-10-10）：**scope 462 单 = 待跟单 69 / 跟单中 41 / 已完成 352；有提醒 56**。

## 1. 范围与可见性
- 范围 = `application_date >= 2026-09-01` ∪ Excel 覆盖 ∪ 已有跟单，常量 `ProductionTrackingPolicy.SCOPE_START_DATE`。
- 节点定义、跟单员定义唯一源 = `ProductionTrackingPolicy`（机器 7 节点；封口机含 OXIDATION，属**按需工序、不计入必经**）。
- 可见性 = MANUFACTURING 功能级 `data_scope`（ALL=主管看全部，否则只看分配给本人的），经 `CurrentUserService.scopeOf`；**读与写同一判据**。

## 2. 取数与 JOIN 结构
- 入库时间真值 = `purchase_receipt_items.production_order_no`（首段 `SCGD…`）→ `purchase_receipts.purchase_date`。
- 只挂 SELECT：`INBOUND_JOIN` / `INBOUND_COLUMNS`；**禁并入 `TRACKING_FROM`**（会改变行使数）。
- ⚠️ 派生表禁放进相关子查询（曾 1.5 亿代价 / 3 分 41 秒不返回）→ 改非相关 `IN` 或键列表传参。

## 3. 状态与判据
- ⭐「未跟单／待跟单」唯一判据 = `NEVER_TRACKED` = `COALESCE(ms.node_count,0) = 0`。
  - **禁用 `version=0` 代替**：进度表导入时整行都没填节点完成日期 → 只落 state 行、`version` 已写 1 ⇒ 真库 17 单被误判「跟单中」。
  - `node_count` 是 `TRACKING_FROM` 的 ms 派生表上的 `COUNT(*)`（同 GROUP BY，不改行使数）。
- 「跟单已推进到入库」= `ms.warehouse_in_date IS NOT NULL`（不是采购入库本身）。该列 = `MAX(CASE WHEN stage_code='WAREHOUSE' AND stage_status='DONE' THEN completed_date END)`。
- `version` 仍照常返回（前端「第 N 次跟进」用），只是不参与状态判定。

## 4. 交付口径（最易错）
- `DEADLINE = COALESCE(expected_completion_date, 有效 required_completion_date)`（有效 = 不早于申请日/任务单提交日）。
- 2026-10-15 更新：`DELIVERED_IN_DATE = COALESCE(跟单员在「入库」节点登记的完成日 ms.warehouse_in_date, 采购首入库日 ib.first_in)`。跟单员登记为权威，未登记才回退采购入库。
- **基准 = `COALESCE(t.forecast_finish_date, p.expected_completion_date)`（车间预计完成日）**，不是 `DEADLINE`（`DEADLINE` 会退回业务员要求日期）。10-10 修此基准前 **43 单被误判逾期**（按期率 56.8% → 68.2%）。
- 延期后新日期只落 `forecast_finish_date`；**氚云原值不回写**。
- 别名必须避开 `order()` 已有的 `t.delivery_result` ⇒ 用 `delivery_outcome` / `deliveredDate` / `deliveryOverdueDays`；三列打包 `DELIVERY_COLUMNS`，与 `INBOUND_COLUMNS` 一起**只挂列表/详情 SELECT**（片段末尾不带逗号）。

## 4.1 工期口径（10-15 重构，LQ 定：延期之后工期要跟着变）

**起因**：延期（`forecast_finish_date`）已在「交付基准 / 倒计时 / 交付结果」三处生效（见 §4 基准 `COALESCE(延期至, 源预计)`），
唯独「要求工期」列还停在**业务员要求完成日** ⇒ 同一行出现「倒计时按延期后算、工期却按原始要求算」的自相矛盾。

**三个真身（10-15 下午 LQ 再定：起算日各不相同；原「承诺工期」并入计划）**；
**10-20 LQ 改起算：计划/实际改从「车间开始接单排产」算**（见下表与 ⚠️）：

> **术语（10-15 LQ 定）**：`sourceCreatedAt` 界面/导出一律叫**「任务单提交时间」**＝业务员把生产任务单提交给车间审批的时间，
> **禁叫「源创建时间」**。排产滞后＝提交日 → 排产时间（`scheduleLagDays`，页面级唯一实现），
> **不是申请日 → 排产**——申请日可能填早（实例 `SCGD20260006013` 实际 10-08 提交、申请日写 07-23，
> 旧口径显示「申请后 78 天排产」，按提交日算实为 **1 天**）。
> **排产时间** `scheduleTime`（氚云 `production_orders.schedule_time`）＝车间**开始接单排产**的时间。

| 真身 | 起算 | 终点 | 性质 |
|---|---|---|---|
| 要求工期 | **申请时间** | `dueDate` | 客户期望，从不改动 |
| 计划工期 | **车间排产时间** | `COALESCE(延期至, 车间源预计)`，**认延期** | 车间真正要兑现的周期；有延期至时徽章标 **`计划·延`**、明细带「含延期」 |
| 实际工期 | **车间排产时间** | `deliveredDate` = 跟单员登记的入库，缺失时回退采购首次入库 | 既成事实，优先级最高 |

- ⚠️ 起算日是**两套**（要求从申请日、计划/实际从**排产日**）——这是有意的：
  ① 申请日常被填早，用它算车间/实际的工期会把录入滞后算成工期；
  ② 业务员提交后车间**不一定马上接单**（真库 6030 单：提交→排产平均滞后 **1.5 天**、最长 **83 天**），
  把「等车间接单」算进车间工期，等于把业务员报得早晚与车间快慢搅在一起（10-20 LQ 原话：
  「并不是说业务员提交之后车间就接单排产了」）。要求工期本来就是申请日提出的期望，不动。
- ⚠️ **未排产 = 只显示要求工期**（真库 44 单无 `schedule_time`，其中 42 单有要求完成日；10-20 LQ 定）。
  车间还没接单排产，就没有「车间多久做完」这件事：**计划工期与实际工期连明细都不出现**
  （不是显示 `—`，也不是「未排产」三个字），主数值降级成**业务员要求工期**、徽章「要求」，
  悬停只给一句「车间尚未接单排产，暂只显示业务员要求工期；排产后自动给出计划/实际工期」。
  即便氚云里已填车间预计完成日、甚至登记过延期，未排产也一样不给计划工期。
  **刻意不回落**到任务单提交日：回落等于把「等车间接单」又算进工期，正是本次要修的病根。
  唯一例外：**已交付但从没登记排产**（真库 1 单）→ 起算日兜底到提交日（否则既成事实被整条藏掉），
  该单 basis 文案写「任务单提交日（排产日缺失）」、悬停写「排产时间未登记，实际工期按任务单提交日起算」。
- 改口径的真库影响（试算，`schedule_time` 非空者）：实际工期均值 **9.0 → 7.5 天**（5111 单，减少量＝
  提交→排产滞后，均值 1.5、最大 30）；计划工期均值 **7.6 → 6.2 天**（578 单）；计划真身负数单
  **2 → 13**（车间预计完成日早于排产日的补录单），仍走「标红 + 原因」，不改数。
- **主数值** `effectiveLeadDays` = 按真身各取各的起算→终点；优先级 已入库 > 车间计划 > 业务员要求。
  未排产的单因为计划/实际整条不成立（`leadDaysHasPlan` / `leadDaysHasActual` 均假），自然落到要求工期。
- **偏差** = 当前真身终点 − 业务员要求完成日（比终点，不比起算）：晚红 / 早绿 / 一致不着色。
  未排产的单终点就是要求完成日，偏差恒 0，界面不出小字。
- **负数工期** = 终点早于起算日（真库 5964 单命中 70：要求 42 / 计划 24 / 实际 4，全是历史补录或
  日期填错，如 `SCGD20260005953` 要求完成日写成 2025-09-30 → −363 天）。**数据现象不是算法错**，
  标红 + 悬停给原因（`negativeLeadReason`），不静默隐藏也不改数。
- **唯一来源** `frontend/src/utils/trackingDateGuard.ts`：列表列 / 详情卡片 / xlsx 导出 / 网页导出四处只准调它，
  契约测试 `trackingDateGuard.test.ts` 同时钉死「页面不得自带一份判据」与四处接线。

**真库实测（全表 5964 单）**：ACTUAL 5664 · PLANNED 260 · REQUIRED 37 · 三者皆无 3；
延期至 98 单（可比 96：92 推后 / 4 提前）；实际入库终点来源：仅采购入库 5209、两源同日 310、
历史核对样本：仅跟单节点 39、采购更早 90、节点更早 16、皆无 300。此样本说明两个来源确实可能冲突；2026-10-15 起按跟单员登记优先的现行口径处理，不再取较早值。

**⚠️ 三个坑**
1. 「延期至」的语义是**跟单确认后的预计完成**，**不一定是推后**：真库 4 单延期至**早于**车间源预计
   ⇒ 措辞由 `forecastAdjustText` 按方向分三种，**禁止写死「已协商延期」**（列表与详情两处同源）。
2. 氚云源字段 `finishDelayDays` = 实际完成 − **车间源预计**，**无视延期**。前端不渲染它；
   将来若要用，须改成「实际 − 交付基准」。
3. 实际工期的终点**必须用后端算好的 `deliveredDate`**（跟单员入库节点优先，采购首次入库兜底），
   前端禁止再写 `LEAST`——MySQL 的 LEAST 遇 NULL 整条返回 NULL，会把「只有一个入库来源」误判成未交付
   （真库 5209 单只有采购入库一个来源，踩中即全错）。详情「车间预计 vs 业务员要求」比较
   （`expectedCompareText`）也必须走 `currentCompletionDate`（认延期）。

**三真身明细展示（10-15 LQ 提：列里只显示一个数「分不清是哪个工期」）**：
工期列在主数值旁挂**当前真身徽章**（要求 / 计划 / 计划·延 / 实际）、
下一行小字列**其余真身**的紧凑天数（当前真身不重复），悬停出**带起止日期的完整明细**
（起算日不同，明细必须写出 `起 → 止` 才不会被当成同一基准比大小）；详情卡片「工期构成」一行列出全部。
数据唯一源 `trackingDateGuard.ts` 的 `leadDaysBreakdown` / `leadDaysKindText` / `leadBreakdownText` /
`otherLeadDaysText`（要求日期不可信时该真身明细给 `—`，与「日期待核实」一致）。
输出契约用真机 e2e `e2e/production-tracking-lead-days.spec.ts` 钉死
（徽章 / 次行小字 / raw-content 悬停明细都属「跑起来才成立」，单测够不着）。

**导出带原因与备注（10-15 LQ 提：导出件里只有延期原因，想线下分析没素材）**：
新增三列（网页 / xlsx 共用同一份 `EXPORT_COLUMNS`）：

| 列 | key | 数据来源 | 备注 |
|---|---|---|---|
| 逾期原因 | `overdueReason` | `time_reviews` 里 `kind='OVERDUE'` 最新一条的 `reason` | 与详情页「交付逾期」录入框同一份数据，不另开字段；**已进核心预设** |
| 其他时间核实原因 | `otherReviewReason` | 其余五类核实（源工单日期/接单等待/车间交期/报工核实/采购入库）合并 | 格式「类目：原因」，` / ` 连接；真库常年 0~1 条，故合并成一列不拆六列 |
| 节点备注 | `stageNotes` | `production_tracking_milestones.note` | 格式「节点名：备注」；**必须与「各节点完成日期」列分开**——节点列是导回可写列，往单元格里塞备注会炸导回解析 |

格式化唯一源 `utils/tracking/rules.ts` 的 `reviewReasonText` / `reviewOtherReasonsText` / `stageNotesText`。
后端配套：`attachStageProgress` 的 SELECT 加 `note`（列表与导出共用一次查询）——**须后端重启后才生效**；
`timeReviews` 本就随列表返回（旧实例也有），逾期原因两列在旧后端上即可用。

⚠️ **导出勾选记忆升过版**（v1 纯数组 → v2 `{version,keys}`）：v1 记忆读到时自动补上本次新增三列并写回 v2，
否则老用户的记忆里永远没有新列（他会以为功能没做）；此后完全按用户自己的勾选，不会反复补。
真库基线（10-15 探针）：OVERDUE 核实 39 单全有原因（跟单员 10-13 起在录）、节点备注 17 条/12 单、
跟单备注 0、延期原因 121 单。e2e `production-tracking-export.spec.ts` 钉死三列输出与 v1 记忆补齐。

## 5. 用词铁律
- **「逾期」** = 实际交付晚于基准的**既成事实**（枚举与文案统一 `OVERDUE`，曾叫 `DELAYED`）。
- **「延期」** = 车间发现按原完成时间做不到、与业务员**协商另订新完成日期**的主动动作（`forecast_finish_date` / `delayReason`）。
- 两者**禁混用**。抽屉里「延期至 / 延期原因 / 登记延期完成日期」保留「延期」二字是对的。

## 6. 跟单待办提醒 `TRACKING_ACTION`
派生单代码，**一单只出一个**（同一单常命中多条）；`CASE` 按业务阶段取第一条，越靠后越严重：

| 代码 | 条件 | 文案 |
|---|---|---|
| `FINISHED_PENDING` | 零节点 且 氚云已报工完工 | 已完工待跟单（红） |
| `INBOUND_PENDING` | 零节点 且 采购已入库 | 已入库待跟单（橙） |
| `SCHEDULED_PENDING` | 零节点 且 已排产（`p.schedule_time`） | 已排产待跟单（蓝） |
| `INBOUND_UNLOGGED` | 已登记节点 且 采购已入库 且 `ms.warehouse_in_date IS NULL` | 已入库待登记（蓝） |
| `STALE_FOLLOWUP` | 已登记节点 且 未完成 且 `last_followup_at < CURDATE()-7d` | 超期未更新（蓝） |

- 依赖 `TRACKING_FROM`(t/ms) + `INBOUND_JOIN`(ib) ⇒ **只能用在同时挂两者的 SELECT**（列表 / 详情 / filter-options 计数）。
- ⚠️ `filter-options` 里前四项会各追加业务条件（如 `production_line IS NOT NULL`），**`actionCounts` 绝不能跟着追加**，否则下拉数字 < 点进去的条数。
- 筛选 `action = all | any | <CODE>`（白名单外 **400**）；`any` → `TRACKING_ACTION IS NOT NULL`；与「时间核实」`reminder` 是**两条独立轴、可叠加**。
- 文案 / 配色 / 原因说明唯一源 = `frontend/src/utils/trackingAction.ts` + 7 spec；`STALE_FOLLOWUP_DAYS = 7` 两端各一份常量、注释互指。
- ⚠️ 单测坑：断言「只允许一个兜底分支」时**不能按 `ELSE` 计数**——内层 `TRACKING_COMPLETE` 自带 `CASE…ELSE…END`，会数出 3；只认 `ELSE NULL END`。

## 7. 导出（10-10 新增，LQ 需求）
- 需求：Excel (.xlsx)，**导出字段可选**。
- 设计铁律：**服务端导出 + 列表同口径**。分页 30/页、总量 462 ⇒ 后端一次拉全量；前端只把 JSON 行组装成 xlsx（**中文文案口径只在前端**，避免前后端各写一份）。
- 后端 `ProductionTrackingService`：抽 **`LIST_COLUMNS` / `LIST_ORDER` / `filter()`** 三件套（`filter()` 返回 `Filtered(where, params)`，含入参校验 + 可见性 + 各筛选轴），**列表与 `exportRows(...)` 共用**。
  - ⚠️ 新 SQL 常量必须声明在**所有片段常量之后**（Java 静态初始化前向引用会拿到 null）。
  - `exportRows(...)`：与 `list()` 同 9 个筛选参数、无分页；`COUNT(*)` 同 FROM/WHERE 报 `total`；取数 `LIST_COLUMNS+TRACKING_FROM+INBOUND_JOIN+where+LIST_ORDER+" LIMIT ?"`（传 `EXPORT_MAX_ROWS+1`）⇒ 超 **`EXPORT_MAX_ROWS = 5000`** 截断并置 `truncated`；返回 `{items,total,exported,truncated,maxRows,visibility}`；仍调 `attachStageProgress`/`attachTimeReviews`。
- 端点：`GET /api/manufacturing/followups/export`，`@RequirePermission({"manufacturing.read.self","manufacturing.read.department","manufacturing.read.all"})`。
- 前端：`api/productionTracking.ts` 的 `fetchTrackingExport(params)`（**返回 JSON 而非文件**）；`utils/export/trackingWorkbook.ts` 纯函数 `trackingSheet(headers, body)`（null/undefined 写空串；列宽按全角 2 计、夹 8~40）+ `buildTrackingWorkbook(sheets[]) -> Blob`（**每个车间一个子表**，sheet 名合法化：非法字符替换 / 31 字符截断 / 重名加 `(2)`）+ `trackingExportFileName(now)` = `生产跟单_YYYYMMDD_HHmm.xlsx`。
- ⭐ **两种输出格式（10-12 新增，LQ：Excel 太朴素不好看）**：弹窗底部两个按钮 —— **「导出网页」**(`.html`) / **「导出 Excel」**(`.xlsx`)。**数据完全同源**：`collectExportGroups(chosen)` 一处做「取数 + 按车间分组 + 展开节点列」，两路只差最后「谁把二维数组写成文件」；入口 `doExport(format)`。
  - 网页版 `utils/export/trackingHtml.ts`（纯函数、零外部依赖）：`buildTrackingHtml({sheets,title,generatedAt,summary,note}) -> string` 渲染**自包含** HTML（样式内联、无任何 `http` 引用）—— 每车间一块带条数徽标、粘性表头、斑马纹、长文本挂 `title`、`@media print` 表头逐页重复 + 行不跨页（`@page` 为 A3 横向）；`esc()` 转义 `< > & "`；空子表保留表头 + 「当前筛选下本车间没有数据」；`buildTrackingHtmlBlob()`；`trackingHtmlFileName(now)` = `生产跟单_YYYYMMDD_HHmm.html`。
  - ⚠️ **Excel 做不到「好看」**：依赖是 SheetJS 社区版 `xlsx@0.18.5`，**不支持写单元格样式**（配色/边框属 pro 能力）⇒ 要么换 `xlsx-js-style` / `exceljs`，要么走网页版。这就是「再来个 html 页面一样的效果」的由来。
  - 语义色：`ExportColumn` 新增可选 `tone(row) -> 'danger'|'warning'|'success'|'info'|''`（Excel 忽略）。现挂 `delivery`（`deliveryOutcomeTone`：逾期 danger / 按期 success / 无基准 info）与 `deliveryOverdueDays`（>0 → danger）。渲染成 `.tag.t-*` 彩色标签，**type 与页面 el-tag 同口径**。
  - 样张：`_probe_out/生产跟单-导出样张.html`（真实数据 481 条 / 4 车间；封口机 23 列、机器车间 25 列）。
- 前端 `ProductionTrackingView.vue`：头部「导出」按钮 → 弹窗（说明「按当前筛选导出全部 N 条、每个车间一块」+ 两种格式说明 / 预设「与列表一致」「只导核心字段」/ 全选全不选 / `el-checkbox-group`）+ 勾选项（**现 19 个**，含多列占位「生产进度」）+ 底部「导出网页 / 导出 Excel」两个按钮；`localStorage 'lq.trackingExportColumns'` 记忆（过滤已不存在的幽灵列）。
- 网页版首页脚：`summary` 只写「口径 + 条数 + 数据范围（全部 / 仅本人）」，**不重抄筛选条件** —— 工具栏标签是唯一来源，文件里再拼一份迟早不一致。
- ⭐ **「生产进度」是多列占位**（替代原「跟单节点 4/4」列）：每个车间子表按**该车间的节点序列**展开成「各节点完成日期」列（封口机：备料/模具生产/氧化/喷特氟龙/组装/入库 5 列；机器车间：画图/备料/钣金/模具/组装/调试/入库 7 列）。列定义（code/label/顺序）取该组第一行的 `stageProgress`（后端 `attachStageProgress` 附加，`ProductionTrackingPolicy` 唯一源）。取值：DONE → 完成日期（`completedDate`）；按需工序本单不走（`optional && !applicable`）→ 「无」；其余留空。
- 子表分组：按 `workshopType`，封口机子表排最前、其余按名称；车间名缺失兜底「未填车间」；筛选结果为空时落一张只有表头的单表（明示「没有数据」而不是「导出坏了」）。
- ⭐ 筛选入参唯一来源 = `trackingFilters()`：**列表与导出共用**（`load()` 也改用它），杜绝两处筛选口径漂移。
- 验收：后端 `ProductionTrackingServiceTest` **40/40**（+3）；前端导出单测 **21 绿**（workbook 10 + html 11），全量 **61 文件 / 420 spec**、`vue-tsc` 0 错（10-12 复跑）；**真实 HTTP 12 场景全 PASS**（脚本 `_probe_out/export_accept.py`）：全部 462 / 待跟单 69 / 跟单中 41 / 已完成 352 / 有提醒 56 / 已完工待跟单 28 / 时间待核实 166 / 逾期待记录原因 120 / 车间=封口机车间 419 / 月份 2026-10 72 / 关键字 54 / 跟单员本人 0，每例「总条数 + 行集合 + 12 个派生列」逐项一致；排序前 30 条任务号逐位相同；越界 `action`/`month`/`trackingStatus` → 400；无身份 → 401。
- ⚠️ 脚本坑（非后端 bug）：URL 里 `&trackingStatus=…` 直接拼在 `/export` 后（少 `?`）⇒ Spring 当成 `/{id}` 路径变量、转换失败 500。用 `qs()` = `"?"+query.lstrip("&")` 归一。

## 7.1 导回：导出件改完再导回（10-12 新增，LQ 需求）
- 需求原话：「导出的表应该可以修改后导入」。**与「导入生产跟单进度表」是两条链路**：进度表导入认氚云模板（固定 sheet 名 / 表头第 2 行 / 行 4 起）；**导回认本系统自己的导出件**（每车间一子表、第 1 行表头、按表头文字定位列），匹配键 = **任务号**（精确等于 `production_orders.work_order_no`）。
- ⭐ **口径三条（LQ 10-12 定，不接受默认之外的猜测）**：① 只支持 `.xlsx`；② **只回写四类列**——跟单备注、车间预计完成（＝延期至）/ 延期原因、各阶段完成日期；③ **空白单元格＝不修改**（漏填不会清空已登记内容）。其余列（产品/规格/车间/数量/各类日期/交付结果…）是源数据或派生值，改了也被忽略。
- 后端 `ProductionTrackingRoundTripService`（新）+ 端点 `POST /api/manufacturing/followups/round-trip/preview|apply`，权限 `manufacturing.followup`。
  - **预检零写入**：逐行给 `status` —— `WRITE`（可回写，带 `changes[]` 旧值→新值）/ `UNCHANGED`（已一致）/ `UNMATCHED`（任务号查不到）/ `FORBIDDEN`（不在可见范围）/ `DUPLICATE`（同任务号多行）/ `INVALID`（日期无法识别、缺任务号）；产品/车间与系统不一致只作 `note` 提示、不阻断。
  - **双重防抖**：`sha256(文件)` 防文件改过；`planHash`（行状态 + 变更内容 + 节点数 + **库内 version** 的指纹）防「预检后库内跟单又被改过」——任一不符都要求重新预检。整批一个事务 + `X-Request-Key` 幂等（`production.tracking.roundtrip.import`），**任一行失败即整批回滚**，不留半批。
  - ⭐ **写入复用唯一路径 `ProductionTrackingService.add()`**（授权 → 版本校验 → 状态 upsert → 节点 upsert → 跟单历史 → 审计），导回不另写 SQL ⇒ 与手工跟进同源、同样产生 `production_tracking_entries` + `erp.row_change_log`；「只能写本人可见任务」也自动继承（`visible()` 唯一源，跟单员只写本人负责车间）。
  - ⚠️ **「车间预计完成」这一列是交付基准**（`COALESCE(forecast_finish_date, expected_completion_date)`），回写要分辨两件事：填的日期 ≠ 车间给的源日期 ⇒ 记为**延期至**；填的日期 ＝ 车间源日期 ⇒ 记为**取消延期**（清空 `forecast_finish_date`）。第二条必须自己翻译，因为 `add()` 明确拒绝「延期至与车间预计完成相同」——不翻译的话用户撤销延期只会拿到一条校验失败。
  - ⚠️⚠️ **COALESCE 陷阱**：`add()` 里 `remark` / `delay_reason` / `delivery_result` / `shipment_status` 都走 `clean()`（null → 空串），而**空串不是 NULL**，`COALESCE` 会选中空串 ⇒ 没提交的字段被**清空**。故导回：① 某行只改节点时传 `state=null`（`add()` 走「只推节点」分支，完全不碰状态列）；② 只改部分字段时，其余字段**回填库内原值**。（副作用：发生过状态写入的行，`delivery_result`/`shipment_status` 的 NULL 会变成空串——与手工跟进一致，两列只用于详情展示，不参与任何判据。）
  - ⚠️ **`jdbc.query(sql, rowHandler, args)` 的 lambda 必须写成块体且无 `return`**：写成表达式（`r->map.put(...)`）会与 `ResultSetExtractor` 重载歧义、编译不过；写成 `r->{...; return null;}` 会被解析成 `ResultSetExtractor`（整个 ResultSet 只回调一次且不自动 `next()`）⇒ 运行期 `Illegal operation on empty result set`。**编译与单测都全绿**，只有真实 HTTP 打一次才暴露（本次就是这样抓到的）。
- 前端：头部「导入跟单表（回写）」按钮（`can('manufacturing.followup')` 可见）→ 选文件即预检 → 弹窗展示「可回写/已一致/未匹配/不在可见范围/重复/无法识别」计数 + 逐行 `字段：旧值 → 新值` 小标签 → 「确认回写 N 行」。
  - 导出侧配套：`EXPORT_COLUMNS` 新增「延期原因」列；xlsx 追加 **「导出说明」子表**（`trackingExportGuideSheet()`，写在文件里说明可回写列与规则；导回解析按「有没有任务号列」认数据子表 ⇒ 自动忽略）；`localStorage 'lq.trackingExportColumns'` 恢复时**补齐后加的列**（否则老存档用户永远导不出延期原因、也就回写不了）。
- 验收（10-12）：后端新单测 **14/14**（解析自家导出件 / Excel 日期序列 / 「无」＝不适用 / 列名含节点名不误判 / 差异判定 6 例），全量后端 test 回归通过；前端 61 文件 **427 spec** + `vue-tsc` 0 错。
  - **真实 HTTP 场景全 PASS**（jar 起在 **8092**，8081 留给并行会话；脚本 `_probe_out/rt_accept*.sh`，验收件由**真实导出工具**生成）：预检 2 可回写 + 1 未匹配；apply `written=2` 且库内 `state.forecast_finish_date/delay_reason/remark`、`milestones`、`entries`（内容含逐项变更）、`enrollments`、`erp.row_change_log` 逐项核对一致；同一文件**连导三次**：第 1 轮 `written=1`（恢复延期）、第 2/3 轮 `written=0` ⇒ **不产生重复记录**；B 文件把日期填回车间源日期 ⇒ `延期至→（清空）` 且 remark/延期原因**原样保留**；跟单员 16 导封口机任务 ⇒ 2 行 `FORBIDDEN`；无权限身份 ⇒ 403；文件哈希不符 ⇒ 400「文件已变化，请重新预检再导入」；库内变过再导 ⇒ 400「工单匹配或库内跟单已变化，请重新预检再导入」。
  - 验收用的两单（17136 / 17142，事前确认无任何跟单记录）**已按 4 张表逐行清理回零**（备份 `_probe_out/rt_cleanup_backup_20261012.txt`）；`erp.row_change_log` 与 `erp.idem_request` 里留下本次验收的记录（append-only，未删）。

## 8. 时间核实（含「逾期原因」）
- 逾期原因记录**走既有**「时间核实」`production_tracking_time_reviews`、kind = `OVERDUE`（**不开新字段**）；入口在详情「生产交付结果」卡（仅逾期时出）。
- ⚠️ `OVERDUE` 的**快照列语义专属**：`source_expected_date` = 交付基准、`source_inbound_date` = 实际交付日（与 `REPORTING` 复用同两列时含义不同）；「已记录即不再提醒」按这两列比对。
- 列表筛选 `reminder = time_overdue`；因谓词引用交付日，**list 的 COUNT 已补挂 `INBOUND_JOIN`**（1:1 派生表，真库 462 = 462 不重复计数）。
- 六类时间口径条件（`TIME_SOURCE_APPLICATION`/`TIME_SOURCE_REQUIRED`/`TIME_ACCEPTANCE`/`TIME_COMMITMENT`/`TIME_REPORTING`/`TIME_INBOUND_REPORTING`）已成 SQL 常量，**只用于逐单筛选与打标，无聚合汇总**。

## 9. 列表展示细节
- 两列合并：「业务员要求完成」与「车间预计完成」**展示值一致时横向合并**（`span-method` 左列 `[1,2]` / 右列 `[0,0]`；EP 2.14.2 固定列是 sticky 单表实现 ⇒ 合并对所有列统一生效）。
  - 口径唯一源 `frontend/src/utils/trackingDateColumns.ts` + 6 spec。**一致 = 业务员要求日期有效 且 展示值相同**。
  - ⚠️ **合并组以列 label 定位 ⇒ 两列 label 一旦改名，合并会静默失效**（不报错、只是不合并）。日期列**无任何附加小字**。
- 任务号列只显示末四位（`shortOrderNo`，`/\d+$/` 取尾段再 `slice(-4)`；`title` 悬浮看全号）。真库 5,900 条 `work_order_no` 固定 15 位、末四位 0 重复。
- 车间显示省尾缀「车间」(`shortWorkshop`)，4 处展示同用；筛选 option 的 `:value` 仍用原名。
- ✅ 10-10 按 LQ 已删：头部 5 张统计卡、列表「任务单状态 / 下次跟进 / 提醒 / 申请月份」列、toolbar「任务单状态」下拉（api 层保留契约字段）。

## 10. 节点参考完成时间
- 基准表 `production_tracking_node_benchmarks`（**V382**，`workshop_kind`+`stage_code` 唯一）＝各节点「一般耗时」**中位数**唯一源。
  - SEALER：MATERIAL 0（n=392）/ MOLD 3（n=381）/ ASSEMBLY 2（n=352）/ WAREHOUSE 0（n=352）。
  - **机器车间无跟单数据 ⇒ 不播种 ⇒ 参考为空、不强算**（等有数据再补）。
- 推算 `computeNodeReferences`（纯静态、可测、派生值**不落库**）：锚点 = 计划开工（回退申请日）；上游 DONE 用**实际完成日**接力、未完成用参考值接力；氧化（按需）不适用、不占链；无基准/无锚点 ⇒ 参考为空。`detail()` 附加 `typicalDays`/`referenceDate`/`referenceOverdueDays`。
- **超参考未完成且备注空 → 保存 400**（`validateNodeReasons`，**逐节点语义**：每个超参考未完成节点都要备注）；前端同规则预校验 + 标红 + 备注占位符提示。
- ⭐ **基准可由管理员配置**（**V383**）：独立权限 `manufacturing.baseline.manage`（**只绑 ADMIN**，四表联动）+ `GET|PUT /api/manufacturing/followups/node-benchmarks`。
  - GET 返回按 SEALER/MACHINE 的**完整矩阵含未配置节点**；PUT 批量 upsert、**0~60 天**、`null` = 删该行、**逐行审计**。
- ⚠️ 参考天数是**派生值的输入** ⇒ **改完基准，全部在制单的参考完成日期当场重算、不留旧判定**，故必进审计。
- ⚠️ 验证后要把 PUT 改动的行按 V382 原值还原（PUT 会一并改掉 `sample_count`/`baseline_note`/`computed_at`）。

## 11. 运维与争抢点
- ⚠️ `ProductionTrackingView.vue` 与 `apps/archive/routes.ts` 是**多会话争抢点**：改完必复验，**勿整文件重写**。
- 跟单 6 张表 10-10 曾整体清空（备份 `db_backup_20261010_followup/`，恢复演练通过）；源 `production_orders`（5,900）/ `production_order_components`（14,702）未动。
- ⚠️ 跟单**只有执行层，无分析层**（无任何聚合看板；`summary()` 端点保留但前端不用）。
- ⚠️ 归因字段断源情况：`delay_reason`（进度表「延期原因」列，覆盖率仅 ~19%、39~41 种写法未归一）**有源**；`risk_category`（前端无编辑入口、Excel 无此列）**恒空、真·断源**。
- ⚠️ 进度表导入未落 2 列：「生产倒计时」「订单变更」；列定位一律按**表头文字**，缺列直接抛错；行上限 2000。
- ⚠️ 进度表 H 列「预计生产完成日期」= 跟单员**另订的延期日** → 落 `state.forecast_finish_date`；**不是** `production_orders.expected_completion_date`（车间填的），两者勿混。导入侧已收口：等于源值即视同无延期、不落库（实测非空恰好 68 行）。
