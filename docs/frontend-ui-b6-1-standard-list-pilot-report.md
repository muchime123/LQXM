# B6-1：标准列表业务页面试点（OperationLogView）

> 目标：选择一个标准列表页面做首批试点，验证统一 UI 体系是否足够成熟、易于复用，并消除该页面已确认的重复视觉与交互实现。
> 本阶段只改一个业务页面，不扩展到全站；不修改公共组件、不改业务行为、不自动提交推送。
> 上游：P0-A / P0-B0 / B1~B5-C 全部 `docs/frontend-ui-*.md`
> 生成日期：2026-10-28

---

## 1. 审计 SHA 与工作区状态

```
HEAD                 1999e3c51e515c283acd1e12dbcd28cd5cf4a4b3
main                 1999e3c51e515c283acd1e12dbcd28cd5cf4a4b3
origin/main          1999e3c51e515c283acd1e12dbcd28cd5cf4a4b3
ahead / behind       0 / 0
git status           78 项（本轮开工时）
```

**并行任务占用情况（本轮只读核对，未触碰、未 stash、未 checkout）**

| 领域 | 文件 | 归属 |
|---|---|---|
| 后端官网（新增控制器/实体/迁移 V424、V425 + 测试） | `backend/**` 约 40 项 | 并行官网任务 |
| 官网前端（companyProfile.ts / navigation.ts / routes.ts / WebsiteCompanyProfileView.vue / 新增两个 View 与 API） | `frontend/src/api/website/*`、`apps/archive/*`、`corporate-website/**` | 并行官网任务 |
| 生产跟踪（TrackingNodeAnalytics.vue + 4 份设计文档） | `frontend/src/components/production/**`、`docs/production-tracking-*` | 并行 tracking 任务 |
| Scratch 脚本 | `tools/_verify_node_*.java` | 并行 tracking 任务 |

**关键结论：`frontend/` 下本轮开工前没有任何一个前端文件被并行任务改动**，因此 `OperationLogView` 不存在与其他任务的修改重叠，试点可安全进行。

---

## 2. 试点页面定位与现状

| 项 | 值 |
|---|---|
| 路径 | `frontend/src/apps/archive/views/system/OperationLogView.vue`（241 行） |
| 路由 | `/system/operation-logs`（`apps/archive/routes.ts`） |
| 权限 | `system.employee.manage`（**路由层声明，页面内没有任何权限分支**） |
| 路由 meta | `layout: 'list'`、`group: 'system'` |
| 数据 | `GET /api/audit-logs`（分页 0 基）、`GET /api/audit-logs/facets` |
| 既有测试 | **无**（`src/apps/` 下此前不存在任何测试文件） |

### 2.1 页面现有功能与交互

| 能力 | 实现 |
|---|---|
| 关键词检索 | `filters.keyword`，`search-mode="submit"` + `query-button`（输入不发请求，点「查询」才发） |
| 筛选 | 实体类型 / 操作动作（来自 facets）经 `SearchToolbar` 的 `fields` + `filter-change` |
| 日期区间 | `el-date-picker`（daterange），通过 `#actions` 插槽置于工具栏右侧，非 `fields` 成员 |
| 清空 | `handleResetFilters()`：清空筛选 + 关键词 + 日期并重新请求 |
| 分页 | `useListPagination()`，`page` 0 基、`size` 20，响应回填 `totalElements/totalPages/number/size` |
| 排序 | **无**（页面未启用排序） |
| 行展开 | `el-table-column type=expand` + `.changes-pre` 展示变更摘要（JSON 格式化） |
| 状态 | `DataState` 五种：loading / empty / filtered-empty / error / stale + refreshing |
| 权限 | 页面内无权限代码，全部由路由 meta 与路由守卫承担 |

### 2.2 修改后应当完全保持不变

接口与请求参数、数据结构、分页 0/1 基协议、权限、筛选与清空语义、行展开与 JSON 摘要格式、五种状态文案、facet 取值来源。

---

## 3. 已复用和未复用公共资产

### 3.1 已正确复用（本轮**保持原状**，未改动）

