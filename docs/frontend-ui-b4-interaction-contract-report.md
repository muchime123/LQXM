# B4：`LqDialog` 与 `SearchToolbar` 公共交互契约收口

> 目标：以**稳定现有公共组件**为优先，不以增加代码或重构代码作为完成标准。
> 已通过契约测试的行为默认视为需要保护的公共接口；只有确认存在真实缺陷、明显重复逻辑或影响统一接入的能力缺口时才做最小修改。
> 上游：`docs/frontend-ui-p0a-audit.md`、`docs/frontend-ui-p0b0-remediation-plan.md`、`docs/frontend-ui-b1-test-baseline-report.md`、`docs/frontend-ui-b2-pagination-decoupling-report.md`、`docs/frontend-ui-b3-table-implementation-report.md`
> 生成日期：2026-10-28

---

## 1. 审计基准

| 项 | 值 |
|---|---|
| HEAD | `42bc081c271124620254a7fe79fff55d00a7e904`（未前进；B1~B4 均未提交推送，全部以当前实际工作区为准） |
| B3 结束时基线 | 75 文件 / **634 passed / 0 failed / 0 expected fail / 0 skipped** |
| 工具 | vitest `^4.1.9` + @vue/test-utils `^2.4.11` + happy-dom；`vue-tsc ^2.2.12`；eslint `^9.39.4`；vite `^5` |

### 1.1 组件调用面（实测）

| 组件 | 含自身文件数 | 标签数 | 生产调用方 |
|---|---:|---:|---|
| `LqDialog` | 56 | 75 | **55 个文件 / 74 处** |
| `LqConfirmDialog` | 2 | 3 | **仅 `App.vue`**（全局确认注册表，3 处） |
| `SearchToolbar` | 15 | 15 | **14 个文件 / 14 处** |
| `SearchToolbarBatchBar` | 由 `SearchToolbar` 内部使用 | — | 无外部直接调用 |
| 仍裸用 `el-dialog` | **24 个文件**（不含 `LqDialog.vue` 自身） | — | 其中 **20 个**自绘 `#footer` / `#header` |
| 仍裸用 `el-dialog__footer` 样式覆盖 | **1 个**（`components/media/ImportUpdatePreviewModal.vue:537`） | — | — |

### 1.2 并行工作区文件（未触碰）

`git status` 显示大量其它并行任务改动，本轮**只读 `git status` 清单，未读取修改、未清理、未 stash、未 checkout 覆盖**：

- 后端 23 个文件：`ManufacturingTaskConfig*`、`ProductionTracking*`（Controller/Service/Entity/Repository）、`WebsiteProduct*`、`WebsiteMedia*` 及相关测试
- 前端：`api/productionTracking.ts`、`api/website/*`、`navigation.ts`、`routes.ts`、`WebsiteProductsView.vue`、`ManufacturingTaskConfigView.vue`、`ProductionTrackingView.vue`、`ManufacturingWorkshopPanel.vue`、`WebsiteMediaPicker.vue`、`useTrackingFilters.ts`；新增 `ProductionProcessConfigView.vue`、`WebsiteProductVariantsDialog.vue`、`TrackingReviewHistory.vue`
- 其它：`jdk21.tar.gz`、`tools/`（Python 脚本与 `classes/`）、11 个 `docs/website-*.json`、4 个中文设计文档

### 1.3 补验 B3 遗留的完整构建（前置检查项）

B3 因环境限制未能跑通完整构建（编译成功 3040 模块，但清空 `dist/` 被批量删除保护拦截）。本轮**已补齐**：

```
npx vite build --outDir %TEMP%\b4build --emptyOutDir
✓ built in 1m 48s      产物 531 个文件（assets / map / index.html / 2 个 logo）
```

采用**独立临时输出目录**：目录本不存在 → 无需删除任何已有产物 → 未触发环境删除保护，也未覆盖仓库内 `dist/`。产物留在系统临时目录，未污染仓库。**这是真实的完整构建成功，不是以模块转换成功冒充。**

---

## 2. `LqDialog` 当前契约与实际缺陷

### 2.1 契约复核结果（26 条关闭契约 **全部保持通过**）

