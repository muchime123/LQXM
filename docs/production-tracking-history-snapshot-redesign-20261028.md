# 历史快照整改设计（2026-10-28）

> ## ⏸️ 状态：远期设计储备 · 暂缓实施（2026-10-28 定）
>
> **不要按本文创建迁移或启动开发。** 历史快照与归档体系不在 LQXM 生产跟单当前建设阶段内。
>
> **保留价值**：缺陷清单（D1-D6）与权限风险分析继续有效，作为未来若决定启用时的输入。
> **红线**：在权限模型（§3）落地前，快照功能**不得开放或自动启用**——当前共享快照会越权（见审计文档 1.3）。
>
> ---
>
> **状态：设计方案，未实施。**
> 本文不涉及任何已执行的迁移、不改生产库、不改业务代码。文内 SQL 均为**草案**，需评审通过后才落地。
>
> **上游**：`docs/production-tracking-analytics-audit-and-decisions-20261028.md`（审计与整改决策）
> **决策依据**：2026-10-28 业务决策（见 §1）

---

## 1. 已确认的决策

| 项 | 决策 |
| --- | --- |
| 归档权限 | **方案 B**：仅具备生产跟单**全量数据权限**的主管或管理员，可生成 / 查看 / 管理团队历史快照 |
| 普通跟单员 | 保留当前权限范围内的**实时周复盘**，**不展示**团队历史快照 |
| 快照口径 | 归档快照必须使用**统一全量统计口径**，不继承触发操作人员的个人数据范围 |
| 归档触发 | **显式归档动作**，不采用"查询即固化" |
| 历史补录 | 标注回溯生成时间与数据来源，**不冒充历史真实状态** |
| 权限定义 | 历史归档的**读权限与写权限分开定义**；有 `manufacturing.read.all` 不等于拥有归档管理权限 |
| V421 唯一键 | 通过**新的迁移脚本**修复，**不修改已执行的 V421** |

---

## 2. 现状与缺陷

### 2.1 链路现状

| 环节 | 位置 | 状态 |
| --- | --- | --- |
| 快照表 | `V421__production_tracking_analytics_snapshots.sql` | ✅ 已建 |
| `snapshotForPeriod` | `ProductionTrackingAnalyticsService:217-230` | ✅ 已实现，**零调用方** |
| `readSnapshot` | `ProductionTrackingAnalyticsService:235-249` | ✅ 已实现，**零调用方** |
| `listSnapshots` | `ProductionTrackingAnalyticsService:255-277` | ✅ 已实现 |
| 端点 `/weekly-review/history` | `ProductionTrackingController:169-173` | ✅ 已暴露 |
| 前端 `TrackingReviewHistory.vue` | 挂载于 `ProductionTrackingWeeklyReviewDrawer.vue:102` | ✅ 已挂载，恒显示空态 |

### 2.2 缺陷清单

| # | 缺陷 | 级别 | 影响 |
| --- | --- | --- | --- |
| D1 | 无触发点，`snapshotForPeriod` / `readSnapshot` 从未被调用 | P0 | 历史趋势恒为空态 |
| D2 | 唯一键 `uk_ptas_period_ws(period_from, period_to, workshop)` 含可空 `workshop`；MySQL 多列 UNIQUE 中 NULL 互不相同 ⇒「全部车间」不幂等 | P0 | 重复堆积；`readSnapshot` 因 `LIMIT 1` 稳定读到最早一条，固化时刻错误且不报错 |
| D3 | 快照无权限维度，而分析结果按用户数据范围收敛 | P0 | 共享快照必然越权 |
| D4 | 未固化 `asOf`；`analyzeUncut` 用 `LocalDate.now()` 现算 | P0 | 已归档周期的数字仍随查询日漂移 |
| D5 | 无归档方式标识，无法区分「当时固化」与「事后回溯」 | P1 | 违反约束 4.3 |
| D6 | 注释与文档误标迁移号 V419（实际 V419 = `sealer_material_ready_stage`，快照表是 V421） | P2 | 文档漂移，误导后续 |

---

## 3. 权限模型设计

