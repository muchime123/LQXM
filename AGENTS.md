# AI Agent 开发指南

## 项目结构

```
backend/                          # Spring Boot + Maven
  src/main/java/.../archive/
    config/                        # Spring 配置 (6)
    controller/                    # REST API (42)
    service/                       # 业务逻辑 (91：主包 77 + file 子包 14)
    repository/                    # JPA 仓库 (55)
    entity/                        # JPA 实体 (59)
    dto/                           # 内联 record DTO
    util/                          # 工具类
  src/main/resources/
    db/migration/                  # Flyway 迁移 (V{版本}__{描述}.sql，当前最大 V401，共 408 个脚本)
frontend/                         # Vue 3 + Vite + TS + Element Plus
  src/
    api/                           # API 调用 (60：52 源 + 8 测试)，统一走 api/http.ts 的单一 axios 实例
    apps/                          # 业务应用装配层（当前仅 archive）；不存在 src/views/
      index.ts                     # allNavItems 侧栏导航唯一入口
      types.ts                     # NavItem 结构
      archive/
        routes.ts                  # 路由表本体（不在 router/ 内）
        navigation.ts              # 侧栏导航数据（含 permission 声明）
        views/                     # 页面视图 (30 .vue：system/product/marketing/production/settlement/governance/customer)
    components/                    # Vue 组件 (169，其中 common/ 59)
    composables/                   # 组合式函数 (86：upload/20、ui/16、catalog/15、list/6、common/6，另有 packaging/mold/tracking/auth 等)
    constants/                     # 常量与枚举元数据 (15)
    router/                        # 路由装配层 (2：index.ts + 契约测试)；路由表见 apps/archive/routes.ts
    shell/                         # 登录 / 403 / 404 视图 (3)
    types/                         # TypeScript 类型 (16)
    utils/                         # 工具函数 (69)
    styles/                        # 样式 (28 CSS：tokens/reset/element-plus/utilities + modules 11 + themes + 页面级)
corporate-website/                # Vue 3 + Vite（无 TS）官网
  src/
    api/                           # API 调用 (2 文件，30 导出函数)
    components/                    # Vue 组件 (14：5 根级 + 8 common + 1 article/ArticleViewer 富文本阅读器)
    composables/                   # 组合式函数 (10，多为单例模式)
    data/                          # 静态数据 (solutionArticles.js)
    utils/                         # 工具函数 (sectionsToHtml.js 旧结构化小节转富文本)
    views/                         # 页面视图 (15)
    router/                        # 路由 (15 条，6 个一级导航 key)
    assets/styles/                 # 样式 (15 CSS + style.css 入口，含 article.css 通用文章阅读器排版)
    public/images/                 # 公共图片 (23 个，jpg/png + webp 双格式配对)
```

> 目录与数量为 2026-10-22 实际核对结果，仅表示规模。具体引用关系（谁在复用哪个公共组件、哪些是死代码）必须现场核对，不要仅凭本文件推断；本文件历史上曾长期保留不存在的 `frontend/src/views/`。

## 命名规范

- Java: PascalCase, snake_case 资源文件
- Vue: PascalCase 组件, kebab-case 模板
- TypeScript: camelCase 函数/变量, PascalCase 类型/接口
- 数据库迁移: `V{版本}__{描述}.sql` (英文 snake_case)。历史脚本存在 V1_x 与 V2~V37 两套编号，均已执行不可改动；新脚本一律从现有最大整数版本号递增（2026-10-22 当前最大 V401），不再使用 `V{主}_{次}` 子版本号
- CSS 类名: kebab-case

## 前端修改策略

