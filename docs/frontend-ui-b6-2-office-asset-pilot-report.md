# B6-2：OfficeAssetView 综合页面试点

> 目标：以物品资产页为综合试点，检查公共组件在**复杂业务页面**（统计卡 + 扫码工作台 + 批量 + 4 弹窗 + 标签打印）中的实际复用效果。不是重新设计页面，而是消除已证实的重复实现、完善公共规范接入，并保持全部业务功能不变。
> 上游：P0-A ~ B5-C、B6-1（`docs/frontend-ui-b6-1-standard-list-pilot-report.md`）
> 生成日期：2026-10-28

---

## 1. 试点基准

```
HEAD          ee359850cd870c30406a907fc42236af4835f783   （= main）
origin/main   1999e3c51e515c283acd1e12dbcd28cd5cf4a4b3   （main 领先 1 个未推送提交）
工作区        101 项
```

**并行任务占用情况（只读核对，未触碰）**

| 项 | 归属 |
|---|---|
| `ee359850`（B5-C 之后新增的本地提交，4 个 production 抽屉文件，未推送） | 并行 tracking 任务 |
| `frontend/src/api/website/*`、`apps/archive/navigation.ts`、`routes.ts`、`Website*View.vue`（M/新增） | 并行官网任务 |
| `frontend/src/components/production/TrackingNodeAnalytics.vue`（M） | 并行 tracking 任务 |
| `docs/production-tracking-*`、`docs/website-content-decoupling-*` | 并行任务文档 |
| `tools/_verify_node_*.java`（未跟踪） | 并行 tracking 任务 scratch |

**B6-1 成果仍在工作区未提交**（OperationLogView.vue 10 增 4 删 + e2e 三文件 + 契约一文件），本轮未触碰其内容。

**试点页面冲突检查**：`git status` 中无 `OfficeAssetView`；`ee359850` 只含 4 个 production 文件 → **页面无并行改动，可安全试点**。

---

## 2. 页面现有公共组件使用情况

`OfficeAssetView.vue`（改造前 1008 行）已接入的公共资产——**统一度是全仓业务页面中最高的之一**：

| 类别 | 已接入 |
|---|---|
| 布局 | `ListPageLayout`（`#summary` / `#toolbar` / `#pagination` 三插槽） |
| 统计 | `MetricGrid` + `MetricCard` |
| 工具栏 | `SearchToolbar`（批量全家桶：`batch-enabled` / `batch-mode` / `selected-count` / `models-count` / `is-all-selected` / 5 个批量事件 + `batch-entry-visible=false`） |
| 筛选回显 | `ActiveFilterChips`（含单个移除与全部清除） |
| 状态 | `DataState` 五态 + `#emptyActions` |
| 表格/分页 | `LqTable`（`@sort-change` / `@row-click` / `@row-dblclick`）+ `LqPagination` |
| 标签 | `StatusTag`（状态列） |
| 弹窗 | `LqDialog` × 4（form/md、detail/md、form/sm、workspace/lg）+ `FieldBatchEditModal` |
| 详情 | `LqDescriptions`、`LqTimeline` |
| 表单控件 | `LqDatePicker`、`UnitField` |
| composables | `useListPagination`、`useBatchSelection`、`useCurrentUser`、`useToast`、`askConfirm` |

**结论：本页面不存在手抄分页栏、自建批量栏、自绘空态等「重复实现」型问题**——它的问题全部属于「已接入但接线不完整」与「样式层少量重复」，见 §3。

### 2.1 审计后确认**没有**缺陷的项（避免误报，逐项有证据）

