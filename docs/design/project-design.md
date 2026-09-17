# 产品档案系统项目设计规范

> 版本：2026-07-04  
> 目标：把项目从“功能能用”整理成“结构统一、规则明确、方便继续开发和维护”的完整系统。  
> 使用方式：后续 AI 或人工开发新功能前，先阅读本文，再阅读相关模块已有设计文档。

## 1. 项目定位

产品档案系统是围绕“公司产品资料长期沉淀”的内部管理系统，不只是 CRUD 后台。核心价值是把产品型号、规格、图片、说明书、图纸、证书、价格、公司资料、专利证书、软件资料等内容整理成可检索、可维护、可追溯的档案库。

当前技术栈：

- 后端：Spring Boot 3.3、Java 17、JPA、MySQL、Apache POI。
- 前端：Vue 3、Vite、TypeScript/JavaScript 混合、axios、Element Plus。
- 文件：本地 `storage/lq-files` 为主要文件资料存储目录。
- 文档：`docs/design`、`docs/analysis` 已有多份专项设计文档。

## 2. 当前功能地图

```text
产品档案系统

├── 公司信息
│   ├── 公司基础信息
│   ├── 资质证书
│   ├── 财务账户
│   ├── 知识库
│   ├── 团队成员
│   ├── 发展历程
│   ├── 地址信息
│   └── 品牌/公司素材
│
├── 产品管理
│   ├── 产品型号 product_models
│   ├── 产品规格 product_variants
│   ├── 产品素材 product_assets
│   ├── 说明书草稿与版本
│   ├── 产品文档关联
│   ├── 产品卖点
│   ├── 机器元器件配置
│   ├── FAQ
│   ├── 耗材关系
│   ├── 数据完整度
│   ├── 数据健康报告
│   ├── 导入导出
│   └── 回收站/操作日志
│
├── 数据导入
│   ├── Excel 导入
│   ├── 导入预览
│   ├── 文件重关联
│   └── 导入记录
│
├── 内容运营
│   ├── 自媒体账号
│   ├── 平台配置
│   ├── 内容选题
│   ├── 更新记录
│   └── 账号分析
│
├── 专利证书
│   ├── 专利/证书基础信息
│   ├── 流程阶段
│   ├── 年费/缴费记录
│   ├── 缴费清单
│   └── 导出
│
├── 文档管理
│   ├── 文件上传
│   ├── 文档分类
│   ├── 预览/下载
│   ├── 批量操作
│   └── 回收站
│
├── 软件管理
│   ├── 软件档案
│   ├── 文件夹导入
│   ├── 安装状态
│   ├── 下载/打开目录
│   └── 回收站
│
├── 产品定价
│   ├── 产品价格字段维护
│   └── 筛选/批量查看
│
├── 说明书模板
│   ├── 模板编辑
│   ├── 变量预览
│   └── 激活模板
│
├── 密码管理
│   ├── 分类
│   ├── 密码条目
│   └── 查看敏感信息
│
├── 数据健康
│   ├── 完整度统计
│   ├── 缺失项检查
│   └── 重复/异常数据
│
├── 操作日志
└── 回收站
```

## 3. 现状盘点

### 3.1 前端页面

已实现路由位于 `frontend/src/router/index.js`：

| 路径 | 页面 | 定位 |
|---|---|---|
| `/products` | `ProductCatalogView.vue` | 产品档案主工作台 |
| `/company` | `CompanyInfoView.vue` | 公司资料 |
| `/media` | `MediaAccountView.vue` | 自媒体账号 |
| `/content-operation` | `ContentOperationView.vue` | 内容作战中心 |
| `/imports` | `ImportRecordsView.vue` | 数据导入 |
| `/health` | `HealthReportView.vue` | 数据健康 |
| `/certificates` | `CertificateView.vue` | 专利证书 |
| `/documents` | `DocumentView.vue` | 文档管理 |
| `/softwares` | `SoftwareView.vue` | 软件管理 |
| `/trash` | `TrashView.vue` | 回收站 |
| `/audit-logs` | `AuditLogView.vue` | 操作日志 |
| `/pricing` | `PricingView.vue` | 产品定价 |
| `/passwords` | `PasswordManager.vue` | 密码管理 |

需要重点治理的大文件：

- `ProductCatalogView.vue`：约 2600 行，后续修改优先拆分或用脚本精确替换。
- `DocumentView.vue`：约 1900 行，接近拆分阈值。
- `CertificateView.vue`：约 1500 行，继续增加功能前应优先抽公共列表/表单逻辑。

### 3.2 前端复用现状

