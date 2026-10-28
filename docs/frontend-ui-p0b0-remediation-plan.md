# 前端统一 UI 体系 P0-B0 整改设计与兼容性基线

> 阶段性质：**设计阶段**。本轮只读复核 + 新增本文档，未修改任何业务组件、公共组件、CSS、数据库或生产配置，未提交、未推送。
> 上游输入：`docs/frontend-ui-p0a-audit.md`
> 生成日期：2026-10-27

---

## 0. 复核基准与复核结论摘要

### 0.1 复核基准

| 项 | 值 |
|---|---|
| 分支 / HEAD | `main` / `42bc081c271124620254a7fe79fff55d00a7e904`（与 P0-A 同一提交） |
| 工作区 | 仅新增 `docs/frontend-ui-p0a-audit.md`，无其它改动 |
| 复核方式 | 逐行重读 `LqTable.vue` / `LqPagination.vue` / `LqDialog.vue`(1-320) / `SearchToolbar.vue`(150-470) / `EmptyState.vue` / `useListPagination.ts` / `useModalForm.ts`；对调用方做标签用法扫描（`<LqTable` 全文 14 处逐一读取上下文） |
| 是否已修复 | **否**。P0-A 报告中的全部问题在当前 HEAD 上均仍然成立，无重复整改风险 |

### 0.2 复核结论：P0-A 有 4 项判断需要修正

复核要求「区分真实缺陷、组件能力不足、视觉不一致、合理业务例外」，复核后修正如下：

| P0-A 编号 | P0-A 原判 | B0 复核结论 | 修正后优先级 |
|---|---|---|---|
| **T-2** | `LqTable` 仅转发 4 个事件，其余（`expand-change`/`select`/`filter-change`）**不转发** | **推翻**。`inheritAttrs:false` + `v-bind="$attrs"`（第 5 行，位于所有显式绑定之前）会把未声明的事件与属性透传给 `el-table`。**实测反证**：`MoldImportPreviewTable.vue:70-72` 正在用 `:span-method` 与 `max-height`；`CashflowAccountTable.vue:2` 正在用 `@expand-change`；`CompanyCertificationsTab.vue:78`、`MediaAccountView.vue:107`、`OfficeAssetView.vue:88`、`CertListTable.vue:6` 正在用 `:default-sort`。能力**已具备** | 由 P1 降级为 **P2（仅补文档）** |
| **T-3** | 不支持 `span-method` / 树形 / `max-height` 等 | **推翻**，同上，均有实际调用方在跑 | **P2（仅补文档）** |
| **D-1** | `LqDialog` 关闭事件「可能收到 1 次或 2 次」 | **修正为「脆弱实现，非现存缺陷」**。推演两种消费模式：① `v-model` 模式——`requestClose` 同步 `emit('update:modelValue',false)`，`nextTick` 时 `props.modelValue` 已为 `false`，**不触发兜底**；关闭时由 `handleClose` 派发**唯一一次** `close`。② `:model-value + @close`（不回写）模式——`nextTick` 时仍为 `true`，走兜底 `emit('close')` 一次，并置 `suppressNextClose=true` 压制随后的 `handleClose`。**两种模式当前都只派发一次**。真实风险仅在于「消费者异步回写 `modelValue`」这种尚未出现的用法 | 由 P0 降级为 **P1（先补契约测试，再考虑收敛）** |
| **D-2** | `beforeClose` 三态语义易误用 | **降级**。`before-close` 全仓仅 **5 个调用点**：`composables/ui/useModalForm.ts:60`、`CustomerFormModal.vue`、`MoldImportDialog.vue`、`MoldFormDialog.vue`、`ExpenseImportDialog.vue`。爆炸半径小 | 由 P1 降级为 **P2** |

**同时确认「重复派发」在 `LqTable` 上不存在**：声明在 `defineEmits` 中的 4 个事件会被 Vue 从 `$attrs` 中移除，因此不会同时走「显式转发」与「attrs 透传」两条路。这是后续设计的**关键约束**（见 §2.1）。

### 0.3 最终优先级（复核后）

| 优先级 | 项 | 判定依据 |
|---|---|---|
| **P0-1** | `LqPagination` 独立状态接入 | **唯一被证实的「组件能力不足导致绕过」**：11 个业务文件因无法构造 `ListPagination` 而裸写 `el-pagination` |
| **P0-2** | `LqDialog` 关闭语义契约化 | 55 文件 / 75 处标签，全站最强耦合点；**本轮只建契约测试，不改行为** |
| **P0-3** | `LqDrawer` 最小公共接口设计 | 17 个文件 100% 裸写 `el-drawer`，`common/` 无抽屉组件（**本轮只设计，不实现**） |
| **P1-1** | `LqTable` 泛型化与类型契约 | 无运行时故障，纯类型收益 |
| **P1-2** | `LqPagination` 事件分离 + 移除 DOM hack | 随 P0-1 一并落地 |
| **P1-3** | `SearchToolbar` 防抖抽出 + 输入法/清空/回写契约测试锁定 | `debounce` 逻辑 3 处平行实现，但 0 处外部调用 `SearchToolbar` 的 `debounce` |
| **P1-4** | 公共组件自身硬编码治理 | `EmptyState.vue:47`、`DataState.vue:149` 等 |
| **P1-5** | ESLint/CI 规则（新增 vs 存量分离） | P0-A §9 已证实现有机制无法防重复 |
| **P2** | `LqTable` 能力矩阵文档、`LqDialog` `beforeClose` 语义、`LqPreview` prop 误用、`suppressNextClose` 重构、`DataState` 废弃 props | 见 §7 |
| **不处理** | `showFooter` 默认推导、`fieldValues` 与 `defineModel` 双数据流风格 | 行为正确，改动收益低于风险 |

---

## 1. 问题证据与最终优先级（逐项）

### 1.1 `LqTable`

| 编号 | 证据（文件:行号） | 分类 | 优先级 |
|---|---|---|---|
| T-1 | `LqTable.vue:41` `data: any[]`；`:49` `rowClassName` 入参 `any`；`:63-66` 四个事件载荷全为 `any`；`:80-89` `defineExpose` 9 个方法入参全为 `any` | **能力不足（类型）** | P1 |
| T-2′ | `LqTable.vue:5` `v-bind="$attrs"` + `:37` `inheritAttrs:false`；已声明的 4 事件被 Vue 从 `$attrs` 移除 → 无双派发。反证调用方：`:span-method`(`MoldImportPreviewTable.vue:71`)、`max-height`(`:72`)、`@expand-change`(`CashflowAccountTable.vue:2`)、`row-key`(`OperationLogView.vue:55`、`DingtalkSyncPanel.vue:92`)、`:default-sort`(`CompanyCertificationsTab.vue:78`) | **文档缺失（非缺陷）** | P2 |
| T-4 | `LqTable.vue:110-135` 8 处 `!important`（`white-space`/`overflow`/`text-overflow`/`opacity`/`visibility`）用于压制 EP 表头；与业务侧 `:deep` 叠加形成双重压制 | **视觉不一致（已有意为之）** | P2 |
| T-5 | `LqTable.vue:78-90` 暴露方法无类型 | 类型 | P2 |
| T-6（新增） | `LqTable.vue:24` 默认空态固定 `EmptyState`，未透传 `size`/`hint`；无调用方使用 `#empty` 插槽 | 能力不足（轻微） | P2 |
| T-7（新增） | `LqTable.vue:6` `v-loading="loading"` 无 `element-loading-text`，与 `DataState`/`LoadingState` 的加载表达不统一 | 视觉不一致 | P2 |

调用方用法实测（14 处 `<LqTable` 标签逐一核对）：

