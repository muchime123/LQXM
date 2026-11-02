# B5-C：阶段成果提交边界审计

> 目标：为 B6 业务页面试点建立清晰、可追溯、可回滚的代码基准。
> 本阶段不开发新功能、不修改业务页面、不重构公共组件；只审计与建立提交计划，**不执行 commit / push**。
> 上游：P0-A / P0-B0 / B1 / B2 / B3 / B4 / B5-A / B5-B 全部 `docs/frontend-ui-*.md`
> 生成日期：2026-10-28

---

## 0. 最重要的结论先行

**审计入口状态与 B5-B 结束时已完全不同：统一 UI 项目（B1~B5-B）的 35 个文件已全部提交并推送到 `origin/main`。**

| 项 | B5-B 结束时 | B5-C 审计时 |
|---|---|---|
| HEAD | `42bc081c271124620254a7fe79fff55d00a7e904` | `1999e3c51e515c283acd1e12dbcd28cd5cf4a4b3` |
| main vs origin/main | 未比较 | **完全相同，ahead 0 / behind 0** |
| 工作区变更 | 204+ 项 | **仅 2 项未跟踪**（均非 UI 文件） |
| UI 文件待提交 | 全部待提交 | **0 个** |

因此本轮的「分批提交计划」**不是**「准备暂存哪些文件」，而是：

1. 核实已进入版本库的内容是否完整、干净、无越界；
2. 指出一处**已发生的提交范围不规范**（见 §4.1）并记录纠正建议；
3. 给出**精确回滚路径**与验证方法，作为 B6 的可回退基准；
4. 记录当前测试门禁基线（含 2 条非 UI 归属的失败）。

**本轮未执行任何 `git add` / `commit` / `push` / `reset` / `clean`。**

---

## 1. 基准 SHA 与工作区状态

### 1.1 版本库指针

```
HEAD                = 1999e3c51e515c283acd1e12dbcd28cd5cf4a4b3
main                = 1999e3c51e515c283acd1e12dbcd28cd5cf4a4b3
origin/main         = 1999e3c51e515c283acd1e12dbcd28cd5cf4a4b3
git rev-list --left-right --count origin/main...main = 0    0
git log origin/main..HEAD                            = （空）
```

**三条指针完全一致，ahead 0 / behind 0** —— 本地没有任何未推送提交，也不落后远端。

### 1.2 工作区状态

```
git status --porcelain
?? tools/_verify_node_analytics.java        (3,679 B)
?? tools/_verify_node_orders.java           (4,410 B)
```

- 已跟踪文件：**零修改、零暂存**。
- 仅剩 2 个未跟踪文件，位于 `tools/`（该目录是各任务共用的脚本/Scratch 目录，已入库 `_probe_*.java`、`_exec_*.java` 等同类文件）。
- **归属判定：不属于统一 UI 项目**。文件名对应 `production tracking` 的 node analytics / orders 参数校验，与 B1~B5-B 的公共组件/样式/契约测试无交集；全库无引用。
- **处置：不纳入 UI 提交范围。** 是否入库由该并行任务决定。注意这两个文件**未被**现有 `.gitignore`（`2fa78d13 chore(git): ignore JDK archives, sql dumps and scratch classes`）覆盖，因此任何 `git add .` / `git add -A` 都会误纳 —— 这也是本报告要求「精确暂存、禁止 `git add .`」的直接理由。

### 1.3 UI 成果提交现状（跨 2 个提交）

| 提交 | 主题 | 时间 | UI 文件数 |
|---|---|---|---:|
| `0f56a72d` | `feat(ui): unify common component layer and public style tokens` | 10-28 14:35 | **34** |
| `1053d3bc` | `docs(website,tracking): record product cutover checks and tracking design notes` | 10-28 14:36 | **1**（仅 `docs/frontend-ui-b5b-visual-governance-report.md`，223 行） |
| | | **合计** | **35** |

---

## 2. 文件归属清单（四分类）

### 分类 A — 统一 UI 项目独有改动（35 个路径，全部已入库）

