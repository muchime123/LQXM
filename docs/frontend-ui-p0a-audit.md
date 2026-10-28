# 前端统一 UI 体系 P0-A 全仓只读审计

> 阶段性质：**只读审计**。本轮未修改任何业务代码、公共组件或 CSS，未执行迁移、未安装依赖、未提交/推送。
> 生成日期：2026-10-27

---

## 1. 审计基准

### 1.1 版本基准

| 项 | 值 |
|---|---|
| 仓库 | `c:/Users/20676/source/LQXM` |
| 分支 | `main` |
| HEAD SHA | `42bc081c271124620254a7fe79fff55d00a7e904` |
| HEAD 提交标题 | `chore(git): ignore sql dumps and archives` |
| HEAD 提交时间 | 2026-10-27 13:20:15 +0800 |
| `origin/main` SHA | `42bc081c271124620254a7fe79fff55d00a7e904`（与 HEAD 一致） |
| `git status --porcelain` | **空**（工作区干净，无未提交改动） |
| `git diff --stat origin/main HEAD` | **空**（本地与远端 main 零差异） |

结论：本地代码 = GitHub `main` 实际版本，审计报告可直接作为后续整改的基线。

### 1.2 扫描范围

| 范围 | 规模（实测） |
|---|---|
| `frontend/src/**/*.vue` | **194** 个 |
| └ `App.vue` | 1 |
| └ `shell/`（登录 / 403 / 404） | 3 |
| └ `components/common/`（公共组件） | **44** |
| └ 业务组件 `components/<非common>/` | 146 |
| └ `apps/archive/views/`（业务视图，含子目录） | **37** |
| `frontend/src/composables/` | 9 个子目录，**67** 源码 + 20 测试 |
| `frontend/src/utils/` | 69（引自 AGENTS.md 2026-10-22 核对） |
| `frontend/src/styles/` | 16 个 CSS（含 `modules/` 11、`themes/` 2） |
| 前端测试 | `*.test.ts` **68** 个（Vitest）+ `*.spec.ts` 10 个（9 个 Playwright e2e + 1 个 Vitest） |

> 说明：本次**未只扫 `views/`**。按 AGENTS.md 的历史教训（`frontend/src/views/` 早已不存在），视图实体位于 `apps/archive/views/`，同时 146 个业务组件同样承载大量 UI，已一并扫描。

### 1.3 执行方式与统计口径

- 全部为只读检索（`search_content` 正则计数 / `read_file` 逐行核对 / 子代理静态扫描），无副作用命令。
- **「使用次数」口径 A（本文主表）**：某组件名作为**标签**出现在其它 `.vue`/`.ts` 的次数 + 作为 `import` 出现的次数，排除组件自身文件与 `__tests__`。
- **「使用次数」口径 B（绕过统计）**：Element Plus 原生标签出现次数。**已做区分**：`<el-table>` 容器标签与 `<el-table-column>` 列标签分别统计（列标签放在 `LqTable` 插槽内是**合规用法**，不能计入违规）。
- 所有数字均来自实际扫描，无估算。合并计数中若发生输出截断，文中显式标注「≥」。

---

## 2. 已有公共组件与样式资产清单

### 2.1 公共组件（`frontend/src/components/common/`，44 个）

按职责分组：

| 组 | 组件 |
|---|---|
| **表格 / 列表骨架** | `LqTable`、`ListPageLayout`、`DataState`、`EmptyState`、`LoadingState`、`LqSkeleton`、`RouteSkeleton`、`AppStatePage`、`LqPagination`、`SearchToolbar`、`SearchToolbarBatchBar`、`ActiveFilterChips`、`CategorySummaryTabs`、`MasterLayout`/`MasterListPanel`/`MasterCardGrid`（主从框架） |
| **弹窗 / 抽屉 / 浮层** | `LqDialog`、`LqConfirmDialog`、`LqPreview`、`LqDropdown`、`LqTooltip`、`MultiTabBar`、`GlobalStatusBanner`、`ErrorBoundaryOverlay` |
| **表单 / 字段** | `FormSection`、`FieldBatchEditModal`、`LqDatePicker`、`LqTagInput`、`UnitField`、`LqDescriptions` |
| **展示 / 反馈** | `StatusTag`、`MetricCard`、`MetricGrid`、`LqProgress`、`LqTabs`、`LqTimeline`、`LqThumbnail`、`FileTypeIcon`、`FilePreviewBody`、`SourceTrace`、`DueSoonAlert` |
| **布局 / 导航** | `DefaultLayout`、`PageHeader`、`ReturnToSourceButton`、`LqLoading` |
| **业务味较重的公共件** | `VariantCarousel`、`VariantImageManager`（包材规格图片域） |

**关键缺口：`components/common/` 中没有 `LqDrawer`。** 抽屉能力完全缺失，全站 17 个文件各自裸写 `el-drawer`（见 §4.4）。这是本次审计发现的最大结构性缺口。

### 2.2 公共逻辑（`composables/`，9 组 67 源码）

| 目录 | 文件数 | 代表能力 |
|---|---|---|
| `auth/` | 1 | `useCurrentUser`（22 处引用） |
| `catalog/` | 14 | 产品目录拆分编排（几乎由 `ProductCatalogView` 独占，各 1 处引用） |
| `common/` | 5 | `useBatchFlow`、`useDirty`、`useImportFlow`、`useMultiTabs`、`useStatus` |
| `list/` | 5 | `useListPagination`(21)、`useBatchSelection`(9)、`useListFilters`(6)、`useDragSort`(4)、`useBatchActions`(1) |
| `ui/` | 12 | `useToast/notify`(76)、`useConfirmDialog`(34)、`useAppBridge`(6)、`useErrorBoundary`(3)、`useDialogStack`(1)、`useLoading`(1) 等 |
| `upload/` | 9 | 分层：`useFileUpload` → `useRegisteredFileUpload` → `useFileUploadWorkflow`(9) → 业务包装 |
| `packaging/` | 9 | 包材规格域 |
| `tracking/` | 9 | 生产跟单域（`useTrackingDetail` 34KB） |
| `mold/` | 2 | 模具形状匹配 |

### 2.3 样式资产（`frontend/src/styles/`）

入口链：`main.ts:7` → `styles/index.css`（唯一聚合器）→ 按「令牌 → 响应式 → 重置 → Element Plus → 工具类 → 无障碍 → 路由进度 → 主题 → modules → 页面级」顺序 `@import`。

| 文件 | 大小 | 职责 |
|---|---|---|
| `tokens.css` | 14.03 KB | **唯一设计令牌源，324 个 CSS 变量** |
| `responsive.css` | 1.89 KB | 三档断点收紧语义间距令牌 |
| `reset.css` | 505 B | 极简重置 |
| `element-plus.css` | 15.42 KB | 用项目 token 覆盖 EP（160 处映射） |
| `utilities.css` | 11.54 KB | `.btn` 全系、空状态、Loading、LqProgress、动画、卡片、表单错误摘要 |
| `accessibility.css` | 471 B | focus-visible / reduced-motion |
| `route-progress.css` | 988 B | 路由进度条 |
| `themes/light.css` | 171 B | 空壳（值在 `:root`） |
| `themes/dark.css` | 5.69 KB | 115 处暗色覆盖 |
| `master-layout.css` | 5.24 KB | 主从工作台布局 |
| `modules/*.css` | 11 个 | 见 §7.2 |

另有 **19 个 `.vue` 在组件内 `@import`** 业务级 CSS（绕过 `index.css` 层次约定），其中 `packaging-detail-drawer.css` 42.78 KB、`media-account-view.css` 35.48 KB。

### 2.4 设计令牌（`tokens.css`，324 个变量）

| 分组 | 数量 | 代表变量 |
|---|---|---|
| 颜色（主色/侧栏/表面/边框/文字/状态/杂项/EP 色阶） | ~150 | `--color-primary(#0f766e)`、`--color-text-primary(#1d252f)`、`--ep-primary-*` |
| 间距 | 11 + 8 语义别名 | `--space-xs(4)`…`--space-3xl(32)`、`--space-control-gap` |
| 语义尺寸 | 11 | `--control-height-*`、`--toolbar-min-height`、`--card-padding`、`--content-max-width` |
| 圆角 / 边框 | 8 + 5 | `--radius-xs(2)`…`--radius-pill`、`--border-width-*` |
| 阴影 | 7 + 7 色分量 | `--shadow-xs`…`--shadow-drawer`、`--shadow-color-*` |
| 字号 / 字重 / 行高 / 排版组合 | 14 + 7 + 8 + 9 | `--fs-3xs(10)`…`--fs-7xl(36)`、`--text-body: 14px/1.5` |
| 过渡 / 缓动 / 时长 | 3 + 5 + 6 + 6 | `--transition-fast`、`--transition-modal` |
| 层级 `z-index` | **13** | `--z-base(1)`…`--z-toast(4000)`、`--z-max(9999)` |
| 弹窗宽度 | 5 | `--dialog-width-sm/md/lg/xl/workspace` |
| 废弃兼容别名 | 38 | `--color-background`、`--space-1..5` 等（指向规范令牌） |

---

## 3. 公共组件使用次数与调用文件清单

> 口径 A（标签 + import）。路径相对 `frontend/src`。缩写：`C/`=`components/`，`A/`=`apps/archive/views/`，`common/`=`components/common/`。

### 3.1 核心组件（高频）