| 分组 | 条数 | 复核结论 |
|---|---:|---|
| 关闭通道转发（`closeOnPressEscape` / `closeOnClickModal` / `destroyOnClose` / `appendToBody`） | 7 | 全部通过 |
| 关闭事件唯一性（`v-model` 与 `:model-value + @close` 两种模式） | 7 | 全部通过 |
| `beforeClose` 语义（同步 false/true、回调式 void、Promise resolve/reject、拦截时 cancel） | 7 | 全部通过 |
| 嵌套与生命周期（子弹窗不影响父、分别指定 zIndex、关闭未完成时卸载不抛错） | 3 | 全部通过 |
| 与真实 Element Plus 集成（Teleport、三段式结构） | 2 | 全部通过 |

**逐项复核（任务 A 的 6 个重点）**：

1. **两种消费模式兼容性** —— 兼容。`v-model` 模式下 `close` 由 `el-dialog` 的 `@close` 唯一派发；`:model-value + @close` 模式（父级不回写）由 `nextTick` 兜底派发一次 `close`，并由 `suppressNextClose` 压制随后 `@close` 的重复派发。**两种模式下 `close` 均恰好一次。**
2. **`close` / `cancel` / `confirm` 语义** —— 明确且一致：`close` = 已关闭的唯一出口；`cancel` = 关闭**被允许后**才派发的业务语义；`confirm` = 仅派发意图、**不自动关闭**。
3. **`beforeClose` 同步 / 异步 / 回调** —— 三通道齐备，且都经 `completeOnce` 保证 `done` 只执行一次：`false` 阻止；`true` 立即关；`void` 由钩子持 `done`；`Promise<false>` 阻止、`Promise<true>` 允许、`Promise.reject` 视为不关闭且不抛出（异常内部吞掉，与 Element Plus 约定一致）。
4. **`loading` 对关闭入口的封锁** —— 完整。`closeOnPressEscape=false`、`closeOnClickModal` 被 `&& !loading` 强制为 `false`、关闭按钮 `v-if="showClose && !loading"` 不渲染。**三个入口全部封锁。**
5. **嵌套 / Teleport / `append-to-body`** —— 见下方 §2.2 的重要更正。
6. **业务页面是否重复实现弹窗头尾与关闭逻辑** —— **存在重复**：24 个文件仍裸用 `el-dialog`，其中 20 个自绘 `#footer` / `#header`。但这属于**业务页面未接入**，不是 `LqDialog` 自身缺陷，按 B4 边界不在本阶段改业务页面（记录，归 B6/B7）。

### 2.2 重要更正：`append-to-body` 并未导致业务 `:deep` 失效

**P0-A 与 B1 曾推断**：`append-to-body` 把内容 Teleport 到 `body`，会让业务侧 `:deep` 样式失效。

**B4 实测结论：该推断不成立。** 新增 `LqDialog.teleport.contract.test.ts`（真实 Element Plus，3 条用例全部通过）验证：

- 业务写法形如 `.media-detail-dialog :deep(.lq-dialog__head)`（`components/media/MediaAccountDetailModal.vue:126-141`），编译后是**后代选择器** `.media-detail-dialog[data-v-x] .lq-dialog__head`；
- `class` 经 attrs 落到 `el-dialog` 根节点，而该节点是 `LqDialog` 的子组件根元素，**会继承父组件的 scope id**；`.lq-dialog__head` 是其后代，因此**照常命中**；
- Teleport 只改变节点在 DOM 树中的位置，**不改变这层祖先后代关系**；
- 实测同时确认内容确实不在挂载容器内（挂载容器内查不到 `.lq-dialog__head`，`body` 下能查到）。

全仓只有 **1 个**业务文件依赖这种穿透写法（`MediaAccountDetailModal.vue`），且它是**后代选择器**形态 → **无失效页面，无需要记录的可复现失效证据**。

> 处理方式：按 B4 指令「不要在本阶段全局调整 Teleport 行为」——**不改 `append-to-body`**。它同时是嵌套弹窗不被父级 `overflow/z-index` 裁剪的保障。

### 2.3 实际缺陷

**无生产缺陷。** 26 条契约全部通过，复核未发现新的行为错误。

**边界观察（记录，不修改）**：

| # | 观察 | 判定 |
|---|---|---|
| 1 | `elBeforeClose` 吞掉 `Promise.reject` | 与 Element Plus `before-close` 约定一致，且已由契约锁定。**不改** |
| 2 | `LqConfirmDialog` 用 `#footer` 插槽覆盖页脚，其取消按钮不经过 `LqDialog.requestClose` | 不构成缺陷：`LqConfirmDialog` 未暴露 `beforeClose`，不存在「绕过拦截」。**不改** |
| 3 | 24 个文件裸用 `el-dialog`，20 个自绘页脚 | 属业务未接入，非组件缺陷。归 B6/B7 |
| 4 | `LqConfirmDialog` 仅 `App.vue` 使用 | 说明业务侧大多直接裸写 `el-dialog` 或自绘确认框，是接入缺口而非缺陷 |

