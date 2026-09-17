# Element Plus 引入方案与迁移计划

> 更新日期：2026-07-04（基于代码库实际状态复核进度）

## 零、当前迁移进度总览

| 阶段 | 状态 | 说明 |
|---|---|---|
| 阶段 0：基础设施 | ✅ 已完成 | 依赖安装、main.ts 注册、element-overrides.css、vite.config.js 分包 |
| 阶段 1：全局通用替换 | ✅ 已完成 | useConfirmDialog/useToast 已重构为 ElMessageBox/ElMessage；30+ 弹窗用 ElDialog；8 个文件用 v-loading |
| 阶段 2：表格与分页统一 | ⚠️ 部分完成 | 7 视图已替换为 ElTable（PricingView、ImportRecordsView、HealthReportView、SoftwareView、PasswordManager、DocumentView、CertificateView）；ProductCatalogView 仍自定义 div 列表/卡片网格，CatalogVariantsTable 也未替换 |
| 阶段 3：表单与筛选统一 | ⚠️ 部分完成 | 11 个文件已使用 ElForm/ElSelect/ElInput（PricingView、SoftwareView、PasswordManager、DocumentView、CertificateView、AuditLogView 等）；CatalogToolbar / ProductEditorModal / VariantEditorModal / ModelEditorModal 仍原生 input/select |
| 阶段 4：文件上传统一 | ❌ 未完成 | 项目无 `<el-upload>` 用法，仍使用原生 `<input type="file">` |
| 阶段 5：样式清理 | ❌ 未完成 | styles.css 中被替代的样式仍保留 |

---

## 一、项目现状摘要

- **框架**: Vue 3.5 + Vite 5 + Vue Router 4
- **UI 依赖**: 纯手写 CSS（styles.css 9613 行 + 12 个视图 scoped + 39 个组件 scoped）
- **弹窗**: 自定义 `useConfirmDialog` composable + 全局 `<section class="modal-backdrop">` 30 处
- **拖拽上传**: 自定义 `useDropZone` / `useLocalDropUpload` composables
- **表格**: 原生 `<table>` 全手动实现，8 种不同的分页器
- **主题**: CSS 变量体系完整，已支持暗色模式（`data-theme="dark"`）
- **后端交互**: Axios 封装在 `api/http.js`

---

## 二、安装与基础配置

### 2.1 安装依赖

```bash
npm install element-plus @element-plus/icons-vue
```

不需要 `unplugin-vue-components` 自动导入 —— 项目已有稳定的显式导入模式，
自动导入会降低代码可读性，不利于后续维护。

### 2.2 按需手动注册（推荐）

在 `src/main.js` 中全局注册，配合 Vite tree-shaking：

```js
import { createApp } from 'vue'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import * as ElementPlusIconsVue from '@element-plus/icons-vue'
import App from './App.vue'
import { router } from './router/index.js'
import './styles.css'           // 保留，用于覆盖 Element Plus 默认变量
import './element-overrides.css' // 新增：Element Plus 主题覆盖文件

const app = createApp(App)

// 注册图标
for (const [key, component] of Object.entries(ElementPlusIconsVue)) {
  app.component(key, component)
}

app.use(router)
  .use(ElementPlus, { size: 'default', zIndex: 2000 })
  .mount('#app')
```

### 2.3 主题覆盖文件 `src/element-overrides.css`

利用你现有的 CSS 变量体系覆盖 Element Plus 默认值：

