# 生产跟单人工结案与节点补全 P1 设计方案

> 依据：《生产跟单 P0 数据与代码复核报告》（`docs/production-tracking-closure-node-p0-audit-20261029.md`，快照 2026-10-29 16:21:46）
> 性质：**设计文档**。本轮**不修改业务代码、不执行迁移、不修改生产数据**，全文待评审后再实施。
> 目标：让人把「每个节点到底怎么样」说清楚并记下来；系统不替人编完成日期。

---

## 0. 设计原则（先立规矩）

| 原则 | 含义 |
| --- | --- |
| P-1 **推断日期不落 `completed_date`** | `completed_date` 直接决定交付日、整单完工日、P50/P75、入库待登记提醒（P0 §7），只接受真实登记日期 |
| P-2 **完成与否 ≠ 是否有完成日期** | 两者必须能独立表达：「已完成·日期不详」是一等公民 |
| P-3 **适用性以人工确认为准，且要持久化** | 现在靠源单关键词实时算（P0 §8.4），氚云重新导入会推翻人工结论 |
| P-4 **异常不能被正常统计吞掉** | 「无法确认」的节点要保持未完成，不能为凑结案而标完成 |
| P-5 **复用现有能力优先于新增** | 现有 entries（before/after JSON + 操作人）已是合格审计载体；`audit.log` 只做补齐 |
| P-6 **最小数据结构** | 能用 3 列解决就不用 5 列；能靠实时计算得到的（如参考依据）不落库 |

---

## 1. 目标与设计边界

### 1.1 要解决的问题

| 现象（P0 证据） | 根因 |
| --- | --- |
| 已结案 139 单里 18 单仍有非 DONE 节点行（31 行） | 结案不校验节点、也不记录「这些节点到底怎么样」 |
| 木箱 114 单 / 氧化 97 单「配置需要但无完成记录」 | 适用性判定不可持久化、没有人工确认入口 |
| 26 单当前超期里 17 单只差按需节点 | 「源单写了要做」被直接当成「必须已完成」 |
| 「没登记」和「不适用」在系统里长得一模一样 | 缺少区分手段 |

### 1.2 不做的事

- ❌ 不自动把任何日期写进 `completed_date`
- ❌ 不新增「节点模型五态适用性」理论模型（远期议题）
- ❌ 不为了结案把节点批量强制标记 DONE
- ❌ 不改动生产交期达成分析、周复盘的已验收口径

---

## 2. 节点完成状态：五态设计

### 2.1 五种业务结果与字段映射

| # | 业务结果 | `stage_status` | `completed_date` | `applicability` | `completion_source` |
| --- | --- | --- | --- | --- | --- |
| 1 | 已完成，日期明确 | `DONE` | 真实日期 | NULL 或 1 | NULL（默认） |
| 2 | 已完成，日期未知 | `DONE` | **NULL** | NULL 或 1 | `NO_DATE` |
| 3 | 未完成 | `PENDING` / `IN_PROGRESS` | NULL | NULL 或 1 | NULL |
| 4 | 不适用 | 保持原值（建议 `PENDING`） | NULL | **0** | NULL |
| 5 | 无法确认 | `PENDING` | NULL | NULL 或 1 | `UNCONFIRMED` |

### 2.2 为什么不新增 `N/A` 状态枚举

现有 `STAGE_STATUSES = {PENDING, IN_PROGRESS, DONE}`（`ProductionTrackingService:46`），而 `stage_status<>'DONE'` 的判断散布在：

- 列表/详情派生（`STAGE_DONE_CASE` 的 `SUM(...='DONE')`）
- 节点筛选 `COALESCE(m.stage_status,'')<>?`（上一轮刚修的 N1）
- 详情 `save()` 的日期清理逻辑（`useTrackingDetail.ts:589-591`）
- 表格导入、导入复核应用

新增枚举值会同时牵动上述四处及其回归测试。**用「独立列表达适用性」可以把影响面收敛到「分母计算」一处**，这是本方案的核心取舍。

### 2.3 「已完成·日期未知」的安全性（关键）

P0 §7 已确认：`loadOrderSamples` 在 `ProductionTrackingService:1941` 以 `if(stageCode!=null && completed!=null)` 过滤，
**「DONE 且 completed_date 为空」天然不进入 P50/P75 样本**。因此第 2 类状态：

- ✅ 「节点完成率」正确计入分子（按 `stage_status='DONE'`）
- ✅ 「工期分析」自动跳过（无日期）
- ✅ 「整单是否仍在产」按 `TRACKING_COMPLETE`（同样只看 `stage_status`）判为完成
- ⚠️ 「入库」节点若是第 2 类，`warehouse_in_date` 仍为 NULL → `DELIVERED_IN_DATE` / `COMPLETE_DATE` 会落到采购入库兜底

最后一条是**期望行为**：没有可信的入库节点日期时，就用有凭证的采购入库日期（而不是编一个）。

---

## 3. 字段级设计（最小迁移）

### 3.1 推荐方案：新增 3 列（V429）