| 检查项 | 结论 | 证据 |
|---|---|---|
| 关键词防抖 | ✅ 正常 | e2e：连续输入 6 次 → 列表请求恰好 +1 |
| 筛选后分页重置 | ✅ 正常 | `handleToolbarFilterChange` 有 `resetPage()`；e2e 断言 `page=0` 通过 |
| 排序触发重复请求 | ✅ **无**此缺陷 | e2e：点击表头 → 请求恰好 1 次，`sort=assetCode`（`default-sort` 经 `$attrs` 透传未引发回写重放） |
| 翻页重复请求 | ✅ 无 | e2e：下一页 → 恰好 1 次请求 |
| `clear-visible` 是否需要外传 | ✅ 不需要 | 本页**没有** `fields` 之外的筛选维度（B6-1 的日期区间情形在此不存在），工具栏自算完整 |
| `models-count` 取值 | ✅ 符合全仓约定 | 9 个页面全部传「当前页列表长度」（`list.length` / `accounts.length` / `assets.length`），非本页独有偏差 |
| `ActiveFilterChips` 用默认插槽而非 `#filterChips` | ✅ 全仓一致 | `#filterChips` 公共插槽**全仓 0 使用**，8+ 页面都放默认插槽（记录为 B7 观察项，不单方面改） |
| 批量进入/退出与选中状态 | ✅ 正常 | 勾选即入批量、清空即退出；e2e 全选/单选两条均通过 |

---

## 3. 发现的问题及证据

### F1（布局）页面头部整块未渲染 —— 与 B6-1 同源的系统性缺陷

`ListPageLayout` 的 `showHeader` 默认 `false`，页面传了 `title="物品资产"` / `description` 但未传 `show-header` → 头部（含 eyebrow 位）从未渲染。

**证据**：改造前 e2e 布局几何探针失败 —— `.page-surface-header` 元素不存在（`Expected: visible / Error: element(s) not found`）；改造前截图 `01-default-light-1440.png` 页面直接从统计卡开始。

### F-A（搜索）关键词搜索不重置页码 —— 全仓唯一一处

`@search="loadAssets"` 直绑加载器，**没有** `pagination.resetPage()`。用户翻到第 3 页后搜索，请求仍带 `page=2`；若命中结果不足 3 页则直接看到「筛选无结果」。

**证据（请求侧，不受 UI 影响）**：改造前 e2e 断言失败 —— 翻到第 3 页后输入关键词，最后一次列表请求 `page=2`（应为 0）。

对照：其余 10 个带搜索的页面全部在搜索处理器中 `resetPage()`（`applyFilters` / `searchEntries` / `handleKeywordChange` / `resetAndLoad`…），本页是唯一例外。

### F-B（弹窗）「编辑 → 新增」表单字段残留

`emptyForm()` 只重置 4 个字段（name/category/assetCode/status），`Object.assign(form, emptyForm())` 后其余字段保留上一次编辑的值。

**证据**：改造前 e2e 实测，编辑「示例资产 001」后点新增，表单残留 **12 个字段值**：

```
示例品牌 001 / M-001 / SN-001 / 行政部 / 张伟 / A 栋 3 楼 / 2026-01-15 / 3200.00 / 示例供应商 / 2029-01-15 …
```

### F-C（权限）批量操作未按权限门控

`batch-enabled` 恒为 true：无 `officeAsset.manage` 权限的用户勾选任意行即可进入批量模式，且批量栏会渲染「批量编辑 / 批量删除」按钮（`SearchToolbarBatchBar` 按监听器存在与否决定渲染，不含权限判断）→ 点了只会收到 403。

**证据**：改造前 e2e「无权限」用例失败 —— 勾选后 `.search-toolbar--batch` 仍然出现。

**仓库先例**：`CustomerView.vue` 已有 `:batch-enabled="canManageCustomer"` 的正确写法，本页未跟随。

### F-D（样式）重复实现与死代码

| # | 问题 | 证据 |
|---|---|---|
| 1 | `.asset-toolbar` 的「表面卡片」四件套（`background/border/radius/shadow`）与 `SearchToolbar` 自身 `.search-toolbar` 的同名四项**逐项相同** | 两份源码逐行对照（`SearchToolbar.vue` L495-498） |
| 2 | `.asset-toolbar :deep(.el-select) { width: 140px }` 用 CSS 重写公共组件内部宽度，而 `SearchToolbar` 本就提供每字段 `width` props | `ToolbarField.width` 存在且直接映射 `--search-toolbar-field-width` |
| 3 | `@media` 内的 `.asset-stats` 规则是**死代码**——模板中不存在该类（统计卡由 `MetricGrid` 渲染） | 模板全文检索 0 命中 |
| 4 | `operation-page-layout__station` 是**幻影公共类**——全仓不存在 `operation-page-layout` 公共样式，该类仅本页模板与 scoped 中自产自销 | `git grep` 全仓仅 2 处、均在本文件 |
| 5 | `font-size: 12px`、`line-height: 1.6` 未走令牌（`--fs-xs: 12px`、`--lh-loose: 1.6` 均存在） | `tokens.css` L271/L299 |
| 6 | `.office-assets-page { width: 100% }` 冗余——`.list-page-layout` 根已有 `width: 100%` | `ListPageLayout.vue` L189 |

