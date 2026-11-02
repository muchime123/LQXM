# B7-A：全站公共组件接入差异审计（只读）

> 目标：以当前真实代码为准，检查全部业务页面是否**真正正确复用**公共组件与规范，产出可执行、可验收的整改任务清单。
> **本阶段只读审计，未修改任何业务页面、公共组件或测试代码。**
> 上游：P0-A ~ B5-C、B6-1（`docs/frontend-ui-b6-1-standard-list-pilot-report.md`）、B6-2（`docs/frontend-ui-b6-2-office-asset-pilot-report.md`）
> 生成日期：2026-10-28

---

## 1. 最新代码与工作区基准

### 1.1 Git 基准

```
HEAD         08d812067cb535a939cc3d3979d051178b0cd995  文档：生产跟单分析抽屉的设计审计与整改决策（远期储备，暂缓实施）
main         08d81206…（= HEAD）
origin/main  1999e3c51e515c283acd1e12dbcd28cd5cf4a4b3
main vs origin/main   领先 2 / 落后 0
工作区变更   125 项
```

自 B6-2 以来新增的 `08d81206` 为**纯文档提交**（4 个 tracking/官网设计文档，+1535 行），**不含代码改动**，不影响本轮审计对象。

### 1.2 B6-1 / B6-2 已完成修改（单独登记，避免重复整改）

| 项 | 文件 | 内容 | 状态 |
|---|---|---|---|
| B6-1 试点 | `frontend/src/apps/archive/views/system/OperationLogView.vue` | 补 `show-header`、`:clear-visible="hasActiveFilters"`、令牌化、删除与令牌不一致的死回退 | **10 增 / 4 删**，工作区未提交 |
| B6-2 试点 | `frontend/src/apps/archive/views/product/OfficeAssetView.vue` | 补 `show-header`、新增 `handleKeywordSearch`（搜索重置页码）、`emptyForm()` 覆盖全部可编辑字段、`:batch-enabled="canManageOfficeAsset"` + 选择列随权限显隐、样式去重与令牌化 | **55 增 / 32 删**，工作区未提交 |
| 新增测试 | `e2e/fixtures/operation-log.{html,ts}`、`e2e/operation-log-standard-list.spec.ts`、`e2e/fixtures/office-asset.{html,ts}`、`e2e/office-asset-standard-page.spec.ts`、`src/components/common/__tests__/OperationLogPilot.contract.test.ts` | 隔离夹具 + 25/9 条 e2e + 7 条源码级契约 | 未提交 |
| 报告 | `docs/frontend-ui-b6-1-*.md`、`docs/frontend-ui-b6-2-*.md` | — | 未提交 |

> **B6-2 遗留未修完项已在本报告登记为 P0-7**（`openEdit` 未以 `emptyForm()` 打底），不属于 B7-B 新发现，属我上一轮修复不彻底，见 §3。

### 1.3 并行任务占用（只读核对，未触碰）

| 归属 | 文件 |
|---|---|
| 并行官网任务 | `api/website/*`、`apps/archive/{navigation,routes}.ts`、`views/marketing/Website*`（含**删除** `WebsiteDashboardView.vue`/`WebsiteVisualEditorView.vue`、新增 `WebsiteCompanyMilestonesView`/`WebsiteSocialLinksView`）、`components/website/*`、`types/website.ts`、`docs/website-*` |
| 并行 tracking 任务 | `components/production/TrackingNodeAnalytics.vue`、提交 `ee359850`/`08d81206`、`docs/production-tracking-*` |
| 其它 | `tools/_verify_node_*.java`（scratch）、`e2e/_tmp-sample.spec.ts`、`vite.config.js.timestamp-*.mjs`（7 个 Vite 中断残留） |

### 1.4 扫描口径

- 范围：`frontend/src/apps/archive/views/**/*.vue`（**40 个 .vue，其中 32 个路由级页面、8 个子视图/抽屉/表格/弹窗**），并核对 `frontend/src/components/**` 中的公共组件源码与调用方。
- 计数规则：统计模板中 `<组件名` 出现次数（`<el-table` 不计入 `<el-table-column`）。
- 所有结论均以当前工作区代码为准，**未沿用 P0-A 旧统计**。

---

## 2. 全站公共组件接入矩阵

### 2.1 组件维度：公共封装 vs 原生 Element Plus（仅业务页，40 个 .vue）

| 能力 | 公共组件（处 / 文件） | 原生 EP（处 / 文件） | 判定 |
|---|---|---|---|
| 页面布局 | `ListPageLayout` 10/10、`MasterLayout` 5/5 | — | 15 页有布局组件，17 页手写 |
| 页面标题 | `PageHeader` **0**（views 无直接使用） | 手写标题块 16 页 | ❌ 公共头部未被直接复用 |
| 搜索栏 | `SearchToolbar` 11/11 | — | ✅ 高覆盖 |
| 筛选回显 | `ActiveFilterChips` 8/8 | 手写 `el-tag` 复刻 1 处（`ProductionTrackingView:77`） | ✅ 基本覆盖 |
| 表格 | `LqTable` **9/8** | `el-table` **57/18**（466 列） | ❌ 原生为主 |
| 分页 | `LqPagination` 9/9 | `el-pagination` **6/6** | ⚠️ 6 处未迁 |
| 弹窗 | `LqDialog` 19/5 | `el-dialog` **28/11** | ❌ 原生为主 |
| 抽屉 | `LqDrawer` **0** | `el-drawer` **8/8** | ❌ 公共抽屉业务页 0 使用 |
| 状态容器 | `DataState` 10/7 | `el-empty` **18/10** + 自写空态 3 | ⚠️ 空态未统一 |
| 空态 | `EmptyState` 2/2 | 同上 | — |
| 状态标签 | `StatusTag` 9/5 | `el-tag` **86/13** | ⚠️ 部分可迁 |
| 统计卡 | `MetricGrid` 6/5、`MetricCard` 18/5 | — | ✅ |
| 批量编辑 | `FieldBatchEditModal` 6/6 | — | ✅ |
| 详情/时间线 | `LqDescriptions` 1/1、`LqTimeline` 1/1 | — | — |
| 日期 | `LqDatePicker` 4/2 | `el-date-picker` **17/10** | ⚠️ 可 100% 覆盖而未迁 |
| 按钮 | **无 `LqButton` 组件** | `el-button` **268/33** | ❌ 标准缺失（见 §9） |
| 输入/选择 | 无 `LqInput`/`LqSelect` | `el-input` 83/18、`el-select` 55/17 | ❌ 标准缺失 |
| 表单 | 无 `LqForm` | `el-form` 22/13、`el-form-item` 116/14 | ❌ 标准缺失 |

### 2.2 页面维度：15 个使用布局组件的页面

`title`/`description` 传了但**未传 `show-header`** ⇒ 标题区整块不渲染（`ListPageLayout` L8 `v-if="showHeader"` + L143 默认 `false`；`MasterLayout` L3 `v-if` + L36 默认 `false`）。

| # | 页面 | 布局 | title | description | show-header | 标题实际渲染 |
|---|---|---|---|---|---|---|
| 1 | `system/OperationLogView.vue` | ListPageLayout | 操作日志 | ✅ | **✅（B6-1 补）** | **✅** |
| 2 | `product/OfficeAssetView.vue` | ListPageLayout | 物品资产 | ✅ | **✅（B6-2 补）** | **✅** |
| 3 | `system/ReimbursementCenterView.vue` | ListPageLayout | 日常报销 | ✅ | ❌ | ❌ 死属性 |
| 4 | `system/DepartmentManagementView.vue` | ListPageLayout | 部门主数据 | ✅ | ❌ | ❌ |
| 5 | `settlement/CashflowJournalView.vue` | ListPageLayout | 资金日记账 | ✅ | ❌ | ❌ |
| 6 | `product/CertificateView.vue` | ListPageLayout | 知识产权 | ✅ | ❌ | ❌ |
| 7 | `marketing/CompanyCertificationsView.vue` | ListPageLayout | 资质合规 | ✅ | ❌ | ❌ |
| 8 | `marketing/WebsiteInquiryView.vue` | ListPageLayout | 询盘管理 | ✅ | ❌ | ❌ |
| 9 | `marketing/MediaAccountView.vue` | ListPageLayout | 自媒体账号 | ✅ | ❌ | ❌ |
| 10 | `marketing/CompanyInfoView.vue` | ListPageLayout | 公司信息 | ✅ | ❌ | ❌（另有 L41 非页面级 `<h1>`） |
| 11 | `customer/CustomerView.vue` | MasterLayout | 客户档案 | ✅ | ❌ | ❌ |
| 12 | `system/EmployeeRosterView.vue` | MasterLayout | 员工名册 | ✅ | ❌ | ❌ |
| 13 | `product/PackagingView.vue` | MasterLayout | 包材管理 | ✅ | ❌ | ❌ |
| 14 | `product/mold/PackagingMoldView.vue` | MasterLayout | 模具资产管理 | ✅ | ❌ | ❌ |
| 15 | `product/ProductCatalogView.vue` | MasterLayout | 产品管理 | ✅ | **显式 `:show-header="false"`** | ❌（有意关闭） |

