# B2：`LqPagination` 分页器解耦与统一接入能力建设

> 目标：让同一个 `LqPagination` 支持**两种状态接入方式**，共用一套视觉样式与用户操作行为。
> **不替换**现有分页模式、**不批量迁移** 11 个裸 `el-pagination` 文件、**不改动** `LqTable` / `LqDialog` / `SearchToolbar`。
> 上游：`docs/frontend-ui-p0b0-remediation-plan.md`（§3.3）、`docs/frontend-ui-b1-test-baseline-report.md`（§6）
> 生成日期：2026-10-27

---

## 1. 执行基准与改动范围

### 1.1 基准

| 项 | 值 |
|---|---|
| HEAD | `42bc081c271124620254a7fe79fff55d00a7e904`（未前进；B1/B2 均未提交） |
| B1 结束时基线 | 74 文件 / **612 passed / 7 expected fail / 1 skipped** |
| 测试框架 | vitest `^4.1.9` + @vue/test-utils `^2.4.11` + happy-dom `^20.10.6`（配置内联于 `frontend/vite.config.js`） |

### 1.2 本轮改动的文件

| 文件 | 类型 | 改动 |
|---|---|---|
| `frontend/src/components/common/LqPagination.vue` | **唯一的生产文件** | 双模式实现：新增可选 props `total`/`page`/`pageSize` 与 emits `update:page`/`update:pageSize`/`page-change`/`size-change`；`pagination` 由必填改为可选；`:total` 由直读 `pagination.pageInfo.total` 改为计算属性；补头注释说明两种模式与统一规则 |
| `frontend/src/components/common/__tests__/LqPagination.contract.test.ts` | 测试 | 5 条 `it.fails` **移除 `.fails` 转为必须通过**；修正 emit 名为 camelCase `update:pageSize`；新增 8 条统一规则用例 + 1 条真实 Element Plus 集成用例 |

**未改动：** 15 个现有调用方、`list-controls.css`（分页样式）、`useListPagination.ts`、`LqTable`/`LqDialog`/`SearchToolbar`、任何业务页面、任何配置。

### 1.3 工作区中的并行任务改动（非本轮产生，未触碰）

`git status` 显示以下文件已被其它并行工作区修改/新增，本轮**未读取修改、未清理、未 stash、未 checkout 覆盖**；全量测试是在包含这些改动的状态下跑的（74 文件全绿）：

`api/productionTracking.ts`、`api/website/media.ts`、`api/website/products.ts`、`apps/archive/navigation.ts`、`apps/archive/routes.ts`、`apps/archive/views/marketing/WebsiteProductsView.vue`、`apps/archive/views/production/ManufacturingTaskConfigView.vue`、`apps/archive/views/production/ProductionTrackingView.vue`、`components/website/WebsiteMediaPicker.vue`、`composables/tracking/useTrackingFilters.ts`、新增 `apps/archive/views/production/ProductionProcessConfigView.vue`。

---

## 2. 接口设计

### 2.1 两种模式

```vue
<!-- 模式 A · 分页对象模式（现有 15 个调用方，行为与改造前完全一致） -->
<LqPagination :pagination="pagination" @change="load()" />

<!-- 模式 B · 独立状态模式（B2 新增，面向拥有独立分页状态的页面） -->
<LqPagination
  v-model:page="page"          <!-- 0 基，与后端 page 参数同语义 -->
  v-model:page-size="size"
  :total="total"
  @change="load()"
/>
```

### 2.2 Props

| 名称 | 类型 | 默认 | 说明 |
|---|---|---|---|
| `pagination` | `ListPagination` | `undefined` | **模式 A**。传入即进入模式 A，优先于下列独立 props |
| `total` | `number` | `0` | **模式 B**。总条数 |
| `page` | `number` | `0` | **模式 B**。当前页码，**0 基** |
| `pageSize` | `number` | `20` | **模式 B**。每页条数 |
| `mode` | `'full' \| 'compact'` | `'full'` | 不变 |
| `pageSizes` | `number[]` | `[20, 50, 100]` | 不变 |
| `pagerCount` | `number` | `7` | 不变 |
| `disabled` | `boolean` | `false` | 不变 |
| `hideOnSinglePage` | `boolean` | `false` | 不变 |