```sql
-- V429__tracking_node_applicability.sql
ALTER TABLE production_tracking_milestones
  ADD COLUMN applicability      TINYINT       NULL COMMENT '节点适用性人工确认：1=适用 / 0=不适用 / NULL=未确认（沿用源单关键词默认规则）',
  ADD COLUMN applicability_note VARCHAR(300)  NULL COMMENT '确认或变更适用性的原因与依据',
  ADD COLUMN completion_source  VARCHAR(20)   NULL COMMENT '完成来源标记：NO_DATE=已完成但日期未知 / UNCONFIRMED=无法确认（异常）',
  ADD KEY idx_ptm_source (completion_source);
```

| 列 | 是否必需 | 说明 |
| --- | --- | --- |
| `applicability` | ✅ 必需 | 人工确认结果；NULL 表示「没确认过」，退回关键词规则，保证向后兼容 |
| `applicability_note` | ✅ 必需 | 满足「变更原因」与「关联依据」的要求（如"管理人确认：本机型不走氧化"） |
| `completion_source` | ✅ 必需 | 区分「已完成无日期」与「无法确认」两种异常/特殊态，供统计过滤 |
| 操作人 / 操作时间 | ❌ **不新增** | `production_tracking_entries` 已有 `created_by_name` / `created_at` + before/after JSON，冗余存会不一致 |

### 3.2 暂不新增的列（及理由）

| 候选列 | 决定 | 理由 |
| --- | --- | --- |
| `reference_date`（推断日期） | ❌ 暂不 | 用户结论：推断日期既不进工期也不进完成判定，落库收益低、维护成本高。UI 实时展示即可 |
| `completed_date_source`（每列来源） | ❌ 暂不 | 只在「有日期」时才有意义；届时由 writing 侧写入 `applicability_note` 记录依据即可 |
| `operated_by` / `operated_at` | ❌ 不新增 | 见上，entries 已覆盖 |

### 3.3 为什么不用 `manual_reference_date`

P0 §6 已证：该列语义是**前瞻预计**（V402 注释 `Per-order node forecasts`），并且：

- `:2485-2487` `effective = manual != null ? manual : systemDate` —— **优先于系统推算**参与 `predictedFinishDate` / `scheduleRisk`
- `:2893-2897` DONE 节点**禁止**填写该列
- 写进去会同时污染「正推参考日期」与「列表派生信号」

→ **明确不复用**。

### 3.4 氚云重新导入时如何不被覆盖（必须项）

现有写入是全列 UPSERT：

```java
// ProductionTrackingService:2957-2962
INSERT INTO production_tracking_milestones(production_order_id,stage_code,stage_status,completed_date,note,manual_reference_date,manual_reference_reason)
VALUES(...) ON DUPLICATE KEY UPDATE stage_status=VALUES(stage_status),completed_date=VALUES(completed_date),...
```

`applicability` / `applicability_note` / `completion_source` **不在 UPDATE 列表**，因此导入天然不会覆盖 ✅

但要处理两个新增要求：

| 场景 | 处理 |
| --- | --- |
| 该节点已被确认「不适用」（`applicability=0`），导入却带来新数据 | **导入时跳过该节点**的 status/date 写入（保留适用性），并在导入结果里提示 1 条冲突 |
| 该节点 `completion_source='NO_DATE'`（已完成无日期），导入带来真实日期 | **允许写入真实日期**并把 `completion_source` 置 NULL（事实优先，且符合 P-2） |

---

## 4. 适用性规则设计

### 4.1 判定优先级（从低到高）

```
第 1 层（默认）：源单关键词规则  OPTIONAL_CONFIGURED_HIT   ← 现状，实时算
第 2 层（人工）：适用            applicability = 1        ← 覆盖第 1 层
第 3 层（人工）：不适用          applicability = 0        ← 覆盖第 1 层，且从分母剔除
```

### 4.2 分母表达式改造（核心改动）

现状（`ProductionTrackingService:132-136`）：

```java
STAGE_TOTAL_WITH_OPTIONAL = 必选总数
                          + 已登记按需数（ms.sealer_optional_count / machine_optional_count）
                          + CONFIGURED_OPTIONAL_PENDING（配置需要但还没登记）
TRACKING_COMPLETE = (STAGE_DONE_CASE) = STAGE_TOTAL_WITH_OPTIONAL
```

改造后：

```
按需项 = 该制程下 optional=1 且 d.enabled=1 的节点
        AND (
              applicability = 1                                   -- 人工确认适用
           OR (applicability IS NULL AND OPTIONAL_CONFIGURED_HIT) -- 未确认，走默认关键词
           OR EXISTS (1 FROM milestones WHERE order_id 与 stage_code 对应 AND stage_status='DONE') -- 已登记完成（含历史）
        )
        AND COALESCE(applicability,1) = 1                          -- 人工确认不适用的排除
```

> ⚠️ 表达式必须保持**标量子查询/派生表**的用法约束：它依赖 `TRACKING_FROM` 提供的 `p` 与 `ms`，不能挪到没有该 FROM 的语句里。

### 4.3 变更要有凭据

`applicability` 从 NULL → 0/1，或 0 ↔ 1，都必须：

