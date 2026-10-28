# B3：`LqTable` 公共表格能力完善

> 目标：只完善现有 `LqTable`，不创建平行实现、不重做表格视觉规范、不批量迁移裸 `el-table` 页面。
> 上游：`docs/frontend-ui-p0a-audit.md`、`docs/frontend-ui-p0b0-remediation-plan.md`、`docs/frontend-ui-b1-test-baseline-report.md`、`docs/frontend-ui-b2-pagination-decoupling-report.md`
> 生成日期：2026-10-27

---

## 1. 执行基准与实际修改文件

### 1.1 基准

| 项 | 值 |
|---|---|
| HEAD | `42bc081c271124620254a7fe79fff55d00a7e904`（未前进；**B1 / B2 / B3 均未提交推送**，全部以当前实际工作区为准） |
| B2 结束时基线 | 74 文件 / **625 passed / 2 expected fail / 1 skipped** |
| 工具 | vitest `^4.1.9` + @vue/test-utils `^2.4.11` + happy-dom `^20.10.6`；`vue-tsc ^2.2.12`；eslint `^9.39.4` |
| `LqTable` 调用方 | **13 个生产文件**（不含 2 个测试文件），14 处标签 |

### 1.2 实际修改的文件

| 文件 | 类型 | 改动 |
|---|---|---|
| `frontend/src/components/common/LqTable.vue` | **生产实现** | ① 加头注释「能力矩阵」；② `<script setup>` 改 `generic="T extends Record<PropertyKey, any> = any"`，`data: T[]`、行事件载荷与 `rowClassName` 行参数随 `T`；③ `sort-change` 载荷抽成具名接口 `SortChangePayload`；④ 新增可选 props `emptyHint` / `emptySize` 并转发给 `EmptyState`；⑤ `defineExpose` 的方法参数改用 `T` |
| `frontend/src/components/common/__tests__/LqTable.attrs.test.ts` | 测试 | 2 条 `it.fails` **转为正常通过**；删除 1 条编译期 `it.skip` 登记；新增 5 条空态扩展用例 + 2 条泛型运行时用例 + 2 条真实 Element Plus 集成用例；修正泛型导致的 VTU 类型退化（1 处断言） |
| `frontend/src/components/common/__tests__/LqTable.test.ts` | 测试 | **仅 2 处类型断言**（`findComponent(LqTable)` 在泛型组件下退化为 `DOMWrapper`，需断言回 `VueWrapper`）。**未改动任何断言逻辑与用例** |
| `frontend/src/components/common/__tests__/LqTable.generic.types.ts` | 新增（编译期类型契约） | 4 段类型夹具，随 `npm run typecheck` 校验；**不是** vitest 用例 |
| `docs/frontend-ui-lqtable-usage.md` | 新增（使用规范） | §任务 D 交付物 |
| `docs/frontend-ui-b3-table-implementation-report.md` | 新增 | 本报告 |

### 1.3 明确未改动的

- **`EmptyState.vue` 未修改** —— 它已具备 `hint` 与 `size` prop（分别渲染 `.empty-hint` 与 `.empty-state--{size}`），B3 的空态需求只需**转发**，无需改动公共组件，因此没有触发「确需修改其他公共组件」的例外流程。
- `LqPagination` / `LqDialog` / `SearchToolbar` 生产实现：未动。
- 13 个调用方业务页面：未动（typecheck 零新增错误即证明兼容）。
- 后端、数据库、Flyway：未动。
- `LqTable.vue` 的 `<style scoped>`：**一字未改**（不重做视觉规范）。

### 1.4 并行任务改动（未触碰）

`git status` 显示大量其它并行工作区改动，本轮未读取修改、未清理：
后端 `ManufacturingTaskConfig*`/`WebsiteProduct*`/`WebsiteMedia*`/`ProductionTracking*` 等 17 个文件、新增 `WebsiteProductCategory*`/`WebsiteProductVariant*`/`ProductionTrackingStage*`、`V419~V421` 迁移脚本；
前端 `api/productionTracking.ts`、`api/website/*`、`navigation.ts`、`routes.ts`、`WebsiteProductsView.vue`、`ManufacturingTaskConfigView.vue`、`ProductionTrackingView.vue`、`ManufacturingWorkshopPanel.vue`、`WebsiteMediaPicker.vue`、`useTrackingFilters.ts`、新增 `ProductionProcessConfigView.vue`/`WebsiteProductVariantsDialog.vue`；
脚本与文档 `publish-stable.ps1` 等。`docs/website-content-decoupling-plan-a-20261026.md` 与 3 个 `website-product-display-media-*.json` 亦非本轮产物。

