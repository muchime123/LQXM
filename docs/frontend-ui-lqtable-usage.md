# LqTable 使用规范

> 适用对象：所有需要渲染表格的前端页面与组件。
> 配套：`docs/frontend-ui-p0b0-remediation-plan.md`（整改设计）、`docs/frontend-ui-b3-table-implementation-report.md`（B3 实施记录）
> 组件：`frontend/src/components/common/LqTable.vue`

---

## 1. 什么时候用 LqTable，什么时候用原生 el-table

### 1.1 默认优先使用 `LqTable`

满足以下任一条件时，**默认**用 `LqTable`，不要裸写 `el-table`：

- 列表页的主表格（带搜索栏、分页、批量操作的那种）
- 需要统一密度、斑马纹、hover、表头样式的表格
- 需要统一空态文案与补充提示的表格
- 需要行选择（批量管理）、排序、行点击跳转的表格

```vue
<LqTable :data="rows" row-key="id" density="compact" @row-click="openDetail">
  <el-table-column prop="name" label="名称" min-width="200" />
</LqTable>
```

### 1.2 允许直接使用原生 `el-table` 的场景

以下情况**不算违规**，可以直接用 `el-table`，不必强行套 `LqTable`：

| 场景 | 说明 | 实例 |
|---|---|---|
| 可编辑表格 | 单元格内是表单控件、需要行内编辑与校验 | 导入预览、批量录入 |
| 嵌套明细 / 展开面板 | 展开区域本身是复杂结构（子表格、图表） | `CashflowAccountTable` 的逐月明细 |
| 复杂业务结构 | 多级表头、树形 + 懒加载、跨列合并等结构远超列表页范畴 | 对账、分析下钻 |
| 抽屉 / 弹窗内的小表 | 行数固定、无分页无空态诉求，套壳收益为负 | 明细弹窗内的附属清单 |
| 自成一套视觉的页面 | 页面本身有独立设计规范，接入公共外壳会冲突 | 设备视频库等 |

> 判定原则：**「列表页主表格用 LqTable，非列表页或结构特殊的表格用 el-table」**。
> 拿不准时优先 `LqTable`；确有特殊理由就用 `el-table`，并在代码里写一句注释说明原因。

### 1.3 不要做的事

- **不要创建平行实现**：不要新增 `LqTableV2` / `CommonTable` / `BaseTable`。能力不够就补 `LqTable`。
- **不要为了绕过 LqTable 而复制它的样式**：`.lq-table-panel` 的样式只在 `LqTable.vue` 内维护。
- **不要批量迁移**。存量裸 `el-table` 按 B6/B7 分批、按页面逐个推进，避免一次改动引入不可控回归。

---

## 2. 属性与事件透传（重点）

`LqTable` 用 `inheritAttrs: false` + `v-bind="$attrs"` 把**未声明**的属性与事件原样透传给内部 `el-table`。

### 2.1 已声明的 props（有类型提示）

| prop | 类型 | 默认 | 说明 |
|---|---|---|---|
| `data` | `T[]` | 必填 | 行数据；泛型 `T` 由此推导 |
| `loading` | `boolean` | `false` | 走 `v-loading` |
| `stripe` | `boolean` | `true` | 斑马纹 |
| `border` | `boolean` | `false` | 边框 |
| `density` | `'compact' \| 'default' \| 'comfortable'` | `'default'` | 单元格纵向内边距 |
| `emptyText` | `string` | `'暂无数据'` | 空态主文案 |
| `emptyIcon` | `string` | — | 空态图标（字符形式） |
| `emptyHint` | `string` | — | 空态补充提示（B3 新增） |
| `emptySize` | `'sm' \| 'md' \| 'lg'` | `'md'` | 空态尺寸（B3 新增） |
| `rowClassName` | `string \| (({ row, rowIndex }) => string)` | — | 行 class，支持字符串与函数 |

### 2.2 已声明的 emits

`selection-change` / `sort-change` / `row-click` / `row-dblclick` —— 均**单次派发**，不会二次触发。

### 2.3 经 `$attrs` 透传的 Element Plus 能力

以下**未声明**，直接写在标签上即可，LqTable 会原样交给 `el-table`：

```vue
<LqTable
  :data="rows"
  row-key="id"                       <!-- 属性透传 -->
  max-height="560px"
  :span-method="spanMethod"
  :default-sort="{ prop: 'createdAt', order: 'descending' }"
  :show-summary="true"
  :tree-props="{ children: 'children' }"
  highlight-current-row
  @expand-change="onExpand"          <!-- 事件透传 -->
  @select="onSelect"
  @filter-change="onFilterChange"
/>
```

常见透传属性：`row-key`、`max-height`、`height`、`span-method`、`default-sort`、`show-summary`、`tree-props`、`fit`、`size`、`highlight-current-row`
常见透传事件：`expand-change`、`select`、`select-all`、`filter-change`、`current-change`、`header-click`、`cell-click`

### 2.4 条件式规则（改动 LqTable 时必须遵守）