1. 填写 `applicability_note`（如「管理人确认：本机型不走氧化」「2026-10-29 客户取消木箱」）；
2. 写一条 entries（`before_json` / `after_json` 自动含新列）；
3. 调用 `audit.log("FOLLOWUP_APPLICABILITY", orderId, stageCode, "确认适用性", note)`。

### 4.4 现有表结构评估

**结论：现有表无法持久化适用性**，`production_tracking_stage_defs` 是**制程维度**（所有单共用），`production_tracking_milestones` 是「订单×节点」唯一落点 → 迁移目标只能是它，且 3 列足够。

---

## 5. 结案规则设计

### 5.1 两类结案

| | 正常人工结案 | 异常人工结案 |
| --- | --- | --- |
| 前提 | 所有适用节点都属于「已完成（有/无日期）」或「不适用」 | 存在至少一个 `UNCONFIRMED` 节点 |
| 权限 | `manufacturing.followup`（现有） | **新增 `manufacturing.followup.abnormal`** |
| 原因 | `closed_note`（现有，500 字） | 额外的 `abnormalReason`，**必填且建议 ≥10 字** |
| 审计 | entries + `audit.log` | entries + `audit.log`（含 reason 与涉及节点列表） |
| 统计处理 | 正常计入结案统计 | **单独标记**，不进正常完成率分子（见 §7） |

### 5.2 结案流程（服务端）

```
1. 校验 version / note / 停止互斥（现有）
2. 计算待确认节点清单（见 §6 的 preview 接口）
3. 若请求带了 nodeConfirmations：
     - 逐个校验 result 合法性与日期完整性（NO_DATE 时必须 completedDate=null）
     - 写入 milestones（applicability / completion_source / completed_date / applicability_note）
4. 重新判定：若仍存在「未完成」或「UNCONFIRMED」适用节点
     - 无 request.abnormal → 拒绝，返回待确认清单
     - 有 request.abnormal 且无 abnormalReason 或无权限 → 拒绝
5. 写 closed_at / closed_by_name / closed_note（+ 异常时额外字段）
6. 写 entries（before/after 快照，含全部节点）
7. audit.log("FOLLOWUP_CLOSURE", orderId, closed?"结案":"撤销结案", note)
8. 以上必须在**同一个 @Transactional** 内，任一步失败整体回滚
```

> ✅ 已核实（2026-10-29）：`changeClosure` 自身没有 `@Transactional`，但它走 `IdempotencyService.execute(...)`，
> 而该模板内部用 `TransactionTemplate` 包裹业务体（`IdempotencyService:112`）——**整个 lambda 天然在同一事务内**。
> P2 只需把节点写入放进这同一个 lambda，无需额外加注解。

### 5.3 批量确认

- 提供「将 N 个标记为：**已完成，日期未知**」的一键操作；
- **默认不预选**，必须让人勾选后才生效；
- 批量结果要记录操作人（entries 已覆盖）与逐节点的 `completion_source='NO_DATE'`；
- **禁止**提供「批量标记已完成并使用推断日期」的一键操作（守住 P-1）。

---

## 6. 接口级设计

### 6.1 改造现有结案接口

```
POST /api/manufacturing/followups/{id}/closure
权限：manufacturing.followup
```

请求体（`ClosureRequest` 扩展，保持向后兼容，旧字段不动）：

```jsonc
{
  "version": 12,
  "closed": true,
  "note": "历史跟单已完成，按需节点未逐项登记，现核实结案。",
  "abnormal": false,                 // 异常结案开关（需 manufacturing.followup.abnormal）
  "abnormalReason": null,            // abnormal=true 时必填
  "nodeConfirmations": [             // 可为空；为空表示「节点已完整」或「走旧行为」
    { "stageCode": "WOODEN_CRATE", "result": "COMPLETED_UNDATED" },
    { "stageCode": "OXIDATION",    "result": "NOT_APPLICABLE", "reason": "本机型不做氧化" },
    { "stageCode": "ASSEMBLY",     "result": "COMPLETED_DATED", "completedDate": "2026-10-18" },
    { "stageCode": "TESTING",      "result": "UNCONFIRMED",    "reason": "无法核实，异常结案" }
  ]
}
```

`result` 枚举（服务端校验白名单）：

| 值 | 含义 | 落库动作 |
| --- | --- | --- |
| `COMPLETED_DATED` | 已完成，日期已知 | `stage_status='DONE'`、`completed_date=?`、`completion_source=NULL` |
| `COMPLETED_UNDATED` | 已完成，日期未知 | `stage_status='DONE'`、`completed_date=NULL`、`completion_source='NO_DATE'` |
| `NOT_APPLICABLE` | 不适用 | `applicability=0`、`applicability_note=reason`、`completion_source=NULL` |
| `UNCONFIRMED` | 无法确认（异常） | `stage_status='PENDING'`、`completion_source='UNCONFIRMED'`、`applicability_note=reason` |

冲突响应（409/400）：

```jsonc
{ "error": "NODES_PENDING_CONFIRM", "nodes": [ /* preview 结构，供前端直接渲染 */ ] }
```

