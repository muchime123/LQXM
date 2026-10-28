# B5-B：公共视觉规范治理与组件视觉验收

> 目标：公共组件视觉一致性验收（重点 LqDrawer）、Design Tokens 与公共 CSS 治理、公共视觉规范固化。
> 不批量迁移业务页面，不重构 B2/B3/B4 已稳定的组件逻辑。
> 上游：P0-A、P0-B0、B1、B2、B3、B4、B5-A（同名 `docs/frontend-ui-*.md`）
> 生成日期：2026-10-28

---

## 1. 审计基准

| 项 | 值 |
|---|---|
| HEAD | `42bc081c271124620254a7fe79fff55d00a7e904`（未前进；B1~B5-B 均未提交推送） |
| B5-A 结束基线 | 76 文件 / 685 passed / 0 failed |
| 工具 | vitest 4.1.9 + happy-dom；`vue-tsc` 0 错误；eslint 0 error；vite build ✓ |
| 视觉验收环境 | **真实浏览器**：隔离 Vite dev server（127.0.0.1:5199，无后端、无业务数据）+ Playwright 驱动本机 Edge（复用仓库既有 `@playwright/test` 依赖与 `playwright.config.ts` 的 `msedge` 通道，未引入新工具链、未下载浏览器） |
| 并行工作区 | `git status` 由 B5-A 时的 160 项增至 204 项再持续增长，本轮仅只读、未触碰、未 stash、未 checkout |

---

## 2. 视觉一致性检查清单与执行方式

### 2.1 执行方式（真实浏览器，非单元测试冒充）

在 `frontend/visual-harness/`（一次性脚手架，验收后已删除）渲染**真实组件**：12 个场景 × 2 主题 × 2 视口（1440×900 / 420×900）= **48 张截图**，存于 `frontend/test-results/b5b-visual/`（已被 `.gitignore` 忽略）。另用 Playwright 读取**真实 computed style** 做精确断言（目测会误判，见 §6.1 纵向抽屉案例）。

| 检查项 | 结论 |
|---|---|
| 列表 / 搜索栏 / 分页器 / 弹窗 / 抽屉 | ✅ 真实渲染无异常，仅 1 条 favicon 404 |
| 表单输入 / 下拉 / 操作按钮 | ✅（经 SearchToolbar、表格、footer 按钮场景） |
| 空状态 / 加载态 | ✅ EmptyState、drawer-loading 场景正常 |
| 标题 / 字体 / 间距 / 边框 / 圆角 / 阴影 | ✅ computed style 逐项校验 |
| 浅色 / 深色主题 | ✅ 48 张双主题截图；投影令牌随主题切换（实测值见 §6.2） |
| 窄屏及常规桌面 | ✅ 420px 断点下抽屉宽度、头部/底部 padding、按钮高度均符合 `@media (max-width:767px)` |

**如实声明**：截图 + computed style **不能**替代人对动画流畅度、焦点陷阱手感、长时间阅读舒适度的评价。这三项本轮未验收，留待 B5-B 后续或 B6 试点时人工走查。除此外的 B5-B1 清单项均已真实执行。

### 2.2 LqDrawer 六项重点验收

| # | 项 | 方式与结论 |
|---|---|---|
| 1 | 三段式与 LqDialog 设计语言一致性 | 抽屉/弹窗并排截图比对：徽章、标题/副标题、关闭按钮、footer 脏状态文案、取消/保存按钮样式逐项一致，差异仅在形态（侧滑 vs 居中）与圆角/投影（符合各自令牌） |
| 2 | 不同尺寸与方向 | sm/md/lg/xl（宽度令牌）、ltr/btt（方向）、自定义 `width="min(1120px,92vw)"` 全部截图正常；btt 实测 `style="height: var(--drawer-width-md)"` → 720px，`maxHeight: 660px`（92vh），符合文档语义 |
| 3 | 内容超长滚动与底部可达 | 45 行明细内容：body 滚动、footer 固定、按钮可见可点 ✅ |
| 4 | loading / dirty / 关闭按钮视觉 | loading 场景：关闭按钮隐藏、footer 禁用、内容降透明度 ✅；dirty：头部徽章 + footer 文案 ✅ |
| 5 | 嵌套 / Teleport / 层级 / 遮罩 | 嵌套场景：两层遮罩、两层面板、内层 zIndex 更高 ✅ |
| 6 | `closeEmitted` 快速关闭重开 / 程序化关闭 / 异步拦截 | B5-A 已有 48 条契约覆盖（含「重开后程序化关闭仍派发 close」「Promise 拦截」「回调式拦截」），本轮全量回归通过 ✅ |

