# 弹窗与搜索栏统一接入规范

> 适用对象：所有需要弹窗或列表搜索栏的前端页面与组件。
> 配套：`docs/frontend-ui-p0b0-remediation-plan.md`、`docs/frontend-ui-b4-interaction-contract-report.md`
> 组件：`frontend/src/components/common/LqDialog.vue`、`LqConfirmDialog.vue`、`SearchToolbar.vue`、`SearchToolbarBatchBar.vue`
> 生成日期：2026-10-28

---

## 1. 弹窗

### 1.1 优先使用 `LqDialog`

`LqDialog` 是全站弹窗的统一外壳，负责 Header / Body / Footer 三段式布局、`size` 尺寸令牌、`loading` 封锁、脏数据拦截与关闭语义统一。

满足以下任一条件时**默认**用 `LqDialog`：

- 新增 / 编辑类表单弹窗
- 详情查看弹窗
- 需要统一标题、副标题、页脚按钮组的弹窗
- 需要「有未保存修改」提示或关闭拦截的弹窗
- 任何希望与其它页面视觉一致的弹窗

```vue
<LqDialog
  v-model="modal.visible"
  title="编辑账号"
  subtitle="维护账号基础资料"
  size="md"
  mode="form"
  :dirty="dirty"
  :loading="modal.saving"
  :before-close="handleBeforeClose"
  confirm-text="保存"
  @confirm="handleSave"
>
  <!-- 内容 -->
</LqDialog>
```

轻量确认（删除、提示）用 `LqConfirmDialog`，它是 `LqDialog` 的封装，不要再自绘确认弹窗。

### 1.2 允许直接使用原生 `el-dialog` 的合理例外

以下情况**不算违规**，可继续用 `el-dialog`：

| 场景 | 说明 |
|---|---|
| 抽屉形态 | 需要侧滑出入的，用 `el-drawer`（B5 将统一为 `LqDrawer`） |
| 非模态浮层 | 选择器、图片选择器等需要保留背景交互的浮层 |
| 结构远超三段式 | 多区域工作台上含独立滚动、分栏、内嵌表格且页脚不统一的弹窗 |
| 第三方组件自带弹窗 | 由外部库渲染，无法替换 |
| 已在稳定运行且改造收益为负 | 由 B6/B7 按页评估，不强制迁移 |

> 判定原则：**「表单 / 详情 / 确认弹窗用 LqDialog，非模态或结构特殊的用原生」**。
> 拿不准就用 `LqDialog`；确有特殊理由直接用 `el-dialog`，并在代码里写一句注释说明原因。

### 1.3 关闭语义（必须遵守）

`LqDialog` 的三种关闭入口（右上角 X / ESC / 遮罩）与页脚「取消」**全部**经过 `beforeClose` 拦截：

- `cancel` **只在关闭被允许后**派发 —— 不会出现「点取消直接关、点 X 才提示」的不一致；
- `close` 是「已经关闭」的唯一出口，两种消费模式（`v-model` 与 `:model-value + @close`）下**均恰好派发一次**；
- `confirm` 只派发意图，**不会**自动关闭弹窗（由业务在保存成功后自行关闭）；
- `loading` 期间三种关闭入口全部封锁（关闭按钮隐藏、ESC 与遮罩失效）。

因此业务侧**不要**自己实现「关闭前确认」弹层，交给 `beforeClose`：

```ts
// 与 useModalForm 配套
function handleBeforeClose(done: () => void) {
  return useModalFormHandleBeforeClose(done)  // 脏则 return false
}
```

### 1.4 嵌套与层级

- 嵌套弹窗时显式传 `:z-index`（`UI_LAYER` 或更高值），不要依赖默认值；
- `append-to-body` 恒为 `true`，这是**现状且受保护**：它保证嵌套弹窗不被父级 `overflow/z-index` 裁剪。
- **业务 `:deep` 样式不会因此失效**。实测：class 经 attrs 落到 `el-dialog` 根节点，该节点是 `LqDialog` 的子组件根元素并继承父组件 scope id，内部结构是其后代，因此 `.xxx :deep(.lq-dialog__head)` 这类**后代选择器**照常命中。Teleport 只改变 DOM 位置，不改变这层关系。（证据见 `LqDialog.teleport.contract.test.ts`）

### 1.5 不要做的事

- **不要创建第二套弹窗组件**（`LqModal` / `BaseDialog` 等）。
- **不要复制弹窗头尾结构**。需要特殊布局就用 `#title` / `#actions` / `#footer` 插槽。
- **不要**在业务侧自绘 `.el-dialog__footer` 覆盖样式；页脚按钮用 `#footer` 插槽 + 公共类。
- **不要**为了「代码看起来更简洁」重构 `suppressNextClose`。它是 `:model-value + @close` 消费模式的兼容机制，26 条契约已锁定其行为，B4 明确保留。

---

## 2. 搜索栏

### 2.1 标准接口