### 6.2 新增：结案预览（前端渲染清单用）

```
GET /api/manufacturing/followups/{id}/closure-preview
权限：manufacturing.followup
```

响应：

```jsonc
{
  "orderId": 16313,
  "complete": false,
  "nodes": [
    {
      "stageCode": "OXIDATION",
      "stageLabel": "氧化/喷特氟龙",
      "currentStatus": "PENDING",
      "applicabilityDefault": true,
      "applicabilitySource": "CONFIG",           // CONFIG | CONFIRMED | MANUAL_ROW
      "suggestedDate": "2026-10-18",
      "suggestedSource": "NEXT_STAGE",           // RECEIPT | NEXT_STAGE | NONE
      "suggestedEvidence": "后续节点「入库」完成于 2026-10-18",
      "evidenceConfidence": "REFERENCE_ONLY"     // VERIFIABLE | REFERENCE_ONLY
    }
  ]
}
```

### 6.3 新增：待核实清单（历史数据处理入口）

```
GET /api/manufacturing/followups/pending-nodes?scope=CONFIRMED|ALL&page=&size=
权限：manufacturing.followup
```

用于 P0 §4 的 114 / 97 单分批核实，与「适用节点未确认」列表打通。

---

## 7. 统计口径兼容（逐项）

| # | 口径 | 现状来源 | 是否需改 | 改法 / 说明 |
| --- | --- | --- | --- | --- |
| 1 | 节点完成率 | `STAGE_DONE_CASE` ÷ `STAGE_TOTAL_WITH_OPTIONAL` | **需改** | 分母按 §4.2 排除 `applicability=0`；分子不变（`stage_status='DONE'`） |
| 2 | 节点未完成数量 | 同上 | **需改** | 同 #1 |
| 3 | 当前超期订单 | `OPEN_TRACKING`（`closed_at` / `TRACKING_COMPLETE`） | **受影响（自动）** | 适用性确认后，27 单里的一部分会转成「完成」从而退出超期；不需要额外改代码 |
| 4 | 生产交期达成分析 | `COMPLETE_DATE = COALESCE(ms.warehouse_in_date, iq.full_in)` | **不改** | 不写推断日期就无影响；「已完成无日期」时仍走 `full_in` 兜底（期望行为） |
| 5 | 跟单周复盘 | 依赖 `analyzeUncut` | **不改** | 同上 |
| 6 | 历史参考工期 P50/P75 | `loadOrderSamples:1927-1943` | **不改，但必须补测试** | “DONE 无日期不入样本”是当前正确行为（《1941》），改动后此不变量需回归测试钉死 |
| 7 | `INBOUND_PENDING` / `INBOUND_UNLOGGED` | `:302` / `:319` | **不改** | 已结案单被 `:298` 的 `closed_at IS NOT NULL → NULL` 压制；「已完成无日期」的入库节点仍会触发 `INBOUND_UNLOGGED`（提示补日期，合理） |
| 8 | 订单结案与完整性统计 | `closed_at` + 节点 | **需新增维度** | 增加「异常结案」标记与「已完成但日期缺失数」（见 §7.1） |
| 9 | 跟单列表/详情的跟单状态 | `TRACKING_STATUS_CASE`（`:361-364`） | **不改** | 闭案优先级最高，已是「无论如何一律显示已完成」 |

### 7.1 建议新增的两个轻量指标

| 指标 | 定义 | 展示位置 |
| --- | --- | --- |
| **已完成·日期缺失数** | `stage_status='DONE' AND completed_date IS NULL` 的行数 | 节点分析抽屉概览、详情目标题旁 |
| **异常结案单数** | `closed_at IS NOT NULL AND EXISTS(适用节点 WHERE completion_source='UNCONFIRMED')` | 跟单列表筛选器 + 节点分析概览 |

这两个指标的作用是让「补齐数据」本身可见——今后再出现大量 `NO_DATE` 或异常结案时能立刻被发现，而不是悄悄混进完成率。

---

## 8. 前端交互级设计

### 8.1 结案弹窗（三步）

```
┌ 步骤 1：系统检查中 ─────────────────────────┐
│  适用节点 7 个：完成 5 · 未完成 2            │
│  → 需要确认 2 个才能正常结案                 │
└────────────────────────────────────────────┘
```
```
┌ 步骤 2：确认缺失节点（默认全部未选中）───────┐
│ ☑ 全选    ☐ 批量：标记为「已完成·日期未知」  │
│───────────────────────────────────────────│
│ 氧化/喷特氟龙                   适用性：是   │
│  ○ 已完成·日期已知  [2026-__-__]            │
│  ○ 已完成·日期未知                          │
│  ○ 本单不适用    ○ 无法确认                 │
│  参考：后续节点「入库」完成于 2026-10-18     │
│  （仅供参考，不会写入完成日期）              │
│───────────────────────────────────────────│
│ 入库                           适用性：是   │
│  ○ 已完成·日期已知  [2026-10-18]            │
│      ↳ 采购入库单日期，可作为事实写          │
│  ○ 已完成·日期未知                          │
│  ○ 本单不适用    ○ 无法确认                 │
└────────────────────────────────────────────┘
```
```
┌ 步骤 3：提交 ──────────────────────────────┐
│  □ 这是异常结案（存在无法确认的节点）        │
│     原因：[________________________] 必填   │
│     ⚠ 需要 manufacturing.followup.abnormal  │
│  [取消]  [确认结案]                         │
└────────────────────────────────────────────┘
```