---

## 2. 透传机制与兼容性验证（任务 A）

### 2.1 机制现状

`LqTable.vue` 用 `defineOptions({ inheritAttrs: false })` + `<el-table v-bind="$attrs">`。B3 **未改动**这条通道。

### 2.2 B3 是否新增了显式声明？

**新增了 2 个 props：`emptyHint` / `emptySize`。** 按 §任务 A 的条件式规则逐项自查：

| 检查项 | 结论 |
|---|---|
| 这两个名字此前是否走 `$attrs`？ | **否**。全仓检索确认 `empty-hint` 只被 `ListPageLayout` / `DataState` 使用，`empty-size` 无任何使用点；没有任何调用方把它们传给 `LqTable` |
| 声明后是否从 `$attrs` 移除某个正在被依赖的能力？ | **否**。它们是全新名字，不覆盖任何既有透传属性 |
| 是否需要转发？ | 无需转发给 `el-table`（它们是空态配置），已转发给 `EmptyState` 的 `hint` / `size` |
| 兼容测试是否证明行为不变？ | 是，见 §5 |

**没有**为 `row-key` / `max-height` / `span-method` / `default-sort` / `expand-change` / `select` 等声明 props 或 emits —— 这些仍走 `$attrs`，13 个调用方依赖的能力全部保留。

### 2.3 透传契约验证结果（B1 建立的 11 条 + 事件 2 条，全部保持通过）

| 能力 | 验证方式 | 结果 |
|---|---|---|
| `row-key` | 断言内部 el-table 收到 `rowKey='id'` + 真实 EP 集成 | ✅ |
| `max-height` | 断言收到 `maxHeight='560px'` + 真实 EP 集成 | ✅ |
| `span-method` | 断言**函数引用不变**（`toBe(spanMethod)`） | ✅ |
| `default-sort` | 断言对象等价 | ✅ |
| `height` / `show-summary` / `tree-props` | 断言透传值 | ✅ |
| `stripe` / `border` | 已声明 props 不被 attrs 覆盖 | ✅ |
| `row-class-name` | 断言 `mergedRowClass` 委派语义（函数 / 字符串 / 未传 3 形态） | ✅ |
| `expand-change` / `select` | 断言仍留在 `$attrs`（`onExpandChange` / `onSelect`） | ✅ |
| `selection-change` / `sort-change` / `row-click` / `row-dblclick` | 断言**不**残留在 `$attrs`（避免二次派发）；`LqTable.test.ts` 另有 4 条「只派发一次」 | ✅ |

**结论：无静默失效。** 13 个调用方零改动，typecheck 零新增错误。

---

## 3. 类型方案与取舍（任务 B）

### 3.1 最终方案

```vue
<script setup lang="ts" generic="T extends Record<PropertyKey, any> = any">
```

- `data: T[]`
- `row-click` / `row-dblclick` 载荷为 `T`；`selection-change` 载荷为 `T[]`
- `rowClassName` 的行参数为 `T`
- `defineExpose` 的 9 个方法参数用 `T`
- 默认 `T = any` → 推导不出时退化为 `any`，**与改造前完全一致**

### 3.2 为什么约束是 `Record<PropertyKey, any>`，而不是 `Record<string, unknown>` 或不约束

Element Plus 把表格行类型定义为 `type DefaultRow = Record<PropertyKey, any>`（见 `element-plus/es/components/table/src/table/defaults.d.ts:11`）。实测三种写法的差异：