### 3.1 现成先例：读写权限分离

`V383__tracking_baseline_permission.sql` 已确立该模式，应直接沿用：

```1:5:backend/src/main/resources/db/migration/V383__tracking_baseline_permission.sql
-- 生产跟单「节点参考完成基准」的可配置入口（负责人 2026-10-10 定：仅系统管理员可改天数）。
-- 基准数据本身由 V382 建表并播种中位数，本迁移只登记权限，不动任何基准值。
-- 权限码独立于 manufacturing.followup：跟单员能推节点，但不该改「一般要几天」的判据。
INSERT IGNORE INTO system_permissions(permission_code, permission_name)
VALUES ('manufacturing.baseline.manage','配置生产跟单节点参考基准天数');
```

对应端点以独立注解把关（`ProductionTrackingController:451-533`）：

```
 * 独立权限 manufacturing.baseline.manage——跟单员能推节点，但不该改「一般要几天」的判据。
```

### 3.2 新增权限

| 权限码 | 名称 | 语义 | 建议绑定 |
| --- | --- | --- | --- |
| `manufacturing.snapshot.read` | 查看团队历史归档快照 | **读**：历史趋势列表与详情 | `ADMIN` + `PRODUCTION_TRACKING_SUPERVISOR` |
| `manufacturing.snapshot.manage` | 归档 / 重建 / 删除历史快照 | **写**：固化周期、删除错误快照 | `ADMIN` + `PRODUCTION_TRACKING_SUPERVISOR` |

> 若希望进一步收紧（仅 `ADMIN` 可固化，主管只读），**只需调整角色绑定，不影响代码与表结构**。本设计默认按用户决策"主管或管理员可生成、查看和管理"绑定。

### 3.3 角色现状（已核实）

```34:44:backend/src/main/resources/db/migration/V372__production_tracking_role_split.sql
-- 2) 新增「生产跟单主管」角色：看全部生产跟单（成员为空，由管理员在「角色权限」页按需加人）
INSERT INTO system_roles(role_code, role_name, data_scope, built_in)
SELECT 'PRODUCTION_TRACKING_SUPERVISOR', '生产跟单主管', 'ALL', 0
 WHERE NOT EXISTS (SELECT 1 FROM system_roles WHERE role_code = 'PRODUCTION_TRACKING_SUPERVISOR');

INSERT INTO system_role_feature_grants(role_id, feature_code, data_scope, selected_actions)
SELECT r.id, 'MANUFACTURING', 'ALL', 'VIEW,manufacturing.followup'
  FROM system_roles r
 WHERE r.role_code = 'PRODUCTION_TRACKING_SUPERVISOR'
```

| 角色 | `role_code` | MANUFACTURING 数据范围 |
| --- | --- | --- |
| 生产跟单主管 | `PRODUCTION_TRACKING_SUPERVISOR` | **ALL** |
| 生产跟单员 | `PRODUCTION_TRACER` | SELF |
| 系统管理员 / 老板 | `ADMIN` / `BOSS` | ALL（V372 第 4 行注释） |

### 3.4 服务端硬校验（不依赖角色配置）

仅靠权限码不够——角色权限页可随时调整，且 `manufacturing.read.all` 与"全量数据范围"不是同一回事。归档端点必须额外做**运行时校验**：

```
归档前置校验（全部满足才允许）：
  ① 持有 manufacturing.snapshot.manage
  ② users.scopeOf(FeatureCode.MANUFACTURING) == "ALL"
  ③ 统计口径强制按 ALL 计算（不继承调用者数据范围）
  ④ 周期闭区间 [from, to] 均非空
```

违反 ② 时返回明确错误，而非降级为个人口径快照。⇒ 满足"归档快照必须使用统一全量统计口径"。

### 3.5 实现陷阱（必须注意）

```8:10:backend/src/main/resources/db/migration/V372__production_tracking_role_split.sql
-- 权限行要与特征授权一致：AccessControlService.expandGrant 在保存角色时会用 data_scope 重算
-- system_role_permissions（VIEW 变体 ALL→read.all / DEPARTMENT→read.department / 其余→read.self），
-- 故本脚本按同一规则物化权限行，避免日后在「角色权限」页一保存权限集就漂移。
```