---

## 3. 发现的实际样式不一致问题与证据

### 3.1 已整改（公共层）

| # | 问题 | 证据 | 修复 |
|---|---|---|---|
| 1 | **B5-A 引入的真实缺陷**：`drawer-layout.css` 硬编码 `box-shadow: 0 0 48px rgba(15,23,42,.28)`，绕过了 tokens 里**已有**且已被 `dark.css` 覆盖的 `--shadow-drawer` | 审计脚本 + 修复后实测：light `rgba(15,23,42,.26) 0 24px 72px`、dark `rgba(0,0,0,.5)` —— 修复前深色主题投影不随主题变化 | 改用 `var(--shadow-drawer)` |
| 2 | `dialog-layout.css` 硬编码 `box-shadow: 0 24px 80px rgba(15,23,42,.32)`，深色主题同样不生效 | 修复后实测：light 取值与历史**完全一致**（零视觉变化），dark `rgba(0,0,0,.55)` | 新增令牌 `--shadow-dialog`（浅色取值照抄历史值）+ `dark.css` 覆盖 |
| 3 | **`PageHeader.vue` 与 `ListPageLayout.vue` 各自维护一份页面头部外观，77 行中 72 行完全相同**（含 `::before` 强调条、渐变、标题排版、窄屏断点） | 脚本逐行比对：`PageHeader 声明/选择器行 77，与 ListPageLayout 完全相同 72` | 收敛为 `modules/list-page-surfaces.css` 的 `.page-surface-header`，两组件共用（修复后 12 项 computed style 逐项一致，见 §6.2） |
| 4 | `letter-spacing: -0.02em` 在 4 个公共文件硬编码，而 `--tracking-tight: -0.02em` 存在却无使用者 | 审计 §6（出现于 4 个公共文件） | 共享头部改用 `var(--tracking-tight)` |
| 5 | `border-radius: 8px` / `transition: all 0.15s ease` / `opacity: 0.5` 硬编码，与 `--radius-lg` / `--transition-fast` / `--opacity-disabled` 同值并存 | dialog-layout.css L179/183/260/266/331；drawer-layout.css L152/156/223/229/249 | 全部改为等价令牌（零视觉变化，实测 `borderRadius: 8px`、`transition: all 0.15s`） |
| 6 | `color: #ffffff` 硬编码（success 块 2 处、danger 块 7 处） | 同值令牌 `--color-text-on-primary: #ffffff` 存在（深浅两主题均白） | 改用 `var(--color-text-on-primary)`（零视觉变化） |
| 7 | `ListPageLayout` 的 `.list-page-layout__toolbar-actions` 选择器无任何模板节点（死规则） | 模板中不存在该 class | 随头部收敛一并删除 |

### 3.2 发现但**未**整改（业务页面 / 并行文件，按边界不动）

| # | 问题 | 证据 | 处置 |
|---|---|---|---|
| 8 | **`WebsiteMediaView` / `WebsiteProductsView` 使用 `<header class="page-header">`，但既无本地样式规则，也无全局 `.page-header` 规则** → 头部实际未套用统一的卡片外观 | 脚本：模板使用 5 个视图、自带规则仅 3 个、`styles/` 全局定义 0 个 | 记录为真实缺陷；两文件属并行任务改动集（`WebsiteProductsView.vue` 为 `M`），留 B6 迁移 |
| 9 | 3 个官网视图（Dashboard/Solutions/Banner）各自手抄 `.page-header`，且 padding 与组件版不同（`var(--space-xl-2xl) var(--space-xl)` vs 组件的 `var(--space-section-gap) var(--space-xl)`） | 三个 `.page-header { }` 规则体几乎逐字相同 | B6 迁移到 `.page-surface-header` |
| 10 | `PageHeader` 组件全仓**仅 1 个调用方**（`MasterLayout.vue`），而 12 个页面用 ListPageLayout、5 个视图手抄头部 —— 公共组件复用率极低 | 引用统计脚本 | 记录；B6 试点时优先推广 `PageHeader` |
| 11 | `!important` 全仓 272 处，集中区：`element-plus.css` 56（EP 对齐补偿，合理 D 类）、`LqDescriptions.vue` 32、`CustomerDetailPanel.vue` 32、`packaging-detail-drawer.css` 30、`dialog-layout.css` 17 | 审计 §4 | `dialog-layout.css` 的 17 处属 EP 按钮样式覆盖补偿（D 类合理保留）；`LqDescriptions`/`CustomerDetailPanel` 属业务组件，留后续批次评估 |