| 公共资产 | 页面用法 | 判定 |
|---|---|---|
| `ListPageLayout` | 页面骨架，使用 `#eyebrow` / `#toolbar` / `#pagination` 三个插槽 | ✅ 与其余 11 个页面一致 |
| `SearchToolbar` | `v-model:keyword` + `fields` + `search-mode="submit"` + `query-button` + `@search/@filter-change/@clear` + `#actions` | ✅ 契约名与 B4 完全一致 |
| `DataState` | `state` / `skeleton` / `skeleton-variant` / `skeleton-rows` / `refreshing` / 五种文案 / `@retry` | ✅ 未手写任何状态分支 |
| `LqTable` | `density="compact"` + `row-key="id"` + 展开列 | ✅ |
| `LqPagination` | `:pagination` + `@change`（组件 emit 名为 `change`，与用法一致） | ✅ 0/1 基转换在组件内完成 |
| `StatusTag` | 实体类型 / 操作动作标签 | ✅ |
| `useListFilters` / `useListPagination` | 筛选状态与分页 | ✅ |
| 公共 CSS | 页面 scoped 样式仅 14 行，**未重复任何公共布局样式** | ✅ |

**结论：该页面此前已是统一度最高的列表页之一，不存在手抄 `.page-header`、自建分页栏或自绘空态等重复实现。**

### 3.2 应复用但尚未复用（本轮整改）

| # | 缺口 | 证据 | 影响 |
|---|---|---|---|
| **F1** | 页面头部从未渲染 | `ListPageLayout` 的 `showHeader` 默认 `false`（`git show 0f56a72d^` 证实 B5-B 之前即如此，非 B5-B 引入），而页面**未传 `show-header`** → `title` / `description` / `#eyebrow` 全是死参数 | 页面无标题卡；且 `RouteSkeleton` 的 list 骨架是「**头部 + 工具栏 + 表格**」三段，真实内容少一段 → 首次加载骨架→内容结构跳版 |
| **F2** | 「清除筛选」不感知日期区间 | `SearchToolbar` 的 `hasActiveFilters` 只算 `keyword + fields`；页面外层还有 `dateRange`，且 `clearVisible` 未传入 → 仅设置日期时按钮**不出现** | 筛选生效却找不到清除入口（只能靠日期选择器自带的 X） |
| **F3** | 页面 CSS 存在未令牌化值与误导性死回退 | `border: 1px solid var(--color-border-light, #e2e8f0)` —— 令牌存在（`#e5e7eb`），回退永不使用且与令牌值不一致；`border-radius: 8px`、`font-size: 12px`、`line-height: 1.6` 均有等价值令牌 | 违反 B5-B3 视觉规范；死回退会误导后续维护者 |

### 3.3 业务特有布局与合理例外（保留，未动）

- `#actions` 插槽承载日期区间选择器（宽 260px），未并入 `fields` —— 视觉上位于筛选行右侧，与其它筛选控件同排，可接受。
- `.changes-pre` 变更摘要代码块 —— 仅本页使用的业务展示，保留页面级样式。
- `showPagination` / `pagination` **prop** 未被使用（12 个页面全部走 `#pagination` 插槽）—— 属公共组件的备用能力，非页面缺陷，记录为观察项。
- `12px / 14px / 320px`（margin/padding/max-height）无对应令牌，属页面专属布局值，**刻意保留字面量**（不机械替换）。

---

## 4. 精确改动文件及 diff

### 4.1 生产代码（1 个文件，3 处）

`frontend/src/apps/archive/views/system/OperationLogView.vue`