### 大文件修改 (Vue 文件 > 1000 行)
优先使用 Python 脚本 (execute_shell + python -c) 进行批量替换，用 sed 做精确行号定位。
当前已知超大文件：
- backend/service/MediaAccountService.java 约 1350 行（已补齐审计日志、优化 N+1 和批量操作）
- backend/service/CompanyInfoService.java 约 705 行（单类承载 9 类资源，拆分候选）
- frontend/src/views/MediaAccountView.vue ≈1302 行
- frontend/src/views/ProductCatalogView.vue ≈951 行（已通过 20+ composables 拆分，编排层重）
已下线：
- PasswordManager.vue（密码管理模块整体删除，含 3 张数据库表）
- PricingView / SoftwareView / ExhibitionArchiveView 均已整体下线
- 数据质量中心（DataQualityCenterView）与匹配中心（H3BusinessCenterView / /matching 已重定向）整体下线：遗留审计/对账表已由 V204 清理，前端残留（ReceiptMatchExceptionPanel、数据修复工具区、数据质量中心 preset 跳转）已同步删除
- 正式销售出库/收款/利润本地链（`FormalSalesShipment/Receipt/Profit*`，`formal_sales_*` 表）与 H3Preparation/期初库存旧入口（`H3Preparation*`/`H3ProductSource*`，`h3_preparation_*`/`product_inventory_opening_*` 表）整体下线（2026-09-04）：后端 12 类 + 4 测试 + 前端 h3-preparation smoke/fixture 已删，表与 RBAC/功能目录由 V260 幂等清理；销售事实口径仅剩氚云台账 `sales_outbounds`，库存口径为台账合并入库（B2）
- `/sales-products`（销售产品）与 `/sales-profits`（源毛利校验）已整体下线（2026-09-04）：前端路由 + `views/settlement/profit/{SalesProductView,SalesProfitView}.vue` + `api/salesProduct|salesProfit` + 后端 `SalesProductController`/`SalesProfitController` 已删（对应 Service 当时暂保留）；孤儿扫描复核后该 Service 实为 0 注入，已于 2026-09-07 连同 DTO 链整链删除（见下）
- 旧业务中心利润/提成核算域（`BusinessProfitService`/`BusinessCommissionService`）整体下线（2026-09-04）：`ExpenseAllocationService` 对 BUSINESS_ORDER 目标的联动重算与 `H3BusinessQueryService` 的业务单只读方法（businessOrders/businessDetail/businessTimeline）已移除；`ExpenseController` 费用归集写端点收敛（删 batch-save / auto-allocate / auto-allocate-batch / runs / allocation-rules CRUD / legacy-migration 组）；前端 `api/expense/expenses.ts` 同步清理对应死函数与类型；旧域 5 张数据表由 V262 归档后 DROP（`business_commission_rules` 为现行销售出库提成共享规则表保留，`business_orders`/`business_order_links` 保留供现行匹配与台账血缘使用）；B3 深化（2026-09-04）：`ExpenseAllocationService` 的 P2 自动归集引擎（autoAllocateClaim/legacy 生成器）、规则 CRUD、运行记录、批量保存等死方法族（连同 `ExpenseDtos` 死类型、`ExpenseAllocationRepository` 的 reverseAllConfirmedForClaim/reverseAutoConfirmedForClaim/updateRunIds、契约测试 ExpenseAllocationEmployeeIdContractTest）已一并清除，仅保留订单费用确认链路（reverse/confirm 及读端点）
- 清理历史设计遗留（2026-09-04）：后端孤儿类 `CryptoUtils`/`AiPromptConfig` 及悬空 `app.crypto.secret-key` 配置、前端死枚举 `governance` 路由分组与 `navigation.ts` 中失效的 `retiredMainEntries` 过滤均已删除；空死表 `contract_archives`/`contract_archive_items` 由 V261 DROP（无任何代码引用且 0 行）
- 孤儿代码扫描清理（2026-09-07）：① 删除 `SalesOutboundDtos`/`SalesProfitDtos`/`SalesProductDtos` 与 `SalesProductService`/`SalesProfitCalculationService`（两 Service 均纯 JdbcTemplate、无 entity/Repository 连带、全树 0 注入，旧注“导入侧调用”不成立）；② 库存子模块 `ProductInventoryTransfer/Stocktake/Other` 三组 Controller+Service 与对应 6 张空表整链删除（前端 api 0 调用、表 COUNT 均 0，`MaterialInventoryService` 不动）；③ `MaterialInventoryController`（/api/material-inventory 6 端点前端 0 调用）删除，Service 保留（制造领料 `ManufacturingMaterialService` 注入使用）；④ 一次性/备份/探针死表 15 张由 V267 DROP（`expense_allocation_rules`/`runs`、`product_variants_v2_soft_deleted`、`probe_h3_extracted_outbounds`/`matched_detail`/`missing_outbounds`/`related_receipts`、`expense_claims/items_bak_20260826`、`backup_sales_contracts/items_v132/_v133`、`business_orders_bak_v204`、`sales_outbounds_bak_v204`），非空表行数据已导出 CSV 留档于根目录 `db_cleanup_20260907/`；⑤ `material_inventory.read/manage` 权限与 `MATERIAL_INVENTORY` 功能授权（V233/V249 seed）随 controller 下线由 V267 幂等清理。数据表保留：`sales_products`/`sales_product_source_aliases`（现行 `ProductInventoryMergeService` 使用）、`sales_profit_calculations`(27456)/`sales_product_cost_snapshots`(3983)（删除写入方后暂无消费，待后续单独决策）
- 「我的订单」销售工作台（`/my-orders` + `views/sales/MyOrdersView.vue`，销售出库"订单利润 + 提成试算 / 批量初始化 / 治理"页面）整体下线（2026-09-07）：该套"按单利润 / 试算提成"设计不再使用，仅保留「月度提成核算」（`MonthlySalesCommissionController`/`MonthlyCommissionView`，现行简化口径）与氚云销售出库台账。前端删除 `/my-orders` 路由/导航项与 `MyOrdersView.vue`，`api/salesOutbound/orderProfit.ts` 收敛为抽屉/客户档案所需（删 summary / readiness-summary / 批量重算 / diff-report / 订单提成查询 / 目标搜索死代码），登录默认落地页由「我的订单」改为「客户档案」（`/customers`）；客户档案"查看全部订单 →"入口移除，氚云销售出库导入卡"查看我的订单"下一步改跳本页出库台账，`SalesOutboundDetailDrawer` 兜底返回改 `/customers`。后端删除 `SalesOutboundProfitController` 的 `/summary`、`/readiness-summary`、`/recalculate/batch`、`/diff-report` 端点与 `SalesOutboundProfitService` 对应方法（`outboundProfitSummary`/`readinessSummary`/`recalculateAll`/`diffReport`），`SalesOutboundCustomerFilterTest` 收敛为列表过滤契约。共享能力全保留：出库单详情抽屉（月度提成/客户档案下钻）、订单费用归单确认链路、列表/详情/调整端点，`sales_outbound_profits`/`_adjustments`/`_commissions` 与 `business_commission_rules` 表及 RBAC/功能授权均不动
- 出库单「详情」统一迁移到氚云销售出库台账（2026-09-07，接上条收口）：`App.vue` 全局 `?orderId=` 宿主由「订单利润档案」抽屉换成台账详情宿主（`H3LedgerDetailDrawer` + `GET /api/h3-imports/ledger/outbound/{id}`，row=`sales_outbounds` 主记录全字段、rows=出库产品明细，与氚云导入中心台账同源），月度提成页出库单/运杂费/识别明细链接（`openOrderDetail` → `openLedgerDetail`）自动跟随；客户档案业务卡片下钻（原 `CustomerOrderProfitDrawer`）改 `fetchLedgerDetail('outbound', id)` 打开同一台账详情抽屉。`components/sales/SalesOutboundDetailDrawer.vue` 与 `components/customer/CustomerOrderProfitDrawer.vue` 组件下线删除；`api/salesOutbound/orderProfit.ts` 收敛为列表（仅留 `fetchOrderProfits`/`OrderProfitItem`/`PageResult` 供客户档案业务列表），订单利润详情/单张重算/调整/费用归单确认前端函数随抽屉下线删除；后端 `ExpenseController` 费用归单确认链路（reverse/confirm 及读端点）与 `SalesOutboundProfitController` 详情/调整端点按用户指示保留
- 客户档案「档案整理」（公司地址 → 省市区县：候选预览 / 一键应用 / 批量应用 / 全量重解析）整体下线（2026-09-10）：前端删除 `components/customer/CustomerAddressTidyDialog.vue`、`CustomerView.vue` 的「档案整理」按钮与弹窗挂载、`api/customer/customer.ts` 的 `AddressTidy*` 类型与 `fetchAddressTidyCandidates`/`applyAddressTidy`/`applyAllResolvableAddressTidy`；后端删除 `CustomerAddressTidyController`（`/api/customers/address-tidy/*` 4 个端点）与 `CustomerTidyDtos`，`CustomerAddressTidyService` 收敛为「地区治理状态扫描 + 地址可选性判定」（仅留 `scanStatuses`/`classify`/`isSelectableCity`/`isSelectableDistrict`，**不再写档案省市县**）。保留：`customers.address_tidy_status` 字段与 V276（客户列表 / CSV 导出「未标注地区」筛选、区域分布统计共用该口径）、`CustomerProfilePlanService` 地区补全计划依赖的状态重算与可选性判定、`ChinaAddressParser`（客户表单地址解析 `/api/customers/address/parse`、导入侧 `CustomerArchiveBackfillService` 回填）、出库台账「同步客户档案」与导入中心「客户档案回填」两个入口
- 「出库最新地址 → 客户档案公司地址」同步链路整体下线（2026-09-10，接上条收口）：前端删除 `H3LedgerPanel.vue` 出库台账工具栏「同步客户档案」按钮与结果弹窗（含 `addrSync*` 状态、`openAddressSync`/`confirmAddressSync`、`.addr-sync-*` 样式）、`api/business/orders.ts` 的 `syncCustomerAddressFromOutbound`、`types/h3Import.ts` 的 `CustomerAddressSyncResult`；后端删除 `H3FactImportController` 的 `POST /api/h3-facts/customer-archive/address-sync` 与 `CustomerArchiveBackfillService.syncCustomerAddressFromOutbound`（连同仅其使用的 `loadLocationIndex`/`mergeValue`/`sameText`/`auditAddressSync` 与 `AddressSyncResult` 结果类）。保留：`CustomerArchiveBackfillService.backfillAll`（`POST /api/h3-facts/customer-archive/backfill`，历史业务数据建档 + 联系人 + 业务员关系 + 关联 customer_id；**前端当前 0 调用**）及其建档时经 `ChinaAddressParser` 的地址解析（`parseAddressText`/`AddressParts`），客户表单地址解析 `POST /api/customers/address/parse`。后续「出库地址 → 客户档案地址」如需回归，改在客户档案页内设计

