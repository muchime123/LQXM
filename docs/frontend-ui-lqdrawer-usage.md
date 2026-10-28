# LqDrawer 使用规范

> 适用对象：所有需要抽屉（侧滑面板）的后台页面与组件。
> 组件：`frontend/src/components/common/LqDrawer.vue`
> 样式：`frontend/src/styles/modules/drawer-layout.css`（三段式布局）
> 尺寸令牌：`frontend/src/styles/tokens.css` 的 `--drawer-width-*`
> 配套：`docs/frontend-ui-dialog-search-usage.md`（弹窗与搜索栏规范）
> 生成日期：2026-10-28（B5-A）

---

## 1. 与 LqDialog 的适用场景区分

一句话：**「需要保留上下文 → 抽屉；需要打断当前任务 → 弹窗」**。

| 判断维度 | 用 `LqDrawer` | 用 `LqDialog` |
|---|---|---|
| 与背景列表的关系 | 从列表中「展开一条看详情」，看完回到列表 | 需要用户停下来做决定 |
| 是否常驻 / 下钻 | 详情里还要下钻、翻页、开二级抽屉 | 一次性完成的表单 |
| 内容形态 | 长内容、纵向滚动、贴边面板 | 短表单、确认、居中卡片 |
| 典型场景 | 台账详情、跟单详情、分析面板、导入明细 | 新增/编辑表单、删除确认 |
| 关闭的心智成本 | 低（ESC / 点遮罩即可退出） | 高（通常要显式点按钮） |

**不要**用抽屉承载需要用户「必须做出选择才能继续」的阻断式流程 —— 那会让遮罩点击关闭变成一个危险操作。

两者共用同一套关闭语义（`v-model` / `beforeClose` / `loading` 封锁 / `confirm` 不自动关闭），迁移心智成本低。

---

## 2. 标准接入

### 2.1 标准详情抽屉

```vue
<LqDrawer
  v-model="detailVisible"
  title="生产跟单"
  subtitle="默认为 md 尺寸，内容区自动滚动"
  size="md"
  mode="detail"
>
  <!-- 详情内容 -->
</LqDrawer>
```

`mode="detail"` 默认**不渲染 footer**，内容区滚动。

### 2.2 标准编辑表单抽屉

```vue
<LqDrawer
  v-model="modal.visible"
  title="编辑角色"
  subtitle="维护角色与权限"
  size="md"
  mode="form"
  :dirty="dirty"
  :loading="modal.saving"
  :before-close="handleBeforeClose"
  confirm-text="保存"
  @confirm="handleSave"
>
  <!-- 表单 -->
</LqDrawer>
```

`mode="form"` 默认渲染 footer（取消 / 保存），并在 footer 左侧显示脏状态文案。

### 2.3 大型工作台

```vue
<LqDrawer v-model="visible" title="生产交期达成分析" size="xl" mode="workspace">
```

`workspace` 只减少内容区内边距；**不会**隐式改变宽度 —— 需要接近全屏请显式传 `size="xl"` 或 `width="92vw"`。

### 2.4 尺寸

| `size` | 令牌 | 适用 |
|---|---|---|
| `sm` | `min(420px, 100vw)` | 简单详情、轻量确认 |
| `md`（默认） | `min(720px, 94vw)` | 普通详情、基础资料 |
| `lg` | `min(1000px, 94vw)` | 复杂业务、表格、多区域 |
| `xl` | `min(1280px, 96vw)` | 大型工作台 |

每档都带 `vw` 上限，窄屏不会溢出。需要沿用旧的百分比或函数表达式时用 `width` 覆盖：

```vue
<!-- 迁移 el-drawer 的 size="90%" 时改为 width -->
<LqDrawer v-model="visible" width="min(1120px, 98vw)" />
```

> `direction` 为 `ttb` / `btt`（纵向）时，`size` / `width` 作用的是**高度**。

### 2.5 方向

`direction` 默认 `rtl`（右侧滑出），支持 `ltr` / `rtl` / `ttb` / `btt`。纵向抽屉 CSS 已限高 `92vh`。

---

## 3. 插槽（slots）

| 插槽 | 作用 | 说明 |
|---|---|---|
| `#title` | 覆盖头部标题块 | **不会**顶掉关闭按钮，关闭入口仍在 `.lq-drawer__actions` |
| `#actions` | 头部右侧操作区 | 关闭按钮左侧，可放刷新、导出等 |
| 默认插槽 | 内容区 | 渲染在 `.lq-drawer__body` 内 |
| `#footer` | 覆盖底部操作区 | 传了就完全接管，默认的取消/保存不再渲染 |

```vue
<LqDrawer v-model="visible" title="台账详情">
  <template #actions>
    <el-button size="small" @click="reload">刷新</el-button>
  </template>

  <!-- 内容 -->

  <template #footer>
    <el-button @click="visible = false">关闭</el-button>
    <el-button type="primary" @click="submit">保存</el-button>
  </template>
</LqDrawer>
```

