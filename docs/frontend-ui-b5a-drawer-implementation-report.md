# B5-A：`LqDrawer` 公共抽屉组件建设

> 目标：在现有 Element Plus + Lq 公共组件体系中新增**唯一**的 `LqDrawer`，统一后台抽屉的标题、尺寸、内容区、底部操作、关闭交互与基础视觉，不创建第二套交互体系。
> 上游：P0-A 审计、P0-B0 方案、B1 基线、B2 分页、B3 表格、B4 交互契约（同名 `docs/frontend-ui-*.md`）
> 生成日期：2026-10-28

---

## 1. 审计基准和改动文件

### 1.1 基准

| 项 | 值 |
|---|---|
| HEAD | `42bc081c271124620254a7fe79fff55d00a7e904`（未前进；B1~B5-A 均未提交推送，全部以当前实际工作区为准） |
| B4 结束时基线 | 75 文件 / **637 passed / 0 failed / 0 skipped / 0 expected fail** |
| Element Plus | **2.14.2**（本轮直接阅读其 `es/components/drawer` 与 `es/components/dialog/src/use-dialog` 编译产物核实 API，未凭假设） |
| 工具 | vitest `^4.1.9` + @vue/test-utils `^2.4.11` + happy-dom；`vue-tsc ^2.2.12`；eslint `^9.39.4`；vite `^5` |

### 1.2 原生抽屉实测数量：**18 个**（任务描述为 17，以实测为准）

`Get-ChildItem -Include *.vue | Select-String '<el-drawer'` → **18 个文件、18 处标签**。

### 1.3 并行工作区文件（未触碰）

`git status` 共 160 项改动，本轮**只读取清单、不读取修改、不清理、不 stash、不 checkout 覆盖**：

- 后端 23 个文件（`ManufacturingTaskConfig*`、`ProductionTracking*`、`WebsiteProduct*`、`WebsiteMedia*` 及测试）
- 前端并行改动：`api/productionTracking.ts`、`api/website/*`、`navigation.ts`、`routes.ts`、`WebsiteProductsView.vue`、`ManufacturingTaskConfigView.vue`、`ProductionTrackingView.vue`、`ManufacturingWorkshopPanel.vue`、`WebsiteMediaPicker.vue`、`useTrackingFilters.ts`；新增 `ProductionProcessConfigView.vue`、`WebsiteProductVariantsDialog.vue`、`TrackingReviewHistory.vue`
- 其它：`jdk21.tar.gz`、`tools/` 下 Python 脚本与 `classes/`、11 个 `docs/website-*.json`、4 个中文设计文档
- B1~B4 自身的未提交产物（`LqPagination.vue`、`LqTable.vue`、`SearchToolbar.vue` 及各契约测试与文档）

### 1.4 实际改动文件

| 文件 | 类型 | 说明 |
|---|---|---|
| `frontend/src/components/common/LqDrawer.vue` | **新增** | 唯一公共抽屉组件 |
| `frontend/src/components/common/__tests__/LqDrawer.contract.test.ts` | **新增** | 48 条契约（stub 精确断言 + 真实 Element Plus 集成） |
| `frontend/src/styles/modules/drawer-layout.css` | **新增** | 抽屉三段式布局（只使用设计令牌） |
| `frontend/src/styles/tokens.css` | **+14 行** | 新增 `--drawer-width-*` 四档尺寸令牌 |
| `frontend/src/styles/index.css` | **+1 行** | 引入 `drawer-layout.css` |
| `docs/frontend-ui-lqdrawer-usage.md` | **新增** | 使用规范 + 18 个页面迁移批次 |
| `docs/frontend-ui-b5a-drawer-implementation-report.md` | **新增** | 本报告 |

**未修改**：`LqDialog.vue`、`LqConfirmDialog.vue`、`dialog-layout.css`、`LqTable.vue`、`LqPagination.vue`、`SearchToolbar.vue`（其 `M` 状态来自 B4 的 7 行注释，非本轮）、`EmptyState.vue`、任何业务页面、任何后端/数据库/Flyway、公共主题文件（`themes/*.css`）。

---

## 2. 18 个原生抽屉需求矩阵

### A. 标准查看详情抽屉（6 个，无 footer）