| 组件 | 使用文件数 | 使用点 | 调用文件清单 |
|---|---:|---:|---|
| **LqDialog** | **55** | **129**（标签 75 处 / 56 个 .vue） | `A/system/EmployeeRosterView`、`A/production/ProductionTrackingView`(9)、`A/product/OfficeAssetView`(4)、`A/product/DeviceVideoLibraryView`(4)、`A/marketing/WebsiteInquiryView`、`C/production/TrackingDetailDrawer`(5)；`C/product/*` 7 个、`C/packaging/*` 8 个、`C/media/*` 8 个、`C/company/*` 8 个、`C/mold/*` 3 个、`C/h3/H3ImportPreviewDialog`、`C/expense/ExpenseImportDialog`、`C/employee/EmployeeDetailPanel`、`C/customer/*` 3 个、`C/certificate/*` 2 个、`common/{LqConfirmDialog,VariantImageManager,FieldBatchEditModal}` |
| **DataState** | 15 | 33 | `C/reimbursement/{ReimbursementLedgerPanel,DingtalkSyncPanel}`、`C/packaging/PackagingMoldsMasterDetail`、`C/mold/MoldAccessoriesPanel`、`C/master/{MasterListPanel,MasterCardGrid}`、`C/company/CompanyCertificationsTab`、`A/system/{OperationLogView,DepartmentManagementView}`、`A/settlement/CashflowJournalView`、`A/product/{OfficeAssetView,CertificateView}`、`A/marketing/{WebsiteInquiryView,MediaAccountView}`、`common/ListPageLayout` |
| **StatusTag** | 13 | 34 | `C/employee/EmployeeDetailPanel`、`C/customer/CustomerDetailPanel`、`C/company/CompanyCertificationsTab`、`C/packaging/PackagingMoldsMasterDetail`、`C/media/MediaPlatformConfigModal`、`C/certificate/{CertListTable,CertDetailModal}`、`A/system/OperationLogView`、`A/settlement/cashflow/{CashflowIssueTable,CashflowAccountTable}`、`A/product/OfficeAssetView`、`A/marketing/MediaAccountView`、`common/LqDescriptions` |
| **LqPagination** | **15** | 30 | `C/reimbursement/*` 2、`C/packaging/PackagingMoldsMasterDetail`、`C/master/MasterListPanel`、`C/company/CompanyCertificationsTab`、`common/ListPageLayout`、`A/system/OperationLogView`、`A/settlement/CashflowJournalView`、`A/production/ProductionTrackingView`、`A/product/{OfficeAssetView,DeviceVideoLibraryView,CertificateView}`、`A/marketing/{WebsiteInquiryView,WebsiteBannerView,MediaAccountView}` |
| **SearchToolbar** | **14** | 32 | `C/reimbursement/ReimbursementLedgerPanel`、`C/company/CompanyCertificationsTab`、`C/packaging/PackagingMoldsPanel`、`A/customer/CustomerView`、`A/system/{OperationLogView,EmployeeRosterView}`、`A/settlement/CashflowJournalView`、`A/product/{ProductCatalogView,PackagingView,OfficeAssetView,CertificateView}`、`A/product/mold/PackagingMoldView`、`A/marketing/{WebsiteInquiryView,MediaAccountView}` |
| **LqTable** | **13** | 27（标签 14 处） | `C/reimbursement/{ReimbursementLedgerPanel,DingtalkSyncPanel}`、`C/mold/MoldImportPreviewTable`、`C/certificate/CertListTable`、`C/company/CompanyCertificationsTab`、`A/system/{OperationLogView,DepartmentManagementView}`、`A/settlement/cashflow/{CashflowIssueTable,CashflowEntryTable,CashflowAccountTable}`、`A/product/OfficeAssetView`、`A/marketing/{WebsiteInquiryView,MediaAccountView}` |
| **ListPageLayout** | 12 | 24 | `C/reimbursement/*` 2、`A/system/{ReimbursementCenterView,OperationLogView,DepartmentManagementView}`、`A/settlement/CashflowJournalView`、`A/product/{OfficeAssetView,CertificateView}`、`A/marketing/{WebsiteInquiryView,MediaAccountView,CompanyInfoView,CompanyCertificationsView}` |
| **MetricCard** | 9 | 40 | `C/reimbursement/*` 2、`C/customer/CustomerDetailPanel`、`A/settlement/CashflowJournalView`、`A/production/ProductionTrackingWeeklyReviewDrawer`、`A/product/OfficeAssetView`、`A/marketing/{WebsiteSolutionsView,WebsiteInquiryView,WebsiteDashboardView}` |
| **FormSection** | 9 | 39 | `C/product/*` 4、`C/media/MediaAccountEditorModal`、`C/mold/MoldFormDialog`、`C/customer/CustomerFormModal`、`C/company/{BankAccountEditorModal,CompanyInfoEditorModal}` |
| **ActiveFilterChips** | 9 | 19 | `C/company/CompanyCertificationsTab`、`A/system/EmployeeRosterView`、`A/settlement/CashflowJournalView`、`A/product/{OfficeAssetView,CertificateView}`、`A/product/mold/PackagingMoldView`、`A/marketing/{WebsiteInquiryView,MediaAccountView}`、`A/customer/CustomerView` |
| **MetricGrid** | 8 | 17 | `C/reimbursement/*` 2、`A/settlement/CashflowJournalView`、`A/production/ProductionTrackingWeeklyReviewDrawer`、`A/product/OfficeAssetView`、`A/marketing/{WebsiteSolutionsView,WebsiteInquiryView,WebsiteDashboardView}` |
| **EmptyState** | 8 | 17 | `C/company/{CompanyKnowledgeTab,CompanyBrandAssetsTab,CompanyAccountsTab}`、`A/system/EmployeeRosterView`、`A/product/PackagingView`、`common/{LqPreview,DataState,LqTable}` |
| **FieldBatchEditModal** | 7 | 14 | `C/company/CompanyCertificationsTab`、`A/product/{PackagingView,OfficeAssetView,CertificateView}`、`A/product/mold/PackagingMoldView`、`A/marketing/MediaAccountView`、`A/customer/CustomerView` |

### 3.2 中低频组件

| 组件 | 使用文件数 | 使用点 | 调用文件 |
|---|---:|---:|---|
| `LqDescriptions` | 6 | 13 | `C/packaging/PackagingSpecDetailPanel`、`C/mold/MoldQualityPanel`、`C/media/MediaAccountDetailModal`、`C/employee/EmployeeDetailPanel`、`C/customer/CustomerDetailPanel`、`A/product/OfficeAssetView` |
| `LoadingState` | 6 | 13 | `C/company/*` 3、`A/marketing/CompanyInfoView`、`common/{RouteSkeleton,DataState}` |
| `LqDatePicker` | 5 | 14 | `C/certificate/FeeRecordsPanel:74,106`、`C/certificate/CertDetailModal:385,389`、`A/system/EmployeeRosterView:143,144`、`common/FieldBatchEditModal:74`、`A/product/OfficeAssetView:171,177` |
| `LqTagInput` | 5 | 10 | `C/company/{TagEditorDialog,ArticleEditorModal}`、`C/packaging/PackagingTagEditor`、`common/FieldBatchEditModal`、`C/media/MediaAccountEditorModal` |
| `CategorySummaryTabs` | 4 | 9 | `A/product/CertificateView`、`C/product/AssetToolbar`、`C/packaging/PackagingAssetPanel`、`C/company/CompanyCertificationsTab` |
| `LqPreview` | 3 | 6 | `App.vue:34`、`C/packaging/PackagingMoldsMasterDetail`、`C/packaging/PackagingDetailDrawer` |
| `LqSkeleton` | 3 | 8 | `C/packaging/PackagingMoldsMasterDetail`、`common/{RouteSkeleton,DataState}` |
| `LqTabs` | 3 | 7 | `C/company/CompanyInfoEditorModal`、`C/packaging/PackagingMoldsMasterDetail`、`A/settlement/CashflowJournalView` |
| `LqThumbnail` | 3 | 7 | `C/company/CompanyBrandAssetsTab`、`C/product/AssetGrid`、`C/packaging/PackagingAssetPanel` |
| `LqDropdown` | 3 | 7 | `common/MultiTabBar`、`A/product/mold/PackagingMoldView`、`A/marketing/MediaAccountView` |
| `ReturnToSourceButton` | 3 | 6 | `A/system/EmployeeRosterView:22`、`A/governance/H3ImportCenterView:89`、`A/customer/CustomerView:30` |
| `AppStatePage` | 2 | 4 | `shell/NotFoundView`、`shell/ForbiddenView` |
| `DueSoonAlert` | 2 | 4 | `C/company/CompanyCertificationsTab:12`、`A/product/CertificateView:12` |
| `SourceTrace` | 2 | 4 | `C/product/AssetGrid:175`、`C/mold/MoldQualityPanel:72` |
| `VariantCarousel` | 2 | 4 | `C/packaging/PackagingVariantCards:350`、`C/packaging/PackagingSpecDetailPanel:24`（+ `packaging-detail-drawer.css:429,1230`） |

### 3.3 单一使用方（准孤儿，16 个）

| 组件 | 唯一使用文件 | 备注 |
|---|---|---|
| `DefaultLayout` | `App.vue` | 外壳，合理 |
| `ErrorBoundaryOverlay` | `App.vue:79` | 外壳，合理 |
| `LqLoading` | `App.vue:80` | 外壳，合理 |
| **`LqConfirmDialog`** | `App.vue:62` | **组件只有 1 处标签用法**；真正的确认能力走 `composables/ui/useConfirmDialog`（**34 处引用**），形成组件/组合式双轨 |
| `RouteSkeleton` | `App.vue:20` | 外壳，合理 |
| `GlobalStatusBanner` | `common/DefaultLayout.vue:207,218` | 合理 |
| `LqTooltip` | `common/DefaultLayout.vue:80,220` | 通用组件仅外壳用 |
| `MultiTabBar` | `common/DefaultLayout.vue:205,219` | 合理 |
| `SearchToolbarBatchBar` | `common/SearchToolbar.vue:124` | 合理（内嵌） |
| `FilePreviewBody` | `common/LqPreview.vue:92` | 合理（内嵌） |
| `FileTypeIcon` | `common/LqThumbnail.vue:38` | 合理（内嵌） |
| `PageHeader` | `C/master/MasterLayout.vue:3` | 主从框架内 |
| **`VariantImageManager`** | `C/packaging/PackagingDetailDrawer:404` | 业务特性进 common |
| **`LqProgress`** | `A/product/ProductCatalogView:243` | 业务特性进 common |
| **`LqTimeline`** | `A/product/OfficeAssetView:195` | 业务特性进 common |
| **`UnitField`** | `A/product/OfficeAssetView:250,259,268` | 业务特性进 common |

**0 使用点的孤儿组件：无**（44 个组件全部至少被 1 个非测试文件使用；已用两种搜索方式二次确认，`main.ts` 无 `app.component()` 全局注册，不存在隐式用法漏判）。

### 3.4 公共逻辑使用热度（Top）

