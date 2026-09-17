# 官网运营后台体系 - 实施设计

> 基于 Codex 分析设计 + 当前代码现状调研，制定可落地的分阶段实施计划。
> 更新日期：2026-08-12

## 一、现状盘点

### 1.1 已具备的基础

| 能力 | 现状 | 文件 |
|------|------|------|
| 产品官网字段 | `websiteEnabled` / `websiteFeatured` / `websiteSlug` / `websiteSummary` | ProductModel.java |
| 素材官网字段 | `websiteVisible` / `websiteSortOrder`（V78） | ProductAsset.java |
| 官网公开接口 | 10 个：首页/产品列表/详情/分类/精选/资质/公司/里程碑/询盘提交 | WebsiteController.java |
| 询盘表 | `website_inquiries` 已建表（V74），有提交接口 | WebsiteInquiry.java |
| 内容完整度评分 | ≥40 进列表，≥60 进精选 | WebsiteController.java |
| 产品发布管理页 | WebsiteProductsView.vue：列表+行内开关+编辑弹窗(发布设置/官网图片) | views/WebsiteProductsView.vue |
| 知识文章实体 | KnowledgeArticle 已存在，有 status 字段 | KnowledgeArticle.java |
| 审计日志 | AuditLogService.log(entityType, entityId, modelName, action, changes) | AuditLogService.java |

### 1.2 主要缺口

| 缺口 | 影响 |
|------|------|
| **询盘后台管理完全缺失** | 无视图/API/类型/路由，询盘只能写入无法处理 |
| **资质无官网发布开关** | 所有"有效"资质自动上官网，无法逐条控制 |
| **包材无官网字段** | PackagingModel 完全未接入官网 |
| **公司信息无官网开关** | CompanyInfo 直接全量暴露 |
| **知识文章未接入官网** | KnowledgeArticle 有实体但无公开接口 |
| **无"官网运营"一级导航** | 官网页面挂在"产品管理"子项下 |
| **无官网工作台** | 缺少内容健康度总览 |
| **V76 批量启用未回退** | 所有产品默认发布，含不完整产品 |
| **无权限控制** | 所有接口匿名可访问（用户已明确暂缓） |

### 1.3 用户明确约束

- 登录体系 / Spring Security / 用户角色：**暂缓**，等项目建设差不多了再做
- 官网图片编辑当前体验不佳（只能看主图）：**暂不管这块**，先做内容管理体系

---

## 二、目标架构

```
内部档案数据（产品/包材/资质/公司/文章）
        ↓
官网发布层（website* 字段 + 完整度门槛 + 公开 DTO 白名单）
        ↓
官网运营后台（工作台 + 发布管理 + 询盘管理）
        ↓
公开只读接口（/api/website/**）
        ↓
企业官网（corporate-website/）
```

### 2.1 后台导航结构调整

```
左侧导航
├── 产品管理（现有）
│   └── 官网发布管理（现有，保留）
├── 官网运营（新增一级模块）
│   ├── 官网工作台        ← 内容健康度总览
│   ├── 询盘管理          ← P0 重点
│   ├── 资质发布管理      ← P0
│   ├── 包材发布管理      ← P1
│   ├── 文章与案例        ← P2
│   └── 页面内容管理      ← P1
├── 包材管理（现有）
├── 模具资产（现有）
└── ...
```

---

## 三、分阶段实施计划

### P0：补齐最关键的运营缺口（不含权限）

> 目标：让官网后台"能管起来"，重点是询盘和资质发布。

#### P0-1 询盘后台管理页面

**后端：**
- `WebsiteInquiryRepository` 补充查询方法：按状态筛选、按时间范围、分页、统计待处理数量
- `WebsiteInquiryService` 新增：分页查询、状态流转（pending→processed→closed）、批量更新、详情查看、跟进记录
- `WebsiteInquiryController`（后台管理接口，路径 `/api/website/inquiries/admin/**`）：列表/详情/更新状态/批量处理/统计
- `WebsiteInquiry` 实体扩展字段：
  - `assignee`（负责人）
  - `followUpNote`（跟进记录 TEXT）
  - `nextContactAt`（下次联系时间）
  - `closedReason`（关闭原因）
  - `updatedAt`