已优化/不再是大文件：
- backend/service/CertificateService.java 417 行（已补齐软删除+审计）
- frontend/src/components/packaging/PackagingDetailDrawer.vue 882 行（已从 ~1924 行优化下来，通过提取 composables 删除重复代码）
- frontend/src/views/CertificateView.vue 418 行

### grep 中文匹配限制
PowerShell Select-String 在 .vue 文件中可能无法匹配中文。替代：使用 read_file 读取行号范围，或 Python 脚本读取内容匹配。

### 页面初始加载（Suspense + 顶层 await，2026-08）
`App.vue` 的 `<RouterView>` 已用 `<Suspense fallback=RouteSkeleton>` 包裹。**要让页面首次进入显示骨架屏，而不是空白/丑加载，需把数据加载从 `onMounted` 提升为 `<script setup>` 顶层 `await`**（async setup），这样 Suspense 才会命中 fallback：
- 简单形态：`onMounted(loadData)` → `await loadData()`，并把不再使用的 `onMounted` 从 vue import 移除。
- 深层 `onMounted(async () => {...})`：仅当回调体**纯数据加载**（只做请求+给 ref 赋值）时，才能去掉包裹改成顶层语句；若回调里注册了 `window/document` 事件监听（配对 `onUnmounted`/`onBeforeUnmount`）、访问模板 ref（`xxxRef.value.focus()`/`.getBoundingClientRect`）等依赖已挂载 DOM 的操作，**禁止**提升（实例：DeviceVideoLibraryView、OfficeAssetView、WebsiteVisualEditorView，保持 `onMounted` 卸载方式）。
- KeepAlive 命中缓存再次进入时不重跑 setup，故骨架屏只在首次进入显示，符合预期。
- 新增带初始加载的视图时，优先写 `await loadXxx()` 顶层形态，让 Suspense 统一接管首屏。