| 能力 | 资产 | 引用站点数 |
|---|---:|---|
| 消息提示 | `composables/ui/useToast.ts`（`notify`） | **76** |
| 确认弹窗 | `composables/ui/useConfirmDialog.ts`（`askConfirm`） | **34** |
| 当前用户/权限 | `composables/auth/useCurrentUser.ts` | 22 |
| 分页状态 | `composables/list/useListPagination.ts` | 21 |
| 文件 URL | `utils/ui/fileAssetUrls.ts` | 19 |
| 多选状态 | `composables/list/useBatchSelection.ts` | 9 |
| 上传工作流 | `composables/upload/useFileUploadWorkflow.ts` | 9 |
| 筛选基座 | `composables/list/useListFilters.ts` | 6 |

---

## 4. 绕过公共组件的页面清单与原因

### 4.1 表格：裸 `<el-table>` 容器

- `<el-table-column>`：**49 个文件**（列标签写在 `LqTable` 插槽内属合规，不计违规）。
- **裸 `<el-table>` 容器：38 个业务文件，共 100 处**（另有 1 处在 `common/LqTable.vue` 自身，属正常实现）。
- 对比：`LqTable` 仅 **13 个文件 / 14 处**。**裸用是复用量的 7.7 倍。**

Top 违规文件（裸容器处数）：

| 文件 | 裸 `<el-table>` |
|---|---:|
| `components/h3/H3LedgerPanel.vue` | 21 |
| `apps/archive/views/settlement/commission/MonthlyCommissionView.vue` | 10 |
| `apps/archive/views/production/ProductionTrackingView.vue` | 8 |
| `apps/archive/views/production/ProductInventoryView.vue` | 8 |
| `components/customer/CustomerDistributionDialog.vue` | 4 |
| `apps/archive/views/settlement/CashflowEntitiesView.vue` | 4 |
| `apps/archive/views/governance/H3ImportCenterView.vue` | 4 |
| `components/production/ManufacturingTaskConfigPanel.vue` | 3 |
| `components/h3/H3ImportPreviewDialog.vue` | 3 |
| `apps/archive/views/production/{ProductionTrackingWeeklyReviewDrawer,ProductionTrackingAnalyticsDrawer}.vue` | 各 3 |
| 其余 27 个文件 | 各 1 |

**原因分析（非臆断，基于代码事实）**：
1. `LqTable` 未提供 `span-method` / 树形 / 多级表头 / 虚拟滚动等能力的显式支持声明，业务不确定能否用 → 直接裸写。
2. `LqTable` 的 `data: any[]`（`common/LqTable.vue:41`）导致类型不友好，复杂表格列渲染无类型收益。
3. 历史包袱：`H3LedgerPanel`（1900+ 行，241 个列标签）、`MonthlyCommissionView`（98 KB）在 `LqTable` 成型前即已存在，从未回迁。
4. 抽屉/弹窗内的小表格（1 处）被认为是「轻量场景」，不值得用 `LqTable`。

### 4.2 弹窗：裸 `<el-dialog>`

- **22 个业务文件，38 处**裸 `<el-dialog>`（另有 `styles/modules/dialog-layout.css` 选择器 1 处、`common/LqDialog.vue` 自身 1 处）。
- 对比：`LqDialog` **56 个 .vue 文件 / 75 处**。即：公共弹窗已成主流，但仍有 22 个文件未接入。

代表文件：`A/settlement/commission/MonthlyCommissionView.vue`（5 处：866/1003/1041/1131/1185）、`A/settlement/CashflowEntitiesView.vue`（87/111/133，各写 `width="460px/760px/640px"`）、`A/system/{DepartmentManagementView:85,108, RolePermissionView:609,641}`、`A/governance/H3ImportCenterView.vue`（173/254，自写 `width="min(980px,94vw)" destroy-on-close`）、`A/production/ProductInventoryView.vue`（366/396，自写 `append-to-body`）、`components/website/*`、`components/media/*` 部分弹窗。

**原因**：
1. `LqDialog` 的 `size` 只有 `sm/md/lg/xl` 四档（420/720/1100/min(1400,90vw)），业务需要 `620px`、`520px`、`min(680px,92vw)` 等中间尺寸时，倾向自写 `width`；而 `LqDialog` 虽支持 `width` prop 覆盖，但知晓率低。
2. 需要 `destroy-on-close`、`append-to-body`、自定义 footer 布局（`drawer-footer`/`dialog-footer`）时，业务认为 `LqDialog` 表达力不足。
3. `close-on-click-modal` / `append-to-body` 组合共 **48 处**手写，说明「弹窗行为约定」没有被公共组件完全收编。

### 4.3 分页：裸 `<el-pagination>`

- **11 个业务文件，11 处**（`common/LqPagination.vue` 自身不计）：
  `A/settlement/cashflow/CashflowReviewDrawer:51`、`components/website/WebsiteMediaPicker:90`、`A/production/ProductionTrackingWeeklyReviewDrawer:44`、`A/production/ProductionTrackingAnalyticsDrawer:34`、`A/production/ProductInventoryView:215`、`A/marketing/WebsiteProductsView:110`、`A/marketing/WebsiteMediaView:108`、`A/governance/H3SalesProductAnalysisView:119`、`A/governance/H3ImportCenterView:268`、`components/h3/H3LedgerPanel:800`、`components/h3/H3ImportBatchPanel:118`。

**根本原因（组件设计缺陷，非业务偷懒）**：`LqPagination` 的 props 是 `pagination: ListPagination`（`common/LqPagination.vue:26`），**强耦合 `useListPagination()` 返回对象**，`ListPageLayout` 亦如此（`common/ListPageLayout.vue:130`）。而这 11 个文件的分页状态多为「局部 ref」（如抽屉内下钻分页 `drilldownPage`、「0 基 vs 1 基」不一致：有的 `page + 1`、有的 `v-model:current-page`），无法直接构造 `ListPagination` → 只能裸写。**这是「公共组件能力缺口导致绕过」的典型证据，整改必须优先解耦。**

### 4.4 抽屉：无公共组件，100% 裸写

- **17 个文件，17 处 `<el-drawer>`**，且 `components/common/` **不存在 `LqDrawer`**：

`A/system/RolePermissionView:425`、`A/settlement/commission/MonthlyCommissionView:665`、`A/settlement/cashflow/CashflowReviewDrawer:2`、`A/production/ProductionTrackingWeeklyReviewDrawer:2`、`A/production/ProductionTrackingAnalyticsDrawer:2`、`A/production/ProductInventoryView:222`、`A/governance/H3ImportCenterView:284`、`components/reimbursement/{ReimbursementLedgerPanel,DingtalkSyncPanel}`、`components/production/{TrackingSourceDetailDrawer:2,TrackingDetailDrawer:3,ProductOrderHistoryDrawer,ProductInventoryDetailDrawer,ProductBenchmarkSamplesDrawer,ManufacturingTaskConfigPanel,CoverageBreakdownDrawer}`、`components/h3/H3LedgerDetailDrawer:2`。

各自手写的属性：`size="min(1120px, 98vw)"`、`size="50%"`、`size="90%" :with-header="false"`、`append-to-body`、`header-class="tracking-drawer-header"`，以及 2 处自绘 `drawer-footer`（`RolePermissionView:591`、`TrackingDetailDrawer:197`）。
**结论：抽屉是全站唯一「完全没有公共层」的高频 UI 形态。**

### 4.5 空状态：裸 `<el-empty>` / 自绘

- **39 处 `<el-empty>`，跨 23 个文件（业务文件 17 个）**；另有 3 处 `empty-text="…"`、1 处 `<p class="empty">暂无记录</p>`。
- 对比：`EmptyState` 仅 8 文件 / 17 使用点（其中 3 处是公共组件内部互相调用），业务页面直接使用者仅 **5 个**。

代表：`A/production/ProductInventoryView.vue`（5 处：210/259/284/344/355）、`components/production/ManufacturingTaskConfigPanel.vue`（4 处）、`components/employee/EmployeeDetailPanel.vue`（3 处）、`components/production/TrackingDetailDrawer.vue`（3 处）、`A/settlement/commission/MonthlyCommissionView.vue`（6 处中文空态文案）、`components/customer/CustomerDistributionDialog.vue`（382/417/465 `empty-text`）。

### 4.6 搜索栏：自绘

- **约 38 处**自绘搜索/筛选栏（`SearchToolbar` 仅 14 个文件使用）。

代表（文件:行号）：
`A/production/ProductInventoryView.vue:27`（`<!-- 搜索栏 --> <div class="toolbar">`，:45 自带「查询」按钮）
`A/production/ProductionTrackingView.vue:26`（`.toolbar`，:43 查询 / :44 重置）
`A/governance/H3SalesProductAnalysisView.vue:57,65`（`.filter-field` / `.filter-actions`）
`A/governance/H3ImportCenterView.vue:256`（`.mapping-toolbar`）
`components/h3/H3LedgerPanel.vue:3`（`.ledger-toolbar`，:14 查询）
`A/marketing/WebsiteBannerView.vue:146`（`.filter-bar`）
**`A/product/DeviceVideoLibraryView.vue:41,74`（完全脱离 Element Plus，用原生 `<input type="search">` + 5 个原生 `<select>`，两行）**
`A/settlement/commission/MonthlyCommissionView.vue:31,69`（两行 `.filter-row`）
`A/system/DepartmentManagementView.vue:10`、`components/company/{CompanyAccountsTab:5,CompanyBrandAssetsTab:15,CompanyKnowledgeTab:40}`、`components/product/FieldManagerDialog.vue:13`、`components/packaging/PackagingDetailDrawer.vue:46`、`components/media/ImportUpdatePreviewModal.vue:9` 等。

> 排除项（**合规**）：`A/system/EmployeeRosterView.vue:9`、`A/customer/CustomerView.vue:9`、`A/product/OfficeAssetView.vue:33`、`A/marketing/WebsiteInquiryView.vue:24`、`A/settlement/CashflowJournalView.vue:71`、`A/marketing/MediaAccountView.vue:21` 的自绘 `*-toolbar` div **内部包的是 `SearchToolbar`**，属页面外壳，不计违规。

### 4.7 批量操作栏：自绘

约 **14 处**自绘「全选 / 已选 N 项 / 批量删除 / 批量编辑」栏，而 `SearchToolbarBatchBar` 只被 `SearchToolbar` 内嵌使用：