---

## 4. Design Tokens 治理结果

### 4.1 规模与问题

`tokens.css` 定义 328 个令牌。审计发现：

| 类别 | 数量 | 代表 | 判定 |
|---|---:|---|---|
| **无外部使用者** | 79 | `--ep-*` 色阶 9 个、`--transition-drawer/modal/…` 9 个、`--opacity-*` 5 个、`--text-*` 5 个、deprecated 兼容别名 22 个（`--background`、`--bg-page`、`--regular`…）、`--shadow-color-gray`、`--color-glass-border` 等 | 过度抽象 / 死令牌。**本轮不删除**：其中 `--shadow-drawer` 本轮已启用；删除 deprecated 别名需要一次独立的小批次 + 专项回归，混入本轮会扩大风险面 |
| **dark.css 未覆盖的颜色类令牌** | 35 | `--ep-success-*/warning-*/info-*` 整组、`--ep-primary-dark`、`--ep-tooltip-bg/text`、`--ep-sidebar-*` | 深色主题漂移风险。**未整改**：多数是 EP 组件色，在深色下沿用浅色值属可接受折衷；逐一决定需要设计评审，不宜由 AI 单方面改 |
| **dark.css 覆盖了但 tokens 未定义** | 0（排除 `--el-*` 组件内部变量后） | — | 无孤儿覆盖 ✅（已固化为契约） |

### 4.2 硬编码四分类结果（A/B/C/D）

| 类 | 定义 | 本轮处置 |
|---|---|---|
| **A** 应进公共令牌的重复基础视觉值 | `--shadow-drawer` 被绕过（drawer 阴影）、缺 `--shadow-dialog`、`-0.02em`、`8px`、`0.15s ease`、`0.5`、`#ffffff` | **全部整改**（§3.1 #1/2/4/5/6），并固化为 18 条自动化契约 |
| **B** 公共组件专属布局值 | 弹窗面板圆角 `14px`、加载圈 `50%`、抽屉 `min-height:56px`、`28px` 关闭按钮、`min(720px,94vw)` 档位 | **保留**（单一使用者，造令牌即过度抽象），已写入规范 §5 与测试允许清单 |
| **C** 合理的局部业务视觉值 | 各业务页面 scoped 内的页级排布 | **保留**，不触碰（B6 范围） |
| **D** 技术性例外 | EP `!important` 56 处、反色表面上的 `rgba(255,255,255,.3)`、媒体查询断点、footer danger 三档红（**自洽**：自带 `[data-theme='dark']` 覆盖，机械替换 `var(--color-danger)` 反而改变深色现有取值，因为 dark 中该令牌是 `#ef4444`） | **保留**并逐项在规范 §5 写明理由；danger 色阶与允许清单一并写入测试 |

**明确不做的事**：不机械替换全部硬编码（danger 块的替换论证见 §5）；不一次性重写 `tokens.css`；不删除 79 个无使用者令牌（其中 `--shadow-drawer` 本轮已启用，证明「留着」比「删了再加」安全）。

---

## 5. 公共 CSS 重复与冲突整改

| 项 | 整改前 | 整改后 |
|---|---|---|
| 页面头部外观 | 两份 77/72 行重复的 scoped 样式 | 一份 `.page-surface-header`（98 行）于 `modules/list-page-surfaces.css`，两组件共用 |
| 弹窗/抽屉投影 | 两处硬编码、深色主题失效 | 两个主题化令牌，实测浅色值与历史一致 |
| 公共模块硬编码色 | dialog-layout 13 处 | 4 处（均为已登记的 D 类允许项） |
| `--tracking-tight` / `--opacity-disabled` / `--transition-fast` / `--radius-lg` | 定义存在但被字面量绕过 | 已被公共模块实际引用 |

**未发现**公共 CSS 与组件 scoped CSS 之间**其他**的重复维护（弹窗/抽屉/头部三处即全部；`ListPageLayout` 其余 scoped 规则均为其独有结构）。

---

## 6. LqDrawer 视觉验收与关闭生命周期复核

### 6.1 一个被实测推翻的目测疑点

纵向抽屉（btt）截图目测疑似「内容区左边距缺失」。用 Playwright 读取 computed style：`bodyPadding: 20px 24px` —— **padding 正常，目测为误判**（截图整体缩放所致）。该案例说明验收必须以 computed style 为准，也说明不能只靠截图下结论。

### 6.2 computed style 实测记录（真实 Edge）