### 8.2 交互约束

| 约束 | 说明 |
| --- | --- |
| 默认不预选 | 每个节点的四选一初始为空，必须手点 |
| 参考值与写入分离 | 参考日期为灰色徽标；只有选「已完成·日期已知」并确认输入框才能写入 |
| 采购入库日期特殊标注 | 标蓝「可作为事实写入」（有外部凭证），其余参考标灰「仅供参考」 |
| 「无法确认」必须填原因 | 否则无法提交，且会触发异常结案流程 |
| 状态显示 | 详情页节点行增加三种文案：已完成 2026-10-18 / **已完成·日期不详** / **不适用（人工确认）** / **无法确认** |

### 8.3 涉及的前端文件（待实施时确认）

- `frontend/src/composables/tracking/useTrackingDetail.ts`（结案流程、节点保存校验）
- `frontend/src/composables/tracking/useTrackingBatchClosure.ts`（批量结案）
- `frontend/src/components/production/TrackingDetailDrawer.vue`（节点行渲染）
- `frontend/src/apps/archive/views/production/ProductionTrackingView.vue`（批量入口、筛选器）
- `frontend/src/api/productionTracking.ts`（新接口封装）

---

## 9. 审计与事务一致性

| 要求 | 设计 | 现状差距 |
| --- | --- | --- |
| 操作人 | `production_tracking_entries.created_by_name`（现有列） | ✅ 已有 |
| 操作时间 | `entries.created_at` | ✅ 已有 |
| 变更前内容 | `entries.before_json`（含 state + milestones 快照） | ✅ 已有，但**快照是列白名单**，新列必须显式加（P1-a 已补 8 处，含复核服务的 4 处） |
| 变更后内容 | `entries.after_json` | ✅ 已有 |
| 原因 | `closed_note` / `applicability_note` / `abnormalReason` | ⚠️ 需增加 abnormalReason |
| 关联依据 | `applicability_note` 内写明（如"采购入库单 xxx"） | ⚠️ 约定写入格式 |
| 统一割面审计 | `audit.log("FOLLOWUP_CLOSURE" \| "FOLLOWUP_APPLICABILITY" \| "FOLLOWUP_NODE_CONFIRM", orderId, stageCode?, action, detail)` | ❌ **结案路径目前未用**，需补齐 |
| 事务一致性 | 结案 + 节点写入同一 `@Transactional`；entries 写入在同一事务内 | ⚠️ 需确认并可能补 `@Transactional` |

> 幂等：沿用现有 version 乐观锁（`entries` 有 `UNIQUE(production_order_id, version)`），重复提交会因唯一键冲突而回滚 ✅

---

## 10. 四分类清单

### 10.1 可直接复用（不改）

| 能力 | 位置 |
| --- | --- |
| entries 的 before/after JSON 审计载体 | `ProductionTrackingService:2912/2965/2978` |
| `audit.log(...)` 通用割面审计工具 | 基准相关方法已用（`:1879` 等） |
| version 乐观锁 + `UNIQUE(order_id,version)` | entries 表约束 |
| `TRACKING_COMPLETE` 判「是否完成」只看 `stage_status` | Policy 常量 |
| P50/P75 天然排除无日期节点 | `:1941` |
| 闭案优先级高于节点（`TRACKING_STATUS_CASE`） | `:361-364` |
| 采购入库兜底 `first_in` / `full_in` | `INBOUND_JOIN` / `QUANTITY_JOIN` |
| `manual_reference_date` 继续保持原语义 | V402 |

### 10.2 必须修改

| 改动 | 位置 | 内容 |
| --- | --- | --- |
| 放开「DONE 可无日期」写入约束 | `:2889-2891`、前端 `useTrackingDetail.ts:589-591`、`useTrackingListCompletion.ts:56` | 允许 `COMPLETED_UNDATED` |
| 适用性参与分母计算 | `STAGE_TOTAL_WITH_OPTIONAL`（`:132-135`）、`CONFIGURED_OPTIONAL_PENDING`（`:68-71`） | 排除 `applicability=0`，纳入 `applicability=1` |
| `ClosureRequest` 扩展 | `:521` record | 增加 abnormal / abnormalReason / nodeConfirmations |
| `changeClosure` 逻辑 | `:2829-2866` | 校验缺失节点、写 nodeConfirmations、异常分支、事务、audit.log |
| 详情返回节点字段 | `:2624-2641` | 带上 applicability / completion_source |
| UPSERT 列清单 | `:2957-2962` | 增加三列；**不**在 UPDATE 里覆盖 applicability（除非显式变更） |
| 导入路径冲突处理 | `:932-937`、`ProductionWorkbookReviewService:198-200` | 跳过 `applicability=0` 的节点；允许写入真实日期并清 `completion_source` |
| 节点行渲染 | `TrackingDetailDrawer.vue` | 四种文案（已知1个日期/日期不详/不适用/无法确认） |