| 用法 | 出现处 |
|---|---|
| `row-key` | `ReimbursementLedgerPanel:91`、`DingtalkSyncPanel:92`、`CompanyCertificationsTab:74`、`OperationLogView:55`、`DepartmentManagementView:41,75`、`CashflowIssueTable:2`、`CashflowEntryTable:2`、`CashflowAccountTable:2`、`OfficeAssetView:87`、`WebsiteInquiryView:73`、`MediaAccountView:105` |
| `density="compact"` | `ReimbursementLedgerPanel:92`、`MoldImportPreviewTable:70`、`OperationLogView:55`、`CashflowIssueTable:2`、`CashflowEntryTable:2`、`CashflowAccountTable:2` |
| `:default-sort` | `CompanyCertificationsTab:78`、`CertListTable:6`、`OfficeAssetView:88`、`MediaAccountView:107` |
| `:span-method` | `MoldImportPreviewTable:71` |
| `max-height` | `MoldImportPreviewTable:72`、`CashflowIssueTable:2` |
| `:row-class-name` | `CertListTable:7`、`MoldImportPreviewTable:70`、`MediaAccountView:106` |
| `empty-text` | `MoldImportPreviewTable:72` |
| `@row-click` | `ReimbursementLedgerPanel:94`、`DingtalkSyncPanel:92`、`CertListTable:8`、`CashflowAccountTable:2`、`OfficeAssetView:90`、`WebsiteInquiryView:73` |
| `@row-dblclick` | `OfficeAssetView:91` |
| `@sort-change` | `CompanyCertificationsTab:79`、`OfficeAssetView:89`、`MediaAccountView:108` |
| `@selection-change` | `MoldImportPreviewTable:73`、`MediaAccountView:109` |
| `@expand-change` | `CashflowAccountTable:2` |
| `ref` + 实例方法 | `CompanyCertificationsTab:74`、`MediaAccountView:104` |

### 1.2 `LqPagination` + `useListPagination`

| 编号 | 证据 | 分类 | 优先级 |
|---|---|---|---|
| P-1 | `LqPagination.vue:26` `pagination: ListPagination`（必填、唯一数据来源）；`ListPageLayout.vue:130` 同样强耦合 | **能力不足（真实阻断）** | **P0** |
| P-1 影响面 | `:pagination=` 命中 **19 个文件**：15 个直接渲染 `<LqPagination>`，另 4 个（`EmployeeRosterView`、`ProductCatalogView`、`PackagingView`、`CustomerView`）把 `pagination` 传给 `ListPageLayout`。`useListPagination(` 命中 **20 个文件**（17 业务 + 1 源码 + 2 测试） | — | — |
| P-1 绕过证据 | 11 个业务文件裸写 `<el-pagination>`：`CashflowReviewDrawer:51`、`WebsiteMediaPicker:90`、`ProductionTrackingWeeklyReviewDrawer:44`、`ProductionTrackingAnalyticsDrawer:34`、`ProductInventoryView:215`、`WebsiteProductsView:110`、`WebsiteMediaView:108`、`H3SalesProductAnalysisView:119`、`H3ImportCenterView:268`、`H3LedgerPanel:800`、`H3ImportBatchPanel:118` | — | — |
| P-2 | `LqPagination.vue:45-52` 0/1 基倒换写在组件内（`get: page+1` / `set: onPageChange`） | 设计约定（需固化） | P0（随 P-1） |
| P-3 | `LqPagination.vue:54-60` `current-change` 与 `size-change` 都只 `emit('change')` | 能力不足 | P1 |
| P-4 | `LqPagination.vue:62-68` `onMounted` + `querySelector` 补 `aria-label` | 实现瑕疵 | P2 |
| P-5（新增） | `useListPagination.ts:40-41` `const responsePage = result.number ?? result.page` —— 后端返回 0 基 `number`；若有接口返回 1 基 `page`，会被当作 0 基写入 | 潜在约定冲突 | P2（观察项，不改） |

### 1.3 `LqDialog`

| 编号 | 证据 | 分类 | 优先级 |
|---|---|---|---|
| D-1′ | `LqDialog.vue:250` `suppressNextClose` + `:258-271` `requestClose` + `:264-269` `nextTick` 兜底 + `:274-280` `handleClose`。推演见 §0.2：**当前两种消费模式均只派发一次** | **脆弱实现** | **P0（仅建契约测试）→ P2（重构）** |
| D-2′ | `before-close` 仅 5 个调用点（§0.2） | 能力足够 | P2 |
| D-3 | `LqConfirmDialog.vue:194-207` 注释自认双派发风险，仅注释规避 | 脆弱实现 | P2 |
| D-5 | `LqDialog.vue:220-230` 4 档 `--dialog-width-*`；`:227-230` `width` prop 可覆盖但**未写入文档注释**（`:33` 仅一句「也可通过 width 自定义覆盖 size 默认值」，未给示例） → 22 个文件裸写 `el-dialog` 的诱因之一 | 文档缺失 | P1 |
| D-6（新增） | `LqDialog.vue:58` `append-to-body` **恒为 true**，`:47-48` `loading` 时屏蔽 ESC/遮罩，`:77` 隐藏关闭按钮 —— 行为正确；但 `append-to-body` 是业务侧 `:deep` 失效的根因（`ProductionTrackingView.vue:939` 注释自曝） | 已知约束（需写入文档） | P2 |
| D-7（新增） | `LqDialog.vue:183-184` `zIndex` prop 注释说明「嵌套业务弹窗显式层级，省略则用 EP 栈分配」；`LqConfirmDialog.vue:43` 用 `UI_LAYER.popover` —— 嵌套层级已有机制 | 能力足够 | 不处理 |

调用方式盘点（55 文件 / 75 处标签）：

| 调用方式 | 命中 | 说明 |
|---|---|---|
| `@close=` / `@cancel=` / `@confirm=` | **≥74 个文件**（含 `el-dialog` 的 `@close`，输出截断） | 多数弹窗同时监听 `confirm` 与 `close` |
| `:before-close=` | **5 处**（`useModalForm.ts`、`CustomerFormModal`、`MoldImportDialog`、`MoldFormDialog`、`ExpenseImportDialog`） | 低爆炸半径 |
| `v-model` 驱动 | 主流（`useModalForm.ts:19-23` `modal.visible`） | — |
| 嵌套 | `LqConfirmDialog` 内嵌 `LqDialog`；业务弹窗内再开弹窗见 `ProductionTrackingView`(9 处) | 依赖 `append-to-body` + `zIndex` |

### 1.4 `SearchToolbar`

| 编号 | 证据 | 分类 | 优先级 |
|---|---|---|---|
| S-1 | `SearchToolbar.vue:443-447` `getCurrentInstance().vnode.props?.onBatchEdit` 探测监听器（注释已说明非响应式） | **脆弱实现** | P2（影响仅批量按钮显隐，且现有 7 个用例已覆盖） |
| S-2 | `SearchToolbar.vue:246-257` 组件内 `debounceTimer` + `setTimeout`；`debounce` prop 默认 300ms；**0 处调用方显式传 `debounce`**。另两处平行实现：`list/useListFilters.ts`、`catalog/useCatalogFilters.ts` | 重复实现 | P1（抽 `utils/debounce.ts`，**不改组件行为**） |
| S-3′ | `SearchToolbar.vue:184-186` `searchOnInput` + `searchMode` 双通道；`:254` 优先级判定。**实测 `search-on-input` 全仓 0 处使用，`search-mode` 11 处使用** | **遗留 API 已死** | **P3（仅加 `@deprecated`）** |
| S-4 | `SearchToolbar.vue:327-428` 文本筛选「镜像 + 已提交值」双状态 + `textFieldComposing` Set | 复杂度高但行为正确 | P2（加测试锁定，不重构） |
| S-5 | `:242-243` `defineModel('keyword')`/`defineModel('viewMode')` vs `:193` `fieldValues` props + `filter-change` 事件 | 风格不统一但行为正确 | 不处理 |
| **S-6（新增，正面）** | **中文输入法已正确处理**：`:19-20` `@compositionstart`/`@compositionend` → `:288-291` `handleCompositionEnd` 重新调度；文本筛选亦有 `:368-375` `onTextCompositionStart/End` + `textFieldComposing` | **无缺陷** | 仅需补测试锁定 |
| **S-7（新增，正面）** | 清空已正确：`:271-275` `handleSearchClear` 取消待发防抖 + 清空 keyword + `emit('search','')` | **无缺陷** | 仅需补测试锁定 |