| 断言 | light | dark |
|---|---|---|
| `.lq-drawer` box-shadow | `rgba(15,23,42,0.26) 0 24px 72px`（= 令牌浅色值） | `rgba(0,0,0,0.5) 0 24px 72px`（= dark 覆盖值）✅ 修复生效 |
| `.lq-dialog` box-shadow | `rgba(15,23,42,0.32) 0 24px 80px`（**与历史硬编码值完全一致 → 零视觉变化**） | `rgba(0,0,0,0.55)` ✅ |
| `.page-surface-header`（两组件 12 项） | padding/minHeight/borderRadius/borderColor/boxShadow/gap/backgroundImage/fontSize/`::before` 宽与色/h1 字距字号 **逐项相同** | — ✅ 收敛成功 |
| 关闭按钮与 footer 按钮 | `borderRadius: 8px`、`transition: all 0.15s`（令牌等价替换生效） | — |
| h1 字距 | `-0.4px`（= 20px × -0.02em，`--tracking-tight` 生效） | — |
| btt 抽屉 | `style="height: var(--drawer-width-md)"` → 720px；`maxHeight 660px`（92vh 限高生效） | — |

### 6.3 关闭生命周期复核

B5-A 的 48 条契约在改动后全量回归通过（`closeEmitted` 状态机、快速关闭重开、程序化关闭、`Promise` 拦截、四条路径 `close` 恰好一次）。本轮未改任何组件逻辑，仅改样式层。

---

## 7. 合理保留的硬编码与业务例外

已固化到 `docs/frontend-ui-visual-spec.md` §5 与契约测试的允许清单：

| 项 | 理由 |
|---|---|
| footer danger 三档红 `#dc2626/#b91c1c/#991b1b/#ef4444` | 该块**已自带** `[data-theme='dark']` 覆盖，自洽；机械替换 `var(--color-danger)` 会把深色底色从 `#dc2626` 变成 `#ef4444`（dark 中该令牌的值），属视觉变化而非治理 |
| 弹窗面板圆角 `14px`、加载圈 `50%` | 单一使用者的组件专属布局值（B 类），造令牌即过度抽象 |
| 反色表面上的 `rgba(255,255,255,.3)` | 主色填充之上的描边，不随主题变化 |
| `element-plus.css` 的 56 处 `!important` | EP 变量对齐补偿（D 类），属该文件的职责本体 |
| 业务页面 scoped 内的全部硬编码 | C 类，B6 范围，本轮一律未触碰 |

**判定纪律**：不是「看到硬编码就换令牌」。换令牌必须满足「浅色取值完全一致（零视觉变化）且确有深浅差异需求」，否则记录理由后保留。danger 块即依此判为保留。

---

## 8. 改动文件及影响范围

| 文件 | 改动 | 影响 |
|---|---|---|
| `styles/tokens.css` | +6 行：`--shadow-dialog` 令牌与注释 | 仅新增，未改任何现有令牌取值 |
| `styles/themes/dark.css` | +1 行：`--shadow-dialog` 覆盖 | 仅新增 |
| `styles/modules/dialog-layout.css` | 17 处令牌化（阴影 1、圆角 3、过渡 3、禁用透明度 1、反白文字 9） | 影响全部 LqDialog 调用方（58 文件）——浅色**零视觉变化**（实测值一致），深色投影由「不可见」变「可见」（属修复目标） |
| `styles/modules/drawer-layout.css` | 7 处令牌化（阴影 1、圆角 2、过渡 2、禁用透明度 1） | 影响全部 LqDrawer（当前 0 业务调用方，仅契约与验收环境） |
| `styles/modules/list-page-surfaces.css` | +98 行：`.page-surface-header` 共享头部 | 新增选择器，不与既有规则冲突 |
| `components/common/PageHeader.vue` | 删除 92 行 scoped 外观，改用共享类 | 唯一调用方 `MasterLayout.vue`；经 48 张截图 + computed style 验证无回归 |
| `components/common/ListPageLayout.vue` | 头部 86 行 scoped 外观移除，改用共享类；删死选择器 `__toolbar-actions` | 12 个调用方；已核查**全仓无任何**对 `.list-page-layout__header/__title/__actions` 的外部引用 |
| `components/common/__tests__/ListPageLayout.test.ts` | 2 处类名更新（断言语义不变） | 配合类名收敛 |
| `components/common/__tests__/PublicStyleTokens.contract.test.ts` | **新增 18 条**治理契约 | 见 §2.2/§4/§5 的固化说明 |
| `docs/frontend-ui-visual-spec.md` | **新增**公共视觉规范（8 节，复用现有文档结构） | |
| `docs/frontend-ui-b5b-visual-governance-report.md` | **新增**本报告 | |