---

## 3. `SearchToolbar` 当前契约与实际缺陷

### 3.1 契约复核结果（13 条防抖契约 **全部保持通过**；另有 `SearchToolbar.test.ts` 7 条全通过）

| 分组 | 条数 | 复核结论 |
|---|---:|---|
| 防抖（默认 300ms、自定义 `debounce`、连续输入合并并 `trim`） | 3 | 全部通过 |
| 提交入口（回车立即查询并取消已排队、输入框清空以空串提交、「清除筛选」只派发 `clear`、submit 模式） | 4 | 全部通过 |
| 中文输入法（组词期间不提交、`compositionend` 后补提交；文本筛选同理） | 2 | 全部通过 |
| 生命周期与状态同步（卸载清理定时器、外部回写 `keyword` 不触发 `search`、外部回写 `fieldValues` 镜像跟随且不反向派发、切批量模式不产生意外搜索） | 4 | 全部通过 |

**逐项复核（任务 B 的 8 个重点）**：

1. **防抖 / 回车 / 清空** —— 明确：防抖默认 300ms；回车 `cancelPendingSearch()` 后立即提交且已排队任务被取消；输入框清空按钮以 `''` 提交一次；「清除筛选」按钮**只**派发 `clear`、**不**派发 `search`。
2. **中文输入法** —— 搜索框与文本筛选框都处理 `compositionstart/end`，组词期间只更新镜像不提交，`compositionend` 后补提交。
3. **文本筛选与外部 `fieldValues` 同步** —— 采用「本地镜像 + 防抖提交 + 提交值去重」；`watch` 外部 `fieldValues`（`deep`）让镜像跟随，且**不会反向派发** `filter-change`（避免回写抖屏）。
4. **事件顺序** —— 关键词与筛选是**两条独立通道**，各自防抖、各自提交，互不取消对方；同一通道内新输入会取消上一次排队。
5. **批量模式切换与批量操作** —— 批量态**替换**普通工具栏（不是新增一行），避免推动下方列表；`batch-edit` / `batch-remove` 通过 `vnode.props` 探测监听器，父级未监听时不渲染内置按钮（避免点击静默无响应）。
6. **内部是否存在重复防抖或职责重叠** —— **无**。关键词一条定时器，文本筛选按字段各一条，共用同一个 `props.debounce`，统一在 `onBeforeUnmount` 清理。
7. **`searchOnInput` 与 `searchMode` 的使用情况** —— 见 §3.2。
8. **14 个调用文件是否存在特殊依赖** —— 见 §3.3。

### 3.2 `searchOnInput` 与 `searchMode`（实测）

| API | 显式调用方 | 结论 |
|---|---|---|
| `searchMode` | **11 个**（9 个 `'debounce'`、2 个 `'submit'`） | 活跃 API，新调用方应显式传 |
| `searchOnInput` | **0 个**（`searchOnInput` 与 `search-on-input` 均无命中） | 已加 `@deprecated` 文档标记 |

> **关键约束**：`searchOnInput` 虽无显式调用方，但**仍是 `searchMode` 缺省时的兜底默认值**（默认 `true`）。14 个调用方中有 **3 个**未传 `search-mode`（`WebsiteInquiryView`、`OfficeAssetView`、`CashflowJournalView`），它们依赖这个默认值。因此**保留实现、仅作文档标记，不删除**。

### 3.3 14 个调用方的特殊依赖（实测）

| 依赖 | 使用方 |
|---|---|
| `:clear-visible` 外部强制控制清除按钮显隐 | `MediaAccountView.vue:26` |
| `:view-mode` + `:view-mode-options` + `:view-toggle-visible` | `ProductCatalogView.vue:21-23`（`@update:view-mode` 走 `defineModel`，正常） |
| `query-button`（submit 模式） | `OperationLogView.vue:16`、`ReimbursementLedgerPanel.vue:51` |
| `:batch-enabled="false"` 显式关闭批量 | `CashflowJournalView`、`EmployeeRosterView`、`OperationLogView`、`ReimbursementLedgerPanel` |
| `:batch-entry-visible="false"`（表格选择列常驻） | `MediaAccountView`、`WebsiteInquiryView`、`CertificateView`、`OfficeAssetView`、`CompanyCertificationsTab` |
| `:debounce` 自定义 | `PackagingMoldsPanel`（200）、`PackagingMoldView`（300） |