见 §3 完整清单。**判定依据**：全部落在 `frontend/src/components/common/**`、`frontend/src/styles/**`、`frontend/src/composables/list/__tests__/**`、`docs/frontend-ui-*.md` 四类路径内，且无一是任何业务模块的源文件。

### 分类 B — 其他并行开发任务改动（7 个提交，全部已入库）

| 提交 | 主题 | 触及领域 |
|---|---|---|
| `1999e3c5` | `docs(website): update content decoupling plan` | 官网内容解耦 |
| `2fa78d13` | `chore(git): ignore JDK archives, sql dumps and scratch classes` | .gitignore |
| `abd11f68` | `feat(website): add company profile parity check and admin page` | 官网企业简介 |
| `82b8c4d8` | `feat(website): build native product and company profile modules` | 官网原生产品模块 |
| `4c6d5432` | `feat(tracking): move stage definitions to catalog and analytics snapshots` | 生产跟踪 |
| `47d3a775` | `docs(tracking): add production tracking design notes` | 生产跟踪文档 |
| `1053d3bc` | `docs(website,tracking): record product cutover checks and tracking design notes` | 官网 + 跟踪（**含 1 个 UI 文件，见 §4.1**） |

另有历史提交 `5bc9a80a`（生产跟单下钻组件 / 可编辑 HTML 导出）位于 `0f56a72d` 之前，与 §5 的失败归属相关。

### 分类 C — 多个任务共同修改的文件

**无。**

证据：对 UI 提交的 35 个路径逐一做范围排除检索——

```
git log 0f56a72d..HEAD --name-only --pretty=format:
   → 命中的 UI 路径数量 = 0
```

即在 `0f56a72d` 之后的 7 个提交里，**没有任何一个提交改动过**任意一个 UI 文件（含 `frontend/src/components/common/`、`frontend/src/styles/`、`useListPagination`、`uiLayers.ts`、`index.css`、`tokens.css`、`docs/frontend-ui-*`）。因此：

- 不存在混合 hunk 的 UI 文件；
- 不存在「我的改动被别人覆盖」的情况；
- 也不存在「别人的改动被我卷进 UI 提交」的情况。

### 分类 D — 无法确认归属的改动

| 文件 | 状态 | 判定 |
|---|---|---|
| `tools/_verify_node_analytics.java` | 未跟踪 | parallel tracking 任务的 Scratch 校验脚本；全库无引用；**不入库** |
| `tools/_verify_node_orders.java` | 未跟踪 | 同上 |

其余全部文件归属明确。

---

## 3. 统一 UI 项目完整变更清单

### 3.1 `0f56a72d`（34 文件，+6883 / −219）

**A 组 · 规范与实施文档（11）**

| 文件 | 行数 | 对应轮次 |
|---|---:|---|
| `docs/frontend-ui-p0a-audit.md` | 679 | P0-A |
| `docs/frontend-ui-p0b0-remediation-plan.md` | 577 | P0-B0 |
| `docs/frontend-ui-b1-test-baseline-report.md` | 339 | B1 |
| `docs/frontend-ui-b2-pagination-decoupling-report.md` | 204 | B2 |
| `docs/frontend-ui-b3-table-implementation-report.md` | 264 | B3 |
| `docs/frontend-ui-b4-interaction-contract-report.md` | 282 | B4 |
| `docs/frontend-ui-b5a-drawer-implementation-report.md` | 350 | B5-A |
| `docs/frontend-ui-visual-spec.md` | 154 | B5-B3 |
| `docs/frontend-ui-lqtable-usage.md` | 206 | B3 使用规范 |
| `docs/frontend-ui-lqdrawer-usage.md` | 286 | B5-A 使用规范 |
| `docs/frontend-ui-dialog-search-usage.md` | 179 | B4 使用规范 |

**B 组 · 公共组件实现（6）**