### DTO 更新模式
DTO 文件需要多处修改时，使用 create_file 重写整个文件更可靠。修改点：新增字段、更新 from 方法、更新 applyPayloadToEntity 方法。

### 批量管理统一方案（试点：DocumentView，2026-08）
所有列表页面统一接入"搜索栏批量管理"能力，批量编辑弹窗支持像编辑弹窗一样修改任意字段。已落地方案：

- **入口**：`SearchToolbar` 增加"批量管理"按钮（`batchEnabled && !batchMode` 时显示在 actions 区），点击后 `toggle-batch-mode` 进入批量模式，显示第二行批量栏（全选/批量编辑/批量删除所选/退出）。
- **通用批量编辑弹窗**：`frontend/src/components/common/FieldBatchEditModal.vue`，字段 Schema 驱动（checkbox + 控件），支持 `text/textarea/number/select/date/tags/switch` 类型。页面只需传入 `fieldGroups: { title, fields: [{ key, label, type, options, full }] }`，`apply` 事件回传 `payload: { fieldKey: value }`（仅勾选的字段）。
- **通用后端端点**：`PUT /api/{entity}/batch`，body `{ ids, fields }`。字段白名单校验 + 枚举值校验 + 单事务 + 审计日志（参考 `DocumentService.batchUpdateFields` 与 `BATCH_EDITABLE_FIELDS`）。
- **前端 API 模式**：`api/document/documents.ts` 中 `batchUpdateDocuments(ids, fields)` 对应新端点；原专用批量端点（batch-status/category/department 等）可保留。

#### 推广到新页面的步骤（试点已验证）
1. 后端：给实体新增 `batchUpdateFields(ids, Map<String,Object>)`（白名单校验 + 类型转换 + 审计）+ 控制器 `PUT /batch`。
2. 前端 API：新增 `batchXxx(ids, fields)`。
3. 页面：`SearchToolbar` 加 `batch-enabled :batch-mode :selected-count :models-count :is-all-selected` 及 `@toggle-batch-mode @toggle-select-all @batch-edit @batch-remove`；`@batch-edit` 打开 `FieldBatchEditModal` 并传入字段 Schema；`@batch-remove` 接原有批量删除逻辑。
4. 移除页面内旧的批量改字段逻辑（`prompt()` 之类），避免双入口。批量栏只有 `SearchToolbar` + `SearchToolbarBatchBar` 一套；`components/common/BatchActionBar.vue` 已于 2026-10-22 删除，不要再引用。
5. 新增控件类型时（如 tags 的 disabled），需确认组件支持 `disabled` prop（`LqTagInput` 已在 2026-08 补上）。
6. 表格/卡片选择改用 `useBatchSelection`（`selectedIds` 为 Set，卡片模式传 `selectedCertIds` 数组派生）。行点击 `@row-click` 在批量模式下切换选中（列表），卡片模式用 `onCardClick`（批量模式切换选中，否则打开详情）。`useBatchSelection<number>()` 在需要 `Set<number>` 传给子组件时使用。

#### 已接入页面（2026-08）
DocumentView（试点）、OfficeAssetView、CertificateView。各页均含 `batchUpdateFields`（后端白名单见各 Service 的 `BATCH_EDITABLE_FIELDS`）+ `PUT /{entity}/batch` + `FieldBatchEditModal` 字段组 + `useBatchSelection` 复选框列（含卡片模式）。
- 明确不迁移：`MediaAccountView`（自定义域批量弹窗，选中 vs 全部筛选范围）。
- 已下线模块不再提及：SoftwareView、PasswordManager、PricingView（均已整体删除）。
- 注意：`Certificate` 的 `assetStatus` 存储为英文枚举（DB），批量更新时经 `toDbStatus` 中文→英文；`feeStatus` 存中文直接写入。OfficeAsset 的 `purchaseDate/warrantyUntil` 用 `LocalDate.parse`，`purchasePrice` 用 `new BigDecimal`。

### 可视化官网编辑器（/website/visual-editor，2026-08）

在后台 iframe 中加载真实官网（`?cmsEdit=1`），实现"看到哪里、改哪里"的 in-context 编辑。