---

## 4. 实际整改文件和代码差异

**生产代码只改 1 个文件**：`frontend/src/apps/archive/views/product/OfficeAssetView.vue`（**55 增 / 32 删**）。零接口变化、零数据结构变化、零保存逻辑变化、零打印协议变化、零分页 0/1 基协议变化。

### 4.1 模板（4 处）

```diff
     title="物品资产"
     description="统一管理资产档案、扫码流转和标签打印。"
+    show-header
     :busy="loading || scanning"

-    <section class="operation-page-layout__station scan-panel" aria-label="扫码工作台">
+    <section class="scan-panel" aria-label="扫码工作台">

-        class="asset-toolbar"
         search-placeholder="搜索编号、名称、序列号、保管人"
-        batch-enabled
+        :batch-enabled="canManageOfficeAsset"
-        @search="loadAssets"
+        @search="handleKeywordSearch"

-        <el-table-column width="46" align="center">
+        <el-table-column v-if="canManageOfficeAsset" width="46" align="center">
```

### 4.2 脚本（3 处）

```diff
 const toolbarFields: ToolbarField[] = [
-  { key: 'category', label: '分类', options: categories },
-  { key: 'status', label: '状态', options: statuses }
+  // 字段宽度走 SearchToolbar 的 width props（原先靠 :deep 覆盖公共组件内部，等价但属重写）
+  { key: 'category', label: '分类', options: categories, width: 140 },
+  { key: 'status', label: '状态', options: statuses, width: 140 }
 ]

+/** 关键词搜索：与其它筛选入口一致，先回到第 1 页再请求（否则会停留在越界页码） */
+function handleKeywordSearch() {
+  pagination.resetPage()
+  loadAssets()
+}
+
 const emptyForm = (): Partial<OfficeAsset> => ({
   name: '', category: '', assetCode: '', status: '在库',
+  brand: '', model: '', serialNumber: '', department: '', custodian: '',
+  location: '', supplier: '', notes: '',
+  purchaseDate: undefined, purchasePrice: undefined, warrantyUntil: undefined
 })
```

> 日期/金额字段用 `undefined`：JSON 序列化自动省略，与「字段不存在」的旧payload形态等价，**不改变提交给后端的数据结构**。

### 4.3 样式（6 处，全部零视觉变化或有注释的例外）

- 删 `.office-assets-page { width: 100% }`（冗余）；
- `.scan-panel` 合并幻影类的 `min-width/margin-bottom`，删除与 `.search-toolbar` 重复的 `.asset-toolbar` 表面规则；
- 删 `:deep(.el-select) width`（改走 props）；
- `12px` → `var(--fs-xs)`、`1.6` → `var(--lh-loose)`（等值）；
- 删死规则 `.asset-stats`；
- 为标签预览的 3 项硬编码与 1000px 断点**补注例外理由**（见 §9）。

### 4.4 新增验收资产（3 个文件，不触及任何业务逻辑）

| 文件 | 说明 |
|---|---|
| `frontend/e2e/fixtures/office-asset.html` + `office-asset-harness.ts` | 隔离夹具：真实页面 + ElementPlus + 项目样式；权限经「本地 token + `/api/auth/me` 拦截」注入（仅 `officeAsset.manage`） |
| `frontend/e2e/office-asset-standard-page.spec.ts` | **25 个用例**：9 视觉/几何 + 6 搜索筛选分页 + 3 批量权限 + 7 弹窗扫码；请求侧断言（page/keyword/category/sort 参数与请求次数） |