> `pageSize` 默认值取 `20`，与 `useListPagination` 的默认 `size` 一致，避免两种模式默认值分叉。

### 2.3 Emits

| 事件 | 载荷 | 模式 | 说明 |
|---|---|---|---|
| `change` | — | A + B | **唯一的「业务数据重新加载」信号**，两种模式语义一致，只在 `current-change` / `size-change` 上派发 |
| `update:page` | `number`（0 基） | B | 页码同步。`v-model:page` 用 |
| `update:pageSize` | `number` | B | 每页条数同步。`v-model:page-size` 用 |
| `page-change` | `number`（0 基） | B | 可选细分信号 |
| `size-change` | `number` | B | 可选细分信号 |

**命名说明**：`update:pageSize` 采用 camelCase，与 Element Plus 自身（`update:currentPage` / `update:pageSize`）一致；Vue 的 `emit()` 对 `update:` 前缀事件会同时尝试 camel 与 hyphen 形式，因此 `v-model:page-size` 与 `@update:page-size` 两种写法都能命中。

### 2.4 统一规则

| 规则 | 实现 |
|---|---|
| **模式判定唯一** | `props.pagination` 存在 → 模式 A；否则 → 模式 B。A 优先，无歧义 |
| **0/1 基转换固定在组件内** | 显示 `page + 1`、回写 `page - 1`；调用方始终面对 0 基页码。不扩散到调用方 |
| **请求触发只有 `@change`** | `update:page` / `update:pageSize` 只同步状态、不触发请求 → 一次用户操作不产生重复请求 |
| **改每页条数页码归零** | 模式 B 在 `update:pageSize` 时同时 `emit('update:page', 0)`，与模式 A 的 `onPageSizeChange` 语义一致 |
| **模式 A 不派发模式 B 的新事件** | 细分事件与 `update:*` 仅在模式 B 派发，旧调用方行为零变化 |
| **边界保护** | 模式 B 的 `page` 负值在显示与回写两处均 clamp 到 `0`（不产生 0 或负的显示页码） |

> **受控语义**：模式 B 是受控组件——用户在 UI 上翻页只会 `emit('update:page')`，需要调用方回写 `page` 才会真正换页（与 `v-model` 语义一致）。想「非受控」使用则只传 `:total` 并监听 `@change` 自行维护状态。

---

## 3. 兼容性策略与证据

| 策略 | 做法 | 证据 |
|---|---|---|
| 只增不改 | 新增 props/emits 全部可选；`pagination` 由必填改为可选 | 15 个调用方 **零改动**，`npm run typecheck` 零错误 |
| 模式 A 代码路径不变 | 所有写状态处仍走 `pagination.onPageChange` / `onPageSizeChange`；`change` 仍只挂在 `current-change` / `size-change` | B1 的 15 条模式 A 现状契约用例 + 原有 `LqPagination.test.ts` 4 条**全部保持通过** |
| 不新增模式 A 事件 | `update:page` / `update:pageSize` / `page-change` / `size-change` 仅在无 `pagination` 时派发 | 新增用例「模式 A 不派发模式 B 的细分事件」明确锁定 |
| 视觉不变 | `class="lq-pagination lq-pagination--{mode}"` 与 `background` 保持不变；`list-controls.css` **未修改**；template 只把 `:total="pagination.pageInfo.total"` 换成 `:total="total"` | 新增用例「两种模式共用同一套视觉 class、layout 与分页选项」断言两模式的 `classes()`、`layout`、`pageSizes`、`pagerCount` 完全相等 |
| 0/1 基不扩散 | 转换仍在组件内 | B1 的 5 条 0/1 基换算用例保持通过 |