- **入口**：后台「官网运营 · 可视化编辑器」→ `frontend/src/views/WebsiteVisualEditorView.vue`（路由 `/website/visual-editor`）。iframe 指向 `VITE_CORPORATE_WEBSITE_URL`（默认 `http://{host}:5174`）。
- **可编辑页面**：`EDITABLE_PAGES`（当前 HOME / PRODUCTS / SOLUTIONS / ABOUT，每页含 `defaultSection`）。iframe 地址随页面切换（首页 `/`、产品 `/products` 等）。官网顶栏导航为 RouterLink 客户端跳转，会在编辑模式丢失 `cmsEdit` 参数，故 App.vue 用 `onCmsNavClick` 拦截内链补回 `?cmsEdit=1`，并通过 `LIQIANG_CMS_NAV {pageKey}` 通知编辑器同步切换页面与表单（编辑器 `onPreviewMessage` 收到后调 `selectPage`）。`/credentials`、`/patents`、`/contact` 均 redirect 到 `/about#xxx`。
- **通信协议**（corporate-website 与后台通过 postMessage，见 `corporate-website/src/App.vue`）：
  - 官网在编辑模式把 `[data-cms-section]` 元素描边，点击发 `LIQIANG_CMS_SELECT {pageKey,sectionKey,source}`。
  - 连接握手：父窗口发 `LIQIANG_CMS_PING`，官网回复 `LIQIANG_CMS_READY {app:'liqiang-corporate-website', pageKey}`（pageKey 随路由变化，如 /about 时 `ABOUT`）；超时 4.5s 显示「官网未连接」。
  - 选中/滚动定位用 `LIQIANG_CMS_FOCUS {sectionKey, scroll}`（官网侧 `focusCmsSection` 切换 `data-cms-active` 高亮）；Hero 选中某 Banner 用 `LIQIANG_CMS_SHOW_BANNER {bannerId}`。
  - 后台推草稿：`LIQIANG_CMS_DRAFT {pageKey,sectionKey,data:{title,subtitle,content,imageUrl,enabled}}` 和 `LIQIANG_CMS_BANNER_DRAFT {bannerId,data}`；清除用 `*_CLEAR_*`。
  - 官网侧内存草稿在 `corporate-website/src/composables/useCmsPreview.js`（区块草稿 + Banner 草稿 + `activeBannerId`），刷新即失，保存=发布（写库后清草稿并重挂 iframe）。
- **区块数据来源**：`hero` → Banner 管理（可面板直编）；`products/industries/process` → 页面配置标题 + 业务档案（面板只改标题，卡片跳转完整后台）；`showcase/service/cases` → 页面区块 `content` JSON（面板用 `STRUCTURED_SCHEMAS` 结构化行编辑：text/textarea/image）；`strength` → 公司信息；ABOUT 页 `intro/manufacturing` 额外支持区块主图（`imageUrl`）。
- **草稿消费**：官网 `Home.vue` 用 `homeSectionEnabled`（草稿 enabled 优先）+ `sectionRows(key)`（content 存在即以草稿为准，含空列表）+ `d(key).title/subtitle`；`About.vue` 用 `sectionEnabled` + `aboutHeadings`；`Products.vue`/`Solutions.vue` 用 `sectionEnabled` + `pageContent`。四个页面均实现了「草稿 enabled 优先 + 标题/描述/图片回退链」。编辑区块新字段时需同时改：编辑器表单/Schema → 草稿推送 → 官网对应页消费草稿的 computed。
- **未保存草稿标记**：编辑器用 `pushed`（键 `${pageKey}.${sectionKey}`）与 `pushedBannerIds` 跟踪已推送到 iframe 且未保存的草稿，结构面板行右上显示圆点；`hasPendingDrafts` 时显示「放弃全部草稿」`discardAllDrafts()`（遍历推送 `*_CLEAR_*` 后 `reloadPreview`）。任何 `reloadPreview()` 都会清空 `pushed`（iframe 重挂后内存草稿全部丢失）。
- 结构面板的显示/隐藏开关 = `updatePageSection(id, {...all fields, enabled})` + 重挂预览；保存后必须 `reloadPreview()`（`frameKey++`）让新 iframe 重新拉取公开接口，否则草稿清除后仍显示旧数据。

### 方案文章富文本化（2026-08，可复用于知识库/新闻）

方案文章正文由"结构化小节（contentSections）"演进为**富文本 HTML（contentHtml）**，官网详情页保留侧边目录（TOC）大纲样式：

- **内容模型**：`website_solutions.content_html TEXT`（V116）。正文用 H2/H3 标题分节；目录**自动**从 h2/h3 生成，无需第二份数据。
- **管理端**：`frontend/src/components/website/RichTextEditor.vue`（wangEditor v5，Vue3 版本在 `@next` 标签 `5.1.12`；`@wangeditor/editor@5.1.23`）。图片走现有 `/api/files/company` 上传 + `website_media` 素材库登记。旧结构化数据在打开编辑时由 `utils/richText.ts#sectionsToHtml` 转 HTML 预填，保存后即切富文本。
- **官网阅读器**：`corporate-website/src/components/article/ArticleViewer.vue` 渲染（DOMPurify 消毒）+ 自动目录 + IntersectionObserver 滚动高亮；`article.css` 负责两栏布局与 `.article-rich` 正文排版。正文为空时回退 `sectionsToHtml` 兼容旧数据。
- **复用约定**：知识库/新闻等新内容模块 = 各自建表 + 复用 `RichTextEditor.vue` 与 `ArticleViewer.vue`，不要各写一套编辑器/阅读器。
- wangEditor 组件在 `frontend/src/types/wangeditor.d.ts` 有模块声明（包内 exports 未暴露 types）。