- Flyway 迁移：V79__extend_website_inquiries_for_admin.sql

**前端：**
- `types/website.ts`：WebsiteInquiry 类型定义
- `api/website/inquiries.ts`：列表/详情/更新/批量 API
- `views/WebsiteInquiryView.vue`：询盘管理页
  - 统计卡片：待处理/已联系/已关闭/本月新增
  - 列表：联系人/公司/电话/邮箱/关注产品/来源页面/状态/创建时间/操作
  - 行内状态切换 + 批量处理
  - 详情抽屉：完整信息 + 跟进记录编辑 + 状态流转
- 路由：`/website/inquiries`

#### P0-2 资质官网发布管理

**后端：**
- `Certificate` 实体新增字段：
  - `websiteEnabled`（默认 false）
  - `websiteFeatured`（默认 false）
  - `websiteSortOrder`（默认 0）
  - `publicDescription`（公开描述）
- Flyway 迁移：V80__add_website_fields_to_certificates.sql
- `WebsiteController` 资质接口改为按 `websiteEnabled=true` 过滤（替代当前按 `assetStatus='有效'`）
- `CertificateService` 新增 `batchUpdateWebsiteFields`
- `CertificateController` 新增 `PUT /api/certificates/website-batch`

**前端：**
- `CompanyCertificationsView.vue` 增加官网发布列（行内 switch + 批量编辑）
- 或独立 `WebsiteCertificateView.vue`（视复杂度决定）

#### P0-3 恢复"审核后发布"原则

- 评估 V76 影响范围，制定回退策略
- 不直接全量回退（会破坏现有官网展示），而是：
  - 在官网工作台显示"未审核即发布"的产品数量
  - 提供批量下线工具
  - 完整度 < 40 的自动从官网隐藏（已有门槛，确认生效）

---

### P1：形成可用的官网运营后台

> 目标：建立"官网运营"一级模块，补齐工作台和包材发布。

#### P1-1 官网运营一级导航 + 工作台

**前端：**
- `constants/navigation.ts` 新增"官网运营"一级导航分组
- `views/WebsiteDashboardView.vue`：官网工作台
  - 数据卡片：已发布产品数 / 精选产品数 / 待完善产品数 / 待处理询盘数
  - 内容健康度检查表：
    - 缺少主图的产品数
    - 缺少官网摘要的产品数
    - 缺少英文名称的产品数
    - Slug 重复检测
    - 没有规格参数的产品数
    - 即将到期的资质数
  - 最近发布记录
  - 官网快捷预览按钮
- 路由：`/website/dashboard`

**后端：**
- `WebsiteController` 或新 `WebsiteDashboardController`：
  - `GET /api/website/admin/dashboard` 返回工作台聚合数据

#### P1-2 包材官网发布管理

**后端：**
- `PackagingModel` 实体新增字段：
  - `websiteEnabled` / `websiteFeatured` / `websiteSlug` / `websiteSummary` / `websiteSortOrder`
- Flyway 迁移：V81__add_website_fields_to_packaging.sql
- `WebsiteController` 新增包材公开接口：
  - `GET /api/website/packaging` 分页列表
  - `GET /api/website/packaging/{slug}` 详情
- `PackagingService` 新增 `batchUpdateWebsiteFields`
- 公开 DTO 隐藏：单价、供应商、内部备注、起订量

**前端：**
- `views/WebsitePackagingView.vue`：包材发布管理页（复用 WebsiteProductsView 模式）

#### P1-3 页面内容管理（区块化）

**后端：**
- 新建实体 `WebsitePageSection`：
  - `pageKey`（首页/关于我们/联系我们/服务支持）
  - `sectionKey`（主视觉/核心优势/解决方案/数据指标/CTA）
  - `title` / `subtitle` / `content` / `imageAssetId` / `sortOrder` / `enabled`