**小计：12 个页面的 `title`/`description` 是死属性**（8 个 ListPageLayout + 4 个 MasterLayout），另有 1 页有意关闭。

### 2.3 其余 17 个路由级页面：手写标题 / 无标题

| 类名族 | 页面（行号） |
|---|---|
| `.page-header/__eyebrow/__title/__desc/__actions`（7 页，官网运营域） | `WebsiteSolutionsView:160`、`WebsiteProductsView:456`、`WebsiteCompanyProfileView:156`、`WebsiteCompanyMilestonesView:71`、`WebsiteSocialLinksView:71`、`WebsiteMediaView:80`、`WebsiteBannerView:138`（`<h2>` 降级） |
| `.page-head`（5 页） | `H3ImportCenterView:3`、`ProductionTrackingView:3`、`ManufacturingTaskConfigView:3`、`ProductionProcessConfigView:3`、`MonthlyCommissionView:3`(+`.page-title` L4 双层包壳)、`H3SalesProductAnalysisView:3`（`<h2>`） |
| `.page-card__header/__title/__desc` | `RolePermissionView:377`（`<h2>`） |
| `.video-library__header` | `DeviceVideoLibraryView:3` |
| 埋在 `el-card` 内无 header 语义 | `CashflowEntitiesView:6`（`<h2 class="title">`） |
| **整页无标题** | `ProductInventoryView`（无 `<h1>/<h2>/<header>`） |

> 结论：**标题实现有 3 套（布局组件头部 / `.page-header` / `.page-head`）+ 4 套类名**，且层级语义不一致（4 处用 `<h2>` 冒充页面标题）。

### 2.4 搜索 / 筛选 / 分页联动矩阵（11 处 SearchToolbar）

| 页面（行） | `@search` 处理函数 | 搜索重置页码 | `@clear` 重置页码 | `:clear-visible` | `batch-enabled` |
|---|---|---|---|---|---|
| `OperationLogView:13` | `applyFilters`(125) | ✅ | ✅ | ✅ 含日期区间 | `false` |
| `EmployeeRosterView:10` | `applyFilters`(229) | ✅ | ✅ | 无需（无额外维度） | `false` |
| `CashflowJournalView:122` | `searchEntries`(690) | ✅ | ✅ | **❌ 缺**（账户/币种/期间/对账/entity） | `false` |
| `ProductCatalogView:12` | `handleSearch`(651)→`loadModels(0)` | ✅ 等价 | ✅ | **❌ 缺**（视图模式/列表筛选/排序） | **❌ 常量 true** |
| `PackagingView:9` | `handleSearch`(468)→`resetAndLoad`(462) | ✅ | ✅ | **❌ 缺**（listFilter/listSort） | **❌ 常量 true** |
| `OfficeAssetView:32` | `handleKeywordSearch`(400) | ✅（B6-2 修复） | ✅ | 无需 | ✅ `canManageOfficeAsset` |
| `PackagingMoldView:4` | `applyFilters`(305) **空实现** | **❌**（靠子组件 watch 间接） | 间接 | **❌ 缺** | **❌ 常量 true** |
| `CertificateView:70` | `applyFilters`(419) | ✅ | **❌**（514→`resetFilters` 无分页能力） | **❌ 缺** | **❌ 常量 true** |
| `MediaAccountView:19` | `handleKeywordChange`(709) | ✅ | ✅ | ⚠️ 有但**漏 `showOnlyStale`**（26 vs 42/783） | **❌ 常量 true** |
| `WebsiteInquiryView:22` | `loadInquiries`(381) **直绑 loader** | **❌** | ✅ | 无需 | **❌ 常量 true** |
| `CustomerView:10` | `applyFilters`(389) | ✅ | **❌**（409→`resetFilters`） | **❌ 缺**（city/district） | ✅ `canManageCustomer` |

### 2.5 权限矩阵

| 页面 | 顶部新增/编辑/删除 | 行级操作 | 批量入口 |
|---|---|---|---|
| `CustomerView` | ✅ (32) | ✅ 传 `can-manage`(65/75/97) | ✅ 绑定权限 |
| `OfficeAssetView` | ✅ (52/82) | ✅ | ✅ 绑定权限 |
| `CertificateView` | ✅ (91/92/117) | **❌** (120-135；`CertListTable:142-143` 未传 `can-manage`) | **❌** |
| `PackagingView` | ✅ (29/30) | **❌** 未向详情抽屉传 `canManagePackaging` | **❌** |
| `PackagingMoldView` | ✅ (25-28) | **❌** (39-45；`PackagingMoldsMasterDetail:186/190/194`) | **❌** |
| `ProductCatalogView` | ✅ (40/43/47) | **❌** (178 删除产品无判断) | **❌** |
| `MediaAccountView` | ✅ (48/52/56/58/69/100) | **❌** (177-180 无 `canManageMedia`) | **❌** |
| `WebsiteInquiryView` | 无权限定义 | — | **❌** |
| `EmployeeRoster`/`OperationLog`/`CashflowJournal` | 全页无权限判断（一致，非缺陷） | — | `false` |
| `governance/*` | **无任何权限判断** | — | — |

### 2.6 表单生命周期矩阵（新增/编辑共用弹窗，26 处）

见 §3 的 P0-7、P1-6、P1-7、P1-8。**已确认无残留的 20 处**（字段集完全一致）不逐条列出，典型正确范式：`EmployeeRosterView:397-439`（15/15）、`DepartmentManagementView:169-172`、`WebsiteProductsView:207-246`（13/13）、`MediaAccountEditorModal:172-200`（12/12）、`CustomerFormModal:349-383`（先 `delete` 全部 key）、`ArticleEditorModal:111-122`、`MoldFormDialog:635-715`（20/20）、`SolutionFormDialog:71-118`（20/20）、`WebsiteSocialLinks/Milestones:31-34`（`?? ` 二合一）、`MonthlyCommission:1744-1755`、`CashflowEntities:225-236`、`ProductInventory:587-593`。

---

## 3. 真实功能缺陷清单

分级：**P0** 真实功能缺陷/权限交互/状态错误；**P1** 接入不完整/重复交互/维护一致性；**P2** 视觉重复；**例外** 合理保留。
每条含：文件路径行号、现有实现、证据、推荐复用、最小整改、影响范围、业务风险、验收方式。

### P0-1 搜索不重置页码（WebsiteInquiryView）

- **位置**：`views/marketing/WebsiteInquiryView.vue:22`（`@search="loadInquiries"`）、`:381`（`loadInquiries` 直接使用 `pagination.pageInfo.page`，`:389`）
- **现有实现**：`@search` 直绑数据加载函数，无 `pagination.resetPage()`
- **证据**：同页 `@filter-change`(319) 与 `@clear`(341) 都调 `resetPage()`，**同一工具栏内三者行为不一致**；B6-2 已在 `OfficeAssetView` 实测同源缺陷——翻到第 3 页后输入关键词，最后一次列表请求 `page=2`（应为 0），改造前 e2e 断言失败，请求侧证据不受 UI 影响
- **推荐**：沿用 `OfficeAssetView:400` 的 `handleKeywordSearch`（`resetPage()` + 加载）
- **最小整改**：新增 `handleKeywordSearch(){ pagination.resetPage(); loadInquiries() }`，`@search` 改绑
- **影响范围**：询盘管理页 1 页
- **风险**：中——越界页码直接表现为「筛选无结果」，用户以为没有数据
- **验收**：e2e 请求侧断言（翻到第 3 页 → 搜索 → 最后请求 `page=0`）

### P0-2 清空筛选不重置页码（CertificateView / CustomerView）

