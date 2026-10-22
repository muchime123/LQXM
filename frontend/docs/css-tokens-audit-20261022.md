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