## 氚云导入与历史台账（2026-08/2026-09 演进）

氚云数据源目前支持 4 类导入（`H3FactType` = `outbound` / `production` / `receipt` / `settlement`）。销售出库走统一订单链路；**四类导入数据源均保留一份原始数据历史台账**，展示在氚云导入中心页内 Tab（不再有侧边栏「废弃台账」分组），不与业务编排、不写 `receipt_allocations`。

- **后端**：`H3FactImportService` 的 `confirm` 与 `confirmFromStaging` 的 switch 中，`OUTBOUND`→`saveOutbound`，`PRODUCTION`→`saveProduction`，`RECEIPT`/`SETTLEMENT`→`saveReceipt(group, false/true)`（都写 `receipts` 表，`receipt_type` 区分 `SINGLE_OUTBOUND`/`MULTI_OUTBOUND`）；每次确认行级写 `h3_import_rows` 原始快照（永久留存，可随批次追溯）；`rejectStoppedFactTypes` 全类型开放。
- **台账查询**：`H3BusinessQueryService#outboundLedger` / `productionLedger` / `receiptLedger(boolean settlement, ...)`，只读分页查询 `sales_outbounds(外部来源 H3YUN)` / `production_orders` / `receipts`。REST 端点 `GET /api/h3-facts/ledger/outbound`、`/ledger/production`、`/ledger/receipt`、`/ledger/settlement`（`H3FactImportController`，类级 `integration.h3`）。
- **前端**：`api/business/orders.ts` 提供 `H3LedgerKind` + `LedgerRow`/`LedgerPage` + 四类台账函数（`fetchOutboundLedger`/`fetchProductionLedger`/`fetchReceiptLedger`/`fetchSettlementLedger`）。`H3ImportCenterView` = 页内 Tab「导入与批次 / 历史台账」；历史台账子 Tab 用通用组件 `frontend/src/components/h3/H3LedgerPanel.vue`（`ledgerType` prop 驱动四类表格）。`batchNextAction` 的台账类动作直接切到本页历史台账 Tab（`openLedger`），outbound 仍跳 `/my-orders`。
- **氚云工单接入并入生产工单台账（2026-09）**：原独立页 `views/production/H3ProductionImportView.vue`（路由 `/manufacturing-h3-import`、导航项「生产 · 氚云工单接入」）已整体下线。接入入口内嵌于 `H3LedgerPanel` 的 production 分支（氚云导入中心 → 历史台账 → 生产任务工单）：「范围 全部/待接入/已接入」radio + 勾选批量接入 + 行内「接入」，接入只回写 `production_orders.linked_manufacturing_order_id`，不删原始工单。`fetchProductionLedger` 支持 `filter=all|available|linked`，响应含 `counts{available,linked}`；`available`=未接入（2026-09 起放开状态限制，任意状态未接入的工单均可接入）。后端 POST `/api/manufacturing/h3-imports` 权限由 `manufacturing.manage` 改为 `integration.h3`；原 GET `/manufacturing/h3-summary`、`/h3-orders` 及 service `h3Summary`/`h3Available` 已删除。
- **路由/导航**：原 `/deprecated/{production,receipt,settlement}-ledger` 与 `views/governance/deprecated/*LedgerView.vue` 已下线，路由保留 redirect → `/h3-imports?tab=ledger&ledger={production|receipt|settlement}`（`H3ImportCenterView` setup 读取 query 预选 Tab）；`navigation.ts` / `routeGroups.ts` / `vue-router.d.ts` 中 `deprecated` 分组与 `RouteGroup` 取值已删除。
- 迁移脚本当前最大版本 V268。历史台账复用现有 `sales_outbounds`/`production_orders`/`receipts`/`h3_import_rows` 表；V260 下线正式销售本地链（出库/收款/利润）与 H3Preparation/期初库存旧入口（表 + RBAC + 功能目录一并清理，`sales_product_source_aliases` 表本身保留、去掉 `source_batch_id` 列）。后续：V261 DROP 空死表 `contract_archives`/`contract_archive_items`；V262 归档（`*_archive_20260904`）并 DROP 旧业务利润/提成域 5 张表（`business_profit_snapshots`/`business_profit_adjustments`/`business_commission_snapshots`/`business_commission_settlements`/`business_commission_settlement_items`）。V263+ 为并行推进的组织主数据（company/department 分离）、氚云生产主数据与任务配置种子等，与本下线线无关（见各迁移文件头）。
- **产品库存单行详情（2026-09-16 新增）**：产品库存列表行（`GET /api/product-inventory/balances`，粒度 = 仓库 × 产品编号，由 `h3_warehouse_stock`（仓库/数量）与 `h3_product_catalog`（名称/规格/单位/采购价）合并）新增「详情」入口。后端 `ProductInventoryService#rowDetail` + `GET /api/product-inventory/detail?warehouseId&productCode&unit`（`product_inventory.read`，只读）返回：`row`（本行快照）、`catalogSource`/`stockSource`（两份来源台账行，含 `id`/导入批次/导入时间；`stockSource` 为 null = 该行只在产品信息台账）、`spread` + `spreadTotal`（同编号跨仓库分布；数量不跨单位合计，只合计金额）。**定位键必须带 `unit`**：V295 后同一仓库同一编号可存在多单位行；仓库明细行应按与列表 `UNIT_EXPR` 同口径的单位兜底（仓库明细单位 → 产品信息台账单位 →「个」）后再匹配，否则会错配到别的单位行。「更新明细」不复用新端点，由前端按来源行 id 调既有 `GET /api/h3-imports/ledger/{product|stock}/{id}/history`（未变化的行不入史）。前端 `components/production/ProductInventoryDetailDrawer.vue`（概览 / 两份来源台账卡片 / 跨仓分布 / 更新明细；缺产品·缺价·编号冲突直接在顶部以 alert 摊开），`ProductInventoryView.vue` 表格与按类目视图均加「操作 → 详情」列；「台账详情 →」跳 `/h3-imports?tab=ledger&ledger={product|stock}&keyword={编号}`（`H3ImportCenterView` 新增 `keyword` query 深链）。**不做库存流水展示**：`product_inventory_transactions` 正式库存账未启用、且 V287 已清空。