| 文件 | 尺寸 | 绑定 | 备注 |
|---|---|---|---|
| `components/production/CoverageBreakdownDrawer.vue` | `min(900px, 94vw)` | `:model-value` | 覆盖率明细 |
| `components/production/ProductBenchmarkSamplesDrawer.vue` | `min(1100px, 92vw)` | `:model-value` | 样本明细 |
| `components/production/ProductOrderHistoryDrawer.vue` | `min(1080px, 94vw)` | `:model-value` | 历史生产任务 |
| `apps/.../settlement/cashflow/CashflowReviewDrawer.vue` | `86%` | `:model-value` | 导入差异 |
| `components/reimbursement/ReimbursementLedgerPanel.vue` | `min(920px, 96vw)` | `v-model` | `#header` 自绘 |
| `components/reimbursement/DingtalkSyncPanel.vue` | `min(880px, 94vw)` | `v-model` | `rtl` + `destroy-on-close` |

### B. 带 footer 的详情 / 列表抽屉（4 个）

| 文件 | 尺寸 | 绑定 | 备注 |
|---|---|---|---|
| `apps/.../governance/H3ImportCenterView.vue` | `min(980px, 94vw)` | `v-model` | `#footer` + `destroy-on-close` |
| `apps/.../production/ProductInventoryView.vue` | `min(980px, 94vw)` | `v-model` | `#footer` |
| `apps/.../commission/MonthlyCommissionView.vue` | `50%` | `v-model` | `#footer` + `destroy-on-close` + `el-drawer__body` 样式覆盖 |
| `components/production/ProductInventoryDetailDrawer.vue` | `min(880px, 92vw)` | `v-model` | `rtl` |

### C. 标准编辑表单抽屉（2 个）

| 文件 | 尺寸 | 绑定 | 备注 |
|---|---|---|---|
| `apps/.../system/RolePermissionView.vue` | `920px` | `v-model` | 新建/编辑角色，`#footer` + `destroy-on-close` |
| `components/production/ManufacturingTaskConfigPanel.vue` | `min(620px, 100vw)` | `v-model` | 可选配置，`#footer` + `#header` + `append-to-body` |

### D. 有特殊宽度、方向或关闭行为的抽屉（2 个）

| 文件 | 尺寸 | 绑定 | 备注 |
|---|---|---|---|
| `components/production/TrackingSourceDetailDrawer.vue` | **动态** `min(900px,94vw)` / `min(660px,92vw)` | `v-model` | `rtl` + `append-to-body` |
| `components/h3/H3LedgerDetailDrawer.vue` | `50%` | `v-model` | `rtl` + **动态 `:append-to-body="appendToBody"`** + `#footer` |

### E. 复杂抽屉：自绘头部 / 内含表格分页下钻 / 深层嵌套（4 个）

| 文件 | 尺寸 | 绑定 | 备注 |
|---|---|---|---|
| `apps/.../production/ProductionTrackingAnalyticsDrawer.vue` | `90%` | `:model-value` | `with-header=false` 自绘头部，内含 `el-table` + `el-pagination` 下钻 |
| `apps/.../production/ProductionTrackingNodeAnalyticsDrawer.vue` | `90%` | `:model-value` | 同上 |
| `apps/.../production/ProductionTrackingWeeklyReviewDrawer.vue` | `90%` | `:model-value` | 同上 |
| `components/production/TrackingDetailDrawer.vue` | `min(1120px, 98vw)` | `v-model` | `header-class` + `append-to-body` + `destroy-on-close` + `#footer` + `#header`，内部嵌套多个 `LqDialog` 下钻 |

### 统计与能力归属

| 现状用法 | 数量 | 归属 |
|---|---:|---|
| `v-model` | 11 | ✅ 公共实现 |
| `:model-value` + `@update:model-value` | 7 | ✅ 公共实现（等价于 `v-model`） |
| `size`（百分比 / `min(px, vw)` / 纯 px） | 18 | ✅ 公共实现：`size` 预设 + `width` 自定义 |
| `direction`（全部显式值都是 `rtl`） | 4 | ✅ 公共实现（默认 `rtl`） |
| `destroy-on-close` | 6 | ✅ 公共实现 |
| `append-to-body` | 7 | ✅ 公共实现（**恒定开启**，与 EP 默认值不同） |
| `with-header=false` | 3 | ✅ 公共实现为 `show-header` |
| `#footer` | 7 | ✅ 公共实现（插槽；默认按钮随 `mode` 变化） |
| `#header` / `#title` 自绘 | 3 | ✅ 公共实现为 `#title` / `#actions`；整块自绘用 `show-header=false` |
| `header-class` 业务样式 | 1 | ✅ attrs 透传（`TrackingDetailDrawer`） |
| 动态 `:append-to-body` | 1 | ❌ **合理例外**：保留原生或先确认需求 |
| 业务专属按钮逻辑 | 全部 | ❌ 留在业务侧，用插槽承载，不进公共组件 |

