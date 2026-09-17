# 月度提成专用结算模块 · 设计落地方案（评审稿）

> 版本：2026-09-04 评审稿
> 状态：待评审（未改动任何代码）
> 范围：把「以氚云销售出库为唯一数据源、支持任意日期范围、按收款/运费/调整明细防重」的专用提成结算设计稿，映射到 LQXM 现有 `commission_calculation_*` 体系，并给出逐项 DDL / 代码改动点 / 排期。

---

## 1. 结论先行

1. **设计稿的核心目标在现有代码中已实现约七成**：氚云唯一数据源、任意日期范围、按收款/运费明细占用防重（V258）、补充核算、运费独立扣减、比例可追溯、作废释放，均已落地。
2. **剩余差距集中在 9 个点（G1~G9）**，其中 3 个属数据模型扩展、3 个属口径/计算修正、3 个属前端体验与导出。
3. **建议保留现有 `commission_calculation_versions/items/source_lines` 表名做增量扩展**，不做 `commission_settlement_batches` 重命名（语义等价，改名成本高、无业务收益）。
4. **G7（比例取数口径）经代码核实存在一处与设计不符**：期间正常提成的来源明细用的是「核算时点当前比例」，而非「明细发生日当时比例」，需修正（详见 §4.7）。
5. 本文给出按 4 个实施包（P1~P4）的推进顺序，每个包含 DDL、后端、前端改动点与验收标准，并附 6 个待财务/评审拍板的口径问题（§7）。

---

## 2. 现状快照（代码已核实）

### 2.1 数据源与口径

- 回款明细 = `sales_outbound_payment_snapshots`（氚云销售出库内嵌收款子表），条件 `legacy_status='CONFIRMED'`，按 `receipt_date` 归属。
- 运费 = `sales_outbounds.freight`（单张出库单一个运费值），按 `COALESCE(ship_date,business_date)` 归属。
- 所有 SQL 均硬编码 `external_source IN ('H3YUN','H3YUN_RECOVERED','H3_HISTORICAL_RECOVERED')`：
  - `MonthlySalesCommissionService.java:53-54`
  - `CommissionCalculationService.java`（`loadUnsettledSources`、`settlementPreview`、`summary` 等全部）
- 经销商判定（`CommissionPolicy.DEALER_CASE`，CommissionPolicy.java:39-42）：`dealer_name` 非空 **或** `source_gross_profit/sales_amount < 15%` 兜底。

### 2.2 核算与防重

- 主流程：`GET /summary`（实时口径参考）→ `GET /versions/preview`（本次待核算预览）→ `POST /versions`（核算结账）→ `GET /versions/{id}`（下钻）→ `POST /versions/{id}/void`（作废）。
- 防重核心（V258 `commission_calculation_source_lines`）：每条收款明细占 `active_source_key='RECEIPT:{snapshotId}'`、每张含运费的出库单占 `'FREIGHT:{outboundId}'`，唯一键 `uk_ccsl_active_source` + 版本生成时 `FOR UPDATE` 行锁，**占用与生成版本在同一事务**。
- 补充核算天然成立：`loadUnsettledSources`（CommissionCalculationService.java:659）只取「未被任何 ACTIVE 占用」的来源，范围重叠只新增。
- 作废（:803）：FINAL→VOID，释放 `source_lines` 为 RELEASED，`commission_outbound_changes` 回滚 PENDING。

### 2.3 比例

- 当前配置：`monthly_commission_rates`（员工×经销/零售两列，NULL=默认规则 4%，另李水云 1% 等）。
- 历史追溯：`monthly_commission_rate_history`（V244，每次调整留痕 + `effective_date`），`CommissionPolicy.rateAt` 按归属日期取「当时比例」。

### 2.4 修正/差额调整

- 导入时（`trackOutboundChanges`，:140）对比 `commission_outbound_baselines` 与当前值，单据级写入 `commission_outbound_changes`（PENDING），含业务员变更追回+补给（V225）；结账时按归属期间过滤并入版本并置 PROCESSED（:555-563）；作废回滚。
- 变化提成按「归属日期的当时比例」计算（:356 `rateAt`），修正公式已统一为 `(Δ回款−Δ运杂费)×比例`（V244）。

### 2.5 现有 REST 端点（MonthlySalesCommissionController.java）

| 端点 | 用途 |
|---|---|
| `GET /api/monthly-commissions/summary` | 实时核算汇总（任意日期范围） |
| `GET /api/monthly-commissions/{employeeId}` | 单业务员核算明细 |
| `GET /api/monthly-commissions/versions` | 核算版本列表 |
| `GET /api/monthly-commissions/versions/preview` | 结账前预览（总量/已核算/待核算） |
| `GET /api/monthly-commissions/versions/{id}` | 版本明细（业务员行+来源行） |
| `GET /api/monthly-commissions/versions/compare` | 同期间两版本逐业务员对比 |
| `POST /api/monthly-commissions/versions` | 核算结账（生成版本） |
| `POST /api/monthly-commissions/versions/{id}/void` | 作废版本 |
| `GET /api/monthly-commissions/changes`、`/changes/count` | 修正变化列表/计数 |
| `GET|POST|PUT|DELETE /api/monthly-commissions/rates[/{id}]` | 比例配置 |

权限：读 `monthly_commission.read.self/.department/.all`（类级），核算/比例写 `monthly_commission.manage`。

### 2.6 前端

- 页面：`frontend/src/views/settlement/commission/MonthlyCommissionView.vue`（≈74KB）。
- API：`frontend/src/api/monthlyCommission/index.ts`（已含 summary/detail/rates/versions/preview/compare/void/changes 全套类型与函数）。

---

## 3. 差距清单总览

