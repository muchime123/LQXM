# 治理执行方案（CSS 审计 + 视觉审计合并，2026-10-22）

> 合并 `css-tokens-audit-20261022.md`（编号 C*）与 `visual-interaction-audit-20261022.md`（编号 V*），
> 按 P0/P1/P2 排序，同一问题只出现一次（CSS 治理与页面整改不分两遍修改）。
> 原则：先修"渲染出错"，再修"多套并存"，最后做"数值收敛"；每批以截图对照验收。

## 工作区改动归属（先整理、再提交）

| 组 | 文件 | 建议 |
|---|---|---|
| A 公共组件治理（本轮已完成） | AGENTS.md；BatchActionBar.vue+test(删)、ListPageLayout.vue、LqConfirmDialog.vue+test、LqDialog.vue+test、SearchToolbar.vue、SearchToolbarBatchBar.vue+test、LqPagination.test.ts、LqTable.test.ts(新)、trackingDateGuard.test.ts | 第一批提交 |
| B 视觉/资产域在途 | AssetToolbar.vue、AssetGrid.vue、asset-toolbar.css、asset-grid→asset-gallery.css、modules/asset-grid.css、tokens.css、themes/dark.css、modules/list-controls.css、modules/dialog-layout.css、CategorySummaryTabs.vue、MoveAssetDialog/Modal.vue、PackagingAssetPanel.vue | 第二批提交（AssetToolbar 令牌收敛等，与 A 分开） |
| C 现金流日记账在途 | CashflowJournalController/ReviewService、CashflowReviewRules+Test(新)、api/cashflowJournal.ts、CashflowJournalView.vue、views/settlement/cashflow/(新)、docs/资金日记账*.md×3、流水日记账功能设计.md | 随现金流需求提交 |
| D 生产跟单在途 | ProductionTrackingService.java、V402__tracking_manual_node_reference.sql(新)、api/productionTracking.ts、ProductionTrackingView.vue、TrackingDetailDrawer.vue、TrackingProductBenchmarkPanel.vue、useTrackingDetail.ts、ManufacturingProcessManagementView.vue、ManufacturingTaskConfigView/Panel.vue、WorkshopSelector.vue | 随生产跟单需求提交 |
| 待归属确认 | PackagingMoldsMasterDetail.vue(101行)、DingtalkSyncPanel.vue(19行)、WebsiteBannerView.vue(分页迁移，可随 A) | 提交前逐个 diff 确认 |
| 杂项建议清理 | `=?`、db_backup_examiner_20261020.tsv、「所有数据结构结论块来自本机真库」、frontend/_build_check/ | 确认无用后删除/加 .gitignore |

正式基线：A、B 提交后重跑全量测试 + 截图脚本，作为治理前的视觉基线。

## P0 —— 渲染错误与功能缺陷（先修，量小）

| 批 | 项 | 内容 | 文件数 |
|---|---|---|---|
| P0-1 | C1+C2+C3 | dark.css 补 12 条缺失覆盖；`color: var(--color-surface)` → `--color-text-on-primary`（utilities.css:38,146,265） | 2 |
| P0-2 | C4+C5+C6 | 15 处引用不存在令牌替换；asset-toolbar success 4 个错色 fallback、`--color-accent`、`--space-s` 修正 | 8 |
| P0-3 | C7+C9 | 删 modules/image-editor-inline.css:89-191（8 个同名冲突）；SidebarAssetPanel 补 @import asset-toolbar.css | 3 |
| P0-4 | V1 | DefaultLayout 移动端（窄屏）侧栏塌陷修复 | 1 |
| 验收 | — | 全量测试 + 浅色/深色 8 页截图对照（首次深色渲染验证，坐实或推翻 C1-C3） | — |

## P1 —— 多套并存收敛（改外观，需截图回归）