```diff
--- a/frontend/src/apps/archive/views/system/OperationLogView.vue
+++ b/frontend/src/apps/archive/views/system/OperationLogView.vue
@@ -3,6 +3,7 @@
     class="operation-log-page"
     title="操作日志"
     description="关键增删改操作审计记录。点击行首箭头查看变更摘要。"
+    show-header
     :busy="loading"
     content-label="操作日志列表"
   >
@@ -17,6 +18,7 @@
         :fields="toolbarFields"
         :field-values="filters"
         :batch-enabled="false"
+        :clear-visible="hasActiveFilters"
         @search="applyFilters"
         @filter-change="handleToolbarFilterChange"
         @clear="handleResetFilters"
@@ -219,16 +221,20 @@
 <style scoped>
+/* 变更摘要代码块：仅此页面使用的展示组件，保留页面级样式。
+   值与令牌完全等价的属性一律走令牌（B5-B3 视觉规范）；
+   12px / 14px / 320px 无对应令牌，属页面专属布局值，保留字面量。 */
 .changes-pre {
   margin: 4px 12px;
   padding: 12px 14px;
   max-height: 320px;
   overflow: auto;
   background: var(--color-surface-alt);
-  border: 1px solid var(--color-border-light, #e2e8f0);
-  border-radius: 8px;
-  font-size: 12px;
-  line-height: 1.6;
+  /* 原写法带死回退 #e2e8f0（与令牌实际值 #e5e7eb 不一致），令牌已存在，删除回退 */
+  border: 1px solid var(--color-border-light);
+  border-radius: var(--radius-lg);
+  font-size: var(--fs-xs);
+  line-height: var(--lh-loose);
   white-space: pre-wrap;
   word-break: break-all;
 }
```

统计：**10 行新增 / 4 行删除**。零接口变化、零数据结构变化、零权限变化、零业务行为变化。

**等价值校验（取自 `tokens.css`，确保零视觉变化）**：`--radius-lg: 8px` ✔、`--fs-xs: 12px` ✔、`--lh-loose: 1.6` ✔、`--color-border-light: #e5e7eb`（原回退 `#e2e8f0` 因令牌存在而永不生效）✔

### 4.2 新增可复用视觉验收脚本（3 个文件）

| 文件 | 说明 |
|---|---|
| `frontend/e2e/fixtures/operation-log.html` | 夹具页面：仅挂 `<div id="e2e-app">` |
| `frontend/e2e/fixtures/operation-log-harness.ts` | 入口：`createApp(OperationLogView)` + ElementPlus + `@/styles/index.css`。不引入 router/store/登录态 |
| `frontend/e2e/operation-log-standard-list.spec.ts` | 9 个用例：默认态（2 主题 × 2 视口）、加载态、空态、筛选无结果、错误态、搜索单次请求、0 基翻页、日期区间清除、布局几何探针 |

复用仓库既有 Playwright + Edge 环境（`playwright.config.ts`：baseURL 4173、`channel: 'msedge'`），**未引入任何新工具链**。

运行方式（已验证）：

```
npx playwright test e2e/operation-log-standard-list.spec.ts --workers=2
# 改造前/后切换：set B6_SHOT_PHASE=before|after
```

截图输出为 `frontend/playwright-report/b6-1/{before,after}/`。刻意**不用** `frontend/test-results/`——Playwright 每次运行都会清空该目录，改造前基线会被下一轮误删（本轮实际发生过一次，已改为 `playwright-report/`）。

### 4.3 新增源码级契约测试（1 个文件）

`frontend/src/components/common/__tests__/OperationLogPilot.contract.test.ts`（7 条）—— 见 §7.3 说明为何采用源码级形态。

### 4.4 未改动

- 未修改任何公共组件（F1 的正确修法是页面级传 `show-header`，属页面改动范围；公共组件默认值的决策留给 B6-2）
- 未修改 `OfficeAssetView` 或任何其他业务页面
- 未修改后端、数据库、Flyway
- 未触碰任何并行任务文件

---

## 5. 重复代码减少情况

| 项 | 改造前 | 改造后 |
|---|---|---|
| 页面自有 CSS | 14 行 | 17 行（+3 行说明注释），**净功能不变** |
| 重复维护的公共视觉 | 0（此前已全部走公共组件） | 0 |
| 死参数 | 3 个（`title` / `description` / `#eyebrow` 全部不渲染） | 0 |
| 未令牌化值 | 4 处（其中 1 处为误导性死回退） | 0 |
| 手抄 `.page-header` | 无 | 无 |
| 自建分页栏 / 空态 | 无 | 无 |