| # | 差距 | 现状证据 | 影响 | 处置 |
|---|---|---|---|---|
| G1 | 调整/差异明细无独立「已核算占用」语义 | `source_lines` 仅 RECEIPT/FREIGHT；`changes` 用 PENDING/PROCESSED 标记，不经唯一键占用 | 无法回答「该调整是否已被某 FINAL 批次占用」；并发下同批调整可能被两次并入 | P1 |
| G2 | 无结算类型 NORMAL/SUPPLEMENT/RECALCULATION | `commission_calculation_versions` 无类型列 | 审计/列表无法区分正常/补充/重算 | P1 |
| G3 | 状态机与作废规则不完整 | 仅 FINAL/VOID；作废无必填原因、无「已发放不可作废」 | 违反设计稿作废规则与审计要求 | P1 |
| G4 | 运费范围只有 PREVIOUS_CALENDAR_MONTH 一种且三处硬编码 | `calculateVersion`/`settlementPreview`/`summary` 各自 `end.minusMonths(1)` 整月 | 不支持等长周期/自定义范围；前端无法选择 | P2 |
| G5 | 渠道只存 DEALER/RETAIL，不存判定来源，兜底无警告 | `source_lines.channel_type` 仅有二值 | 无法区分「填了经销商」与「毛利兜底」，兜底单无提醒 | P2 |
| G6 | 结账前无阻断/警告检查清单 | `preview` 仅返回数量金额；`summary.issues` 仅 3 类 | 阻断为零才能核算的门禁缺失 | P3 |
| G7 | 正常提成来源行比例按「核算时点」而非「发生日当时」取 | `summarizeSources`/`claimSources` 用 `loadRates`（核算时点当前值），仅修正变化用 `rateAt` | 跨比例有效期核算时口径偏差；已核算后再调比例不回算正确 | P2 |
| G8 | 前端查询区/确认界面/详情抽屉/导出未达设计稿 | 有日期范围+版本列表+明细抽屉，缺快捷范围、运费范围控件、确认弹窗、三项导出 | 使用体验与可复核性不足 | P4 |
| G9 | 精度未统一 | `monthly_commission_rates`/history 为 DECIMAL(6,4)，设计稿要求 6 位 | 未来低比例（如 0.01%）会截断 | P2 |

---

## 4. 逐项落地方案

### 4.1 P1-①  G1：调整明细进入统一占用模型

**目标语义**：每条 PENDING 差异（`commission_outbound_changes`）也只允许被一个 FINAL 版本消费，作废后恢复待处理。

**方案 A（推荐，改动小且复用现有防重机制）**：
1. `commission_calculation_source_lines.line_type` 允许 `ADJUSTMENT`（现为 VARCHAR(20)，无需改列型）。
2. 在 `calculateVersion` 中，对每笔并入的 PENDING 变化追加写一行 `source_lines`：
   - `line_type='ADJUSTMENT'`、`source_id=change.id`、`outbound_id` 复用、`amount=净额（Δ回款−Δ运费，经销/零售分行或拆两行）`、`applied_rate=change 行内已存比例`、`commission_contribution=change_commission`、`active_source_key='ADJUSTMENT:{changeId}'`。
   - 若一笔变化同时含经销+零售Δ，则拆两行（复用 V258 已有的正负贡献模型）。
3. 占用写入与「change 置 PROCESSED」同事务；唯一键天然拦截同笔调整被两个批次同时并入。
4. 作废时现有 `voidVersion` 已把 `changes` 回滚 PENDING 并释放 `source_lines`，只需把 ADJUSTMENT 行一并 RELEASED（现有 UPDATE 已按 version_id 全量释放，自动覆盖）。

**方案 B（贴稿子建 `commission_settlement_adjustments`）**：需迁移存量 `changes`→adjustments、双写占用，成本高。**仅当评审要求「人工录入调整 + 独立状态机 PENDING/PROCESSING/PROCESSED/CANCELLED」时再采用**，此时把方案 A 的 ADJUSTMENT 占用行仍作为只读投影，主数据在 changes/adjustments 表。

**验收**：同一调整不能出现在两个 FINAL 版本；作废后原调整可再次被并入；`versionDetail` 的 sourceLines 出现 ADJUSTMENT 行。

---

### 4.2 P1-②  G2：核算版本带结算类型

**DDL（V259）**：
```sql
ALTER TABLE commission_calculation_versions
    ADD COLUMN settlement_type VARCHAR(20) NOT NULL DEFAULT 'NORMAL'
        COMMENT 'NORMAL=正常核算 / SUPPLEMENT=补充核算 / RECALCULATION=作废后重新核算' AFTER version_no,
    ADD COLUMN void_reason VARCHAR(500) NULL COMMENT '作废原因（必填）' AFTER created_by_name,
    ADD COLUMN void_by BIGINT NULL COMMENT '作废人 employees.id' AFTER void_reason,
    ADD COLUMN void_at DATETIME NULL COMMENT '作废时间' AFTER void_by,
    ADD COLUMN paid_flag VARCHAR(20) NOT NULL DEFAULT 'UNPAID'
        COMMENT 'UNPAID=未发放 / PAID=已发放（已发放不可作废）' AFTER status,
    ADD COLUMN paid_by BIGINT NULL COMMENT '发放确认人' AFTER paid_flag,
    ADD COLUMN paid_at DATETIME NULL COMMENT '发放确认时间' AFTER paid_by;
-- 兼容旧 FINAL 版本：status='FINAL' 即视为已正常结算
UPDATE commission_calculation_versions SET settlement_type='NORMAL' WHERE settlement_type='NORMAL';
```

**类型推导规则（`calculateVersion` 内实现）**：
- 同 `[start,end]` 无 FINAL 版本且本次来源非空 → `NORMAL`。
- 同 `[start,end]` 已有 FINAL 版本、本次存在新增待核算来源/调整 → `SUPPLEMENT`。
- `RECALCULATION`：作废后立刻重算时由前端带参 `intent=RECALCULATION`（用于审计标识），服务端校验该范围最近存在 VOID 版本方可标注。

**后端**：`insertVersion` 增加 `settlement_type` 参数；`listVersions`/`versionDetail` 返回该列。
**前端**：列表与详情展示类型标签（正常/补充/重算）。

**验收**：8 月首算=NORMAL，8 月补录后同范围再算=SUPPLEMENT，作废重算=RECALCULATION；VOID 行保留类型与原因。

---

### 4.3 P1-③  G3：作废规则与发放状态