| 文件 | 行数 | 说明 |
|---|---:|---|
| `frontend/src/components/common/LqDrawer.vue` | 377 | B5-A 新增 |
| `frontend/src/components/common/LqTable.vue` | 73 ± | B3 泛型/attrs |
| `frontend/src/components/common/LqPagination.vue` | 86 ± | B2 解耦 |
| `frontend/src/components/common/SearchToolbar.vue` | 7 + | B4 注释/契约 |
| `frontend/src/components/common/ListPageLayout.vue` | 97 ± | B5-B 头部收敛（−86 外观 + 5 模板） |
| `frontend/src/components/common/PageHeader.vue` | 101 ± | B5-B 头部收敛（−92 scoped 外观） |

**C 组 · 契约测试与类型（11）**

| 文件 | 行数 | 用例数 |
|---|---:|---:|
| `frontend/src/components/common/__tests__/LqDrawer.contract.test.ts` | 598 | 48 |
| `frontend/src/components/common/__tests__/LqDialog.close.contract.test.ts` | 351 | 26 |
| `frontend/src/components/common/__tests__/LqDialog.teleport.contract.test.ts` | 78 | 3 |
| `frontend/src/components/common/__tests__/LqTable.attrs.test.ts` | 405 | 28 |
| `frontend/src/components/common/__tests__/LqTable.generic.types.ts` | 105 | 类型夹具 |
| `frontend/src/components/common/__tests__/LqTable.test.ts` | 8 ± | 6 |
| `frontend/src/components/common/__tests__/LqPagination.contract.test.ts` | 388 | 28 |
| `frontend/src/components/common/__tests__/SearchToolbar.debounce.test.ts` | 219 | 13 |
| `frontend/src/components/common/__tests__/PublicStyleTokens.contract.test.ts` | 149 | 18 |
| `frontend/src/components/common/__tests__/ListPageLayout.test.ts` | 5 ± | 4（B5-B 类名同步） |
| `frontend/src/composables/list/__tests__/useListPagination.test.ts` | 95 | 32 |

**D 组 · 样式与令牌（6）**

| 文件 | 行数 | 说明 |
|---|---:|---|
| `frontend/src/styles/modules/drawer-layout.css` | 294 | B5-A 新增，B5-B 令牌化 |
| `frontend/src/styles/modules/list-page-surfaces.css` | 98 + | B5-B `.page-surface-header` |
| `frontend/src/styles/modules/dialog-layout.css` | 27 ± | B5-B 17 处令牌化 |
| `frontend/src/styles/tokens.css` | 19 + | `--drawer-width-*` ×4、`--shadow-dialog` +注释 |
| `frontend/src/styles/themes/dark.css` | 1 + | `--shadow-dialog` 覆盖 |
| `frontend/src/styles/index.css` | 1 + | `@import './modules/drawer-layout.css'` |

### 3.2 `1053d3bc` 中的 UI 文件（1）

| 文件 | 行数 | 说明 |
|---|---:|---|
| `docs/frontend-ui-b5b-visual-governance-report.md` | 223 | B5-B 验收报告（**落错提交，见 §4.1**） |

### 3.3 完整性核对：无遗漏、无悬空依赖

| 检查项 | 方法 | 结果 |
|---|---|---|
| 报告声称的交付是否都在库中 | `git ls-files` 过滤 `frontend-ui-` | 12 个文档全部入库 ✅ |
| 组件是否入库 | `git ls-files` 过滤 | `LqDrawer.vue`、`PageHeader.vue`、`ListPageLayout.vue` 等全部入库 ✅ |
| 样式是否入库 | `git ls-files` 过滤 | `drawer-layout.css`、`list-page-surfaces.css` 全部入库 ✅ |
| 契约测试是否入库 | `git ls-files` 过滤 | `PublicStyleTokens.contract.test.ts` 等全部入库 ✅ |
| **依赖是否悬空** | `frontend/src/composables/list/useListPagination.ts`、`frontend/src/constants/uiLayers.ts` 均为建库初始提交即存在的前置文件 | 无需另补 ✅ |
| 样式入口是否接通 | `git show HEAD:frontend/src/styles/index.css` 含 `@import './modules/drawer-layout.css'` | ✅ |
| B5-B 治理成果是否真实落地 | 在 HEAD 逐个核对：tokens 含 `--shadow-dialog` / `--shadow-drawer` / `--drawer-width-sm`；dark.css 含两者覆盖；`drawer-layout.css` 含 `box-shadow: var(--shadow-drawer)` / `--radius-lg` / `--opacity-disabled` / `--transition-fast`；`list-page-surfaces.css` 含完整 `.page-surface-header` 家族 | ✅ 全部与 B5-B 报告一致 |
| UI 文件是否被后续提交再改 | `git log 0f56a72d..HEAD` 范围检索 | 0 命中 ✅ |