不连接数据库；保存/批量删除/登记/打印接口全部被 `page.route` 拦截返回成功，**无任何真实写入**。

---

## 5. 重复代码减少情况

| 项 | 改造前 | 改造后 |
|---|---|---|
| 页面 scoped 样式 | 180 行 | 172 行（−8 行死代码/重复；+10 行例外理由注释） |
| 与公共组件重复的外观规则 | 2 处（`.asset-toolbar` 表面四件套、`:deep` 宽度覆盖） | 0 |
| 死代码 | 2 处（`.asset-stats`、`.office-assets-page` width） | 0 |
| 幻影公共类名 | 1 处（`operation-page-layout__station`） | 0 |
| 死参数 | 2 个（title/description 不渲染） | 0 |
| 未令牌化基础值 | 2 处（12px / 1.6） | 0（另有 5 处标签预览值按例外登记） |

页面行数 1008 → 1031（+23：+19 行 emptyForm 完整化与修复注释、+6 行搜索处理器、−2 行删除）——**行数不是目标**，消除的是「死参数 + 字段残留 + 越界页码 + 无权限可见的批量入口 + 样式重复」。

---

## 6. 改造前后视觉证据

| 阶段 | 目录 | 张数 |
|---|---|---:|
| 改造前 | `frontend/playwright-report/b6-2/before/` | 14 |
| 改造后 | `frontend/playwright-report/b6-2/after/` | 17 |

覆盖：默认态（浅/深 × 1440/420）、加载、空态、筛选无结果、错误态、几何探针、无权限态、新增/编辑残留/详情/打印设计器（桌面+窄屏+深色）、扫码登记。

**改造前缺 3 张**（09 几何探针 / 10 无权限 / 12 编辑后新增）——**缺失本身就是缺陷证据**：这三个用例在断言处失败，截图未能生成。

### 6.1 布局几何实测（改造后，真实 Edge，1440×1000，`geometry.json`）

| 区块 | top→bottom | 关键值 |
|---|---|---|
| `.page-surface-header` | 0 → 82 | padding `14px 18px`、radius `10px`（B5-B 共享头部，此前整块缺失） |
| 统计卡 `__summary` | 96 → 215 | MetricGrid 五卡 |
| `.search-toolbar` | 229 → 279 | padding `8px 12px`、radius `8px` |
| `.scan-panel` | 293 → 372 | padding `18px`、radius `8px`、主色边框 |
| `.el-table` | 387 → 1715 | 宽 1438 |
| `.list-page-layout__pagination-bar` | 1716 → 1757 | padding `4px 8px`、右对齐 |

区块间距 14–15px 一致（`--space-section-gap` 系），表格与分页条衔接正常（1715 → 1716）。头部引入使后续区块整体下移 82px + 区块间距，除此之外**无任何布局位移**（已删除的样式均与公共组件逐项等值）。

### 6.2 视觉结论

- 浅/深主题下头部、统计卡、工具栏、扫码台（主色描边）、表格、状态标签、分页全部随主题正确渲染（已逐张人工核验 01/02 两张关键图）；
- 420px 窄屏：工具栏纵向铺满、扫码台单列、标签设计器单列（1000px 业务断点生效）；
- 打印设计器预览恒为白底纸面（深色主题下同样白色，符合物理标签语义）。

---

## 7. 核心业务行为回归结果

e2e 25 项（改造前 21 通过 / 4 失败 → **改造后 25 全通过**）：

| 能力 | 用例 | 结果 |
|---|---|---|
| 数据加载 | ready 态、行渲染、0 基参数 | ✅ |
| 搜索 | 防抖单请求、**页码重置（修复项）**、关键词进请求 | ✅ |
| 筛选 | 分类变更带参 + 回第 1 页、chip 单个移除 | ✅ |
| 排序 | 单次请求、`sort=assetCode`、无重复请求 | ✅ |
| 分页 | 单次请求、页码正确 | ✅ |
| 批量 | 全选进/出、单选进/出、**权限门控（修复项）** | ✅ |
| 弹窗 | 新增空表单、**编辑后新增无残留（修复项）**、详情+流转记录、打印设计器预览尺寸（70×40mm×5 倍 = 350×200px 精确断言）、窄屏、深色 | ✅ |
| 扫码 | 扫描 → 登记弹窗 | ✅ |
| 权限 | 无 `officeAsset.manage`：无新增按钮、**无选择列、无批量模式（修复项）** | ✅ |