已有基础：

- `frontend/src/components/common`：`EmptyState`、`LoadingState`、`AssetPreviewModal`、`FilePreviewBody`、`MarkdownEditor` 等。
- `frontend/src/composables`：`useListPagination`、`useListFilters`、`useFileUpload`、`useToast`、`useConfirmDialog`、`useBatchActions`、`useCompleteness` 等。
- `frontend/src/api`：已按模块拆分 API，产品目录为 `api/catalog/*`。
- `frontend/src/styles/index.css`：全局样式入口，已按多个 CSS 分片导入。

主要问题：

- 产品目录组件已拆出很多业务组件，但主页面仍过大。
- API 层存在 TS 与 JS 混用，`api/catalog/*` 仍是 JS。
- 全局 CSS 已有变量，但仍需要拆分为更清晰的 `styles/` 层级。
- 列表页的搜索、分页、空状态、错误状态、批量操作还未形成统一页面模板。

### 3.3 后端结构

后端采用：

```text
controller -> service -> repository/entity -> dto/util
```

主要 Controller：

- `ProductCatalogController`：产品型号、规格、素材、完整度、健康报告、导入导出、回收站、操作日志、卖点、元器件、FAQ、耗材关系。
- `CompanyInfoController`：公司信息、资质、账户、知识库、成员、历程、地址、公司素材。
- `CertificateController`：专利证书、年费记录、流程、导出。
- `DocumentController`：文档、文件、回收站、批量操作。
- `SoftwareController`：软件资料、导入、回收站、下载。
- `MediaAccountController` / `MediaContentTopicController` / `PlatformConfigController`：内容运营。
- `FileController`：统一文件上传、读取、本地路径与打开目录能力。
- `PasswordController`：密码管理。

### 3.4 数据库与迁移

核心表来自 `docs/data/product_archive.sql` 与 `backend/src/main/resources/db/migration`。

主要表：

- 产品：`product_models`、`product_variants`、`product_assets`、`product_documents`、`product_machine_components`、`machine_component_template_items`、`product_selling_points`、`product_faq`、`product_consumable_relations`。
- 旧产品：`products`，属于历史扁平结构。
- 公司：`company_info`、`company_certifications`、`company_accounts`、`company_addresses`、`company_milestones`、`company_team_members`、`company_assets`。
- 专利证书：`certificates`、`certificate_fee_records`。
- 文档/软件/内容：`documents`、`media_accounts`、`media_account_updates`、`media_account_snapshots`、`platform_configs`。
- 系统：`audit_logs`、`import_records`、`password_categories`、`password_entries`、`knowledge_categories`、`knowledge_articles`。

迁移命名现状同时存在 `migration_V*__*.sql` 与 `V1_*__*.sql`。后续新增迁移统一使用：

```text
V1_19__description_in_snake_case.sql
```

当前最新序列为 `V1_18__create_company_assets.sql`，下一次新增应从 `V1_19` 开始。

## 4. 核心业务模型

### 4.1 产品主数据

产品档案的主数据应明确为：

```text
产品型号 product_models
  └── 产品规格/SKU product_variants
        └── 规格级价格、尺寸、功率、包装、配置等差异字段

产品型号 product_models
  ├── 产品素材 product_assets
  ├── 文档关联 product_documents
  ├── 卖点 product_selling_points
  ├── 元器件 product_machine_components
  ├── FAQ product_faq
  └── 耗材关系 product_consumable_relations
```

规则：

- `product_models` 是产品档案主对象。
- `product_variants` 是规格/SKU 对象，通过 `model_id` 归属型号。
- 旧 `products` 表只作为历史兼容或价格等专项功能的过渡数据源；新增核心功能不应继续直接依赖 `products`。
- 产品详情页展示时，优先以型号为主，规格作为型号下的配置列表。
- 如果字段在型号和规格都存在，遵循“型号存通用默认值，规格存覆盖值”的规则。

权威参考：

- `docs/analysis/product-archive-field-ownership.md`
- `docs/design/completeness-scoring-v2-design.md`

### 4.2 文件与业务对象关系

文件不应在每个业务表里重复设计一套上传逻辑。建议统一抽象：

```text
文件 file
  ├── category: main_image / sku_image / detail_image / scene_image / parameter_image
  ├── category: manual / drawing / certificate / brochure / device_video / operation_video
  ├── category: document / software / company_asset / patent / other
  ├── owner_type: product_model / product_variant / company / certificate / document / software
  └── owner_id
```