```css
/* element-overrides.css */
/* Element Plus 会读取 --el-* 变量，这里用你现有的设计 token 覆盖 */
:root {
  --el-color-primary: var(--color-primary);           /* #0f766e */
  --el-color-primary-light-3: var(--color-primary-bg); /* #ecfdf5 */
  --el-color-primary-light-5: #d1fae5;
  --el-color-primary-light-7: #a7f3d0;
  --el-color-primary-light-8: #6ee7b7;
  --el-color-primary-light-9: #a7f3d0;
  --el-color-primary-dark-2: #0d6b63;
  --el-color-success: var(--color-success);            /* #16a34a */
  --el-color-warning: var(--color-warning);            /* #d97706 */
  --el-color-danger: var(--color-danger);              /* #dc2626 */
  --el-color-info: #64748b;

  --el-border-radius-base: 6px;
  --el-border-radius-small: 4px;
  --el-font-family: var(--font-sans);

  --el-bg-color: var(--color-surface);
  --el-bg-color-page: var(--color-page-bg);
  --el-bg-color-overlay: var(--color-surface);

  --el-text-color-primary: var(--color-text-primary);
  --el-text-color-regular: var(--color-text-primary);
  --el-text-color-secondary: var(--color-text-secondary);
  --el-text-color-placeholder: var(--color-text-faint);

  --el-border-color: var(--color-border-light);
  --el-border-color-light: var(--color-border-lighter);
  --el-border-color-lighter: var(--color-border-lighter);

  --el-fill-color-light: var(--color-surface-hover);
  --el-fill-color-lighter: var(--color-surface-alt);
  --el-fill-color-extra-light: var(--color-surface-raised);
}

/* 暗色模式 */
[data-theme="dark"] {
  --el-bg-color: var(--color-surface);
  --el-bg-color-page: var(--color-page-bg);
  --el-bg-color-overlay: var(--color-surface);
  --el-text-color-primary: var(--color-text-primary);
  --el-text-color-regular: var(--color-text-primary);
  --el-text-color-secondary: var(--color-text-secondary);
  --el-text-color-placeholder: var(--color-text-faint);
  --el-border-color: var(--color-border);
  --el-border-color-light: var(--color-border-light);
  --el-border-color-lighter: var(--color-border-lighter);
}
```

### 2.4 vite.config.js 更新

```js
manualChunks(id) {
  if (id.includes('node_modules/vue') || id.includes('node_modules/@vue')) {
    return 'vue-vendor'
  }
  // Element Plus 单独分包
  if (id.includes('node_modules/element-plus')) {
    return 'element-plus'
  }
  if (id.includes('node_modules/xlsx') || id.includes('node_modules/mammoth')) {
    return 'heavy-libs'
  }
  if (id.includes('node_modules')) {
    return 'vendor'
  }
}
```

---

## 三、组件映射表（手写 → Element Plus）

这是迁移的核心参考。每个手写实现都有对应的 Element Plus 组件替代方案。

### 3.1 通用组件替换

| 手写实现 | Element Plus 替代 | 涉及文件数 |
|---------|-------------------|-----------|
| `.modal-backdrop` + `.modal` (30处) | `ElDialog` | 30+ 文件 |
| `useConfirmDialog` composable | `ElMessageBox.confirm` / `ElMessageBox` | 20+ 调用 |
| 原生 `<table>` (8种风格) | `ElTable` + `ElTableColumn` | 8 个视图 |
| 8 种不同分页器 | `ElPagination` | 6 个视图 |
| 手写筛选 select | `ElSelect` + `ElOption` | 8 个视图 |
| 手写搜索 input | `ElInput` (search) | 8 个视图 |
| 手写按钮组 | `ElButton` + `ElButtonGroup` | 全部 |
| 手写 Tag/Badge | `ElTag` | 10+ 文件 |
| 手写 Tab 标签页 | `ElTabs` + `ElTabPane` | 3 个视图 |
| 手写 Tooltip | `ElTooltip` | 分散 |
| 手写 Drawer（产品详情） | `ElDrawer` | 1 个组件 |
| 手写 loading 遮罩 | `ElLoading` directive | 分散 |
| 手写 skeleton 骨架屏 | `ElSkeleton` | 2 个视图 |
| 手写 empty 空状态 | `ElEmpty` | 5 个视图 |

### 3.2 特定功能替换