---

## 3. LqDrawer 最终 API 和设计取舍

### 3.1 API

**Props**：`modelValue`、`eyebrow`、`title`、`subtitle`、`size`（`sm`/`md`/`lg`/`xl`，默认 `md`）、`width`（覆盖 `size`，支持 `'90%'`、`'min(1120px, 98vw)'`；纵向方向下作用于**高度**）、`direction`（`ltr`/`rtl`/`ttb`/`btt`，默认 `rtl`）、`mode`（`detail`/`form`/`workspace`）、`showHeader`、`showClose`、`closeOnClickModal`、`destroyOnClose`、`dirty`、`loading`、`scrollable`、`showFooter`、`showCancel`、`showConfirm`、`confirmText`、`cancelText`、`confirmDisabled`、`zIndex`、`beforeClose`（与 `LqDialog` 完全同语义）。

**Emits**：`update:modelValue`、`close`、`cancel`、`confirm`

**Slots**：`#title`（覆盖标题块，保留关闭按钮）、`#actions`（头部右侧）、默认、`#footer`

**透传**（经自动 attrs 落账到 `el-drawer`）：`header-class`、`body-class`、`footer-class`、`modal`、`modal-class`、`lock-scroll`、`resizable`、`header-aria-level`，以及 `open` / `opened` / `closed` / `open-auto-focus` / `close-auto-focus` / `resize-start` / `resize` / `resize-end` 事件。

### 3.2 关键设计取舍

#### 取舍 1：不复制 `LqDialog` 的 `suppressNextClose` 状态机，改用 `closeEmitted` 标记

本轮**最重要的发现**。核实 EP 源码后确认（`use-dialog.mjs`）：

| 行为 | `el-dialog` / `el-drawer`（共用 `use-dialog`） |
|---|---|
| `close` 派发时机 | `beforeLeave` —— **过渡开始前** |
| `update:modelValue` 派发时机 | `afterLeave` —— **过渡结束后** |

`LqDialog` 的兜底写法是 `nextTick(() => { if (props.modelValue) emit('close') })`。在抽屉上，EP 的 `close` 比 `nextTick` 回调**先到**，于是 `:model-value` 模式下会变成：EP 先派发一次 `close` → `nextTick` 兜底再派发一次 → **`close` 双派发**。

因此 `LqDrawer` 改用 `closeEmitted` 标记让两条路径互相压制，并在抽屉重新打开时重置：

```ts
function emitCloseIntent() {
  closeEmitted = false
  emit('update:modelValue', false)
  nextTick(() => {
    if (props.modelValue && !closeEmitted) { closeEmitted = true; emit('close') }
  })
}
function handleClose() {
  if (closeEmitted) { closeEmitted = false; return }
  closeEmitted = true
  emit('close')
}
watch(() => props.modelValue, v => { if (v) closeEmitted = false })
```

**不回改 `LqDialog`**：B5-A 边界明确禁止重构 `LqDialog`，且其 26 条契约已锁定 stub 语义，改动波及 55 个调用方。此差异已写入组件头注释与测试文件头注释。

#### 取舍 2：恒定 `append-to-body`（与 EP 默认值不同）

`el-drawer` 的 Teleport 判定是 `disabled: appendTo !== 'body' ? false : !appendToBody`，而 `appendToBody` **默认为 `false`** —— 即 **EP 抽屉默认不 Teleport**，会被父级 `overflow` / `z-index` 上下文裁剪。`LqDialog` 恒定 `append-to-body`，`LqDrawer` 保持一致。同时 B4 已实测：Teleport **不会**让业务 `:deep` 后代选择器失效，因此这里不存在两难。

#### 取舍 3：刻意不调用 EP 的 `hide()`，四条关闭路径统一

