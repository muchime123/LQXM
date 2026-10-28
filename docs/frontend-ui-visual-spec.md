# 公共视觉规范

> 目标：明确「哪些视觉值必须来自设计令牌、哪些由公共组件统一管理、业务可以自定义什么」，
> 避免同一设计代码在多个文件重复维护，也避免为了统一而牺牲业务功能。
> 上游：`docs/frontend-ui-b5b-visual-governance-report.md`（本规范的证据与整改记录）
> 生成日期：2026-10-28（B5-B）

---

## 1. 分层与归属

| 层 | 文件 | 职责 | 禁止 |
|---|---|---|---|
| 设计令牌 | `styles/tokens.css` | 颜色 / 间距 / 圆角 / 字号 / 阴影 / 层级 / 过渡的唯一来源 | 组件与业务 CSS 直接写字面量 |
| 主题 | `styles/themes/light.css`、`dark.css` | 仅覆盖令牌，不写组件选择器 | 在主题文件里改某个组件的布局 |
| Element Plus 对齐 | `styles/element-plus.css` | EP 变量映射到本项目令牌 | 业务页面绕过它直接改 EP 变量 |
| 公共布局模块 | `styles/modules/*.css` | 跨组件共享的结构性外观（弹窗、抽屉、页面头部、字段栅格…） | 组件 scoped 样式重复维护同一份外观 |
| 公共组件 | `components/common/*` | 结构与行为 | 把业务流程写进组件 |
| 业务页面 | `apps/archive/views/**` | 页面编排 | 手抄公共组件的外观 |

**判断口诀**：这个值换主题要不要变？要变 → 必须走令牌。这个外观是否被两个以上组件共用？是 → 收进 `modules/`。都不是 → 留在组件或页面内，并写清理由。

---

## 2. 必须来自设计令牌的视觉属性

以下属性**禁止**在组件或业务 CSS 中写字面量，一律引用令牌：

| 属性 | 使用令牌 | 备注 |
|---|---|---|
| 任何颜色（背景 / 边框 / 文字 / 状态） | `--color-*` | 唯一例外见 §5 |
| 投影 | `--shadow-*`；弹窗用 `--shadow-dialog`、抽屉用 `--shadow-drawer` | 二者已在 `dark.css` 有对应覆盖 |
| 字号 / 字重 / 行高 | `--fs-*` / `--fw-*` / `--lh-*`；组合用 `--text-*` | |
| 字距 | `--tracking-*` | 不再写 `-0.02em` 之类 |
| 间距 | 场景化别名优先：`--space-control-gap`、`--space-section-gap`…；数字梯度次之 | 新增场景先在 `tokens.css` 加别名 |
| 圆角 | `--radius-*`；胶囊用 `--radius-pill` | 组件专属尺寸见 §5 |
| 控件高度 | `--control-height-*` | |
| 过渡 | `--transition-fast/base/slow`、`--transition-modal/drawer/…` | 不再写 `0.15s ease` |
| 禁用态透明度 | `--opacity-disabled` | |
| 层级 | `--z-*` | JS 侧用 `constants/uiLayers.ts` |
| 弹窗/抽屉宽度 | `--dialog-width-*` / `--drawer-width-*` | 由 `LqDialog` / `LqDrawer` 的 `size` 统一映射 |

**新增令牌的三条硬性要求**：

1. **命名**：`--{类别}-{语义}`，禁止一次性色号命名（`--red-500` 这类）；
   语义别名（`--space-section-gap`）优先于数字梯度（`--space-lg-xl`）。
2. **归属**：写进 `tokens.css` 对应分组，并在分组注释里说明「谁在用」。
3. **主题同步**：凡是颜色类令牌，必须在 `themes/dark.css` 给出覆盖值，或在令牌注释里写明
   「深浅两主题同值，故不覆盖」（现有先例：`--color-text-on-overlay`）。
   `PublicStyleTokens.contract.test.ts` 会拦截未在 `dark.css` 覆盖的孤儿令牌。

**防止过度抽象**：不要为单个使用点新增令牌。一个值只出现一次、且不承载主题语义时，保留字面量并在注释里写明归属，比制造一个无人引用的令牌更好。当前令牌库中有 79 个无外部使用者的定义，是前车之鉴（见治理报告 §4）。

---

## 3. 由公共组件统一管理的样式

| 能力 | 唯一管理者 | 业务应该做什么 |
|---|---|---|
| 弹窗三段式 | `LqDialog` + `styles/modules/dialog-layout.css` | 用 `mode` / `size` / `dirty` / `loading`，不自己写 header/footer |
| 抽屉三段式 | `LqDrawer` + `styles/modules/drawer-layout.css` | 同上 |
| 页面头部 | `.page-surface-header`（`styles/modules/list-page-surfaces.css`），由 `PageHeader` 与 `ListPageLayout` 共用 | 用 `<PageHeader>` 或 `<ListPageLayout show-header>`，**不要**手抄 `.page-header` |
| 列表状态 | `DataState` / `ListPageLayout`（loading/empty/error/forbidden/stale） | 传状态，不自己写空态/错误态 |
| 表格外观与密度 | `LqTable` 的 `density` | 不覆盖 `el-table` 内部类 |
| 分页 | `LqPagination` / `ListPageLayout` 的 `pagination` 插槽 | 不直接用 `el-pagination` |
| 搜索与批量 | `SearchToolbar` + `SearchToolbarBatchBar` | 不自建第二套批量栏 |
| 详情字段栅格 | `LqDescriptions`、`modules/field-grid.css` | 不手写栅格 |
| 图片/媒体 | `LqThumbnail`、`modules/asset-grid.css`、`modules/media-dialog-shared.css` | 复用，不另写 |