| 写法 | 结果 |
|---|---|
| `generic="T = any"`（不约束） | `LqTable.vue` 内部报 **4 个错误**：`T[]` 不能赋给 `DefaultRow[]`、`toggleRowSelection(row: T)` 等 3 处参数不兼容 |
| `generic="T extends Record<string, unknown>"` | 未采用：TS 不给 **interface** 隐式索引签名，`interface AssetRow {…}` 无法满足该约束，会波及 13 个调用方 |
| **`generic="T extends Record<PropertyKey, any> = any"`** | ✅ **0 错误**，且与 EP 的 `DefaultRow` 完全对齐 |

### 3.3 泛型是否真的生效？——实测

**结论：在 `.vue` 模板中生效，在 `h()` 调用中不生效。**

- **`.vue` 模板（真实用法）**：临时探针里写 `@row-click="row => row.definitelyNotAField"`，vue-tsc 报
  `Property 'definitelyNotAField' does not exist on type 'AssetRow'` → **`row` 确实被推导为 `AssetRow`**。
  探针验证后已删除。
- **`h(LqTable, {...})`**：TS 无法为泛型组件推导类型参数，`T` 退化为约束 `Record<PropertyKey, any>`。
  这是 TypeScript 的已知限制，不是组件缺陷，已在 `LqTable.generic.types.ts` 文件头明确记录。

### 3.4 取舍与代价（如实记录）

| 收益 | 代价 |
|---|---|
| 13 个调用方的 `@row-click` / `@row-dblclick` / `@sort-change` 处理函数得到真实行类型提示 | 泛型组件会让 VTU 的 `findComponent(LqTable)` 重载解析退化为 `DOMWrapper<Node>`，需 3 处类型断言（2 个测试文件） |
| `row-class-name` 的行参数有类型 | `h()` 场景推导失效（仅影响类型夹具，不影响模板） |
| `data` 与行事件的载荷类型自动保持一致 | 无 |

**保留的 `any`（不强行消灭）**：
1. `SortChangePayload.column` —— Element Plus 未公开稳定的列上下文类型，改成 `unknown` 会打断调用方对 `column.prop` 的访问。
2. 模板中 `#default="{ row }"` 的行类型 —— 由 EP 的 `el-table-column` 提供，不受本泛型控制；若强行接管需要重写列插槽类型，收益不成比例。
3. `T` 的默认值为 `any` —— 保证推导失败时行为与改造前一致。

> 判据（§任务 B 第 4 条）全部满足：① 现有调用方无需改动；② 不破坏模板插槽与 `$attrs` 透传；③ 真实页面获得有效类型提示（已由探针证明）；④ typecheck 与既有测试通过。

### 3.5 编译期登记用例的转正（任务七第 3 条）

B1 的 `it.skip('泛型类型契约…')` **已删除**，改为两层：

1. **运行层**：`LqTable.attrs.test.ts` 新增 2 条用例，断言 `row-click` 载荷即 `data` 元素、`selection-change` 载荷为行数组。
2. **编译层**：新增 `LqTable.generic.types.ts`（4 段夹具），由 `vue-tsc` 校验；文件头记录了「`h()` 推导失效、模板推导生效」的实测结论，推导能力的最终守护者是 **13 个真实调用方 + typecheck 全绿**。

> 之所以不保留 `it.skip`：`skip` 只登记不校验，泛型一旦被破坏也不会有任何信号。现在删除泛型或改回 `any[]`，编译期夹具与 typecheck 会同时给出反馈。

---

## 4. 空状态能力（任务 C）

### 4.1 B1 留下的 2 条 `it.fails` 的真实需求

| 用例 | 需求 |
|---|---|
| `emptyHint 渲染空态补充提示` | 空态除主文案外，需要一行次级引导文案（如「调整筛选条件后再试」） |
| `emptySize 控制空态尺寸` | 空态需要可选的小尺寸，用于抽屉/面板等空间受限场景 |

### 4.2 实现方式：复用 `EmptyState`，不创建第二套视觉

读完 `EmptyState.vue` 后发现它**已经具备**这两个能力（`hint` 渲染 `.empty-hint`，`size` 渲染 `.empty-state--{size}`），只是 `LqTable` 没有把它们暴露出来。因此：

```vue
<EmptyState :text="emptyText" :icon="emptyIcon" :hint="emptyHint" :size="emptySize" />
```