EP 的 `handleClose` 是 `if (props.beforeClose) props.beforeClose(hide); else close()`。若沿用 `hide()`，`visible` 会被直接置 false，ESC / 遮罩路径的事件顺序就与「关闭按钮 / 取消」路径不一致。`LqDrawer` 把 `:before-close` 绑到一个忽略 `hide` 的包装函数，ESC / 遮罩的关闭意图也转成 `update:modelValue`，于是**四条路径顺序完全一致**：先 `update:modelValue(false)`，后 `close` 恰好一次。

#### 取舍 4：`handleModelUpdate` 去重

EP 在 `afterLeave` 会补发一次 `update:modelValue(false)`。`v-model` 模式下父级早已回写，因此加一条 `if (value === props.modelValue) return` 守卫，避免收到重复回写。

#### 取舍 5：不共享 `dialog-layout.css`

两套布局刻意不合并。弹窗是居中卡片、抽屉是贴边面板，圆角、阴影方向、最大高度约束都不同；B5-A 边界要求「不为减少少量重复而改动稳定的 `LqDialog`」。代价是 footer 按钮约 25 行 CSS 在 `drawer-layout.css` 中独立维护，已在文件头与规范文档中说明。

#### 取舍 6：新增 `--drawer-width-*`，不复用 `--dialog-width-*`

任务要求「优先使用现有变量，仅当抽屉确有独立尺寸需求时才新增」。实测 18 个抽屉的尺寸全是**百分比或 `min(px, vw)`**（`90%`、`86%`、`50%`、`min(1120px, 98vw)` …），而弹窗令牌全是固定 px —— 抽屉宽度本质是「视口占比 + 上限」，语义不同，因此新增四档且每档带 `vw` 上限：

```css
--drawer-width-sm: min(420px, 100vw);
--drawer-width-md: min(720px, 94vw);
--drawer-width-lg: min(1000px, 94vw);
--drawer-width-xl: min(1280px, 96vw);
```

有静态契约测试守护「四档必须含 `vw`」，防止后续被改成固定 px 而在窄屏溢出。

#### 取舍 7：`workspace` 模式不隐式改变宽度

初版曾让 `mode === 'workspace'` 覆盖尺寸，自评后删除：隐式行为会让 `size="md" mode="workspace"` 的宽度变得不可预测。`workspace` 现在**只减少内边距**，需要接近全屏请显式传 `size="xl"` 或 `width="92vw"`。

#### 取舍 8：`size` 是预设、`width` 是原值（与 `LqDialog` 一致）

代价：迁移时必须把原生 `el-drawer` 的 `size="90%"` 改名为 `width="90%"`。选择与 `LqDialog` 对齐是为了「优先与 LqDialog 保持共同语义」，避免两套命名规则；该重命名是纯机械替换，已写进使用规范的迁移步骤。

#### 取舍 9：不支持的能力，明确说明而非假装兼容

- **动态 `append-to-body`**：组件恒定 Teleport，无法表达。`H3LedgerDetailDrawer` 属此类，列为合理例外。
- **`resizable`**：仅通过 attrs 透传给 EP，组件**未封装也未测试**其交互，使用时需自行回归。
- **`close-on-press-escape`**：由组件按 `!loading` 统一接管，业务传了会被覆盖（这是 `loading` 封锁的一部分，属有意行为）。

---

## 4. 与 LqDialog 的共用原则和差异

### 4.1 共用原则（刻意对齐）

| 原则 | 说明 |
|---|---|
| 统一三段式 | Header / Body / Footer，自定义类名用 `__head` / `__body` / `__foot` 避开 EP 的 `__header` / `__footer` |
| 统一的 `mode` | `detail`（默认无 footer）/ `form`（默认有 footer）/ `workspace`（减少内边距） |
| 统一关闭语义 | 四条路径（关闭按钮 / ESC / 遮罩 / 取消）全部经 `beforeClose`；`close` 恰好一次；`cancel` 只在允许后派发；`confirm` 不自动关闭 |
| 统一 `loading` 封锁 | 关闭按钮隐藏 + ESC 失效 + 遮罩失效 + footer 禁用 + 内容区降透明度禁交互 |
| 统一 `dirty` 表现 | 头部徽章 + footer 左侧脏状态文案 |
| 统一 `beforeClose` 语义 | `false` 阻止 / `true` 立即关闭 / `void` 回调式 / `Promise<boolean>` / reject 视为不关闭且不抛出 |
| 统一尺寸策略 | `size` 预设 + `width` 自定义覆盖 |
| 统一 attrs 透传 | 依赖自动落账，不为未封装能力声明 props；声明了就必须等价转发 |
| 统一 `zIndex` 可选 | 不传时用 EP 栈式分配 |