### 1.5 其它已复核项

| 项 | 证据 | 分类 | 优先级 |
|---|---|---|---|
| `LqPreview` prop 误用 | `LqPreview.vue:96` `<EmptyState description="没有可预览的内容" />`；`EmptyState.vue:18-26` props 只有 `icon/text/hint/size` → `description` 被丢弃，实际渲染默认「暂无数据」 | **真实缺陷（极小）** | P2（一行修复） |
| `EmptyState` 硬编码 | `EmptyState.vue:47`（`--lg` 档 `padding: 60px 20px`） | 视觉规范违背 | P1 |
| `DataState` 硬编码 | `DataState.vue:149` `min-height:240px`、`:157-158` `width/height:36px` | 视觉规范违背 | P1 |
| `DataState` 废弃 props | `DataState.vue:88-93` 三个 `@deprecated`（`loading`/`empty`/`error`）与 `state` 并存 | API 冗余 | P2 |
| `LqDrawer` 缺失 | `common/` 无抽屉组件；17 个文件 100% 裸写 `el-drawer` | **结构性缺口** | P0（设计）/ P1（实现） |

---

## 2. 每项整改的目标公共资产

> 原则：**以现有公共组件为唯一目标资产，不新建平行体系**。所有整改都是「增强现有组件」或「补文档/补类型」，不新增第二个 Table / Dialog / Pagination / Toolbar。

| 整改项 | 目标公共资产（唯一） | 明确禁止 |
|---|---|---|
| 表格类型与契约 | `frontend/src/components/common/LqTable.vue` | 禁止新建 `LqDataTable` / `BaseTable` |
| 分页独立状态 | `frontend/src/components/common/LqPagination.vue` + `frontend/src/composables/list/useListPagination.ts` | 禁止新建 `usePagination` / `LqPager` |
| 弹窗关闭语义 | `frontend/src/components/common/LqDialog.vue`（及内嵌的 `LqConfirmDialog.vue`） | 禁止新建 `LqModal` |
| 搜索栏 | `frontend/src/components/common/SearchToolbar.vue`（+ 内嵌 `SearchToolbarBatchBar.vue`） | 禁止新建 `LqSearchBar` / `FilterBar` |
| 抽屉（本轮仅设计） | **新增** `frontend/src/components/common/LqDrawer.vue` —— 唯一例外，因为 `common/` 当前为空缺而非重复 | 禁止同时提供两套抽屉 |
| 防抖 | **新增** `frontend/src/utils/debounce.ts`（工具层，非组件层） | 禁止在组件内再写一份 |
| 空态 | `EmptyState.vue` / `DataState.vue` | 禁止新建 |
| 设计变量 | `frontend/src/styles/tokens.css`（324 变量，唯一源） | 禁止新增第二套 token |

---

## 3. 兼容性策略

### 3.1 总原则

1. **只增不改**：新增 props/emits 必须是可选的，现有调用方零改动即可继续工作。
2. **不删除兼容机制**：`suppressNextClose`、`searchOnInput`、`DataState` 的三个 `@deprecated` props 在本轮**一律保留**，仅加 `@deprecated` 注释与文档，删除动作推到全部调用点归零后的独立批次。
3. **契约先行**：任何行为改动前，先落地覆盖「现状行为」的契约测试并跑绿；测试绿之后再改实现，改完仍须绿。
4. **语义不变**：事件名、参数顺序、默认值一律不变；新增能力走新事件名。
5. **测试二分（2026-10-27 修订）**：契约测试必须分成两类，**不得要求尚未实现的新能力在旧代码上先通过**——
   - **现状契约测试**：描述当前已存在的正确行为，**必须先在未改动的组件上跑绿**；
   - **新增能力测试**：描述未来批次才要实现的能力（如 `LqPagination` 独立状态模式），**允许先失败**，实现落地后再转为必须通过。
   
   实现约定：新增能力测试用 vitest 的 `it.fails()` 标记为「**期望失败**」——它会真实执行（不是 skip，因此始终提供缺口证据），失败时不阻断 CI；一旦后续批次把能力补齐，该用例转为通过，vitest 会报「expected to fail but passed」并提示移除 `.fails`，正好作为批次交付的交接信号。

### 3.2 `LqTable` 兼容性策略

| 方面 | 现状（须保持） | 新增（不破坏） |
|---|---|---|
| 属性透传 | `v-bind="$attrs"` 透传一切未声明属性 | **条件式规则（2026-10-27 修订，取代原先的「永久禁止声明」）**：当前这些属性走 `$attrs` 是合理且受保护的；**并不是永久禁止显式声明**。真正的约束是——若未来为 `expand-change`/`select`/`filter-change`/`row-key`/`max-height`/`span-method`/`default-sort` 显式声明 props 或 emits，**必须同步实现向内部 `el-table` 的转发，并用兼容测试证明行为与现状完全一致**（断言转发到的目标 props/事件名、单次派发、函数引用不变）。原因是 Vue 会把已声明的项从 `$attrs` 移除，漏转发即**静默失效**（`MoldImportPreviewTable:71` 的 `span-method` 与 `CashflowAccountTable:2` 的 `expand-change` 会立刻坏掉）。因此规则是「**声明即须转发 + 测试证明等价**」，而非「永不声明」——这也为未来完善 TypeScript 类型留出空间 |
| 事件 | 4 个声明事件（`selection-change`/`sort-change`/`row-click`/`row-dblclick`）单次派发 | 新增声明事件时同样适用上述条件式规则：**声明即可靠转发 + 单次派发测试证明**；未声明事件继续走 attrs 透传 |
| 泛型 | `data: any[]` | 用 `<script setup lang="ts" generic="T extends Record<string, unknown> = Record<string, unknown>">`（vue `^3.5.12` 支持 3.3+ `generic`）。`T` 默认值为宽泛型 → 现有 13 个调用方**类型检查行为不变** |
| 暴露方法 | 9 个方法，`any` 入参 | 改为 `LqTableInstance<T>` 接口，方法签名用 `T`；运行时签名不变 |
| 空态 | 默认 `EmptyState` + `empty-text`/`empty-icon` | 新增可选 `emptyHint` / `emptySize`，不传时行为同现状 |

**类型默认值的选择理由**：若把默认泛型设为 `any`，则 `data` 仍是 `any`，泛型收益为零；若设为 `Record<string, unknown>`，则现有调用方传入具体数组时会自动推导为具体类型，且未指定时不会比现在更差。因此选后者。

### 3.3 `LqPagination` 兼容性策略

**双模式并存**，`pagination` 对象模式优先级高于独立模式：

```ts
// 模式 A（现有，15 个文件在用）—— 行为完全不变
<LqPagination :pagination="pg" />

// 模式 B（新增，面向 11 个自绘分页文件）—— 仅在未传 pagination 时生效
<LqPagination
  :total="total"
  :page="page"            <!-- 0 基，与 useListPagination.pageInfo.page 同语义 -->
  :page-size="size"
  @update:page="page = $event"
  @update:page-size="size = $event"
  @change="load()"        <!-- 兼容保留，仍为唯一的「请求触发」信号 -->
/>
```