当前产品素材已有 `product_assets`，公司素材已有 `company_assets`，文档/软件也有各自表。下一步不是强行合并所有表，而是先统一上传、预览、下载、删除、移动、标签、默认文件等行为。

## 5. 前端工程规范

### 5.1 页面职责

页面文件只负责：

- 路由级状态组织。
- 页面级布局。
- 调用 composable 和业务组件。
- 少量跨组件协调。

页面文件不应长期承载：

- 大量表单字段细节。
- 复杂表格列和单元格渲染。
- 文件上传/预览细节。
- 重复的搜索、分页、删除确认逻辑。

超过 1200 行应评估拆分；超过 2000 行必须优先拆分或用脚本精确修改。

### 5.2 推荐组件分层

```text
components/
├── common/          # 纯通用组件
├── layout/          # 页面模板和布局组件
├── product/         # 产品业务组件
├── company/         # 公司业务组件
├── certificate/     # 专利证书业务组件
├── document/        # 文档业务组件
└── media/           # 内容运营业务组件
```

已建立/待补充的通用组件：

- `ListPageLayout`：标题、工具栏、筛选、主体、分页。已建立在 `frontend/src/components/common/ListPageLayout.vue`。
- `DetailDrawerLayout`：标题、操作区、分组内容、底部操作。
- `FilterPanel`：统一筛选栏。已建立在 `frontend/src/components/common/FilterPanel.vue`。
- `DataState`：加载、空、错误、成功状态包装。已建立在 `frontend/src/components/common/DataState.vue`。
- `confirmDelete`：统一删除确认文案和危险操作配置。已建立在 `frontend/src/utils/confirmDelete.ts`，底层复用 `askConfirm`。
- `StatusTag`：统一状态展示。
- `FileUploader`：统一上传入口。
- `FileAttachmentList`：统一附件列表。
- `ImagePreview` / `FilePreview`：统一预览。

迁移试点：

- `frontend/src/views/HealthReportView.vue` 已接入 `ListPageLayout` 和 `DataState`，并将局部样式改为优先使用 design tokens。后续列表/报表页迁移时，以该页面作为轻量示例。
- `frontend/src/components/media/ContentTopicPanel.vue` 已接入 `FilterPanel`、`DataState` 和 `confirmDelete`。后续业务面板内部迁移时，以该组件作为轻量示例。
- `frontend/src/views/ImportRecordsView.vue` 已接入 `ListPageLayout`，文件浏览区接入 `DataState`。后续导入/文件浏览类页面迁移时，以该页面作为轻量示例。
- `frontend/src/views/CompanyInfoView.vue` 已接入 `ListPageLayout`，保留原有 tab 容器和子组件职责。后续多 tab 容器页迁移时，以该页面作为轻量示例。
- `frontend/src/views/PricingView.vue` 已接入 `ListPageLayout`，选配加价删除确认改用 `confirmDelete`，并将局部状态色逐步改为 design tokens。后续“列表 + 编辑弹窗”页面迁移时，以该页面作为轻量示例。
- `frontend/src/views/PasswordManager.vue` 已接入 `ListPageLayout` 和 `DataState`，删除确认改用 `confirmDelete`。后续“侧栏分类 + 列表 + 编辑弹窗”页面迁移时，以该页面作为轻量示例。
- `frontend/src/views/AuditLogView.vue` 已接入 `ListPageLayout`，保留时间线和骨架屏特色，并修复筛选重置顺序及 `getQueryParams` 缺失问题。后续系统能力类页面迁移时，以该页面作为轻量示例。

### 5.3 Composable 规范

已有 `useListPagination`、`useListFilters`、`useFileUpload` 等应作为默认选择。新增列表页优先组合：

```text
useListFilters()
useListPagination()
useBatchSelection()
useBatchActions()
useToast()
useConfirmDialog()
```

命名规则：

- `useXxx` 只放可复用状态和行为。
- 不在 composable 里直接耦合具体页面 DOM。
- API 调用可注入或通过参数传入，避免一个 composable 只服务一个页面。

### 5.4 API 层规范

页面禁止直接写 `fetch('/api/...')` 或散落 axios 调用。统一通过 `frontend/src/api`。

推荐结构：

```text
api/
├── http.ts
├── productCatalog/
│   ├── models.ts
│   ├── variants.ts
│   ├── assets.ts
│   ├── stats.ts
│   └── index.ts
├── company.ts
├── certificate.ts
├── documents.ts
├── file.ts
└── softwares.ts
```

函数命名：

```text
fetchXxxList()
fetchXxxDetail()
createXxx()
updateXxx()
deleteXxx()
restoreXxx()
exportXxx()
importXxx()
```