- **位置**：`CertificateView.vue:78`→`:514-516`；`CustomerView.vue:23`→`:409-411`；公共 `composables/list/useListFilters.ts:28-34`
- **现有实现**：`handleResetFilters(){ resetFilters(doSearch) }`；`resetFilters` 只重置筛选字段并回调，**不接触分页**
- **证据**：`useListFilters.ts:28-34` 全文无 `pagination`/`resetPage`（该 composable 本就不持有分页状态）
- **推荐**：页面层在 `doSearch` 前补 `pagination.resetPage()`
- **最小整改**：`handleResetFilters(){ pagination.resetPage(); resetFilters(doSearch) }`
- **影响范围**：2 页（客户档案为核心业务页）
- **风险**：中高——清除筛选后结果集变小，停在第 N 页会显示空白
- **验收**：e2e（翻页 → 设筛选 → 点「清除筛选」→ 断言请求 `page=0`）

### P0-3 批量操作入口未按权限门控（6 页）

- **位置**：`ProductCatalogView:24`、`PackagingView:15`、`PackagingMoldView:11`、`CertificateView:79`、`MediaAccountView:27`、`WebsiteInquiryView:28`（均常量 `batch-enabled`）
- **现有实现**：`SearchToolbarBatchBar` 按**监听器是否存在**渲染「批量编辑/批量删除」，不含权限语义；无权限用户勾选即进入批量模式
- **证据**：B6-2 e2e 实测——`permissions: []` 时勾选行后 `.search-toolbar--batch` **仍然出现**，且批量按钮可见（点击只会收到 403）；仓库已有正确先例 `CustomerView:16` `:batch-enabled="canManageCustomer"`
- **推荐**：`OfficeAssetView:37` 的写法（`:batch-enabled="canManageXxx"` + 选择列 `v-if` 权限）
- **最小整改**：6 页逐个 `:batch-enabled="canManageXxx"`；`WebsiteInquiryView` 需先补权限定义
- **影响范围**：6 页
- **风险**：中——后端授权仍生效（职责分离未被破坏），但前端暴露越权入口，属误导性 UI
- **验收**：e2e 注入 `permissions: []`，断言选择列与批量栏均不出现

### P0-4 行级操作与顶部权限不一致（4 页）

- **位置**：`MediaAccountView:177-180`（行内 `＋更新/详情/编辑/删除` 无 `canManageMedia`）、`CertificateView:120-135` + `components/certificate/CertListTable.vue:142-143`（视图未传 `can-manage`）、`PackagingMoldView:39-45` + `components/packaging/PackagingMoldsMasterDetail.vue:186/190/194`（未传权限 prop）、`ProductCatalogView:178`（标题菜单「删除产品」无判断）
- **现有实现**：顶部按钮有 `v-if="canManageXxx"`，行内/子组件按钮没有，也未向下传权限 prop
- **证据**：对照正确范式 `CustomerView:65/75/97`（向抽屉传 `:can-manage`）
- **推荐**：`CustomerView` 范式——权限 computed 下发给子组件，行内按钮同源判断
- **最小整改**：4 页补 `v-if` / 传 prop
- **影响范围**：4 页 + 2 个子组件
- **风险**：中——无权限用户可见并点击行内写操作
- **验收**：e2e 无权限态断言行内写按钮不可见；有权限态断言可见

### P0-5 `@search` 为空实现，加载完全依赖子组件 watch（PackagingMoldView）

- **位置**：`views/product/mold/PackagingMoldView.vue:305-307`（`applyFilters(){ /* 注释 */ }`）、`:301-303`（`@filter-change` 只改字段）、`:291-299`（`@clear` 调空实现）
- **现有实现**：工具栏事件不发请求；实际加载由 `components/packaging/PackagingMoldsMasterDetail.vue:1496-1503` 的 `watch(extraFilters)` → `pageInfo.page = 0; loadMolds()` 触发
- **证据**：源码直读；全页无任何 `resetPage` 调用
- **推荐**：在 `applyFilters` 内显式 `pagination.resetPage()` + 调用子组件加载（或保持子组件自治但删除无用的空事件绑定并加注释说明）
- **最小整改**：二选一——① 页面显式重置页码并触发加载；② 保留现状但把空函数改为显式注释的「由子组件 watch 驱动」，避免后人误判为遗漏
- **影响范围**：模具资产管理页
- **风险**：中——语义错位；子组件 watch 一旦调整即静默失效；且「清除筛选」是否重置页码不受本页控制
- **验收**：e2e 断言筛选后请求 `page=0`

### P0-7 表单字段残留（OfficeAssetView，B6-2 修复未完）

> **诚实登记**：这是我在 B6-2 中修复不彻底的残留，不是本轮新发现。

- **位置**：`views/product/OfficeAssetView.vue:686`（`openEdit` → `Object.assign(form, asset)`，**不以 `emptyForm()` 打底**）、`:681`（`openCreate` → `Object.assign(form, emptyForm())`，只覆盖不删除 key）
- **现有实现**：`emptyForm()`（:585-601）覆盖 16 个可编辑字段，但 `OfficeAsset` 类型另有 `id`、`lastInventoryAt`、`createdAt`、`updatedAt`；`Object.assign` 不删除 key
- **证据**：① 源码直读；② 后端 `OfficeAssetService.java:55` `asset.setId(null)` —— 说明**当前后端兜住了 `id`**，`createdAt`/`updatedAt` 由 `@CreationTimestamp/@UpdateTimestamp`（`entity/OfficeAsset.java:50-51`）覆盖；③ 但 `lastInventoryAt`（`entity:48`，无审计注解）**无保护**，新资产会继承上一条的最后盘点时间
- **推荐**：`composables/ui/useModalForm.ts:44` 的正确范式 `Object.assign(form, defaultForm(), item)`，或 `CustomerFormModal.vue:347` 的先 `delete` 全部 key
- **最小整改**：`openEdit` 改为 `Object.assign(form, emptyForm(), asset)`
- **影响范围**：物品资产页（B6-2 试点页）
- **风险**：中——潜在数据污染（`lastInventoryAt`）；若后端将来信任 payload `id` 则升级为误更新
- **验收**：e2e 断言「编辑 → 新增」后表单仅剩默认值（含 `id` 不存在）

> 说明：B6-2 的 e2e（12-字段残留检测）当时通过，是因为它只检测**输入框可见值**，`id`/时间戳不在输入框中，属测试用例覆盖盲区，已在本报告记录补丁方向。

### P1-1 12 个页面的 `title`/`description` 为死属性

- **位置**：见 §2.2 表（8 个 ListPageLayout + 4 个 MasterLayout）
- **证据**：`ListPageLayout.vue:143` `showHeader: false`、`MasterLayout.vue:36` `showHeader: false`
- **推荐**：复用既有头部；`PageHeader` 与 `ListPageLayout` 头部已共用 `.page-surface-header`（`styles/modules/list-page-surfaces.css`）
- **最小整改**：**需先决策**（见 §7 B7-B3）：(a) 逐页补 `show-header`（B6-1/B6-2 已证可行，改动可控）；(b) 改默认值（一次性生效，但改动面大且需设计确认）。**本轮按边界不改公共默认值**
- **影响范围**：12 页
- **风险**：低（纯展示补齐），但与 `RouteSkeleton` 的 `layout:'list'`（头部+工具栏+表格）结构一致性相关——补头部可消除首屏跳版
- **验收**：每页 e2e 断言 `.page-surface-header h1` 文本 == `title`；`git diff` 单文件、逐页截图

### P1-2 `#filterChips` 公共插槽 0 使用

- **位置**：`ListPageLayout.vue:36-37` 提供插槽；8 处 `ActiveFilterChips` 全在默认插槽（`EmployeeRoster:31`、`Certificate:97`、`OfficeAsset:62`、`PackagingMold:37`、`WebsiteInquiry:51`、`MediaAccount:80`、`Customer:35`、`CashflowJournal:118`）；`ProductionTrackingView:77` 手写 `el-tag` 复刻
- **证据**：全仓检索 `#filterChips` 仅 1 处（组件定义本身）
- **推荐**：统一走 `#filterChips`（语义正确、由布局统一控制间距）**或**在规范中明确「默认插槽即合规」并评估删除该插槽
- **最小整改**：决策后统一；`ProductionTrackingView:77` 优先改用 `ActiveFilterChips`
- **影响范围**：9 处
- **风险**：低
- **验收**：视觉回归（chips 与工具栏间距不变）

### P1-3 缺 `:clear-visible`（6 页）+ 1 页漏维度