**发现的无效绑定（业务页面问题，不在 B4 修改）**：

`PackagingMoldView.vue:43` 写了 `v-model:batch-mode="batchMode"`，但 `SearchToolbar` 的 `batchMode` 是**单向 prop**，组件未声明 `update:batchMode` emit。该 `v-model` **静默无效**——`onUpdate:batchMode` 落进 `$attrs` 且永不被调用。该页实际靠 `@toggle-batch-mode="toggleBatchMode"`（第 19 行）正常工作，所以**功能未坏，但绑定是误导性的死代码**。属业务页面改动 → 按 B4 边界**不修改**，记录为 B6/B7 整改项（改为 `:batch-mode` 或为组件补 `defineModel('batchMode')`）。

### 3.4 实际缺陷

**无生产缺陷。** 13 + 7 条契约全部通过。

**边界观察（记录，不修改）**：

| # | 观察 | 判定 |
|---|---|---|
| 1 | `handleClear` 只调用 `cancelPendingSearch()`，未调用 `cancelPendingTextFilters()` | 实测不构成缺陷：「清除筛选」后业务侧重置 `fieldValues` → `watch` 把 `textCommitted` 归零 → 待发的文本定时器到点时因「值未变」不再派发 `filter-change`。**不改**，仅记录 |
| 2 | 本地正在编辑（镜像有值、未提交）时，外部回写为「已提交值」不会覆盖镜像 | 有意设计：保留用户正在输入的内容。**不改** |
| 3 | `PackagingMoldView.vue:43` 的 `v-model:batch-mode` 静默无效 | 业务页面问题，归 B6/B7 |
| 4 | 14 个调用方中仅 `OfficeAssetView` 存在自有 `setTimeout`，且与搜索防抖无关 | 无可复用的实际需求 |

---

## 4. 保留不改的功能及理由

| 项 | 决定 | 理由 |
|---|---|---|
| `suppressNextClose` | **保留，不重构** | 它是 `:model-value + @close` 消费模式的兼容机制。26 条契约证明两种模式下 `close` 均恰好一次、无双派发。**不因代码复杂就认定存在缺陷** —— B4 明确要求不主动重构 |
| `append-to-body` | **保留，不改** | B4 实测证伪了「导致 `:deep` 失效」的推断；同时它是嵌套弹窗不被父级裁剪的保障。全局调整 Teleport 会波及 55 个调用方 |
| `beforeClose` 吞 `Promise.reject` | **保留** | 与 Element Plus 约定一致，且已由契约锁定 |
| `LqConfirmDialog` 的 `#footer` 覆盖 | **保留** | 未暴露 `beforeClose`，不存在绕过拦截的问题 |
| `searchOnInput` | **保留实现，加 `@deprecated`** | 3 个调用方依赖其默认值兜底；B4 要求「优先添加 deprecated 标记，不在本阶段删除」 |
| 抽取 `debounce` 工具函数 | **不抽取** | 全仓无可复用的实际需求（14 个调用方中仅 1 个有无关 `setTimeout`）。B4 要求「没有必要时可以不抽取」，抽取反而会引入定时器调度/取消/卸载行为变化的风险 |
| `LqTable` / `LqPagination` 生产实现 | **不动** | B4 边界明确禁止 |
| 业务页面 | **不动** | B4 边界明确禁止 |
| 公共主题与全局样式 | **不动** | 无证据表明本阶段必须修改 |

---

## 5. 实际修改文件及必要性

| 文件 | 改动 | 必要性 | 风险 |
|---|---|---|---|
| `frontend/src/components/common/SearchToolbar.vue` | **+7 行，纯 JSDoc**：为 `searchOnInput` 加 `@deprecated` 并注明「仍是 `searchMode` 缺省时的兜底默认值」 | B4 指令 §四-7 明确要求「对确认无使用者的旧 API，优先添加 `@deprecated` 文档标记」。实测确认 0 个显式调用方 | **零**：注释变更，不改任何运行时行为 |
| `frontend/src/components/common/__tests__/LqDialog.teleport.contract.test.ts` | **新增**，3 条真实 Element Plus 用例 | 任务 A-5 要求确认 `append-to-body` 的实际作用并给出可复现证据。这是证伪 / 证实原推断的唯一可靠手段 | **零**：只新增，未改动任何既有断言 |
| `docs/frontend-ui-dialog-search-usage.md` | **新增** | 任务 C：弹窗与搜索栏统一接入规范 | — |
| `docs/frontend-ui-b4-interaction-contract-report.md` | **新增** | 任务九交付物 | — |