### 10.3 必须新增

| 新增项 | 类型 |
| --- | --- |
| V429 迁移（3 列） | DDL |
| `manufacturing.followup.abnormal` 权限码 | 权限字典 + 角色授权 |
| `GET /{id}/closure-preview` | 接口 |
| `GET /followups/pending-nodes` | 接口 |
| 「已完成·日期缺失数」「异常结案单数」两个指标 | 统计 |
| 结案确认三步弹窗 UI | 前端 |

### 10.4 暂不需要实施的扩展

| 扩展 | 暂缓理由 |
| --- | --- |
| `reference_date` / `completed_date_source` 列 | 推断日期不入工期也不入判定，落库收益低于维护成本 |
| `N/A` 作为第三种 `stage_status` 枚举 | 影响面远大于「独立列表达适用性」（§2.2） |
| 节点完成率的「在制维度」/「排产等待拆分在制/已完工」 | 已在前轮决策中列为远期暂缓 |
| 五态适用性理论模型 | 远期议题，不夹带 |
| 自动补齐 114/97 单 | 必须人工核实（P0 §4.3 五种情况无法自动区分） |

---

## 11. 回归测试清单（实施时必须落地）

### 11.1 后端（契约 / SQL 断言）

| 编号 | 用例 | 断言要点 |
| --- | --- | --- |
| T-1 | `doneWithoutDateIsAllowed` | `COMPLETED_UNDATED` 能落库，`stage_status='DONE'`、`completed_date IS NULL`、`completion_source='NO_DATE'` |
| T-2 | `realCompletedDateNeverOverwritten` | 已有 `completed_date` 的节点再次确认时不被置空/覆盖（除非显式提供新日期） |
| T-3 | `notApplicableExcludedFromDenominator` | `applicability=0` 时该节点不计入 `STAGE_TOTAL_WITH_OPTIONAL`；`TRACKING_COMPLETE` 因此变完成 |
| T-4 | `explicitlyApplicableIncludedInDenominator` | `applicability=1` 且源单关键词不命中时，节点仍计入分母 |
| T-5 | `unconfirmedBlocksNormalClosure` | 存在 `UNCONFIRMED` 且 `abnormal=false` → 拒绝，返回待确认清单 |
| T-6 | `abnormalClosureRequiresReasonAndPermission` | `abnormal=true` 无 reason 或无权限 → 拒绝；满足 → 成功且 entries 含 abnormalReason |
| T-7 | `closureWritesEntryAndAudit` | 结案后存在 entries 行（before/after 含新列）且触发 `audit.log` |
| T-8 | `closureRollsBackOnNodeFailure` | 节点写入失败时 `closed_at` 不被写入（事务一致性） |
| T-9 | `p50ExcludesDoneWithoutDate`（**关键不变量**） | `loadOrderSamples` 不返回 `completed_date IS NULL` 的样本 |
| T-10 | `importDoesNotOverwriteApplicability` | 导入 UPSERT 后 `applicability` / `applicability_note` 保持原值 |
| T-11 | `importCanSupplyRealDateForNoDateNode` | `NO_DATE` 节点被导入真实日期后 `completion_source` 置 NULL |
| T-12 | `inboundRemindersUnaffected` | 已结案单不带 `INBOUND_*` 标签；「已完成无日期」的入库节点仍触发 `INBOUND_UNLOGGED` |
| T-13 | `completeDateFallsBackToReceipt` | 入库节点 `DONE` 无日期时 `COMPLETE_DATE` 取 `full_in` |

### 11.2 前端

| 编号 | 用例 |
| --- | --- |
| U-1 | 结案弹窗默认不预选任何节点，未选择时「确认结案」禁用 |
| U-2 | 采购入库日期显示「可作为事实写入」，后置节点日期显示「仅供参考」且不写入请求体 |
| U-3 | 选「无法确认」时必须填原因，否则提交被拦 |
| U-4 | 详情节点行四种文案渲染正确 |
| U-5 | 无 `manufacturing.followup.abnormal` 权限时异常结案开关不可选并给出提示 |

### 11.3 数据对拍（改动后必做，同一快照）

以 P0 报告数值为基线，核对方向而非绝对一致：

| 指标 | P0 基线 | 期望方向 |
| --- | --- | --- |
| 结案单非 DONE 节点行 | 31 行 / 18 单 | 人工确认后下降 |
| 当前超期 | 26 单 | 适用性确认后下降（尤其那 17 单只差按需的） |
| 木箱 / 氧化关注单 | 114 / 97 | 转为「已核实」而非单纯清零 |
| P50 / P75 | 现有值 | **必须不变**（T-9） |

---

## 12. 实施顺序