**说明**：本页此前并非「重复实现」型页面，而是**公共能力已接入但开关未打开**型。因此本轮减少的不是行数量，而是**死参数 + 未令牌化值 + 与骨架不一致的结构缺口**。按 §4 原则「如果页面已经充分统一，允许不修改生产代码」，本轮仍做了 3 处改动，理由是三者均有确定性证据且属于「补齐统一」而非「重写」。

---

## 6. 视觉对比证据

### 6.1 产物

| 阶段 | 目录 | 张数 |
|---|---|---:|
| 改造前（HEAD 原始页面） | `frontend/playwright-report/b6-1/before/` | 11 |
| 改造后 | `frontend/playwright-report/b6-1/after/` | 11 |

覆盖：默认态（浅/深 × 1440/420）、加载态（浅/深）、空状态（浅/深）、筛选无结果（浅）、错误态（浅/深）。目录已被 `.gitignore` 忽略。

**基线重建方式**：Playwright 会清空 `test-results/`，首次基线因此被误删。随后把输出改到 `playwright-report/`，并用**受控往返**（编辑工具回退 3 处改动 → `git diff` 确认为空 → 跑 before → 重新应用 3 处改动 → `git diff --stat` 确认 10 增 4 删）重建基线。全程未使用 `git stash` / `reset` / `checkout`。

### 6.2 差异结论

| 场景 | 改造前 | 改造后 | 判定 |
|---|---|---|---|
| 默认态 · 桌面 · 浅色 | 页面直接从筛选栏开始，**无标题卡** | 顶部出现统一 `.page-surface-header`：eyebrow「系统管理 · AUDIT LOG」+ 标题「操作日志」+ 描述 + 左侧强调条 | ✅ 与 B5-B 统一头部一致 |
| 默认态 · 桌面 · 深色 | 同上 | 头部卡片随深色主题正确切换 | ✅ |
| 默认态 · 420px 窄屏 | 工具栏字段纵向铺满 | 头部转为纵向堆叠、强调条变为顶部横条；工具栏全宽；分页在底部 | ✅ 断点生效 |
| 加载态 | 骨架屏 | 骨架屏（含头部骨架占位一致） | ✅ 无变化 |
| 空状态 / 筛选无结果 / 错误态 | 文案与留白正常 | 完全一致 | ✅ 无回归 |

### 6.3 布局几何探针（真实 Edge，改造后实测）

| 节点 | top→bottom | 关键属性 |
|---|---|---|
| `.page-surface-header` | 0 → 106 | padding `14px 18px`、radius `10px` |
| `.search-toolbar` | — | padding `8px 12px`、radius `8px` |
| `.el-table` | 底部 862 | 无自身外框 |
| `.list-page-layout__pagination-bar` | 863 → 906 | `border-top 1px`、`background` 表面色、右对齐 |

**结论**：表格与分页条**衔接正常**（表格底 862 → 分页条顶 863，无断裂），此前担心的「分页条脱离卡片」在插槽模式下并未发生；12 个页面采用同一模式，视觉一致。

**观察项（未改，记录）**：表格渲染宽 1438px，分页条宽 1440px，存在 2px 差异（来自 `el-table` 自身左右边框）。视觉上不可察，若后续要求像素级对齐，属公共样式议题，不在本阶段处理。

**另一观察项**：`.list-page-layout__list-container`（带边框卡片容器）探针返回 `null` —— 确认 12 个页面走 `#pagination` 插槽时均不进入该容器分支，属一致行为而非差异。

---

## 7. 功能和测试验收结果

### 7.1 e2e（真实 Edge，后端请求全部 `page.route` 拦截，无数据库、无写入）

| 阶段 | 结果 |
|---|---|
| **改造前**（HEAD 版本） | **7 passed / 2 failed** —— 两个失败**正是缺陷证据**：「仅设置日期区间时『清除筛选』可见」失败（F2）、「布局几何探针」失败（F1：`.page-surface-header` 不存在） |
| **改造后** | **9 passed / 0 failed** |

覆盖断言：头部三要素渲染、五种状态、搜索只发一次请求（submit 模式不发防抖重复请求）、0 基翻页（`page=0` → UI 第 2 页 → `page=1`）、关键词进入请求、日期区间清除、几何探针。

