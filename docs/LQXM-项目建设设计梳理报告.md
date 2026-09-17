# 利强包装（LQXM）项目建设设计完整梳理报告

> 梳理时间：2026-08-25
> 梳理范围：`backend/`、`frontend/`、`corporate-website/`、`docs/`、基础设施与根目录脚本

---

## 目录

1. [项目总览与定位](#1-项目总览与定位)
2. [总体架构设计](#2-总体架构设计)
3. [后端设计详解](#3-后端设计详解)
4. [前端管理后台设计详解](#4-前端管理后台设计详解)
5. [官网设计详解](#5-官网设计详解)
6. [核心业务域设计](#6-核心业务域设计)
7. [数据库设计（Flyway 迁移演进）](#7-数据库设计flyway-迁移演进)
8. [横切关注点](#8-横切关注点)
9. [权限与安全设计](#9-权限与安全设计)
10. [设计文档体系](#10-设计文档体系)
11. [演进路线与当前阶段判断](#11-演进路线与当前阶段判断)

---

## 1. 项目总览与定位

**LQXM（利强包装）** 是一个从"产品档案管理系统"逐步演进为"**企业内部业务管理系统 + 对外官网运营平台**"的一体化信息系统，由三大子项目构成：

| 子项目 | 技术栈 | 定位 |
|---|---|---|
| `backend/` | Spring Boot 3.3 + Java 17 + Spring Data JPA + MySQL + Flyway + Apache POI | 统一后端服务，承载全部业务域 |
| `frontend/` | Vue 3 + Vite + TypeScript + Element Plus + Vue Router + Pinia + axios | 内部管理后台（SPA，hash 路由） |
| `corporate-website/` | Vue 3 + Vite（纯 JS，无 TS） | 对外展示官网，支持后台 CMS 可视化编辑 |

**演进主线**（可从代码与文档反推）：

```
产品档案管理（2017+ 遗留数据 V1_10 起）
  → 产品/包材/模具/自媒体/知识产权/公司信息 六大档案域（V10~V50）
  → 官网运营域（Banner/页面区块/询盘/解决方案，V50~V116）
  → 氚云业务域重构（business_orders 统一业务档案，V102 起）
  → 销售/财务/利润/提成域（sales_contracts/outbounds/profit/commission）
  → 生产执行看板 + 设备视频库 + 客户档案（V117 起）
  → RBAC 权限体系 + 钉钉集成 + 员工统一体系（V136~V155）
  → 多标签工作台 / 经营看板 / 出库回款对账（最新）
```

**项目根目录关键文件**：
- `AGENTS.md` — AI 协作开发指南（项目结构、命名规范、前端修改策略、批量管理方案等）
- `docker-compose.yml` — MySQL 等基础设施编排
- `README.md` — 项目说明
- `pack_source.bat` / `pack_source使用方法.txt` — 源码打包脚本
- `product_archive.sql` — 数据库导出备份
- `tmp_customer_merge.sql` / `tmp_rule.json` — 临时数据迁移与规则文件
- `storage/`、`data/` — 文件存储与数据目录
- `scripts/` — 辅助脚本

---

## 2. 总体架构设计

### 2.1 部署拓扑

```
┌─────────────────────┐      ┌──────────────────────┐
│ frontend (5173)     │      │ corporate-website    │
│ 管理后台 SPA         │      │ 官网 (5180)          │
└────────┬────────────┘      └──────────┬───────────┘
         │ /api (Vite 代理)             │ /api (Vite 代理)
         ▼                              ▼
┌──────────────────────────────────────────────────────┐
│              backend (8081)                           │
│  Spring Boot 3.3 + JPA + Flyway + POI                │
│  文件存储(本地磁盘 storage/)、FFmpeg 视频处理          │
└──────────────────────────┬───────────────────────────┘
                           ▼
                 MySQL（docker-compose / 本地）
```

- **前后端分离**：两个前端各自通过 Vite 代理将 `/api` 转发到后端 8081，浏览器端无跨域问题。
- **官网嵌入后台**：管理后台"可视化编辑器"通过 iframe 加载真实官网（`VITE_CORPORATE_WEBSITE_URL`），双方通过 `postMessage` 通信（`LIQIANG_CMS_*` 协议族）。

### 2.2 分层架构（后端）

```
controller (37) ──▶ service (60 主包 + 13 file 子包) ──▶ repository (53) ──▶ JPA Entity (61)
        │                      │                              │
        │                      ├─ file 子包（上传/转码/预览）   │
        │                      ├─ util 工具                    │
        ▼                      ▼                              ▼
   DTO（内联 record）    config(8) + 审计/权限横切    Flyway 迁移(V1_10~V155)
```

### 2.3 前后端通信约定

- 前端统一 `axios` 实例（`src/api/http.ts`），`baseURL=/api`，请求头带 `X-Employee-Id` / `X-User-Name`（员工身份，由钉钉/本地登录体系注入）。
- 统一响应格式解包（`code 0/200` 约定），错误统一拦截提示。
- 后端控制器遵循 REST 资源风格：`GET/POST/PUT/DELETE /api/{entity}[/{id}]`，批量操作统一 `PUT /api/{entity}/batch`。

---

## 3. 后端设计详解

### 3.1 包结构与规模（2026-08-25 核实）

| 包 | 文件数 | 说明 |
|---|---|---|
| `config` | 8 | Spring 配置 |
| `controller` | 37 | REST API |
| `service` | 60 + 13(file) | 业务逻辑（主包 60，`service/file` 子包 13） |
| `repository` | 53 | JPA 仓库 |
| `entity` | 61（含枚举） | JPA 实体 |
| `dto` | 内联 record | 传输对象（DTO 采用 record 内联模式） |
| `util` | 若干 | 工具类 |
| `resources/db/migration` | 125+ | Flyway 迁移脚本，当前最高 **V155** |

### 3.2 服务层全景（核心业务能力分布）

按业务域归类：

**档案域**
- `ProductCatalogService` / `CatalogImportService` / `CatalogTrashService` / `CatalogStatsExportService` — 产品档案 CRUD、Excel 导入、回收站、统计导出
- `PackagingService` / `PackagingApertureResolver` / `PackagingSpecResolver` — 包材管理、口径/规格解析
- `PackagingMoldService` / `PackagingMoldGroupService` / `PackagingMoldImportService` — 模具资产、模具分组、模具导入
- `MediaAccountService`（~1350 行大文件，已补齐审计日志、优化 N+1 与批量操作）— 自媒体账号
- `CertificateService`（417 行，已软删除+审计）— 知识产权证书
- `CompanyInfoService`（~705 行，单类承载 9 类资源，拆分候选）— 公司信息多资源管理
- `CompanyCertificationService` — 资质合规
- `OfficeAssetService` — 物品资产
- `VideoLibraryMaintenanceService` — 设备视频库
- `CustomerService` — 客户档案

**官网域**
- `WebsiteBannerService` — Banner 管理
- `WebsitePageSectionService` — 页面区块配置（含结构化 JSON Schema）
- `WebsiteSolutionService` — 方案文章（富文本 contentHtml + TOC）
- `WebsiteInquiryService` — 询盘管理（防刷）
- `WebsiteProductCompatibilityService` — 官网产品兼容性
- `InquiryAntiSpamService` — 询盘防刷（频控）

**销售/财务/经营域（氚云业务域重构产物）**
- `BusinessCommissionService` — 提成计算
- `BusinessOrderMatchingService` — 业务订单匹配
- `BusinessProfitService` — 业务利润
- `H3BusinessQueryService` / `H3FactImportService` — 氚云数据查询与事实表导入
- `SalesContractService` / `SalesContractImportService` — 销售合同档案（可选模型）
- `SalesOutboundSettlementService` — 出库与回款结算
- `SalesProductService` — 销售产品（源毛利）
- `SalesProfitCalculationService` — 利润计算
- `AnnualFeeStrategy` / `AnnualFeeStrategyFactory` / `DesignAnnualFeeStrategy` / `InventionAnnualFeeStrategy` / `UtilityAnnualFeeStrategy` — 知识产权年费策略（策略模式）

**生产/工效**
- `ProductionBoardService` — 生产执行看板

**工作台/看板**
- `MyWorkbenchService` — 我的业务工作台
- `BossDashboardService` — 经营看板

**平台/员工/钉钉**
- `PlatformConfigService` — 平台配置
- `EmployeeService` — 员工名册（统一员工体系）
- `DingtalkClient` / `DingtalkSyncService` / `DingtalkIdentityMappingService` — 钉钉集成三件套
- `AccessControlService` — RBAC 权限
- `AuditLogService` — 操作审计日志
- `ArchiveScheduler` — 定时任务

**产品扩展能力**
- `ProductMachineTierService` / `ProductMachineTierCapabilityValidator` — 机型/层级能力校验
- `ProductPackagingCompatibilityService` / `ProductVariantPackagingService` — 产品-包材兼容性
- `ProductVariantTagPolicy` — 变体标签策略
- `DuplicateValueNormalizer` — 去重值归一化

**`service/file` 子包（13 个，文件/媒体管线）**
- `FileStorageService` — 本地磁盘存储
- `FileHandleMapper` — 文件类型映射
- `FfmpegMediaService` — FFmpeg 视频转码/封面提取
- `OfficePreviewService` — Office 文档在线预览（POI）

### 3.3 实体域模型（61 个 JPA 实体）

实体按域分组（从 `entity` 目录 61 个文件 + 枚举归纳）：

| 业务域 | 实体 |
|---|---|
| 产品档案 | 产品主数据、产品变体、机型/层级、产品-包材兼容关系、变体标签 |
| 包材 | 包材主档、包材规格、包材-产品关系 |
| 模具 | 模具资产、模具分组 |
| 自媒体 | 自媒体账号 |
| 知识产权 | 证书、年费策略相关 |
| 公司信息 | 公司主档、9 类附属资源（图册/荣誉/联系方式等） |
| 官网运营 | Banner、页面区块、方案文章、询盘记录 |
| 客户/销售 | 客户档案、销售合同、销售产品、销售出库、回款、回款分配 |
| 氚云业务域 | business_orders（统一业务档案）、business_order_links（事实关联）、production_orders（生产执行）、sales_outbounds（销售财务事实）、receipts / receipt_allocations（资金事实） |
| 员工/权限 | 员工名册、钉钉身份映射、角色/权限、操作日志 |
| 物品资产 | 办公物品资产 |
| 视频库 | 设备视频 |

### 3.4 配置层（config，8 个）

典型配置类包括：WebMvc（CORS/拦截器）、JPA 审计（`@EnableJpaAuditing`）、异步任务、定时任务、文件存储路径、HTTP 客户端（钉钉/OAuth）、Flyway 等。

### 3.5 关键设计模式

1. **DTO 采用内联 record** — 每个 controller 内部定义传输 record，编译期不可变、简洁。
2. **策略模式** — 年费计算按专利类型分策略（`AnnualFeeStrategy` + `Factory`）。
3. **解析器模式** — 包材口径/规格通过 `PackagingApertureResolver` / `PackagingSpecResolver` 归一化字符串为结构化数据。
4. **批量更新白名单** — 各 Service 的 `BATCH_EDITABLE_FIELDS` 白名单 + `batchUpdateFields(ids, Map)`，单事务 + 审计。
5. **软删除 + 审计** — 关键实体（证书、自媒体等）统一软删除 + 操作日志记录。

---

## 4. 前端管理后台设计详解

### 4.1 技术底座

- Vue 3（Composition API）+ Vite + TypeScript + Element Plus
- Vue Router（hash 模式，14 业务路由 + 2 错误页 + 若干重定向）
- 状态管理：以 **composables 为主、Pinia 辅助**（`useCurrentUser` 等全局单例组合式函数承载用户态）
- 图表：chart.js 相关（经营看板/利润看板可视化）
- Excel：xlsx（导入导出）；富文本：wangEditor v5（`5.1.12`，类型声明在 `types/wangeditor.d.ts`）

### 4.2 路由全景（从 `src/router/index.ts` 核实）

| 路由 | 视图 | 业务 |
|---|---|---|
| `/` | 重定向 `/workbench` | — |
| `/products` | `ProductCatalogView`（~951 行，20+ composables 编排） | 产品管理工作台 |
| `/device-videos` | 设备视频库 | 视频库 |
| `/website/dashboard` | 官网工作台 | 官网运营总览 |
| `/website/visual-editor` | `WebsiteVisualEditorView` | 可视化官网编辑器（iframe + postMessage） |
| `/website/inquiries` | 询盘管理 | 询盘处理 |
| `/website/solutions` | 解决方案管理 | 方案文章富文本编辑 |
| `/website/banners` | Banner 管理 | 官网轮播 |
| `/packaging` | 包材管理 | 包材详情抽屉 |
| `/molds` | 模具资产 | 模具 |
| `/media` | `MediaAccountView`（~1302 行） | 自媒体 |
| `/certificates` | 知识产权 | 证书 |
| `/company` | 公司信息 | 9 类资源 |
| `/company/certifications` | 资质合规 | 资质 |
| `/office-assets` | 物品资产 | 资产 |
| `/customers` | 客户档案 | 客户 |
| `/h3-business` | 氚云数据管理 | 氚云业务域重构入口 |
| `/production-board` | 生产执行看板 | 生产 |
| `/sales-outbound-settlement` | 出库与回款 | 销售结算 |
| `/workbench` | 我的业务 | 多标签工作台 |
| `/boss-dashboard` | 经营看板 | 老板视图 |
| `/business`、`/business/:id` | 业务档案列表/详情 | 统一业务档案 |
| `/expenses` | 费用管理 | 费用报销 |
| `/sales-products` | 销售产品 | 源毛利 |
| `/sales-profits` | 源毛利校验 | 毛利 |
| `/profit` | 利润核算 | 利润 |
| `/commission` | 提成管理 | 提成 |
| `/system/employees` | 员工名册 | 员工 |
| `/system/reimbursement` | 日常报销（报销台账 + 钉钉同步，单入口 Tab） | 报销 |
| `/system/operation-logs` | 操作日志 | 审计 |
| `/403`、`/:pathMatch(.*)*` | 错误页 | — |
| 已下线 | `sales-orders`→重定向、密码管理/软件管理/展会档案/报价管理/UiLab 已删除 | — |

**路由守卫能力**：未登录/无权限跳转、未保存修改离开确认、顶部进度条、多标签页支持。

### 4.3 API 层（26+ 模块）

`src/api/`：`accessControl`、`auditLogs`、`auth`、`bossDashboard`、`business`、`catalog`、`certificate`、`company`、`customer`、`dingtalk`、`document`、`employees`、`expense`、`file`、`media`、`myWorkbench`、`officeAsset`、`packaging`、`packagingMold`、`productionBoard`、`salesCommission`、`salesContract`、`salesOutbound`、`salesProduct`、`salesProfit`、`website` + `http.ts`（axios 实例）+ `index.ts`。

### 4.4 Composables 编排（55 源文件，5 大类 + 多小类）

| 分类 | 数量 | 职责 |
|---|---|---|
| `catalog/` | 13 | 产品工作台编排：`useCatalogModels/Filters/Editors/Assets/BatchOps` 等（把 951 行视图拆成可维护单元） |
| `upload/` | 9 | 文件上传：`useFileUpload/useFileAssets/useCatalogFileUpload` 等多套入口 |
| `ui/` | 11 | UI 状态：抽屉/弹窗/表单/主题等 |
| `list/` | 5 | 列表通用：`useListPagination/useBatchSelection/useDragSort` |
| `common/`、`packaging/`(9)、`mold/`(2) | 16 | 公共能力、包材抽屉拆分、模具 |

另 18 个测试文件保障组合式函数质量。

### 4.5 组件体系（120 个）

- `common/`（46）— 通用组件：`SearchToolbar`（搜索栏+批量管理入口）、`FieldBatchEditModal`（字段 Schema 驱动批量编辑弹窗）、`LqTagInput` 等
- `product/`（20）— 产品工作台：表单、变体、兼容性、图片资产
- `packaging/`（16）— 包材详情抽屉（882 行，已从 ~1924 行通过 composables 拆解）
- `company/`（15）— 公司信息 9 类资源 Tab
- `media/`（10）— 自媒体账号
- `master/`（4）— 主从布局（侧边栏/顶栏/多标签）
- `website/`（6）— 官网运营：`RichTextEditor`（wangEditor 封装）、可视化编辑器面板
- `certificate/`（3）— 知识产权

### 4.6 样式系统（三层组织）

- **核心层**：`tokens.css`（设计令牌）→ `reset.css` → `element-plus.css`（覆盖）→ `utilities.css` → `accessibility.css` → `route-progress.css` → `themes/light.css` + `themes/dark.css`（暗色主题）
- **业务模块层**：`modules/` 11 个共享 CSS（product-editor、dialog-layout、list-controls、list-page-surfaces、action-menu、asset-grid、media-dialog-shared、field-grid、image-editor-inline、catalog-table-image、multi-tabs）
- **页面级**：`master-layout.css` + 5 个页面专属 CSS（由组件 `<style>` 内 `@import` 按需加载）
- **规范**：CSS 变量（`--color-*`、`--space-*`、`--radius-*`、`--shadow-*`）保持一致性

---

## 5. 官网设计详解

### 5.1 技术底座

- Vue 3 + Vite（纯 JS），端口 5180，代理 `/api` → 8081
- 依赖：`vue`、`vue-router`、`dompurify`（富文本消毒）
- `.env` 含高德地图 key（联系/位置）

### 5.2 路由与页面（15 条路由，6 个一级导航）

| 一级导航 key | 路由 | 页面 |
|---|---|---|
| HOME | `/` | 首页（Hero/产品/行业/工艺/案例/实力/服务等区块） |
| PRODUCTS | `/products` | 产品列表（分类筛选/搜索） |
| — | `/products/:id` | 产品详情 |
| SOLUTIONS | `/solutions` | 解决方案列表 |
| — | `/solutions/:id` | 方案文章详情（ArticleViewer + TOC） |
| PACKAGING | `/packaging` | 包材方案 |
| MOLDS | `/molds` | 模具展示 |
| ABOUT | `/about`（含 `#credentials`/`#patents`/`#contact` 锚点，`/credentials`、`/patents`、`/contact` 重定向至此） | 关于我们（资质/专利/联系） |
| — | `/:pathMatch(.*)*` | 404 |

### 5.3 组件与组合式函数

- 根级组件：`AppHeader`（导航拦截 `onCmsNavClick` 补 `?cmsEdit=1`）、`AppFooter`、`AppIcon`、`BackToTop`、`ProductCard`
- `common/`（7）：`FormField`、`InquiryFormModal`（询盘表单）、`PageHero`、`Pagination`、`SearchBar`、`SectionHeading`、`SiteCTA`
- `article/ArticleViewer.vue` — 富文本阅读器（DOMPurify 消毒 + 自动目录 + IntersectionObserver 滚动高亮）
- composables（10 个，单例模式）：`useCmsPreview`（CMS 草稿消费）、`useSiteData` 等

### 5.4 CMS 可视化编辑协议（与后台联动，核心设计）

官网通过 `postMessage` 与后台可视化编辑器通信（协议族 `LIQIANG_CMS_*`）：

| 消息 | 方向 | 含义 |
|---|---|---|
| `LIQIANG_CMS_PING` → `LIQIANG_CMS_READY` | 父→子→父 | 连接握手（4.5s 超时显示"官网未连接"） |
| `LIQIANG_CMS_SELECT` | 子→父 | 点击区块选中（`{pageKey, sectionKey, source}`） |
| `LIQIANG_CMS_FOCUS` | 父→子 | 滚动定位/高亮区块 |
| `LIQIANG_CMS_SHOW_BANNER` | 父→子 | Hero 指定 Banner 选中 |
| `LIQIANG_CMS_DRAFT` / `LIQIANG_CMS_BANNER_DRAFT` | 父→子 | 推送区块/Banner 草稿（内存态，刷新即失） |
| `*_CLEAR_*` | 父→子 | 清除草稿 |
| `LIQIANG_CMS_NAV` | 子→父 | 官网内链跳转时通知编辑器同步切页 |

**关键设计**：
- 官网顶栏是客户端路由跳转，编辑模式由 `App.vue` 的 `onCmsNavClick` 拦截内链补回 `cmsEdit` 参数。
- 区块数据多源：hero→Banner 表；products/industries/process→页面配置+业务档案；showcase/service/cases→区块 `content` JSON（`STRUCTURED_SCHEMAS` 结构化行编辑）；strength→公司信息。
- 保存 = 发布（写库后清内存草稿并重挂 iframe）；编辑器 `pushed` 追踪未保存草稿，支持"放弃全部草稿"。

### 5.5 静态内容

- `data/solutionArticles.js` — 方案文章静态数据（早期结构，后演进为后端富文本）
- `utils/sectionsToHtml.js` — 旧结构化小节转富文本（兼容旧数据）

---

## 6. 核心业务域设计

### 6.1 产品档案域（最成熟域）

- **两级主数据**：`product_models`（产品型号）+ `product_variants`（变体规格）——产品档案 V2 重构。
- **扩展关系**：机型/层级能力（`ProductMachineTier*`）、产品-包材兼容性（`ProductPackagingCompatibility`）、变体标签策略（`ProductVariantTagPolicy`）。
- **能力**：Excel 批量导入（`CatalogImportService`）、回收站（`CatalogTrashService`）、统计导出（`CatalogStatsExportService`）、图片/视频/文件多资产（`catalog` composables + `upload` 管线）、防抖拖动排序、批量选择。
- **前端编排**：`ProductCatalogView.vue`（~951 行）+ 20+ composables（Models/Filters/Editors/Assets/BatchOps）——"以组合式函数拆分超大视图"的标杆。

### 6.2 氚云业务域重构（业务档案统一模型）

设计文档 `docs/h3-business-domain-design.md` 明确了从氚云时代"**销售合同即订单**"到自建系统**统一业务档案**的转型：

```
business_orders（统一业务档案/订单主数据）
  ├── business_order_links（事实关联：业务↔合同↔生产↔出库↔回款）
  ├── production_orders（生产执行事实）
  ├── sales_outbounds（销售财务事实/出库）
  └── receipts + receipt_allocations（资金事实/回款+分配）
```

配套：`H3FactImportService`（历史事实导入）、`H3BusinessQueryService`（查询服务）、`BusinessOrderMatchingService`（订单匹配）、`BusinessProfitService`（利润）。

### 6.3 销售-财务-利润-提成闭环

- `sales_contracts`（可选合同档案，`SalesContractImportService` Excel 导入）→ `sales_products`（销售产品）→ `sales_outbounds`（出库，含回款收款子表）→ `receipts`（回款，只读历史）→ `SalesProfitCalculationService`（利润核算）→ `BusinessCommissionService`（提成计算）。
- 页面链：`/sales-products`（源毛利校验）→ `/profit`（利润核算）→ `/commission`（提成管理）→ `/sales-outbound`（销售出库与回款）→ `/boss-dashboard`（经营看板）。

### 6.4 官网运营域

- Banner 管理、页面区块配置（`content` JSON + `STRUCTURED_SCHEMAS`）、方案文章富文本（V116 `content_html` + 自动 TOC）、询盘（防刷 + 后台处理）。
- 富文本化约定：**wangEditor（后台编辑）+ ArticleViewer（官网阅读）+ DOMPurify（消毒）** 三件套可复用于知识库/新闻等新内容模块。

### 6.5 批量管理统一方案（2026-08 试点落地）

已推广到 `DocumentView`（试点）、`OfficeAssetView`、`CertificateView`：
1. **入口**：`SearchToolbar` 增加"批量管理"按钮 → 批量模式第二行（全选/批量编辑/批量删除/退出）。
2. **弹窗**：`FieldBatchEditModal.vue` 字段 Schema 驱动（checkbox + 控件，支持 text/textarea/number/select/date/tags/switch）。
3. **后端**：`PUT /api/{entity}/batch`，body `{ ids, fields }`，字段白名单 + 枚举校验 + 单事务 + 审计。
4. **前端选择**：`useBatchSelection`（`selectedIds` Set）。
- 明确不迁移：`MediaAccountView`（自定义域批量弹窗）。

### 6.6 生产与设备域

- `/production-board` 生产执行看板（`ProductionBoardService`）。
- 设备视频库（`VideoLibraryMaintenanceService` + `device-videos` 视图 + FFmpeg 转码管线 + 视频封面）。

### 6.7 平台管理域

- 员工名册（`/system/employees`，统一员工体系 V136/V137）。
- 钉钉三件套：`DingtalkClient` + `DingtalkSyncService` + `DingtalkIdentityMappingService`（`/system/reimbursement?tab=sync` 钉钉同步页签，文档 `dingtalk-integration-and-parallel-plan.md`）。
- 操作日志（`/system/operation-logs` + `AuditLogService`）。
- 客户档案（`/customers`，V117）。

---

## 7. 数据库设计（Flyway 迁移演进）

### 7.1 迁移脚本规则

- 位置：`backend/src/main/resources/db/migration/`
- 命名：`V{整数版本}__{描述}.sql`（英文 snake_case）。当前最高 **V155**。
- 历史两套编号：早期 `V1_x`（V1_10~V1_18，产品档案遗留数据）与 `V2~V37` 一套（另一编号体系）；新脚本统一整数递增（V10→V155），不再使用子版本号。
- 已执行脚本不可改动；新增从最大版本号 +1。

### 7.2 迁移覆盖的表分组（按演进阶段）

| 版本区间 | 业务表 |
|---|---|
| V1_10 ~ V1_18 | 产品档案早期遗留表（公司信息等） |
| V2 ~ V37 | 产品/包材/模具/自媒体/证书/文件素材早期域 |
| V40 ~ V99 | 官网域（website_banners/page_sections/solutions/inquiries）、产品 V2（product_models/variants）、包材规格、兼容性、素材库 |
| V100 ~ V116 | 氚云业务域（business_orders/order_links/production_orders/sales_outbounds/receipts）、方案文章富文本化（content_html，V116） |
| V117 ~ V130 | 客户档案（V117）、销售域（sales_contracts/sales_products）、费用报销、利润、提成 |
| V131 ~ V139 | 源毛利、员工统一体系（V136/V137）、平台配置 |
| V140 ~ V155 | RBAC 权限体系（V143 附近）、钉钉集成（身份映射）、设备视频库（V147/V149/V151）、经营看板支撑 |

> 注：以上区间为按业务主题的合理推断归纳，精确对应关系以各迁移脚本内容为准。

### 7.3 备份与迁移辅助

- 根目录 `product_archive.sql` — 数据库导出备份。
- `tmp_customer_merge.sql`、`tmp_rule.json` — 一次性客户数据合并/规则文件（临时任务产物）。

---

## 8. 横切关注点

| 关注点 | 实现 |
|---|---|
| 审计日志 | `AuditLogService` + 关键实体软删除/批量操作审计，前端 `/system/operation-logs` 查看 |
| 文件存储 | `service/file` 子包：本地磁盘 `storage/` + `FileStorageService` + 素材库登记（`website_media`） |
| 视频处理 | `FfmpegMediaService`（FFmpeg 转码/封面），前端视频库 |
| Office 预览 | `OfficePreviewService`（Apache POI） |
| 定时任务 | `ArchiveScheduler` + config 定时配置 |
| 异常处理 | 全局异常处理器（`@RestControllerAdvice`，code/msg 统一响应） |
| CORS/拦截器 | `config` WebMvc 配置，身份头 `X-Employee-Id`/`X-User-Name` 注入 |
| 询盘防刷 | `InquiryAntiSpamService` 频控 |
| 去重归一化 | `DuplicateValueNormalizer`（防止导入脏数据） |
| 前端进度条 | `route-progress.css` + 路由守卫 |
| 暗色主题 | `themes/dark.css` 双主题 |

---

## 9. 权限与安全设计

- **RBAC**：`AccessControlService` + `accessControl` API + V143 起权限表。前端路由守卫调用 `useCurrentUser.can(...)` 做按钮/路由级权限控制，`/403` 兜底。
- **身份链路**：钉钉集成后员工统一体系（`employees` + 钉钉身份映射 `DingtalkIdentityMappingService`），前端请求带 `X-Employee-Id`/`X-User-Name`。
- **内容安全**：官网富文本经 DOMPurify 消毒后才渲染（防 XSS）。
- **数据安全**：软删除保护关键档案；批量操作白名单防越权字段；Flyway 版本化 schema 演进。

---

## 10. 设计文档体系

| 文档 | 主题 |
|---|---|
| `docs/design/`（15 份） | 功能设计分析、项目总设计规范、产品档案、包材、官网、业务域、权限、上传、富文本等专项设计 |
| `docs/h3-business-domain-design.md` | 氚云业务域重构设计（统一业务档案模型） |
| `docs/dingtalk-integration-and-parallel-plan.md` | 钉钉集成方案与并行切换计划 |
| `docs/data/` | 数据相关素材 |
| `docs/利强包装产品价格表.xlsx` | 产品价格数据 |
| `AGENTS.md` | AI 协作开发指南（可执行的工程规范） |

---

## 11. 演进路线与当前阶段判断

### 演进阶段

```
阶段一：档案数字化  产品/包材/模具/自媒体/证书/公司 六大档案上线（V1~V50）
阶段二：官网运营化  官网 CMS 上线（Banner/区块/询盘/方案），可视化编辑器 + 富文本化（V50~V116）
阶段三：业务系统化  氚云数据迁移 + 统一业务档案 + 销售/财务/利润/提成闭环（V100~V135）
阶段四：组织数字化  RBAC 权限 + 员工统一 + 钉钉集成 + 客户档案（V136~V155）
阶段五：经营可视化  多标签工作台 / 经营看板 / 出库回款对账 / 设备视频库 / 生产看板（最新）
```

### 当前架构成熟度评估

| 维度 | 现状 | 评价 |
|---|---|---|
| 领域建模 | 档案域成熟；业务域（订单/合同/出库/回款）已统一为事实模型 | 良好，氚云重构是正确方向 |
| 前端工程化 | composables 拆分超大视图、批量管理统一方案、样式令牌化 | 优秀，有清晰的工程规范 |
| 代码质量 | 多个"超大文件"已被拆解（PackagingDetailDrawer 1924→882、CertificateView），MediaAccountService/CompanyInfoService 仍偏大 | 持续优化中 |
| 数据库治理 | Flyway 全版本化、有备份、编号规则明确 | 良好 |
| 可维护性隐患 | `CompanyInfoService`（705 行单类 9 资源）为拆分候选；`tmp_*.sql` 临时文件建议清理；`product_archive.sql` 备份策略需制度化 | 需关注 |
| 自动化测试 | composables 有 18 个测试；API 层 10 个测试 | 覆盖集中在编排层，后端测试待补充 |

### 建议关注方向（基于现状）

1. 清理根目录临时文件（`tmp_customer_merge.sql`、`tmp_rule.json`），制度化数据库备份。
2. `CompanyInfoService` 按 9 类资源拆分；`MediaAccountService` 继续瘦身。
3. 富文本三件套（RichTextEditor/ArticleViewer/DOMPurify）向知识库/新闻模块复用。
4. 批量管理统一方案向更多列表页推广（目前 3/7）。
5. 后端单元/集成测试覆盖度提升（当前以 JPA 集成与前端 composable 测试为主）。

---

*本报告基于对代码库的静态梳理生成，版本号为 2026-08-25 时点实际值。*