| 决策项 | 结论 | 理由 |
|---|---|---|
| 新增 props 是否必填 | 全部可选 | 保证模式 A 零改动 |
| 模式判定 | `props.pagination` 存在 → 模式 A；否则 → 模式 B | 单一判定，无歧义 |
| **0/1 基转换位置** | **仍固定在 `LqPagination` 组件内部**（`+1` 显示 / `-1` 回写），不扩散到调用方 | 现有 `LqPagination.vue:45-52` 已是唯一转换点，迁移到别处会让 15 个现有调用方全部要改 |
| 公开 `page` 的基数 | **0 基**，与 `useListPagination.pageInfo.page` 和后端 `page` 参数一致 | 与后端契约一致；若公开 1 基，则每个调用方都要 `-1`，反而制造新的 0/1 混乱 |
| 请求触发方式 | **统一由 `@change` 触发重新加载**；`update:page` / `update:page-size` 只同步状态，不触发请求 | 与现有 15 个调用方的 `@change="load()"` 习惯一致；若两个信号都触发请求会产生双请求 |
| `change` 是否分离 | **保留 `change` 不变**；新增 `page-change` / `size-change` 作为可选细分信号 | 分离后旧调用方若只听 `change` 会漏触发 |
| 筛选重置 | 不新增组件内逻辑。`useListPagination.resetPage()` 继续由调用方在筛选变更时显式调用；新增文档约定「筛选变更 → `resetPage()` → 重新加载」 | 不引入隐式副作用 |
| DOM hack | `onMounted` `querySelector` 补 `aria-label` 保留，直到有 `el-pagination` 官方 `aria` 支持 | 删除会导致可访问性回归 |

### 3.4 `LqDialog` 兼容性策略

| 决策项 | 结论 |
|---|---|
| 本轮是否改实现 | **否**。P0-B0 只设计契约测试；实现收敛放到 P2 批次 |
| 是否删除 `suppressNextClose` | **否**。按用户约束「不得直接删除 `suppressNextClose` 等兼容机制」。仅在 P2 批次中，当全部契约用例证明两条兜底路径都不可达后，才将其标记为废弃空实现并注释说明，再于独立批次删除 |
| `beforeClose` 三态 | **语义不变**（`false` 阻止 / `Promise<false>` 阻止 / `true` 立即关 / `void` 由钩子持 `done`）。仅补文档与用例 |
| `append-to-body` | 保持恒 `true`（现有嵌套与 z-index 机制依赖它）；在组件头注释中明确写出「业务侧 `:deep` 无法命中弹窗内容，样式须走 `styles/modules/dialog-layout.css` 或弹窗内部非 scoped 样式」 |
| `width` prop | 保持可覆盖；在 `:22-33` 的尺寸文档块中补一行「需要 520/620/680 等中间尺寸时直接用 `width="620px"`，不要绕过组件裸写 `el-dialog`」 |
| 嵌套弹窗 | 保持 `zIndex` + `UI_LAYER` 机制；契约测试覆盖「子弹窗关闭不触发父弹窗关闭」 |
| 未保存提醒 | 现有 `dirty` + `before-close` + `useModalForm.handleBeforeClose`（`:60-66`）已完整，本轮只补测试 |

### 3.5 `SearchToolbar` 兼容性策略

| 决策项 | 结论 |
|---|---|
| 是否新建组件 | **否** |
| `searchOnInput` vs `searchMode` | 两者**同时保留**，`searchMode` 为文档推荐；给 `searchOnInput` 加 `@deprecated`。删除动作等 P0-A 记录的全部调用点（当前 0 处）确认归零后再做 |
| 防抖数值 | `debounce` 默认 300ms 不变；抽出 `utils/debounce.ts` 后组件内部改调用该 util，行为不变 |
| 输入法行为 | 保持现有 `compositionstart/end` 逻辑，只补测试锁定 |
| 批量监听器探测 | 保持 `vnode.props` 探测；P2 批次再加显式 `showBatchEdit`/`showBatchRemove` props（自动探测仍为默认，保证现有调用方不变） |
| `v-model:keyword` 外部回写 | 保持：外部改 `keyword` 不自动触发 `search`（避免双向触发循环），仅同步输入框显示 |

### 3.6 `LqDrawer` 最小公共接口（本轮仅设计）

接口必须与 `LqDialog` 保持一致，不得另起一套命名：

```ts
// 目标形态（本轮不实现）
<LqDrawer
  v-model="visible"
  title="" subtitle=""            // 与 LqDialog 同名
  size="sm|md|lg|xl|full"         // 复用 --drawer-width-* 新令牌；不复用 --dialog-width-*
  mode="detail|form|workspace"    // 与 LqDialog 同枚举语义
  :loading :dirty :show-close
  :close-on-click-modal :show-footer
  :before-close                   // 与 LqDialog 同三态语义
  @close @cancel @confirm         // 与 LqDialog 同名同语义
>
  <template #title #actions #footer />   // 与 LqDialog 同插槽名
</LqDrawer>
```

- 关闭语义**复用 `LqDialog` 的 `requestClose` 模型**（含 `suppressNextClose` 兼容机制），保证「关闭只派发一次」在弹窗与抽屉上行为一致。
- `append-to-body` 恒 `true`，与 `LqDialog` 一致，并在头注释写明 `:deep` 限制。
- 令牌：新增 `--drawer-width-sm/md/lg/xl` 到 `styles/tokens.css`（**不复用 `--dialog-width-*`，避免抽屉宽度被弹窗改动连带影响**）。
- 与 `LqDialog` 的公共三段式样式抽到 `styles/modules/dialog-layout.css` 的共享选择器（`.lq-overlay__head/__body/__foot`），**分两个批次**：先建 `LqDrawer` 自持样式，验证后再抽共享，避免一次性改两个组件。

---

## 4. 风险与影响调用方

| 整改项 | 影响调用方（实测） | 主要风险 | 缓解 |
|---|---|---|---|
| `LqTable` 泛型化 | 13 个文件 / 14 处标签 | ① 误为透传属性声明 props/emits 会导致 `span-method`、`expand-change`、`max-height` **静默失效**；② `generic` 属性与 `vue-tsc` 版本（`^2.2.12`）兼容性 | ① 契约测试断言透传属性到达 `el-table`；② 先在 `LqTable.vue` 单文件试编译，`npm run typecheck` 全绿再推广 |
| `LqTable` 类型 | 同上 | 现有 13 个调用方传入 `any[]` 时不报错，收紧后可能出现新类型错误 | 默认泛型设为宽类型；typecheck 必须零新增错误 |
| `LqPagination` 独立模式 | 现有 15 个文件（模式 A）+ 未来 11 个文件（模式 B） | ① 0/1 基混淆导致跳页错误；② `@change` 与 `update:page` 双触发产生双请求 | ① 组件内单点转换 + 契约测试断言 `page=0` 渲染第 1 页、`update:page` 返回 0 基；② 文档与测试明确「只有 `@change` 触发请求」 |
| `LqPagination` 迁移 11 个文件 | `CashflowReviewDrawer`、`WebsiteMediaPicker`、`ProductionTrackingWeeklyReviewDrawer:44`、`ProductionTrackingAnalyticsDrawer:34`、`ProductInventoryView:215`、`WebsiteProductsView:110`、`WebsiteMediaView:108`、`H3SalesProductAnalysisView:119`、`H3ImportCenterView:268`、`H3LedgerPanel:800`、`H3ImportBatchPanel:118` | 抽屉内下钻分页（如 `drilldownPage`）语义可能与列表分页不同，强行统一会改行为 | 逐文件判定；抽屉内下钻分页允许保留独立状态，只统一**渲染**为 `LqPagination` 模式 B，不统一状态来源 |
| `LqDialog` 契约测试 | 55 文件 / 75 处标签 | 测试若误判现状行为，会把「现状」固化成错误契约 | 契约测试必须由 P0-A + B0 的推演结论反推，每条用例注明「依据 `LqDialog.vue` 第 X 行」 |
| `LqDialog` 行为收敛（P2） | 同上 | 全站最强耦合点，任何改动可能导致弹窗关不掉 | 契约测试 10 条全绿后才允许动 `LqDialog.vue:246-280`；`suppressNextClose` 保留至最后 |
| `SearchToolbar` 防抖抽出 | 14 个文件 | 抽出 util 时若改变 `clearTimeout` 时机，会导致输入中文时漏触发 | 只替换 `setTimeout` 调用，不动调度时机；补输入法用例 |
| `SearchToolbar` deprecated `searchOnInput` | 0 处使用 | 无 | 无风险 |
| 公共组件硬编码治理 | `EmptyState`（8 文件 / 17 点，含 `LqTable`/`DataState`/`LqPreview` 内部调用）、`DataState`（15 文件 / 33 点） | 改 padding / min-height 会改变列表页空态高度，视觉回归 | 逐组件改造 + 暗色/亮色双主题人工比对 |
| `LqDrawer`（P1 实现） | 17 个裸 `el-drawer` 文件；先试点 3 个 | `append-to-body` + `:deep` 失效导致样式丢失（已知：`ProductionTrackingView.vue:939`） | 试点只选 `TrackingDetailDrawer` / `H3LedgerDetailDrawer` / `ProductionTrackingWeeklyReviewDrawer`；改造前后截图比对 |
| ESLint 规则 | 存量 38（table）+ 22（dialog）+ 17（drawer）+ 11（pagination）文件 | 一次开启会让 CI 全红 | 新增代码严格、存量用基线文件豁免（见 §6 B10） |