规则：

- `http.ts` 是唯一 axios 实例入口。
- 新模块优先写 TypeScript。
- 产品目录 `api/catalog/*.js` 后续逐步迁移为 TS。
- API 函数返回值应尽量在 API 层归一化，不把后端差异扩散到页面。

### 5.5 页面状态规范

所有列表和详情页都应处理：

- `loading`：首次加载和刷新加载。
- `success`：正常展示。
- `empty`：空数据，给出下一步行动。
- `error`：加载失败，可重试。
- `saving`：保存中防重复提交。
- `dirty`：未保存离开确认。

已有 `LoadingState`、`EmptyState`、`useStatus`、`useDirty`，后续优先复用。

## 6. CSS 与设计规范

### 6.1 当前基础

`frontend/src/styles/index.css` 是当前全局样式入口，`frontend/src/styles/*.css` 已承载：

- 颜色 token。
- 暗色主题。
- 间距、圆角、阴影。
- 基础按钮、输入、整体布局。

下一步建议把现有按序号拆分的文件继续整理为语义化层级：

```text
frontend/src/styles/
├── index.css
├── reset.css
├── variables.css
├── base.css
├── layout.css
├── components.css
├── utilities.css
└── animations.css
```

`main.ts` 只引入 `styles/index.css`。当前不再保留旧的 `frontend/src/styles.css` 入口，避免出现两个可修改入口；如果后续需要兼容旧引用，再新增一个只转发到 `styles/index.css` 的薄入口文件。

### 6.2 Design Token 规则

新增 CSS 优先使用变量，不直接写散乱颜色和尺寸。

颜色：

```css
--color-primary
--color-success
--color-warning
--color-danger
--color-text-primary
--color-text-secondary
--color-border
--color-page-bg
--color-surface
```

间距：

```css
--space-xs
--space-sm
--space-md
--space-lg
--space-xl
--space-2xl
--space-3xl
```

圆角：

```css
--radius-sm
--radius-md
--radius-lg
--radius-xl
--radius-pill
```

阴影：

```css
--shadow-sm
--shadow-md
--shadow-lg
```

规则：

- 禁止新页面随意新增类似 `#1677ff`、`#1890ff`、`#409eff` 的近似主色。
- 企业后台页面以信息密度、清晰分组、稳定交互为主，不做营销页式大 hero。
- 卡片圆角默认不超过 `8px`，特殊大容器最多 `12px`。
- 字号控制在少量层级：12、14、16、18、20、24。

## 7. 后端与数据库规范

### 7.1 分层

```text
Controller：REST API 入参、响应、HTTP 语义
Service：业务逻辑、事务、校验、日志
Repository：JPA 数据访问
Entity：数据库实体
DTO：请求/响应对象
Util：纯工具能力
```

Controller 不写复杂业务逻辑；Service 不直接处理前端展示文案。

### 7.2 字段命名

数据库：

- 表名和字段名使用 `snake_case`。
- 主键统一 `id`。
- 排序统一 `sort_order`。
- 备注统一 `remark`。
- 状态统一 `status` 或明确业务状态字段。
- 时间字段优先统一 `created_at`、`updated_at`、必要时补 `deleted_at`。

前端/后端 Java 对象：

- Java 和 JS/TS 使用 `camelCase`。
- DTO 字段名与前端字段保持一致。

状态值建议：

```text
active
inactive
draft
published
archived
deleted
```

不要在新模块中混用“启用/正常/有效/1/true/active”等多套状态语义。

### 7.3 迁移

后续迁移使用：

```text
V1_19__description_in_snake_case.sql
```

规则：

- 只追加迁移，不修改已发布迁移。
- 描述使用英文 snake_case。
- 一个迁移只解决一个明确主题。
- 对大表数据变更，迁移中要考虑幂等或先判断字段/表是否存在。

## 8. 统一 CRUD 行为

新增：

```text
点击新增 -> 打开弹窗/抽屉/页面 -> 表单校验 -> 保存中 -> 成功提示 -> 刷新列表/局部更新
```

编辑：

```text
打开详情 -> 修改 -> 标记 dirty -> 保存 -> 成功提示 -> 清除 dirty
```

删除：

```text
点击删除 -> 明确对象名称的确认弹窗 -> 删除/移入回收站 -> 成功提示 -> 刷新列表
```

删除确认文案必须包含对象名，例如：

```text
确定删除产品“LQ380A 气调包装机”吗？
删除后可在回收站恢复。
```

前端新增删除操作优先使用 `frontend/src/utils/confirmDelete.ts`：