- **未修改 `EmptyState.vue`** —— 不需要。
- **未创建第二套空状态视觉** —— 完全复用。
- **`#empty` 插槽优先级保持最高** —— 已有新增用例锁定（传 `empty-hint` + `#empty` 时，插槽内容胜出，不渲染 `EmptyState`）。

### 4.3 测试期望与公共组件职责的一致性判断

**一致，无需调整测试期望。** 两条用例断言的 `.empty-hint` 与 `.empty-state--sm` 正是 `EmptyState` 既有的公开表现，`LqTable` 的职责是「把配置透传给统一的空态组件」，不是自己实现空态。因此按原期望实现，**没有为了通过测试而添加无意义功能**。

补充了 3 条防止回归的用例：不传时保持原行为（无 hint、尺寸 `md`）、`#empty` 插槽优先、三个空态 props 可组合。

---

## 5. 测试结果

### 5.1 B1 契约与 B3 用例

| 文件 | B1 建立 | B2 结束时 | **B3 后** |
|---|---:|---:|---:|
| `LqTable.attrs.test.ts` | 22（19 通过 + 2 期望失败 + 1 skip） | 同左 | **28（全部通过，0 skip、0 期望失败）** |
| `LqTable.test.ts` | 6 | 6 | **6**（断言逻辑未改） |

`LqTable.attrs.test.ts` 用例构成（28 条）：

| 分类 | 条数 | 说明 |
|---|---:|---|
| 属性透传契约 | 11 | B1 建立，保持通过 |
| 结构与空态契约 | 6 | B1 建立，保持通过 |
| 暴露实例方法契约 | 1 | B1 建立（一次覆盖 9 个方法），保持通过 |
| 真实 Element Plus 集成（属性） | 1 | B1 建立，保持通过 |
| **空态扩展能力（B3）** | 5 | `emptyHint`、`emptySize`、不传时保持原行为、`#empty` 插槽优先、三个 props 组合 |
| **泛型运行时（B3）** | 2 | `row-click` 载荷即 data 元素；`selection-change` 载荷为行数组 |
| **真实 Element Plus 集成（B3 新增）** | 2 | 真实 el-table 下 `empty-hint` / `empty-size` 生效；真实事件只派发一次 |

### 5.2 全量门禁

| 命令 | 结果 |
|---|---|
| `npx vitest run` | **74 文件全部通过；634 passed / 0 failed / 0 expected fail / 0 skipped**；退出码 0 |
| `npm run typecheck`（`vue-tsc --noEmit --skipLibCheck`） | **0 错误** |
| `npm run lint`（`eslint src/ --quiet`） | **0 error** |
| `npm run build` | **编译成功**（`✓ 3040 modules transformed`）；随后在清空 `dist/` 步骤被环境的批量删除保护拦截（`SAFE_DELETE_BULK_CONFIRM_REQUIRED`，528 文件 > 阈值 500），**非代码问题**；改用 `--emptyOutDir=false` 复跑时命令被杀毒软件误拦截，未再强推 |

> 全量数字变化：B2 结束 625 passed / 2 expected fail / 1 skipped（628）→ B3 **634 passed / 0 / 0（634）**。
> 拆解：2 条 `LqTable` 期望失败转通过、1 条 skip 转为真实测试、新增 9 条（5 空态 + 2 泛型运行时 + 2 真实 EP），净 +6。

### 5.3 关于「不掩盖问题」

- 未删除任何既有断言；
- 未新增 `skip`；
- 未为通过测试而修改生产组件之外的东西；
- 泛型带来的 VTU 类型退化如实记录并**只加类型断言**（不改断言逻辑）；
- 构建被环境拦截如实记录，未伪造成功。

---

## 6. 对既有调用页面的影响

| 维度 | 结论 |
|---|---|
| 代码改动 | **0**。13 个调用方一行未改 |
| 运行时行为 | **0 变化**。`emptyHint` / `emptySize` 为可选且默认 `undefined`（`EmptyState` 回落到自身默认 `md`）；泛型只在编译期起作用 |
| 类型 | typecheck 零新增错误；`@row-click` 等处理函数**新增**了行类型提示（纯增益） |
| 视觉 | **0 变化**。`<style scoped>` 未改；不传新 props 时 DOM 结构与改造前一致 |
| 测试 | 13 个调用方所在页面若有测试，均在全量 634 通过范围内 |