---

## 5. 测试用例与验收标准

> 约定：本轮（P0-B0）**不落地任何测试文件**；下表是 P0-B1 必须交付的测试文件清单与用例设计。测试框架：vitest `^4.1.9` + `@vue/test-utils` `^2.4.11`；现有 `components/common/__tests__/` 已有 14 个测试文件（LqDialog 32 用例、LqTable 2 用例、LqPagination 4 用例、SearchToolbar 7 用例等）。

### 5.1 兼容性基线（B1 开工前置条件）

| 基线项 | 取值 | 校验方式 |
|---|---|---|
| 现有单测 | 68 个 `*.test.ts` 全绿 | `npm test` |
| 现有公共组件测试 | `common/__tests__/` 14 个文件全绿 | `npm test` |
| 类型检查 | 零错误 | `npm run typecheck` |
| 构建 | 成功 | `npm run build` |
| 行为基线 | **先写契约测试 → 在「未改动的组件」上跑 → 必须全绿**。绿了才证明测试描述的是现状，之后改实现仍需绿 | 关键顺序约束 |

> 这条「先绿再改」是本次兼容性基线的核心机制：**契约测试必须先能描述现状，才有权约束未来**。
>
> **适用范围澄清（2026-10-27）**：「先绿再改」只约束**现状契约测试**。描述未来能力的新增能力测试按 §3.1-5 的二分约定处理，允许先失败（以 `it.fails()` 标记），不在此约束内。

### 5.2 `LqTable`（新增 `frontend/src/components/common/__tests__/LqTable.attrs.test.ts`）

| # | 用例 | 断言 | 依据 |
|---|---|---|---|
| T-01 | 未声明属性透传 | 传 `row-key="id"` / `max-height="560"` / `:span-method="fn"` / `:default-sort="{...}"` → 内部 `el-table` 的 props 收到同名值 | `LqTable.vue:5` + 实测调用方 |
| T-02 | 未声明事件透传且只触发一次 | 传 `@expand-change` → 内部 `el-table` emit `expand-change` → 父组件回调被调用 **1 次** | `CashflowAccountTable.vue:2` |
| T-03 | 声明事件只派发一次（无双派发） | 内部 `el-table` emit `selection-change` → 父组件回调 **1 次**；同理 `sort-change`/`row-click`/`row-dblclick` | `LqTable.vue:11-14` + Vue `$attrs` 语义 |
| T-04 | 空态默认 | 不传 `empty-text` → 渲染 `EmptyState` 且文案「暂无数据」 | `LqTable.vue:22-26` |
| T-05 | 空态自定义 | 传 `empty-text="没有可显示的行"` → 文案生效 | `MoldImportPreviewTable.vue:72` |
| T-06 | `#empty` 插槽覆盖 | 提供 `#empty` → 不再渲染默认 `EmptyState` | `LqTable.vue:22-26` |
| T-07 | 密度 class | `density="compact"` → 根元素含 `lq-table-panel--compact` | `LqTable.vue:2` |
| T-08 | 实例方法委派 | 调用 `clearSelection()`/`toggleAllSelection()`/`doLayout()` → 内部 `el-table` 同名方法被调用 | `LqTable.vue:78-90` + `CompanyCertificationsTab.vue:74` |
| T-09 | `loading` | `loading=true` → `v-loading` 生效（内部 `el-table` 收到 loading） | `LqTable.vue:6` |
| T-10 | 泛型类型（编译期） | `<LqTable :data="Foo[]" @row-click="(r) => r.id">` 类型检查通过且 `r` 推导为 `Foo` | B2 目标 |

### 5.3 `LqDialog`（新增 `frontend/src/components/common/__tests__/LqDialog.close.contract.test.ts`）

| # | 场景 | 断言 | 依据 |
|---|---|---|---|
| D-01 | `v-model` + 点关闭按钮 | `close` **1 次**，`update:modelValue(false)` 1 次 | `LqDialog.vue:81` → `:258-280` |
| D-02 | `v-model` + ESC | `close` **1 次** | `:48` `close-on-press-escape` |
| D-03 | `v-model` + 点击遮罩（`close-on-click-modal=true`） | `close` **1 次** | `:47` |
| D-04 | `v-model` + 取消按钮 | `cancel` **1 次** + `close` **1 次** | `:282-285` |
| D-05 | `:model-value` + `@close`（父组件不回写） | `close` **1 次**（走 `:264-269` 兜底），随后 `suppressNextClose` 压制第二次 | `:262-269` |
| D-06 | `before-close` 返回 `false` | 不派发 `close`、不派发 `cancel`，`modelValue` 保持 `true` | `:305` |
| D-07 | `before-close` 返回 `Promise.resolve(false)` | 同 D-06 | `:306-313` |
| D-08 | `before-close` 持 `done` 异步放行 | 调用 `done()` 后关闭，`close` 1 次 | `:298-302` |
| D-09 | `loading=true` | 点关闭按钮/ESC/遮罩 → **均不关闭**，`close` 0 次 | `:47-48`、`:77` |
| D-10 | 确认按钮 | `confirm` 1 次，**不自动关闭**（由业务自行置 `v-model=false`） | `:287-289` |
| D-11 | 嵌套 | 父 `LqDialog` 打开，子 `LqDialog`（或 `LqConfirmDialog`）关闭 → 父 `close` 0 次 | `:58` `append-to-body` + `:183-184` `zIndex` |
| D-12 | `width` 覆盖 `size` | `size="md" width="620px"` → 渲染宽度为 `620px` | `:227-230` |
| D-13 | `mode="detail"` 默认无 footer | 不渲染 `.lq-dialog__foot` | `:232-235` |

### 5.4 `LqPagination`（新增 `frontend/src/components/common/__tests__/LqPagination.independent.test.ts`）