`AccessControlService.expandGrant` 会按 `data_scope` **重算** `system_role_permissions`。新增的两个权限码**不在该展开规则内**（非 VIEW 变体），因此不会被自动重建；但登记权限行时必须与 V383 的写法一致（`INSERT IGNORE` + 同步 `system_role_feature_grants.selected_actions`），否则在角色权限页保存后会漂移。

---

## 4. 数据模型变更（草案，未执行）

### 4.1 迁移编号

截稿时已执行最大版本 **V423**（`V423__create_website_company_profile.sql`），故本文草案编号取 **V424**。

> ⚠️ `AGENTS.md` 记载"当前最大 V401"已过时。
>
> ⚠️ **V424 不是预留号，且截稿时已被占用**（2026-10-28 实测）：
> 工作区已存在**未跟踪**的 `V424__create_website_company_milestones.sql`（官网公司里程碑，另一条并行开发流）。
>
> ⇒ 实际创建本方案迁移文件前**必须重新核对** `backend/src/main/resources/db/migration/` 下的最大版本号与所有未跟踪文件，并顺延。
> 在 V424 落地的前提下，本方案应为 **V425**（若官网侧再追加则可能更后）。**禁止直接套用本文编号。**

### 4.2 草案 `V424__tracking_analytics_snapshots_key_and_scope.sql`

```sql
-- 修复快照表唯一键与归档元数据（草案，未执行）
--
-- 背景：V421 唯一键 uk_ptas_period_ws(period_from, period_to, workshop) 中 workshop 可为 NULL，
--       MySQL 多列 UNIQUE 中 NULL 互不相同 ⇒ 「全部车间」场景不幂等（ON DUPLICATE 不命中）。
--       本脚本只加约束与列，不修改已执行的 V421。

-- 0) 防御性去重：writer 从未被调用，理论上 0 行；若已有重复只保留最早一条
DELETE s FROM production_tracking_analytics_snapshots s
JOIN (
  SELECT id FROM (
    SELECT id, ROW_NUMBER() OVER (
      PARTITION BY period_from, period_to, COALESCE(workshop,'')
      ORDER BY generated_at ASC, id ASC) rn
    FROM production_tracking_analytics_snapshots
  ) t WHERE t.rn > 1
) d ON d.id = s.id;

-- 1) 存量 NULL 归一为 ''（必须先于 NOT NULL）
UPDATE production_tracking_analytics_snapshots SET workshop = '' WHERE workshop IS NULL;

-- 2) 收窄为 NOT NULL DEFAULT ''，使唯一键对所有场景生效
ALTER TABLE production_tracking_analytics_snapshots
  MODIFY workshop VARCHAR(64) NOT NULL DEFAULT '' COMMENT '车间；空串 = 全部车间';

-- 3) 归档元数据
ALTER TABLE production_tracking_analytics_snapshots
  ADD COLUMN visibility_scope VARCHAR(20) NOT NULL DEFAULT 'ALL'
    COMMENT '生成该快照时使用的数据范围；当前仅允许 ALL（全量口径）' AFTER workshop,
  ADD COLUMN as_of DATE NULL
    COMMENT '统计截止日：归档时定格，重查不再随查询日变化' AFTER period_to,
  ADD COLUMN source_type VARCHAR(20) NOT NULL DEFAULT 'MANUAL'
    COMMENT 'SCHEDULED=周期结束自动 / MANUAL=手工固化 / BACKFILL=事后回溯补录' AFTER visibility_scope;

-- 4) 回溯补录的溯因说明（仅 BACKFILL 填写）
ALTER TABLE production_tracking_analytics_snapshots
  ADD COLUMN backfill_note VARCHAR(300) NULL
    COMMENT 'BACKFILL 专用：说明为何事后补录，以及数据来源' AFTER source_type;

ALTER TABLE production_tracking_analytics_snapshots
  ADD KEY idx_ptas_period (period_from, period_to);
```

### 4.3 代码侧必须同步的归一化