**未被纳入版本控制的资产（已知缺口）**：B5-B 的 **48 张真实浏览器截图**存于 `frontend/test-results/b5b-visual/`（被 `.gitignore` 忽略），且验收脚手架 `frontend/visual-harness/` 已按要求删除。后果：**视觉证据当前不可追溯** —— 建议另择一处归档保存，并在需要时补 `git add -f`（见 §7.3）。

---

## 4. 潜在提交冲突与风险

### 4.1 【已发生】一处提交范围不规范（需知悉，非阻塞）

`docs/frontend-ui-b5b-visual-governance-report.md` 被提交进了 **`1053d3bc docs(website,tracking): record product cutover checks and tracking design notes`**（该提交共 27 文件、+7955，含大量官网 JSON 与 `scripts/*.ps1`），而不是 UI 提交 `0f56a72d`。

- **影响**：UI 项目的文件集合被拆在两个提交里，且第二个提交的主题与实际内容不符；仅凭提交主题无法追溯到 B5-B 报告的存在。
- **严重程度**：低。文件内容完整、正确，未被改动（后续无提交触及它）。
- **纠正建议（任选其一，均需人工确认后再执行）**：
  1. **不纠正**（推荐）：仅在本报告与 commit message 中记录事实；历史改写代价大于收益。
  2. 若必须纠正，用 `--no-edit` 的 `git revert` **重做**而非 `rebase`：
     先从 `1053d3bc` 移除该文档再以 UI 提交重提 —— 这会改写已推送历史，需要与全部并行任务方协商，**本轮不执行**。

### 4.2 混合改动 hunk 风险

**无。** UI 提交的 34 个文件里，对共享风险最高的三个组件（`LqTable.vue`、`LqPagination.vue`、`SearchToolbar.vue`）做了新增行全文扫描，检索 `Tracking / Cashflow / Settlement / Website / Production / 模具 / 跟单 / 财务` 等业务词：**0 命中**。三者被后续提交再次改动的可能性也已排除（§2 分类 C）。

### 4.3 回滚可行性

`0f56a72d` 是线性历史中的普通提交，且其后的 7 个提交**无一触及**它的 34 个文件 → **回滚无文本冲突**：

```
# 仅演示，本轮未执行
git revert --no-commit 0f56a72d     # 回滚 UI 组件/样式/测试主体
git revert --no-commit 1053d3bc     # 仅用于回滚其中的 b5b 文档（会同时回滚 26 个非 UI 文件，不可取）
```

建议：若需整体回退 UI 成果，只 `revert 0f56a72d`；**不要** `revert 1053d3bc`（其中包含大量官网/跟踪资产）。必要时单独把 `docs/frontend-ui-b5b-visual-governance-report.md` 删除或移出即可。

### 4.4 暂存纪律风险

工作区存在 2 个未跟踪 Scratch 文件且未被 `.gitignore` 覆盖 → **任何宽口径的 `git add .` / `git add -A` / `git add -u` 都可能误纳**。后续所有 UI 相关提交必须逐路径精确暂存（§6 给出命令模板）。

---

## 5. 两条业务测试失败的归属与待处理状态

**结论：与统一 UI 项目无关，未代修，处于「等待 tracking 任务方处理」状态。本轮仅复核证据，未修改任何实现或测试。**

### 5.1 当前状态（本轮重跑确认）

```
npx vitest run
 Test Files  1 failed | 76 passed (77)
      Tests  2 failed | 701 passed (703)
   Duration  11.79s
```