| # | 用例 | 断言 |
|---|---|---|
| P-01 | 模式 A 不受影响 | 传 `pagination` → 行为与改造前完全一致（`page=0` 显示第 1 页），`@change` 触发 |
| P-02 | 模式 B 渲染 | 传 `total=100 / page=0 / page-size=20` → 显示第 1 页，共 5 页 |
| P-03 | 模式 B 0/1 基 | 点第 3 页 → `update:page` 收到 **2**（0 基） |
| P-04 | 模式 B 改页大小 | 改 `page-size` 为 50 → `update:page-size`=50，且 `update:page` 收到 **0**（重置到首页） |
| P-05 | 请求触发唯一性 | 切页 → `change` **1 次**；`update:page` 不额外触发请求（以监听器计数断言） |
| P-06 | 模式判定 | 同时传 `pagination` 与 `page` → 以 `pagination` 为准（模式 A 优先） |
| P-07 | `hide-on-single-page` / `disabled` / `mode="compact"` 两种模式下行为一致 | — |

### 5.5 `useListPagination`（扩展已有 `frontend/src/composables/list/__tests__/useListPagination.test.ts`）

| # | 用例 | 断言 |
|---|---|---|
| U-01 | `updateFromResponse` 0 基 | `{number:2,...}` → `pageInfo.page===2` |
| U-02 | `onPageChange(3)` | `pageInfo.page===2` |
| U-03 | `onPageSizeChange(50)` | `size===50` 且 `page===0` |
| U-04 | `indexMethod` | `page=1,size=20` → `indexMethod(0)===21` |
| U-05 | `resetPage` | `page===0`，`size` 不变 |
| U-06 | `totalPages` 回退 | `pages=0` 时由 `ceil(total/size)` 计算 |

### 5.6 `SearchToolbar`（新增 `frontend/src/components/common/__tests__/SearchToolbar.debounce.test.ts`）

| # | 用例 | 断言 | 依据 |
|---|---|---|---|
| S-01 | 防抖合并 | 300ms 内连续输入 3 次 → `search` 只发 **1 次**，值为最后一次 trim 结果 | `:253-258` |
| S-02 | 中文组词不触发 | `compositionstart` → 输入 → 未到 `compositionend` → `search` **0 次** | `:19-20`、`:255` |
| S-03 | 组词结束补触发 | `compositionend` → `search` **1 次** | `:288-291` |
| S-04 | 回车立即搜索 | `keyup.enter` → 取消待发防抖 + 立即 `search` 1 次 | `:265-269` |
| S-05 | 清空 | 点清空 → 取消待发防抖 + `search('')` 1 次 | `:271-275` |
| S-06 | 外部回写 keyword | 父组件改 `v-model:keyword` → **不触发** `search` | `:242` |
| S-07 | `search-mode="submit"` | 输入不触发，回车/查询按钮才触发 | `:254` |
| S-08 | `searchOnInput` 遗留通道 | `search-on-input=false` 且未设 `search-mode` → 输入不触发（保留兼容） | `:184-186`、`:254` |

### 5.7 验收标准（批次级）

| 批次 | 验收标准 |
|---|---|
| B1 | 新增 4 个测试文件全部**在未改动组件上通过**；`npm test` 全量绿（68+4）；`npm run typecheck` 零错误 |
| B2 | `LqTable.vue` 通过 `generic`；13 个调用方 typecheck 零新增错误；T-01~T-09 仍绿 |
| B3 | `LqPagination.vue` 双模式；P-01~P-07 绿；15 个现有调用方零改动且行为不变（人工抽查 `OperationLogView`、`CashflowJournalView`、`MediaAccountView` 三页翻页） |
| B4 | `useListPagination.ts` U-01~U-06 绿 |
| B5/B6 | 见 §6 |
| B8 试点 | `OfficeAssetView` 全量功能人工回归（列表/排序/批量/弹窗/分页/空态/筛选） |
| B10 | ESLint 规则在新增代码上生效；存量违规进入基线文件不阻断 CI |

---

## 6. 分批实施顺序（精确到文件）

> 本轮 P0-B0 只交付本文档。以下 B1 及之后为**待批准**的实施计划。

#### ⚠️ 阶段编号对齐（2026-10-27 修订，以本节为准）

本文档 §6 原始编号（B1~B10）已被业务方确认的**新阶段顺序取代**。以下映射**覆盖**后文的旧编号：

| 新编号 | 任务 | 验收重点 | 对应本文旧编号 |
|---|---|---|---|
| **B1** | 核心组件契约测试 | **现有行为测试通过**（新增能力测试允许先失败） | 旧 B1 |
| **B2** | `LqPagination` 解耦（独立状态模式） | 兼容原对象模式，支持独立状态 | 旧 B3 |
| **B3** | `LqTable` 类型与接口完善 | **不破坏事件及属性透传** | 旧 B2（已按 §3.2 修订为条件式规则） |
| **B4** | `LqDialog`、`SearchToolbar` 稳定性完善 | 保持交互行为一致 | 旧 B6（`SearchToolbar`） + `LqDialog` 收敛（旧 B4/P1） |
| **B5** | `LqDrawer` 和公共视觉规范 | 统一抽屉、样式变量 | 旧 B7 + `LqDrawer` 新建（旧 P0 项） |
| **B6** | 业务页面试点改造 | 搜索、列表、分页、弹窗协作正常 | 旧 B8（`OfficeAssetView` 试点） |
| **B7** | 全站分批推广和防回退规则 | 逐步减少重复实现 | 旧 B5（11 个自绘分页回归） + 旧 B10（ESLint/CI） |

> 顺序调整要点：**分页先于表格**（分页是真正阻碍业务页面复用的瓶颈，且影响面 11 个文件）；**`LqDialog` 收敛后移到 B4**——若 B1 契约测试证明其当前行为稳定，可继续保留 `suppressNextClose` 等兼容机制，不必立即重构。

### B0 — 设计（本轮，已完成）
- 新增：`docs/frontend-ui-p0b0-remediation-plan.md`
- 无代码改动

### B1 — 契约测试基线（只新增测试文件，不改任何组件）
| 顺序 | 文件 | 动作 |
|---|---|---|
| 1 | `frontend/src/components/common/__tests__/LqTable.attrs.test.ts` | 新增（T-01~T-09） |
| 2 | `frontend/src/components/common/__tests__/LqDialog.close.contract.test.ts` | 新增（D-01~D-13） |
| 3 | `frontend/src/components/common/__tests__/LqPagination.independent.test.ts` | 新增（P-01~P-07；模式 B 用例在 B3 前为 `it.skip` 或先以模式 A 替代断言） |
| 4 | `frontend/src/components/common/__tests__/SearchToolbar.debounce.test.ts` | 新增（S-01~S-08） |
| 5 | `frontend/src/composables/list/__tests__/useListPagination.test.ts` | 扩展（U-01~U-06） |

风险：**低**（纯新增）。验收：`npm test` 全绿；若某条用例在未改动组件上失败，说明用例描述错了现状，须修正用例而非组件。

### B2 — `LqTable` 类型契约
| 文件 | 改动 |
|---|---|
| `frontend/src/components/common/LqTable.vue` | `<script setup>` 加 `generic="T extends Record<string, unknown> = Record<string, unknown>"`；`data: T[]`；`rowClassName` 与 4 个事件载荷改 `T`；导出 `LqTableInstance<T>`；`defineExpose` 方法签名用 `T`；新增可选 `emptyHint` / `emptySize`；**头注释补「能力矩阵」**（明确列已验证透传：`row-key`/`max-height`/`span-method`/`default-sort`/`border`/`stripe` 与任意 `el-table` 事件；明确不得为这些声明 props/emits） |
| 不改 | template 与 `<style>` 段（本批次不动样式） |

依赖：B1。风险：中（类型）。影响：13 个调用方（typecheck 验证）。

### B3 — `LqPagination` 独立状态
| 文件 | 改动 |
|---|---|
| `frontend/src/components/common/LqPagination.vue` | 新增可选 props `page`/`page-size`/`total`；新增 emits `update:page`/`update:page-size`/`page-change`/`size-change`；`change` 保留且语义不变；模式判定 `props.pagination ? A : B`；0/1 基转换仍集中在组件内；补头注释说明「`@change` 是唯一请求触发信号」 |