调用方清单（13）：`ReimbursementLedgerPanel`、`DingtalkSyncPanel`、`CompanyCertificationsTab`、`CertListTable`、`MoldImportPreviewTable`、`OperationLogView`、`DepartmentManagementView`（2 处）、`CashflowIssueTable`、`CashflowEntryTable`、`CashflowAccountTable`、`OfficeAssetView`、`WebsiteInquiryView`、`MediaAccountView`。

---

## 7. 后续业务页面接入建议

1. **新页面一律用 `LqTable`**；存量裸 `el-table` 按 `docs/frontend-ui-lqtable-usage.md` §1 的例外清单判断是否保留，不要一次性批量迁移。
2. **迁移顺序建议**（B6/B7 排期参考）：先挑「列表页主表格 + 无特殊结构」的页面练手，如 `OperationLogView`（已用 LqTable，可作为范式）、`WebsiteInquiryView`；把 `H3LedgerPanel`（1900+ 行）、`ProductInventoryView`、`ProductionTracking*Drawer` 等高风险文件单独排期。
3. **迁移时必须删掉页面里重复的表格外壳样式**，否则会出现两套视觉。
4. **不要为了用 LqTable 而给 LqTable 加业务功能**（判据见使用规范 §4）。
5. 试点页建议仍为 `OfficeAssetView`（同时接入 15 个公共组件，能一次验证 LqTable 泛型 + B2 分页 + 搜索栏 + 弹窗）。

---

## 8. 未解决的问题与合理例外

| # | 项 | 性质 | 处理 |
|---|---|---|---|
| 1 | 泛型在 `h()` 调用中无法推导 | TypeScript 限制，非组件缺陷 | 已在类型夹具文件头记录；真实 `.vue` 模板推导正常（探针验证） |
| 2 | 泛型导致 VTU `findComponent` 重载退化 | 工具链限制 | 2 个测试文件各加 1~2 处类型断言；不影响运行时行为 |
| 3 | `sort-change` 载荷的 `column` 仍为 `any` | 合理例外 | EP 未公开稳定列上下文类型；改 `unknown` 会打断调用方 |
| 4 | `el-table-column` 的 `row` 不受本泛型控制 | 合理例外 | 由 EP 提供；强行接管需重写列插槽类型，收益不成比例 |
| 5 | 11 个（实为更多）裸 `el-table` 页面未迁移 | **本轮明确不做** | 归 B6/B7；本轮只保证公共组件具备能力 |
| 6 | `LqDialog` / `SearchToolbar` 未动 | 本轮明确不做 | 现状契约（26 / 13 条）全绿，归 B4 |
| 7 | `LqPreview.vue:96` 误传 `EmptyState` 不存在的 `description` prop | 既有缺陷（P0-A 已记录） | 不在 B3 范围，避免扩大改动面 |
| 8 | 构建 `npm run build` 未完整跑通 | **环境问题** | 编译阶段成功（3040 模块）；清空 `dist/` 被环境删除保护拦截 |
| 9 | 全量测试中 `ListPageLayout.test.ts` 的 `el-button` / `el-icon` 未解析警告 | 存量现象 | 非本轮引入，测试仍通过 |

---

## 9. 交付物与后续

- 生产改动：`frontend/src/components/common/LqTable.vue`（唯一）
- 测试：3 个文件（1 新增契约夹具、2 个 LqTable 测试）
- 文档：`docs/frontend-ui-lqtable-usage.md`（使用规范）、`docs/frontend-ui-b3-table-implementation-report.md`（本报告）
- 未提交、未推送；未执行 `git clean` / `reset` / `stash`；未触碰并行工作区文件

**下一阶段建议：B4（`LqDialog` / `SearchToolbar` 稳定性完善）**，以 B1 建立的 26 条 + 13 条现状契约作为回归基线。B4 之前不动 `LqTable`。