- **位置**：`CashflowJournalView:122`（账户/币种/期间/对账/entity）、`CustomerView:10`（city/district，`:396-399` chips `:375-385`）、`CertificateView:70`、`PackagingView:9`（listFilter/listSort）、`ProductCatalogView:12`（视图模式/列表筛选/排序）、`PackagingMoldView:4`；`MediaAccountView:26` 有但**漏 `showOnlyStale`**（`:42`/`:783`）
- **证据**：`SearchToolbar` 的 `hasActiveFilters` 只看 `keyword` + `fields` 值，页面私有维度不在其中（B6-1 已在 `OperationLogView` 证实并修复同类问题）
- **推荐**：`OperationLogView:21` 的 `:clear-visible="hasActiveFilters"`（computed 内纳入全部维度）
- **最小整改**：各页补齐 computed 并传参；`MediaAccountView` 把 `showOnlyStale` 纳入
- **影响范围**：7 页
- **风险**：中——只设这些维度时「清除筛选」按钮不出现，用户只能逐个清
- **验收**：e2e 断言「仅设日期/账户/开关 → 清除按钮可见；点击后全部维度归零且 `page=0`」

### P1-4 标题实现三套并存、层级语义不一致

- **位置**：§2.3 表；层级问题：`WebsiteBannerView:139`、`H3SalesProductAnalysisView:4`、`RolePermissionView:379`、`CashflowEntitiesView:6` 用 `<h2>` 冒充页面标题；`CompanyInfoView:41` 非页面级 `<h1>`；`ProductInventoryView` 无标题
- **推荐**：统一 `PageHeader` / `.page-surface-header`
- **最小整改**：B7-C 阶段分批替换为公共头部；`<h2>`→`<h1>` 随替换一并修正
- **影响范围**：17 页
- **风险**：低-中（纯展示；但涉及 16 页 DOM 结构，需逐页视觉回归）
- **验收**：逐页截图 + 断言每页有且仅有一个 `<h1>`

### P1-5 表格/抽屉/空态公共组件覆盖率低

- **位置**：`LqTable` 9 vs `el-table` 57（18 文件）；`LqDrawer` 0 vs `el-drawer` 8；`DataState` 10 vs `el-empty` 18
- **推荐**：低风险只读表优先迁 `LqTable`（详见 §7 B7-C1 清单，约 25 张）；抽屉迁 `LqDrawer`（`size`→`width` 1:1 映射）；`el-empty`→`EmptyState`
- **风险**：中——`ProductionTrackingView:124` 的粘性表头依赖 `:style="stickyHeaderStyle"`，`LqTable` 外层 `.lq-table-panel` 可能破坏 sticky 计算，**需实测**（列为 B7-C3 单独安排，不强制）
- **验收**：迁移后逐页 2 主题 × 2 视口截图 + 表格几何探针

### P1-6 表单校验态跨次打开遗留

- **位置**：全仓 `clearValidate`/`resetFields` **0 处**；`EmployeeRosterView:78-79`（`LqDialog`）+ `:445`（`validate()`）；`OfficeAssetView:143-151` + `:690`
- **证据**：`LqDialog.vue:197` `destroyOnClose: false` → 弹窗关闭不销毁 `el-form`，校验失败的红字/错误态保留到下次打开
- **推荐**：`openCreate`/`openEdit`/`resetForm` 内补 `formRef.value?.clearValidate()`（或给 `LqDialog` 加 `destroy-on-close`）
- **最小整改**：2 处（EmployeeRoster、OfficeAsset）
- **影响范围**：2 页
- **风险**：低-中（误导用户以为上次错误仍在）
- **验收**：e2e 断言「打开编辑 → 清空必填 → 触发校验 → 关闭 → 重开 → 无红字错误态」

### P1-7 异步回填覆盖用户输入（3 处无守卫）

- **位置**：`components/packaging/PackagingEditorModal.vue:324-331`（`onMounted`→await→`Object.assign(form, detail.model)`，无守卫；父级 `PackagingView:86` 的 `v-if` 每次重建，暴露窗口短）、`views/marketing/WebsiteProductsView.vue:262-278`/`:300-314`/`:348-369`（有守卫但只校验「是否同一弹窗」，不校验用户是否已编辑）、`components/mold/MoldFormDialog.vue:628-691`（无守卫）
- **推荐**：`views/marketing/WebsiteBannerView.vue:80-89` 的**正确范式**——先 `await` 取数，再 `dialogVisible = true`
- **最小整改**：3 处改为「先取数后开弹窗」，或在回填前比较「用户是否已修改（dirty）」
- **影响范围**：3 处（含 1 个包材编辑器）
- **风险**：中——慢网/大数据时用户已输入被整对象覆盖（静默丢数据）
- **验收**：e2e 用延迟 mock：打开编辑 → 立即输入 → 断言回填后用户输入仍保留

### P1-8 `useModalForm` 空默认值（结构隐患）

- **位置**：`views/marketing/MediaAccountView.vue:555-558`（`useModalForm({ defaultForm: () => ({}) })`）
- **证据**：空对象默认 → `openAdd()` 重置不了任何字段；实际未爆发是因为真正表单是独立 `updateForm`（:594），由 `watch(updateModal.visible)`（:676-699）两分支全量覆盖
- **最小整改**：`defaultForm` 补 5 个字段并去掉重复 watch，或直接弃用该 `form`
- **影响范围**：1 页
- **风险**：低（当前被 watch 兜住），属「后人改用即复现 OfficeAsset 缺陷」的隐患
- **验收**：契约断言 `defaultForm()` 字段集 == 编辑分支字段集

### P1-9 对公共组件根类/内部类的样式耦合

- **位置**：`EmployeeRosterView:513/517`、`:568/572`（`CustomerView`）对 `.search-toolbar`/`.active-filter-chips` **根类**用 `:deep()`；`WebsiteInquiryView:525` `:deep(.batch-hint)`（SearchToolbar 内部类）；`OfficeAssetView:891-894/927`（弹窗内 `.el-select`/`.el-date-editor`/`.el-input-number` 宽度，:391 已注明「等价但属重写」）
- **推荐**：布局约束放在外层包裹容器；宽度走组件 props（`SearchToolbar` 的 `field.width`）
- **最小整改**：4 处
- **影响范围**：3 页
- **风险**：低（脆弱耦合，公共组件内部改名即失效）
- **验收**：lint/构建通过 + 视觉回归一致

### P2 汇总（详见 §4）

见 §4「重复 UI 与 CSS 清单」：卡片外观重复 12 处、裸色值 ~110 处、`border-radius` px ~45 处、`font-size` px ~200 处、`:deep()` 38 处（14 存疑）、死规则 4 处、原生分页 6 处、原生日期选择器 17 处。

---
## 4. 重复 UI 与 CSS 清单

### 4.1 重复实现「公共卡片/表面外观」（与 `.search-toolbar` 逐项比对）

基准（`SearchToolbar.vue:488-502`）：`background: var(--color-surface)` + `border: 1px solid var(--color-border)` + `border-radius: var(--radius-lg)` + `box-shadow: var(--shadow-sm)`。

| # | 文件:行 | 选择器 | 重复度 | 处置 |
|---|---|---|---|---|
| 1 | `governance/H3ImportCenterView.vue:1104-1111` | `.import-actions` | **4/4 完全相同** | 抽公共表面类 |
| 2 | `product/DeviceVideoLibraryView.vue:1922-1928` | `.video-card` | 4/4（圆角档位不同） | 抽公共表面类 |
| 3 | `production/ManufacturingTaskConfigView.vue:351` | `.status-bar` | 4/4（均带硬编码回退） | 抽公共表面类 + 去回退 |
| 4 | `settlement/commission/MonthlyCommissionView.vue:1919-1924` | `.page-head` | 4/4 变体 | 抽公共表面类 |
| 5 | `marketing/WebsiteSolutionsView.vue:359-365` | `.sections-accordion` | 4/4 变体 | 抽公共表面类 |
| 6 | `marketing/CompanyInfoView.vue:998-1005` | `.info-card` | 与同行 `.panel` **互为逐字重复** | 合并为一个类 |
| 7 | `marketing/CompanyInfoView.vue:1382-1388` | `.panel` | 同上 | 合并 |
| 8 | `production/ProductInventoryView.vue:762-765` | `.stat-card` | 4/4 + 硬编码回退 | 抽公共类 + 去回退 |
| 9 | `marketing/WebsiteSolutionsView.vue:466-471` | `.section-table-card` | 3/4（无阴影） | 可收敛 |
| 10 | `governance/H3ImportCenterView.vue:1303-1309` | `.panel` | 3/4，与 #1 同源 | 同文件内合并 |
| 11 | `product/OfficeAssetView.vue:861-873` | `.scan-panel` | 3/4（border 取主色属有意区分） | **例外**（B6-2 已删除重复的那一份） |
| 12 | `product/DeviceVideoLibraryView.vue:2199-2207` | `.card-menu-panel` | 4/4（用 `--shadow-lg`） | **例外**（浮层需与卡片区分） |