## RBAC 权限体系（2026-08 加固）

自研轻量 RBAC（无 Spring Security FilterChain）：DB 会话 Token（SHA-256）+ BCrypt + `@RequirePermission` 注解 + `HandlerInterceptor`。

### 接口拦截策略（`PermissionInterceptor`，/api/**）
按顺序判定：
1. `@PublicApi`（类级/方法级）→ 匿名与登录均放行（登录/验证码/公开官网数据）；
2. 其余一律强制认证，未登录 401；
3. `@LoginOnly`（类级/方法级）→ 仅要求登录、不校验权限；
4. `@RequirePermission`（类级/方法级）→ 校验权限码，未命中 403；
5. **未声明注解的写方法（POST/PUT/DELETE/PATCH）一律 403**，未声明注解的读方法（GET/HEAD/OPTIONS）灰度期放行。

**新增写接口必须声明 `@RequirePermission`（受控操作）或 `@LoginOnly`（全员操作）**，否则会被拦截器拒绝。已标注 `@LoginOnly` 的全员接口：退出登录、文件上传、资产借用/归还/流转、视频收藏/浏览/自建合集。

**文件预览/下载类接口必须 `@PublicApi`**：预览用 iframe/img、Office 转换轮询与下载用原生 fetch（均无法携带 Authorization header），故 `FileAssetController` 的 `preview`/`preview-pdf`/`preview-pdf/status`/`download` 与 `FileController` 的 `/api/files/**` 系列全部标注 `@PublicApi`（文件内容本就公开，URL 含 encoded 文件名防枚举）。新增此类"前端原生访问的资源接口"时保持一致；列表/上传/登记等 axios 接口保持登录态。注意 Javadoc 中勿写含 `*/` 的路径（如 `/api/files/**/...`）。

### 功能级授权（V249：功能 × 数据范围 × 操作）
角色授权不再平铺勾选 90 个权限码，而是按「业务功能」配置（配置层），保存时展开回写权限码（执行层零改动）：
- `system_features` 功能目录（28 项，如 `MANUFACTURING`、`SALES_OUTBOUND`、`PRODUCT_CENTER`）；
- `system_feature_actions` 权限码归类：功能 × 动作（VIEW/CREATE/UPDATE/DELETE/EXECUTE），其中查看动作带范围变体（`read.self` / `read.department` / `read.all`）；
- `system_role_feature_grants` 角色 × 功能 授权行：`data_scope`（ALL/DEPARTMENT/CUSTOM/SELF）+ 自定义员工 ID 与部门名（JSON 数组）+ `selected_actions`（`VIEW` 表示查看，其余为该功能下的 permission_code）。

关键约定：
- 保存链路 `AccessControlService#replaceRoleGrants`：先归一化授权（空动作=忽略该功能、剔除重复功能），再按范围变体展开写 `system_role_permissions`（ALL→`read.all`、DEPARTMENT→`read.department`、**CUSTOM 与 SELF 均授 `read.self`**，行级过滤再按自定义名单放行）；`system_roles.data_scope` 仅作兼容列（员工角色面板仍展示），新配置一律以 grants 为准。
- 运行时：`CurrentUserFactory#aggregateFeatureScopes` 按员工的全部角色聚合（`ALL>DEPARTMENT>CUSTOM>SELF` 取最高级，自定义名单取并集）→ `CurrentUser.featureScopes`，`/api/auth/me` 返回。
- 配置端点：`GET /api/access-control/features`（功能目录含动作清单）、`GET|POST|PUT|DELETE /api/access-control/roles[/id]`（载荷 `{roleName, grants:[{featureCode,dataScope,employeeIds,departmentNames,actions}]}`）。
- 前端：`views/system/RolePermissionView.vue` 按功能分类折叠渲染（数据范围下拉 + 自定义员工/部门多选 + 动作勾选），类型与 API 见 `api/accessControl/index.ts`（`FeatureGrant`、`fetchSystemFeatures`、`createRole`、`updateRole`）。
- 新增功能/动作需同步：迁移脚本 INSERT（`system_features` + `system_feature_actions`）、`security/FeatureCode` 常量、对应 Service 的数据范围标注；权限配置页自动渲染无需改代码。
- 典型配置（V249 内置）：生产跟单 `PRODUCTION_TRACER` 在 `MANUFACTURING` 上为 DEPARTMENT + 查看/报工/异常上报；此前角色级 `data_scope=ALL` 使其跨部门看到全部生产任务（生产看板 5369 条 → 收敛后仅本人 53 条）。