- **作废必填原因**：`POST /versions/{id}/void` body 增加 `{ reason: string }`（必填，长度校验），写入 `void_reason/void_by/void_at`；`voidVersion` 与审计日志携带原因。
- **已发放不可作废**：`voidVersion` 增加前置校验 `paid_flag='PAID'` 直接拒绝（`IllegalArgumentException`）。
- **发放确认**：新增 `POST /versions/{id}/mark-paid`（`@RequirePermission("monthly_commission.manage")`），把 `paid_flag='PAID'`+操作人/时间；列表 `voidable` 标记改为 `status='FINAL' AND paid_flag='UNPAID'`。
- **DRAFT/CHECKED 是否落库（评审二选一）**：
  - 方案 1（推荐，轻量）：不做持久化草稿。以「preview+检查清单（P3）+ 前端确认弹窗」实现「先生成预览、跑检查、再确认提交」，提交时服务端重跑阻断检查作为门禁。等价满足设计稿流程图。
  - 方案 2（贴稿子状态机）：`versions.status` 增加 DRAFT/CHECKED 取值 + `checks_result JSON` 列 + 两步接口（先生成草稿，再确认 FINAL）。改动更大，适合「财务希望保留未最终确认的中间态便于换人接手」。

**验收**：未填原因作废被拒；已发放批次作废被拒；作废审计含原因；发放记录可查。

---

### 4.4 P2-①  G4：运费范围三模式

**现状**：`calculateVersion`(:409-411)、`settlementPreview`(:597-599)、`summary`(MonthlySalesCommissionService.java:88-91) 三处各自用 `end.minusMonths(1)` 整月推导。

**目标**：`freight_period_mode ∈ {PREVIOUS_CALENDAR_MONTH, PREVIOUS_EQUAL_PERIOD, CUSTOM}`。

**设计**：
- 新增共享工具（放 `CommissionPolicy` 或新建 `CommissionFreightRange`）：
  ```java
  record FreightRange(LocalDate startInclusive, LocalDate endExclusive) {}
  FreightRange resolve(start, end, mode, customStart, customEnd)
  // PREVIOUS_CALENDAR_MONTH: [end 所在月的上月 1 日, 下月 1 日)（= 现行口径）
  // PREVIOUS_EQUAL_PERIOD:   [start.minusDays(len), start)，len=end-start+1 天
  // CUSTOM:                  [customStart, customEnd+1)，custom 必填且须填原因
  ```
- 后端入参统一：`summary / {employeeId} / preview / POST versions` 增加可选参数 `freightPeriodMode`、`freightStartDate`、`freightEndDate`、`freightReason`；**未传时默认 `PREVIOUS_CALENDAR_MONTH`（与现行一致，公司现行公式不受影响）**。
- 版本落库：`versions` 增列 `freight_start_date / freight_end_date / freight_period_mode`（随 G4 一并写入 V259 或独立 V260）。preview/版本详情返回运费窗口与模式，供确认界面展示。
- 自定义模式必须 `freightReason` 非空，否则拒绝。

**校验**：`CUSTOM` 时 custom 范围必填；`freightStart<=freightEnd`；与回款范围重叠不禁止但给出警告（设计稿：运费范围独立）。

**前端**：查询区在日期旁加「运费范围模式」下拉 + 跟随日期输入 + 自定义时原因输入框；「核算范围说明」卡片展示 `回款范围 / 运费范围 / 运费规则 / 本次只计算尚未进入有效批次的明细`。

**验收**：默认选中「上一个完整自然月」，选择后 summary/preview 的窗口随之变化；自定义必须填原因；版本上能看到当时实际使用的运费窗口快照。

---

### 4.5 P2-②  G5：渠道判定来源落列 + 兜底警告

**DDL（V259/V260）**：
```sql
ALTER TABLE commission_calculation_source_lines
    ADD COLUMN channel_source VARCHAR(30) NOT NULL DEFAULT 'DEFAULT_RETAIL'
        COMMENT 'DEALER_FIELD=填了经销商 / MARGIN_FALLBACK=毛利兜底 / DEFAULT_RETAIL=默认终端 / MANUAL_CORRECTION=人工纠正'
        AFTER channel_type;
```

**判定表达式改造**（现 `DEALER_CASE` 是二值布尔，改三档 CASE）：
- `dealer_name` 非空 → `DEALER` / `DEALER_FIELD`
- 否则毛利 <15% → `DEALER` / `MARGIN_FALLBACK`
- 否则 → `RETAIL` / `DEFAULT_RETAIL`

实现：在 `CommissionPolicy` 新增 `DEALER_SOURCE_CASE`（返回 'DEALER_FIELD'/'MARGIN_FALLBACK'/NULL 的 CASE 片段），与现有 `DEALER_CASE` 并存（`DEALER_CASE = channel_source IS NOT NULL` 或直接 `(dealer_name非空 OR 毛利兜底)` 不变）；`loadUnsettledSources`/V258 回填 SQL 把二值 dealer_flag 换为同时输出 channel_source 并落列。

**兜底警告**：MARGIN_FALLBACK 行为预览/确认界面的警告项（并入 P3 检查清单；设计稿要求「警告但不阻止核算」）。

**人工纠正（MANUAL_CORRECTION，后置可选）**：渠道纠正会改变来源行的 dealer/retail 归类与贡献，且与已核算版本快照冲突需走「作废→纠正→重算」或「差额调整」。**本期不做，列入 §7 待定；若财务要求，单独立项**（在 source_lines 上做人工改判需同时记录 `corrected_by/at` 与审计）。

**验收**：来源行能回答「这单为什么算经销商」；兜底单在预览中出现警告且不阻断。

---

### 4.6 P3：G6 结账前检查引擎

**新增服务方法** `CommissionCalculationService.checks(preview..., extra)`：返回 `{ blocking: [{code,message,count,amount}], warnings: [{code,message,count,amount}] }`。`settlementPreview` 响应扩展 `blocking/warnings` 字段。