> **不是「永远不能声明」这些属性，而是「声明即须转发 + 测试证明等价」。**

若将来为上面任一项显式声明 prop 或 emit：

1. **必须同步实现向内部 `el-table` 的转发**（属性 → 目标 prop，事件 → 目标 emit）；
2. **必须让 `LqTable.attrs.test.ts` 的透传用例继续通过**（目标 prop 名一致、函数引用不变、事件单次派发）。

原因：Vue 会把已声明的项从 `$attrs` 移除。漏转发即**静默失效**——`MoldImportPreviewTable` 的 `span-method`、`CashflowAccountTable` 的 `expand-change` 会立刻坏掉，且没有任何报错。

---

## 3. 常用能力

### 3.1 行选择（批量管理）

```vue
<LqTable :data="rows" row-key="id" @selection-change="onSelectionChange">
  <el-table-column type="selection" width="50" />
  ...
</LqTable>
```

- 批量选择状态用 `useBatchSelection`（`selectedIds` 为 `Set`），不要自建第二套。
- 需要清空选中时调用 `LqTable` 暴露的 `clearSelection()`：

```vue
<LqTable ref="tableRef" ... />
```
```ts
const tableRef = ref<{ clearSelection: () => void; toggleAllSelection?: () => void } | null>(null)
tableRef.value?.clearSelection()
```

### 3.2 排序

```vue
<LqTable
  :data="rows"
  :default-sort="{ prop: sortState.field, order: sortState.order }"
  @sort-change="handleSortChange"
>
  <el-table-column prop="name" label="名称" sortable="custom" />
</LqTable>
```

- `default-sort` 走透传；`sort-change` 是 LqTable 已声明事件，载荷为 `{ column, prop, order }`。
- 服务端排序时：在 `handleSortChange` 里更新 `sortState`，并让 `default-sort` 与 `sortState` 初始值保持一致。

### 3.3 空状态

```vue
<LqTable
  :data="rows"
  empty-text="没有可显示的资产"
  empty-icon="📦"
  empty-hint="调整筛选条件，或点击右上角「新增资产」。"
  empty-size="sm"
/>
```

- 空态复用 `EmptyState` 组件，**不创建第二套空状态视觉**。
- 完全自定义时用 `#empty` 插槽，它会**覆盖** `emptyText`/`emptyHint`/`emptySize`（插槽优先）。
- `emptyHint` 只在需要补充引导时出现；没有引导价值就不要写（空着比写废话好）。

### 3.4 密度

`density` 控制单元格纵向内边距：`compact`（密集列表、抽屉内）/ `default`（默认）/ `comfortable`（宽松）。

列表页主表格常用 `compact` 或 `default`，由页面信息密度决定，不要为了「看起来一致」强行统一到同一档。

---

## 4. 什么时候不应继续向 LqTable 加功能

`LqTable` 是**通用表格外壳**，不是业务组件。以下需求**不应**加进 `LqTable`：

| 不该加的 | 应该放哪 |
|---|---|
| 某个业务的专属列渲染（状态标签、金额格式、操作按钮组） | 业务页面 / 业务组件内 |
| 业务专属的空态引导（"去导入中心导入"这类带跳转的） | 页面级 `#empty` 插槽 |
| 行内编辑、单元格校验 | 业务组件（直接 `el-table`） |
| 与某个接口耦合的加载逻辑 | 页面的 composable |
| 只在 1 个页面用到的交互（如"双击复制单号"） | 页面内 `@row-dblclick` 处理 |

**判据**：如果某个能力只有 1 个业务方会用，或者带上后需要 LqTable 认识业务字段名，它就属于业务层。

允许加进 `LqTable` 的：跨多个页面复用的**交互/视觉**能力，且能不依赖业务语义实现（如 `emptyHint`、`density`、`rowClassName`）。

---

## 5. 泛型与类型

```vue
<LqTable :data="assets" @row-click="row => openDetail(row.id)" />
```

- `data` 传入 `AssetRow[]` 时，`row-click` / `row-dblclick` 的载荷、`selection-change` 的数组元素都会被推导为 `AssetRow`（由 vue-tsc 在 `.vue` 模板中校验）。
- 推导不出来时退化为 `any`，与改造前一致，现有调用方零改动。
- `el-table-column` 的 `#default="{ row }"` 由 Element Plus 提供类型，不受 LqTable 泛型影响——需要行字段类型提示时，在页面里自行标注。

---

## 6. 迁移存量 `el-table` 的步骤

1. 确认不是 §1.2 的例外场景。
2. 把 `<el-table>` 换成 `<LqTable>`，保留 `el-table-column` 不变。
3. 把原先写在 `el-table` 上的属性保留（大部分会自动透传）；`stripe`/`border`/`loading` 改成 LqTable 的 props。
4. 删除页面里的表格外壳 `<style>`（`.el-table` 相关），交给 LqTable。
5. 空态改用 `empty-text` / `empty-hint`，删除页面自绘的空态块。
6. 人工回归：加载、排序、行选择、批量、分页、空态、筛选。