| 批 | 项 | 内容 | 文件数 |
|---|---|---|---|
| P1-1 | C10+C14 | 删 utilities.css 19 个 0 引用类（约 280 行）；spin×7、highlightFlash 同名不同义、空态×5 去重 | 10 |
| P1-2 | C8 | `.btn-sm` 5 份收敛为唯一来源；packaging 裸选择器改前缀 | 5 |
| P1-3 | C11+C12 | `.dialog-actions`×5 + `.form-dialog-footer`×5 收敛到 dialog-layout.css；media-dialog-shared 补丁层并入 | 8 |
| P1-4 | C13 | 表格皮肤收敛：element-plus.css:363-382 删结构规则只留 `--el-table-*` 变量，斑马纹统一 | 2 |
| P1-5 | C15 | danger 三档令牌（消灭 #be123c 与 #dc2626 两套红）；LqDescriptions 32 处 `!important` 改造；CustomerDetailPanel 改用 LqDescriptions | 5 |
| P1-6 | C16 | DataState 加 `padding/align`、LqDialog 加 `density`、FormSection 加 `size`、分页皮肤内置 `mode` → 删 22 处反向 :deep | 10 |
| P1-7 | V2-V6 | 页面小改：产品档案裸 file input、H3 建议面板遮挡、月度提成口径长文、客户档案 select 截断、物品资产操作列换行 | 5 |
| P1-8 | V8-V11 | 令牌级统一：新增 `--control-height-md`(34px) 等，`.tb-btn`/`.lq-dialog__foot button`/`.btn`/EP `--el-component-size` 对齐；AssetToolbar 与生产跟单 `.toolbar` 不换组件 | 6 |
| 验收 | — | 每批改动前后截图对照（桌面 1440 + 390） | — |

## P2 —— 令牌收敛与规范（分批，不急）

| 批 | 项 | 内容 |
|---|---|---|
| P2-1 | C19-C23 | 同值异名合并（别名过渡）；secondary/muted 拉开（#334155）；`--ep-*` 双轨并入 `--color-*`；Deprecated 别名 44 处分批替换；删死令牌 |
| P2-2 | C24 | 命名规范：删 `--space-3/5`、颜色禁 `--text-*`、字号统一 `--font-size-*` |
| P2-3 | C25 | 硬编码 Top4（PackagingMoldsMasterDetail / packaging-detail-drawer / DeviceVideoLibraryView / MonthlyCommissionView）+ 禁 `var(--x,#hex)` |
| P2-4 | C17 | media-account-view.css(36KB×8 import)、packaging-detail-drawer.css(50KB×3 import) 拆分 |
| P2-5 | C26+C27 | index.css 层序修正、master-layout.css 入 modules；stylelint（令牌存在性、裸 .btn-sm、var 兜底） |

## 不做的事（防"为了统一而统一"）

- 不合并 asset 三件套（0 选择器交集、作用域不同）；不合并 AssetToolbar 与 SearchToolbar 组件。
- 不把生产跟单自研工具栏替换成 SearchToolbar；不动 H3/提成"一格多行"的高行内容。
- 不做全局间距/字号一刀切替换（≥1044 处硬编码按 P2-3 的 Top 文件逐个消化）。
- 现金流日记账的 accountId 报错归其在途需求交付，不并入治理批次。

## 进度（2026-10-23）

| 批 | 内容 | 状态 |
|---|---|---|
| 提交 | 批 1「列表/弹窗/分页规范收口」25 文件已提交并推送（`62293eb`，标题含 BOM 未修、已推送故不 force-push） | 完成 |
| P0-1 | dark.css 补 12 条缺失令牌 | **完成 + 实测通过**（浏览器 12/12 解析正确） |
| P0-1 | `color: var(--color-surface)` 前景色修复 | **部分**：utilities.css 3 处已改，但实测证明是死代码/被覆盖；真实缺陷另有 44 处，范围已修正进审计报告 C3，待分类裁决后执行 |