`components/product/AssetToolbar.vue:20`（`{{ isAllSelected ? '取消全选' : '全选' }}`）、`components/company/CompanyBrandAssetsTab.vue:29,30,43`、`components/packaging/PackagingAssetPanel.vue:494`、`components/packaging/PackagingDetailDrawer.vue:119,143,151`、`components/product/AssetGrid.vue:37`、`components/mold/MoldImportDialog.vue:47,48`、`components/media/ImportUpdatePreviewModal.vue:103`、`components/certificate/CertListTable.vue:13`、`A/product/OfficeAssetView.vue:95`、`A/marketing/WebsiteInquiryView.vue:76`、`A/product/DeviceVideoLibraryView.vue:165`、`A/production/ProductionTrackingView.vue:288,422`。

另有 **23 个文件自建本地 `selectedIds/selectedRows` 状态**未收敛到 `useBatchSelection`（`DeviceVideoLibraryView` 21 处、`MediaAccountView` 21 处、`PackagingAssetPanel` 20 处、`CompanyBrandAssetsTab` 20 处、`OfficeAssetView` 9 处等）。

---

## 5. 重复实现的代码证据

> 路径相对 `frontend/src`，行号来自实际扫描。

### 5.1 重复最严重的 10 个文件

| # | 文件 | 重复类别 | 证据 |
|---|---|---|---|
| 1 | `apps/archive/views/production/ProductInventoryView.vue` | 表格+分页+空态+弹窗+抽屉+搜索栏 | `:27` 自绘 `.toolbar`、`:45` 查询按钮；`:215` 自绘分页；`:210/259/284/344/355` 5 处 `el-empty`；`:366/396` 裸 dialog（:366 手写 `append-to-body`）、`:222` 裸 drawer；8 处裸 `el-table` |
| 2 | `apps/archive/views/governance/H3ImportCenterView.vue` | 搜索栏+分页+空态+弹窗 | `:256-259` `.mapping-toolbar`+查询、`:268` 自绘分页、`:308` `el-empty`、`:173/254` 裸 dialog（自写 `width="min(980px,94vw)" destroy-on-close`）、`:284` 裸 drawer；4 处裸 `el-table` |
| 3 | `apps/archive/views/production/ProductionTrackingView.vue` | 搜索栏+表格+批量栏+深度覆盖 | `:26/43/44` 自绘 toolbar+查询/重置；8 处裸 `el-table`；`:288` 自绘全选、`:422` 批量归档；`:939` 注释自曝「`append-to-body` 导致 `:deep` 失效」 |
| 4 | `apps/archive/views/settlement/commission/MonthlyCommissionView.vue` | 弹窗最密集+筛选行+表格 | 5 个裸 `el-dialog`（`:866/1003/1041/1131/1185`）+ 1 个 `el-drawer`（`:665`）；`:31/69` 两行 `.filter-row`；10 处裸 `el-table`；6 处自写中文空态 |
| 5 | `components/h3/H3LedgerPanel.vue` | 表格+搜索栏+分页 | 1900+ 行，241 个 `el-table-column`、21 处裸 `el-table`；`:3` `.ledger-toolbar` + `:14` 查询；`:800` 自绘分页；自带 18 组搜索 placeholder 配置 |
| 6 | `apps/archive/views/governance/H3SalesProductAnalysisView.vue` | 筛选行+分页+空态 | `:46-65` 全自绘 `.filter-field/.filter-actions`；`:119` 私有 `.pagination` div；`:11/70` 2 处 `el-empty` |
| 7 | `apps/archive/views/settlement/cashflow/CashflowReviewDrawer.vue` | 四合一 | `:2` `:with-header` 抽屉自绘标题栏、`:8` `.cf-review-toolbar`、`:21` `el-empty`、`:51` 自绘分页 |
| 8 | `apps/archive/views/product/DeviceVideoLibraryView.vue` | 完全脱离组件体系 | `:41/74` 两行 `.filter-row` 用**原生** `<input type="search">` + 5 个原生 `<select>`；`:165` 自绘「本页全选」；47 处硬编码色 + 47 处 `font-size: Npx` |
| 9 | `apps/archive/views/marketing/WebsiteSolutionsView.vue` | 搜索栏+表格壳+空态+覆盖 | `:197` `.section-toolbar`、`:206` `.section-table-card`、`:259` `el-empty`、`:deep(.el-table)` 覆盖 |
| 10 | `components/production/TrackingDetailDrawer.vue` | 抽屉+空态 | `:3` 裸 drawer 自定 `size="min(1120px,98vw)" header-class append-to-body`、`:197/549` 自绘 `.drawer-footer`、`:151/179/192` 3 处 `el-empty`、`:deep(.el-table)` |

紧随其后：`ProductionTrackingWeeklyReviewDrawer.vue` / `ProductionTrackingAnalyticsDrawer.vue`（各 `:with-header="false"` 自绘标题 + 自绘分页 + `:deep(.el-table)`）、`components/production/ManufacturingTaskConfigPanel.vue`（4 处 `el-empty` + 3 个 drawer/dialog + `:deep(.el-table)`）、`components/employee/EmployeeDetailPanel.vue`（3 处 `el-empty` + 2 个 toolbar）。

### 5.2 逻辑层重复（composables / utils）

| 能力域 | 平行实现 | 证据 |
|---|---|---|
| **防抖搜索** | **3 套** | `common/SearchToolbar.vue:246,257`（组件内 `debounceTimer` + `setTimeout`）、`list/useListFilters.ts`（`debouncedSearch`，默认 300ms）、`catalog/useCatalogFilters.ts`（自带 `searchDebounceTimer`）。三处各自维护 `setTimeout`，**无共享 debounce util** |
| **筛选条件组装** | **4 套** | `list/useListFilters.ts`、`catalog/useCatalogFilters.ts`、`packaging/useVariantFilters.ts`、`tracking/useTrackingFilters.ts`，各自定义筛选对象、`hasActiveFilters`、重置逻辑，**无统一 `useFilters`** |
| **错误反馈** | **4 层** | `ui/useToast.ts`（`notify`，76 处）+ `ui/useErrorBoundary.ts`(`captureError`)+ `utils/ui/errorHandler.ts`(`errorMessage`)+ `api/http.ts` 拦截器；同时 **36 个文件直接调用 `ElMessage/ElMessageBox/ElNotification`** 绕过封装（`useTrackingDetail.ts` 33 处、`useTrackingWorkbook.ts` 32 处、`PackagingMoldsMasterDetail.vue` 21 处、`MoldFormDialog.vue` 21 处、`RolePermissionView.vue` 18 处） |
| **确认交互** | **3 条路径** | `composables/ui/useConfirmDialog.ts`（34 处）+ `common/LqConfirmDialog.vue`（组件仅 `App.vue` 1 处）+ 直接 `ElMessageBox.confirm`（含在上表 36 文件内） |
| **日期格式化** | **4 处同名导出 + 5 处组件私写** | `utils/common/dateUtils.ts`(`formatDate/formatDateTime/fmtDate/fmtLocaleDate`)、`utils/tracking/week.ts`(`formatDate`)、`utils/h3ImportUi.ts`(`formatDateTime`)、`utils/tracking/rules.ts`(`formatTime`)；组件内私写：`EmployeeDetailPanel.vue`、`MoldQualityPanel.vue`、`CompanyBrandAssetsTab.vue`、`WebsiteInquiryView.vue`、`ErrorBoundaryOverlay.vue` |
| **上传** | 分层清晰但有冗余 | `upload/useLocalDropUpload.ts` 头部注释自认「与 `useFileUploadWorkflow` 拖拽逻辑同源」仍单拉一份；`useFileAssetActions.ts` 与 `useFileAssets.ts` 各写一遍批量/部分失败提示；`useCatalogFileUpload.ts` 与 `usePackagingUpload.ts` 是同模式两份业务副本 |
| **表格选中** | 通用 1 套 + 23 处本地实现 | `list/useBatchSelection.ts`(9) vs 23 个文件自建 `selectedIds/selectedRows/selectedKeys` |
| **请求态** | **缺失** | 全仓**无** `useRequest`/`useAsync`/`useApi`（0 处），各组件手写 `loading = ref(false)`；`ui/useLoading.ts` 的 `withLoading()` **0 处调用** |
| **权限** | **缺失统一层 + 5 处白名单分散** | 无 `usePermission`/`v-permission`；口径分散在 `apps/archive/routes.ts`(32)、`navigation.ts`(30)、`RolePermissionView.vue`(23)、`api/accessControl/index.ts`(11)、`utils/defaultLanding.ts`(13)、`utils/tracking/detailLink.ts`(`TRACKING_READ_PERMISSIONS`) |
| **抽屉/弹窗开关** | **4 个各管一种** | `ui/useAssetPreview.ts`(1)、`ui/useModalForm.ts`(2)、`ui/useDialogStack.ts`(1)、`ui/useConfirmDialog.ts`(34)；无统一 `useDrawer/useDisclosure`（0 处）；`useDialogStack` 名为「通用弹窗栈」仅 1 处使用，名不副实 |

**已确认死代码**：`utils/trackingListColumns.ts`（生产代码 0 引用，仅自己的测试引用；与 `composables/tracking/useTrackingListColumns.ts` 同名近似易误判）、`useLoading.withLoading()`（0 调用）、`upload/useFileAssetCapabilities.ts`（0 业务引用）。

---

## 6. 公共组件自身的功能与架构缺陷

### 6.1 `LqDialog`（影响面最大：55 文件 / 75 处标签）

| 编号 | 缺陷 | 证据 | 风险 |
|---|---|---|---|
| D-1 | **关闭事件双路径 + 状态兜底 hack**。`requestClose()` 先 `emit('update:modelValue', false)`，再 `nextTick` 检查若仍未关闭则补发 `emit('close')`，并用模块级 `suppressNextClose` 标志压制重复派发 | `LqDialog.vue:250,258-271,274-280` | 消费者监听 `@close` 时可能收到 **1 次或 2 次**，取决于是否响应 `update:modelValue`；`suppressNextClose` 是可变闭包状态，嵌套/并发关闭时序下不可靠 |
| D-2 | `beforeClose` 语义三态混杂：`false` / `Promise<false>` / `true`（立即关）/ `void`（调用方持有 done） | `LqDialog.vue:291-318` | 业务易写错返回类型导致「关不掉」或「绕过脏数据拦截」 |
| D-3 | `LqConfirmDialog.handleCancel()` 同时 `emit('cancel')` 与 `emit('update:modelValue', false)`，注释明确写着「这里再补一次 close 会让消费者收到两次」 | `LqConfirmDialog.vue:194-207` | 作者已意识到双派发风险，但未彻底解决，仅注释规避 |
| D-4 | `showFooter` 默认 `undefined`，靠 `mode !== 'detail'` 推导；同时 `mode` 默认又是 `'form'` | `LqDialog.vue:194,201,232-235` | 默认行为隐含耦合，新增 mode 易踩坑 |
| D-5 | 尺寸档位仅 4 档，业务需要中间尺寸时倾向绕过 | `LqDialog.vue:220-230` 映射 `--dialog-width-sm/md/lg/xl` | 助推 §4.2 的 22 个文件裸写 `el-dialog` |