### 4.2 与 tokens.css 不一致的基础视觉（业务页 `<style>`）

| 类别 | 命中 | 最重文件 | 处置 |
|---|---:|---|---|
| 裸十六进制色值 | ~110 处 | `DeviceVideoLibraryView` 35、`ProductionTrackingView` 30、`H3ImportCenterView` 6、`MonthlyCommissionView` 4、`ProductionTrackingAnalyticsDrawer` 7、`ProductionTrackingWeeklyReviewDrawer` 8、`MediaAccountView` 2、`ProductInventoryView` 2 | 按文件批次令牌化；语义色优先复用 `--color-*` |
| `border-radius: Npx` | ~45 处 | `MonthlyCommissionView` 10、`H3ImportCenterView` 11、`ProductInventoryView` 6、`ProductionTrackingView` 5、`RolePermissionView` 3 | 替换为 `--radius-*` |
| `font-size: Npx` | ~200 处 | `DeviceVideoLibraryView` 47、`MonthlyCommissionView` 31、`H3ImportCenterView` 29、`CompanyInfoView` 25、`ProductInventoryView` 15、`ProductionTrackingView` 14 | 替换为 `--fs-*` |
| `box-shadow` 真字面量 rgba | 3 处 | `ProductionTrackingView:842`、`H3ImportCenterView:1198`、(+1) | 替换为 `--shadow-*` |
| `var(--x, 回退)` **死回退** | 3 处 | `MonthlyCommissionView:1923`、`ManufacturingTaskConfigView:351`、`ProductInventoryView:765` | 删除回退（令牌恒存在，回退永不生效且易与真值分叉） |

### 4.3 对公共组件/Element Plus 内部的 `:deep()` 覆盖（38 处）

- **存疑 14 处**：覆盖公共组件**根类** 3 处（`EmployeeRosterView:513/517`、`CustomerView:568/572`）、`!important` 2 处（`H3ImportCenterView:1691/1707`）、重复规则 5 处（`ProductionTrackingView:853/855/932` 重复 `.el-button + .el-button`、`916/925` 重复 `.el-checkbox`）、`OfficeAssetView:891-894/927`
- **必要 24 处**：`WebsiteSolutionsView:367-390`（`el-collapse` 无对应 prop）、`:474-488`（走 `--el-table-*` CSS 变量注入，**做法正确**）、`RolePermissionView:752/800`、`MonthlyCommissionView:2114-2124`（抽屉内距）、`H3ImportCenterView:1685-1703`（分段控件）、`ProductionTrackingView:849-864/878`、`H3SalesProductAnalysisView:245/254`

### 4.4 死规则与死声明（4 处）

| 文件:行 | 类名 | 判定 |
|---|---|---|
| `production/ProductInventoryView.vue:834` | `.text-success` | 模板无引用 |
| `production/ProductInventoryView.vue:835` | `.text-danger` | 模板无引用 |
| `marketing/WebsiteBannerView.vue:287` | `.hero-link` | 模板 `:137-245` 无引用 |
| `production/ManufacturingTaskConfigView.vue:365` | `.duration-panel{position:sticky;top:16px}` | 被紧邻 `:367` 无条件 `position:static` 覆盖，永不生效 |

### 4.5 未迁移的原生组件（业务页）

| 组件 | 处 / 文件 | 迁移可行性 |
|---|---|---|
| `el-pagination` | 6 / 6（`CashflowReviewDrawer:51`、`ProductInventory:215`、`WebsiteProducts:597`、`WebsiteMedia:108`、`H3SalesProductAnalysis:119`、`H3ImportCenter:268`） | 4 处可迁；`H3SalesProductAnalysis`/`H3ImportCenter` 因 `layout` 缺口**暂保留**（§9） |
| `el-date-picker` | 17 / 10 | **100% 可迁** `LqDatePicker`（7 个 prop 全覆盖） |
| `el-empty` | 18 / 10 | 可迁 `EmptyState`（`image-size` 用 `size` 替代） |
| `el-dialog` | 28 / 11 | 18 处可迁（简单表单/只读）；复杂/嵌套见 §5 |
| `el-drawer` | 8 / 8 | 可迁 `LqDrawer`（`size`→`width` 1:1） |
| `el-table` | 57 / 18 | 约 25 张低风险只读表可迁；2 张核心复杂主表单列（§5） |
| `el-tag` | 86 / 13 | 纯状态型可迁 `StatusTag`；交互型（`closable`/`@close`/`effect`）**保留** |
| `el-button`/`el-input`/`el-select` | 268/83/55 | **无公共封装**，属标准缺失（§9） |

---

## 5. 合理例外清单

不得把所有裸用 Element Plus 一概认定为违规。以下为**建议保留**：

| # | 位置 | 保留理由 |
|---|---|---|
| 1 | `product/OfficeAssetView.vue:800`、`:965-979`（打印 HTML + 标签预览：`#fff`/`#000`、`9pt/15pt/6.5pt`、白底、`box-shadow: 0 3px 14px rgba(0,0,0,.18)`） | **物理纸张/标签的屏幕模拟**：纸恒为白、不随主题；字号对应打印字号的 mm×5 倍换算；投影主题化后在深色下会变「发光」而失真。三项已在代码注释中写明 |
| 2 | `production/ProductionTracking{Analytics,NodeAnalytics,WeeklyReview}Drawer.vue:2`（`size="90%"` + `:with-header="false"` + 自绘标题） | 全屏级分析抽屉 + 自绘大标题，属业务特例；迁 `LqDrawer` 需 `fullscreen` prop（§9 缺口），可等价用 `width="90%"`，**不强制** |
| 3 | `marketing/WebsiteProductsView.vue:700` 嵌套 `:716` | 嵌套弹窗需显式 `zIndex` 分层；`LqDialog` 虽恒定 `append-to-body` 且有 `zIndex`，但迁移需实测层级，**可延后** |
| 4 | `production/ProductInventoryView.vue:366`（抽屉内再开 `append-to-body` 弹窗）、`governance/H3ImportCenterView.vue:254`（弹窗内分页表格） | 复杂嵌套结构，不强制一次性迁移 |
| 5 | 全部 268 处 `el-button` / 83 处 `el-input` / 55 处 `el-select` | **公共组件库无对应封装**，属标准缺失而非页面违规 → 应优先补 `LqButton`（§9） |
| 6 | `el-tag` 交互型（`ProductionTrackingView:77` 可关闭筛选 chip、`:201` `v-for` 动态标签、`MonthlyCommissionView` 22 处） | `StatusTag` 无 `closable`/`@close`/`effect` |
| 7 | `H3SalesProductAnalysisView.vue:119`、`H3ImportCenterView.vue:268` 分页 | `layout="total, prev, pager, next"`（无 sizes），`LqPagination` 仅 full/compact 两档，**确实缺能力** |
| 8 | `production/ProductionTrackingView.vue:124`（66 列 + `stickyHeaderStyle` + 固定列）、`production/ProductInventoryView.vue:94`（62 列 + 分组折叠子表） | 核心复杂主表；`LqTable` 外层 `.lq-table-panel` 可能破坏 sticky 计算并改变现有视觉 → **B7-C3 单独安排，不强制迁移** |
| 9 | `product/DeviceVideoLibraryView.vue:2199-2207` `.card-menu-panel` | 浮层 popover，需 `--shadow-lg` 与卡片区分 |
| 10 | `product/ProductCatalogView.vue:158-167` `.catalog-workspace-empty` | **已合规**：挂在 `DataState` 的 `#empty` 插槽内，是推荐的自定义空态写法 |
| 11 | `WebsiteSolutionsView.vue:474-488` 用 `--el-table-*` CSS 变量注入 | 做法正确（变量注入而非硬覆盖），保留 |
| 12 | `system/OperationLogView.vue:58`、`settlement/cashflow/CashflowAccountTable.vue:3` 的 `type="expand"` 展开行 | 实证 `LqTable` 支持展开行（后者外层已是 `LqTable`），**不构成保留理由，可迁** |

---

## 6. 每个问题的文件级整改方案

按文件聚合（同一文件内多项合并实施，减少往返）。**优先级**：P0 > P1 > P2；同一文件内先做 P0。