**阻断问题（设计稿 §8，逐条实现）**：
1. 缺少业务员（回款/运费/调整来源 `employee_id` 为空）
2. 收款有金额但缺收款日期
3. 运费缺归属日期（出库单无 ship/business_date）
4. 提成比例缺失（员工与默认均无可匹配比例→异常兜底当前默认 4%，检查项应报「命中兜底默认比例」）
5. 来源明细 ID 重复（同批 RECEIPT/FREIGHT/ADJUSTMENT 内部 sourceId 重复）
6. 明细已被其他 FINAL 版本占用（并发下唯一键冲突前置探测）
7. 同一调整正被其他核算处理（G1 后指无占用但 PROCESSED 冲突）
8. 导入批次未确认完成（涉及来源所属 `h3_import_batches` 状态）
9. 日期范围无效（start>end / 运费窗口非法）
10. 金额无法解析（snapshot 金额 NULL/非数字等）

**警告问题**：渠道由毛利率推断（G5）、收款累计超销售出库金额、运费为负、本次提成基数为负、业务员近期发生过变更、收款日期早于出库日期、同单收款跨多个期间、提成较最近批次波动>阈值（取该范围最近的 FINAL 版本总额对比，阈值先定 30% 可配）、自定义运费范围。

**门禁**：`POST /versions` 开头重跑全部阻断检查（与 preview 同函数），阻断数>0 即抛 `IllegalArgumentException`（带第一条阻断文案）；`summary.issues` 保留同时并入 warning 出口。

**前端**：确认弹窗（P4）中「警告：n 项」可展开明细；阻断时后端拒绝并以红色列表展示。

**验收**：人为构造「明细已被占用」/「缺比例」场景时无法结账；警告全部可展示但放行。

---

### 4.7 P2-③  G7：比例按明细发生日追溯（已核实，需修正）

**核实结论（读码确认）**：

| 路径 | 取数方式 | 代码位置 |
|---|---|---|
| 期间正常提成（base 来源行） | `CommissionPolicy.loadRates` = **核算时点当前配置**，逐行 `rateOf` | `summarizeSources` :712/:731；`claimSources` 调用方 :514 传入、:747 `rateOf` |
| 修正变化（changes 行） | `rateAt(history, empId, 归属日期)` = **发生日当时比例**（并存行内） | :356；V244 |

**结论：base 来源行用的是「核算时点比例」，不是设计稿要求的「按明细发生日（receipt_date/ship_date）匹配当时比例」。** 当前因比例历史从未真正变动过（V244 基线全部 effective 2026-01-01，早于全部单归属日期），两种取数结果一致；**一旦未来某员工调整比例，跨有效期的一次核算就会算错**。且设计稿 §7 明确：同一业务员范围内跨不同有效期须分明细计算。

**修正方案**：
1. `calculateVersion`/`settlementPreview`/`summary` 中，凡为来源行取比例处，由 `loadRates` 改为逐行 `rateAt(loadRateHistory(jdbc), employeeId, source.occurrenceDate())`；`claimSources` 落库 `applied_rate` 即该行追溯值，`commission_contribution` 即按追溯值计算。
2. `commission_calculation_items` 的员工级 `dealer_rate/retail_rate` 单值语义调整：保留为「本次结算综合快照」；当该员工本批次来源行比例不一致时，返回 `rateSegments`（`[{effectiveFrom,effectiveTo,dealerRate,retailRate,sourceLineCount}]`）并在列表标「多段比例」，避免误导。source_lines 已有逐行 `applied_rate`，金额真实性不受影响。
3. V244 重算历史仅覆盖 change_commission；本次如实施，需把历史上「已 FINAL 版本中 base 行」按追溯比例重算的决策提交评审（**不建议回溯**——已结账结果不可直接改，遵循设计稿 §3「已结算结果不可直接改变」；如需纠错走作废重算）。
4. 比例维护端点（create/update/delete）继续写 `monthly_commission_rate_history`；补充 V259 为 `monthly_commission_rates` 与 history 增加 `effective_from/effective_to` 字段并校验同员工区间不重叠（可选加强，替代仅靠一条 effective_date 的表达）。

**验收**：构造「员工 A 于 2026-08-15 调整比例」用例，跨 7-16~8-31 范围核算：8-15 前收款按旧比例、其后按新比例，`source_lines.applied_rate` 与预览计算一致；员工汇总行提示多段比例。

---

### 4.8 P2-④  G9：金额/比例精度统一

| 位置 | 现值 | 目标 | DDL/代码 |
|---|---|---|---|
| `monthly_commission_rates` dealer/retail | DECIMAL(6,4) | DECIMAL(8,6) | V259 `ALTER ... MODIFY` + `0.04`→`0.040000` |
| `monthly_commission_rate_history` 同上 | DECIMAL(6,4) | DECIMAL(8,6) | V259 |
| `CommissionPolicy.DEFAULT_RATE` | `0.0400` | `0.040000` | 代码常量 |
| 业务金额/回款/运费/汇总 | DECIMAL(14,2) | 保持 2 位 | 不变 |
| 中间计算/贡献 | DECIMAL(16,6) | 保持 6 位 | 不变（`source_lines.commission_contribution` 已 16,6） |
| 业务员最终提成 | 现 `money()` round2 | 四舍五入至 2 位 | `items.total_commission` DECIMAL(14,2) 已满足 |

**验收**：录入 0.001%（万分之零点一）不溢出；所有展示金额保留 2 位、贡献溯源 6 位。

---

### 4.9 P4：前端体验与导出（G8）

#### 4.9.1 查询区
- 日期快捷：本月 / 上月 / 本季度 / 自定义（月份与季度按自然期算，季度=当季首日~末日）。
- 新增：运费范围模式 + 起止日期 + 自定义原因；业务员/部门/渠道筛选（渠道筛选作用在待核算来源行上）。
- 参数向后端透传 `freightPeriodMode/freightStartDate/freightEndDate/freightReason`（默认不传=现行口径）。

#### 4.9.2 核算范围说明与核心指标
- 卡片：「回款范围 / 运费范围 / 运费规则 / 本次只计算尚未进入有效核算批次的明细」。
- 指标行：范围内销售额 → 范围内回款总额 → 已核算回款 → **本次待核算回款** → 范围内运费总额 → 已核算运费 → 本次待扣运费 → 待处理调整 → 预计应结提成（待处理调整=PENDING 变化合并，预计=预览 base+adj）。