```ts
const ok = await confirmDelete({
  entityLabel: '产品',
  entityName: model.name,
  recoverable: true
})
```

批量操作：

- 必须显示选中数量。
- 危险操作必须二次确认。
- 成功后清空选择并刷新统计。

## 9. 操作记录与数据安全

已有 `audit_logs` 和产品目录操作日志接口，应继续扩展到高价值模块：

- 产品型号、规格、价格、素材默认图。
- 公司基础资料、财务账户。
- 专利证书、年费记录。
- 文档、软件、密码。

日志应记录：

```text
操作者
操作时间
实体类型
实体 ID
操作类型
变更前摘要
变更后摘要
```

权限当前不是项目强项，后续至少先定义操作等级：

- 只读：查看、搜索、预览、下载。
- 编辑：新增、修改、上传、标签、排序。
- 危险：删除、批量删除、清空回收站、查看密码明文。
- 管理：导入、迁移、系统配置。

## 10. 数据完整度与质量

完整度评分已有专项设计，后续应作为产品档案核心能力继续维护：

- 基础信息。
- 视觉素材。
- 技术文档。
- 配置完整度。

权威参考：`docs/design/completeness-scoring-v2-design.md`。

首页/数据健康页建议持续呈现：

- 待完善产品数量。
- 缺主图数量。
- 缺说明书数量。
- 缺规格数量。
- 缺价格数量。
- 重复型号/规格。
- 文件失联数量。

## 11. 命名规范

页面：

```text
ProductCatalogView
ProductDetailView
CertificateView
DocumentView
```

组件：

```text
ProductCard
ProductSelector
ProductAssetGallery
CertificateFeeRecords
FileAttachmentList
```

Composable：

```text
useListPagination
useListFilters
useFileUpload
useConfirmDialog
```

API：

```text
fetchProductModels
fetchProductDetail
createProductModel
updateProductModel
deleteProductModel
```

数据库：

```text
product_models
product_variants
created_at
sort_order
```

避免同一概念出现多种命名，例如 `productList`、`productsList`、`listProduct` 混用。

## 12. 新功能开发流程

新增模块前先回答：

1. 属于哪个业务模块？
2. 主实体是什么？是否已有表可以承载？
3. 是否需要文件/图片？能否复用现有上传和预览？
4. 是否需要搜索、分页、批量操作？能否复用列表模板？
5. 是否需要操作日志？
6. 是否影响产品完整度或数据健康？
7. API 是否放在 `frontend/src/api`？
8. CSS 是否使用现有 token？
9. 是否需要数据库迁移？迁移版本号是多少？
10. 是否需要补文档？

实现顺序：

```text
数据模型/迁移
-> 后端 Entity/DTO/Repository/Service/Controller
-> 前端 API
-> composable
-> 业务组件
-> 页面接入
-> 状态/空态/错误态
-> 验证和文档
```

## 13. 近期工程化优先级

P0：

- 明确 `product_models + product_variants` 是产品主数据，停止新增核心功能直接依赖旧 `products` 表。
- 拆分 `ProductCatalogView.vue`，让页面只做路由级协调。
- 建立剩余通用组件，并逐步把列表页迁移到已建立的 `ListPageLayout`、`FilterPanel`、`DataState` 和 `confirmDelete`。
- 继续整理 `styles/` 目录，把序号文件归并为 `variables/base/layout/components/utilities` 等语义文件，保留现有 token。
- 统一新增迁移命名，从 `V1_19` 继续。

P1：

- 将 `api/catalog/*.js` 迁移为 TypeScript。
- 给文档、软件、证书页面复用统一列表状态和批量操作。
- 统一文件上传、预览、删除、标签、默认文件行为。
- 扩展操作日志到公司、证书、文档、软件、密码模块。
- 完善数据健康页，把完整度结果转成可执行问题清单。

P2：

- 梳理权限模型。
- 整理 README，使其指向当前真实架构和启动方式。
- 建立模块级开发模板。
- 补充测试策略和回归清单。

## 14. 已有重要参考文档

- `README.md`：项目基础说明，内容偏早期，需要后续更新。
- `PROJECT_ANALYSIS.md`：历史项目分析。
- `docs/analysis/product-archive-field-ownership.md`：产品字段归属矩阵。
- `docs/design/completeness-scoring-v2-design.md`：完整度评分模型。
- `docs/design/产品档案工作流代码设计文档.md`：产品档案工作流设计。
- `docs/design/company-info-design.md`：公司信息设计。
- `docs/analysis/product-archive-regression-checklist.md`：回归检查清单。