| 阶段 | 内容 | 前置 | 可独立验收 |
| --- | --- | --- | --- |
| **P1-a 数据语义** | V429 迁移（3 列）+ 放开 DONE 无日期 + 详情返回新字段 + T-1/T-2/T-9/T-10/T-11 | — | ✅ 可只对 ceasefire 看，不影响现有统计（新列默认 NULL = 走旧规则） |
| **P1-b 审计补齐** | `changeClosure` 补 `audit.log` + 确认 `@Transactional` + T-7/T-8 | P1-a | ✅ |
| **P2 结案改造** | `ClosureRequest` 扩展 + preview 接口 + 异常结案权限/原因 + 三步弹窗 + T-3~T-6、U-1~U-5 | P1-a/b | ✅ |
| **P3 统计统一** | 分母按适用性改造 + 两个新指标 + T-3/T-4/T-12/T-13 + 数据对拍 | P1-a | ✅ |
| **P4 历史处理** | 按 §6.3 清单分批核实 114 / 97 单，**逐批确认不自动补齐** | P2/P3 | ✅ |

> P1-a 之后节点统计仍走旧规则（`applicability` 默认 NULL），因此**每个阶段都可以单独发布、单独回滚**。

---

## 13. 决策结论（2026-10-29 已拍板）

| # | 决策 | **结论** |
| --- | --- | --- |
| D-1 | 新列是否记录「确认人与时间」 | **不记** —— `entries` 的 `created_by_name` / `created_at` 已覆盖，重复存会产生两份不一致的真相 |
| D-2 | 异常结案权限 | **新增** `manufacturing.followup.abnormal`，不复用 `system.config.manage`（语义不符） |
| D-3 | 114 / 97 单历史处理入口 | **提供批量入口，但必填原因 + 二次确认**，不做无监督批量；批量只作用于「适用性」的确认（适用 / 不适用），**不自动标记节点完成** |

---

## 14. 实施台账（P1-a 已完成，2026-10-29）

### 14.1 本阶段做了什么

| 文件 | 改动 |
| --- | --- |
| `V429__tracking_node_applicability.sql`（新增） | 加 3 列 + `idx_ptm_completion_source`；注释里写明「为什么不复用 `manual_reference_date`」「为什么暂不存推断日期」 |
| `ProductionTrackingService.java` | ① 详情 SELECT 带新列并在返回体补 `applicability / applicabilityNote / completionSource`；② 结案与停止/恢复的里程碑快照 SELECT 补新列；③ `add` 的 before/after 两处快照补新列 |
| `ProductionTrackingWorkbookReviewService.java` | 复核应用的两对 before/after 快照补新列（P0 调研漏掉的写路径，见下） |
| `ProductionTrackingNodeApplicabilitySchemaTest.java`（新增） | 5 条契约用例，全绿 |

### 14.2 落地时修正了两处 P0/P1 判断（留档）

| 原判断 | 实际情况 |
| --- | --- |
| 「结案可能需要补 `@Transactional`」 | **不需要**：`IdempotencyService.execute` 内部用 `TransactionTemplate` 包裹业务体（`IdempotencyService:112`），整个 lambda 天然同事务。P2 只要把节点写入放进同一个 lambda 即可 |
| 「新列会**自动**进入 before/after 快照」 | **不会**：快照是**列白名单**（`SELECT stage_code,stage_status,completed_date,note...`），新列必须显式加。已补 **8 处** |
| 快照写路径只有 `changeClosure` 与 `add` | 漏了 `ProductionTrackingWorkbookReviewService`（车间进度表复核）另有 **4 处**；**其中 2 处是靠新测试抓出来的**（`replace_all` 因缩进未命中而只改了一半）——这正是这条契约测试的价值 |

### 14.3 本阶段刻意未做的事（不要跳过顺序）

**尚未放开「DONE 可以没有完成日期」。** 理由：放开后会产生「日期为空、但没有任何来源标记」的裸数据，解释成本比现状更高。
放开必须与能写 `completion_source` 的通道（P2 的 `nodeConfirmations`）**同一批发布** —— 测试里用
`doneWithoutDateIsStillRejectedToday()` 把这件事钉住了，届时该用例同步改写。

### 14.4 尚未执行迁移

`mvn test` 不触发 Flyway。V429 会在**下次启动后端**时由 Spring 自动执行。
若要先验证迁移能否跑通，可在本机跑一次 flyway validate/migrate，或重启后端观察启动日志。

### 14.5 P1-b：统一割面审计（同日完成）

| 文件 | 改动 |
| --- | --- |
| `ProductionTrackingService.changeClosure` | 结案 / 撤销结案写 `audit.log("TRACKING_STATE", id, orderNo, 结案\|撤销结案, content+是否登记交付)` |
| 同上 `changeDisposition` | 停止 / 恢复跟单同样写一条，口径一致 |
| 同上 `add`（跟进保存） | 「节点/状态修改」也进割面审计 —— 它是适用性、日期、预测的一手写入口 |
| `ProductionTrackingClosureAuditTest`（新增，6 条全绿） | 真实调用三个方法，用 ArgumentCaptor 校验 entityType / entityId / modelName / action / changes |