**头部整块自绘**：`show-header="false"` 会连 EP 的 `<header>` 一起不渲染，适合内部已自带标题栏的分析面板。

```vue
<LqDrawer v-model="visible" :show-header="false" width="90%">
  <header class="my-own-head">…</header>
</LqDrawer>
```

---

## 4. attrs 透传

`LqDrawer` **没有**用 `defineOptions({ inheritAttrs: false })`，未声明的属性与事件会自动落到 `el-drawer` 上：

- **可透传的属性**：`header-class`、`body-class`、`footer-class`、`modal`、`modal-class`、`lock-scroll`、`resizable`、`header-aria-level`、`modal-penetrable`、`close-on-press-escape`*…
- **可透传的事件**：`open`、`opened`、`closed`、`open-auto-focus`、`close-auto-focus`、`resize-start` / `resize` / `resize-end`…

```vue
<!-- 迁移 TrackingDetailDrawer 的 header-class 时无需改造，直接传即可 -->
<LqDrawer v-model="visible" header-class="tracking-drawer-header" @opened="onOpened" />
```

> \* `close-on-press-escape` 由组件按 `!loading` 统一接管：业务传了也会被覆盖，这是 `loading` 封锁三个关闭入口的一部分，属有意行为。

**注意**：`size`、`direction`、`title` 已被组件声明为 props，不会进 `$attrs`。原生 `el-drawer` 的 `size="90%"` 需改写为 `width="90%"`。

---

## 5. dirty / loading / beforeClose

三者共用 `LqDialog` 的同一套心智模型：

```vue
<LqDrawer
  v-model="visible"
  mode="form"
  :dirty="dirty"
  :loading="saving"
  :before-close="handleBeforeClose"
  @confirm="handleSave"
/>
```

```ts
// 与 useModalForm 配套：脏数据直接阻止关闭
function handleBeforeClose(done: () => void) {
  return modalForm.handleBeforeClose(done)   // 脏则返回 false
}
```

| 能力 | 行为 |
|---|---|
| `dirty` | 头部显示「已修改」徽章；`mode="form"` 时 footer 左侧显示「有未保存的修改」 |
| `loading` | footer 按钮禁用 + 保存按钮显示转圈；**关闭按钮隐藏，ESC 与遮罩同步失效**；内容区降透明度并禁用交互 |
| `beforeClose` | 支持 `false` / `true` / 回调式 `void` / `Promise<boolean>`；`Promise` reject 视为不关闭且不抛出 |

**四条关闭路径语义完全一致**（关闭按钮 / ESC / 遮罩 / 取消）：都经 `beforeClose` 拦截，都先派发 `update:modelValue(false)`，`close` 恰好派发一次，`cancel` 只在关闭被允许后派发。业务**不要**自己再写一层「关闭前确认」。

`confirm` **只派发意图，不自动关闭** —— 由业务在保存成功后自行置 `visible = false`。

---

## 6. 复用公共样式

- **不要**在业务侧重写 `.el-drawer__header` / `__body` / `__footer`。需要改内边距、背景、边框时，在抽屉根节点挂业务 class，再用 `:deep()` 覆盖 `.lq-drawer__head` / `__body` / `__foot`：

  ```css
  /* 与弹窗同一套写法，append-to-body 的 Teleport 不会让 :deep 失效 */
  .tracking-detail-drawer :deep(.lq-drawer__head) { background: var(--color-surface); }
  ```

  （B4 实测：class 经 attrs 落到 `el-drawer` 根节点，内部结构是其后代，`:deep` 后代选择器照常命中。）

- 深色主题自动生效：`drawer-layout.css` 只使用 `tokens.css` 的设计变量，不含任何十六进制颜色字面量（有静态契约测试守护）。
- 内容区默认 `padding: 20px 24px`（窄屏 16px）。需要内部双栏/内部滚动时传 `:scrollable="false"`，body 会变成 flex 容器，子级 `flex:1 / min-height:0` 即可生效。

---

## 7. 什么时候允许保留原生 `el-drawer`

以下情况**不算违规**，可继续使用原生：

| 场景 | 说明 |
|---|---|
| 需要动态开关 `append-to-body` | `LqDrawer` 恒定 Teleport 到 body（`H3LedgerDetailDrawer` 属此类） |
| 依赖 `el-drawer` 的 `resizable` 拖拽改宽 | 组件未封装，但可通过 attrs 透传；若交互较深可保留原生 |
| 头部/底部结构与三段式差异过大 | 评估后 `show-header="false"` 仍不够时可保留 |
| 第三方组件内部自带的抽屉 | 无法替换 |
| 高风险复杂页面，改造收益为负 | 由迁移批次按页评估决定 |

**判定顺序**：先试 `LqDrawer` → 不行就用 `show-header="false"` + `#footer` 自绘 → 仍不行才保留原生，并在代码里写一句注释说明原因。

---

## 8. 未来迁移批次（18 个原生抽屉）

> 本轮（B5-A）**不迁移任何业务页面**，仅提供分组建议。