> 注：8 worker 并行下偶发 `page.goto` 超时（Vite dev 即时编译 + 8 个 Edge 实例争抢资源），加 `--workers=2` 后稳定 9/9。已在报告与脚本注释中记录。

### 7.2 新增源码级契约（7 条，全通过）

`src/components/common/__tests__/OperationLogPilot.contract.test.ts`：复用 `ListPageLayout` 且开启 `show-header`、头部不手抄 `.page-header`、复用四大公共组件、`clear-visible` 绑定 `hasActiveFilters`、分页走 `useListPagination` 0 基协议、页面样式无十六进制色值/px 圆角、`.changes-pre` 保留为页面专属。

### 7.3 为什么页面测试是源码级而不是挂载式（如实记录）

本轮先写了挂载 `OperationLogView` 的 vitest 集成测试（6 条，含 Element Plus 插件），单文件运行 6/6 通过；但**加入后全量 `npx vitest run` 会在收集期整体崩溃**：

```
Test Files  78 failed (78)
Tests  no tests
Error: Vitest failed to find the runner / failed to find the current suite
```

连纯工具函数用例（`utils/ui/__tests__/textUtils.test.ts`）也一起失败。判别实验：

| 运行方式 | 结果 |
|---|---|
| 全量（78 文件，含我的测试） | 78 全崩 |
| `--exclude '**/OperationLogView.*'`（77 文件） | **701 passed / 2 failed**（仅 tracking 两条已知） |
| `--exclude '**/textUtils.test.ts'`（77 文件，**含**我的测试） | **703 passed / 2 failed**（我的用例全绿） |
| 换成 forks 池 | 同样 78 全崩 |

**结论：崩溃由「测试文件总数达到 78」触发的环境资源上限导致，与测试文件内容/位置无关**（交换被排除的文件，结论一致）。出于「不得交付会破坏门禁的测试文件」的原则，挂载式测试已删除，页面集成验证改由 e2e 承担（真实浏览器，覆盖度更高），并保留源码级契约防止修复被回退。

### 7.4 全量门禁

| 命令 | 结果 |
|---|---|
| `npx vitest run`（77 文件，排除 1 个无关用例以绕开环境上限） | **703 passed / 2 failed** —— 2 条均为 tracking 既有失败 |
| `npx vitest run src/apps` → 改为 `src/components/common` | 契约 7/7 通过 |
| `npm run typecheck` | **0 错误** |
| `npm run lint` | **0 error** |
| `npx vite build --outDir %TEMP%\b6build --emptyOutDir` | **✓ 3067 modules transformed；✓ built in 32.55s**；532 个产物（`assets` 168 + `index.html` + map + 2 logo） |

**未被绕过的验证**：typecheck / lint / 完整构建 / e2e 全部在**含本次改动**的完整代码上执行通过；全量 vitest 也验证过「含本次测试文件」的 77 文件组合（703 通过）。唯一未能完成的是 78 文件的**完整**集合，原因如上。

---

## 8. 既有失败与新增失败区分

| 失败 | 数量 | 归属 | 本轮处置 |
|---|---:|---|---|
| `TrackingAnalyticsDrilldownCells.test.ts` · 规格拼接 | 2 | **既有**，追踪：测试由 `5bc9a80a` 引入、被测组件由 `4c6d5432`（tracking 提交）修改而测试未同步；组件现只渲染 `specification` 首段 | **未修改**。与本轮无任何依赖（本轮未触碰 `components/production/**`） |
| 本轮新增失败 | **0** | — | — |

**明确不粉饰**：本轮门禁口径是 **703 passed / 2 failed**，不是「全绿」。两条失败与本轮改动零关联，且回退本轮改动后依然存在。

---

## 9. 适合推广的标准做法

从本试点抽象出的**标准列表页配方**（可直接作为 B6-2 检查表）：