### 4.2 差异（全部有实证，非随意偏离）

| 维度 | `LqDialog` | `LqDrawer` | 原因 |
|---|---|---|---|
| 底层 Teleport 默认值 | EP dialog 侧由组件恒定传 `append-to-body` | 同样恒定传，但**EP 抽屉默认是 `false`**，所以这一条对抽屉是「改变默认值」 | EP `drawer.vue` 的 Teleport `disabled` 判定 |
| 关闭状态机 | `suppressNextClose` + `nextTick` 兜底 | `closeEmitted` 标记双向压制 | EP 抽屉的 `close` 早于 `nextTick` 回调，照搬会双派发（见取舍 1） |
| `handleModelUpdate` | 原样转发 | 增加 `value === props.modelValue` 守卫 | EP 抽屉在 `afterLeave` 补发 `update:modelValue` |
| 是否调用框架的 `done/hide` | `void` 返回路径下不调用 | **全部路径都不调用** | 统一四条关闭路径的事件顺序 |
| 尺寸令牌 | `--dialog-width-*`（固定 px / `min(px,vw)`） | `--drawer-width-*`（全部 `min(px,vw)`） | 抽屉是侧边面板，宽度语义是「视口占比 + 上限」 |
| 布局 CSS | `dialog-layout.css` | `drawer-layout.css`（独立） | 圆角、阴影方向、限高约束不同；避免改动稳定的 `LqDialog` |
| 独有 prop | `fullscreen`、`align-center` | `direction`、`showHeader` | 形态差异 |
| 纵向布局 | 无 | `ttb` / `btt` 时尺寸作用于高度，CSS 限高 `92vh` | 抽屉特有 |

**没有抽取的共享逻辑**：关闭状态机、footer 按钮样式、`size` 映射。三者都已评估：抽取要么需要改 `LqDialog`（B5-A 禁止），要么会让抽象比重复更难读。按边界要求「先评估抽取风险，不为减少少量重复而改动稳定的 LqDialog」，全部保留独立实现，并在文件头写明原因。

---

## 5. 测试及完整构建结果

### 5.1 新增 `LqDrawer` 契约：48 条全通过

| 分组 | 条数 | 覆盖 |
|---|---:|---|
| 属性转发契约 | 14 | `direction`、四档 `size` 令牌映射、`width` 覆盖、`closeOnClickModal`、`closeOnPressEscape`、`destroyOnClose`、恒定 `append-to-body`、`showHeader`、`zIndex`、`mode` 与 footer 默认 |
| 关闭事件唯一性 | 7 | `v-model` 模式 / 父级不回写兜底 / 取消 / 确认不自动关闭 / ESC 同序 / `loading` 隐藏关闭按钮 / 重开后程序化关闭仍派发 `close` |
| `beforeClose` 语义 | 8 | `false` / `true` / 回调式 `void` / `Promise<false>` / `Promise<true>` / reject / 拦截时 `cancel` 不派发 / ESC 通道同样被拦截 |
| 插槽契约 | 5 | `#title`、`#actions`、`#footer`、`dirty` 徽章、默认插槽位置 |
| 属性与事件透传 | 2 | `header-class` + `@opened` 到达 `el-drawer`；已声明的事件被组件消费不重复透传 |
| 真实 Element Plus 集成 | 9 | Teleport、三段式结构、**真实 document ESC**、loading 下 ESC 无效、嵌套、真实尺寸应用、`destroy-on-close`、卸载安全 |
| 静态资源契约 | 3 | 四档 `vw` 上限、窄屏断点、CSS 无十六进制硬编码色 |

**真实 ESC 如何验证（非浅层 Mock）**：核实 EP 源码后确认 ESC 由 `use-escape-keydown` 注册在 `document` 上（`document.addEventListener('keydown', cachedHandler)`），因此测试直接 `document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', code: 'Escape', bubbles: true }))`，走通完整链路：document ESC → focus-trap `release-requested` → `useDialog.onCloseRequested` → `handleClose` → `props.beforeClose(hide)` → 本组件 `elBeforeClose` → `emitCloseIntent`。断言 `update:modelValue` 为 `[[false]]` 且 `close` 恰好 1 次。