### 6.2 `LqTable`

| 编号 | 缺陷 | 证据 |
|---|---|---|
| T-1 | **类型退化**：`data: any[]`，`row-click`/`selection-change`/`rowClassName` 全为 `any` | `LqTable.vue:41,49,62-67` |
| T-2 | 仅转发 4 个事件（`selection-change`/`sort-change`/`row-click`/`row-dblclick`），`el-table` 的 `expand-change`、`current-change`、`filter-change`、`header-dragend`、`select`、`select-all` **全部未转发**，`v-bind="$attrs"` 也不覆盖事件 | `LqTable.vue:10-15,62-67` |
| T-3 | 未声明 `height/max-height`、`show-summary`、`span-method`、`tree-props`、`default-expand-all`、`scrollbar-always-on` 等常用能力的支持边界，业务无法确认可否使用 | 全文件 |
| T-4 | 靠 `!important` 压制 Element Plus 表头样式（8 处），包括 `white-space: nowrap !important`、`opacity: 1 !important`、`visibility: visible !important` | `LqTable.vue:110-135` |
| T-5 | `defineExpose` 暴露 9 个方法但均为 `any` 入参，无类型 | `LqTable.vue:78-90` |

### 6.3 `LqPagination`（**采纳率低的根因**）

| 编号 | 缺陷 | 证据 |
|---|---|---|
| P-1 | **强耦合**：`pagination: ListPagination` 必填且只能来自 `useListPagination()`，无法传入独立的 `page/size/total` | `LqPagination.vue:26`、`useListPagination.ts:26-81` |
| P-2 | **0 基/1 基倒换在组件内**：`get: () => pageInfo.page + 1`、`set: page => onPageChange(page)` | `LqPagination.vue:45-52` |
| P-3 | `change` 事件在 `current-change` 与 `size-change` 上**都会触发**，调用方无法区分是翻页还是改页大小 | `LqPagination.vue:54-60` |
| P-4 | `onMounted` 里用 DOM `querySelector` 补 `aria-label`，属命令式 hack | `LqPagination.vue:62-68` |

→ 直接后果：11 个文件的「局部分页状态」无法接入（§4.3）。

### 6.4 `SearchToolbar`

| 编号 | 缺陷 | 证据 |
|---|---|---|
| S-1 | **监听器探测 hack**：`getCurrentInstance().vnode.props?.onBatchEdit` 判断父级是否监听，注释自认「`vnode.props` 非响应式，依赖渲染时机重新取值」 | `SearchToolbar.vue:443-447` | 
| S-2 | **防抖实现内建且不可复用**：组件内 `debounceTimer`，与 `useListFilters`/`useCatalogFilters` 各写一份 | `SearchToolbar.vue:246-258` |
| S-3 | **双套筛选通道**：`searchMode('debounce'|'submit')` 与遗留 `searchOnInput` 并存，优先级靠 `props.searchMode ? … : props.searchOnInput` 判定 | `SearchToolbar.vue:184-189,254` |
| S-4 | 文本筛选的「镜像 + 已提交值」双状态（`textMirrors`/`textCommitted`）+ `watch(fieldValues, {deep:true})` 回写，状态同步复杂 | `SearchToolbar.vue:327-428` |
| S-5 | `keyword` 与 `viewMode` 用 `defineModel`，但 `fieldValues` 仍是普通 props + `filter-change` 事件，两种数据流风格混用 | `SearchToolbar.vue:192-193,232-240,242-243` |

### 6.5 `ListPageLayout`

- **props 爆炸**：为透传 `DataState` 复制了 16 个文案/状态 props（`loadingText/emptyText/errorTitle/forbiddenTitle/staleTitle/...`），组件自身 168 行默认值，是「透传式包装」的典型坏味道。`ListPageLayout.vue:99-168` vs `DataState.vue:67-119` 两张 props 表高度重复。
- 分页同样强耦合 `ListPagination`（`ListPageLayout.vue:130`）。

### 6.6 `EmptyState` / `LqPreview`

| 编号 | 缺陷 | 证据 |
|---|---|---|
| E-1 | **`LqPreview` 用错 `EmptyState` 的 prop**：传 `description="没有可预览的内容"`，而 `EmptyState` 的 props 是 `icon/text/hint/size`，**没有 `description`** → 该文案被丢弃，实际渲染默认「暂无数据」 | `LqPreview.vue:96` vs `EmptyState.vue:18-26` |
| E-2 | `EmptyState` 自身硬编码间距：`--lg` 档 `padding: 60px 20px` 未走 token | `EmptyState.vue:47` |
| E-3 | `DataState` 保留 3 个 `@deprecated` props（`loading`/`empty`/`error`）与 `state` 并存，两条判定链 | `DataState.vue:88-93,123-129` |
| E-4 | `DataState` 硬编码 `min-height: 240px`、`width/height: 36px` | `DataState.vue:149,157-158` |

### 6.7 命名/职责近似导致的混淆

| 组 | 成员 | 问题 |
|---|---|---|
| 状态展示 6 件套 | `EmptyState` / `DataState` / `LoadingState` / `LqSkeleton` / `RouteSkeleton` / `AppStatePage` | 边界靠约定而非文档；`DataState` 内部又包 `EmptyState` + `LoadingState` + `LqSkeleton` |
| 标签页 3 个 | `LqTabs` / `CategorySummaryTabs` / `MultiTabBar`（后者是路由多标签，非内容 Tab） | 名称相近，用途完全不同 |
| 卡片网格 2 个 | `styles/modules/asset-grid.css`（公共） vs `styles/asset-gallery.css`（业务） | 两个文件头部注释互相警告「勿混淆」 |
| 状态标签 2 条路径 | `common/StatusTag.vue`（13 文件 / 34 点） vs `composables/common/useStatus.ts` 的 `certStatusTagClass/certFeeTagClass` | 证书域另走一套 class 函数 |
| 主从框架 2 套 | `ListPageLayout`（12 文件） vs `components/master/{MasterLayout,MasterListPanel,MasterCardGrid}` + `PageHeader` | 两套列表页骨架并行；`PageHeader` 仅 `MasterLayout` 使用，与 `ListPageLayout` 自带 header 重复 |

### 6.8 业务 CSS 反向覆盖公共组件

- `:deep(` / `::v-deep`：**196 处 / 49 个 .vue 文件**（25% 的 vue 文件在穿透覆盖 Element Plus 或公共组件内部）。
- **直接覆盖公共组件的实例**：
  - `components/packaging/PackagingMoldsMasterDetail.vue:1584` — `.master-panel :deep(.data-state) {`（页面覆盖公共组件 `DataState`）
  - 同文件 `:1630` 注释自曝曾写死未命中的 `.el-pagination` 死样式
  - `components/packaging/PackagingMoldsMasterDetail.vue:2019,2044` — `:deep(.el-tabs__header)` / `:deep(.el-tabs__item)`
- `!important`：**108 处 / 12 文件**，其中公共组件自身占 40 处（`LqDescriptions.vue` 32、`LqTable.vue` 8），`CustomerDetailPanel.vue` 32 处 → 形成「公共组件用 `!important` 压 EP、业务再用 `!important` 压公共组件」的双重压制链，后续任何样式改动都需更高优先级，维护成本递增。

---

## 7. 设计变量与 CSS 重复问题

### 7.1 硬编码统计（194 个 .vue，实测）

| 指标 | 总次数 | 文件数 | 说明 |
|---|---:|---:|---|
| 十六进制颜色 `#[0-9a-fA-F]{3,8}` | **864** | 55 | 28.4% 的 vue 文件 |
| `rgb(` / `rgba(` | **97** | 30 | |
| `border-radius: Npx`（非 var） | **102** | 33 | tokens 已有 8 档 `--radius-*` |
| `box-shadow:`（非 var） | **156** | 67 | 另有 68 次走 `var()` → **约 70% 阴影写死** |
| `font-size: Npx`（非 var） | **614** | 80 | **规模最大的一类** |
| `margin/padding` 纯字面量 px | **716** | 126 | 量级最大 |
| `z-index`（非 var） | **26** | 15 | tokens 已定义 13 级仍被绕过 |
| `!important` | **108** | 12 | 见 §6.8 |

Top 文件（十六进制颜色）：`PackagingMoldsMasterDetail.vue` 107、`TrackingDetailDrawer.vue` 63、`MonthlyCommissionView.vue` 61、`CustomerDistributionDialog.vue` 52、`DeviceVideoLibraryView.vue` 47、`ProductInventoryView.vue` 37、`MoldImportPreviewTable.vue` 36。
Top 文件（font-size px）：`DeviceVideoLibraryView.vue` 47、`PackagingMoldsMasterDetail.vue` 42、`TrackingDetailDrawer.vue` 34、`MonthlyCommissionView.vue` 31、`H3ImportCenterView.vue` 29、`CompanyKnowledgeTab.vue` 27。
Top 文件（margin/padding px）：`H3ImportCenterView.vue` 42、`MonthlyCommissionView.vue` 41、`DeviceVideoLibraryView.vue` 40、`CompanyKnowledgeTab.vue` 28。

**结论：`tokens.css` 虽定义 324 个变量，但 `--fs-*`/`--space-*`/`--radius-*` 体系并未成为唯一来源**，字号（614）、间距（716）、圆角（102）三类的写死量级远超合理范围。

### 7.2 `styles/modules/` 内部重复（11 个）

