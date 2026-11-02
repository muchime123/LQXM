# LQXM 前端统一 UI 体系 B7-B1b：搜索、筛选与分页联动修复

- 阶段：B7-B1b（P0 功能缺陷，紧接 B7-B1a）
- 执行日期：2026-10-29
- 修改文件（业务）：`WebsiteInquiryView.vue`、`CertificateView.vue`、`CustomerView.vue`
- 新增测试：`frontend/e2e/b7-b1b-search-pagination.spec.ts`（6 个用例）、
  `frontend/src/components/common/__tests__/SearchPaginationLinkage.contract.test.ts`（8 条契约）
- 新增夹具：`frontend/e2e/fixtures/{website-inquiry,certificate,customer}.{html,harness.ts}`
- 结论：**4 处联动缺陷已证实、已修复、请求侧验收通过**；全量门禁通过，
  Tracking 两条既有失败独立登记未代修。

---

## 1. 审计基准

| 项 | 值 |
|---|---|
| HEAD | `620191bd`（较 B7-B1a 时的 `433cc563` 已前移，并行任务有提交） |
| 当前分支 | `main` |
| `frontend/` 工作区变更 | 35 项（含多个并行任务改动） |
| Node / npm | v24.18.0 / 11.16.0 |
| vitest / vite（嵌套） | 4.1.9 / 8.1.3 |
| 测试启动路径 | 一律大写盘符 `cd /d C:\Users\20676\source\LQXM\frontend`（B7-B0 结论） |

### 本阶段前置确认

- 已记录 B7-B1a 报告要点（表单生命周期修复、白名单双层保护）。
- 已确认当前无其他 Agent 正在运行测试；dev server 监听 `127.0.0.1:4173`，
  验收结束后已主动结束（仅杀 4173 监听进程及其子进程）。
- 已确认 B6-2 / B7-B1a 的未提交改动完整保留（见 `git diff --stat`）。

---

## 2. 契约定义（本阶段钉死的行为）

> **改查询条件 → 用新条件从第 1 页重新查询；仅翻页 → 保留当前条件，只改页码。**

这条契约规范的是**组件之间的行为关系**，而不是"必须用哪个组件"：
页面即使用了 `SearchToolbar` + `LqPagination`，只要事件接线错了，行为仍然是错的
（证书页正是如此——组件齐全，但分页栏接到了会复位页码的处理函数）。

---

## 3. 实际缺陷与复现证据

### 3.1 缺陷清单

| # | 页面 | 缺陷 | 根因 | 影响 |
|---|---|---|---|---|
| 1 | `WebsiteInquiryView:34` | 输入关键词后仍请求旧页码 | `@search` 直接绑 `loadInquiries`，无 `resetPage()` | 第 3 页搜索 → 请求第 3 页，结果为空或看不出变化 |
| 2 | `CertificateView:139` | **翻页完全失效** | 分页栏 `@change="loadList"`，而 `loadList()` 内含 `resetPage()` | 点任意页码都被弹回第 1 页 |
| 3 | `CertificateView:514` | 清除筛选不重置页码 | `handleResetFilters()` 只把 `doSearch` 交给 `resetFilters()` | 第 5 页清除筛选 → 仍请求第 5 页 |
| 4 | `CustomerView:409` | 清除筛选不重置页码 | 同 #3 | 同 #3 |

### 3.2 修复前实测（Playwright + Edge，请求侧断言）

```text
Running 6 tests using 3 workers

  ✓ 1 WebsiteInquiryView › 翻页只改页码，保留当前查询条件
  ✘ 2 WebsiteInquiryView › 输入关键词后回到第 1 页（分页重置）
      Error: 改关键词后必须回到第 1 页
      Expected: "1"   Received: "3"

  ✘ 3 CertificateView › 翻页只改页码，保留当前查询条件
      Error: 翻页应请求目标页码（0 基）
      Expected: "2"   Received: "0"

  ✘ 4 CertificateView › 清除筛选后回到第 1 页（分页重置）
      （失败于前置断言：翻页后仍停在 0 页）

  ✓ 5 CustomerView › 翻页只改页码，保留当前查询条件
  ✘ 6 CustomerView › 清除筛选后回到第 1 页（分页重置）
      Error: 清除筛选后必须回到第 1 页
      Expected: "0"   Received: "2"

  2 passed | 4 failed
```