- Flyway 迁移：V82__create_website_page_sections.sql
- `WebsiteController` 新增：`GET /api/website/page-sections/{pageKey}`

**前端：**
- `views/WebsitePageContentView.vue`：页面区块内容编辑（固定区块 + 富文本/图片）

---

### P2：提升运营能力

#### P2-1 文章与案例管理接入官网

**后端：**
- `KnowledgeArticle` 扩展字段：
  - `articleType`（news/knowledge/guide/case/faq/download）
  - `websiteEnabled` / `websiteSlug` / `websiteSummary` / `coverImageAssetId`
  - `seoTitle` / `seoDescription` / `publishedAt`
- `WebsiteController` 新增文章公开接口

**前端：**
- 知识库管理页增加官网发布开关 + 文章类型分类
- 或独立 `WebsiteArticleView.vue`

#### P2-2 中英文内容完整度

- 扩展完整度评分：中英文双语各算一分
- 工作台分别展示中文完整度 / 英文完整度

#### P2-3 SEO 字段统一

- 产品/包材/文章统一增加 `seoTitle` / `seoDescription`
- 官网页面 `<meta>` 标签由后端返回

#### P2-4 官网预览

- 草稿预览：带临时 token 的预览链接
- 初期可简化为"未发布产品也能在后台查看官网效果"

---

### P3：安全与长期扩展（用户已明确暂缓）

> 此阶段等用户明确启动后再规划。

- 后台登录（Spring Security + JWT）
- 公开接口与后台接口彻底分离（`/api/public/v1/**` vs `/api/admin/**`）
- 角色权限：系统管理员 / 内容编辑 / 内容审核 / 销售
- 询盘提交频率限制 + 验证码
- 发布版本管理与回滚
- 图片自动压缩与 WebP
- 多语言 / 海外站

---

## 四、实施路线图

```
P0（询盘 + 资质 + 回退策略）
  ├── P0-1 询盘后台管理页面          ← 建议第一个做（价值最高、独立性最强）
  ├── P0-2 资质官网发布管理
  └── P0-3 恢复审核后发布原则

P1（官网运营模块成型）
  ├── P1-1 官网运营导航 + 工作台
  ├── P1-2 包材官网发布管理
  └── P1-3 页面内容管理

P2（运营提升）
  ├── P2-1 文章与案例
  ├── P2-2 中英文完整度
  ├── P2-3 SEO 字段
  └── P2-4 官网预览

P3（安全与长期，暂缓）
  └── 权限/安全/版本管理
```

---

## 五、技术约定

### 5.1 数据库迁移
- 版本号从 V79 起递增（V78 已用于素材官网字段）
- 命名：`V{版本}__{描述}.sql`，英文 snake_case

### 5.2 公开 DTO 白名单
- 官网接口（`/api/website/**`）必须使用独立 PublicXxxView record，不直接返回 JPA 实体
- 禁止返回：内部备注、单价、供应商、来源文件路径、真实文件路径
- 现有 `WebsiteController.PublicProductView` / `PublicAssetView` / `PublicCompanyView` 已遵循此约定

### 5.3 后台管理接口
- 询盘后台管理：`/api/website/inquiries/admin/**`
- 批量更新统一模式：`PUT /api/{entity}/website-batch`，body `{ ids, fields }`
- 所有写操作记录审计日志

### 5.4 前端组件复用
- 列表页统一用 ListPageLayout + SearchToolbar + LqTable + DataState
- 批量编辑用 FieldBatchEditModal
- 详情编辑用 LqDialog（mode=form）
- 统计卡片用 MetricGrid + MetricCard

---

## 六、决策记录

1. **权限暂缓**：用户明确"无登录体系暂不修复"，P0-P2 不做权限，P3 统一规划
2. **官网图片编辑暂缓**：用户反馈当前体验不佳（只能看主图），此问题暂不处理，优先做内容管理体系
3. **不推翻现有结构**：在现有档案系统上增加官网发布层，不另建独立 CMS
4. **询盘优先**：P0-1 询盘后台管理独立性最强、业务价值最高，建议第一个实施