#### 4.9.3 业务员汇总表
列：业务员 | 待核算回款 | 待扣运费 | 经销商基数 | 终端基数 | 基础提成 | 调整 | 应结提成（与设计稿 §9-4 对齐；金额口径与预览一致）。

#### 4.9.4 确认界面
- 「核算」按钮 → 弹窗 = 确认界面：展示回款/运费范围、范围内总额/已核算/本次待核算、待处理调整、预计提成、涉及业务员数、警告 n 项（可展开明细，阻断项红显且不可提交）；**最终确认的是本次待核算来源（行数）**。提交 POST 带同一批参数。
- 版本列表每行显示 settlement_type 标签、运费窗口、paid 状态、「发放」与「作废(填原因)」操作；已发放行禁作废。

#### 4.9.5 详情抽屉（版本详情 & 业务员详情共用六分区）
回款明细 / 运费明细 / 调整明细（ADJUSTMENT 来源行）/ 销售与未收分析（复用 summary 的 sales/collected）/ 计算过程（含 rateSegments）/ 来源追溯（source_lines → 出库单 → 导入批次）。每条明细显示是否已核算（ACTIVE/RELEASED→对应批次链接）。

#### 4.9.6 导出（后端 CSV，前端 Blob 下载；权限复用 `monthly_commission.read.*`）
新增三个只读端点（挂在现有 Controller）：
1. `GET /api/monthly-commissions/versions/{id}/export/items.csv` 提成发放汇总：范围、业务员、经销/终端回款、运费、基础提成、调整、应结提成。
2. `GET /api/monthly-commissions/versions/{id}/export/lines.csv` 核算来源明细：出库单号、客户、业务员、出库日期、收款日期、回款、运费、渠道(含 source)、比例、提成贡献、批次。
3. `GET /api/monthly-commissions/versions/{id}/export/adjustments.csv` 调整明细：变化类型、原值、新值、差额、原业务员、新业务员、调整提成、来源导入批次。
- 文件头行：批次编号、生成时间、核算人、口径说明（运费规则/比例规则说明文本）。
- 走既有 CSV 导出组件/风格（参照其他列表页导出实现），不引 Excel 依赖。

**验收**：三项导出列与设计稿 §12 一致且含文件头；确认界面与版本详情数字与后端返回值逐项对账。

---

## 5. DDL 汇总（拟议迁移脚本）

| 脚本 | 内容 | 对应 |
|---|---|---|
| `V259__commission_settlement_batch_extend.sql` | versions 增 settlement_type/void_* /paid_*/freight_*；source_lines 增 channel_source；rates 与 history 精度 8,6；history 增 effective_from/to（可选）；存量 FINAL 版本回填 settlement_type='NORMAL'、存量 source_lines 回填 channel_source | G2 G3 G4 G5 G7 G9 |
| `V260__commission_source_line_channel_source_backfill.sql`（可与 259 合并） | 按 DEALER_CASE 回填 MARGIN_FALLBACK 等 | G5 |
| 如需 G3 方案 2：`V261__commission_version_draft_state.sql` | status 语义扩展 + checks_result JSON | G3（待评审） |

> 迁移脚本遵守项目规范：从 V258 之后递增（下一脚本 V259），不修改已执行脚本。金额/日期字段类型对齐现有 `commission_*` 表风格。

---

## 6. 实施排期（估算，含回归）

| 实施包 | 内容 | 后端 | 前端 | 估算(天) | 依赖 |
|---|---|---|---|---|---|
| P1 防重收口 | G1+G2+G3 | V259(a)、calculateVersion/voidVersion/insertVersion、changes 占用行、controller void/paid、类型推导 | 版本列表类型标签、作废填原因、发放操作 | 3~4 | 无 |
| P2 口径修正 | G4+G5+G7+G9 | V259(b)+V260、运费窗口工具+全链入参、channel_source 落列、比例 rateAt 化+rateSegments | 查询区运费控件、范围说明卡片、渠道来源展示、多段比例提示 | 4~5 | P1 |
| P3 检查引擎 | G6 | checks() + preview 扩展 + POST 门禁 | 预览/确认面板警告展示 | 2~3 | P2（G5 兜底警告入清单） |
| P4 体验导出 | G8 | 三个 export 端点 | 快捷范围、核心指标、确认弹窗、六分区抽屉、下载 | 4~5 | P1~P3 |

合计约 **13~17 人日**（不含评审等待与回归修复）。每包独立可发布、可回滚（DDL 单脚本；代码向后兼容：旧参数缺省走现行口径）。

**建议推进顺序**：先 P1（最优先：防重收口，完成前不再把「已核算」表达在出库单主状态上——现实现本就没在主单上标记，符合要求）→ P2（口径正确性）→ P3（门禁）→ P4（体验）。P2 与 P3 可在 P1 后并行。

---

## 7. 口径决策记录（2026-09-04 评审确认）

| # | 议题 | 决策 | 状态 |
|---|---|---|---|
| 1 | 回款口径 | **采用氚云出库内嵌收款子表**（`sales_outbound_payment_snapshots`，财务审核通过才写入，即真实收款数据，`legacy_status='CONFIRMED'`），维持现行口径；不引入银行到账回款链 | ✅ 用户确认 |
| 2 | 运费默认模式 | 维持 `PREVIOUS_CALENDAR_MONTH`（结束日所在月上一个完整自然月）为默认；`PREVIOUS_EQUAL_PERIOD`/`CUSTOM` 作为可选模式（P2 实施） | ✅ 按推荐 |
| 3 | 渠道人工纠正 | `MANUAL_CORRECTION` 本期不做，`channel_source` 先落 `DEALER_FIELD/MARGIN_FALLBACK/DEFAULT_RETAIL` 三值 | ✅ 按推荐 |
| 4 | 发放状态 | 只加 `paid_flag + paid_by + paid_at` 与「标记已发放」接口，已发放不可作废；不做发放单 | ✅ 按推荐 |
| 5 | DRAFT/CHECKED | 采用方案 1（预览+检查清单+确认弹窗），不落库中间态 | ✅ 按推荐 |
| 6 | 比例有效期建模 | 维持单 `effective_date` 历史行；**base 正常提成改为按明细发生日逐行追溯**（G7，P2 实施）；不做 effective_from/to 区间升级 | ✅ 按推荐 |
| 7 | 波动阈值 | 后端常量 30%（`COMMISSION_FLUCTUATION_WARN_RATIO`），暂不做界面配置 | ✅ 按推荐 |