**未修改**：`LqDialog.vue`、`LqConfirmDialog.vue`、`SearchToolbarBatchBar.vue`、`useModalForm.ts`、`LqTable.vue`、`LqPagination.vue`、任何业务页面、任何样式、后端与数据库。

> **本阶段结论可概括为：零行为性生产代码修改，规范与契约确认完成。**（唯一的生产文件改动是 7 行注释，不影响运行时行为。）

---

## 6. 全量测试与门禁

| 命令 | 结果 |
|---|---|
| `npx vitest run`（全量） | **75 文件全部通过；637 passed / 0 failed / 0 skipped / 0 expected fail**；耗时 26.19s |
| `npm run typecheck`（`vue-tsc --noEmit --skipLibCheck`） | **0 错误** |
| `npm run lint`（`eslint src/ --quiet`） | **0 error** |
| `npx vite build --outDir %TEMP%\b4build --emptyOutDir` | **✓ built in 1m 48s**，531 个产物，完整构建成功（补齐 B3 遗留项） |

### 6.1 逐项对照 B4 测试要求

| 要求 | 结果 |
|---|---|
| `LqDialog` 26 条关闭契约全部保留 | ✅ `LqDialog.close.contract.test.ts` **26 passed**，未删改任何一条 |
| `SearchToolbar` 13 条现状契约全部保留 | ✅ `SearchToolbar.debounce.test.ts` **13 passed**；另有 `SearchToolbar.test.ts` **7 passed** |
| B2 分页、B3 表格现状测试不得回退 | ✅ `LqPagination.contract.test.ts` 28 passed、`LqTable.attrs.test.ts` 28 passed、`LqTable.test.ts` 6 passed |
| 全量 Vitest / Typecheck / Lint | ✅ 见上表 |
| 适用的完整构建检查 | ✅ 已用独立临时输出目录跑通完整构建 |
| 必须区分代码问题与环境限制 | ✅ 构建失败在 B3 属**环境删除保护**，本轮用独立输出目录规避后**真实成功**；本报告未以模块转换成功冒充构建成功 |
| 不得删减原有断言以制造通过结果 | ✅ 零删减；新增 3 条，既有断言一条未动 |

### 6.2 数量变化

B3 结束 634 passed（75 文件）→ B4 **637 passed（75 文件）**，净 **+3**，全部来自新增的 Teleport 契约用例。失败 / 跳过 / 期望失败均为 **0**。

### 6.3 存量噪声（非本轮引入）

全量测试输出中 `ListPageLayout.test.ts` 的 `[Vue warn]: Failed to resolve component: el-button` / `el-icon` 为**存量现象**，相关测试仍通过。

---

## 7. 弹窗、搜索栏统一接入规范

已交付独立文档 **`docs/frontend-ui-dialog-search-usage.md`**，要点：

**弹窗**
- 表单 / 详情 / 确认弹窗默认用 `LqDialog`；轻量确认用 `LqConfirmDialog`。
- 允许原生 `el-dialog` 的例外：抽屉形态、非模态浮层、结构远超三段式的工作台、第三方组件自带弹窗、已在稳定运行且改造收益为负（B6/B7 按页评估）。
- 关闭语义：`close` 是唯一出口且恰好一次；`cancel` 只在关闭被允许后派发；`confirm` 不自动关闭；`loading` 封锁三个入口。**业务不要自绘「关闭前确认」弹层**。
- 嵌套显式传 `:z-index`；`append-to-body` 是受保护现状，业务 `:deep` 后代选择器照常命中。
- 不创建第二套弹窗组件；不复制头尾结构，用 `#title` / `#actions` / `#footer` 插槽。

**搜索栏**
- 标准接口：`v-model:keyword`、`search-mode`、`fields` / `field-values`、`@search` / `@filter-change` / `@clear` / `@toggle-batch-mode`。
- 新调用方**一律显式传** `search-mode`；`search-on-input` 已 `@deprecated`（保留实现）。
- **搜索状态归业务页面所有**，`SearchToolbar` 只做输入与事件翻译，不持有结果、不发起请求；文本筛选的本地镜像只是防抖手段，不是状态所有权。
- 输入法由组件统一处理，业务不要重复拦截。
- 批量态切换走 `@toggle-batch-mode`；**不要写 `v-model:batch-mode`**（单向 prop，会静默无效）。
- 职责边界表 + 「props → 插槽 → 公共样式 → 最后才考虑复制」的优先级。