| # | 文件 | 项 | 最小整改 | 预估改动 | 验收方式 |
|---|---|---|---|---|---|
| 1 | `views/marketing/WebsiteInquiryView.vue` | P0-1 / P0-3 / P1-1 / P1-9 | ① 新增 `handleKeywordSearch`（`resetPage`+加载）改绑 `@search`(22)；② 补 `canManageXxx` 权限 computed 并绑 `:batch-enabled`(28)；③ 补 `show-header`(2)；④ `:deep(.batch-hint)`(525) 移除外层容器 | ~8 行 | e2e：搜索后 `page=0`；无权限态无批量栏 |
| 2 | `views/product/CertificateView.vue` | P0-2 / P0-3 / P0-4 / P1-1 / P1-3 | ① `handleResetFilters`(514) 补 `resetPage`；② `:batch-enabled="canManageCertificate"`(79)；③ 向 `CertListTable` 传 `:can-manage`(120) 并在子组件(142-143)判断；④ 补 `show-header`(2)；⑤ 补 `:clear-visible` | ~12 行 + 子组件 ~4 行 | e2e 清除后 `page=0`；无权限态行内写按钮不可见 |
| 3 | `views/customer/CustomerView.vue` | P0-2 / P1-1 / P1-3 / P1-9 | ① `handleResetFilters`(409) 补 `resetPage`；② `MasterLayout` 补 `show-header`(2)；③ `:clear-visible` 纳入 city/district(10)；④ `:deep()`(568/572) 移除外层 | ~10 行 | e2e：仅设省市 → 清除按钮可见 |
| 4 | `views/product/ProductCatalogView.vue` | P0-3 / P0-4 / P1-3 | ① `:batch-enabled="canManageProduct"`(24)；② 标题菜单删除按钮(178) 补 `v-if`；③ `:clear-visible` 纳入视图模式/列表筛选/排序(12) | ~8 行 | e2e 无权限态断言 |
| 5 | `views/product/PackagingView.vue` | P0-3 / P0-4 / P1-1 / P1-3 | ① `:batch-enabled="canManagePackaging"`(15)；② 向详情抽屉传 `canManagePackaging`；③ `show-header`(2)；④ `:clear-visible` 纳入 listFilter/listSort(9) | ~10 行 | 同上 |
| 6 | `views/product/mold/PackagingMoldView.vue` | P0-5 / P0-3 / P0-4 / P1-1 / P1-3 | ① `applyFilters`(305) 显式 `resetPage`+触发加载（或改为注释明确的子组件驱动）；② `:batch-enabled="canManageMold"`(11)；③ 行内(39-45) 与 `PackagingMoldsMasterDetail`(186/190/194) 补权限；④ `show-header`(2)；⑤ `:clear-visible`(4) | ~14 行 | e2e 筛选后 `page=0` |
| 7 | `views/marketing/MediaAccountView.vue` | P0-3 / P0-4 / P1-1 / P1-3 / P1-8 | ① `:batch-enabled="canManageMedia"`(27)；② 行内按钮(177-180) 补 `v-if="canManageMedia"`；③ `show-header`(2)；④ `:clear-visible`(26) 纳入 `showOnlyStale`；⑤ `useModalForm` 的 `defaultForm`(556) 补 5 字段或弃用 | ~14 行 | e2e 断言「待关注」开关纳入清除判断 |
| 8 | `views/product/OfficeAssetView.vue` | **P0-7** / P1-6 | ① `openEdit`(686) 改 `Object.assign(form, emptyForm(), asset)`；② `openCreate`/`openEdit` 补 `formRef.value?.clearValidate()`（或 `LqDialog`(143) 加 `destroy-on-close`） | ~4 行 | e2e：编辑→新增后表单无残留（**含断言 `id` 不存在**）；校验态不跨次遗留 |
| 9 | `views/system/EmployeeRosterView.vue` | P1-1 / P1-6 / P1-9 | ① `MasterLayout` 补 `show-header`(2)；② `openCreate`/`openEdit` 补 `clearValidate()`（配合 `:445` validate + `LqDialog`:78）；③ `:deep()`(513/517) 移除外层 | ~8 行 | e2e 校验态断言 |
| 10 | `views/settlement/CashflowJournalView.vue` | P1-1 / P1-3 | ① `show-header`(2)；② `:clear-visible` 纳入账户/币种/期间/对账/entity(122) | ~6 行 | e2e 清除按钮断言 |
| 11 | `views/system/ReimbursementCenterView.vue`、`views/system/DepartmentManagementView.vue`、`views/marketing/CompanyCertificationsView.vue` | P1-1 | 各补 `show-header`（行 2） | 各 1 行 | e2e 头部文本断言 |
| 12 | `views/marketing/CompanyInfoView.vue` | P1-1 / 语义 | 补 `show-header`(2)；`:41` 的 `<h1>`（公司名）降级为 `<h2>`，避免与页面标题双 h1 | ~2 行 | e2e 断言每页仅 1 个 `<h1>` |
| 13 | `components/certificate/CertListTable.vue` | P0-4 | 新增 `canManage` prop，行内编辑/删除(142-143) 判断 | ~6 行 | 配合第 2 项验收 |
| 14 | `components/packaging/PackagingMoldsMasterDetail.vue` | P0-4 / P0-5 | 新增权限 prop 并在(186/190/194) 判断；向页面暴露显式加载方法供 `applyFilters` 调用 | ~10 行 | 配合第 6 项验收 |
| 15 | `components/packaging/PackagingEditorModal.vue` | P1-7 | `:324-331` 改为「先 await 取数 → 再通知父级开弹窗」，或回填前校验 dirty | ~8 行 | e2e 延迟 mock：用户输入不被覆盖 |
| 16 | `views/marketing/WebsiteProductsView.vue` | P1-7 / P1-5 | `:262-278`/`:300-314`/`:348-369` 三处回填守卫增强；表格(470/701) 与 5 个弹窗(607/655/675/686/716) 迁 `LqTable`/`LqDialog`（B7-C） | 分两批 | e2e 覆盖断言 |
| 17 | `components/mold/MoldFormDialog.vue` | P1-7 | `:628-691` 补 dirty 守卫 | ~5 行 | 同上 |
| 18 | `views/production/ProductInventoryView.vue` | P1-5 / P2 | 6 张快照子表(163/247/272/294/329/346) 迁 `LqTable`；分页(215) 迁 `LqPagination` 并消除 `@current-change`+`@size-change` 双触发；删死规则(834/835)；令牌化(765/767/769)；补页面标题 | 中 | 逐表截图 + 分页请求计数断言 |
| 19 | `views/governance/H3ImportCenterView.vue` | P1-5 / P2 | `.import-actions`(1104) 与 `.panel`(1303) 合并/抽公共类；`:1691/1707` 的 `!important` 复核；3 张只读表(196/202/291) 迁 `LqTable`；抽屉(284) 迁 `LqDrawer` | 中 | 视觉回归 |
| 20 | `views/production/ProductionTrackingView.vue` | P1-2 / P1-5 / P2 | `:77` 手写筛选 chip → `ActiveFilterChips`；6 张预览/候选表(230/251/330/379/506/534) 迁 `LqTable`；**主表(124) 单列，不强制**；重复 `:deep()`(853/855/932、916/925) 合并；30 处裸色值令牌化 | 大，拆两次 | 逐批截图 + 表格几何探针 |
| 21 | `views/settlement/cashflow/CashflowReviewDrawer.vue`、`views/marketing/Website{Solutions,Media,SocialLinks,CompanyMilestones,CompanyProfile,Banner}View.vue`、`views/product/DeviceVideoLibraryView.vue`、`views/settlement/commission/MonthlyCommissionView.vue`、`views/system/RolePermissionView.vue`、`views/settlement/CashflowEntitiesView.vue`、`views/governance/H3SalesProductAnalysisView.vue`、`views/production/{ManufacturingTaskConfig,ProductionProcessConfig}View.vue` | P1-4 / P1-5 / P2 | 标题体系收敛（B7-B3/C1）+ 表格/弹窗迁移（C1/C2）+ 令牌化（C4） | 大 | 逐页截图 + 单 h1 断言 |

> 排序原则：**先 P0 逻辑、后 P1 接入、再 P2 样式**；同一文件内合并实施。第 1~10 项为 B7-B 范围（低风险、可单点验证）；第 11~21 项为 B7-C 范围（中风险、需视觉回归）。

---

## 7. B7-B、B7-C 分批实施计划

### 7.0 前置条件（必须先解决，否则无法验收）

