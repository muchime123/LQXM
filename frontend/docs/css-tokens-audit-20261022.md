# CSS 与设计令牌问题清单（2026-10-22）

> 只读审计产物，未修改任何代码。行号以当日工作区文件为准。
> 范围：`frontend/src/styles/**` + 全部 `.vue` 的 `<style>` 块。
> 三路核查：①变量与主题一致性 ②分层与重复样式 ③覆盖关系与公共组件自身设计。

## 结论摘要

1. 令牌体系**骨架是好的**（tokens.css 职责纯正、无类型越界），但存在**单向的深色缺失**：`dark.css` 只覆盖约 100/300 条变量，深色下 38+ 个颜色令牌静默回落浅色值。
2. 存在**引用不存在令牌**的反向问题 15 处——声明整条失效，靠 fallback 掩盖，其中 4 处 fallback 是 Element Plus 默认色，**已经渲染出错误品牌色**。
3. `modules/image-editor-inline.css` 与 `asset-gallery.css` 有 **8 个同名选择器全部属性冲突**（`display: grid` vs `flex` 等），现网全靠加载顺序兜住。
4. `utilities.css` 名为工具类层，实为第二套组件库，**约 51% 是 0 引用死代码**；`.btn-sm` 有 5 份互相冲突的全局定义。
5. `!important` 约 280 处：`element-plus.css` 56 处属必要；**`LqDescriptions.vue` 32 处 + `CustomerDetailPanel.vue` 32 处**（把 el-descriptions 结构硬掰回 table）是最大坏味道。
6. `:deep(` 195 处/51 文件，其中 **22 处是页面反向打穿公共组件内部**（DataState / LqDialog / FormSection / LqPagination 缺密度 props 所致）。
7. 公共组件自身**不同源**：容器圆角 5 套（6/8/10/12/14px）、次要按钮高度 5 档（24/28/30/32/34px）、危险按钮红色 2 套。

---

## P0 —— 功能性缺陷（会渲染出错，不是审美问题）

| # | 问题 | 证据 | 影响范围 | 根本原因 | 建议处理 | 风险 |
|---|---|---|---|---|---|---|
| C1 | 深色主题缺失 12+ 条令牌覆盖 | `themes/dark.css` 仅 100 条 vs `tokens.css` 约 300 条；`--color-primary-hover`(tokens:7)、`--color-primary-bg-light`(:9)、`--color-success/warning/danger/info`(:61-97)、`--color-purple*`(:104-106)、`--ep-primary-filled`(:135)、`--shadow-xs/drawer/focus`(:228-234) 均未覆盖 | 全站深色模式 | dark.css 按"当时看到的页面"增量补，未做补集校验 | 给 dark.css 按清单补 12 条（dark.css 内无"只在 dark 定义"的变量，可安全按补集补齐） | 低 |
| C2 | dark.css 自己引用了未覆盖的浅色令牌 | `dark.css:116,118` 引用 `--ep-primary-filled`（tokens.css:135，dark 未覆盖） | 深色下 radio-button 选中态几乎不可见 | 同上 | 与 C1 一并补 | 低 |
| C3 | 背景令牌当前景色，深色下对比度崩坏 | `utilities.css:38,146,265` `color: var(--color-surface)`；深色下 `--color-surface=#1e293b` → 主按钮文字变深蓝 | 全站主按钮/链接（深色） | 缺 `--color-text-on-primary` 意识（令牌已存在） | 改为 `var(--color-text-on-primary)`，并立规则禁止 `color: var(--color-surface)` | 低 |
| C4 | 引用不存在的令牌，声明整条失效 | `--color-surface-subtle`：MonthlyCommissionView.vue:2028,2031,2085,2107；`--space-4`：DepartmentManagementView.vue:207-208；`--space-1`：同上:208,212、ManufacturingWorkshopPanel.vue:44；`--space-2`：DeviceVideoGovernanceSummary.vue:32-33、DeviceVideoLibraryView.vue:2367,2380；`--space-2xs`：PackagingMoldsMasterDetail.vue:1780,2501 | 15 处声明失效 | 同时存在 `--space-1..5` 数字档与 `--space-xs..3xl` 语义档两套思维，写错无人拦截 | 逐处替换为存在令牌（`--space-sm/md-lg` 等）；同时补 CI 校验（见 C15） | 低 |
| C5 | fallback 掩盖型错色：success 四令牌不存在，fallback 是 EP 默认绿 | `asset-toolbar.css:46-51` `--color-success-border,#b3e19d` 等 4 个均不存在；项目 success 实为 `#16a34a/#dcfce7/#a7f3d0` | 资产工具栏「文件库」按钮渲染成 EP 绿，与全站品牌色不一致 | 抄 EP 色板当 fallback | 替换为 `--color-success-bg/-border` 等存在令牌 | 低 |
| C6 | 同类：更多拼写漂移 | `company-certifications-tab.css:264,304`（`--color-success`/`--color-warning-bg-light` 不存在）、`:171,197`（`--color-text-quaternary` 不存在）、`asset-gallery.css:317`（`--color-accent` 不存在，**整条 gradient 失效**）、`packaging-mold-view.css:47`（`--space-s` 不存在） | 证书永久标签、网站精选徽标、包材模具分页 | 同上 | 同上 | 低 |
| C7 | 8 个同名选择器全局冲突，靠加载顺序兜底 | `modules/image-editor-inline.css:89-191` ↔ `asset-gallery.css:6-226`：`.asset-gallery-groups`（grid vs flex）、`.asset-preview-grid`（`minmax(150px,1fr)` vs `minmax(176px,176px)`）、`.asset-preview-card`（grid+112px 行 vs flex+radius-2xl）、`.active`（3px 环 vs 1px）、`.highlighted`（绿 vs 橙）等 | 产品素材画廊 / 官网图片编辑器 | 两套皮肤写了同名类，全局先加载、组件 @import 后加载 → image-editor-inline 的 8 块**实际是死样式**，但任何一个使用方漏 import asset-gallery.css 就会瞬间换成另一套外观 | 删除 `image-editor-inline.css:89-191`，唯一来源 = asset-gallery.css；删除前确认所有使用方都 import 了它 | 中 |
| C8 | `.btn-sm` 5 份冲突定义，其一为裸选择器全局泄漏 | `utilities.css:124`（6px 14px / radius-lg / 12px）、`packaging-detail-drawer.css:1961`（**裸 `.btn-sm`，0 11px / 6px / 13px**）、`FilePreviewBody.vue:630`（6px 12px）、`FeeRecordsPanel.vue:318`（5px 12px / radius-sm）、`master-layout.css:154`（5px 12px） | 打包 / 媒体 / 证书 / 预览四个域按钮互相串扰 | 无"按钮唯一来源"约束 | 保留 utilities.css 版本为唯一来源；packaging 裸选择器改前缀 `.pk-btn-sm`；其余逐域替换 | 高（直接改变 4 域按钮尺寸，需截图回归） |
| C9 | SidebarAssetPanel 用了 `.file-button` 却未引入 asset-toolbar.css | `SidebarAssetPanel.vue:185` 使用类名；`asset-toolbar.css` 仅被 `AssetToolbar.vue:198`、`PackagingAssetPanel.vue:674` scoped 引入；该面板 `:374-393` 只写覆写不写基样式 | 侧栏素材面板上传按钮缺 `height/padding/radius` 基样式 | scoped @import 遗漏 | 在其 `<style scoped>` 补 `@import`，并删除与基样式重复的覆写 | 低 |

## P1 —— 结构性重复（多套实现并存）

| # | 问题 | 证据 | 建议唯一来源 | 风险 |
|---|---|---|---|---|
| C10 | `utilities.css` 约 51% 死代码（19 个 0 引用类：`.card` 全家 4 个、`.btn-secondary/-warning/-success/-lg/-xl/-block`、`a.shop-link`、`.empty-template`、`.variant-note`、`.row-highlight`、`.form-error-summary` 3 个、`.lq-table-row-actions` 2 个，约 280 行）；且该文件没有工具类，实为按钮/空态/Loading 组件库 | `utilities.css:36-120,171-186,247-273,440-549` | 删除 0 引用类；按内容拆为 `modules/buttons.css` / `empty-state.css` / `loading.css` 后清空该层 | 低（0 引用即净收益）→ 中（拆层需同步 index.css） |
| C11 | 弹窗底部按钮区逐字复制 5 份 | `.dialog-actions`：media-account-view.css:704 + UpdateRecordFormDialog.vue:176 + AssignOwnerDialog.vue:191 + BatchUpdateRecordDialog.vue:221 + BatchPublishTaskDialog.vue:236（五份逐属性相同）；`.form-dialog-footer` 同样 5 份；`.btn-cancel/.btn-save` 成套 4 份 | `modules/dialog-layout.css` `.lq-dialog__foot`（但注意其 padding `12px 22px` 与副本 `10px 18px` 不同，统一时二选一） | 中（media 弹窗是自建 el-dialog，需同步改模板） |
| C12 | 弹窗头部 padding 被 3 个文件 5 条规则争夺 | `dialog-layout.css:97-106`（56px）↔ `media-account-view.css:436-442` ↔ `modules/media-dialog-shared.css:39-44`（64px）；`media-dialog-shared` 实为 dialog-layout 的补丁层 | 收敛为 dialog-layout 单层 + `density` 修饰类 | 中 |
| C13 | 表格皮肤双写，斑马纹肉眼可辨 | `element-plus.css:363-382`（`--color-surface-alt` #f8fafc）↔ `LqTable.vue:95-170`（`--color-surface-raised` #fbfcfd）；表头背景/行高/hover 相同 | LqTable（`list-page-surfaces.css:1-7` 已声明此契约）；element-plus.css 只保留 `--el-table-*` 变量（:346-358），删 :363-382 结构规则 | 中（弹窗内裸 `<el-table>` 会失去 fs-sm，需留一条字号兜底） |
| C14 | 分页容器 6 处 / 工具栏 12 处 / 空态 5 份 / `@keyframes spin` 7 份 | 分页：list-controls.css:3-11 ↔ `.pager`×3（ProductionTrackingView:873、ProductInventoryView:851、H3ImportCenterView:1366，margin-top 10/16/0 三档）↔ `.pagination-wrapper`（WebsiteBannerView:331）；工具栏：`.toolbar` 同名 7 处、padding 四档；空态：utilities.css:275 / EmptyState.vue:32 / media-account-view.css:1760 / MediaAnalyticsPanel.vue:839 / MediaAccountDetailModal.vue:473；spin：7 处，且 `dialog-layout.css:353` 与 `spin` 内容完全相同；**`@keyframes highlightFlash` 同名不同义**（utilities.css:428 改 background vs asset-gallery.css:207 改 box-shadow，互相污染） | 分页→`.lq-pagination`；工具栏→`.search-toolbar`；空态→`EmptyState` 组件；动画各归其主并给 highlightFlash 改名 | 中 |
| C15 | `!important` 坏味道集中两处 | `LqDescriptions.vue:271-341`（32 处把 el-descriptions 硬改回 table）+ `CustomerDetailPanel.vue:872-929`（32 处复制同一做法）；「危险按钮」三档红在 4 个文件各写一遍（utilities.css:155-168、dialog-layout.css:286-314、media-account-view.css:255-279、packaging-detail-drawer.css:17-36,1942-2004），且 `#be123c` 与 `#dc2626` **两套红并存** | LqDescriptions 改 `:deep`+特异性或 EP 原生列配置；CustomerDetailPanel 改用 LqDescriptions；danger 抽 `--color-danger/-hover/-active` 三档 + 单一按钮类 | 中 |
| C16 | 页面 `:deep` 打穿公共组件内部 22 处 | DataState：PackagingMoldsMasterDetail.vue:1584-1601、MasterListPanel.vue:474、MasterCardGrid.vue:190；LqDialog 三段：MediaAccountDetailModal.vue:126-139；FormSection：MachineTierManager.vue:448-640；LqPagination：MasterListPanel.vue:671-684、PackagingMoldsMasterDetail.vue:1630-1632（注释已自认死样式） | 根因是公共组件缺密度/内边距 props：给 DataState 加 `padding/align`、LqDialog 加 `density`、FormSection 加 `size="compact"`、分页皮肤内置 `mode` 变体，然后删 :deep | 低 |
| C17 | 巨型 CSS 反复 scoped 注入 | `media-account-view.css`(36KB) 被 **8 个组件** `@import`（MediaAccountView.vue:1345 等）；`packaging-detail-drawer.css`(50KB) 被 3 个组件引入；后者内 `.btn-sm` 裸选择器全局泄漏（见 C8）、danger 样式出现两遍（:17-36 与 :1985-2004）、又重复定义 `@keyframes spin`（:145） | 按组件拆分或改为全局一次性引入；文件内去重 | 中 |
| C18 | 无前缀通用类名全局泄漏 | `ProductEditorModal.vue:442` 非 scoped `.editor-section-card`/`.archive-field-grid` 等；`.view-mode-toggle` 双定义（asset-gallery.css:56 原生 pill vs list-controls.css:115 EP 皮肤）；`.htb-btn` 两份（asset-gallery.css:438-476 vs CompanyBrandAssetsTab.vue:1439-1473） | 加父级前缀限定或移回 scoped；`.htb-btn` 抽 `modules/asset-card-actions.css` | 中 |