```vue
<SearchToolbar
  v-model:keyword="filters.keyword"
  search-mode="debounce"          <!-- 推荐显式指定：debounce | submit -->
  :debounce="300"
  :fields="fields"
  :field-values="filters"
  :batch-enabled="canManage"
  :batch-mode="batchMode"
  @search="onSearch"
  @filter-change="onFilterChange"
  @clear="onClear"
  @toggle-batch-mode="toggleBatchMode"
>
  <template #extra> ... </template>
  <template #actions> ... </template>
</SearchToolbar>
```

| 事件 | 时机 | 载荷 |
|---|---|---|
| `search` | 防抖到点 / 回车 / 输入框清空 / 点「查询」 | `string`（已 `trim`） |
| `filter-change` | 下拉筛选变更；文本筛选防抖到点 / 回车 / 清空 | `(key, value)` |
| `clear` | 点「清除筛选」 | — |
| `toggle-batch-mode` | 进入 / 退出批量 | — |
| `toggle-select-all` / `batch-edit` / `batch-remove` | 批量条按钮 | — |

### 2.2 `searchMode` 与 `searchOnInput`

- **新调用方一律显式传 `search-mode`**：`'debounce'`（输入即搜）或 `'submit'`（配合 `query-button`，点「查询」才搜）。
- `search-on-input` 已标记 `@deprecated`。它有**零个显式调用方**，但仍是 `searchMode` 缺省时的兜底默认值（`true`），因此保留实现不删除。

### 2.3 搜索状态由哪一层管理

**搜索状态归业务页面（列表页）所有，`SearchToolbar` 只做输入与事件翻译。**

- `keyword` / `fieldValues` 由页面的筛选状态持有（`useListFilters` 一类），并通过 `v-model:keyword` 与 `:field-values` 传入；
- `SearchToolbar` **不**持有搜索结果、不发起请求、不缓存列表；
- 文本筛选在组件内保留「本地镜像 + 防抖提交」，只是为了让输入过程不抖动，**不是**状态所有权；外部回写 `fieldValues` 时镜像会跟随，且不会反向派发 `filter-change`；
- 重置筛选由页面响应 `@clear` 后重置自己的状态，组件不代劳。

### 2.4 输入法

中文 / 日文输入法组词期间（`compositionstart` ~ `compositionend`）组件**不会**提交搜索或筛选，`compositionend` 后补提交。业务侧**不要**再自行加输入法判断，否则会重复拦截。

### 2.5 批量模式

- `batch-enabled` 打开批量能力，`batch-mode` 控制当前是否处于批量态；
- 批量态下工具栏整体替换为 `SearchToolbarBatchBar`（不是新增一行），避免推动下方列表；
- **批量态的切换走 `@toggle-batch-mode` 事件**。目前 `batchMode` 是**单向 prop**，组件不派发 `update:batchMode`，因此**不要写 `v-model:batch-mode`**——它会静默无效（见 B4 报告已记录的 `PackagingMoldView.vue:43`）。

### 2.6 职责边界

| 属于 `SearchToolbar` | 属于业务页面 |
|---|---|
| 输入框、下拉、视图切换的渲染 | 搜索结果、分页、请求 |
| 防抖调度、输入法处理、卸载清理 | 筛选状态的持有与重置 |
| 把用户操作翻译成 `search` / `filter-change` / `clear` | 响应事件并刷新列表 |
| 批量条的显隐与内置按钮 | 批量操作的具体业务实现 |

**不要把业务专属筛选**（如只在某一页出现的复合选择器）塞进 `SearchToolbar`；用 `#extra` 插槽。

---

## 3. 特殊布局的正确做法

优先级从高到低：

1. **先用 props**：`size` / `mode` / `showFooter` / `showCancel` / `showConfirm` / `confirmText` / `fullscreen` / `scrollable`。
2. **再用插槽**：`#title`、`#actions`、`#footer`、`#extra`、`#actions`、`#batch-actions`。
3. **再用公共样式**：`styles/modules/dialog-layout.css`、设计令牌 `--dialog-width-*` / `--space-*` / `--radius-*`。
4. **最后才考虑复制组件代码** —— 如果走到这一步，说明公共组件缺能力，**提需求补 `LqDialog` / `SearchToolbar`**，而不是复制一份。

---

## 4. 存量页面迁移

`LqDialog` 已有 55 个调用方、75 处标签；仍有 **24 个文件**使用原生 `el-dialog`（其中 20 个自绘 `#footer`）。`SearchToolbar` 有 14 个调用方，全部已接入。

迁移顺序建议（B6/B7 排期）：

1. 先迁「纯表单 / 纯详情」弹窗，收益最高、风险最低；
2. 再迁自绘 `#footer` 的文件，删除页面内重复的页脚样式；
3. 结构特殊的（工作台、多区域）最后评估，允许保留原生实现；
4. 每迁一个页面人工回归：打开 / 关闭 / ESC / 遮罩 / 脏数据拦截 / 保存中关闭 / 嵌套层级。