`blankToNull` 把空串折成 `null`（`AnalyticsService:335`），改造后快照路径**必须改用空串**，否则 `workshop <=> NULL` 永远匹配不到 `''`：

| 位置 | 当前 | 改造后 |
| --- | --- | --- |
| `snapshotForPeriod:225` 写入参数 | `blankToNull(workshop)` | `blankToEmpty(workshop)` → `''` |
| `readSnapshot:241` 查询参数 | `blankToNull(workshop)` | `blankToEmpty(workshop)` → `''` |
| `listSnapshots:273` 查询参数 | `blankToNull(workshop)` | `blankToEmpty(workshop)` → `''` |

> `<=>` 本身对非 NULL 值有效，故 SQL 无需改；**只改参数归一化**即可。
> ⚠️ 不要改动 `blankToNull` 本体——它在 `:329-330` 用于展示侧"空值显示 —"，语义不同。

### 4.4 快照内容需新增字段

`snapshot_json` 由 `analyzeUncut` 结果序列化而来，已含 `asOf`（`WeeklyReviewService:44` 读取它）。因此：

- `as_of` 列冗余一份用于**查询与展示**（避免解析 JSON）
- 归档时以 `result.get("asOf")` 落列，与 JSON 内一致

---

## 5. 触发与归档流程

### 5.1 两条独立链路

| 链路 | 端点 | 触发 | 数据源 | 落库 | 权限 |
| --- | --- | --- | --- | --- | --- |
| **实时周复盘** | `GET /weekly-review`（现状端点） | 用户查询 | 现算 `analyzeUncut` | ❌ 永不固化 | `manufacturing.read.*` 任一 |
| **团队历史归档** | `GET /weekly-review/history`（现状端点） | 只读快照 | `readSnapshot` / `listSnapshots` | — | **新增** `manufacturing.snapshot.read` |
| **归档动作** | **新增** `POST /weekly-review/archive` | 显式点击 / 定时任务 | `snapshotForPeriod`（全量口径） | ✅ | **新增** `manufacturing.snapshot.manage` |

### 5.2 归档类型

| `source_type` | 触发方式 | 展示要求 |
| --- | --- | --- |
| `SCHEDULED` | 自然周结束后定时任务自动固化 | 正常展示 |
| `MANUAL` | 主管在周期内手工固化 | 正常展示 |
| `BACKFILL` | 周期已过才首次生成 | **显著标注「回溯生成，非当时真实状态」** + `backfill_note` |

### 5.3 前端改造

| 位置 | 改动 |
| --- | --- |
| `ProductionTrackingWeeklyReviewDrawer.vue:102` | 按权限条件挂载：无 `manufacturing.snapshot.read` 时不渲染 `TrackingReviewHistory` |
| 同上 | 新增「固化本周期周报」按钮，无 `manufacturing.snapshot.manage` 时隐藏 |
| `TrackingReviewHistory.vue:9` | 空态文案区分三种情形：无权限 / 尚未归档 / 无数据 |
| 同上 | 表格新增「归档方式」列；`BACKFILL` 行加警示标记与 `backfill_note` 悬浮说明 |
| 同上 | 表格新增「统计截止」列（`as_of`） |

### 5.4 定时任务与系统级统计身份

自然周结束后固化上周数据。**本期不实现**，仅预留 `source_type='SCHEDULED'`。

> ⚠️ **已确认的设计约束（2026-10-28）**：定时归档**不得**依赖登录用户上下文。

**问题**：§3.4 的硬校验 ② 依赖 `users.scopeOf(FeatureCode.MANUFACTURING)`，而定时任务**没有登录用户**，该判据取不到值——既不能因此放行（会产生非全量快照），也不能因此跳过校验。

**要求**

| # | 要求 |
| --- | --- |
| T1 | 为定时任务定义**经授权的系统级统计执行身份**，该身份在 `MANUFACTURING` 上的数据范围恒为 `ALL` |
| T2 | 该身份**仅用于统计与归档**，不授予任何业务写权限，不可用于交互式登录 |
| T3 | 由该身份产生的快照 `source_type='SCHEDULED'`，`generated_by_employee_id` 记录系统身份标识（或显式置空并配合 `visibility_scope` 说明） |
| T4 | **历史快照的读取权限不受影响**：仍由 `manufacturing.snapshot.read` 控制，系统身份不赋予读权限 |