### 3.3 缺陷 3 的独立验证（排除被缺陷 2 掩盖）

证书页同时存在缺陷 2（翻页失效）与缺陷 3（清除筛选不重置页码）。
缺陷 2 会把页码永远压在 0，导致缺陷 3 **在修复前不可观测**。

为此做了中间态验证：**只修缺陷 2（分页绑定），暂不修缺陷 3** ——

```text
  ✓ CertificateView › 翻页只改页码，保留当前查询条件
  ✘ CertificateView › 清除筛选后回到第 1 页（分页重置）
      Error: 清除筛选后必须回到第 1 页
      Expected: "0"   Received: "2"
```

证明缺陷 3 独立存在，且回归用例确实能捕获它（不是被缺陷 2 连带通过的空断言）。

---

## 4. 搜索 / 筛选 / 分页入口核查表

逐页面列出全部「改变查询条件」与「翻页」入口及其接入状态。

### WebsiteInquiryView（询盘管理）

| 入口 | 绑定 | 修复前复位页码 | 修复后 |
|---|---|---|---|
| 关键词搜索 | `@search` | ✗ | ✓ `handleKeywordSearch` |
| 状态筛选变更 | `@filter-change` → `handleFilterChange` | ✓ | ✓（未动） |
| 清除筛选 | `@clear` → `resetFilters` | ✓ | ✓（未动） |
| 移除单个标签 | `@remove` → `removeFilterChip` | ✓ | ✓（未动） |
| 翻页 | `@change` → `loadInquiries` | —（不应复位） | ✓（未动） |
| 刷新 / 重试 | `loadInquiries` | —（不应复位） | ✓（未动） |

### CertificateView（知识产权）

| 入口 | 绑定 | 修复前 | 修复后 |
|---|---|---|---|
| 关键词搜索 | `@search` → `applyFilters` | ✓ | ✓（未动） |
| 工具栏筛选变更 | `@filter-change` → `handleToolbarFilterChange` | ✓ | ✓（未动） |
| 移除单个标签 | `@remove` → `removeFilterChip` | ✓ | ✓（未动） |
| **清除筛选** | `@clear` → `handleResetFilters` | **✗** | ✓ `resetFilters(applyFilters)` |
| 分类 Tab 切换 | `@filter-change` → `switchCategoryTab` | ✓ | ✓（未动） |
| **翻页** | `@change` → `loadList` | **✗（被复位）** | ✓ `doSearch` |
| 删除 / 保存后刷新 | `loadList()` | ✓（应复位） | ✓（未动） |

### CustomerView（客户档案）

| 入口 | 绑定 | 修复前 | 修复后 |
|---|---|---|---|
| 关键词搜索 | `@search` → `applyFilters` | ✓ | ✓（未动） |
| 工具栏筛选变更 | `@filter-change` → `handleToolbarFilterChange` | ✓ | ✓（未动） |
| 移除单个标签 | `@remove` → `removeFilterChip` | ✓ | ✓（未动） |
| **清除筛选** | `@clear` → `handleResetFilters` | **✗** | ✓ `resetFilters(applyFilters)` |
| 排序变更 | `@sort-change` → `handleMasterSortChange` | ✓ | ✓（未动） |
| 翻页 | `@pagination-change` → `doSearch` | —（不应复位） | ✓（未动） |
| 地区分布下钻 | `handleDistributionApply` → `applyFilters` | ✓ | ✓（未动） |

### 翻页协议差异（写用例时必须区分）

| 页面 | `page` 参数基 | 来源 |
|---|---|---|
| `WebsiteInquiryView` | **1 基** | `fetchInquiries({ page: pagination.pageInfo.page + 1 })` |
| `CertificateView` | **0 基** | `...pagination.getPageParams()` |
| `CustomerView` | **0 基** | `...pagination.getPageParams()` |