失败全部集中在：

`src/components/production/__tests__/TrackingAnalyticsDrilldownCells.test.ts`（7 tests | 2 failed）

| # | 用例 | Expected | Received |
|---|---|---|---|
| 1 | `TrackingAnalyticsSpecCell > 规格与模具出数连成一行，用「 / 」分隔` | `120 / 圆形 / PP / 一出四` | `120 / 一出四` |
| 2 | `TrackingAnalyticsSpecCell > 只有规格时不留下孤零零的分隔符` | `95 / 方形 / PP` | `95` |

两条用例的共同现象：组件现在只渲染 `specification` 的**首个分段**（`120 / 圆形 / PP` → `120`；`95 / 方形 / PP` → `95`），后续的盒型/材质分段被丢弃。

### 5.2 归属证据链（本次已由提交历史永久化）

B5-B 时我只能依据「工作区 `M` 标记 + 开工清单对比」推断归属；现在提交已落地，**证据变为不可变的提交历史**：

| 对象 | 引入 / 最后修改提交 | 归属 |
|---|---|---|
| 测试文件 `TrackingAnalyticsDrilldownCells.test.ts` | `5bc9a80a` `feat(tracking): 模具跟进筛选、分析下钻单元格组件、可编辑 HTML 导出与产品历史订单接口` | **tracking 任务**（同一提交同时引入该测试与其被测组件） |
| 被测组件 `TrackingAnalyticsSpecCell.vue` | `5bc9a80a` 引入 → `4c6d5432` `feat(tracking): move stage definitions to catalog and analytics snapshots` **最后修改** | **tracking 任务** |

推理闭环：

1. 测试与组件**由同一个 tracking 提交引入**；
2. 之后**改组件的还是 tracking 提交**（`4c6d5432`），测试未随之更新；
3. 统一 UI 的 35 个文件**没有一个**属于 `components/production/**`；
4. `0f56a72d` 之后的 7 个提交**没有一个**触及过 `components/common` / `styles` / `composables/list`（§2 分类 C 已验证）；
5. 失败断言的内容是**规格文本拼接格式**，与样式令牌、公共组件毫无依赖。

→ **无论是否回退 UI 成果，这两条失败都照样存在。**

### 5.3 待处理事项（交回 tracking 任务方）

二选一，**本轮均不执行**：

- **方案 1（推荐）**：由 `4c6d5432` 的作者确认 `specification` 字段的结构化改造是否应保留完整拼接展示；若是，修正 `TrackingAnalyticsSpecCell.vue`。
- **方案 2**：若丢弃后段是**有意的新需求**（只显示数值），则更新 `TrackingAnalyticsDrilldownCells.test.ts` 第 19 行与第 26 行的期望值。

在解决之前，全量门禁的绿线口径应表述为：**703 条中 701 通过，2 条已知非 UI 归属失败**。B6 试点开始时应以该口径为准，不要为了「凑绿」改动这两个文件。

---

## 6. 分批提交建议与精确暂存范围

> **本轮不执行任何提交动作。** 以下既是现状记录，也是后续 UI 修正时的操作模板。

### 6.1 现状：无待提交内容

| 范围 | 待暂存数 | 说明 |
|---|---:|---|
| UI 组件 / 样式 | 0 | 已在 `0f56a72d` |
| UI 契约测试 / 类型 | 0 | 已在 `0f56a72d` |
| UI 规范与实施文档 | 0 | 11 个在 `0f56a72d`，1 个在 `1053d3bc` |
| 未跟踪 Scratch | 2 | **明确排除**，见 §2 分类 D |

**因此本阶段的「提交计划」结论是：保持现状，不再追加提交。** `HEAD == main == origin/main` 本身就是最干净的 B6 起点。

### 6.2 后续如需修正 UI 文件：精确暂存命令模板

**严禁** `git add .` / `git add -A` / `git add -u` / `git add frontend`（最后一项会把业务的在手改动一并带入）。

按改动类型选择下列逐路径 `--` 显式暂存（每条均为白名单式，范围外文件不会被带入）：