1. **恢复 Vitest 运行环境**：当前 `npx vitest run` 在**任何规模**下都失败（详见 §8.2，**B6 的「78 文件上限」结论已被本轮证据推翻**）。B7-B 开工前需先修复环境（建议 `npm ci` 或重装 `node_modules` + 清理 `node_modules/.vite`），并重建基线。
2. **决策项：`showHeader` 默认值**（影响 B7-B3 的实施方式）：逐页补 `show-header`（推荐，B6-1/B6-2 已验证可行）或改公共默认值（一次性生效但需设计确认）。**在决策前不批量动手写标题页**。

### 7.1 B7-B：低风险、同类问题集中整改

| 批次 | 主题 | 覆盖项 | 页面/文件 | 风险 | 验收 |
|---|---|---|---|---|---|
| **B7-B1** | 搜索 / 筛选 / 分页联动 | P0-1、P0-2、P0-5、P1-3 | `WebsiteInquiryView`、`CertificateView`、`CustomerView`、`PackagingMoldView`、`CashflowJournalView`、`PackagingView`、`ProductCatalogView`、`MediaAccountView`（8 页） | 低（每个改动 1~3 行，请求侧可断言） | 每页 e2e：① 翻页后搜索 `page=0`；② 翻页后清除筛选 `page=0`；③ 仅设私有维度时清除按钮可见 |
| **B7-B2** | 权限与操作入口 | P0-3、P0-4 | 6 页 + `CertListTable`、`PackagingMoldsMasterDetail` | 低-中（不触碰后端授权） | e2e 双态断言（有/无 `*.manage` 权限） |
| **B7-B3** | 标题体系（布局组件页） | P1-1 | 12 页补 `show-header`（决策后执行） | 低（纯展示补齐，B6-1/B6-2 已验证） | 每页 e2e `.page-surface-header h1` == `title`；逐页前后截图 |
| **B7-B4** | 表单生命周期 | P0-7、P1-6、P1-7、P1-8 | `OfficeAssetView`、`EmployeeRosterView`、`PackagingEditorModal`、`WebsiteProductsView`、`MoldFormDialog`、`MediaAccountView` | 中（触碰保存链路，需严格回归「新增/编辑/关闭重开」三条路径） | e2e：编辑→新增无残留（含 `id`）、校验态不遗留、延迟回填不覆盖输入 |

**B7-B 不做**：不改公共组件默认值、不动核心复杂页、不做样式批量令牌化。

### 7.2 B7-C：中风险、逐模块实施

| 批次 | 主题 | 内容 | 风险 | 说明 |
|---|---|---|---|---|
| **B7-C1** | 表格 / 抽屉 / 空态迁移 | `LqTable` 约 25 张低风险只读表（官网域 7 + 财务域 8 + 预览/候选表 6 + 其余 4）；`el-drawer` 8 → `LqDrawer`；`el-empty` 18 → `EmptyState` | 中 | `empty-text` 必须改写为 `emptyText` prop（否则被 `#empty` 插槽静默吞掉） |
| **B7-C2** | 弹窗迁移 | `el-dialog` 18 处中先做简单表单/只读（约 12 处），嵌套与复杂预检弹窗延后 | 中 | 宽度用 `width` 原样传；`destroy-on-close`/`@closed` 走 attrs fallthrough |
| **B7-C3** | **核心复杂页单独安排** | `ProductionTrackingView`（66 列主表 + sticky）、`ProductInventoryView`（62 列 + 分组折叠）、`MonthlyCommissionView`（版本快照 + 大量 el-tag） | 高 | **不强制表格一次性迁移**；本批只做：权限补齐、状态/异常统一、分页重复请求修复、令牌化 |
| **B7-C4** | 样式收敛 | 抽 `.lq-card`/`.lq-panel` 公共表面类替换 12 处重复；按文件批次令牌化（顺序：`DeviceVideoLibrary` 35 → `ProductionTracking` 30 → `H3ImportCenter` 29 → `CompanyInfo` 25 → 其余）；删 4 处死规则、3 处死回退、合并 5 处重复 `:deep()` | 低-中 | 需 2 主题 × 2 视口视觉回归；打印/预览例外跳过 |
| **B7-C5** | 标题体系（手写标题页） | 17 页 `.page-header`/`.page-head` → 公共头部；`<h2>`→`<h1>` 修正；`ProductInventoryView` 补标题 | 中 | 需设计确认 eyebrow 文案来源；建议按域（官网 → 生产 → 财务）分批 |

### 7.3 顺序原则（非按组件出现次数机械排序）

1. **同类问题集中**：B7-B1/B7-B2 一次改完所有页面的同一类接线错误（改动小、可批量断言）。
2. **先逻辑后视觉**：P0/P1 逻辑问题先于 P2 样式，避免视觉批次掩盖逻辑回归。
3. **核心业务页单列**：生产跟单、生产库存、月度提成不参与批量迁移批次，单独评估。
4. **大文件/复杂抽屉/可编辑表格不强制**：`ProductionTrackingView`（~1500 行）、`ProductInventoryView`（~900 行）、3 个全屏分析抽屉、`WebsiteProductsView`（嵌套弹窗 + 5 表单）均按模块拆次实施。

---
## 8. 测试和视觉验收建议

### 8.1 当前测试基线（**环境异常，需先恢复**）

| 命令 | 文件数 | 本次结果 |
|---|---:|---|
| `npx vitest run` | 78 | **78 failed / 0 test** |
| `npx vitest run --maxWorkers=2` | 78 | 78 failed |
| `npx vitest run --pool=threads` | 78 | 78 failed |
| `npx vitest run --environment=node` | 78 | 78 failed |
| `npx vitest run --no-file-parallelism` | 78 | 78 failed |
| `npx vitest run --exclude **/__no_such_file_xyz.test.ts` | 78（未真的排除） | 78 failed |
| `npx vitest run --exclude **/textUtils.test.ts` | 77 | **77 failed**（B6-2 时同命令为 **703 passed / 2 failed**） |
| `npx vitest run src/components/common` | 22 | **22 failed** |
| `npx vitest run src/components/common/__tests__/StatusTag.test.ts` | **1** | **1 failed** |

统一报错（每个文件第 1~5 行即触发）：
```
Error: Vitest failed to find the runner.
TypeError: Cannot read properties of undefined (reading 'config')
```

### 8.2 对 B6「78 文件固定上限」结论的**修正**（重要）

B6-1/B6-2 曾据「78 全崩 / 77 正常」推断存在固定文件数量上限。**本轮进一步排查推翻了该结论**：

- **决定性反证 1**：**单个测试文件**（`StatusTag.test.ts`）现在同样以相同错误失败 → 与文件数量无关。
- **决定性反证 2**：B6-2 时通过的同款 77 文件命令（`--exclude **/textUtils.test.ts` → 703 passed / 2 failed）**现在也全崩** → 阈值会漂移，不可能是固定文件数上限。
- **已排除的候选因素**：worker 数量（`--maxWorkers=2` 仍崩）、pool 类型（`threads` 仍崩）、环境类型（`node` 仍崩）、并发度（完全串行仍崩）、机器负载（本次运行仅 11.55s / environment 53s，比 B6 更快仍崩）、`--exclude` 代码路径（排除不存在文件仍崩）、具体文件内容（两个不同文件任一被排除都曾恢复）。
- **未确认的根因**：指向 `node_modules` 局部状态 / Vite 缓存损坏——佐证：仓库根目录残留 **7 个 `vite.config.js.timestamp-*.mjs`**（Vite 进程被异常中断的产物）；`@vitest/*` 子包版本一致（均 4.1.9）但 `@vitest/mocker` 缺失；并行 Agent 在同一仓库执行 npm/lint 操作也可能改动依赖树。
- **建议处置（不在本轮执行）**：① `npm ci` 或重装 `node_modules`；② 清理 `node_modules/.vite`；③ 清理 7 个 `vite.config.js.timestamp-*` 残留；④ 恢复后重跑 `npx vitest run` 重建基线。
- **纪律要求**：环境恢复前，**不得**把「排除某个测试文件」当作门禁通过手段，也不得据此宣称测试全绿。

### 8.3 两条 Tracking 既有失败：继续独立登记

| 失败 | 数量 | 归属 | 处置 |
|---|---:|---|---|
| `components/production/__tests__/TrackingAnalyticsDrilldownCells.test.ts` · 规格拼接（`'120 / 一出四'` vs `'120 / 圆形 / PP / 一出四'`） | 2 | 测试由 `5bc9a80a` 引入、被测组件由 tracking 提交 `4c6d5432` 修改而测试未同步 | **不修改**（非本轮范围，与 UI 统一无依赖关系） |

### 8.4 现有 Playwright 视觉测试可复用性（可用于后续批次）

