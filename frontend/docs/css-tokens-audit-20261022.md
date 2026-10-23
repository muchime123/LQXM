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
| `--app-header-height` | 1 | 58px | 布局常量，建议定义为语义令牌而非散落硬编码 |
| `--el-dialog-width` | 1 | `var(--dialog-width-workspace)` | 直接写 `--dialog-width-workspace` |

> 标注「同值」的 12 个变量（合计约 25 处）替换后**零视觉变化**，可作为 C4-B 第一批；`--space-s`/`--space-m`/`--radius-card` 因取值分散，须逐处按场景映射，不做批量正则替换。