**嵌套测试如何处理 Teleport**：两个抽屉都 Teleport 到 body 后，组件 wrapper 内查不到 DOM，改从 `document.body` 按标题文本定位内层面板并 `dispatchEvent(new MouseEvent('click'))`。

### 5.2 既有核心组件契约：9 文件 / 195 条全通过，零回退

| 文件 | 条数 | 与基线对比 |
|---|---:|---|
| `LqDrawer.contract.test.ts` | 48 | 新增 |
| `LqDialog.close.contract.test.ts` | 26 | **与 B4 一致，一条未动** |
| `LqPagination.contract.test.ts` | 28 | 与 B2/B3 一致 |
| `LqTable.attrs.test.ts` | 28 | 与 B3 一致 |
| `LqDialog.test.ts` | 36 | 一致 |
| `SearchToolbar.debounce.test.ts` | 13 | **与 B4 一致** |
| `SearchToolbar.test.ts` | 7 | 一致 |
| `LqDialog.teleport.contract.test.ts` | 3 | 一致 |
| `LqTable.test.ts` | 6 | 一致 |

### 5.3 全量门禁

| 命令 | 结果 |
|---|---|
| `npx vitest run`（全量） | **76 文件全部通过；685 passed / 0 failed / 0 skipped / 0 expected fail**（B4：75/637 → +1 文件、+48 用例） |
| `npm run typecheck`（`vue-tsc --noEmit --skipLibCheck`） | **0 错误** |
| `npm run lint`（`eslint src/ --quiet`） | **0 error** |
| `npx vite build --outDir %TEMP%\b5build3 --emptyOutDir` | **✓ 3049 modules transformed；✓ built in 46.74s**；产物 531 个文件（`assets` 163 + `map` + `index.html` + 2 logo） |

**构建方式说明**：沿用 B4 验证过的做法 —— 输出到**系统临时目录下的新建目录**（目录本不存在 → 无需删除任何已有产物 → 不触发环境的批量删除保护），也未覆盖仓库内 `dist/`。产物留在临时目录，未污染仓库。这是**真实的完整构建成功**，不是以模块转换成功冒充。

> 期间曾有一次构建在工具 10 秒返回后被中断（产物不全、无 `assets/`），已换用全新目录重跑并确认完整。

---

## 6. 视觉一致性和响应式验证

### 6.1 已验证（自动化）

| 项 | 手段 | 结果 |
|---|---|---|
| 三段式结构 | 真实 EP 集成断言 `.lq-drawer__head` / `__body` / `__foot` 均存在 | ✅ |
| 尺寸预设 | stub 断言四档分别映射到 `--drawer-width-*`；真实 EP 断言根节点 `style.width` 含对应令牌且带 `rtl` 类 | ✅ |
| 主题一致性 | 静态契约断言 `drawer-layout.css` 剔除注释后不含任何十六进制颜色字面量，全部走 `tokens.css` 变量 | ✅ |
| 窄屏 | 静态契约断言四档尺寸令牌均含 `vw` 上限；`drawer-layout.css` 含 `@media (max-width: 767px)` 断点 | ✅ |
| 纵向抽屉限高 | `.lq-drawer.ttb / .btt { max-height: 92vh }` | ✅（CSS 层） |
| 业务 `:deep` 可达 | 沿用 B4 结论：Teleport 不改变祖先后代关系；使用规范给出 `.xxx :deep(.lq-drawer__head)` 写法 | ✅ |

### 6.2 未验证（如实记录）

- **未做人工视觉走查**：本轮无真人/浏览器截图确认浅色与深色主题下的实际观感、动画流畅度、焦点陷阱手感。按 B5-A 定位，视觉走查归 B5-B。
- **纵向抽屉（`ttb` / `btt`）无真实页面**：18 个现有抽屉全部是横向，纵向仅做了 API 支持与 CSS 限高，缺少真实页面验证。
- **过渡动画时序未做浏览器级验证**：`afterLeave`（EP 补发 `update:modelValue` 的时机）依赖真实 CSS transition，happy-dom 下无法保证触发，因此 `handleModelUpdate` 的去重守卫是按源码推断设计 + stub 层断言，未在真实浏览器验证。
- **`resizable`（拖拽改宽）未测试**：仅 attrs 透传，未封装也未验证。