---

## 8. B5（`LqDrawer` 与公共视觉规范）前置建议

1. **先抽抽屉的真实形态再定组件**。当前侧滑场景散落在 `el-drawer` 与业务自绘面板中，B5 开工前应先完成一次只读盘点（文件清单 + 尺寸 / 方向 / 是否模态 / 是否有页脚），避免 `LqDrawer` 一上来就做成第二个 `el-dialog`。
2. **`LqDrawer` 应复用 `LqDialog` 的已验证契约**：`loading` 封锁、`beforeClose` 三通道（同步 / 回调 / Promise）、关闭事件单次派发。建议把这几条直接作为 `LqDrawer` 的验收用例，**不要重新设计一套关闭语义**。
3. **`append-to-body` 的取舍留给 B5 一并决策**。B4 已证伪「导致 `:deep` 失效」，所以不再有「必须改」的理由；但 `LqDialog` / `LqDrawer` 是否统一为「可选 Teleport」，应在 B5 统一视觉规范时定一次，避免两组件行为分叉。
4. **视觉规范先落令牌再落组件**。`--dialog-width-*` / `--space-*` / `--radius-*` 已在使用，B5 应补齐抽屉尺寸令牌与 `--drawer-width-*`，并明确「哪些样式属于组件、哪些属于页面」。
5. **`LqConfirmDialog` 的接入推广建议并入 B5/B6**。它目前只有 `App.vue` 使用，说明大量业务仍自绘确认框；建议与抽屉推广一起排期，而不是单独开批次。
6. **B5 不要顺手重构 `LqDialog`**。`suppressNextClose` 与 `append-to-body` 均已确认稳定，改动会波及 55 个调用方；如确有理由，应先补契约测试再动。

---

## 9. 遗留问题及风险

| # | 项 | 性质 | 处理 |
|---|---|---|---|
| 1 | 24 个文件仍裸用 `el-dialog`，其中 20 个自绘 `#footer` / `#header` | 接入缺口（非组件缺陷） | 归 B6/B7 分批迁移；本阶段按边界不改业务页面 |
| 2 | `PackagingMoldView.vue:43` 的 `v-model:batch-mode` 静默无效 | 业务页面死绑定 | 记录；建议 B6/B7 改为 `:batch-mode` 或为组件补 `defineModel('batchMode')`（后者属能力新增，需另立批次） |
| 3 | `LqConfirmDialog` 仅 `App.vue` 使用 | 接入缺口 | 建议随 B5/B6 推广 |
| 4 | `handleClear` 未取消文本筛选待发定时器 | 边界观察，实测不构成缺陷 | 记录；若将来出现「清除后又被旧值重新过滤」的复现，再最小修复 |
| 5 | `searchOnInput` 无显式调用方但仍是默认兜底 | 技术债（低） | 已 `@deprecated`；删除需先让 3 个未传 `search-mode` 的调用方显式化 |
| 6 | 全量测试中 `ListPageLayout.test.ts` 的 `el-button` / `el-icon` 未解析警告 | 存量噪声 | 非本轮引入，测试仍通过 |
| 7 | B3 之前 `npm run build` 无法直接跑通（清空 `dist/` 被环境删除保护拦截） | **环境限制** | 本轮已用独立临时输出目录跑通完整构建；建议后续把 CI 的构建目录固定为干净路径，或接受「本地需手动清理 `dist/`」 |
| 8 | 本轮未做人工视觉走查 | 范围限制 | B4 为契约复核阶段；视觉规范归 B5，页面改造的人工回归归 B6 |

---

## 10. 变更边界核对

- 未修改 `LqTable` / `LqPagination` 生产实现 —— ✅
- 未修改业务页面 —— ✅
- 未修改后端、数据库、Flyway —— ✅
- 未触碰生产跟单、财务流水、官网等并行功能 —— ✅
- 未修改公共主题与全局样式 —— ✅
- 未自动提交、未推送 —— ✅
- 未执行 `git clean` / `reset` / `stash` —— ✅
- 保护全部并行工作区文件（仅只读 `git status` 清单）—— ✅

**完成后停止，未进入 B5。**