| 重复组 | 证据 |
|---|---|
| **两列表单网格 ×2** | `modules/product-editor.css:62` `.form-grid{grid-template-columns:1fr 1fr; gap:var(--space-lg)}`（16px） vs `modules/field-grid.css:3` `.spec-field-grid{repeat(2,minmax(0,1fr)); gap:var(--space-section-gap)}`（12px）—— **同一形态，gap 值还不一致** |
| **field-grid.css 内部自重复** | `.field-item input/textarea`（47–74 行）与 `.spec-field-grid label > input/textarea`（96–118 行）逐字相同，且 `padding: 10px 12px` 为写死值 |
| **弹窗皮肤 ×3** | `dialog-layout.css`（通用，13 处 `.el-*`）+ `product-editor.css`（产品编辑）+ `media-dialog-shared.css`（媒体账号，注释自认「收敛三个标准对话框各自复制的计数徽标」） |
| **表格样式 ×4** | `modules/catalog-table-image.css` + `media-account-view.css`（含 `:deep(.el-table .stale-row td.el-table__cell)`）+ `element-plus.css`（「表格全局统一/轻量基线」）+ `modules/list-page-surfaces.css`（注释声明「外观交给 LqTable，刻意不再覆盖」） |
| **命名易混** | `modules/asset-grid.css`（公共） vs `styles/asset-gallery.css`（业务） |

### 7.3 主题覆盖不完整

| 主题 | 覆盖变量数 | 结论 |
|---|---:|---|
| light（`themes/light.css`） | 0（空壳，值在 `tokens.css:root`） | 完整 |
| dark（`themes/dark.css`） | **115** | **不完整** |

- 占 tokens 总量 324 的 **34.6%**；仅按颜色类（约 150 个）算约 **75%**，即 **约 38 个颜色/状态色在暗色下回退亮色值**。
- **确认缺失**（dark.css 中检索 0 命中）：`--ep-success-3/5/7/8/9/-dark`(6)、`--ep-warning-3/5/7/8/9/-dark`(6)、`--ep-info*`(7)、`--ep-danger-3/5/7/-dark`、`--ep-primary-dark`、`--color-sidebar-text/-text-strong/-muted`(3)、`--color-brand-cyan`、`--color-tooltip-bg/-text/-border`(3)、`--color-sidebar-overlay-*`(4)。
- 间距/圆角/字号/z-index/过渡未覆盖属预期（主题无关），不计缺陷。

### 7.4 CSS 引入层次被绕过

**19 个 `.vue` 在组件内 `@import`** 业务级 CSS，绕过 `styles/index.css` 的层次约定：`AssetGrid`、`AssetToolbar`、`PackagingAssetPanel`(2 处)、`PackagingDetailDrawer`、`PackagingSpecDetailPanel`、`PackagingVariantCards`、`CompanyCertificationsTab`、`CertificationFormDialog`、`MediaAccount*`(5)、`MediaAnalyticsPanel`、`ImportUpdatePreviewModal`、`UpdateRecordFormDialog`、`MediaPlatformConfigModal`、`EmployeeRosterView`、`ProductionTrackingView`、`MediaAccountView`、`CompanyInfoView`。其中 `packaging-detail-drawer.css` 42.78 KB、`media-account-view.css` 35.48 KB。

### 7.5 官网第二套令牌（**仅记录，本轮不治理**）

`corporate-website/src/assets/styles/tokens.css`（13.94 KB）与后台 `frontend/src/styles/tokens.css`（14.03 KB）体量几乎一致，**两套 tokens 无任何引用关系，主题/颜色语义不共享**。按审计限制第五条（「不将官网独立内容模型的改造混入本次内部后台 UI 治理」），本轮仅登记，不纳入整改项。

---

## 8. 按 P0 / P1 / P2 分类的整改建议

### P0 — 阻塞性、架构级（建议 2 批内完成）

| ID | 整改项 | 关键理由 |
|---|---|---|
| **P0-1** | **新建 `LqDrawer` 公共抽屉组件**（填补唯一的全站空白） | 17 个文件 100% 裸写 `el-drawer`，且各自写 `size`/`with-header`/`append-to-body`/`drawer-footer` |
| **P0-2** | **建立防重复门禁**：eslint `vue/no-restricted-component-names` 对 `src/apps/**` 与 `src/components/<非 common>/**` 禁用 `el-table`、`el-dialog`、`el-drawer`、`el-pagination`（豁免 `common/` 与测试）；并把 `npm run lint` 接入 CI | 现有机制完全无法发现新增违规（§9） |
| **P0-3** | **`LqDialog` 关闭事件模型收敛**：删除 `suppressNextClose` + `nextTick` 兜底双路径，统一为「`requestClose` 只改状态，由 `el-dialog @close` 唯一派发 `close`」；同步修 `LqConfirmDialog.handleCancel` 的双派发 | D-1/D-3，影响 55 文件 / 75 处 |
| **P0-4** | **`LqPagination` 解耦**：新增 `page`/`pageSize`/`total` 独立 props + `v-model:page`/`v-model:page-size`，`pagination` 对象改为可选兼容路径；区分 `change`/`size-change` 事件 | P-1~P-3，直接解锁 11 个自绘分页文件 |
| **P0-5** | **设计变量硬编码治理（公共层 + Top 10 页面）**：先把 `tokens.css` 的 `--fs-*`/`--space-*`/`--radius-*`/`--shadow-*`/`--z-*` 立为唯一来源，再治理硬编码 Top 10 文件 | 字号 614 / 间距 716 / 圆角 102 / 阴影 156 / z-index 26 |

### P1 — 能力缺口与扩散治理

| ID | 整改项 | 关键理由 |
|---|---|---|
| **P1-1** | **`LqTable` 泛型化 + 事件补齐**：`data: T[]`，`row`/`selection`/`rowClassName` 类型化；补齐 `expand-change`/`current-change`/`select`/`select-all`/`filter-change`；在组件头注释中**显式声明支持与不支持的 `el-table` 能力** | T-1/T-2/T-3 |
| **P1-2** | **统一防抖与筛选基座**：抽出 `utils/debounce.ts`，`SearchToolbar`/`useListFilters`/`useCatalogFilters` 共用；沉淀一个 `useFilters` 基座（**只做「值 + 提交时机 + hasActiveFilters + reset」**，业务字段定义仍留在各域，不强行统一字段） | 防抖 3 套、筛选 4 套 |
| **P1-3** | **错误反馈收敛**：36 个直连 `ElMessage/ElMessageBox/ElNotification` 的文件改为 `notify`/`errorMessage`；`useConfirmDialog` 与 `LqConfirmDialog` 定主次（建议以组合式为主，组件仅保留声明式场景） | 4 层并行 + 36 文件绕过 |
| **P1-4** | **弹窗/抽屉尺寸与层级统一**：用 `--dialog-width-*` 与 `constants/uiLayers`（`UI_LAYER`）替换 48 处手写 `close-on-click-modal`/`append-to-body` 与 26 处裸 `z-index` | §4.2 / §7.1 |
| **P1-5** | **暗色主题补全**：补齐缺失的 ~38 个颜色令牌（EP 色阶 19 个 + 侧栏 3 + tooltip 3 + brand-cyan 1 + sidebar-overlay 4 等） | dark 仅覆盖 115/324 |
| **P1-6** | **`LqDialog` 尺寸档位扩展/文档化**：在保留 4 档基础上明确 `width` prop 为官方自定义入口并写入组件头注释，消除「以为不支持」导致的裸写 | D-5 |
| **P1-7** | **清理准孤儿与死代码**：16 个仅 1 文件使用的组件逐一判定「下沉业务目录 / 升为通用」；删除 `utils/trackingListColumns.ts`、`useLoading.withLoading()`；`useDialogStack` 改名或下沉 | §3.3 / §5.2 |
| **P1-8** | **批量选择收敛**：23 个自建 `selectedIds` 的文件评估接入 `useBatchSelection`；批量栏统一到 `SearchToolbar` + `SearchToolbarBatchBar` | §4.7 |

### P2 — 长尾与整洁性

| ID | 整改项 | 关键理由 |
|---|---|---|
| **P2-1** | `styles/modules/` 内部去重：两列表单网格合 1、`field-grid.css` 内两份逐字相同规则合 1、弹窗皮肤收敛到 `dialog-layout.css`、表格样式 4 处收敛 | §7.2 |
| **P2-2** | 19 个 `.vue` 组件内 `@import` 业务 CSS 回归 `styles/index.css` 层次（或明确「页面级 CSS 白名单」约定） | §7.4 |
| **P2-3** | 日期格式化收敛：4 处同名导出 + 5 处组件私写 → 统一到 `utils/common/dateUtils.ts` | §5.2 |
| **P2-4** | `StatusTag` 与 `useStatus.ts` 的 `certStatusTagClass/certFeeTagClass` 双轨统一 | §6.7 |
| **P2-5** | 主从框架边界定义：`ListPageLayout` vs `master/*` + `PageHeader`，**先定边界与适用场景，不强行合并** | §6.7 |
| **P2-6** | 剩余硬编码治理（Top 11~55 文件） | §7.1 |
| **P2-7** | **（仅登记，本轮不做）** 官网 `tokens.css` 与后台分叉问题 | §7.5，受审计限制五约束 |

### 明确「不强行抽象」的项

以下虽存在重复，**但不建议在本次抽象**（重复量小或业务语义差异大，强行抽象会制造第二套体系）：
- 4 套筛选 hooks 中的**业务字段定义部分**（只抽「提交时机/hasActiveFilters/reset」机制，不抽字段）。
- `upload/` 的 `useCatalogFileUpload` 与 `usePackagingUpload`（业务定制层，保留）。
- `ListPageLayout` 与 `master/*`（先定边界，不合并）。
- 各页面 1~2 处的一次性空态文案差异。

---

## 9. 整改项影响范围、推荐复用资产与验收条件

### 9.1 测试 / lint / 类型检查 / 构建机制现状（H 项结论）