### 第 1 批 · 低风险（8 个，标准详情，无 footer 或简单 footer）

| 文件 | 现状 |
|---|---|
| `components/production/CoverageBreakdownDrawer.vue` | `min(900px,94vw)`、无 footer、`:model-value` |
| `components/production/ProductBenchmarkSamplesDrawer.vue` | `min(1100px,92vw)`、无 footer |
| `components/production/ProductOrderHistoryDrawer.vue` | `min(1080px,94vw)`、无 footer |
| `apps/.../settlement/cashflow/CashflowReviewDrawer.vue` | `86%`、无 footer |
| `components/reimbursement/ReimbursementLedgerPanel.vue` | `min(920px,96vw)`、`#header` 自绘 |
| `apps/.../governance/H3ImportCenterView.vue` | `min(980px,94vw)`、`#footer` |
| `apps/.../production/ProductInventoryView.vue` | `min(980px,94vw)`、`#footer` |
| `components/reimbursement/DingtalkSyncPanel.vue` | `min(880px,94vw)`、rtl、destroy-on-close |

**风险**：只需把 `size=` 改名为 `width=`，`:model-value + @update:model-value` 可直接换 `v-model`。

### 第 2 批 · 中风险（5 个，编辑表单 / 动态尺寸）

| 文件 | 现状 | 关注点 |
|---|---|---|
| `apps/.../system/RolePermissionView.vue` | `920px`、`#footer`、destroy-on-close | 新建/编辑共用抽屉，需接 `dirty` + `beforeClose` |
| `components/production/ManufacturingTaskConfigPanel.vue` | `min(620px,100vw)`、`#footer` + `#header` | 嵌套在弹窗里，需验证 `z-index` |
| `components/production/ProductInventoryDetailDrawer.vue` | `min(880px,92vw)`、rtl | — |
| `components/production/TrackingSourceDetailDrawer.vue` | **动态 `size`** | 改用动态 `width` |
| `apps/.../commission/MonthlyCommissionView.vue` | `50%`、`#footer`、destroy-on-close | 含 `el-drawer__body` 样式覆盖 |

**风险**：`#footer` 里的业务按钮需逐个核对；有 `el-drawer__body` 覆盖样式的要改成 `:deep(.lq-drawer__body)`。

### 第 3 批 · 高风险（5 个，复杂工作台 / 自绘头部 / 深层下钻）

| 文件 | 现状 | 关注点 |
|---|---|---|
| `components/production/TrackingDetailDrawer.vue` | `min(1120px,98vw)`、`header-class`、`append-to-body`、destroy-on-close、`#footer` | 内部嵌套多个 `LqDialog` 下钻，需逐条回归 |
| `apps/.../production/ProductionTrackingAnalyticsDrawer.vue` | `90%`、`with-header=false`、内含表格+分页 | 改用 `show-header=false` + `width="90%"` |
| `apps/.../production/ProductionTrackingNodeAnalyticsDrawer.vue` | 同上 | 同上 |
| `apps/.../production/ProductionTrackingWeeklyReviewDrawer.vue` | 同上 | 同上 |
| `components/h3/H3LedgerDetailDrawer.vue` | `50%`、rtl、**动态 `append-to-body`**、`#footer` | 动态 Teleport 属合理例外，建议保留原生或先确认需求 |

**风险**：自绘头部要逐个对齐；下钻链路（抽屉里开弹窗、弹窗里再开抽屉）必须人工回归层级与焦点。

### 迁移通用步骤

1. `<el-drawer>` → `<LqDrawer>`，`size="X"` → `width="X"`
2. `:model-value + @update:model-value` → `v-model`
3. `#footer` 内的按钮保留；若原本是「取消 / 保存」标准形态，可删除插槽改用默认 footer + `@confirm`
4. 页面内 `el-drawer__body` / `el-drawer__header` 的样式覆盖 → `:deep(.lq-drawer__body)` / `:deep(.lq-drawer__head)`
5. 人工回归：打开 / 关闭 / ESC / 遮罩 / 脏数据拦截 / 保存中关闭 / 嵌套层级 / 窄屏

### 迁移前置条件

- 每个抽屉页面的业务负责人确认关闭行为（尤其是 `beforeClose` 拦截的必要性）
- 高风险页面需在非生产环境完整走查一遍下钻链路

---

## 9. 组件边界：什么时候不该往 LqDrawer 里加东西

**加**：影响所有抽屉视觉一致性的能力（新的尺寸档位、头部/底部结构变体、通用 loading / dirty 表现）。

**不加**：

- 某个页面独有的业务流程（如「保存跟单」按钮的具体逻辑）
- 业务专属的筛选器、导出、打印等按钮 —— 用 `#actions` / `#footer`
- 第二套抽屉组件（`LqPanel` / `BaseDrawer` 等）
- 把 `suppressNextClose` 式的兼容分支再复制到抽屉里 —— 抽屉已用 `closeEmitted` 标记实现等价且更稳的语义，**不要**再改回 LqDialog 的写法