**公共组件之间不得复制外观**：B5-B 已把 `PageHeader` 与 `ListPageLayout` 重复的 72 行头部外观收敛到 `.page-surface-header`。今后新增公共组件若需要「页面级头部」，必须复用这个类，而不是再写一份。

---

## 4. 业务页面可以自定义什么

**允许**：

- 通过组件的 props 表达差异（`size`、`mode`、`density`、`dirty`、`loading`、`emptyText`…）
- 通过插槽注入内容（`#actions`、`#footer`、`#title`、`#toolbar`…）
- 在**页面根节点**挂自己的 class，用 `:deep()` 覆盖**自身实例**的局部细节
- 传透传属性（`header-class`、`body-class`、`modal-class`…）给 `LqDialog` / `LqDrawer`

**禁止**：

- 重写 `.el-dialog__header` / `.el-drawer__header` / `.lq-drawer__head` 这类结构类
- 手抄一份页面头部（当前 `WebsiteDashboardView` / `WebsiteSolutionsView` / `WebsiteBannerView` 即属此类，待 B6 迁移）
- 在页面里复制公共组件的按钮、徽章、空态样式
- 为绕开组件限制而新建第二套弹窗/抽屉组件

**`:deep()` 的正确姿势**（B4 已实测 Teleport 不影响后代选择器命中）：

```css
/* ✅ 作用于本页实例，且只覆盖本页关心的局部 */
.tracking-detail-drawer :deep(.lq-drawer__body) { background: var(--color-surface-alt); }

/* ❌ 影响所有抽屉，会与其他页面冲突 */
:deep(.lq-drawer__body) { background: red; }
```

---

## 5. 合理保留的硬编码（允许清单）

以下情况**不算违规**，但必须在注释里写明理由：

| 类型 | 现有例子 | 理由 |
|---|---|---|
| 技术性例外 | `border-radius: 50%`（加载圈圆形） | 无对应令牌，语义就是「圆」 |
| 组件专属尺寸 | `dialog-layout.css` 的 `border-radius: 14px`（弹窗面板圆角） | 只有一个使用者，造令牌属过度抽象 |
| 自洽的组件色阶 | `dialog-layout.css` footer danger 按钮的 `#dc2626 / #b91c1c / #991b1b / #ef4444` | 该块已自带 `[data-theme='dark']` 覆盖；机械替换成 `var(--color-danger)` 反而会**改变**深色主题下的现有取值（dark 中 `--color-danger` 是 `#ef4444`） |
| 反色表面上的白色 | 加载圈描边 `rgba(255,255,255,.3)` | 出现在主色填充之上，不随主题变化 |
| Element Plus 对齐补偿 | `element-plus.css` 的 56 处 `!important` | 覆盖 EP 内部变量所必需 |
| CSS 无法引用令牌的场景 | `@media (max-width: 767px)` | 媒体查询不能用 `var()`，断点字面量与 `--bp-*` 保持一致即可 |

**新增硬编码前请自问**：这个值换主题要不要变？会变却不走令牌 = 深色模式缺陷（B5-B 已修掉两处此类缺陷）。

---

## 6. 主题与响应式

- 深色模式**只**通过令牌覆盖实现。任何新增组件/样式若直接写字面色值，在深色主题下必然出错。
- 断点统一为 `--bp-mobile: 767px`（下）、`--bp-tablet: 768px`、`--bp-desktop: 1200px`。
  公共组件至少覆盖 767px 断点（`page-surface-header`、`dialog-layout`、`drawer-layout` 均已覆盖）。
- 抽屉纵向方向（`ttb` / `btt`）下 `size` / `width` 作用于**高度**，CSS 已限高 `92vh`。

---

## 7. 新增业务页面的默认清单

按顺序复用，缺什么再补什么：

1. `<ListPageLayout>`（或仅头部时用 `<PageHeader>`）
2. `<SearchToolbar>`（含批量模式时接 `SearchToolbarBatchBar`）
3. `<LqTable>` + `<LqPagination>`（或 `ListPageLayout` 的 `pagination` 插槽）
4. 详情/编辑 → `<LqDialog>`（居中表单）或 `<LqDrawer>`（侧滑详情，见 `frontend-ui-lqdrawer-usage.md` §1）
5. 空态/加载/错误 → `DataState` / `EmptyState`
6. 详情字段 → `LqDescriptions`
7. 确认类操作 → `LqConfirmDialog`

全部复用的情况下，页面 scoped 样式应当**接近为零**。如果发现自己写了 30 行以上的布局样式，先回头查有没有现成公共能力。

---

## 8. 违规检测

`src/components/common/__tests__/PublicStyleTokens.contract.test.ts` 已固化为自动化门禁：

- 公共布局模块不得出现未登记的硬编码色值 / px 圆角 / 手写过渡 / `opacity: 0.5`
- 弹窗与抽屉投影必须引用 `--shadow-dialog` / `--shadow-drawer`
- 颜色类令牌必须在 `dark.css` 有覆盖（防孤儿令牌）
- `PageHeader` / `ListPageLayout` 不得再在 scoped 样式里自带头部外观
- 关键令牌取值锁定（防止「等价替换」日后被无意改变成视觉变化）

新增公共布局模块时，请把文件名加进该测试的 `LAYOUT_MODULES` 数组。