### 行级数据范围（`DataScopeSupport`，security 包）
`DataScopeSupport` 统一追加 SQL 条件，**禁止各 Service 自写私有 appendScope**。两种列形态：
- `appendByName(where, params, nameColumn[, featureCode])`：业务表只有业务员姓名列（如 `salesperson_name`、`b.salesperson_name_snapshot`）。SELF 姓名等值；DEPARTMENT 用 `EXISTS(SELECT 1 FROM employees scope_employee WHERE scope_employee.name=列 AND scope_employee.department=?)`。
- `appendByEmployeeId(where, params, employeeColumn, departmentColumn[, featureCode])`：业务表持有员工 ID 列且已 JOIN employees（如提成快照 `s.employee_id`/`e.department`）。SELF 员工 ID 等值；DEPARTMENT 部门列等值。

尾部 `featureCode` 参数（`FeatureCode` 常量）为 V249 新增：**传了就按该功能的数据范围判定，不传则回退全局 `dataScope`**。CUSTOM 分支 = 本人 OR 员工 ID IN 名单 OR 部门 IN 名单；ALL 分支不追加任何条件。同理 `CurrentUserService#canAccessEmployee/canAccessSalesperson/scopeOf` 也有带 `featureCode` 的重载。

已按功能标注的服务（括号内为标注处数）：ManufacturingService(6)、SalesOutboundProfitService(6)、ProductionBoardService(4)、BusinessCommissionService(4)、BusinessProfitService(3)、FormalSalesShipmentService(3)、MonthlySalesCommissionService(3)、FormalSalesOrderService(2)、FormalReceiptService(2)、FormalProfitService(2)、SalesOutboundCommissionService(2)、H3BusinessQueryService(2)。各模块的 `requireVisible`/`requireSettlementScope` 等行级越权校验保留在各 Service 内。

### 权限码
- 命名 `{域}.{操作}`（`read.self/department/all`、`*.manage`、`settle` 等）；迁移脚本硬编码注册到 `system_permissions`。
- 启动期 `PermissionCodeValidator` 扫描 `@RequirePermission` 引用与表比对（默认 FAIL，见 `SecurityProperties.permissionCodeValidation`）。
- 前端：路由 `meta.permission` + 菜单/按钮 `can()`；RolePermissionView 承担「功能授权（主）+ 权限码启停用（弹窗，`setPermissionEnabled` PUT `/access-control/permissions/{id}/enabled`）+ 角色成员管理」。
- 部署检查：`docker-compose.yml` 已强制 `APP_SECURITY_ALLOW_EMPLOYEE_HEADER=false`、`APP_SECURITY_COMPAT_ADMIN=false`。

## 样式系统

样式位于 `frontend/src/styles/`，按三层组织，通过 `index.css` 统一导入。

- **核心层**：`tokens.css`（设计令牌）→ `reset.css` → `element-plus.css` → `utilities.css` → `accessibility.css` → `route-progress.css` → `themes/light.css` + `themes/dark.css`
- **业务模块（多组件共享）**：`modules/` 下 11 个 CSS（product-editor、dialog-layout、list-controls、list-page-surfaces、action-menu、asset-grid、media-dialog-shared、field-grid、image-editor-inline、catalog-table-image、multi-tabs）
- **页面级专属**：`master-layout.css` + `packaging-mold-view.css`；另有 5 个页面级 CSS（`company-certifications-tab.css`、`asset-grid.css`、`asset-toolbar.css`、`packaging-detail-drawer.css`、`media-account-view.css`）由各组件 `<style>` 内 `@import` 按需加载

官网样式位于 `corporate-website/src/assets/styles/`，14 个 CSS + `style.css` 入口，级联顺序：tokens → base → components → home → products → credentials → patents → solutions → about → contact → packaging → supplies → molds → prototype-system → site-redesign。

使用 CSS 变量 (--color-*, --space-*, --radius-*, --shadow-*) 保持一致性，避免硬编码值。

## 开发命令

```bash
cd frontend
npm run dev           # 开发服务器
npm run build         # 生产构建
npm run lint          # ESLint 检查
npm run lint:fix      # ESLint 自动修复
npm run format        # Prettier 格式化
npm run format:check  # 格式检查
npm run typecheck     # TypeScript 类型检查
```

```bash
cd backend
mvn spring-boot:run   # 启动
mvn clean package     # 构建
mvn test              # 测试
```