```bash
# 1) 仅公共组件
git add -- frontend/src/components/common/LqDrawer.vue \
           frontend/src/components/common/LqTable.vue \
           frontend/src/components/common/PageHeader.vue

# 2) 仅公共样式与令牌
git add -- frontend/src/styles/tokens.css \
           frontend/src/styles/themes/dark.css \
           frontend/src/styles/modules/drawer-layout.css \
           frontend/src/styles/modules/list-page-surfaces.css

# 3) 仅契约测试
git add -- frontend/src/components/common/__tests__/LqDrawer.contract.test.ts \
           frontend/src/components/common/__tests__/PublicStyleTokens.contract.test.ts

# 4) 仅 UI 文档
git add -- docs/frontend-ui-visual-spec.md

# 提交前必做：逐 file diff 复核，确认无业务 hunk 混入
git diff --cached --stat
git diff --cached
```

**混合 hunk 处理原则**：若某个 UI 文件同时含有 UI 改动与其他任务的改动，**不强行用 `-p` 拆分**（风险高于收益），应报告并等待归属方确认后再提交。当前无此情况。

### 6.3 建议的批次划分（供后续参考）

历史上 B1~B5-B 若需分批次提交，依赖关系自洽的最小拆分应为：

| 批次 | 内容 | 依赖 |
|---|---|---|
| 1 | 文档（P0-A、P0-B0、B1~B5 报告 + 三份使用规范 + 视觉规范） | 无（纯文档） |
| 2 | 令牌与样式入口（`tokens.css`、`dark.css`、`index.css`、`dialog-layout.css`、`list-page-surfaces.css`） | 无 |
| 3 | `drawer-layout.css` + `LqDrawer.vue` + 其 48 条契约 + 使用规范 | 批次 2 |
| 4 | `ListPageLayout.vue` + `PageHeader.vue` + `PublicStyleTokens.contract.test.ts` + `ListPageLayout.test.ts` | 批次 2 |
| 5 | `LqTable` / `LqPagination` / `SearchToolbar` 及其契约 | 无 |

当前 5 个批次已合并在 `0f56a72d` 中一次性落地（+1 文档在 `1053d3bc`），依赖自洽，无拆分必要。

### 6.4 禁止操作清单（本阶段已遵守）

- ❌ `git add .` / `git add -A` / `git add -u`
- ❌ `git reset --hard`
- ❌ `git clean`（含 `-fd`）
- ❌ `git checkout -- <其他任务文件>` / `git restore` 覆盖并行文件
- ❌ `git commit` / `git push`
- ❌ 修改 `TrackingAnalyticsSpecCell.vue` 或其测试

---

## 7. B6 试点开始前的验收条件

### 7.1 公共组件接口基准（B6 起可直接依赖）

| 组件 | 关键 props | 插槽 | 事件 |
|---|---|---|---|
| `LqDrawer` | `modelValue`、`size`(sm/md/lg/xl)、`width`、`mode`(detail/form/workspace)、`direction`(rtl/ltr/ttb/btt)、`dirty`、`loading`、`beforeClose`、`showHeader`、`zIndex`、`title`/`subtitle`/`eyebrow` | `default`、`title`、`actions`、`footer`、`eyebrow`、`description` | `update:modelValue`、`close`、`cancel`、`confirm` |
| `LqDialog` | 同上另加 `fullscreen`、`align-center` | 同上 | 同上 |
| `LqTable` | `data`、`rowKey`、`loading`、`stripe`、`border`、`density`、`emptyText` | 默认插槽承载 `el-table-column` | attrs 透传 |
| `LqPagination` | `pagination`（来自 `useListPagination()`） | — | 变更回调 |
| `SearchToolbar` | `keyword`、`modelsCount`、`searchPlaceholder`、`searchMode`、`batchEnabled`、`batchMode`、`selectedCount`、`isAllSelected` | `actions` | `update:keyword`、`toggle-batch-mode`、`toggle-select-all`、`batch-edit`、`batch-remove` |
| `ListPageLayout` | `showHeader`、`title`、`description` | `eyebrow`/`title`/`description`/`actions`/`summary`/`notice`/`toolbar`/`filterChips`/`list-header`/`list-actions`/`list-scroll`/`default`/`pagination` | — |
| `PageHeader` | `title`、`description` | `eyebrow`/`description`/`actions` | — |
| 共享外观类 | `.page-surface-header`（+ `__title` / `__actions`）——**页面头部唯一入口** | — | — |
| 辅助 | `useListPagination()`、`UI_LAYER`（`@/constants/uiLayers`） | — | — |