**一次性脚手架**：`frontend/visual-harness/`（4 个文件）已删除；48 张截图留存于被 gitignore 的 `frontend/test-results/b5b-visual/`。dev server（端口 5199）已停止。`git status` 不显示任何脚手架残留。

**未触碰**：业务页面（含 `WebsiteMediaView` / `WebsiteProductsView` 等 3.2 节缺陷文件）、`LqTable` / `LqPagination` / `SearchToolbar` / `LqDialog` / `LqDrawer` 的组件逻辑、后端、数据库、Flyway、全部并行任务文件。

---

## 9. 自动化测试和构建结果

| 命令 | 结果 |
|---|---|
| 新增 `PublicStyleTokens.contract.test.ts` | **18 条全通过** |
| `ListPageLayout.test.ts` | 4 条通过（更新后） |
| **全量 `npx vitest run`** | **77 文件 / 703 条：701 passed / 2 failed / 0 skipped**（B5-A 基线 685 → +18 全部来自新增治理契约；2 条失败的归因见 §9.1，非本轮引入） |
| `npm run typecheck` | **0 错误** |
| `npm run lint` | **0 error** |
| `npx vite build --outDir %TEMP%\b5bbuild --emptyOutDir` | **✓ 3062 modules transformed；✓ built in 17.59s**；532 个产物（assets 164 + index.html + map + 2 logo） |

### 9.1 两条失败用例的归因（非本轮引入，未代修）

`src/components/production/__tests__/TrackingAnalyticsDrilldownCells.test.ts` 有 2 条失败（`expected '120 / 一出四' to be '120 / 圆形 / PP / 一出四'`）：

- 该测试文件**在 HEAD 中且未被修改**（`git ls-files` 命中，status 干净）；
- 其被测组件 `components/production/TrackingAnalyticsSpecCell.vue` 在 **B5-B 开工时的 git status 清单中不存在**、现在为 `M` —— 即并行任务在本轮执行期间修改了该组件，未同步其既有契约测试；
- 失败断言内容是生产跟踪的**业务文本格式**，与本轮全部改动（styles / PageHeader / ListPageLayout / 契约测试）无任何依赖关系；
- B5-A 结束时全量为 685 条全绿（含该文件当时的 7 条），此后我未触碰它。

按边界（「禁止修改生产跟单等业务功能」「禁止清理或覆盖其他并行任务文件」），本轮**不代修**该组件或测试。**若回退本轮全部改动，该失败依旧存在。** 建议由该组件的改动方同步更新测试。

---

## 10. B6 业务页面试点的前置建议

1. **优先试点对象**：`WebsiteMediaView` / `WebsiteProductsView`（头部无样式，§3.2 #8 的真实缺陷）——迁到 `.page-surface-header` 即同时修复缺陷与统一外观；但须等并行任务提交后进行。
2. **其次**：3 个手抄 `.page-header` 的官网视图 → 改用 `<PageHeader>` 组件。
3. **第 1 批抽屉迁移**（B5-A 已分好 8 个低风险页面）：`size="X"` 改 `width="X"` 的纯机械替换。
4. **推广 `PageHeader`**：当前仅 1 个调用方，属「有组件没人用」的典型；B6 试点应把它作为页面头部唯一入口。
5. **令牌清理**：79 个无使用者令牌与 22 个 deprecated 别名，建议作为独立小批次（先删 deprecated 别名——全仓 0 引用，风险最低），不要与页面迁移混做。
6. **深色主题专项**：35 个未被 dark.css 覆盖的颜色令牌需设计评审逐一决策。
7. **`!important` 治理**：`LqDescriptions.vue`（32 处）与 `CustomerDetailPanel.vue`（32 处）是公共/业务组件中最大的两处，建议先梳理其被覆盖对象是否可用 EP 变量替代。

---

## 11. 边界核对

- 未批量迁移业务页面（0 个业务页面改动）—— ✅
- 未重构 LqTable / LqPagination / SearchToolbar / LqDialog / LqDrawer 的**逻辑**（仅样式层令牌化）—— ✅
- 未修改生产跟单、财务流水等业务功能 —— ✅（2 条失败测试属并行任务改动，未代修）
- 未修改后端、数据库、Flyway —— ✅
- 未新建平行 UI 体系（新增的是令牌与公共 CSS 模块内的规则，属完善现有公共层）—— ✅
- 无证据不替换：danger 色阶经论证后**保留** —— ✅
- 未自动提交、未推送、未 stash、未清理并行文件 —— ✅

**完成 B5-B 后停止，未进入 B6。**