1. **骨架**：`<ListPageLayout show-header :busy="loading" content-label="…">`，`#eyebrow` / `#toolbar` / `#pagination` 三插槽。
2. **必传 `show-header`**：否则 `title` / `description` / `#eyebrow` 全部静默失效（本轮最大发现）。
3. **筛选容器**：一律 `<SearchToolbar>`，字段进 `fields`，页面私有的宽控件（日期区间）放 `#actions`。
4. **若页面有 `fields` 之外的筛选维度**：必须把页面的 `hasActiveFilters` 通过 `:clear-visible` 回传给工具栏，否则清除按钮会漏显。
5. **状态**：一律 `DataState` 五态，不手写空态/错误态；空态与筛选空态必须区分文案。
6. **分页**：`useListPagination()` + `<LqPagination :pagination @change>`，0/1 基转换只在组件内做，页面不写 `page±1`。
7. **表格**：`LqTable` + `density` + `row-key`；业务列用插槽，不覆盖 `el-table` 内部类。
8. **页面 CSS**：只保留业务专属样式；与令牌等值的属性一律走令牌；无令牌的布局值保留字面量并写明理由。
9. **结构一致性**：页面真实结构必须与 `RouteSkeleton` 的 `layout: 'list'` 骨架（头部 + 工具栏 + 表格）一致，否则首屏跳版。

**验收配方（可复用）**：`e2e/fixtures/` 加一个只挂真实页面的夹具（无 router/store）+ `page.route` 拦截后端 + 2 主题 × 2 视口 × 5 状态截图 + 几何探针。本轮脚本可直接复制改页面路径。

---

## 10. B6-2 前置建议

1. **【需决策】`ListPageLayout` 的 `showHeader` 默认值**：12 个页面**全部**传了 `title`/`description` 却**无一**传 `show-header`（其中 4 个还传了 `#eyebrow`）。两个方向二选一，需产品/设计确认后统一执行：
   - (a) 把默认值改为 `true`（12 页一次性获得头部，改动面大但语义正确）；
   - (b) 给其余 11 页逐个补 `show-header`（改动可控，但依赖人工不遗漏）。
   **本轮按边界只改试点页，未动公共组件默认值。**
2. **`pagination` prop 无生产使用方**：`ListPageLayout` 内置的 `pagination` prop + `showPagination` 目前 0 个页面使用（12 页全走 `#pagination` 插槽）。建议评估：要么删除该 prop，要么在文档中明确它是「分页在卡片内」的备用形态。
3. **`RouteSkeleton` 与页面结构一致性巡检**：本轮发现骨架假设「有头部」而真实页面没有。建议把「骨架结构 vs 真实结构」纳入后续每页试点检查项。
4. **下一批试点建议**：优先同为 `layout: 'list'`、且同样传了 `title`/`description` 的页面，一次可同时验证 `show-header` 补齐（建议选与 OperationLogView 结构最接近的，便于复用本轮 e2e 夹具）。
5. **环境问题需单独跟进**：全量 vitest 在 78 个测试文件时于收集期整体崩溃（`Vitest failed to find the runner`），本轮已证明与改动无关，但在机器负载较低时应复跑一次 `npx vitest run` 全量（预期 709 条：701 + 本轮 7 条契约 + 1 条其他）以确认。
6. **视觉证据留存**：`frontend/playwright-report/b6-1/` 已被 gitignore，若需长期留档请另择目录或 `git add -f` 单独提交（建议只提交关键几张，不必全量入库）。

---

## 11. 变更边界核对

- 只改了一个业务页面（OperationLogView）—— ✅
- 未修改任何公共组件生产实现 —— ✅
- 未修改 `OfficeAssetView` 或其他业务页面 —— ✅
- 未修改生产跟单、财务流水、官网等并行模块 —— ✅
- 未批量迁移其他 `el-table` / `el-dialog` / `el-pagination` —— ✅
- 未重构 B2~B4 已稳定逻辑 —— ✅
- 未修改后端、数据库、Flyway —— ✅
- 未改接口、数据结构、权限、业务行为、分页 0/1 基协议 —— ✅
- 未执行 `git add` / `commit` / `push` / `clean` / `reset` / `stash` —— ✅
- 未清理、未覆盖任何并行任务文件 —— ✅
- 未删除断言、未扩大 Mock 掩盖缺陷 —— ✅

**完成 B6-1 后停止，未开始 B6-2，未提交推送。**