---

## 5. 请求侧核对（修复前 → 修复后）

断言对象是**实际发出的 XHR 查询串**，不是界面上的高亮页码。

| 页面 | 场景 | 修复前请求 | 修复后请求 |
|---|---|---|---|
| WebsiteInquiryView | 翻到第 3 页后输入关键词 | `page=3&keyword=封口机` ✗ | `page=1&keyword=封口机` ✓ |
| WebsiteInquiryView | 搜索后翻到第 3 页 | `page=3&keyword=包装机` ✓ | 不变 ✓ |
| CertificateView | 点第 3 页 | `page=0` ✗（弹回第 1 页） | `page=2` ✓ |
| CertificateView | 第 3 页点清除筛选 | `page=2` ✗ | `page=0` ✓ |
| CustomerView | 搜索后翻到第 3 页 | `page=2&keyword=温州` ✓ | 不变 ✓ |
| CustomerView | 第 3 页点清除筛选 | `page=2` ✗ | `page=0` ✓ |

### 关于"生产库是否存在历史受影响记录"

同 B7-B1a 的处理方式：本阶段**仅证明请求侧行为正确**，
未查询生产库、未做任何数据清理或迁移。
证书页的缺陷只影响"看到哪一段数据"，不产生错误写入，因此**无需数据纠错**。

---

## 6. 修复原则执行情况

| 原则 | 执行情况 |
|---|---|
| 不为单个页面创建大型新 composable | **遵守**。三处均为就地接线修正，共 26 增 / 4 删 |
| 优先复用已有公共逻辑 | **遵守**。证书页 / 客户页复用页面内既有的 `applyFilters()`（已含 `resetPage()`） |
| 与 B7-B1a 的"两层保护"一致口径 | **一致**。改条件 → 复位；翻页 → 保留 |
| 不修改无关业务 | **遵守**。仅 3 个页面文件 + 本阶段测试与报告 |
| 不修改后端 / DB / Flyway | **遵守**，零改动 |

### 6.1 `WebsiteInquiryView.vue`

```diff
-        @search="loadInquiries"
+        @search="handleKeywordSearch"
```

```js
/**
 * 关键词搜索：与其它筛选入口一致，**先回到第 1 页再请求**。
 *
 * <p>此前 `@search` 直接绑 `loadInquiries`，翻到第 3 页后再输入关键词会继续请求第 3 页，
 * 结果要么空列表、要么看不出数据已变。统一走「改条件 → 复位页码 → 请求」。
 */
function handleKeywordSearch() {
  pagination.resetPage()
  loadInquiries()
}
```

### 6.2 `CertificateView.vue`

```diff
-      <LqPagination :pagination="pagination" @change="loadList" />
+      <LqPagination :pagination="pagination" @change="doSearch" />
```

```diff
 function handleResetFilters() {
-  resetFilters(doSearch)
+  resetFilters(applyFilters)
 }
```

> 说明：`loadList()`（`resetPage()` + `doSearch()`）语义本身正确，
> 保留给"删除 / 保存后回到第 1 页"使用；只是**不能**接在分页栏上——
> 分页栏内部已通过 `onPageChange` 回写页码，再复位就会被弹回第 1 页。

### 6.3 `CustomerView.vue`

```diff
 function handleResetFilters() {
-  resetFilters(doSearch)
+  resetFilters(applyFilters)
 }
```

### 6.4 客户档案批量选择状态核查（你要求一并检查）

| 场景 | 行为 | 判定 |
|---|---|---|
| 翻页 | **不清空**批量选择，跨页保留 | 与 `OfficeAssetView` / `MediaAccountView` 一致，符合 `useBatchSelection` 设计 |
| 改筛选条件 | **不清空**批量选择 | 同上 |
| 批量修改 / 批量删除成功后 | 清空选择并退出批量模式 | 正确 |
| 「地区分布」下钻应用筛选 | **会清空**批量选择 | 与其它筛选入口口径**不一致**，登记为待确认项（见 §8） |