**保持不变**：接口与请求参数结构、批量删除/登记/保存/打印协议（拦截层断言 URL 与方法未变）、分页 0/1 基协议、`officeAsset.manage` 权限语义（只收紧了「无权限者不可见批量入口」，有权限者的全部能力不变）。

---

## 8. 自动化测试及构建结果

| 命令 | 结果 |
|---|---|
| e2e（本页 25 用例，改造前） | 21 passed / **4 failed**（失败即 §3 缺陷证据） |
| e2e（本页 25 用例，改造后） | **25 passed / 0 failed** |
| `npx vitest run src/components/common src/composables/list src/constants` | **24 文件 / 301 全通过**（B5-C 基线 294 + B6-1 契约 7） |
| `npx vitest run --exclude '**/textUtils.test.ts'`（77 文件组合） | **703 passed / 2 failed**（2 条为 tracking 既有失败，见下） |
| `npm run typecheck` | **0 错误** |
| `npm run lint` | **0 error** |
| `npx vite build --outDir %TEMP%\b62build --emptyOutDir` | **✓ 3057 modules transformed；✓ built in 16.18s**；524 产物、index.html 存在 |

### 8.1 两条 tracking 失败的归因（不修改、不宣称全绿）

`TrackingAnalyticsDrilldownCells.test.ts` 2 条失败（`'120 / 一出四'` vs `'120 / 圆形 / PP / 一出四'`）：测试由 `5bc9a80a` 引入、被测组件最后由 tracking 提交 `4c6d5432` 修改（B5-C 已固化证据链）。本轮未触碰 `components/production/**`，**回退本轮改动该失败依旧存在**。全量绿线口径：**705 条中 703 通过，2 条已知非 UI 归属失败**。

### 8.2 Vitest 全量 78 文件环境异常的复现记录（如实报告）

```
npx vitest run
→ Test Files 78 failed (78) / Tests no tests
→ 全部文件收集期报 "Vitest failed to find the runner"（含纯 util 用例）
npx vitest run --exclude '**/textUtils.test.ts'   （77 文件）
→ 703 passed / 2 failed（同一代码、同一测试集合去掉一个文件即恢复）
```

与 B6-1 报告 §7.3 的判别结论一致：**崩溃由「测试文件总数达到 78」触发的环境资源上限导致，与测试内容无关**（B6-1 已用「交换被排除文件」双向往返证明）。本轮未新增任何 vitest 测试文件，故以 77 文件组合 + 公共层子集作为门禁口径，并在此记录复现命令。**建议在机器空闲时复跑一次全量确认。**

### 8.3 本轮测试基建的两个自纠（诚实记录）

1. 首版夹具 mock 未在分页响应中回传 `number`，导致翻页在测试中实际无法前进、F-A 证据落在准备步骤而非真正断言——已修正为「响应回传请求页码」（与真实后端一致），并用**受控往返**（临时回退 `@search` 绑定 → 单跑该用例取到 `page=2` 的失败证据 → 恢复修复）重新取证。全程未使用 stash/reset。
2. 运行 Playwright 时其内部清理 `test-results/`（707 个文件）触发了安全删除审批拦截（命令未执行）。改用 `--output` 指向独立临时目录绕开批量清理，**未删除任何文件**。`test-results/` 中的历史 trace 残留留待人工清理。

---

## 9. 合理保留的业务例外（全部已写进代码注释）