**entityType 选型**：本模块已有 `STAGE_DEF` / `NODE_BENCHMARK` / `PRODUCT_BENCHMARK`（大写下划线、表名语义），
工单级操作此前**没有任何先例**；本次新增 `TRACKING_STATE`（对应被改写的 `production_tracking_state` 表），
`modelName` 存**单号**（审计列表要能落到人看得懂的单据）。

**为什么写在幂等事务内部**：业务体与审计在同一个 `TransactionTemplate` 事务里，
节点写入失败回滚时审计一并回滚，不会出现「有日志没改动」或「改了一半」。

### 14.6 P2-1：结案时的节点人工确认（写通道，2026-10-30 完成）

| 文件 | 改动 |
| --- | --- |
| `ClosureRequest` | 扩 `abnormalReason` + `nodeConfirmations`，保留三参兼容构造器（老调用零改动） |
| `NodeConfirmation`（新增 record） | 节点 code + 四选一结论 + 日期 + 依据 |
| `closureNodeConfirmations`（新私有方法） | **事务外**做完全部校验：结论白名单、补日期必须带日期、依据字数、权限、整体原因 |
| `applyNodeConfirmations`（新私有方法） | **事务内**写库，与结案状态 / 时间线 / 审计同生同灭 |
| `V431__tracking_closure_node_confirmation.sql`（新增） | 登记 `manufacturing.followup.abnormal`，默认只给 ADMIN |
| `ProductionTrackingClosureNodeConfirmationTest`（新增，12 条） | 四种结论落列、权限边界、抽屉入口同钥匙 |

四选一 → 落列 / 权限：

| 人点的结论 | 写库 | 需要 abnormal 权限 | 审计动作 |
| --- | --- | --- | --- |
| 已完成，日期是 X | `DONE` + `completion_source='MANUAL'` | 否（有据可查） | 结案 |
| 已完成，日期查不到 | `DONE` + `completed_date=NULL` + `completion_source='NO_DATE'` | **是** | 结案 |
| 适用，只是还没登记 | `applicability='APPLICABLE'` | **是** | **异常结案** |
| 本单不适用 | `applicability='NOT_APPLICABLE'` | **是** | **异常结案** |

### 14.7 与并行会话的合并裁决（A 方案，2026-10-30）

并行会话另起了一条链路：`Milestone` 加 `completionSource` 字段 → 跟进抽屉（`TrackingDetailDrawer.vue` 等三个前端文件）可随时给单个节点标「日期未知」，但不校验权限。

**裁决：保留两个入口，共用同一把钥匙**（A 方案）：

| 落地项 | 说明 |
| --- | --- |
| `add` 通道补权限闸口 | `NO_DATE` 与结案通道同要求 `PERMISSION_ABNORMAL_CLOSURE`（`ProductionTrackingService` 的两处 `users.can(PERMISSION_ABNORMAL_CLOSURE)`） |
| 契约测试守住「不漂移」 | `bothWriteEntryPointsShareOnePermissionGate`：权限必须收敛成一个常量，且**两个入口都要引用它**（当前 2 处），少一处就红 |
| 保留并行会话的结构 | 它把「要写依据」与「要权限」分成 `EVIDENCE_RESULTS` / `ABNORMAL_RESULTS` 两个集合，语义不同，保留；当前取值相同 |

**名词口径（重要）**：「异常结案」这个审计动作名**只留给改了适用性的裁决**（`APPLICABLE` / `NOT_APPLICABLE`）——
它们改的是「这道工序算不算」，统计分母会跟着变，必须能被单独挑出来复核；
`DONE_NO_DATE` 不改变分母（节点确实完成了，只是没有日期），动作名仍是「结案」，靠 changes 里的摘要留痕。
这样既保留了追溯能力，也不会给单据乱贴「异常」标签。

### 14.8 前端侧：结案弹窗与口径对齐（2026-10-30）

并行会话已完成 P2-2 的结案弹窗（`TrackingDetailDrawer.vue`：待确认节点清单 + 四选一下拉 + 异常原因输入 + `canAbnormalClosure` 门控），
因此落到前端侧的只有两件事：

| 改动 | 说明 |
| --- | --- |
| `closureHasAbnormal` 纳入 `DONE_NO_DATE` | 原实现只算 `APPLICABLE` / `NOT_APPLICABLE`，于是选了「已完成，日期未知」时不弹原因输入框、也不拦无权限的人，用户填到最后点保存才收到 403。这是把 A 方案落到后端后**新暴露**出的前后端不一致 |
| `TrackingClosurePermissionGate.test.ts`（新增，6 条全绿） | 把「三种结论 ↔ abnormal 权限」钉成前后端一致的契约：既看后端 `ABNORMAL_RESULTS`，也看前端 `closureHasAbnormal` / 下拉选项 / API 类型 union。再漂移就红 |

为什么这种不一致必须靠契约兜：两边单看都「能跑通」，只有合起来用才会出事 —— 肉眼评审发现不了。

---

结论：本方案用 **3 个新列 + 结案时的一次人工确认**，把「没登记」与「不适用」分开，把「完成」与「有日期」分开；
**不写一个推断日期，不改已完成的分析口径**，从而让节点完成率、P50/P75、交付/完工日三处继续保持可信。