---

## 4. 验收标准对照

| 验收项 | 成功标准 | 结果 | 证据 |
|---|---|---|---|
| **原有兼容** | 15 个现有调用文件无需修改 | ✅ | `git status` 显示 15 个调用方均未被改动；typecheck 零错误；模式 A 契约 15 条 + 原 4 条全绿 |
| **新增能力** | B2 的 5 条 `it.fails` 转为正常通过 | ✅ | 5 条已移除 `.fails` 并通过，且新增补强用例 |
| **状态一致** | 两种模式均正确处理 0/1 基页码 | ✅ | 模式 A：0 基 0→显示 1、点击第 3 页→回写 2；模式 B：`page=2`→显示 3、点击第 3 页→`update:page` 为 2、外部回写 `page=4`→显示 5 |
| **请求行为** | 一次用户分页操作不产生重复请求 | ✅ | 两模式均有「单次翻页只触发一次 change」；「仅 `update:*` 不触发 change」；模式 A 两次独立交互触发两次（不合并、不丢失） |
| **视觉一致** | 保留原有公共分页 CSS 与交互 | ✅ | CSS 未改；两模式 class/layout/pageSizes/pagerCount 断言相等 |

---

## 5. 测试数据

### 5.1 执行结果

| 阶段 | 命令 | 结果 |
|---|---|---|
| B2 后全量 | `npx vitest run` | **74 文件全部通过，625 passed / 0 failed / 2 expected fail / 1 skipped（628）**，退出码 0 |
| 单文件 | `npx vitest run .../LqPagination.contract.test.ts .../LqPagination.test.ts` | 28 + 4 = **32 全通过** |
| 类型 | `npm run typecheck`（`vue-tsc --noEmit --skipLibCheck`） | **零错误** |
| Lint | `npm run lint`（`eslint src/ --quiet`） | **零 error** |

### 5.2 与 B1 基线对比

| 指标 | B1 结束时 | B2 后 | 变化 |
|---|---:|---:|---|
| 全量 passed | 612 | **625** | **+13** |
| 全量 failed | 0 | **0** | 持平 |
| expected fail | 7（B2 占 5、B3 占 2） | **2**（仅剩 B3 的 `LqTable` 空态扩展） | **−5** |
| skipped | 1 | 1 | 持平（`LqTable` 泛型编译期登记） |
| `LqPagination.contract.test.ts` | 20（15 通过 + 5 期望失败） | **28（全通过）** | +8 |

> 变化拆解：5 条期望失败转通过（+5），新增 8 条统一规则/集成用例（+8），共 +13。

### 5.3 `LqPagination.contract.test.ts` 用例构成（28 条）

| 分类 | 条数 | 说明 |
|---|---:|---|
| 模式 A · 0/1 基换算契约 | 5 | B1 建立，保持通过 |
| 模式 A · 请求触发契约 | 4 | B1 建立，保持通过 |
| 模式 A · 属性透传契约 | 5 | B1 建立，保持通过 |
| 模式 A · 真实 Element Plus 集成 | 1 | B1 建立，保持通过 |
| **模式 B · 独立状态（B2 新增能力）** | 5 | 原 `it.fails`，B2 后转正常通过 |
| **统一规则（B2 新增）** | 7 | 模式优先级、默认值、负 page clamp、单次翻页不重复 change、仅 `update:*` 不触发、模式 A 不派发细分事件、两种模式视觉一致 |
| **模式 B · 真实 Element Plus 集成** | 1 | 真实点击 `.btn-next` → `update:page` 为 0 基 1、`change` 一次 |

---

## 6. 未做的事（按指令明确排除）