依赖：B1、B2。风险：中。影响：15 个现有调用方（零改动）+ 未来 11 个。

### B4 — `useListPagination` 文档与测试
| 文件 | 改动 |
|---|---|
| `frontend/src/composables/list/useListPagination.ts` | 仅补 JSDoc（0 基约定、`updateFromResponse` 读取 `number` 优先、筛选变更须显式 `resetPage()`）；**不改逻辑** |

风险：低。

### B5 — 11 个自绘分页回归（分批，每批独立验收）
| 批次 | 文件 |
|---|---|
| B5-1（低风险，3 个） | `frontend/src/components/h3/H3ImportBatchPanel.vue:118`、`frontend/src/apps/archive/views/marketing/WebsiteProductsView.vue:110`、`frontend/src/apps/archive/views/marketing/WebsiteMediaView.vue:108` |
| B5-2（中风险，4 个） | `frontend/src/apps/archive/views/settlement/cashflow/CashflowReviewDrawer.vue:51`、`frontend/src/components/website/WebsiteMediaPicker.vue:90`、`frontend/src/apps/archive/views/governance/H3SalesProductAnalysisView.vue:119`、`frontend/src/apps/archive/views/governance/H3ImportCenterView.vue:268` |
| B5-3（高风险，4 个，需单独排期） | `frontend/src/apps/archive/views/production/ProductionTrackingWeeklyReviewDrawer.vue:44`、`frontend/src/apps/archive/views/production/ProductionTrackingAnalyticsDrawer.vue:34`、`frontend/src/apps/archive/views/production/ProductInventoryView.vue:215`、`frontend/src/components/h3/H3LedgerPanel.vue:800` |

风险：B5-3 涉及抽屉内下钻分页与 1900+ 行文件，中高。

### B6 — `SearchToolbar` 防抖抽出
| 文件 | 改动 |
|---|---|
| `frontend/src/utils/debounce.ts` | **新增**（纯函数，含 `cancel`） |
| `frontend/src/components/common/SearchToolbar.vue` | 内部 `setTimeout` 改为调用 `debounce`；`searchOnInput` 加 `@deprecated` 注释；头注释补「`search-mode` 为唯一推荐 API」 |
| 不改 | 调度时机、默认值 300ms、事件名 |

风险：低-中。影响：14 个调用方。
> `useListFilters.ts` / `useCatalogFilters.ts` 改调同一 util 归入 **P2**，本批次不动（避免同时改 3 处）。

### B7 — 公共组件硬编码治理
| 顺序 | 文件 | 改动 |
|---|---|---|
| 1 | `frontend/src/components/common/EmptyState.vue` | `:47` `padding: 60px 20px` → `--space-*` 令牌（需先确认 tokens 中有等价档位，否则先补令牌再改） |
| 2 | `frontend/src/components/common/DataState.vue` | `:149` `min-height:240px`、`:157-158` `36px` → 令牌 |
| 3 | `frontend/src/components/common/SearchToolbar.vue` | 组件内写死的 `min-height`/`padding`/`gap`/`width/height`（`:591`、`:599-601`、`:634`、`:642` 等）→ 令牌 |
| 4 | `frontend/src/components/common/LqPreview.vue` | `:96` `description=` → 改为 `text=`（一行修复，真实缺陷） |

风险：中（视觉）。影响：EmptyState 8 文件 / 17 点、DataState 15 文件 / 33 点、SearchToolbar 14 文件。须亮/暗双主题人工比对。

### B8 — 试点页面
**选定：`frontend/src/apps/archive/views/product/OfficeAssetView.vue`**

选择理由（实测）：该页同时使用了 **15 个公共组件**，是全站最集中的一页 —— `LqTable`(`:85`)、`LqPagination`、`ListPageLayout`、`SearchToolbar`(`:33`)、`ActiveFilterChips`(`:95`)、`LqDialog`(4 处)、`FieldBatchEditModal`、`MetricCard`(`:10`)、`MetricGrid`、`DataState`、`StatusTag`、`LqDescriptions`、`LqDatePicker`(`:171,177`)、`LqTimeline`(`:195`)、`UnitField`(`:250,259,268`)。它能一次性验证 B2/B3/B6/B7 的全部改动，且 `LqTimeline`/`UnitField` 是「仅此页使用」的准孤儿组件，可顺带验证准孤儿判定决策。
规模 36.89 KB，可接受。

试点动作：
1. 升级到泛型 `LqTable`（验证 B2）
2. 分页切到模式 B 或确认模式 A 无回归（验证 B3）
3. 校验搜索栏防抖/输入法/清空（验证 B6）
4. 全量人工回归：列表加载、排序、批量选择、批量编辑、4 个弹窗、分页、空态、筛选 chips

验收：功能零回归 + `npm run typecheck` / `npm test` 绿。
> 备选（更小、更低风险）：`frontend/src/apps/archive/views/system/OperationLogView.vue`（7.72 KB，覆盖 `SearchToolbar`+`LqTable`+`LqPagination`+`ListPageLayout`），可作为 B8 之前的冒烟试点。

### B9 — `LqDrawer`（先设计后实现）
| 顺序 | 文件 | 动作 |
|---|---|---|
| 1 | `frontend/src/styles/tokens.css` | 新增 `--drawer-width-sm/md/lg/xl` |
| 2 | `frontend/src/components/common/LqDrawer.vue` | 新增，接口与 `LqDialog` 对齐（§3.6），自持样式 |
| 3 | `frontend/src/components/common/__tests__/LqDrawer.test.ts` | 新增，至少覆盖 §5.3 的 D-01~D-11 同名场景 |
| 4 | 试点 3 个 | `frontend/src/components/production/TrackingDetailDrawer.vue:3`、`frontend/src/components/h3/H3LedgerDetailDrawer.vue:2`、`frontend/src/apps/archive/views/production/ProductionTrackingWeeklyReviewDrawer.vue:2` |
| 5 | （后续批次） | 剩余 14 个文件；样式共享抽到 `styles/modules/dialog-layout.css` |

风险：中（`append-to-body` 导致 `:deep` 失效）。

### B10 — ESLint / CI 规则
| 文件 | 改动 |
|---|---|
| `frontend/eslint.config.js` | 新增 `vue/no-restricted-component-names`，对 `src/apps/**/*.vue` 与 `src/components/<非 common>/**/*.vue` 限制 `el-table`/`el-dialog`/`el-drawer`/`el-pagination`；`message` 指向对应公共组件 |
| `frontend/.eslint-baseline.json`（新） | 存量违规清单（38+22+17+11），规则对该清单内文件降为 warn |
| `frontend/package.json` | `verify:file-behavior` 增加 `npm run lint`（或在 CI 增加独立 step） |
| `.github/workflows/unified-file-behavior.yml` | 新增/并入 lint step |

规则设计要点：
- **新增代码严格、存量豁免**：以基线文件区分，基线只减不增（CI 校验基线文件行数不得增加）。
- 豁免目录：`components/common/`（`LqTable.vue`/`LqDialog.vue`/`LqPagination.vue` 内的 `el-*` 是合法实现）、`__tests__/`。
- Element Plus 全局注册（`main.ts:2,27`）使 import 类规则无效，故必须用模板层组件名规则。
- 设计 token 硬编码规则：先只开 `--fs-*` / `--radius-*` / `--z-*` 三类为 error；`margin/padding` 因语义别名复杂先设为 warn；`box-shadow` 待 `--shadow-*` 令牌补齐后再开。