| 项 | 理由 |
|---|---|
| 标签预览白底（`--color-text-on-primary`） | 物理标签纸面，不随主题变化 |
| 标签预览 px 字号（22/13/10px） | 对应打印字号（9pt/15pt/6.5pt）的 mm×5 倍预览换算值，非界面字号语义 |
| 标签预览投影 `0 3px 14px rgba(0,0,0,.18)` | 「纸张浮起」模拟；走 `--shadow-*` 会在深色下变成发光，反而失真 |
| `@media (max-width: 1000px)` | 标签设计器 340px 设置栏 + 预览两栏布局，767px 才折叠会挤压预览（与公共组件 767/768/1200 不同，属业务断点） |
| 扫码工作台自带卡片外观 | 业务专属区块，无公共等价物；且主色描边是「工作台」的视觉身份 |
| 扫码台位于工具栏之下（body 默认插槽内） | 现状 DOM 顺序；公共布局无「工作台位」插槽，重新排序属布局重设计，超出本阶段边界（记录为 B7 议题） |
| `ActiveFilterChips` 走默认插槽 | 与其余 8+ 页面一致；`#filterChips` 公共插槽全仓 0 使用 |
| `models-count` 传当前页长度 | 全仓 9 页统一约定 |

---

## 10. B7 全站推广建议

1. **【需决策】`showHeader` 默认值**：B6-1（12 页全部死参数）+ 本页再次证实。12 页逐个补 `show-header` 或改默认值，需产品/设计拍板后一次性执行。
2. **「搜索不重置页码」全仓排查（高优先）**：本页不是孤例——`WebsiteInquiryView.vue` 的 `@search="loadInquiries"` 同样直绑加载器且 `loadInquiries` 无 `resetPage()`（本轮已核实源码，未修改）。建议以「`@search` 直绑 loader」为特征码全仓扫描，逐页修复。
3. **表单弹窗「完整复位」模式**：`emptyForm()` 只重置部分字段导致残留，属易复发模式。仓库已有 `useModalForm` composable，建议 B7 评估把「打开即全字段复位」收敛进去，并巡检其它 `Object.assign(form, emptyForm())` 型页面。
4. **批量权限约定固化**：以 `CustomerView` / 本页为范本，把「`batch-enabled` 必须绑定管理权限、选择列随权限显隐」写入 `SearchToolbar` 使用规范；同时 `SearchToolbarBatchBar` 的「按监听器渲染按钮」机制可考虑增加权限语义（公共组件改动，需单独任务）。
5. **`#filterChips` 插槽**：全仓 0 使用，建议要么在规范中明确推荐迁移，要么在 B7 后评估删除该插槽。
6. **业务断点登记**：`1000px`（本页）等非标准断点建议进入视觉规范 §5 允许清单并注明理由，避免后续被机械替换成 767px 造成挤压。
7. **页面级「表面卡片」工具类评估**：扫码台/统计卡等业务区块各自维护 `background/border/radius/shadow` 四件套，B7 可评估抽一个 `.surface-panel` 公共类（需先统计使用面，避免制造无人引用的令牌/类）。
8. **环境项**：vitest 78 文件上限（§8.2）需在机器空闲时复跑确认；Playwright `test-results/` 历史残留需人工清理（安全删除拦截，本轮未动）。

---

## 11. 变更边界核对

- 只改了 OfficeAssetView + 新增 e2e 夹具/用例 + 本报告 —— ✅
- 未修改任何公共组件（`SearchToolbar` / `ListPageLayout` / `LqDialog` / `LqTable` 等零改动）—— ✅
- 未修改其他业务页面（含发现同类缺陷的 `WebsiteInquiryView`，仅记录）—— ✅
- 未修改生产跟单、财务流水、官网等并行模块 —— ✅
- 未修改后端、数据库、Flyway —— ✅
- 未改接口、数据结构、保存逻辑、权限语义、打印协议、分页 0/1 基协议 —— ✅
- 未执行 `git add` / `commit` / `push` / `reset` / `clean` / `stash` —— ✅
- 未删除断言、未扩大 Mock 掩盖缺陷（mock 修正使断言**更严格**）—— ✅
- 未清理、未覆盖任何并行任务文件（含 `tools/` scratch 与 `test-results/` 残留）—— ✅

**完成 B6-2 后停止，未进入 B7，未提交推送。**