本次**未改动**批量选择相关逻辑——它不属于"改条件→第 1 页"契约，改它会扩大本阶段范围。

---

## 7. 门禁结果

所有命令均从大写盘符 cwd 启动；构建输出写入独立临时目录，未触及 `frontend/dist`。

| # | 门禁 | 命令 | 结果 |
|---|---|---|---|
| 1 | 三个页面 E2E（新） | `npx playwright test e2e/b7-b1b-search-pagination.spec.ts` | **6 passed** |
| 2 | 本页 + 既有标准列表页 E2E | `…b7-b1b… office-asset-form-lifecycle… office-asset-standard-page… operation-log-standard-list…` | **46 passed**（28.8s） |
| 3 | 相关公共组件契约 | `vitest run src/components/common/__tests__` | 包含新增 1 文件 8 条，**全部通过** |
| 4 | 全量 Vitest | `vitest run` | **80/81 文件通过；726/728 用例通过** |
| 5 | Typecheck | `npm run typecheck`（vue-tsc 6.0.3） | **通过** |
| 6 | Lint | `npm run lint`（eslint src/ --quiet） | **通过** |
| 7 | 完整构建 | `vite build --outDir <TEMP>\b1b-build --emptyOutDir` | **通过**（15.57s） |

唯一失败文件为 `TrackingAnalyticsDrilldownCells.test.ts`（2 条），
属并行 Tracking 任务引入的规格/测试分叉，**独立登记、未代修**。

---

## 8. 遗留问题和下一批建议

### 8.1 本阶段遗留（未处理，仅登记）

1. **Tracking 两条既有失败**：`TrackingAnalyticsSpecCell.vue` 改为只渲染规格第一段后，
   测试仍按旧口径断言完整规格。需与并行任务确认边界后处理。
2. **`CustomerView.handleDistributionApply` 会清空批量选择**，
   而翻页 / 其它筛选入口都保留选择 —— 口径不一致，建议在 B7-B1c 或 B7-B2 统一确认。
3. **`PackagingMoldView:305-307` `applyFilters()` 空实现**（靠子组件 watch 生效），
   属"空实现"类，放入 B7-B1c。
4. **`resetAndLoad` / `loadList` 语义混用**：证书页把"应当复位页码的刷新"和"不应复位页码的翻页"
   绑到了同一个函数上。本阶段只解开了绑定，未重命名函数；建议 B7-B1c 统一命名
   （如 `refreshFromFirstPage()` vs `reloadCurrentPage()`），降低再次接错的概率。

### 8.2 你提的 22 个未覆盖业务组件 —— 已按你的分阶段方案落位

| 阶段 | 范围 | 说明 |
|---|---|---|
| **B7-B1b**（本阶段） | 搜索 / 筛选 / 分页联动，3 个已确认页面 + 直接依赖子组件 | 已完成 |
| **B7-B1c** | 表单、弹窗、抽屉、异步回填相关组件 | 下一个 |
| **B7-B2** | 权限与批量操作相关业务组件 | 再下一个 |
| **B7-B3** | 空实现与其它共性缺陷 | 随后 |
| **B7-C** | 表格 / 分页 / 弹窗 / 抽屉迁移与视觉一致性 | 最后 |

### 8.3 建议固化为规范的标准列表页状态契约

新建标准列表页时至少满足：

```
搜索 / 筛选 / 移除标签 / 清除筛选   →   改条件 → resetPage() → 请求
翻页                                →   保留条件 → 只改页码 → 请求
刷新 / 重试 / 错误恢复              →   保留条件与页码 → 请求
删除 / 保存后刷新                   →   复位到第 1 页 → 请求
```

并且**在测试里断言请求查询串**，而不是只断言界面页码高亮——
本次 4 处缺陷中有 3 处（证书页翻页、证书页清除筛选、客户页清除筛选）
无法从界面上直接看出来。

**未提交、未推送。未执行 `git clean` / `reset --hard` / `stash`。未修改依赖与锁文件。
构建与测试产物均写入临时目录；已结束本次启动的 dev server。**