> 实施顺序：P1 防重收口 → P2 口径修正 → P3 检查引擎 → P4 前端体验与导出。每包独立可发布。

---

## 8. 刻意维持现状的事项（避免过度改造）

- **表命名**：保留 `commission_calculation_versions/items/source_lines`（与 `commission_settlement_batches` 语义等价）；不在主单据上加「已核算」状态。
- **数据源边界**：新增查询一律延续 `H3_SOURCE_FILTER` 硬限制，不依赖前端筛选。
- **并发防重**：维持「唯一键 + FOR UPDATE + 同事务生成/占用」三件套。
- **不可变性**：不直接改已 FINAL 版本；纠正一律走作废重算或差额调整。
- **旧体系隔离**：V141 business / V205 sales_outbound_profit 链冻结不动。

---

## 9. 参考文件

- 设计稿：《月度提成核算 业务边界与核算口径（2026-09）》
- V213 / V214 / V217 / V219 / V225 / V244 / V258（backend/src/main/resources/db/migration/）
- `MonthlySalesCommissionService.java`（summary:84-148、运费窗口:88-91）
- `CommissionCalculationService.java`（calculateVersion:407-591、settlementPreview:595-648、loadUnsettledSources:659-700、summarizeSources:710-741、claimSources:743-763、insertVersion:765、listVersions:779-797、voidVersion:803-838、versionDetail:841-874、compareVersions:881-949）
- `CommissionPolicy.java`
- `MonthlySalesCommissionController.java`
- `frontend/src/api/monthlyCommission/index.ts`、`frontend/src/views/settlement/commission/MonthlyCommissionView.vue`

---

## 10. 实施进度

### P1 防重收口 —— 已完成（2026-09-04，后端编译通过、前端 lint 通过，未做运行期验证）

落地内容：

1. **V259 迁移**（`backend/.../db/migration/V259__commission_settlement_batch_extend.sql`）：
   - `commission_calculation_versions`：`settlement_type`（默认 NORMAL）、`freight_start_date/end_date/period_mode`（存量按旧口径回填上一自然月）、`void_reason/void_by/void_at`、`paid_flag/paid_by/paid_at`；
   - `commission_calculation_source_lines`：`channel_source`（按 DEALER_CASE 三档回填存量 RECEIPT/FREIGHT，ADJUSTMENT 行为 NULL）；
   - 比例统一 `DECIMAL(8,6)`：`monthly_commission_rates`、`monthly_commission_rate_history`、`commission_calculation_items`、`commission_outbound_changes`。
2. **结算类型（G2）**：`calculateVersion` 自动推导 NORMAL/SUPPLEMENT；前端传 `intent=RECALCULATION` 且期间无 FINAL 有 VOID 时记为 RECALCULATION（作废重算）。
3. **作废/发放规则（G3）**：`voidVersion(id, reason)` 原因必填、已发放拦截；新增 `markVersionPaid`；列表 `voidable = FINAL && 未发放`；版本详情与列表返回结算类型/运费窗口/发放/作废信息。
4. **调整占用登记（G1）**：新增 `claimAdjustments/insertAdjustmentLine/claimedSourcePairs`——结账时把「独立并入、未与本次正常提成来源重叠」的修正变化拆经销/零售两条 ADJUSTMENT 来源行（INSERT IGNORE 幂等），纳入 `active_source_key='ADJUSTMENT:{changeId}:{DEALER|RETAIL}'` 统一占用；作废随版本一并释放。
   - 语义说明：若某笔变化的 (employee, outbound) 同时作为本版本正常提成来源被占用（新增收款/首次运费本身触发的单据级变化），该变化不单列——金额已含入 base，避免同一金额在来源明细重复列示；其防重仍由 PENDING→PROCESSED 状态 + FOR UPDATE 行锁保证。作废原因/发放状态等均审计留痕。
5. **前端（G8 部分）**：版本列表新增「类型/发放」列（正常/补充/作废重算、已发放）；作废改为必填原因（`ElMessageBox.prompt`）；新增「标记发放」按钮；结账确认按 FINAL 计数、作废后重算自动带 `intent=RECALCULATION`。

**改动文件**：V259 迁移脚本；`CommissionCalculationService.java`、`MonthlySalesCommissionController.java`、`CommissionPolicy.java`、`MonthlySalesCommissionService.java`（修复一处遗留未定义变量 `settledItem`）；`frontend/src/api/monthlyCommission/index.ts`、`frontend/src/views/settlement/commission/MonthlyCommissionView.vue`。

**验证提示（人工）**：启动应用执行 V259 → 版本列表/详情查看新字段；作废未填原因与已发放拦截；补充核算类型；作废后重算类型标记；调整明细（ADJUSTMENT）占用行出现与作废释放。

### P2 口径修正 —— 已完成（2026-09-04，后端编译通过，前端 vue-tsc / ESLint 通过，未做运行期验证）

无需新增迁移脚本：P2 需要的列已在 V259 就绪（`freight_*`、`channel_source`、比例 DECIMAL(8,6)）。