| 资产 | 状态 |
|---|---|
| `e2e/fixtures/operation-log.{html,ts}` + `e2e/operation-log-standard-list.spec.ts`（9 条） | ✅ 可用，B6-1 改造后 9/9 |
| `e2e/fixtures/office-asset.{html,ts}` + `e2e/office-asset-standard-page.spec.ts`（25 条） | ✅ 可用，B6-2 改造后 25/25 |
| `e2e/harness.html` + `LqPreviewHarness.vue` + 6 个既有 spec（file/production-tracking 等） | ✅ 其它任务在用，不触碰 |

**可直接复用的 4 个模式**（B7-B/C 每批次照抄）：
1. **隔离夹具**：`e2e/fixtures/<page>.html` + `<page>-harness.ts`，只挂真实页面 + ElementPlus + 项目样式，不引 router/store。
2. **Mock API**：`page.route` 拦截全部 `/api/**`，写入类接口一律返回成功 → 不连数据库、无真实写入/打印。
3. **权限双态注入**：`localStorage.setItem('lq.token', …)` + 拦截 `/api/auth/me` 返回 `permissions: []` 或含 `*.manage` → **B7-B2 直接可用**（B6-2 已用它抓到批量权限缺陷）。
4. **请求侧断言**：把「页码 / 筛选参数 / 请求次数」写入 Node 侧数组后断言 → **最适合验收 B7-B1**（不受 UI 影响，且能抓到「重复请求」）。

**踩过的坑（照做可避坑）**：
- 8 worker 并行下偶发 `page.goto` 超时（Vite 即时编译 + 多 Edge 争抢）→ 用 `--workers=2`。
- 截图输出目录用 `playwright-report/<批次>/{before,after}`：`test-results/` 会被 Playwright 每次运行清空，基线会丢。
- Playwright 清理 `test-results/` 达 707 文件时会触发安全删除审批拦截（命令被拦截未执行）→ 用 `--output` 指向独立临时目录绕开批量清理，**不要手工批量删除**。
- Mock 分页响应必须回传请求页码（`number`），否则 `useListPagination` 会把页码同步回 0，导致「翻页」类断言假失败。

### 8.5 各批次验收配方

| 批次 | 必做验收 |
|---|---|
| B7-B1 | 每页 e2e：翻页后搜索 `page=0`、翻页后清除筛选 `page=0`、仅设私有维度时清除按钮可见、操作只触发 1 次请求 |
| B7-B2 | 每页 e2e 双态（有/无权限）：选择列与批量栏显隐、顶部与行内按钮一致 |
| B7-B3 | 每页 e2e：`.page-surface-header h1` 文本 == `title`；前后截图对比 |
| B7-B4 | e2e：编辑→新增无残留（断言 `id` 不存在）、关闭重开无校验红字、延迟回填不覆盖输入 |
| B7-C1/C2 | 逐表/逐弹窗 2 主题 × 2 视口截图 + 表格几何探针；`empty-text`→`emptyText` 专项检查 |
| B7-C4 | 逐文件 2 主题截图 + 构建通过；打印/预览例外跳过 |

---

## 9. 需要补强公共组件的真实能力缺口

以下为**组件库侧缺口**（页面裸用并非违规，见 §5），建议单独立项：

| # | 缺口 | 证据 | 影响面 | 建议 |
|---|---|---|---:|---|
| 1 | **无 `LqButton`** | `components/common/` 下 17 个 `Lq*` 组件，无按钮封装 | 268 处 `el-button` / 33 文件 | 最高优先级新增，统一尺寸/图标间距/loading |
| 2 | `LqPagination` **无 `layout` prop** | `layout` 由 `mode` 计算（full/compact 两档），业务需要 `total, prev, pager, next`（无 sizes） | 阻塞 2 处迁移 | 增加 `layout` prop |
| 3 | `LqTable` 的 `empty-text` 被 `#empty` 插槽吞掉 | `LqTable.vue:22-26` 用插槽接管空态 | 6 张表迁移时会静默失效 | 兼容 `empty-text`→`emptyText`，或写入迁移指南 |
| 4 | `LqDrawer` **无 `fullscreen` prop**（`LqDialog:182` 有） | 3 个分析抽屉用 `size="90%"` | 3 处 | 增加 `fullscreen`；或规范明确用 `width="90%"` |
| 5 | 无 `LqInput` / `LqSelect` | — | 83 + 55 处 | 可选，收益中等 |
| 6 | `StatusTag` 缺 `closable`/`effect`/`@close`/`hit` | 组件仅 `type/label/size/dot` | 86 处 `el-tag`（交互型须保留原生） | 视需要增强，或明确「交互型用原生」 |
| 7 | `SearchToolbarBatchBar` **按钮显隐只看监听器，无权限语义** | B6-2 e2e 实证：无权限也能看到批量编辑/删除 | 6 页 | 公共侧加 `batchActionsVisible`，或规范要求页面必须用 `:batch-enabled="canManageXxx"` 门控 |
| 8 | `#filterChips` 插槽 0 使用 | 8 处 chips 全在默认插槽 | 8 处 | 决策：纳入规范推广，或删除该插槽 |
| 9 | `ListPageLayout` 的 `pagination` prop + `showPagination` 零使用 | 15 页全走 `#pagination` 插槽 | — | 评估删除或文档标注为备用形态（B6-1 已记录） |
| 10 | `useListFilters.resetFilters` **无分页能力** | `useListFilters.ts:28-34` 只重置字段 | 2 页已踩坑（P0-2） | 文档化「调用方必须自行 `resetPage()`」；或在 composable 增加可选分页回调 |
| 11 | 表单生命周期无规范沉淀 | 26 处新增/编辑共用弹窗，2 处校验态遗留、3 处回填覆盖、1 处空默认值 | 全仓 | 沉淀规范（推荐 `useModalForm` 的 `Object.assign(form, defaultForm(), item)` + `clearValidate`），**不强制抽成一个大表单组件** |

---

## 10. 不纳入本轮的范围及原因

| 项 | 原因 |
|---|---|
| 修改任何业务页面 / 公共组件 / 测试代码 | **B7-A 只读审计**，按任务要求只产出清单 |
| 修改 `ListPageLayout.showHeader` 默认值 | 需产品/设计决策（12 页 vs 全站），本轮只提出方案 |
| 生产跟单（`ProductionTrackingView`）、生产库存（`ProductInventoryView`）、月度提成（`MonthlyCommissionView`）的一次性迁移 | 核心业务复杂页，66/62 列主表 + sticky 表头/分组折叠 + 大量自定义 slot；列为 B7-C3 单独安排，**不强制** |
| 3 个全屏分析抽屉（`ProductionTracking*Drawer`）、`WebsiteProductsView` 嵌套弹窗、可编辑表格 | 复杂嵌套结构，不强制一次性迁移 |
| 后端、数据库、Flyway | 超出前端 UI 统一范围 |
| 并行任务文件（官网解耦、tracking 抽屉与文档、`tools/_verify_node_*.java`、`e2e/_tmp-sample.spec.ts`、7 个 `vite.config.js.timestamp-*.mjs`） | 属其它任务，未触碰 |
| `TrackingAnalyticsDrilldownCells.test.ts` 的 2 条失败 | 既有、非本轮引入、与 UI 统一无依赖，独立登记 |
| 恢复 Vitest 环境（`npm ci` / 清理缓存） | 涉及依赖重装，需用户确认，不在只读审计范围；已列为 B7-B 前置条件 |
| 为「凑数」而迁移 | 表格/弹窗迁移以「能力对等 + 视觉回归通过」为准；不等效者保留原生并登记为 §5 例外 |
| 新增/删除测试文件 | 环境未恢复，新增测试无法验证有效性 |

---

## 11. 变更边界核对（本轮）

- 未修改任何业务页面、公共组件、测试代码 —— ✅
- 未修改后端 / 数据库 / Flyway —— ✅
- 未触碰并行任务文件 —— ✅
- 未执行 `git add` / `commit` / `push` / `reset` / `clean` / `stash` —— ✅
- 未删除任何文件（含 `test-results/` 残留与 `vite.config.js.timestamp-*.mjs`）—— ✅
- 未把「排除测试文件」当作门禁通过手段；未宣称测试全绿 —— ✅
- 主动修正了自己在 B6 中「78 文件固定上限」的错误结论，并登记 B6-2 未修完的 P0-7 —— ✅

**新增文件仅本报告：`docs/frontend-ui-b7a-full-adoption-audit.md`。完成 B7-A 后停止，未进入 B7-B，未提交推送。**