| 项 | 原因 |
|---|---|
| **不迁移 11 个裸 `el-pagination` 文件** | 本轮只证明公共组件已具备接入能力；迁移属于 B6/B7，需按业务差异逐个推进。混在一起会同时引入「公共组件改动」与「业务页面改动」两类变量，增加排查难度 |
| 不改 `LqTable` | 属 B3；B1 已验证其 `$attrs` 透传完好，2 条 B3 期望失败用例仍保留 |
| 不改 `LqDialog` / `SearchToolbar` | 两者已通过较完整的现状测试（26 / 13 条全绿），属 B4，不抢先动 |
| 不改 `useListPagination.ts` | 模式 A 完全依赖它，零改动即可兼容 |
| 不改 `list-controls.css` | 视觉一致性要求「保留原有公共分页 CSS」 |
| 不新增 `LqDrawer`、不做设计变量治理 | 属 B5 |
| 不提交、不推送 | 按限制执行 |

---

## 7. 风险与观察

| # | 项 | 说明 |
|---|---|---|
| 1 | **模式 B 是受控组件** | 只传 `:page` 而不监听 `@update:page` 时，UI 翻页不会真正生效。这是有意的设计（与 `v-model` 语义一致），但接入时**必须**回写，否则会出现「点了没反应」。已在组件头注释中写明 |
| 2 | **`pageSize` 未做非法值兜底** | 模式 B 传 `0` 或负数会原样透传给 `el-pagination`（与 `useListPagination` 的现状一致：`size=0` 时 `totalPages` 为 `Infinity`）。刻意不加隐式兜底，避免与模式 A 行为分叉；如需治理建议在 `useListPagination` 层统一处理 |
| 3 | **模式判定依赖 `pagination` 是否存在** | 若调用方误把 `undefined` 传进 `pagination`（如异步初始化未完成时），组件会**降级到模式 B** 而不是保持模式 A。现有 15 个调用方均为同步创建，未触发；这是双模式设计的固有取舍（判定必须唯一且无歧义） |
| 4 | `onMounted` 的 `querySelector` aria 兜底 | 仍保留（删除即可访问性回归），P2 长尾项 |
| 5 | 全量测试中的存量 stderr | `ListPageLayout.test.ts` 的 `el-button`/`el-icon` 未解析警告为**存量现象**，非本轮引入，测试仍通过 |

---

## 8. B3 衔接

B3 为 `LqTable` 类型与接口完善，须遵循：

1. **条件式透传规则**（B1 §6.4）：允许为 `row-key`/`max-height`/`span-method`/`default-sort`/`expand-change`/`select` 等显式声明 props/emits，但**必须同步实现向内部 `el-table` 的转发，并用兼容测试证明行为等价**。判据是「声明后透传行为是否与现状一致」，而非「有没有声明」。
2. 交接信号：`LqTable.attrs.test.ts` 中 2 条 `it.fails`（`emptyHint` / `emptySize`）须先移除 `.fails` 再改实现，否则 vitest 会报「expected to fail but passed」。
3. 泛型类型契约由 `vue-tsc` 在 13 个调用方上验证零新增错误；该 1 条 `it.skip` 登记项随之关闭。
4. `LqDialog`（26 条）与 `SearchToolbar`（13 条）契约继续作为 B4 的回归基线，B3 期间不得改动。

---

## 附录：B2 关键实现位置

| 内容 | 位置 |
|---|---|
| 双模式 props/emits 声明 | `frontend/src/components/common/LqPagination.vue` `defineProps` / `defineEmits` |
| 模式判定与 0/1 基换算 | 同上，`total` / `currentPage` / `pageSize` 三个计算属性 |
| 请求触发统一 | 同上，`handleCurrentChange` / `handleSizeChange`（只 emit `change`，模式 B 追加细分事件） |
| 模式 A 契约（15 条） | `frontend/src/components/common/__tests__/LqPagination.contract.test.ts` 前半 |
| 模式 B 契约（5 + 7 条） | 同上，「模式 B · 独立状态模式」「两种模式的统一规则」两个 describe |
| 分页样式（未改动） | `frontend/src/styles/modules/list-controls.css` |