### 7.2 测试基准

| 范围 | 命令 | 结果 |
|---|---|---|
| **公共组件层（回归首选）** | `npx vitest run src/components/common src/composables/list src/constants` | **23 文件 / 294 passed / 0 failed** |
| 全量 | `npx vitest run` | **77 文件 / 703 tests：701 passed、2 failed**（2 条见 §5，非 UI 归属） |
| `npm run typecheck` | `vue-tsc --noEmit --skipLibCheck` | **0 错误** |
| `npm run lint` | `eslint src/ --quiet` | **0 error** |

公共组件层明细（本轮实测）：

`LqDrawer.contract` 48 · `LqDialog.test` 36 · `LqDialog.close.contract` 26 · `LqPagination.contract` 28 · `LqTable.attrs` 28 · `LqTable.test` 6 · `useListPagination` 32 · `SearchToolbar.debounce` 13 · `SearchToolbar.test` 7 · `PublicStyleTokens.contract` 18 · `ListPageLayout.test` 4 · `LqDialog.teleport.contract` 3 · `LqPagination.test` 4 · `LqSkeleton` 6 · `LqConfirmDialog` 5 · `DataState` 5 · `EmptyState` 2 · `LoadingState` 2 · `StatusTag` 2 · `FilePreviewBody` 11 · `SourceTrace` 2 · `LqPreview` 4 · `fileAssetMessages` 2

### 7.3 B6 启动前必须满足的条件

1. **代码基准锁定的三条入口命令**（任选其一核对，应得到本文 §1 的结果）：
   ```
   git rev-parse HEAD main origin/main            # 三者相同 → 1999e3c5...
   git rev-list --left-right --count origin/main...main   # 0  0
   git status --porcelain                          # 仅 2 个 tools/ Scratch
   ```
2. **公共层绿线**：§7.2 第一行命令必须 294/294 通过。
3. **门禁绿线口径**：全量 701/703，允许 §5 的 2 条已知失败；**不得**为凑绿修改生产跟单文件。
4. **视觉取证（建议补齐）**：决定是否把 `frontend/test-results/b5b-visual/` 的 48 张截图另择目录归档；若需要入库则用 `git add -f` 显式暂存并单独成提交，避免动用宽口径 add。
5. **试点页面选择**：B5-B §10 建议优先 `WebsiteMediaView` / `WebsiteProductsView`（头部缺样式的真实缺陷）——须等待并行 website/tracking 任务提交完成后再动，避免与在途改动打架。
6. **每次迁移一个页面必须**：跑 §7.2 公共层 + 全量，`typecheck` + `lint`，并对该页面做 ≥1 次深浅色双主题 + 420px 窄屏的人工或截图走查。
7. **提交纪律**：按 §6.2 逐路径精确暂存，禁止宽口径 add；如遇混合 hunk，按 §6.2 原则**不强行拆分**，先报告。

---

## 8. 本轮边界核对

- 未开发新功能、未修改业务页面、未重构公共组件 —— ✅（本轮只读，唯一写入是新报告文件）
- 未修改 `TrackingAnalyticsSpecCell.vue` 及其测试 —— ✅
- 未执行 `git add` / `commit` / `push` / `reset --hard` / `clean` / `checkout --` —— ✅
- 未清理、未覆盖其他并行任务文件（含 2 个未跟踪 Scratch）—— ✅
- 未修改后端、数据库、Flyway —— ✅

**完成 B5-C 后停止，等待提交审批与 B6 实施指令。**