| 功能 | 手写 → Element Plus | 备注 |
|------|---------------------|------|
| 文件上传（拖拽区域） | `ElUpload` (drag 模式) | 保留 `useDropZone` 用于产品素材 Gallery 的拖拽排序（非文件上传） |
| 统计卡片 | 保持自定义 + 复用 `ElStatistic` | 不强求统一用 Element Plus |
| 工具栏 | 保留 HTML 结构，内部控件用 Element Plus | 工具栏不是标准 UI 组件，保持灵活布局 |
| Toast 通知 | `ElMessage` / `ElNotification` | 替换现有 `useToast` composable |
| 产品管理抽屉 | `ElDrawer` | 替换 `ProductDetailDrawer` |
| 自媒体数据分析抽屉 | `ElDrawer` | 替换手写 drawer |

---

## 四、分阶段迁移计划

> **原则：每个阶段独立可部署、不破坏现有功能。逐步替换，不做大爆炸式重写。**

### 阶段 0：基础设施（1 天）

**目标**：安装 Element Plus，建立主题覆盖，不影响任何现有功能。

- [x] 安装 `element-plus` + `@element-plus/icons-vue`
- [x] 在 `main.js` 全局注册
- [x] 创建 `element-overrides.css`，覆盖主题变量匹配现有设计
- [x] 更新 `vite.config.js` 分包配置
- [x] 验证：现有页面外观无变化，Element Plus 组件可正常使用

### 阶段 1：全局通用替换（2-3 天）✅ 已完成

**目标**：替换所有视图共享的基础交互，影响最大但改动最安全。

#### 1.1 确认对话框 → ElMessageBox ✅

- [x] 重构 `useConfirmDialog` composable 内部实现，用 `ElMessageBox` 替代
- [x] 删除 `App.vue` 中的全局确认弹窗 HTML
- [x] 删除 `styles.css` 中 `.confirm-modal` 相关样式（约 50 行）
- [x] 验证：所有删除确认、离开确认等功能正常

#### 1.2 Toast 通知 → ElMessage ✅

- [x] 重构 `useToast` composable 内部实现，用 `ElMessage` / `ElNotification` 替代
- [x] 删除 `App.vue` 中的 Toast HTML 和动画样式
- [x] 删除 `styles.css` 中 `.toast-*` 相关样式（约 60 行）
- [x] 验证：所有操作提示正常显示

#### 1.3 全局弹窗模式 → ElDialog ✅

- [x] 统一替换 30 处 `modal-backdrop` + `modal` 结构为 `ElDialog`（已落地 32+ 文件）
- [x] 移除 `<Teleport to="body">` 包装（ElDialog 内置）
- [x] 清理各组件中的 `:global(...)` scoped 弹窗样式 hack
- [x] 验证：所有弹窗正常打开、关闭、点击遮罩关闭

#### 1.4 Loading 遮罩 → v-loading 指令 ✅

- [x] 替换手写 `.loading-wrap` / `.loading-line` 为 `v-loading`（已落地 8 个文件：PricingView、HealthReportView、SoftwareView、PasswordManager、DocumentView、CertificateView、CompanyTeamMembers、CompanyMilestones）
- [x] 验证：加载状态正常显示

**阶段 1 预计可删除 styles.css 约 300-500 行。**

### 阶段 2：表格与分页统一（3-4 天）⚠️ 部分完成

**目标**：将 8 种不同风格的表格统一为 ElTable，这是视觉一致性提升最大的阶段。

**当前进度**：7 个视图已替换为 ElTable（HealthReportView、PricingView、SoftwareView、PasswordManager、DocumentView、CertificateView、ImportRecordsView）。仍待处理：ProductCatalogView（复杂组件化架构）、CatalogVariantsTable。

#### 2.1 逐视图替换表格

按复杂度从低到高排序：