⇒ 即：写侧用系统身份保证口径一致，读侧仍按人授权。两者不得合并。

**实现提示**：`appendVisibility` 当前按 `users.current().employeeId()` 取当前用户（`ProductionTrackingService:556`），无用户时会退化为 `AND 1=0`（查不到任何行）。⇒ 定时任务必须走**独立的全量统计代码路径**（或临时提升 scope），**不能**复用现有 `visible()`，否则会固化出空快照而不报错。

---

## 6. 验收标准

| # | 验收项 | 判定 |
| --- | --- | --- |
| A1 | 连续两次归档同一「全部车间」周期，表行数仍为 1 | `SELECT COUNT(*) ... GROUP BY period_from, period_to, workshop` |
| A2 | 跟单员账号（`PRODUCTION_TRACER`）打开周复盘，看不到历史趋势区块 | 权限隔离 |
| A3 | 跟单员账号直接调用 `/weekly-review/history` 返回 403 | 服务端强制 |
| A4 | 非 ALL 数据范围账号调用归档端点被拒，且不产生任何行 | 硬校验 ② |
| A5 | 归档后修改某单车间预计日期，重查该周期 `totals` 与归档时**完全一致** | `asOf` 定格生效 |
| A6 | `BACKFILL` 快照在 UI 上带明显标识，且不与 `SCHEDULED` 混排展示 | 约束 4.3 |
| A7 | 同一周期已有快照时，重复归档不覆盖（幂等跳过） | `ON DUPLICATE KEY UPDATE id=id` |
| A8 | 已执行的 V421 文件未被修改 | `git log` / 文件校验 |
| A9 | 定时归档**不依赖登录用户上下文**，以系统级全量身份执行，且该身份无业务写权限 | T1 / T2 |
| A10 | 由定时任务产生的快照 `visibility_scope='ALL'`、条数与主管手工归档**一致**（非空、非个人口径） | T3；与 A4 对照 |
| A11 | 系统身份不改变历史快照的读取授权；无 `snapshot.read` 者仍不可见 | T4 |

### 回归要求

- 实时周复盘链路**行为不变**：普通跟单员的 `/weekly-review` 仍按自身数据范围现算、可下钻
- 现有 45 个接口契约测试与 `frontend/e2e/production-tracking-weekly-review.spec.ts` 需同步更新权限 mock

---

## 7. 风险与开放项

| # | 项 | 说明 | 处理 |
| --- | --- | --- | --- |
| R1 | `visibility_scope` 当前只允许 `ALL` | 未来自定义范围会破坏可比性 | 列已预留，先用常量约束在代码层 |
| R2 | 定时任务未实现，且**不能依赖登录用户上下文** | 需为定时任务定义系统级全量统计身份（§5.4 T1-T4）；现有 `visible()` 在无用户时退化为 `AND 1=0`，会静默固化出空快照 | 本期预留字段与身份设计，后续阶段再接 |
| R3 | 快照删除 / 重建语义未定义 | 误归档的纠正路径 | 待定：建议只允许 `snapshot.manage` 删除，且写审计日志 |
| R4 | `snapshot_json` 体积 | 全量结果序列化，长期累积 | 建议限制保留周期数或只存 `totals` + 原因榜 |
| R5 | V419 / V421 文档漂移 | 代码注释与 `生产跟单三大分析抽屉设计梳理.md:45` 均误标 V419 | 随本方案一并更正 |

---

## 8. 与节点模型改造的边界

本文**不涉及**节点统计口径改造。快照固化的是 `analyzeUncut`（交期达成口径）的结果，不含 `nodeAnalyticsView`。

⇒ 若未来节点分析需要进快照，须**先**完成《生产节点流程模型改造设计》并稳定口径，再扩展 `snapshot_json`，否则会把尚在变动的口径固化进历史。
