# 公司管理模块设计梳理

## 当前定位

公司管理不是单一“公司资料表单”，而是公司级主数据与对外资料中心。它承载公司身份、品牌资产、财务账户、知识库、发展历程和团队组织等信息，应当作为产品档案系统里的“企业档案底座”。

## 当前功能地图

```text
公司管理
├── 公司概览
│   ├── 公司基础身份
│   ├── 品牌名称与中英文展示
│   ├── 经营统计
│   ├── 联系方式
│   └── 公司基础资料编辑
├── 品牌资产
│   ├── Logo / 品牌图片
│   ├── 宣传资料
│   ├── 文件预览
│   ├── 标签管理
│   ├── 分类移动
│   ├── 回收站
│   └── 拖拽排序 / 拖拽上传 / 粘贴上传
├── 财务账户
│   ├── 对公账户
│   ├── 对私账户
│   ├── 退税收款账户
│   ├── 国际收款账户
│   └── 收款码附件
├── 知识库
│   ├── 分类管理
│   ├── 文章列表
│   ├── Markdown 内容
│   ├── 搜索
│   └── 阅读计数
├── 发展历程
│   ├── 年份
│   ├── 标题
│   ├── 描述
│   ├── 附件名
│   └── 排序
└── 团队组织
    ├── 核心成员
    ├── 普通成员
    ├── 头像文件名
    ├── 职位/部门
    └── 排序
```

## 当前实现状态

目前公司管理基本全部内联在 `frontend/src/views/CompanyInfoView.vue` 中。历史上存在过 `components/company/*` 组件，但当前目录已不存在，相关能力被合并到单个视图文件里。

这带来的优点是页面可运行、依赖关系集中；问题是文件过大、模块边界不清、后续新增功能容易继续堆到同一文件。

## 设计原则

1. 公司概览只展示“公司身份”和关键统计，不继续塞复杂维护表格。
2. 品牌资产是公司文件中心，优先复用 `useFileUploadWorkflow` 与 `useDragSort`。
3. 财务账户属于敏感信息，默认脱敏展示，点击后临时显示。
4. 知识库只保留真正有维护价值的公司资料、话术和制度内容，避免变成杂物箱。
5. 发展历程和团队组织是轻量维护模块，不应引入过重编辑流程。
6. 后续拆组件时，以业务模块拆分，而不是按 UI 小块过度拆分。

## 推荐组件边界

后续建议从 `CompanyInfoView.vue` 逐步拆出：

```text
components/company/
├── CompanyOverviewPanel.vue
├── CompanyEditDialog.vue
├── BrandAssetsPanel.vue
├── FinancialAccountsPanel.vue
├── KnowledgeBasePanel.vue
├── CompanyMilestonesPanel.vue
└── CompanyTeamPanel.vue
```

其中品牌资产可以继续拆更细：

```text
BrandAssetsPanel.vue
├── BrandAssetToolbar.vue
├── BrandAssetGrid.vue
├── BrandAssetMoveModal.vue
└── BrandAssetTagEditor.vue
```

## 数据与 API

当前公司 API 集中在 `frontend/src/api/company.ts`，覆盖：

- `company/info`
- `company/stats`
- `company/accounts`
- `company/assets`
- `company/kb/categories`
- `company/kb/articles`
- `company/milestones`
- `company/team-members`

短期可以继续保持一个 API 文件；当类型稳定后，再按业务拆成 `company/info.ts`、`company/assets.ts` 等子模块。

## 已完成的工程化能力

- 品牌资产上传已接入 `useFileUploadWorkflow`
- 品牌资产拖拽排序已接入 `useDragSort`
- 财务账户收款码上传已接入 `useFileUploadWorkflow`
- Tab 元信息已抽到 `frontend/src/constants/company-management.ts`

## 当前主要问题

1. `CompanyInfoView.vue` 文件过大，包含页面、弹窗、表单、上传、排序、知识库、团队、样式。
2. 多个模块各自 `onMounted` 初始化，加载时机没有统一入口。
3. 样式全部内联在同一个 scoped style 中，后续难以迁移和复用。
4. 发展历程和团队组织仍是基础表单形态，缺少附件上传和预览复用。
5. 地址、证照等能力在 API 中存在，但当前公司页没有清晰入口。

## 优化顺序

1. 抽离 tab 配置和模块设计文档。
2. 将概览、财务账户、知识库三个低耦合模块拆成组件。
3. 将品牌资产拆成独立组件，保留现有上传/排序 composable。
4. 统一公司模块的加载入口，避免多个 `onMounted` 分散触发。
5. 将公司相关样式迁移到 `frontend/src/styles/` 的业务模块样式文件。
6. 再评估是否恢复“地址管理”和“证照资质”入口。

## 暂不建议恢复的内容

“业务体系”如果只是展示使命、愿景、价值观、业务模型，短期不建议恢复为独立 tab。它更适合隐藏在公司基础资料编辑里，或作为概览中的少量展示字段。独立 tab 会让公司管理变散，维护价值不高。