| 机制 | 现状 | 能否防重复 |
|---|---|---|
| `package.json` scripts | `dev/build/preview/lint/lint:fix/format/format:check/typecheck/test/test:watch/test:e2e/verify:file-behavior` | — |
| `verify:file-behavior` | `npm run typecheck && npm test && npm run build && npm run test:e2e` | 不含 lint |
| ESLint（`eslint.config.js`） | flat config：`pluginVue` recommended + `@typescript-eslint` recommended（`no-explicit-any: off`、`no-unused-vars: warn`）+ prettier；自定义规则仅 `vue/multi-word-component-names: off`、`vue/max-attributes-per-line`、`vue/no-mutating-props: warn`、`prettier/prettier: off`、`no-console: warn` | **否**。无 `no-restricted-imports`、无 `vue/no-restricted-component-names`、无分层约束 |
| stylelint | **不存在** | 无法防止硬编码 |
| husky / lint-staged | **不存在** | — |
| 类型检查 | `vue-tsc --noEmit --skipLibCheck`，已纳入 CI | 无法发现组件选型问题 |
| 单元测试 | 68 个 `*.test.ts`；`common/__tests__/` 14 个（LqDialog 32 用例、FilePreviewBody 7、SearchToolbar 7、LqSkeleton 6、DataState 5、LqConfirmDialog 5、LqPagination 4、LqPreview 4、ListPageLayout 4、LqTable 2、EmptyState 2、LoadingState 2、SourceTrace 2、StatusTag 2） | 全部为**单组件行为测试** |
| 架构守卫/契约测试 | **不存在**。搜索 `契约|contract|架构守卫|architecture` 在测试文件中 0 命中；测试文件中 `readFileSync|readdirSync|fast-glob|import.meta.glob` **0 命中**（即没有任何测试会扫描源码目录） | **否** |
| CI（`.github/workflows/unified-file-behavior.yml`） | 唯一 workflow；`npm ci` → playwright install → `backend/mvnw test` → `npm run verify:file-behavior` | 有 typecheck/test/build/e2e 门禁，**`lint` 与 `format:check` 不在 CI 链路** |
| Element Plus 注册方式 | `main.ts:2,27` **全局全量注册** `app.use(ElementPlus)`，未按需引入 | 模板中 `<el-table>` 无需 import 即可编译通过 → **基于 import 的禁用规则完全无效**，只能用模板层组件名规则或源码扫描 |

**判定：现有机制不能阻止「新页面直接用 `el-table` 而不用 `LqTable`」。** 现实中 38 个业务文件 100 处裸 `<el-table>` 长期存在且被 CI 放行，证明该模式是被容忍的既有事实。缺的正是一条模板层组件名禁用规则 + 接入 CI。

### 9.2 各整改项明细

#### P0-1 新建 `LqDrawer`

| 项 | 内容 |
|---|---|
| **影响范围** | 17 个现有 `el-drawer` 文件；后续所有抽屉。试点 3 个：`components/production/TrackingDetailDrawer.vue`、`components/h3/H3LedgerDetailDrawer.vue`、`apps/archive/views/production/ProductionTrackingWeeklyReviewDrawer.vue` |
| **推荐复用资产** | `LqDialog.vue` 的三段式结构（`__head`/`__body`/`__foot`）与关闭语义（`beforeClose` / `loading` 拦截 / ESC 与遮罩一致性）；`styles/modules/dialog-layout.css` 的 skin 思路；`constants/uiLayers` 的 `UI_LAYER` 层级；`LqDialog.vue:40-41` 的命名约定（避免与 `el-drawer__header` 混淆） |
| **验收条件** | ① 3 个试点抽屉改后视觉零回归（截图比对 header/footer/内容区）；② `size` 支持 `sm/md/lg/xl/full` 且映射到新 `--drawer-width-*` token；③ ESC / 遮罩 / 关闭按钮 / `beforeClose` 返回 `false` 四种关闭路径行为与 `LqDialog` 一致；④ 新增 `common/__tests__/LqDrawer.test.ts`，用例数 ≥ `LqConfirmDialog.test.ts`（5）；⑤ `append-to-body` 由组件统一处理，业务侧删除手写 |

#### P0-2 防重复门禁

| 项 | 内容 |
|---|---|
| **影响范围** | 全仓 `frontend/src/**/*.vue`；存量 38（表格）+ 22（弹窗）+ 17（抽屉）+ 11（分页）个文件会被规则命中 |
| **推荐复用资产** | `eslint-plugin-vue` 的 `vue/no-restricted-component-names`（已随 `eslint-plugin-vue@^9.33.0` 提供，无需新依赖）；`frontend/eslint.config.js` flat config |
| **验收条件** | ① 规则仅对 `src/apps/**` 与 `src/components/<非 common>/**/*.vue` 生效（`common/` 与 `__tests__/` 豁免，`LqTable.vue`/`LqDialog.vue`/`LqPagination.vue` 内的 `el-*` 是合法实现）；② 存量违规用一次性基线文件（如 `eslint-baseline.json`）或行内 `eslint-disable` + TODO 登记，**不阻断 CI**；③ 新增违规在 CI 失败；④ `npm run lint` 与 `format:check` 加入 `verify:file-behavior` 或新增独立 CI step；⑤ 规则需带 `message` 指引到对应公共组件 |

#### P0-3 `LqDialog` 关闭语义收敛

| 项 | 内容 |
|---|---|
| **影响范围** | **55 个文件 / 75 处标签**，全站最强耦合点；波及 `LqConfirmDialog`（1）、`VariantImageManager`（1）、`FieldBatchEditModal`（1）与 52 个业务文件 |
| **推荐复用资产** | 现有 `LqDialog.test.ts`（32 用例）作为回归网；`withDefaults` + `defineEmits` 类型化签名 |
| **验收条件** | ① 删除 `suppressNextClose` 与 `nextTick` 兜底（`LqDialog.vue:250,264-269`）；② 新增用例：「`beforeClose` 返回 `false` 时不派发 `close`」「点取消 / 点 X / ESC / 点遮罩四种方式均只派发一次 `close`」「`loading=true` 时四种方式均不关闭」；③ `LqDialog.test.ts` 32 用例 + `LqConfirmDialog.test.ts` 5 用例全绿；④ 全量 `npm test` 通过；⑤ 只监听 `@close` 的消费者行为不变 |

#### P0-4 `LqPagination` 解耦

| 项 | 内容 |
|---|---|
| **影响范围** | 现有 15 个文件（含 `ListPageLayout`）；解锁 11 个自绘分页文件 |
| **推荐复用资产** | `composables/list/useListPagination.ts` 作为可选便捷层继续保留；`tokens.css` 已有分页相关 token（`element-plus.css` 已统一分页视觉） |
| **验收条件** | ① 支持 `<LqPagination :page="p" :page-size="s" :total="t" @update:page @update:page-size>` 与 `<LqPagination :pagination="pg">` 两种用法；② `change` 与 `size-change` 分离；③ 移除 `onMounted` 的 `querySelector` aria hack（`LqPagination.vue:62-68`）；④ 至少 3 个原自绘分页文件（建议 `H3ImportBatchPanel.vue:118`、`WebsiteProductsView.vue:110`、`CashflowReviewDrawer.vue:51`）完成回归且分页行为不变；⑤ `LqPagination.test.ts`（4 用例）扩充至覆盖两种用法 |

#### P0-5 设计变量治理（公共层 + Top 10）

| 项 | 内容 |
|---|---|
| **影响范围** | 公共组件自身硬编码（`EmptyState.vue:47`、`DataState.vue:149,157-158`、`SearchToolbar.vue:591,599-601,634,642` 等）+ Top 10 文件：`PackagingMoldsMasterDetail`、`TrackingDetailDrawer`、`MonthlyCommissionView`、`CustomerDistributionDialog`、`DeviceVideoLibraryView`、`ProductInventoryView`、`MoldImportPreviewTable`、`ProductionTrackingView`、`H3ImportCenterView`、`CompanyKnowledgeTab` |
| **推荐复用资产** | `styles/tokens.css` 324 变量（`--fs-*`/`--space-*`/`--radius-*`/`--shadow-*`/`--z-*`）；`styles/utilities.css` |
| **验收条件** | ① 公共组件内 0 处硬编码色/间距/圆角/字号/z-index；② Top 10 文件硬编码下降 ≥70%（以审计基线数字为准：如 `PackagingMoldsMasterDetail` 色 107→≤32、`DeviceVideoLibraryView` font-size 47→≤14）；③ 引入 stylelint 或自定义 lint 规则，对**可替换为 token 的字面量**报错（建议先只开 `--fs-*`/`--radius-*`/`--z-*` 三类，间距因语义别名复杂改为 warn）；④ 每文件改造后需视觉比对通过（尤其暗色主题下） |

#### P1-1 `LqTable` 泛型化

| 项 | 内容 |
|---|---|
| **影响范围** | 13 个文件 / 14 处标签；潜在影响 38 个裸用文件（改造后迁移时受益） |
| **推荐复用资产** | `LqTable.test.ts`（2 用例 / 5 执行）作为回归网 |
| **验收条件** | ① `data: T[]` 且 `row-click`/`selection-change`/`rowClassName` 类型随 `T`；② 补齐 `expand-change`/`current-change`/`select`/`select-all`/`filter-change` 转发；③ 组件头注释写明「支持：列/排序/多选/展开/固定列/自定义空态；不支持：span-method、树形、虚拟滚动（需裸用并登记）」；④ `npm run typecheck` 通过且 13 个使用方无新增类型错误 |

#### P1-2 防抖与筛选基座

| 项 | 内容 |
|---|---|
| **影响范围** | `SearchToolbar.vue`（内建防抖）、`useListFilters`(6)、`useCatalogFilters`(1)、`useVariantFilters`(1)、`useTrackingFilters`(1) |
| **推荐复用资产** | 现有 4 套实现中行为最完整的 `SearchToolbar` 文本筛选镜像机制（`SearchToolbar.vue:327-428`）作为语义参考 |
| **验收条件** | ① 新增 `utils/debounce.ts` 并被 ≥3 处引用；② `useFilters` 只提供「值集合 + 提交 + `hasActiveFilters` + `reset`」，**不定义任何业务字段**；③ 现有 4 套筛选行为（含中文输入法组词不触发）不变；④ `useCatalogFilters.test.ts` 全绿 |

#### P1-3 错误反馈收敛