1. **运费三模式（G4）**：`CommissionPolicy` 新增模式常量 + `FreightWindow` 解析器（默认 `PREVIOUS_CALENDAR_MONTH` 完全等同旧口径；`PREVIOUS_EQUAL_PERIOD`=前一个等长周期；`CUSTOM`=自定义起止，须填原因）。`calculateVersion`/`settlementPreview`/`summary`/`employeeDetail` 全部参数化（旧签名保留为默认委托，旧请求零影响），自定义原因校验只在结账/预览链路生效。
2. **渠道来源落列（G5）**：`CommissionPolicy.DEALER_SOURCE_CASE` 三档 CASE（DEALER_FIELD / MARGIN_FALLBACK / DEFAULT_RETAIL），`loadUnsettledSources` 输出 `channel_source` 并随 `claimSources` 写入新来源行；存量行由 V259 回填，ADJUSTMENT 行为空。
3. **比例按发生日追溯（G7）**：`summarizeSources`/`claimSources`/“重叠抵扣” 全部改用 `rateAt(history, empId, occurrenceDate)` 逐行追溯（运杂费行负向贡献）；单率段内数学等价旧口径，仅当同一员工同一批来源跨比例段时才会产生差异。员工快照比例取本批最晚发生日生效值；版本详情由来源行**现算** `rateSegments`/`multiRateSegments`（不落库、不改表），供“多段比例”提示。
4. **前端**：视图顶部运费范围选择（上一自然月/前等长周期/自定义+原因），口径提示更新；版本列表/详情显示运费模式与窗口；来源明细显示渠道来源（填经销商/毛利兜底/默认零售/修正并入）；业务员行“多段”badge + tooltip 展示逐段比例与行数；API 类型与 query/body 透传。

**验证提示（人工）**：构造比例跨段数据（月中调整业务员比例后再核算含调整日的期间），核对版本来源明细行 `applied_rate` 按发生日取值、员工汇总金额 = 明细行合计；切换等长/自定义窗口预览“总运杂费笔数/待扣金额”随之变化（与旧默认差异正确）；出现 MARGIN_FALLBACK（未填经销商但毛利 <15%）行标注“毛利兜底”。

### P3 检查引擎 —— 已完成（2026-09-04，后端编译通过、前端 vue-tsc 通过，未做运行期验证）

无需新增迁移脚本。**未做运行期验证前暂建议先人工核对，再用于正式结账**。

落地内容（核心在 `CommissionCalculationService`）：

1. **`runSettlementChecks(start,end,actualMode,prevStart,prevEnd,pending,items)`**：随 `settlementPreview` 一并返回 `{blocking:[], warnings:[]}`（每项 `{code,message,count,amount}`）。检查口径与本期核算一致：回款窗口 `[start,end+1)`、运杂费窗口 `[prevStart,prevEnd)`。
2. **阻断项（6 条，`blocking`）**：
   - `RECEIPT_WITHOUT_EMPLOYEE`：回款窗口内无业务员收款（金额不会计入本次提成）；
   - `FREIGHT_WITHOUT_EMPLOYEE`：运杂费窗口内无业务员出库运杂费（无法抵扣）；
   - `RECEIPT_WITHOUT_DATE`：全量缺收款日期收款（无法归属期间；已排除无业务员重叠口径）；
   - `FREIGHT_WITHOUT_DATE`：全量缺发货/业务日期的含运杂费出库单（无法归属窗口）；
   - `RATE_MISSING`：默认规则被删且员工无独立配置（内置 4% 兜底非常态，防御性检查）；
   - `IMPORT_BATCH_UNFINISHED`：本期间窗口内待核算修正所属 `h3_import_batches` 状态 ≠ COMPLETED（范围过滤避免未来期间在途导入误阻断）。
3. **警告项（8 条，`warnings`）**：`MARGIN_FALLBACK`（渠道按毛利兜底）、`RECEIPT_EXCEEDS_SALES`（收款合计超订单金额）、`NEGATIVE_FREIGHT`、`NEGATIVE_BASE`（基础提成为负）、`EMPLOYEE_CHANGED`（业务员变更补给）、`RECEIPT_CROSS_PERIODS`（同单收款跨自然月）、`FLUCTUATION_GT_30`（本范围首次结账且与最近 ≤6 个月 FINAL 版本总额差异 >30%）、`CUSTOM_FREIGHT_WINDOW`（自定义窗口提示）。
4. **结账门禁（G6）**：`calculateVersion` 在计算来源占用后、`insertVersion` 前重跑同口径检查，`blocking` 非空即 `IllegalArgumentException`（`gateBlockMessage`：最多列前 3 条消息，其余指引回预览查看），事务整体回滚。
5. **前端**：`api/monthlyCommission` 新增 `CommissionSettlementCheck` 类型，`CommissionSettlementPreview` 增 `blocking?/warnings?`；结账确认弹窗在金额预览下方展示：阻断区红色常显（提示需处理后才能结账），警告区黄色可展开（默认收起、不阻断）；`confirmSettle` 前 `settleBlockingCount>0` 即拦截；「确认结账」按钮在阻断非空时 `:disabled`。

**说明/取舍（与原 §8 检查清单差异）**：
- 「来源明细 ID 重复」「被其他 FINAL 占用」「同调整正被其他核算处理」未另设阻断：来源占用冲突由 `active_source_key` UNIQUE + PENDING→PROCESSED + `FOR UPDATE` 天然兜底（并发下数据库会拒绝重复占用，不会静默重复计）；
- 「金额无法解析」未设项：快照金额为 DECIMAL NOT NULL 列，不存在解析失败场景；负金额由 `NEGATIVE_FREIGHT`/`NEGATIVE_BASE`/`RECEIPT_EXCEEDS_SALES` 等警告覆盖；
- 「日期范围无效」仍由既有 `validateRange` + `requireFreightCustomReason` 直接抛错拦截。

**改动文件**：`backend/.../service/CommissionCalculationService.java`；`frontend/src/api/monthlyCommission/index.ts`、`frontend/src/views/settlement/commission/MonthlyCommissionView.vue`；`docs/monthly-commission-specialized-settlement-plan-20260904.md`。

**验证提示（人工）**：制造无业务员收款/缺日期数据 → 结账预览出现红色阻断、确认按钮置灰、POST /versions 被拒且报前 3 条；清除数据后恢复；制造毛利兜底/负运费/自定义窗口 → 预览出现黄色警告且可展开但不阻断；补充核算（范围已有 FINAL）不触发 FLUCTUATION_GT_30。

### P4 前端体验与导出 —— 已完成主要包（2026-09-04，后端编译通过、前端 vue-tsc/eslint 通过，未做运行期验证）

无需迁移脚本。**未做运行期验证前暂建议先人工核对导出的 CSV，再用于发放对账**。

落地内容：