---

## 7. 未来迁移批次

> 本轮**未迁移任何业务页面**，完整分组、逐页关注点与迁移步骤见 `docs/frontend-ui-lqdrawer-usage.md` 第 8 节。

| 批次 | 数量 | 范围 | 主要风险 |
|---|---:|---|---|
| 第 1 批 · 低风险 | 8 | A 组 6 个 + H3ImportCenterView + ProductInventoryView | 仅 `size=` → `width=` 的机械改名；`:model-value` 换 `v-model` |
| 第 2 批 · 中风险 | 5 | RolePermissionView、ManufacturingTaskConfigPanel、ProductInventoryDetailDrawer、TrackingSourceDetailDrawer、MonthlyCommissionView | `#footer` 业务按钮逐个核对；`el-drawer__body` 覆盖样式改 `:deep(.lq-drawer__body)`；`ManufacturingTaskConfigPanel` 嵌套在弹窗内需验 `z-index` |
| 第 3 批 · 高风险 | 4 | TrackingDetailDrawer + ProductionTracking 三兄弟 | 自绘头部逐个对齐；下钻链路（抽屉里开弹窗、弹窗里再开抽屉）必须人工回归层级与焦点 |
| 合理例外 | 1 | `H3LedgerDetailDrawer.vue` | 动态 `:append-to-body` 无法用 `LqDrawer` 表达，建议保留原生或先确认需求 |

**迁移前置条件**：每个抽屉页面的业务负责人确认关闭行为（尤其 `beforeClose` 拦截的必要性）；高风险页面需在非生产环境完整走查下钻链路。

---

## 8. 未解决风险与合理例外

| # | 项 | 性质 | 处理 |
|---|---|---|---|
| 1 | 18 个原生抽屉未迁移 | 范围限制（B5-A 明确不迁移） | 已分组排序，归 B5-B / B6 |
| 2 | 未做人工视觉走查与主题观感确认 | 范围限制 | 归 B5-B |
| 3 | 纵向抽屉（`ttb`/`btt`）无真实使用点 | 缺少验证场景 | API 与 CSS 已支持，待首个使用点出现时补回归 |
| 4 | 过渡动画时序（`afterLeave`）未在真实浏览器验证 | 环境限制 | 按 EP 源码设计 + stub 断言；迁移时人工回归 |
| 5 | `resizable` 未封装未测试 | 能力缺口（已知） | 仅 attrs 透传，文档已声明「未测试」 |
| 6 | 动态 `append-to-body` 无法表达 | 合理例外 | `H3LedgerDetailDrawer` 保留原生 |
| 7 | `LqDialog` 与 `LqDrawer` 的关闭状态机存在两份实现 | 已知重复（刻意） | B5-A 边界禁止改 `LqDialog`；若 B5-B 后确需统一，应先为 `LqDialog` 补真实 EP 时序测试再动 |
| 8 | `close-on-press-escape` 被组件按 `!loading` 覆盖 | 有意行为 | 文档已写明；业务若需自定义 ESC 行为应改用 `beforeClose` |
| 9 | 全量测试中 `ListPageLayout.test.ts` 的 `el-button` / `el-icon` 未解析警告 | 存量噪声 | 非本轮引入，测试仍通过 |
| 10 | footer 按钮 CSS 与 `dialog-layout.css` 存在约 25 行重复 | 已知重复（刻意） | 抽取需改稳定的 `dialog-layout.css`，按边界不抽；已在工作区文档与代码注释中说明 |

---

## 9. 变更边界核对

- 未批量迁移业务页面 —— ✅（0 个业务页面改动）
- 未重构 `LqDialog` / `SearchToolbar` / `LqTable` / `LqPagination` 生产实现 —— ✅
- 未修改生产跟单、财务流水、官网等并行功能 —— ✅
- 未整体重做 `tokens.css` —— ✅（仅新增 4 个抽屉宽度令牌与注释）
- 未引入第二套弹窗/抽屉视觉体系 —— ✅
- 未修改后端、数据库、Flyway —— ✅
- 未自动提交、未推送 —— ✅
- 未执行 `git clean` / `reset` / `stash` —— ✅
- 未清理或覆盖其他并行任务文件（仅只读 `git status` 清单）—— ✅

**完成 B5-A 后停止，未进入 B5-B，未迁移任何业务页面。**