| 优先级 | 视图 | 表格特点 | 预计工作量 | 状态 |
|--------|------|---------|-----------|------|
| 1 | HealthReportView | 简单只读表 | 0.5 天 | ✅ 已完成 |
| 2 | PricingView | 只读 + 编辑按钮行 | 0.5 天 | ✅ 已完成 |
| 3 | SoftwareView | 列表/卡片双视图 | 1 天 | ✅ 已完成 |
| 4 | PasswordManager | 简单表格 + 分页 | 0.5 天 | ✅ 已完成 |
| 5 | DocumentView | 列表/卡片双视图 + 批量选择 | 1.5 天 | ✅ 已完成 |
| 6 | CertificateView | 列表/卡片双视图 + 行状态色 | 1.5 天 | ✅ 已完成 |
| 7 | ImportRecordsView | 预览表 + 历史列表 | 0.5 天 | ✅ 已完成 |
| 8 | TrashView | 卡片列表（非表格，保持自定义） | 0.5 天 | — 保持自定义 |
| 9 | AuditLogView | 时间线布局（非表格，保持自定义） | 0 天 | — 保持自定义 |
| 10 | ProductCatalogView | 复杂组件化架构，独立改造 | 2 天 | ❌ 待处理 |
| 11 | CatalogVariantsTable | 自定义配置表格 | 0.5 天 | ❌ 待处理 |

#### 2.2 分页统一

- [x] 已使用 `<el-pagination>` 的视图：PricingView（第 109-117 行）
- [ ] ProductCatalogView 仍使用自定义分页器，待统一为 `ElPagination`
- [ ] 删除 `styles.css` 中约 200 行分页样式

**阶段 2 预计可删除 styles.css 约 600-800 行。**

### 阶段 3：表单与筛选统一（2-3 天）⚠️ 部分完成

**目标**：统一表单控件、筛选区域、搜索框。

**当前进度**：11 个文件已使用 ElForm/ElSelect/ElInput（PricingView、SoftwareView、PasswordManager、DocumentView、CertificateView、AuditLogView、DocFormBody、CompanyTeamMembers、CompanyMilestones、CertificateFeeRecords、CertificateCertModal）。

#### 3.1 筛选工具栏

- [x] 各视图的 `select` → `ElSelect` + `ElOption`（部分视图已完成）
- [x] 各视图的 `input[type="search"]` → `ElInput` (clearable)（部分视图已完成）
- [x] 搜索/重置按钮 → `ElButton`（部分视图已完成）
- [ ] **待处理**：CatalogToolbar 仍使用原生 `<select>`（第 41-67 行）和原生 `<input>`（第 17-22 行）
- [ ] 删除 `.search-input` 的 5 处重复定义

#### 3.2 表单弹窗

- [ ] **待处理**：ProductEditorModal.vue 仍使用原生 input/textarea
- [ ] **待处理**：VariantEditorModal.vue 仍使用原生 input/textarea
- [ ] **待处理**：ModelEditorModal.vue 仍使用原生 input/textarea
- [ ] 各编辑弹窗的表单 → `ElForm` + `ElFormItem`
- [ ] 复用 `ElForm` 的 `rules` 校验替代手写校验逻辑
- [ ] 输入框 → `ElInput`，下拉 → `ElSelect`，文本域 → `ElInput type="textarea"`

#### 3.3 标签页

- [ ] CompanyInfoView 的自定义 tabs → `ElTabs` + `ElTabPane`
- [ ] TrashView 的 pill tabs → `ElTabs` (自定义样式或保持)
- [ ] ProductDetailDrawer 内 tabs → `ElTabs`

### 阶段 4：文件上传统一（1-2 天）❌ 未完成

**当前进度**：项目无 `<el-upload>` 用法，仍使用原生 `<input type="file">`。

#### 4.1 标准文件上传

- [ ] DocumentView 的 `.doc-file-upload-area` → `ElUpload` (drag 模式)
- [ ] ImportRecordsView 的 `.upload-zone` → `ElUpload` (drag 模式)
- [ ] CertificateView 的附件上传 → `ElUpload` (list 模式)
- [ ] BrandAssets / CertificationsList / FinancialAccounts 的上传区 → `ElUpload`
- [ ] ProductCatalogView 第 33 行 `<input type="file" accept=".xlsx,.xls" @change="handleCatalogImport" />` → `ElUpload`