### B11 — P2 长尾（本轮仅设计）
`LqDialog` 关闭语义重构（`suppressNextClose` 转为废弃空实现）、`DataState` 废弃 props 移除、`LqTable` 8 处 `!important` 降权、`LqPagination` DOM hack 移除、`SearchToolbar` 批量监听器探测改显式 props、`useListFilters`/`useCatalogFilters` 接入 `utils/debounce.ts`、`StatusTag` 与 `useStatus.certStatusTagClass` 双轨统一。

---

## 7. 不纳入本轮的合理例外及原因

### 7.1 原生 Element Plus 使用（不一律认定违规）

| 例外 | 证据 | 不纳入原因 |
|---|---|---|
| `common/` 内部的 `el-table` / `el-dialog` / `el-pagination` / `el-drawer` | `LqTable.vue:3`、`LqDialog.vue:44`、`LqPagination.vue:2` | 这是公共组件的实现本身，**合法且必需** |
| `<el-table-column>` 放在 `LqTable` 插槽内 | 49 个文件 | **合规用法**，P0-A 已明确区分，不计违规 |
| 抽屉内下钻/局部表格（1~2 处小表） | 如 `CoverageBreakdownDrawer`、`ProductOrderHistoryDrawer` | 场景局部、无排序/多选/密度需求；强行套 `LqTable` 收益低于风险 |
| `DeviceVideoLibraryView.vue:41,74` 原生 `<input>`/`<select>` | 两行筛选 | 该页视觉自成一体（47 处硬编码色 + 47 处 `font-size`），整体改造需独立设计与视觉基线，**不在本轮** |
| `H3LedgerPanel.vue`（1900+ 行 / 21 处裸 table / 241 个列标签）、`MonthlyCommissionView.vue`（98 KB / 10 处裸 table + 5 处裸 dialog） | — | 超大文件，改造需独立排期与视觉快照基线；本轮只登记进 ESLint 基线 |
| `MediaAccountView` 自定义域批量弹窗 | AGENTS.md 已明确「不迁移（选中 vs 全部筛选范围语义不同）」 | **既有决策，遵守** |

### 7.2 硬编码 CSS（分类，不一律治理）

| 类别 | 判定 | 处理 |
|---|---|---|
| 公共组件自身硬编码（`EmptyState.vue:47`、`DataState.vue:149,157-158`、`SearchToolbar.vue:591,599-601,634,642`） | **应治理** | B7 |
| 业务页大面积硬编码（Top 10：色 107/63/61/52/47/37/36…，字号 47/42/34/31/29/27…） | 应治理但**不在本轮** | 待 B8 试点验证令牌替换流程无回归后，再按 Top 10 → Top 55 推进 |
| hairline 边框 `1px`、零值 `0`、负值、`calc()` 内微调 | **合理例外** | 不治理 |
| SVG `viewBox`/`width`/`height`、`stroke-width` | **合理例外**（矢量坐标系，非视觉令牌） | 不治理 |
| 第三方组件内部对齐补偿（如 `LqTable.vue:127` `gap: 4px` 用于 EP 排序图标） | **合理例外** | 不治理，但需注释说明 |
| EP 组件尺寸约束（如 `el-tag size="small"` 的固定高度） | **合理例外** | 不治理 |
| `LqTable.vue:110-135` 8 处 `!important` | **已稳定且刻意为之**（压制 EP 表头默认） | 本轮不动；B11 评估降权方案 |
| 业务 `:deep` 覆盖（196 处 / 49 文件） | 多数为局部合理适配 | 本轮只登记；仅对「覆盖公共组件」（如 `PackagingMoldsMasterDetail.vue:1584` `:deep(.data-state)`）单独评估 |

### 7.3 其它不纳入项

| 项 | 原因 |
|---|---|
| 38 个裸 `<el-table>` 文件整体迁移 | 用户明确约束「不直接修改全部原生表格」；本轮只建门禁 + 试点 |
| 22 个裸 `<el-dialog>` 文件迁移 | 同上；仅补 `LqDialog` 的 `width` 文档降低诱因 |
| `LqConfirmDialog`（组件）与 `useConfirmDialog`（组合式，34 处引用）双轨合并 | 语义不同（声明式 vs 命令式），且合并会触碰 34 处调用点；仅记录 |
| 16 个准孤儿组件下沉/提升 | 需逐组件业务判定；本轮仅记录，B8 试点顺带验证 `LqTimeline`/`UnitField` |
| `ListPageLayout` 与 `master/*`+`PageHeader` 双骨架合并 | 场景不同，先定边界不合并 |
| 暗色主题补全（115/324） | 需人工双主题比对，独立批次 |
| 19 个 `.vue` 组件内 `@import` 业务 CSS 回归 `index.css` | 纯整洁性，风险/收益比低 |
| 官网 `corporate-website/src/assets/styles/tokens.css` 分叉 | 受「不将官网独立内容模型改造混入本次内部后台 UI 治理」约束 |
| 日期格式化 4 处同名导出、5 处组件私写 | 与本次四大组件主线无关 |
| `useRequest`/`useAsync` 缺失（P0-A 记录全仓 0 处） | 属新增能力，不是复用整改；且覆盖面过大 |
| 权限统一层（`usePermission`）缺失 | 同上，属新增能力，本轮不引入 |

---

## 8. 交付与后续

- 本轮交付：`docs/frontend-ui-p0b0-remediation-plan.md`（仅新增，1 个文件）。
- 未修改：任何业务组件、公共组件、CSS、数据库、生产配置。
- 未提交、未推送；未执行 `git clean`/`reset`/恢复操作；其它并行工作区文件（如根目录 `生产跟单设计梳理.md`）保持原样未触碰。
- **等待下一阶段实施指令**：建议从 **B1（契约测试基线）** 开始，B1 是全后续批次的兼容性基线前提。

## 附录：本轮复核用到的关键证据索引

| 结论 | 证据 |
|---|---|
| `LqTable` attrs 透传生效（推翻「不转发事件」） | `frontend/src/components/common/LqTable.vue:5,37,11-14` |
| `span-method` / `max-height` 实际在用 | `frontend/src/components/mold/MoldImportPreviewTable.vue:70-72` |
| `@expand-change` 实际在用 | `frontend/src/apps/archive/views/settlement/cashflow/CashflowAccountTable.vue:2` |
| `search-on-input` 0 处使用 | 全仓搜索 0 命中 |
| `search-mode` 11 处使用 | `OperationLogView`/`EmployeeRosterView`/`ProductCatalogView`/`PackagingView`/`PackagingMoldView`/`CertificateView`/`MediaAccountView`/`CustomerView`/`ReimbursementLedgerPanel`/`CompanyCertificationsTab`/`PackagingMoldsPanel` |
| 中文输入法已正确处理 | `SearchToolbar.vue:19-20,255,288-291,368-375` |
| `before-close` 仅 5 个调用点 | `useModalForm.ts:60`；`CustomerFormModal.vue`/`MoldImportDialog.vue`/`MoldFormDialog.vue`/`ExpenseImportDialog.vue` |
| `LqDialog` 关闭双路径（当前单次派发） | `LqDialog.vue:250,258-271,274-280` |
| `append-to-body` 恒 true（`:deep` 失效根因） | `LqDialog.vue:58`；`ProductionTrackingView.vue:939` |
| `LqPagination` 强耦合 + 0/1 基位置 | `LqPagination.vue:26,45-52`；`useListPagination.ts:26-81` |
| `:pagination=` 19 个文件 / `useListPagination(` 20 个文件 | 全仓搜索 |
| vue 支持 `generic`（3.5.12 ≥ 3.3） | `frontend/package.json:30`；vitest `^4.1.9`(`:54`)、@vue/test-utils `^2.4.11`(`:44`)、vue-tsc `^2.2.12`(`:55`) |
| `LqPreview` prop 误用 | `LqPreview.vue:96` vs `EmptyState.vue:18-26` |