| 项 | 内容 |
|---|---|
| **影响范围** | 36 个直连 `ElMessage/ElMessageBox/ElNotification` 的文件（`useTrackingDetail.ts` 33、`useTrackingWorkbook.ts` 32、`PackagingMoldsMasterDetail.vue` 21、`MoldFormDialog.vue` 21、`RolePermissionView.vue` 18 …） |
| **推荐复用资产** | `composables/ui/useToast.ts`（`notify`，76 处，事实标准）、`utils/ui/errorHandler.ts`（`errorMessage`）、`api/http.ts` 拦截器 |
| **验收条件** | ① 业务 `.vue`/composable 中 `ElMessage` 直连文件数由 36 → ≤5（保留 `useToast.ts` 与少数特殊场景并登记）；② 提示文案与级别（success/warning/error）保持现状不变更；③ 全量 `npm test` 通过 |

#### P1-5 暗色主题补全

| 项 | 内容 |
|---|---|
| **影响范围** | `styles/themes/dark.css`；所有在暗色下使用 EP 成功/警告/信息/危险色、侧栏文字、Tooltip 的页面 |
| **推荐复用资产** | `styles/tokens.css` 亮色定义作为对照清单 |
| **验收条件** | ① dark.css 覆盖的颜色令牌数由 115 → ≥150（覆盖全部颜色类）；② 逐一比对缺失清单（EP 色阶 19 + 侧栏 3 + tooltip 3 + brand-cyan 1 + sidebar-overlay 4 + 其余 ~8）全部补齐；③ 暗色模式下主要列表页（OfficeAsset / Certificate / CashflowJournal / MediaAccount / WebsiteInquiry）无「亮色块」 |

#### P1-7 准孤儿与死代码清理

| 项 | 内容 |
|---|---|
| **影响范围** | 16 个仅 1 文件使用的组件；`utils/trackingListColumns.ts`、`useLoading.withLoading()`、`upload/useFileAssetCapabilities.ts` |
| **推荐复用资产** | 组件若下沉，移入对应业务目录（如 `VariantImageManager`/`VariantCarousel` → `components/packaging/`，`UnitField`/`LqTimeline` → 视使用方） |
| **验收条件** | ① 每个准孤儿组件有明确结论（下沉 / 升为通用并补文档 / 保留），记录在案；② 删除的死代码确认 0 引用（两种搜索方式）；③ `npm run typecheck` + `npm test` + `npm run build` 全绿 |

---

## 10. 分批整改顺序与风险

### 10.1 批次规划

| 批次 | 内容 | 预估规模 | 风险 | 前置条件 |
|---|---|---|---|---|
| **批次 0**<br>门禁与基线 | P0-2（eslint 规则 + 基线文件 + CI 接 lint）+ 建立「公共组件 API 契约测试骨架」 | 配置为主，0 业务改动 | **低** | 无 |
| **批次 1**<br>抽屉补白 | P0-1（`LqDrawer` 新建）+ 3 个高频抽屉试点 | 新组件 1 个 + 3 文件改造 | **中** | 批次 0 完成 |
| **批次 2**<br>核心组件收敛 | P0-3（`LqDialog` 关闭语义）+ P0-4（`LqPagination` 解耦）+ P1-1（`LqTable` 泛型） | 55 + 15 + 13 文件 | **中高** | 批次 1 完成；须先补 3 个组件的契约用例 |
| **批次 3**<br>批量回归 | 11 个自绘分页 + 23 个 `el-empty`/自绘空态文件 + 22 个裸 `el-dialog`（尺寸/层级统一，P1-4） | ~50 文件 | **中** | 批次 2 完成（`LqPagination` 已解耦） |
| **批次 4**<br>设计变量 | P0-5（公共层 + Top 10）→ P2-6（Top 11~55） | 10 + 45 文件 | **中**（视觉回归） | 批次 3 完成，避免与结构改造冲突 |
| **批次 5**<br>逻辑收敛 | P1-2（防抖/筛选基座）+ P1-3（错误反馈）+ P2-3（日期格式化） | ~40 文件 | **低-中** | 可**与批次 3/4 并行**（无 UI 结构改动） |
| **批次 6**<br>整洁性 | P1-7（准孤儿/死代码）+ P2-1（modules 去重）+ P2-2（CSS 引入层次）+ P1-5（暗色补全）+ P2-4/P2-5 | 配置与删除为主 | **低** | 前序批次完成 |

### 10.2 主要风险与缓解

| # | 风险 | 说明 | 缓解措施 |
|---|---|---|---|
| R-1 | **`LqDialog` 是全站最强耦合点** | 55 文件 / 75 处标签，任何 `close` 语义变更都可能引发「弹窗关不掉」或「关两次」 | 批次 2 开工前，先在 `common/__tests__/LqDialog.test.ts`（现有 32 用例）补齐关闭路径的契约用例（P0-3 验收条件②），再改实现 |
| R-2 | **`append-to-body` 导致 scoped `:deep` 失效** | `ProductionTrackingView.vue:939` 注释已自曝该问题；抽屉/弹窗改造时易出现「样式丢失」 | 样式统一收编到公共组件的**非 scoped** 皮肤 CSS（参照 `styles/modules/dialog-layout.css`），业务侧删除 `:deep`；改造前后逐一截图比对 |
| R-3 | **Element Plus 全局注册** | `main.ts:2,27` 全量注册，模板中 `<el-table>` 无需 import 即可编译 → 基于 import 的禁用规则无效 | 只能用 `vue/no-restricted-component-names`（模板层）+ 源码扫描；规则需按目录豁免 `common/` |
| R-4 | **暗色主题改动需人工比对** | 38 个颜色令牌回退亮色是「静默缺陷」，自动化测试无法捕获 | P1-5 验收必须含人工暗色走查清单（5 个主要列表页） |
| R-5 | **大文件改造风险** | `H3LedgerPanel.vue`(1900+ 行 / 21 处裸 table)、`MonthlyCommissionView.vue`(98 KB / 10 处裸 table + 5 处裸 dialog)、`ProductInventoryView.vue`(46 KB) | **不在本批次强制改造**；先接入门禁登记为存量违规，单独排期并配视觉基线快照 |
| R-6 | **强行抽象制造第二套体系** | 筛选/日期/主从框架的重复中，部分差异是业务语义差异 | 遵守 §8「明确不强行抽象」清单；`useFilters` 只抽机制不抽字段；主从框架先定边界不合并 |
| R-7 | **视觉回归不可自动化** | 项目无视觉回归工具（CI 只有 Playwright 行为 e2e，9 个 spec） | 每批次改造页面需人工比对；建议在批次 0 为 5 个高频列表页补 Playwright 截图基线 |
| R-8 | **范围蔓延** | 官网 `corporate-website` 存在第二套 tokens.css | 按审计限制第五条，**本轮仅登记（§7.5），不纳入任何整改项** |

### 10.3 建议的「是否开工」判定

- **P0-1（LqDrawer）与 P0-2（门禁）可独立先行**，二者不涉及现有组件行为变更，风险最低、收益最高。
- **P0-3 必须先补测试再改实现**；在未补齐 `LqDialog` 关闭路径契约用例前，不建议动 `LqDialog.vue:250-280`。
- **批次 4（设计变量）规模最大但风险可控**，建议拆为「公共层 → Top 10 → 其余」三小批，每小批独立验收。

---

## 附录 A：本次审计的只读性声明

- 未修改任何业务代码、公共组件或 CSS（唯一写入为新建本文档 `docs/frontend-ui-p0a-audit.md`）。
- 未执行数据库迁移、未操作生产数据。
- 未安装依赖、未执行 `npm install` / `mvnw` 等有副作用命令。
- 未提交、未推送。
- 使用的方法：正则检索、逐行文件读取、静态分析子代理；`git` 仅执行 `log/status/rev-parse/diff --stat` 等只读命令。
- 所有数字均来自实际扫描，无估算值；合并计数发生截断时已在文中标注「≥」。

## 附录 B：关键证据索引（文件:行号速查）

| 结论 | 证据位置 |
|---|---|
| `LqTable` 类型退化为 `any[]` | `frontend/src/components/common/LqTable.vue:41` |
| `LqTable` 用 `!important` 压 EP | `frontend/src/components/common/LqTable.vue:110-135` |
| `LqPagination` 强耦合 `ListPagination` | `frontend/src/components/common/LqPagination.vue:26` |
| `LqPagination` 0/1 基倒换 + 单一 `change` 事件 | `frontend/src/components/common/LqPagination.vue:45-60` |
| `LqDialog` 关闭双路径 + `suppressNextClose` | `frontend/src/components/common/LqDialog.vue:250,258-280` |
| `LqConfirmDialog` 双派发自认风险 | `frontend/src/components/common/LqConfirmDialog.vue:194-207` |
| `SearchToolbar` 监听器探测 hack | `frontend/src/components/common/SearchToolbar.vue:443-447` |
| `SearchToolbar` 内建防抖（第 1 套） | `frontend/src/components/common/SearchToolbar.vue:246-258` |
| `SearchToolbar` 双筛选通道 | `frontend/src/components/common/SearchToolbar.vue:184-189,254` |
| `DataState` 三个 `@deprecated` props 并存 | `frontend/src/components/common/DataState.vue:88-93,123-129` |
| `EmptyState` 硬编码间距 | `frontend/src/components/common/EmptyState.vue:47` |
| `LqPreview` 误用 `EmptyState` 的 `description` prop | `frontend/src/components/common/LqPreview.vue:96` vs `EmptyState.vue:18-26` |
| `ListPageLayout` props 爆炸（透传 16 个 DataState 文案） | `frontend/src/components/common/ListPageLayout.vue:99-168` |
| 业务覆盖公共组件 `DataState` | `frontend/src/components/packaging/PackagingMoldsMasterDetail.vue:1584` |
| `append-to-body` 致 `:deep` 失效（已知坏味道） | `frontend/src/apps/archive/views/production/ProductionTrackingView.vue:939` |
| 完全脱离组件体系（原生 input/select） | `frontend/src/apps/archive/views/product/DeviceVideoLibraryView.vue:41,74` |
| 自绘 `.drawer-footer` | `frontend/src/apps/archive/views/system/RolePermissionView.vue:591`、`frontend/src/components/production/TrackingDetailDrawer.vue:197` |
| Element Plus 全局注册（禁用规则只能用模板层） | `frontend/src/main.ts:2,27` |
| eslint 无组件禁用规则 | `frontend/eslint.config.js` |
| CI 未跑 lint | `.github/workflows/unified-file-behavior.yml` + `frontend/package.json:verify:file-behavior` |
| `useLoading.withLoading()` 0 调用 | `frontend/src/composables/ui/useLoading.ts` |