#### 4.2 保留自定义拖拽

- [ ] **保留** `useDropZone` / `useLocalDropUpload` —— 这些用于产品素材的拖拽排序（Gallery 内卡片重排、SKU 图片拖入），不是文件上传
- [ ] **保留** `CatalogAssetGallery` 的拖拽交互逻辑

### 阶段 5：样式清理与优化（1-2 天）❌ 未完成

- [ ] 清理 `styles.css` 中被 Element Plus 替代的样式（预计可删除 2000-3000 行）
- [ ] 清理各视图 scoped 中的重复样式定义
- [ ] 整理 DocumentView 中大量 `:global(...)` hack
- [ ] 统一暗色模式处理（部分视图硬编码了颜色值如 `#0f172a`，需替换为 CSS 变量）
- [ ] 验证全功能回归

---

## 五、不替换的部分

以下手写实现**不在迁移范围内**，保持现有实现：

| 保留项 | 原因 |
|--------|------|
| `App.vue` 侧边栏 + 顶栏布局 | 非标准 UI 组件，Element Plus 没有对应物 |
| 产品管理（ProductCatalogView）核心架构 | 已高度组件化，独立改造计划（阶段 2.1 末尾） |
| 自媒体时间线（AuditLogView） | 非标准布局，Element Plus 无对应组件 |
| 回收站卡片列表（TrashView） | 自定义程度高，ElTable 不适合 |
| `useDropZone` / `useLocalDropUpload` | 拖拽排序逻辑，非文件上传 |
| 健康度面板 | 产品管理的内嵌面板，随产品管理一起改造 |
| 内容编排工作台 | 高度定制化编辑器 |
| 产品详情抽屉内容 | 随产品管理架构一起改造 |

---

## 六、风险控制

### 6.1 兼容策略

- 阶段 0-1 期间，**不删除任何现有代码**，只新增 Element Plus 组件
- 阶段 2 开始，每替换一个视图后立即验证该视图全部功能
- 每个阶段完成后提交一个 Git tag，方便回滚

### 6.2 不兼容的 API 变化

| 变化点 | 影响 | 应对 |
|--------|------|------|
| Element Plus 组件会注入自身的 CSS | 可能影响现有手写组件的间距 | 阶段 0 充分测试 |
| `ElDialog` 的 z-index 管理和现有 `z-index: 1000` 不同 | 弹窗层级 | 配置 `zIndex: 2000` 全局参数 |
| `ElTable` 的事件和数据结构不同于手写 table | 模板需重写 | 这是阶段 2 的主要工作 |

### 6.3 包体积影响

- Element Plus 完整包约 ~800KB（gzip 后 ~200KB）
- 按需手动导入后，预估实际使用的组件 bundle 约 ~300KB（gzip 后 ~80KB）
- 可接受范围内，Vite 分包后不影响首屏

---

## 七、时间线总览

| 阶段 | 工作量 | 状态 | 剩余工作 |
|------|--------|------|----------|
| 阶段 0：基础设施 | 1 天 | ✅ 已完成 | — |
| 阶段 1：全局通用替换 | 2-3 天 | ✅ 已完成 | — |
| 阶段 2：表格与分页 | 3-4 天 | ⚠️ 部分完成 | ProductCatalogView + CatalogVariantsTable + 分页统一 + 样式清理 |
| 阶段 3：表单与筛选 | 2-3 天 | ⚠️ 部分完成 | CatalogToolbar + ProductEditorModal + VariantEditorModal + ModelEditorModal + Tabs 替换 |
| 阶段 4：文件上传 | 1-2 天 | ❌ 未开始 | 全部 |
| 阶段 5：样式清理 | 1-2 天 | ❌ 未开始 | 全部 |

**已完成 3.5/6 阶段（约 60% 进度）。剩余约 6-10 个工作日。**

建议从阶段 2 收尾（ProductCatalogView 表格替换）开始，每完成一个阶段后验证再进入下一阶段。