1. **三项 CSV 导出（G8，后端）**，新增于 `MonthlySalesCommissionController` + `CommissionCalculationService`，URL 形如 `GET /api/monthly-commissions/versions/{id}/export/{items|lines|adjustments}.csv`（读权限可用，Content-Type `text/csv;charset=UTF-8`，响应头带下载文件名）：
   - `items.csv` 提成发放汇总：文件头口径（版本号/核算期间/运费窗口/结算类型/核算人/发放状态/备注，BOM 开头）+ 每业务员一行（正常经销回款/正常零售回款/正常经销运费/正常零售运费/修正经销回款/修正零售回款/修正经销运费/修正零售运费/正常提成/修正提成/应发提成 + 比例）；
   - `lines.csv` 核算来源明细：来源行粒度（回款/运杂费/调整 三类 + 业务员/出库单号/客户/归属日期/渠道及来源标签/来源金额/适用比例/提成贡献/状态/来源ID/导入批次），导入批次取该出库单最近一次 `h3_import_rows`（`resolveOutboundImportBatches` 批量解析防 N+1，无则留空）；
   - `adjustments.csv` 修正调整明细：已并入该版本（`version_id` + PROCESSED）的变化记录，含变化前后各口径金额、比例、来源导入批次文件、识别时间。
2. **前端下载**：`api/monthlyCommission/index.ts` 新增 `exportCommissionVersionCsv(versionId, kind)`（`responseType:'blob'`）；`MonthlyCommissionView.vue` 版本列表操作列新增「导出」下拉（汇总/来源明细/修正调整），版本详情弹窗 footer 增加三个对应导出按钮；统一走 `utils/ui/downloadBlob`，文件名含版本号。
3. **核算范围快捷选择**：顶部滤镜区新增「本月 / 上月 / 本季度」radio 快捷档（`quickRangeKey` + `onQuickRange`），选中即覆盖日期范围并按当前页签刷新。

**说明/取舍**：
- §4.9.4 确认界面：已由 P1/P2 结账弹窗覆盖（范围/已核算/待核算/待处理调整/预计提成/阻断红显/警告黄标/来源确认），本轮不再重复；
- §4.9.5 版本详情已含员工汇总、来源行、修正明细（按版本过滤）、结账前阻断/警告清单等分区；「销售与未收分析」「计算过程（逐步演示）」两分区暂未单列（明细已可按需导出核对），如需展示级下钻再单独排期；
- §4.9.1 业务员/部门/渠道筛选与 §4.9.2 指标卡细化（销售额/回款/已核算/待核算/运费/待处理调整分列）暂未铺开——月度参考页已有实时汇总（销售额/回款/未收/进度/运杂费/基数/提成/人数），结账弹窗含已核算/待核算口径；如需更细的在页分列再补充。

**改动文件**：`backend/.../controller/MonthlySalesCommissionController.java`、`backend/.../service/CommissionCalculationService.java`；`frontend/src/api/monthlyCommission/index.ts`、`frontend/src/views/settlement/commission/MonthlyCommissionView.vue`；`docs/monthly-commission-specialized-settlement-plan-20260904.md`。

**验证提示（人工）**：对任一 FINAL 版本分别下载三类 CSV，用 Excel 打开确认无乱码（BOM）、文件头与表头对齐、items 合计与版本列表「应发合计」一致、lines 行数与详情来源行数一致、adjustments 与修正明细一致；抽查无业务员变更期 adjustments 为空文件仍可下载；快捷范围「上月/本季度」切换后各页签数字随之变化。

### P4 运行期验证结果（2026-09-04，已完成）

用本机 MySQL（127.0.0.1，Flyway V259）跑通「预览 → 核算结账 → 三类 CSV 导出 → 作废清理」全链路，测试后数据库已复位（仅留 1 条 VOID 审计版本，无 FINAL；14 条修正变化全部回 PENDING；2893 行来源明细保留在 VOID 版本下、`active_source_key` 已清空可被重算占用）。

**实测对账（8 月核算，16 人，应发合计 169510.71）**：
- items.csv 16 行：正常 170368.62 + 修正 -857.91 = 应发 169510.71，与版本 `totalCommission` 一致；
- lines.csv 2893 行 = 1571 回款 + 1321 运杂费 + 1 调整，逐行与 DB `source_lines` **0 mismatch**，6 位贡献合计 169510.6983 = DB 精确合计（与发放合计差 0.0117，属员工级 round2 固有差）；
- adjustments.csv 1 条 -857.91，与并入该版本的变化记录一致；作废回滚 `rolledBackChanges=1`、`releasedSourceLines=2893`。

**发现并修复 3 处 bug（已进 P4 实施记录）**：
1. 结账预览 500：`settlementPreview` 为只读事务，内部 `loadUnsettledSources` 却执行 `FOR UPDATE`，MySQL 报 error 1792「Cannot execute statement in a READ ONLY transaction」。修复：方法增加 `boolean lockRows` 重载——核算结账传 `true`（保留防并发锁），预览传 `false`（只读事务不锁；防重仍由结账落库 PENDING→PROCESSED + UNIQUE 兜底）。
2. 预览/结账 SQL 语法错误：`runSettlementChecks` 四处阻断 SQL 以文本块 `""" + h3` 收尾，text block 会把行尾空格规范化掉，导致 `AND` 与 `o.external_source` 粘连成 `ANDo.external_source`。修复：局部 `h3` 变量加前导空格 `" " + H3_SOURCE_FILTER`。
3. lines.csv 溯源列被 round 成 2 位：逐行「提成贡献」经 `qAmt` 输出 2 位（如 DB -2.376 → 导出 -2.38），2893 行累计与明细偏差 0.38，破坏「贡献溯源 6 位」口径。修复：新增 `qAmt6`（6 位、去尾零），lines.csv 的「来源金额/提成贡献」按 DB 原精度输出；items.csv / adjustments.csv 维持发放 2 位。

**说明**：本次验证环境无真实前端浏览器交互，前端 Blob 下载/快捷范围仍建议按上面「验证提示」抽查一次；后端现以最新构建 jar 运行并健康（8081）。测试中间产物已清理，未遗留表数据。