## P2 —— 令牌收敛与规范

| # | 问题 | 建议（**保留哪个值**） | 需改文件 | 风险 |
|---|---|---|---|---|
| C19 | 同值异名 20 组（如 `#94a3b8` 三名：text-faint 148 处 / text-disabled 4 / text-placeholder 2） | 保留 `--color-text-faint`，其余改别名 | 6 处引用 | 低 |
| C20 | `--color-text-secondary`(#5f6f82) 与 `-muted`(#64748b) 亮度仅差 5%，肉眼不可辨（155 文件混用） | 两个都保留但**拉开**：secondary → `#334155`；`--el-text-color-secondary` 改指 muted 与业务主流一致 | 仅 tokens.css:50 + dark.css:24 两行 | 中（需视觉回归） |
| C21 | `--ep-*` 与 `--color-*` 双轨（warning/success/danger/info 色阶 1:1 复制） | `--color-*` 为唯一真值，`--ep-warning-7/8/9` 等 12 个改 `var(--color-*)` | 3 文件 | 中 |
| C22 | 40 条 Deprecated 别名仍有 44 处/17 文件引用（`--color-text` 9 处、`--color-text-tertiary` 13 处等） | 别名暂不删定义，分批替换引用 | 17 文件 | 中 |
| C23 | 0 引用死令牌：`--color-surface-readonly`、`--border-width-*` 整族 5 个、`--ease-spring/bounce`、`--opacity-*`、`--tracking-*` 族 | 直接删除 | tokens.css | 低 |
| C24 | 命名规范 13 项（`--space-3`=12px vs `--space-3xl`=32px 同族不同义；`--text-*` 前缀颜色/字号双语义；`--fs/--fw/--lh` 缩写三套） | 删 `--space-3/5`；颜色禁用 `--text-*`；字号统一 `--font-size-*` 保留旧名做别名 | 分批 | 中 |
| C25 | 硬编码：真实硬编码颜色约 443 处 + `var(--x, #hex)` 兜底 648 处（兜底会掩盖 C1/C4 类缺陷）；硬编码间距 ≥1044 处、字号 ≥550 处 | 先修 Top4：PackagingMoldsMasterDetail.vue(115/39)、packaging-detail-drawer.css(51/91)、DeviceVideoLibraryView.vue(76/80)、MonthlyCommissionView.vue(61/70)；lint 禁 `var(--x, <color>)` | 41+ 文件，分批 | 中→高 |
| C26 | index.css 层序：`responsive.css` 插在 tokens 与 reset 之间；注释与实际顺序不符；`master-layout.css` 自述公共却与页面级 CSS 并列 | responsive 移到 utilities 之后；master-layout.css 移入 modules/ | 1 文件 | 低 |
| C27 | 无令牌存在性/裸类名校验 | 加 stylelint：`var(--x)` 的 x 必须存在于 tokens.css；禁 `var(--x, <color>)`；styles/ 外禁裸 `.btn-sm` | CI 配置 | 低 |

## 明确"不应合并"的判定（避免为统一而统一）

| 对象 | 判定 | 依据 |
|---|---|---|
| `asset-toolbar.css` / `asset-gallery.css` / `modules/asset-grid.css` | **不合并**，保持三件套 | ①选择器集合 0 交集（工具栏按钮 / 画廊卡片皮肤 / auto-fill 网格骨架）；②作用域不同（前两者 scoped 组件级，后者全局）；③两个文件头部已有互斥声明注释。仅需：修 C9（SidebarAssetPanel 缺 import）、`.htb-btn` 去重（C18） |
| AssetToolbar vs SearchToolbar | **不合并组件，只统一令牌** | AssetToolbar 全原生元素（pill 999px/34px/13px），SearchToolbar 全 EP 控件（6px/32px/14px）；职责一个是素材画廊工具栏、一个是通用列表搜索栏。统一手段：新增 `--control-height-md`(34px)、统一 `--btn-radius/--btn-font-size`，让 `.tb-btn`、`.lq-dialog__foot button`、`.btn`、EP `--el-component-size` 指向同组令牌；同一工具栏内 999px 与 6px 并存（asset-toolbar.css:73 vs :17）二选一收敛 |
| `LqDialog`/`LqPagination` 无 `<style>` 块（视觉全外置） | 保持外置但**声明契约** | 视觉唯一来源是 modules/dialog-layout.css 与 list-controls.css，这本身成立；问题是 media-dialog-shared 又打补丁（C12）。收敛补丁层即可，不必把视觉搬回组件 |

## 建议执行顺序（ROI 排序）

| 批次 | 内容 | 文件数 | 风险 |
|---|---|---|---|
| P0 | C1+C2+C3（dark 补 12 条 + surface 当前景色） | 2 | 低 |
| P0 | C4+C5+C6（15 处失效令牌 + 6 处错色 fallback） | 8 | 低 |
| P0 | C7（删 image-editor-inline.css:89-191）+ C9（SidebarAssetPanel 补 import） | 3 | 中 |
| P1 | C10（删 utilities 19 个死类）+ C14 动画/空态去重 | 10 | 低→中 |
| P1 | C8 + C11 + C13（按钮唯一来源 / 弹窗底区收敛 / 表格皮肤收敛） | 12 | 中→高 |
| P1 | C15 + C16（danger 三档令牌 + 公共组件加密度 props 删 ：deep） | 12 | 中 |
| P2 | C19-C24 令牌收敛批次 | 25 | 中 |
| P2 | C25 硬编码 Top4 + C17 巨型 CSS 拆分 + C27 stylelint | 50+ | 高（需分批+截图回归） |

## 审计边界（诚实声明）

- 计数为"命中行数"下限；`.vue` 硬编码间距/字号在 141/80+ 文件上输出截断，数字为下限。
- **深色模式未做渲染截图验证**——C1-C3 的判断基于源码比对（dark.css 变量补集），修复后应补一轮深色截图对比。
- `--text-body` 等"字号+行高组合令牌"的实际使用率未逐个统计。

---

## 修订记录（2026-10-23 实测校正）

### C3 范围修正：审计低估

审计原文把 C3 描述为"`utilities.css:38,146,265` 三处"。真实渲染实测后，**范围远大于此**：

| 项 | 审计原判 | 实测真实值 |
|---|---|---|
| 命中文件数 | 1 | **23** |
| 命中处数 | 3 | **47**（`^\s*color:\s*var\(--color-surface\)`） |
| 分布 | utilities.css | packaging-detail-drawer.css(8)、asset-gallery.css(7)、media-account-view.css(5)、asset-toolbar.css(3)、CertificateView.vue(3)、MediaAccountDetailModal.vue(3)、modules/dialog-layout.css(2)、master-layout.css(2)、modules/list-controls.css(1)，以及 15 个组件各 1 处 |

**实测纠正（重要）**：此前基于目视判断的"深色对比度崩坏"结论**不成立**。`/packaging`「新增规格」按钮（`.btn-sm.primary`）实测计算样式为 `color: rgb(30, 41, 59)` + `background: rgb(20, 184, 166)`，按 WCAG 2.1 计算对比度为 **5.88:1，达到 AA（4.5:1）**；浅色同为 **5.47:1**。原因是深色主题把主色调亮（`#14b8a6`）而把 `--color-surface` 调暗（`#1e293b`），二者恰好构成及格的明暗配对。

同时实测推翻了"修 utilities.css 即可"的隐含结论：该按钮的实际渲染由**局部定义**（`PackagingVariantEditor.vue:381` 等的 `.btn-sm.primary`）决定，全局 utilities.css 那份被组件 scoped 样式覆盖。

### 对比度实测矩阵（WCAG 2.1，脚本 `frontend/scripts/audit/primary-btn-probe.mjs`）

| 场景 | 深色现状（surface 作文字） | 改为白字后 | 判定 |
|---|---|---|---|
| A 主色实心（`#14b8a6`） | **5.88:1 ✓** | 2.49:1 ✗ | 现状达标，改白**劣化** |
| E 琥珀底（`#f59e0b`） | **6.82:1 ✓** | 2.15:1 ✗ | 现状达标，改白**劣化** |
| E 绿色底（`#22c55e`） | **6.42:1 ✓** | 2.28:1 ✗ | 现状达标，改白**劣化** |
| B 图片遮罩（`rgba(0,0,0,.6)`） | 约 1.4:1 ✗ | 约 8.9:1 ✓ | **确实崩坏**，改白修复 |
| C 危险色实心（`#ef4444`） | 3.89:1 ✗ | 3.77:1 ✗ | 控件内容是字符 `x`（文本，门槛 4.5），两者均未达标 → 不动，归 P1 |
| E 灰底徽标（`--color-text-tertiary`） | 3.08:1 ✗ | 4.76:1 ✓ | **确实不足**，改白修复 |
| D 反转（`--color-text-heading` 底 + surface 字） | 双主题均高对比 ✓ | 改白会破坏 | 保持 |

> 根源：深色主题为"在深色背景上可辨"而**调亮**了主色/状态色，但这些调亮后的色值又被用作**填充面背景**，此时白字反而不达标。这是缺少"填充面色阶（solid/filled scale）"的系统性问题，不是单靠替换文字色能解决的。

### 最终分类与处置（47 处，A–E）

| 类 | 处数 | 处置 | 依据 |
|---|---|---|---|
| A 主色实心 | 34（含 `utilities.css:146`） | **不动**（并回退先前误改） | 深色 5.88 ✓；改白 2.49 ✗ |
| B 遮罩/图片 | 7 | 改 `--color-text-on-overlay`（新增） | 深色 1.4 ✗ → 8.9 ✓ |
| C 危险色实心 | 1（`CertDetailModal.vue:1586`） | **不动**（曾改后回退） | 该控件内容是字符 `x`（文本字形，适用 4.5 门槛），白 3.77 / 深 3.89 **均未达标**；浅色下两值同为 `#ffffff` 属零变化，深色下改白为 −0.12 的微小劣化，无改善 → 归入 P1 填充色阶 |
| D 刻意反转 | 1（`DingtalkSyncPanel.vue:637`） | 不动 | 双主题均高对比 |
| E 灰底徽标 | 1（`asset-gallery.css:325`） | 改 `--color-text-on-overlay` | 3.08 ✗ → 4.76 ✓ |
| E 琥珀/绿色底 | 3 | 不动 | 深色 6.82/6.42 ✓，改白 2.15/2.28 ✗ |

**浅色零变化保证**：浅色 `--color-surface` 与新令牌同为 `#ffffff`，故 B/E 所有改动在浅色模式下计算色完全不变（已实测 `.thumb-drop-label` 浅色 = `rgb(255,255,255)`）。

### B 类「图片上的文字」实测（真实图片亮度，非固定色值推断）

`/packaging` 采集 24 张真实图片的亮度分布：`p50` 范围 0 → **0.999**，`p95` 范围 0.266 → **1.0**——**近乎纯白的图片真实存在**，因此必须按纯白最坏情况核算：

| 元素 | 遮罩层 | 纯白图下白字对比度 | 判定 |
|---|---|---|---|
| `.asset-ext-corner` | `rgba(15,23,42,.62)` | 5.03 | ✓ |
| `.htb-btn:hover` | 父层 `--color-overlay-dark`(.82) + `.14` 白叠加 | 7.16 | ✓ |
| `.manager-actions button` | 父层 `overlay-image-strong`(.7/.8) + `.2` 白叠加 | 4.94 / 6.69 | ✓ |
| `.thumb-drop-label` | `rgba(15,23,42,.55)` → 已提到 `.62` | 4.04 → **5.03** | ✓（本轮一并修） |
| `.hero-image-count`、`.carousel-*` | `--color-overlay-image`(.5/.6) | 深色 5.74 ✓ / 浅色 3.95 | 浅色为既有状态、本轮零变化，记 P2 |

### 已修与未修（截至 2026-10-23）

- **已修并实测通过**：dark.css 补 12 条令牌（C1+C2）+ 新增 1 条语义令牌（`--color-text-on-overlay`）；浏览器实测 13/13 令牌解析正确（脚本 `frontend/scripts/audit/dark-check.mjs`）。
- **已修**：B 类 7 处、E 灰底 1 处；另 `.thumb-drop-label` 遮罩透明度 0.55 → 0.62（与 `.asset-ext-corner` 对齐）。
- **回退（C 类）**：`CertDetailModal.vue` 的 `.btn-icon:hover` 保持 `--color-surface`。理由见上：内容为文本字形 `x`，两种取值均未达 4.5，改动无改善。
- **回退**：`utilities.css` 三处（A 类改白会劣化；其中 `.btn-primary`、`a.shop-link` 为 0 使用死代码，宜在 C10 删除而非改色）。
- **遗留 P1**：深色"填充主色/状态色面 + 白字"体系不达标（EP 主按钮白字压 `#14b8a6` 实测 2.49 ✗，全站性）。需引入 `--color-primary-solid` / `--color-danger-solid` 等填充色阶并与 Element Plus 填充色统一后再一并处理。

---

## C4 引用未定义令牌（2026-10-23）

### 扫描口径

工具 `frontend/scripts/audit/token-usage.mjs`（新增，可作后续回归）。定义来源必须同时认三类，否则误报：
CSS 声明 `--x:`、JS/内联对象键 `'--x':`、运行时 `setProperty('--x')`。

扫描 438 文件 / 引用令牌 323 个 / 定义 481 个 → **缺失 48 个**：

| 类别 | 数量 | 后果 |
|---|---|---|
| 无 fallback | 23 个变量 / 41 处 | `var()` 无法替换 → **整条声明被丢弃**，浏览器不报错 |
| 有 fallback | 25 个变量 | 实际渲染的是 fallback 硬编码值，令牌形同虚设 |
| 动态注入 | 3 个（`--metric-grid-columns`、`--metric-grid-min`、`--ov-color`） | `:style` 运行时写入，**不整改**，仅在扫描器中识别避免误报 |

### C4-A 已修：23 个变量 / 41 处 / 16 文件

渲染实测（`_audit_shots/c4a-verify.mjs`，浅深双主题各 23 项对照）：把修复前后的声明分别套到探测元素上读计算值，确认旧声明的计算值均为"失效签名"。

| 变量 | 处 | 实测失效表现 | 改为 |
|---|---|---|---|
| `--space-1/2/4` | 10 | 间距声明丢弃 → 卡片 gap/padding、工具栏间距塌成 0 | tokens.css 补 4px 步长别名（4/8/16px）→ `--space-xs`/`--space-sm-md`/`--space-lg-xl`，与已有 `--space-3`(12px)/`--space-5`(20px) 成系列 |
| `--color-bg-subtle` | 6 | 背景 `rgba(0,0,0,0)`：chip、提示块、图片占位无底色 | `--color-surface-alt` |
| `--color-fill` | 3 | 背景透明：H3 指标卡、批量条、进度条轨道 | `--color-surface-alt` |
| `--color-primary-soft` | 3 | 底色透明（角色卡选中态无高亮）；边框回落 `currentColor` | 底色 `--color-primary-bg`；边框 `--color-primary-border` |
| `--color-bg-surface` | 2 | 背景透明：官网横幅筛选栏、表格无白底 | `--color-surface` |
| `--color-text-quaternary` | 2 | 回落继承正文色，弱化层级消失 | `--color-text-muted`（白底 4.76:1 ✓） |
| `--color-success-300/-50`、`--color-warning-300`、`--color-danger-300/-50/-400` | 6 | 质检三态边框回落 `currentColor`、底色透明 → **颜色编码全丢** | 规范色阶 `--color-{success,warning,danger}-border` / `-bg-soft` / `--color-danger`（保留原 color-mix 透明度意图） |
| `--color-accent` | 1 | `linear-gradient` 整条丢弃 → 资产库角标无底色 | `--color-purple`（与该块 `box-shadow: rgba(124,58,237,.28)` 一致） |
| `--color-bg` | 1 | 透明 → 品牌资产吸顶栏失效，滚动内容穿透 | `--color-page-bg` |
| `--color-border-2`、`--color-border-default` | 2 | 边框回落 `currentColor`（用上深色文字色） | `--color-border` |
| `--color-surface-2`、`--color-surface-muted` | 2 | 背景透明 | `--color-surface-alt` / `--color-surface-disabled` |
| `--lh-base`、`--fw-regular` | 2 | 行高回落 `normal`；字重回落 400（视觉无差，仍属失效） | `--lh-normal` / `--fw-normal` |
| `--lq-text-secondary` | 1 | 内联样式失效，回落继承色 | `--color-text-secondary` |

复核：修复后 `无 fallback = 0`；16 文件 lint exit 0；全量 `70 files / 524 tests` 通过。

### C4-B 待定：25 个有 fallback 变量（本轮不改代码）

| 变量 | 引用 | fallback 分布 | 建议 |
|---|---|---|---|
| `--space-s` | 52 | 8px×49, 6px×3 | 主流 8px = `--space-sm-md`；6px = `--space-sm`。不新增 `--space-s`，按场景改用命名令牌（控件间距优先 `--space-control-gap`） |
| `--space-m` | 43 | 12px×31, 16px×7, 10px×5 | 三档分散，说明意图不同：12→`--space-md-lg`、16→`--space-lg-xl`、10→`--space-md`。需逐处判断，不做全局替换 |
| `--radius-card` | 13 | 10px×7, 8px×5, 6px×1 | 10→`--radius-xl`、8→`--card-radius`（=radius-lg）、6→`--radius-md` |
| `--color-bg-elevated` | 5 | #f9fafb×3, #f3f4f6×2 | `--color-surface-raised`（语义即"抬升面"） |
| `--color-surface-subtle` | 4 | #f1f5f9×2, #f8fafc×2 | 按用途 `--color-surface-hover` / `--color-surface-alt` |
| `--color-bg-surface` | 4 | #fff×4 | `--color-surface`（**同值，零视觉变化**） |
| `--color-bg` | 3 | #f6f7f9×2, #f8fafc×1 | `--color-page-bg`（#f4f6f8，近似） |
| `--color-warning-600` | 3 | #d97706×3 | `--color-warning`（**同值**） |
| `--color-text-body` | 3 | #334155×3 | `--color-text-strong`（#475569，最接近） |
| `--color-fill` / `--color-fill-hover` | 2 / 1 | #f8fafc、#f1f5f9 / #f1f5f9 | `--color-surface-alt` / `--color-surface-hover`（**同值**） |
| `--color-danger-50` | 2 | #fef2f2×2 | `--color-danger-bg-soft`（#fff1f2，近似） |
| `--color-surface-soft` | 2 | #f8fafc×2 | `--color-surface-alt`（**同值**） |
| `--color-danger-600` / `--color-danger-500` | 2 / 1 | #dc2626 | `--color-danger`（**同值**） |
| `--color-bg-input` | 2 | #f9fafb×2 | `--color-surface-alt`（需确认是否为输入框底） |
| `--space-2xs` | 2 | 4px×1, 2px×1 | 4px → `--space-xs`；2px 现无令牌（最小 4px），需单独决策 |
| `--space-l` | 2 | 16px×2 | `--space-lg-xl`（**同值**） |
| `--color-bg-subtle` | 2 | #fafafa、`var(--color-surface)` | `--color-surface-alt` |
| `--color-surface-muted` | 1 | #f1f5f9 | `--color-surface-disabled` 或 `--color-surface-hover` |
| `--color-bg-secondary` | 1 | #f8fafc | `--color-surface-alt`（**同值**） |
| `--color-sidebar-active-text` | 1 | `var(--color-sidebar-text-strong)` | 直接写 `--color-sidebar-text-strong` |
| `--border-color` | 1 | #e5e7eb | `--color-border-light`（**同值**，EP 旧变量名） |
| `--color-danger-300` | 1 | #fca5a5 | `--ep-danger-5`（**同值**） |
| `--color-danger-700` | 1 | #b91c1c | `--color-danger-text`（#be123c，近似） |
| `--color-success-600` | 1 | #059669 | `--color-success`（#16a34a，近似） |
| `--color-success-bg-light` / `--color-warning-bg-light` | 1 / 1 | #f0f9eb / #fdf6ec | `--color-{success,warning}-bg-soft`（近似） |
| `--color-primary-600` / `--color-primary-soft` | 1 / 1 | #2563eb / `rgba(59,130,246,.12)`（**蓝色**） | 与项目主色（teal）冲突**必须逐处判断**：属"信息/链接"用 `--color-info`，属主色用 `--color-primary` |
| `--app-header-height` | 1 | 58px | 实测 topbar 仅 38px，58px 实为「topbar + 页面留白」合计，名实不符；但当前 `.visual-editor` 无溢出（顶部偏移 70、高 918、viewport 1000），改动需重设该页高度算式 → 不新增令牌 |
| `--el-dialog-width` | 1 | `var(--dialog-width-workspace)` | **保留**。这是 Element Plus 由 `width` prop 透传的真实变量（见 `LqDialog.vue:219` 注释），本处写 `var(--el-dialog-width, var(--dialog-width-workspace))` 正是让 prop 优先、令牌兜底，属正确用法，不是无效命名 |

### C4-B1 已修：同值同语义 18 处 / 11 文件（`cac7c62`）

**同值只保证浅色**：fallback 是写死的字面量、不跟随主题，深色下会沿用浅色值。故 B1 逐项做浅/深双主题计算值对照后才替换。

| 变量 | 处 | 浅色 | 深色（原 → 新） | 语义核实 |
|---|---|---|---|---|
| `--color-bg-surface` | 4 | 同值 #fff | `#fff` → `#1e293b` | 卡片/面板底（`.accessory-card`、`.q-item`、`.history-item`、`.score-inner`） |
| `--color-warning-600` | 3 | 同值 #d97706 | `#d97706` → `#f59e0b`（文字对比度 5.6 → **8.31**） | 状态文字/指示点 |
| `--color-danger-600` / `-500` | 3 | 同值 #dc2626 | `#dc2626` → `#ef4444`（3.7 → **4.74**） | 状态文字/指示点 |
| `--color-surface-soft` / `--color-bg-secondary` | 3 | 同值 #f8fafc | `#f8fafc` → `#1a2332` | 空态块、代码块底 |
| `--color-fill` + `--color-fill-hover` | 2 | 同值 #f8fafc / #f1f5f9 | → `#1a2332` / `#293548` | **必须成对**：base 与 hover 同属一个折叠头，只改 hover 会变成「浅底 + 深 hover」 |
| `--border-color` | 1 | 同值 #e5e7eb | `#e5e7eb` → `#2d3748` | 表单容器边框 |
| `--color-sidebar-active-text` | 1 | 同值 #fff | 无差异 | 本就是 `--color-sidebar-text-strong` 的别名 |
| `--space-l` | 2 | 16px | 无差异 | 纯间距 |

### C4-B2 已修：间距/圆角 108 处 / 9 文件（`5d0c283`）

范围全在 mold/packaging 这一套组件里，是一套独立写法。映射按**每处 fallback 的实际取值**决定，不按变量名：

| 原 | fallback | 目标令牌 | 处数 |
|---|---|---|---|
| `--space-s` | 8px | `--space-sm-md` | 48 |
| `--space-s` | 8px（`.detail-actions` 操作按钮行） | `--space-control-gap`（语义别名，同值） | 1 |
| `--space-s` | 6px | `--space-sm` | 3 |
| `--space-m` | 12px / 16px / 10px | `--space-md-lg` / `--space-lg-xl` / `--space-md` | 31 / 7 / 5 |
| `--radius-card` | 10px / 8px / 6px | `--radius-xl` / `--card-radius` / `--radius-md` | 7 / 5 / 1 |

「布局不变」是证明出来的，不是靠肉眼：浏览器实测 9 个目标令牌取值与各自 fallback **逐一相等**（8/6/12/16/10/10/8/6/8px，浅深双主题一致），故每个声明的计算值完全相同；替换处数按 8 组预期值逐组校验（108/108）。

### C4-B3 只分析：剩余 20 个变量的三类决策

**① 保留（不改，均附理由）**

| 变量 | 处 | 理由 |
|---|---|---|
| `--el-dialog-width` | 1 | Element Plus 由 `width` prop 透传的真实变量，`var(--el-dialog-width, …)` 是「prop 优先、令牌兜底」的正确写法 |
| `--color-primary-600` | 1 | fallback #2563eb 是 EP 蓝，与项目主色 teal 语义冲突；需先判断原意是「信息色」还是「主色」，不能按名替换 |
| `--color-primary-soft` | 1 | 同上（`rgba(59,130,246,.12)` 为蓝） |
| `--color-primary-bg-strong` | 1（`CertificationFormDialog:471`） | 该处 hover 底色是 EP 蓝 tint、边框却是 teal `--color-primary-border`，属旧蓝色主题残留；改绿是**可见变化**，需单独确认 |
| `--color-text-body` | 3 | #334155 无等值令牌（primary #1d252f 明显更深、strong #475569 更浅），替换必然改变浅色正文观感 |
| `--app-header-height` | 1 | 名实不符（实测 topbar 38px），但当前渲染正确；属布局算式问题，归 C6 处理 |
| `--space-2xs`（2px 站点） | 1 | 项目最小间距令牌是 4px，2px 是真实需求（`.tab-header-actions` 的 `padding: 0 2px`），不能为了复用改成 4px |
| `--color-surface-muted` | 1 | 取值 #f1f5f9 与 `--color-surface-hover` 完全相同，但用途是静态面板底，「hover」语义不成立；需在「同值错语义」与「正确语义差 1 档」之间决策 |

**② 可修改（同值组已执行；近似组待批准）**

同值组执行结果（`51a3c09`，双主题实测后）：

| 变量 | 处 | 双主题实测 | 结果 |
|---|---|---|---|
| `--color-surface-subtle`（#f8fafc，`.settle-tip` / `.detail-note`） | 2 | 浅色同值；深色 `#f8fafc` → `#1a2332` | **已收敛** → `--color-surface-alt`（提示块在深色下原本是浅灰块，属主题修复） |
| `--color-bg-subtle`（fallback 本就是 `var(--color-surface)`） | 1 | 双主题同值 | **已收敛** → `--color-surface` |
| `--space-2xs`（4px，`.header-right` 内为 el-tag 标签组） | 1 | 双主题同值 | **已收敛** → `--space-control-gap-tight`（按场景语义，不用通用刻度 `--space-xs`） |
| `--color-surface-subtle`（#f1f5f9，`.version-detail-total` / `.formula-row.total` 合计行） | 2 | 浅色同值；深色 → `#293548` | **否决保留**：值相同的 `--color-surface-hover` 语义是 hover 态 |
| `--color-fill`（#f1f5f9，`.detail-sec__count` 计数徽标） | 1 | 浅色同值；深色 → `#293548` | **否决保留**：同为静态填充，无等值且语义相符的令牌 |

`#f1f5f9` 的静态填充另有一个同值候选 `--color-surface-readonly`，但其语义是"只读面"且**项目内 0 处引用**，属"颜色相同、语义不同"，按约定不采用。

| 组 | 变量 | 处 | 目标 |
|---|---|---|---|
| 近似 | `--color-bg-elevated` | 5 | #f9fafb → `--color-surface-raised`；#f3f4f6 → `--color-surface-hover` |
| 近似 | `--color-bg` | 3 | #f6f7f9 → `--color-page-bg`；#f8fafc → `--color-surface-alt` |
| 近似 | `--color-bg-input` | 2 | #f9fafb → `--color-surface-alt`（实际用于面板底，不是输入框） |
| 近似 | `--color-danger-50` / `--color-danger-700` | 2 / 1 | → `--color-danger-bg-soft` / `--color-danger-text` |
| 近似 | `--color-success-600` / `--color-success-bg-light` / `--color-warning-bg-light` | 1 / 1 / 1 | → `--color-success` / `--color-{success,warning}-bg-soft` |
| 近似 | `--color-bg-subtle`（`PackagingVariantCards:460`） | 1 | #fafafa → `--color-surface-alt` |
| 待定 | `--color-danger-300` | 1 | 二选一：`--ep-danger-5`（同值 #fca5a5 但语义是 EP 组件色阶）/ `--color-danger-border`（语义正确，浅色差 1 档、深色为半透明） |

**③ 新增令牌：本轮为 0**

不新增任何别名。`--app-header-height` 经实测确认名实不符，正确做法是重设该页高度算式（C6），而不是把 58px 固化成令牌；`--space-2xs` 的 2px 需求若后续反复出现，再单独评估是否引入 `--space-3xs`。

### C4-B3 三分类总表（剩余 19 个变量 / 31 处）

| 分类 | 变量 | 处 | 说明 |
|---|---|---|---|
| **可修改** | `--color-bg`（#f8fafc 站点） | 1 | 与 `--color-surface-alt` 同值且语义一致，后续可直接收敛 |
| **需专项视觉评估** | `--color-bg-elevated` | 5 | 需先分清卡片 / 抬升面 / 悬浮层，再决定 `surface-raised` 还是其他 |
| | `--color-bg-input` | 2 | 实际是面板底（`.quality-score`、`.history-empty`），不是输入框；需确认映射 `--color-surface-alt` 是否可接受 |
| | `--color-bg`（#f6f7f9 站点） | 2 | `App.vue` 授权兜底层、`LoginView` 登录层；`--color-page-bg` 为近似值 |
| | `--color-surface-subtle`（#f1f5f9）/ `--color-fill` / `--color-surface-muted` | 2 / 1 / 1 | 静态填充；同值令牌语义不符（hover / readonly），语义令牌值不同，需专项权衡 |
| | `--color-danger-50` / `-300` / `-700` | 2 / 1 / 1 | 需连同所在组件的背景、文字、边框一起看状态配色体系 |
| | `--color-success-600` / `--color-success-bg-light` / `--color-warning-bg-light` | 1 / 1 / 1 | Element Plus 绿/橙旧色阶残留，需确认是否整套替换 |
| | `--color-text-body` | 3 | 正文层级取舍：无等值令牌，改则浅色观感变化 |
| | `--color-primary-600` / `--color-primary-soft` / `--color-primary-bg-strong` | 1 / 1 / 1 | 蓝色遗留：需按组件用途判断是信息提示（`--color-info`）还是品牌强调（`--color-primary`）；`--color-primary-bg-strong` 需先看正常/hover/active/selected 是否构成完整配色体系 |
| **合理保留** | `--space-2xs`（2px 站点） | 1 | 2px 是真实需求（`.tab-header-actions`），项目最小间距令牌为 4px，不为复用改成 4px |
| | `--app-header-height` | 1 | 名实不符（实测 topbar 38px）但当前渲染正确，属布局算式问题 → 转 C6 重设该页高度 |
| | `--el-dialog-width` | 1 | Element Plus 由 `width` prop 透传的运行时变量，`var(--el-dialog-width, …)` 是 prop 优先、令牌兜底的正确写法 → 扫描器已加例外，不再报警 |

### C4 收口结论

**验收标准（已调整）**：不追求把剩余变量压到零。标准是——① 所有真实缺失且导致声明失效的变量均已修复；② 其余变量要么完成语义与取值都成立的映射，要么有明确记录的保留原因。

- 无 fallback 缺失：`23 变量 / 41 处` → **0**（C4-A 修完并保持）
- 有 fallback 命名漂移：初扫 `48` → **19 个变量 / 31 处**；其中 4 处已在 B3 同值组收敛，其余按上表三类处置
- 框架/运行时变量：1 个（`--el-dialog-width`）已纳入扫描器例外表，不再计入缺失

扫描器新增 `FRAMEWORK_VARS` 例外表，用于登记"由组件库或运行时注入、引用属正确用法"的变量（须写明注入方与本项目用法出处），避免其长期作为普通缺失报警并诱导错误替换。

**C4 治理完成**，剩余特殊项按上表转入专项：静态填充与状态配色体系 → 专项视觉评估；`--app-header-height` → C6；蓝色遗留 → 组件配色确认。

---

## C5 重复令牌与主题覆盖关系（2026-10-23，只读审计）

工具 `frontend/scripts/audit/token-graph.mjs`。解析 209 个样式单元（CSS 文件 + `.vue` 的 `<style>` 块），得到 632 条令牌定义、13,965 处 `var()` 引用；输出「定义位置 → 生效值 → 被哪些令牌引用 → 影响哪些文件」。

**三类排除规则**（否则全是误报）：① `@media` 内的重定义是响应式设计，不是重复定义错误；② `--el-*` 由 Element Plus 自身 CSS 消费（在 node_modules），零引用不算死代码；③ `var()` 也会以字符串出现在 `.vue` 的 `<script>` 与 `.ts`（如 `LqDialog` 的 `sizeWidthMap`），引用统计必须覆盖这些位置。

### C5-A 重复定义：**0 组**

同名多值共 9 组，逐组核实后全部是断点覆盖：`--page-inline-padding`、`--page-block-padding`、`--card-padding`、`--space-section-gap(-wide)`、`--space-card-gap`、`--sidebar-width`、`--toolbar-min-height`（`responsive.css`）与 `--stage-cols`（`TrackingDetailDrawer.vue`）。**无同一作用域内的重复声明**。

### C5-B 主题覆盖：base 403 / dark 135（131 正常覆盖 + 4 深色孤儿）

深色未覆盖 35 个（实测计算值确认，非推断），分两类：

- **正常，不需要改（11 个）**：侧栏 7 个（`--color-sidebar-text/-strong/-muted`、`--color-sidebar-overlay-*`，侧栏在深浅两主题下都是深色底，恒定正确）；浮层与提示 3 个（`--color-tooltip-bg/-text`、`--color-text-on-overlay`，浮层恒为深色面 + 白字）；`--color-brand-cyan`（品牌色不随主题）。
- **覆盖不对称，需专项评估（24 个）**：`--ep-success-3/5/7/8/9/-dark`、`--ep-warning-3/5/7/8/9/-dark`、`--ep-danger-3/5/7/-dark`、`--ep-info(-3/5/7/8/9/-dark)`、`--ep-primary-dark`。`dark.css` 只覆盖了 8 个 EP 令牌（primary 的 filled/3/5/7/8/9 与 danger 的 8/9），success / warning / info 一档未覆盖 → 深色下 EP 组件的 light 变体（el-tag、el-alert、plain 按钮）仍渲染浅色面。

### C5-C 语义重叠

- **同值但语义不同 → 保留**：`--color-text-faint`(54 文件) / `-disabled`(3) / `-placeholder`(5) 浅色同为 #94a3b8，深色已分别覆盖为 #8493a7 / #64748b / #64748b，属有意区分。
- **跨色系重合 → 需核实**：`--color-primary-border` 浅色为 `#a7f3d0`，与 `--color-success-border` **完全相同**；而深色下两者分别是 `rgba(20,184,166,.25)`（teal）与 `rgba(22,163,74,.3)`（green）。即浅色的主色边框用了绿色系值，深色才是 teal，两侧不一致 → 浅色值疑似从 success 复制而来。影响 38 个文件。
- **零引用的同值别名 → 可清理**：见 C5-D。

### C5-D 零引用令牌：78 个（项目自有）

- **真死代码候选（与主流令牌同值的另一套命名，零引用）**：`--color-text-regular`（=`--color-text`）、`--color-bg-page` / `--color-background`（=`--color-page-bg`）、`--color-success-bright`（=`--color-success`）、`--color-surface-elevated`（=`--color-surface-raised`）、`--color-overlay-bg`（=`--color-overlay`）、`--color-primary-50`（=`--color-primary-bg`）、`--color-warning-light` / `--color-amber-100/50/600/700`、`--color-danger-light` / `--color-success-100` / `--color-success-border-light`、`--color-hover`、`--space-list-row-gap`（=`--space-control-gap`）、`--color-border-dark/focus/selected`、`--color-primary-dark/text`、`--color-text-inverse`、`--color-completion-*`、`--shadow-color-gray` / `--shadow-drawer`、`--opacity-*`、`--space-5`（已由 `--space-1..4` 系列覆盖）。
- **预留规范，保留**：排版体系 `--text-h1..h4` / `--text-body(-sm)` / `--text-caption` / `--text-display` / `--text-overline` / `--text-placeholder` / `--fs-2xs`；层级 `--z-*`；动效 `--duration-*` / `--ease-*` / `--transition-*`；字形 `--tracking-*` / `--lh-wider`；断点 `--bp-*`；边框宽度 `--border-width-*`；对话框宽度 `--dialog-width-*`（由 `LqDialog.vue` 的 `sizeWidthMap` 在 `<script>` 中引用，静态只扫 `<style>` 会误判）；`--content-max-width`、`--color-glass-border`。
- **无效覆盖（深色孤儿，且 EP 不消费）**：`--el-alert-text-color`（`element-plus.css:328`）。已在 Element Plus 全部 123 个 CSS 文件中确认**未使用该变量**，且同一规则内已直接写 `color: var(--color-danger-text)`，该行是冗余的。另 3 个深色孤儿 `--el-radio-button-checked-bg/text/border-color` 经确认**被 EP 消费**，属正常覆盖。

### C5 结论表

| 变量 | 问题类型 | 当前生效值（浅 / 深） | 影响范围 | 建议 | 风险 |
|---|---|---|---|---|---|
| `--color-border-light` | C5-B **正常**主题覆盖 | #e5e7eb / #2d3748 | 被 6 个 EP 变量依赖（`--el-border-color`、`--el-border-color-light`、`--el-disabled-border-color`、`--el-table-border-color`、`--el-table-border`、`--el-message-border-color`）+ 64 个文件 | 保留现状；改动须联动验证 EP 控件 | 高：一处改动波及全站边框与表格 |
| `--color-sidebar-text-strong` | C5-B **正常**（侧栏恒深色） | #ffffff / #ffffff | 无令牌依赖，仅 `DefaultLayout.vue` | 保留，建议补注释说明「侧栏深浅均为深色底，故不需要深色覆盖」 | 无 |
| `--color-sidebar-text/-muted`、`--color-sidebar-overlay-*`（7 个） | C5-B 正常 | 见实测 | 侧栏相关 | 保留 | 无 |
| `--color-tooltip-bg/-text`、`--color-text-on-overlay`、`--color-brand-cyan` | C5-B 正常 | 恒定值 | 浮层/品牌 | 保留 | 无 |
| `--ep-success/warning/info/danger` 浅色阶（24 个） | C5-B **覆盖不对称** | 深色沿用浅色值（如 `--ep-success-9` 深浅均 #dcfce7） | EP light 变体（el-tag / el-alert / plain 按钮等） | 专项评估：与已覆盖的 primary(6) / danger(2) 对齐 | 中：深色下多处浅色面会变深 |
| `--color-primary-border` | C5-C **跨色系重合** | 浅 #a7f3d0（= success 边框）；深 `rgba(20,184,166,.25)`（teal） | 38 个文件 | 核实浅色是否应为 teal 系（如 #99f6e4） | 中：浅色可见变化，需专项评估 |
| `--color-text-regular`、`--color-bg-page`、`--color-background`、`--color-success-bright`、`--color-surface-elevated`、`--color-overlay-bg`、`--color-primary-50`、`--space-list-row-gap` 等 | C5-D 真死代码候选 | 与主流令牌同值 | 0 引用 | 可删除，逐个确认无 TS 引用后清理 | 低 |
| `--el-alert-text-color` | C5-D 无效覆盖 | 仅在深色块定义 | EP 全量 CSS 未消费 | 删除该行（同规则内 `color:` 已生效） | 低 |
| `--text-h*`、`--z-*`、`--duration-*`、`--ease-*`、`--dialog-width-*` 等 | C5-D 预留规范 | — | 0 引用但成体系 | 保留 | 无 |

### C5 决策与执行（2026-10-23）

**① `--el-alert-text-color`：已删除（`1b262dd`，单文件 1 行）**

判定不靠变量名推断：在 `node_modules/element-plus` 全部 **6,830 个文件**（含 js / mjs / scss / css）中检索该变量名，**命中 0 处**；`src` 内也无 `var()` 引用。项目自身的 Alert 文字色由 `element-plus.css:270` 的 `.el-alert--error .el-alert__title` 与 `dark.css:144/150` 直接声明，早已覆盖，故该变量完全惰性。

最小修改：只删该声明，未动同一规则里的 `color`，也未调整其他 Alert 样式。

删除前后对照（浅/深双主题，注入 error alert 的 `is-light` 与默认变体，读容器与标题计算色）**逐项一致**：

| 主题 | 位置 | 删除前 | 删除后 | 与 `--color-danger-text` 关系 |
|---|---|---|---|---|
| 浅色 | `is-light` 标题 | `rgb(190,18,60)` | `rgb(190,18,60)` | = `#be123c` ✓ |
| 浅色 | `is-light` 容器 / 底色 | `rgb(245,108,108)` / `rgb(254,240,240)` | 同左 | EP 默认 |
| 深色 | 容器与标题 | `rgb(252,165,165)` | `rgb(252,165,165)` | = `#fca5a5` ✓ |

复核：prettier 格式检查通过，全量 70 文件 / 525 用例通过。

> 验证中另发现一处**不属于本次范围**的现象：深色下非 `is-light` 的 error alert 容器底色仍是 `rgb(255,255,255)`（EP 默认 alert 底色），已并入下方 EP 专项清单。

**② `--ep-*` 深色覆盖不对称：转入「Element Plus 深色变体」视觉专项**

立项但不一次性补齐 24 个变量。**验收标准是 EP 组件在双主题下的实际表现，不是「24 个变量是否都有 dark 定义」。**

验证范围：`el-tag`（success / warning / danger / info）× `el-alert`（各状态）× `el-button`（plain / text / disabled）；每个组件验证默认、hover、active、disabled 四态，以及浅色 / 深色两模式下的实际颜色与文字对比度（正文 ≥ 4.5）。

处置分类：

| 分类 | 处理方式 |
|---|---|
| 深色下实际存在低对比度或异常亮底 | 补充对应深色令牌 |
| 深色下沿用浅色值，但视觉与对比度正常 | 保留 |
| 当前业务完全未使用、也未发现问题的色阶 | 暂不修改 |
| 多个色阶存在系统性问题 | 再考虑完整补齐该色系 |

已知待验 Item：深色下默认 `el-alert` 白底（`rgb(255,255,255)`）。

**③ `--color-primary-border`：保留，暂不修改**

「浅色下与 `--color-success-border` 同为 `#a7f3d0`」只能说明当前值相同，**不足以证明是复制错误**；该变量涉及 38 个文件，修改风险较大。后续需核实三件事：① 是否用于品牌强调 / 选中 / 焦点等主色语义；② 浅色下该边框与主色背景、文字是否协调；③ 现有值是否真的影响视觉，还是符合当前青绿色品牌设计。无明确视觉问题则不改。

**④ C5 收口**

| 项 | 结论 |
|---|---|
| C5-A 重复定义 | **完成**：同作用域重复定义 0 组，9 组同名多值均为响应式覆盖，属正常设计，继续保留 |
| C5-B 主题覆盖 | **审计完成**：base 403 / dark 135；深色未覆盖 35 个中 11 个属正常机制、24 个转入 EP 视觉专项 |
| C5-C 语义重叠 | **审计完成，不做批量合并**：语义不同的变量即使同色也可并存（如主色边框与成功边框）；`--color-primary-border` 按③保留核实 |
| C5-D 无效令牌 | **删除已确认的 1 处**（`--el-alert-text-color`）；78 个零引用暂不批量清理，后续遗留代码阶段按「无引用、无动态消费、非规范预留」三条件逐项核对；成体系的排版 / 层级 / 动画 / 布局令牌不删 |

C5 标记为治理完成，未决项（EP 深色变体、`--color-primary-border`）移入视觉专项清单。

---

## C6 全局与组件样式职责冲突（2026-10-23，只读审计）

工具 `frontend/scripts/audit/style-scope.mjs`。统计 5,383 条规则（全局表 1,158 / 组件非 scoped 173 / 组件 scoped 4,052），用于发现「谁在改谁」。

**判断前提**：`:deep()`、`!important`、EP 内部选择器本身都不是错误，只有出现**真实优先级冲突或重复实现**才列为整改项。本轮只审计不改动。

### C6-1 `.btn-sm` 重复实现且已漂移（最值得整改）

同一个类名在 **9 个文件**各自 scoped 定义，且已漂移成 **4 种写法**，视觉并不一致：

| 写法 | 出处 | 特征 |
|---|---|---|
| A | `CertDetailModal.vue` | `padding: 6px 12px`、圆角 `--radius-sm`、文字 `--color-text-secondary` |
| B | `FeeRecordsPanel.vue` | `padding: 5px 12px`、字号 `--fs-xs`（其余同 A） |
| C | `FilePreviewBody.vue` | 圆角 `--radius-md, 6px`、边框 `--color-border-light`、文字 `--color-text-strong`、字重 semibold |
| D | `PackagingBatchEditModal.vue` / `PackagingBatchFillModal.vue` | `height: 32px`、`padding: 0 14px`、字号 `--fs-xs`、字重 bold、文字 `--color-text-heading` |

`.btn-sm` 在 12+ 个组件、26 处以上真实使用，因此这是**对外可见的不一致**：同一套 UI 里同为次要按钮，高度（32px 固定 vs padding 撑开）、圆角、字号、文字色各不相同。

同类纯副本（逐字节相同，无漂移）：`.batch-count-pill`、`.btn-cancel` / `.btn-save`、`.dialog-actions`、`.form-dialog-header`（各 3–5 个 media 对话框重复）、`.toolbar`（4 个视图）、`.page-head` / `.page-header::before`（4 个视图）。

> 说明：这些都是 scoped 定义，彼此**不构成级联冲突**（scoped 会隔离），属于源码级重复实现，而不是优先级问题。

### C6-2 `utilities.css` 的硬编码 + `!important` 裸选择器（唯一确认的行为偏差）

`utilities.css:76/82/88`（`.btn-danger`）与 `:154/160`（`.btn-sm.danger`）用写死字面量 + `!important`，**完全绕过 danger 令牌体系**。实测双主题：

| 类 | 浅色 | 深色 | 结论 |
|---|---|---|---|
| `.btn-danger` | bg `rgb(190,18,60)` | bg `rgb(190,18,60)` | **两主题完全相同**，未跟随主题；而 `--color-danger` 在深色已变为 `#ef4444`、`--color-danger-text` 变为 `#fca5a5` |
| `.btn-sm.danger` | bg `rgb(255,255,255)` | bg `rgb(255,255,255)` | 深色下**仍是纯白底**（`background: #ffffff !important`），与 C4-B 修过的"写死字面量不跟随主题"是同一类缺陷 |
| `.btn-success`（对照） | `#f0fdf4` | `rgba(22,163,74,.1)` | 走令牌，双主题正常 ✓ |

当前影响范围有限：`.btn-danger` 与 `.btn-success` 在**模板中零引用**；`PackagingDetailDrawer` 的删除按钮虽用 `class="btn-sm danger"`，但被更具体的 `.drawer-actions .btn-sm.danger`（同为 `!important`）覆盖，故尚未显现问题。属**潜在风险 + 令牌体系外绕过**，建议清理而非紧急修复。

对照之下，`packaging-detail-drawer.css`(30)、`media-account-view.css`(19)、`dialog-layout.css`(17) 中的 `!important` 都带有上下文限定（`.drawer-actions …`、`.row-btn…`、`.lq-dialog__foot …`），不会外溢。

### C6-3 分布统计（本身不是错误，供后续定位）

| 项 | 数量 | 主要分布 |
|---|---|---|
| 改写 EP 内部选择器 | 48 个文件 | 全局表 7 / 组件非 scoped 2 / **组件 scoped 39**：`element-plus.css` 45 条、`ProductionTrackingView` 19、`H3ImportCenterView` 12、`LqTable` 11、`LqDescriptions` 10 |
| `!important` | 274 处 / 26 文件 | `element-plus.css` 55、`LqDescriptions` 32、`CustomerDetailPanel` 32、`packaging-detail-drawer.css` 30、`media-account-view.css` 19 |
| `:deep()` | 174 条 / 50 文件 | `ProductionTrackingView` 16、`PackagingMoldsMasterDetail` 12、`LqTable` 11、`CustomerDetailPanel` 11、`LqDescriptions` 10 |
| 同选择器跨文件且有属性重叠 | 351 组 | Top 见 C6-1 |
| 全局类与组件 scoped 同名 | 240 个类 | Top 多为通用状态名（`.active` 22、`.primary` 12、`.required` 12、`.danger` 11） |

240 个同名类里，**绝大多数不构成冲突**：`.active` / `.primary` / `.danger` / `.muted` 等通用状态名被各组件 scoped 各自定义，scoped 隔离有效。真正需要留意的是 `.el-select`（8 个组件）与 `.el-table__cell`（6 个组件）这类 **EP 结构类被业务组件各自改写**，组件库的改动分散在业务代码里，改 EP 版本时排查面会很大。

### C6-4 引用核查：`btn-danger` / `btn-success` / `btn-sm.danger`（决定是否值得修）

先看这两个类是否真的没人用——如果没人用，把它改成令牌只是让一段死代码变规范。

| 类 | 源码命中 | 结论 |
|---|---|---|
| `.btn-danger` | 仅 `utilities.css:76/82/88` 自身定义；其余命中全部落在 `_build_check` / `_build_verify` 的**构建产物** CSS | **零引用**：不仅静态 class 没有，动态 `:class`、数组/对象绑定、JS/TS 字符串拼接也没有 |
| `.btn-success` | 仅 `utilities.css:95/101` 自身定义，其余同为构建产物 | **零引用** |
| `.btn-sm.danger` | 实际使用 **2 处**：`PackagingDetailDrawer.vue:13`「删除」、`:148`「批量删除」 | 有真实消费，见下 |

核查范围覆盖仓库全部包（`frontend` 之外也扫），扩展名含 vue/ts/tsx/js/jsx/mjs/html/css/scss/less，排除 `node_modules`、`dist`、产物目录。

### C6-5 运行时取证：同一个 `.btn-sm` 到底编译出多少份

在 `/#/packaging` 页面直接读 `<style>` 原文，`.btn-sm` 相关规则共 **60 条，来自 10 个来源**：

| 来源 | 条数 | 性质 |
|---|---|---|
| `src/styles/index.css`（`utilities.css` + `master-layout.css`） | 14 | 全局公共层 |
| `FilePreviewBody.vue` / `PackagingTagEditor.vue` / `PackagingBatchEditModal.vue` / `PackagingBatchFillModal.vue` / `PackagingBatchMoveModal.vue` / `PackagingVariantEditor.vue` | 2–3 | 组件 scoped 副本 |
| `PackagingDetailDrawer.vue` / `PackagingSpecDetailPanel.vue` / `PackagingVariantCards.vue` | 10 各 | 由 `@import '../../styles/packaging-detail-drawer.css'` 引入后再被 scoped 化，**同一份文件编译了 3 份** |

**是否泄漏到全局**：否。注入同一个 `.btn-sm.danger` 按钮，在 `/#/customers`（未加载包材模块）与 `/#/packaging`（已加载）两个页面读取计算值，**结果逐字节一致**（浅深均为 `bg=rgb(255,255,255) color=rgb(190,18,60) border=rgb(254,202,202)`）。即 `@import` 进 `<style scoped>` 的规则被 Vue 加上了 `data-v`，不会外溢。

**实际业务是否有暗色异常**：没有。`PackagingDetailDrawer` 的两个危险按钮吃到的是 `packaging-detail-drawer.css` 的 scoped 副本，该文件提供了正确的深色分支（`[data-theme='dark'] … .btn-sm.danger { background: transparent; color: #fca5a5 }`）。真正没有深色适配的是 `utilities.css:154-169` 那份**全局**规则（`#ffffff` 硬编码，浅深同值），而它当前**没有任何业务消费**——属于惯例风险而非现存缺陷。

按既定规则（实际页面已被局部样式正确覆盖 → 记录为所有权冲突，不急于修改），本轮不改代码。

### C6-6 `.btn-sm` 用途分类（28 处 / 11 文件）

同名不代表同职责，先按用途分层：

| 用途 | 组件 | 元素 | 特征 |
|---|---|---|---|
| **A 面板/抽屉头部操作组** | `PackagingDetailDrawer`（编辑/删除/批量管理/回收站/批量复制/移动到系列/批量补膜/批量编辑/批量删除/导出选中，10 处）、`ProductDetailPanel`（编辑 / ghost「更多」）、`FeeRecordsPanel`（＋新增缴费记录）、`PackagingBatchFillModal`、`PackagingBatchMoveModal`、`PackagingSpecDetailPanel` | `<button>` | 依赖字尾修饰符（`primary` / `danger` / `ghost`）区分语义，同一容器内成组出现 |
| **B 文件预览操作** | `FilePreviewBody`（新窗口打开 / 下载文件，×2） | `<a>` | 是链接不是按钮，语义上应按链接处理 |
| **C 上传触发器** | `CertDetailModal`、 `CertificationFormDialog`（均为 `.btn-sm.btn-upload`） | `<button>`/`<label>` | 与 `btn-upload` 组合使用，`btn-sm` 只提供尺寸 |
| **D 工具栏内联操作** | `OfficeAssetView`（恢复推荐设置）、`VariantImageManager`（primary） | `<button>` | 单个、非成组 |

**另有 3 处失效定义**（定义了 `.btn-sm` 但自身 template 并未使用）：`PackagingBatchEditModal.vue`、 `PackagingTagEditor.vue`、`PackagingVariantEditor.vue`。

结论：真正同构的是 **A 组**（成套出现、共用修饰符），B/C/D 只是用了同一个尺寸类名，不应合并进同一套按钮。即使 A 组内部，`ProductDetailPanel` 还有独有的 `ghost` 变体。因此**不新建统一次要按钮组件**，也暂不收敛 `.btn-sm` 类名本身。

### C6-7 样式所有权清单

| 样式类型 | 建议归属 | 现状偏差 |
|---|---|---|
| 颜色 / 字号 / 间距 / 圆角等设计令牌 | `tokens.css` | 符合（`utilities.css` 的 `.btn-sm` 基础与 `.primary` 已全部走令牌） |
| Element Plus 基础控件外观 | `element-plus.css` | 基本符合；改 EP 内部结构的规则另有 39 个组件在 scoped 里书写（C6-3） |
| 真正跨页面复用的通用工具类 | `utilities.css` | **偏差**：混入 `.btn-danger` / `.btn-success` 两个零引用的类；`.btn-sm.danger` 用硬编码而非 danger 令牌，且缺深色分支 |
| `LqDialog`、`LqTable` 等公共组件自身样式 | 对应组件及其专属样式文件 | 基本符合 |
| 包材详情抽屉、生产跟单等专用样式 | 对应业务模块 | **偏差**：`packaging-detail-drawer.css` 被 3 个组件 `@import` 后又各自 scoped 编译，实际存在 3 份；其中的 `.btn-sm.danger` / `.primary` 看似全局裸选择器，实则靠 scoped 生效，源码可读性差 |
| 业务页面独有布局 | 页面 scoped 样式 | 偏差见 C6-1（`.btn-sm` 在 9 个文件重复定义） |

### C6-8 三分类结论

| 分类 | 项目 | 下一步 |
|---|---|---|
| **可直接清理** | `utilities.css` 的 `.btn-danger`(3 条) / `.btn-success`(2 条)；`PackagingBatchEditModal` / `PackagingTagEditor` / `PackagingVariantEditor` 中未使用的 scoped `.btn-sm` 定义（8 条） | 转入 **C8**，届时连同其他无引用历史 CSS 一并处理 |
| **需视觉整改** | `.btn-sm.danger` 的三份重复硬编码（`utilities.css:154`、`packaging-detail-drawer.css:16`、同文件 `:1984`）+ A 组 6 个组件的 base/primary 重复定义 | 转入 **C7**（跨文件重复实现专项）；整改时应让 `utilities.css` 走 `--color-danger*` 令牌并补深色分支，业务侧按上下文分层，不新建按钮组件 |
| **合理保留** | `master-layout.css` 的 `.drawer-actions .btn-sm`（上下文限定的布局层，属正确分层）；`FilePreviewBody` 的 `<a>` 变体（链接而非按钮）；`ProductDetailPanel` 的 `ghost` 变体（独立语义）；`:deep()` 与带上下文限定的 `!important` | 保持现状 |

### C6 收口

C6 为只读审计，本轮**未修改任何代码**。核实结果改变了初期的判断：`.btn-sm` 不抽统一按钮组件，`utilities.css` 的 `btn-danger` / `btn-success` 归入 C8 删除候选而非在中途改颜色，真正动手的重复实现留给 C7。

---

## C7 跨文件重复样式实现专项（2026-10-23，只读审计）

工具 `frontend/scripts/audit/style-duplicates.mjs`。解析 5,391 条规则，剔除 `__tests__` / `*.test.*` / `*.spec.*`、构建产物与注释内容。

**分类**：C7-A 完全重复（同选择器 + 同声明跨文件）· C7-B 轻微漂移（同选择器、声明不一致）· C7-C 相似但职责不同（人工判定）· C7-D 重复引入（同一份样式文件被多个组件导入编译）。

> 口径修正：置于 `src/styles/` 下的文件若在组件 `<style scoped>` 里 `@import`，运行时会被加上 `data-v`，**实际是组件级作用域而非全局**。C6 把它们一律记为 global，此处更正——`media-account-view.css` 与 `packaging-detail-drawer.css` 均属这种情况。

### C7-D 重复引入（合规 ⇒ 但实际以体积与维护为代价）

| 样式文件 | 被引入次数 | 引入方 | 引入方式 |
|---|---|---|---|
| `src/styles/media-account-view.css` | **7** | `ImportUpdatePreviewModal`、`MediaAccountDetailModal`、`MediaAccountEditorModal`、`MediaAccountStats`、`MediaAnalyticsPanel`、`MediaPlatformConfigModal`、`UpdateRecordFormDialog` | 均在 `<style scoped>` 中 `@import` |
| `src/styles/packaging-detail-drawer.css` | 3 | `PackagingDetailDrawer`、`PackagingSpecDetailPanel`、`PackagingVariantCards` | 同上 |

同一份文件被编译 7 / 3 份，每份都换一个 `data-v`。**不建议直接改为全局导入**——那会改变选择器作用域，可能引入新的覆盖问题（风险：高）。正确方向是先分清其中哪些规则是真正跨组件复用的，再决定提取到 `modules/` 层由单一入口引入。

### C7-A 完全重复：其中大部分是**未使用的复制块**（新发现）

`form-dialog-header` / `form-dialog-footer` / `dialog-actions` / `btn-cancel` / `btn-save` / `batch-count-pill` 在 4–6 个 media 对话框里逐字节重复。但逐行核对后，**模板使用情况并不一致**：

| 类 | 定义处 | 模板是否使用 |
|---|---|---|
| `.form-dialog-header`（含 `h3` / `p`） | 6 个文件 | 是（4 个 media 对话框 + `MediaAccountEditorModal`） |
| `.batch-count-pill` | 5 个文件 | 是（3 个批量对话框） |
| `.dialog-actions` / `.form-dialog-footer` / `.btn-cancel` / `.btn-save`（含 `:hover`、`:disabled`） | 4 个文件 × 9 条 ≈ **36 条规则** | **否** |

未使用的结论经过全仓核验：4 个对话框里这 9 处命中**全部落在 `<style>` 内**；对全仓 `*.vue` 模板与 `*.ts`（含父组件插槽内容）检索这几个类名，**零命中**。→ 转 **C8**，低风险。

仍在使用的 `form-dialog-header` 与 `batch-count-pill` 属真实重复实现，且 `modules/media-dialog-shared.css` 本来就为这层服务 → 收敛进该文件是最自然的方向（中风险，需视觉确认前后一致）。

### 表格 / 分页：原假设被推翻

原假设是"业务页面重复实现了 `LqTable` / `LqPagination` 的视觉规范"。实测并非如此：

| 层 | 实际写法 |
|---|---|
| `LqTable.vue`（公共） | 提供字号、表头背景/字色/字重、条纹、hover、三种密度的 cell padding 等完整规范（11 条） |
| 业务侧 | 只做局部打补丁：`ManufacturingTaskConfigPanel` 与 `WebsiteSolutionsView` 改 EP 主题变量（`--el-table-border-color`、`--el-table-header-bg-color`、`--el-table-row-hover-bg-color`、`--el-table-header-text-color`）；`ProductionTrackingWeeklyReviewDrawer` 改分页对齐（`margin-top`、`justify-content`） |

**没有重复实现表头外观**。真正的问题是这些页面**裸用 `el-table`（8 / 17 / 24 处，均未使用 `LqTable`）**，绕过公共组件后再局部打补丁；分页同理，`ProductionTrackingWeeklyReviewDrawer` 裸用 `el-pagination`，而 `MasterListPanel` 已用 `LqPagination`。这属于"是否迁移到现有公共组件"的问题而非样式去重，属高风险改造，本轮只记录。

### C7-B 轻微漂移（需视觉确认）

| 选择器 | 文件数 / 写法数 | 漂移内容 |
|---|---|---|
| `.page-head` | 6 / 8 | `gap` 有 `24px` / `var(--space-lg-xl, 16px)` / `12px` / `16px` / `14px` 五种；`align-items` 有 `flex-start` / `center` / `stretch` |
| `.page-header` | 4 / 5 | `margin-bottom` `16px` vs 缺；部分文件用 `position: relative` + flex 布局，部分不用 |
| `.batch-bar` | 4 / 4 | `gap` `10px` / `8px` / `var(--space-md)`；`padding` `8px 12px` / `10px 12px` / `10px 18px` |
| `.filter-chip` | 2 / 2 | `padding` `3px 12px` vs `0 10px`；边框用 `--color-border-light` vs `--color-border`；文字 `--color-text-secondary` vs `--color-text-muted`；字号 `12px` vs `--fs-xxs` |
| `.btn-sm` | 9 / 6 | 已在 C6 记录（`padding` 五种、`border-radius` 四种、文字色四类） |

均为**中风险**：职责相似，统一与否取决于是否确认视觉一致，需前后截图比对，不凭文本相似度判定。

### C7 结论清单

| 问题 | 涉及文件 | 是否同一职责 | 实际影响 | 建议 | 风险 |
|---|---|---|---|---|---|
| `media-account-view.css` 编译 7 份 | 7 个 media 组件 | 是 | 样式体积与维护成本：改一处要同步 7 个认知入口 | 先区分真正复用部分，再评估提取到 `modules/` 由单一入口引入；**不要**简单改全局导入 | 高（涉作用域与加载顺序） |
| `packaging-detail-drawer.css` 编译 3 份 | 3 个包材组件 | 是 | 同上，但规模较小 | 同上；已确认其在 scoped 下不外溢，非紧急 | 高（同上） |
| `.dialog-actions` / `.form-dialog-footer` / `.btn-cancel` / `.btn-save` 复制块 ≈ 36 条 | 4 个 media 对话框 | —（根本没在用） | 无视觉效果，只增加维护噪音 | **删除**，转 C8 | 低（全仓零引用，可证明删除后样式不变） |
| `.form-dialog-header`（含 `h3`/`p`）重复 6 份 | 4 个 media 对话框 + `MediaAccountEditorModal` | 是（同为表单弹窗标题区） | 同素材维护 6 份，易漂移 | 收敛进已有的 `modules/media-dialog-shared.css` | 中（需视觉确认前后一致） |
| `.batch-count-pill` 重复 5 份 | 3 个批量对话框在用 | 是 | 同上 | 同上 | 中 |
| `.page-head` / `.page-header` / `.batch-bar` / `.filter-chip` 漂移 | 6 / 4 / 4 / 2 个文件 | 待核实（名称相同，场景可能有别） | 页面头部间距不统一 | 视觉专项：先确认哪些页面应一致，再决定是否统一 | 中（需前后截图） |
| 裸用 `el-table` / `el-pagination` 后局部打补丁 | 4 个页面/组件 | 否（不是重复实现） | 公共组件规范未覆盖到这些页面，靠 EP 变量微调 | 记录；是否迁移到 `LqTable` / `LqPagination` 属独立改造 | 高 |
| `:root` / `[data-theme='dark']` 令牌分层定义 | `tokens.css`、`element-plus.css` 等 | 否 | 无 | **不修改**，扫描器已排除该类误报 | 无 |

**实施顺序（按收益/风险，而非重复行数）**：① 删除未使用的复制块（低风险、无视觉影响，转 C8）→ ② `form-dialog-header` / `batch-count-pill` 收敛进已有 `media-dialog-shared.css`（中风险、收益明确）→ ③ 重复引入文件的单一入口评估（高风险，需专项验证作用域）→ ④ 漂移类统一（中风险，依赖视觉确认）。

### C7-1 已执行：删除确认无引用的复制块（`6941762`）

删除 `.dialog-actions` / `.form-dialog-footer` / `.btn-cancel` / `.btn-save` 四个类及其 `:hover`、`:disabled` 子规则，仅限 `AssignOwnerDialog`、`BatchPublishTaskDialog`、`BatchUpdateRecordDialog`、`UpdateRecordFormDialog` 四个已证明无引用的对话框。**4 文件 × 56 行 = 224 行，纯删除，0 行新增**，未改 `<template>` / `<script>` 与任何有效 CSS。

删除前复核：`git status` 确认这 4 个文件工作区干净、最近提交仍是仓库初始提交，无并行会话改动。

**无引用的证明链**（三条互证）：

1. 静态：各文件里这 4 个类的 9 处命中全部分布在 `<style>` 块内；
2. 全仓：对 `*.vue` 模板与 `*.ts`（含父组件插槽内容）检索这四个类名，**零命中**——不是"暂时没渲染"，而是没有任何元素会带上它们；
3. 运行时：在同一路由逐个打开这四个对话框，统计这四种选择器的 DOM 元素数，**均为 0**。

**删除前后对比**：同一探针逐个打开四个对话框，读取 `form-dialog-header`（含 `h3` / `p`）、`batch-count-pill`、`dialog-form-body`、`batch-summary-box`、`lq-dialog__foot` 及其按钮等 **48 个选择器的计算样式并截图，比对零差异**。

**边界确认**：保留仍在使用的 `.form-dialog-header` 与 `.batch-count-pill`（经核对 `UpdateRecordFormDialog` 模板本就未使用 `batch-count-pill`、该文件也从未定义它，不存在误删）。

> 格式说明：这 4 个文件在 HEAD 版本即未通过 prettier 检查，属既有问题，本次**未做无关重排**；用 prettier 格式化后再比对，差异恰好是被删的 56 行加一个空行，没有任何新增格式改动。测试 70 文件 / 525 用例通过。
>
> 附带发现（本次范围外，留给 C8）：`ImportUpdatePreviewModal.vue` 里的 `.form-dialog-footer` 同样定义了但模板未使用。

### C7-2 只核查：共享样式的归属与作用域（不改代码）

**问题一：组件里的重复声明真的完全相同吗？**

| 类 | 定义处 | 是否完全一致 |
|---|---|---|
| `.batch-count-pill` | 5 处（4 个对话框 + `media-account-view.css`，另有 scopod 副本在 `ImportUpdatePreviewModal`） | **是**，5 处属性逐项一致 → 真 C7-A 完全重复 |
| `.form-dialog-header` | 22 处 → 9 种写法 | **否**。多数是不同选择器（`h3` / `p` / `> div` / `> span`）而非漂移；真正的漂移只有 `padding`：`0` vs `media-account-view.css:436` 的 `14px 42px 12px 18px` |

**问题二：共享样式现在通过什么方式生效？**

`modules/media-dialog-shared.css` 由 `index.css:21` **全局导入**，且**没有任何组件 scoped 引入**（无重复编译问题）。其 15 条顶层选择器**全部带模块前缀**——`.media-form-dialog` / `.media-platform-dialog` / `.media-detail-dialog`——本身就已是"模块限定的全局样式"，符合期望的归属方式。它已承载 `.form-dialog-header`（含 `> div` / `> span` / `h3` / `p`），但未承载 `.batch-count-pill`。

**问题三：收敛后能否保持原来的作用域？**

四个对话框的根节点**都已带** `class="media-form-dialog …"`（`UpdateRecordFormDialog`、`BatchUpdateRecordDialog`、`BatchPublishTaskDialog`、`AssignOwnerDialog` 逐一确认），因此沿用该前缀收敛后仍能命中，且不会外溢到其他模块——**作用域可保持**。

但有两个必须先解决的前提，否则不能直接收敛：

1. **共享文件里的定义并不完整**：`.media-form-dialog .form-dialog-header` 只写了 `flex` / `min-width` / `padding`，**缺 `display` / `gap` / `align-items` / `justify-content`**（这些目前写在组件 scoped 里）。直接删组件副本而不同步补齐，视觉会变。
2. **`ImportUpdatePreviewModal` 是例外**：它**确实用了** `.batch-count-pill`（模板第 5 行），但根节点**没有** `media-form-dialog` 类。若按该前缀收敛，这个组件的徽标会失去样式 → 需先给它加合适的容器类，或保留其副本。

结论：**第 2 项暂不修改**。收敛方案可行且作用域安全，但要先把上述两点处理掉（补齐基属性 + 处理例外组件），届时再提改动方案。

### C7-2 详细设计方案（2026-10-23，仅设计，未修改代码）

#### 步骤一：样式归属对照表

**关键事实**：逐字节比对了 5 个标准成员的 `.form-dialog-header` 及 `h3` / `p` 声明，**完全相同**；DOM 也完全同形：`<div class="form-dialog-header"><div><h3>…</h3><p>…</p></div><span>…</span></div>`。

但第三个子元素**并非都是徽标**——只有 3 个是 `.batch-count-pill`，另 2 个是不同的 `.platform-badge`：

| 组件 | 根节点类 | 表头结构 | 第三个子元素 | `.batch-count-pill` | `.form-dialog-header` | 分类 |
|---|---|---|---|---|---|---|
| `AssignOwnerDialog` | `media-form-dialog` | 同形 | 徽标 | 定义 + 使用 | 定义 + 使用 | 标准成员 |
| `BatchPublishTaskDialog` | `media-form-dialog` | 同形 | 徽标 | 定义 + 使用 | 定义 + 使用 | 标准成员 |
| `BatchUpdateRecordDialog` | `media-form-dialog` | 同形 | 徽标 | 定义 + 使用 | 定义 + 使用 | 标准成员 |
| `UpdateRecordFormDialog` | `media-form-dialog` | 同形 | `platform-badge` | 无 | 定义 + 使用 | 标准成员（仅表头） |
| `MediaAccountEditorModal` | `media-form-dialog` | 同形 | `platform-badge` | 无 | 定义 + 使用 | 标准成员（仅表头） |
| `ImportUpdatePreviewModal` | 无 | 无表头 | 徽标放在 `#actions` 插槽 | 定义 + 使用 | 无 | **例外组件，保留独立实现** |

另有 `media-account-view.css` 也定义了这两个类，其中 `.form-dialog-header` 的 `padding` 是唯一的差异值（`14px 42px 12px 18px`），其 `h3` / `p` 与标准成员相同。

**约束校验**：media 之外**没有任何文件**在模板中使用这两个类（`src` 全量检索确认）→ 收敛不会影响其他模块。

#### 步骤二：明确迁入 `media-dialog-shared.css` 的声明

沿用该文件现有的 `.media-form-dialog` 前缀（不新增全局裸选择器）：

| 目标选择器 | 动作 | 声明内容 |
|---|---|---|
| `.media-form-dialog .batch-count-pill` | **新增** | `align-items:center; background:var(--color-primary-bg); border:1px solid var(--color-primary-border-light); border-radius:var(--radius-pill); color:var(--color-success-text); display:inline-flex; font-size:var(--fs-xs); font-weight:var(--fw-extrabold); padding:5px 10px; white-space:nowrap`（10 条，与现有 5 处定义逐字一致） |
| `.media-form-dialog .form-dialog-header` | **扩充** | 保留现有 `flex:1 1 auto; min-width:0; padding:0`，补入 `display:flex; align-items:center; gap:var(--space-control-gap-wide); justify-content:space-between` |
| `.media-form-dialog .form-dialog-header h3` | **合并** | 现有 `overflow/text-overflow/white-space`（截断）与待迁入的 `color:var(--color-text-heading); font-size:var(--fs-xl); font-weight:var(--fw-extrabold); line-height:var(--lh-tight); margin:0` **合并为同一规则块**，不出现重复选择器 |
| `.media-form-dialog .form-dialog-header p` | **合并** | 同上：截断三条 + `color:var(--color-text-muted); font-size:var(--fs-xs); font-weight:var(--fw-bold); margin:5px 0 0` |
| `> div` / `> span` | **不动** | 现有两条保持不变 |

全部使用令牌（无硬编码色值）→ 浅深双主题跟随令牌，不需要额外的深色适配。

#### 步骤三：明确哪些 scoped 声明删除、哪些必须保留

| 文件 | 删除 | 保留 |
|---|---|---|
| `AssignOwnerDialog` / `BatchPublishTaskDialog` / `BatchUpdateRecordDialog` | `.batch-count-pill`、`.form-dialog-header` 及 `h3` / `p` | 其余全部 |
| `UpdateRecordFormDialog` / `MediaAccountEditorModal` | `.form-dialog-header` 及 `h3` / `p` | 其余全部（含各自的 `platform-badge` 局部规则） |
| `ImportUpdatePreviewModal` | **不删** | `.batch-count-pill` 保留独立实现（它未使用模块前缀，且徽标位于 `#actions` 插槽，DOM 结构不同） |
| `media-dialog-shared.css` | 不改现有规则 | 全部保留，仅按步骤二新增/扩写 |
| `media-account-view.css` | **待决，见步骤四** | — |

#### 步骤四：加载顺序与优先级 —— 唯一真实风险点

**现状**：`media-dialog-shared.css` 由 `index.css:21` 全局导入（早期加载）；组件 scoped 样式在组件模块执行时注入（更晚）。同特异性（两边都是 0,2,0）时**后注入者胜**，这正是今天表头实际的着色来源。

**实测佐证**：`UpdateRecordFormDialog` 同时具备三份来源（自身 scoped `padding:0`、`@import` 来的 `padding:14px…`、全局 `.media-form-dialog …` 的 `padding:0`），**实测计算值为 `0px`** —— 说明组件自身的声明排在 `@import` 内容之后，胜出。

**收敛后的风险**：一旦删除这 5 个组件的 scoped 副本，竞争者变成「全局 shared（早、0,2,0）」对「`media-account-view.css` 经 `@import` 进入 `UpdateRecordFormDialog` 与 `MediaAccountEditorModal` 的 scoped 副本（晚、0,2,0）」→ **后者可能反超，导致这两个弹窗的表头 `padding` 由 `0` 变为 `14px 42px 12px 18px`，出现视觉回归。** 这正是不能把 `padding` 简单替换成 `0` 的原因。

两个备选（**需你定夺**，未决前不动手）：

- **方案 A（推荐）**：先作为独立前置项，核实 `media-account-view.css` 那份 `.form-dialog-header` 是否对所有引入者都无实际视觉效果。已知只有 `UpdateRecordFormDialog` 与 `MediaAccountEditorModal` 会用表头类，且两者实测均为 `0px` → 说明该块当前不产生任何视觉效果，属可被 C8 吸收的无效声明。先处理它（独立验证 + 独立提交），风险即消除，再执行收敛。
- **方案 B（保守）**：不动 `media-account-view.css`，则 `.form-dialog-header` **不迁入全局**，保留各组件 scoped 副本，本次只收敛确定无冲突的 `.batch-count-pill`。代价：表头仍有 5 份副本，收益减半。

**`.batch-count-pill` 不受此风险影响**：3 个使用它的标准成员**都不引入** `media-account-view.css`（引入者是另外 4 个组件），迁入后无同级竞争者。

#### 步骤五：浅 / 深双主题前后对比方案

扩展 C7-1 已用的探针（复用 `_audit_shots/c7-dialog-baseline.mjs` 的思路），实施前后各跑一次并逐项比对：

- **覆盖对象**：5 个标准对话框 + 例外组件 `ImportUpdatePreviewModal`（确认它不受影响）
- **双主题**：浅色 / 深色（实施游标按双主题各跑一遍）
- **读取选择器**：`.form-dialog-header`、`h3`、`p`、`.batch-count-pill`、`.platform-badge`、`.lq-dialog__head`、`.lq-dialog__body`
- **读取属性**：`display` / `gap` / `align-items` / `justify-content` / `padding` / `height` / `background-color` / `color` / `font-size` / `font-weight` / `line-height` / `border-radius` / `border-top-color`
- **验收标准**：上述属性逐项一致；每个对话框浅深各留一份截图；全量测试 525 用例通过；prettier 检查不新增问题（这几个文件 HEAD 版本本身不符合 prettier，不对其做无关重排）

#### 步骤六：最小修改实施顺序（待批准后执行）

1. **前置**：处理 `media-account-view.css` 的 14px 变体（按上述方案 A，独立核验 + 独立提交）
2. **第一批**：迁入 `.batch-count-pill` → 删除 3 个标准成员的副本 → 对比 + 测试 + 独立提交
3. **第二批**：迁入 `.form-dialog-header` 基础（含 h3 / p 合并）→ 删除 5 个标准成员的副本 → 对比 + 测试 + 独立提交
4. **例外**：`ImportUpdatePreviewModal` 全程不动，仅在实施后确认其徽标样式未变

分两批是为了让"无冲突项"先落地验证，把有优先级风险的表头收敛放在前置项处理完之后。

### C7-2 执行结果（2026-10-23，三批独立验证、独立提交）

| 批次 | 提交 | 改动 | 验证 |
|---|---|---|---|
| 第一批：清理 14px 无效声明 | `a1cca1b` | `media-account-view.css` −22 行 | 24 组合零差异 |
| 第二批：收敛 `.batch-count-pill` | `fedd8ff` | 共享文件 +14；3 个组件各 −12 | 24 组合零差异 + 来源切换 + 不外溢 |
| 第三批：收敛 `.form-dialog-header` | `1703af9` | 共享文件 +13；5 个组件各 −22 | 36 组合 × 12 字段零差异 + 来源切换 + 零残留 |

**第一批没有只用"当前计算值 0"下结论**，而是四个维度分别核验：引入方式上该文件只被 8 处 `<style scoped>` 引入、不存在全局泄漏，且只有两个组件真正渲染表头；断点上没有任何 `@media` 规则涉及该类，并在 1600/1366/1024/768 四个宽度实测；主题上浅深都实测（`--color-surface` 分别为 `#ffffff` 与 `#1e293b` 证明主题生效）；状态上覆盖编辑弹窗的新建态与编辑态两种 DOM 形态。24 个组合实测 `padding` 恒为 `0px`，删除后重跑零差异。

**第二、三批的优先级风险已按方案 A 消除**：删除组件 scoped 副本后，`media-account-view.css` 那份 `padding: 14px…` 已不存在，不会在加载顺序上反超。读取真实元素命中的规则确认：表头、h3、p、徽标现在**只**命中 `.media-form-dialog …` 系列，来源已由组件副本切换到共享文件；在页面根部插入裸 `.batch-count-pill` 探针命中为空，证明新选择器是模块限定的、不会外溢。

**C8 台账补记**：`media-account-view.css` 中 `padding: 14px 42px 12px 18px` 的表头声明已作为 C7-2 前置清理项提前执行并完成（`a1cca1b`），C8 不再重复处理。

**未改动**：`ImportUpdatePreviewModal` 保持独立实现；`platform-badge` 等局部样式保留；没有新建组件或 CSS 文件，也没有扩大共享样式的作用域。

### C7 收口

C7-1 已关闭，C7-2 已完成（三批独立提交）。C7-3（`media-account-view.css` 与 `packaging-detail-drawer.css` 的重复引入）与 C7-4（视觉漂移）保留待办，按既定顺序推进，随后进入 C8。

本轮 C7 完成了第 1 项删除（`6941762`，4 文件 224 行）并完成了第 2 项的归属与作用域核查（未改代码），未新建组件，未把业务 CSS 搬进 `utilities.css`。剩余项：第 2 项待上述两个前提处理后再动；第 3 项重复引入、第 4 项漂移等待你的下一步指示，其中包括已澄清的两点——`media-account-view.css` 重复编译次数多**不等于**必须迁移到全局 CSS；生产跟单等页面裸用 `el-table` **不自动认定为错误**，需先验证能否直接使用 `LqTable` 而不损失动态列、滚动等特殊功能。
