# LQXM 前端统一 UI 体系 B7-B1a：OfficeAssetView 表单生命周期与数据正确性修复

- 阶段：B7-B1a（P0 功能缺陷，基础设施之后、视觉迁移之前）
- 执行日期：2026-10-29
- 修改文件：`frontend/src/apps/archive/views/product/OfficeAssetView.vue`（唯一业务文件）
- 新增测试：`frontend/e2e/office-asset-form-lifecycle.spec.ts`（6 个场景）、`frontend/src/components/common/__tests__/OfficeAssetFormLifecycle.contract.test.ts`（8 条契约）
- 结论：**缺陷已证实、已修复、已回归验证**；全量门禁通过，Tracking 两条既有失败独立登记未代修。

---

## 1. 审计基准

| 项 | 值 |
|---|---|
| HEAD | `433cc563808d09a260f7ea56b7f1ad77d8dd8aac` |
| 当前分支 | `main` |
| 本地 `main` | `433cc563`（与 HEAD 一致） |
| `origin/main` | `1999e3c5`（本地领先，未推送） |
| 最后提交 | `433cc563` 2026-10-29 10:18:48 +0800 「周复盘未完工清单按紧急程度排序…」 |
| `frontend/` 工作区变更 | 32 项（含并行任务改动） |
| Node / npm | v24.18.0 / 11.16.0 |
| vitest / vite（根） / vite（嵌套） | 4.1.9 / 5.4.21 / 8.1.3 |
| `@vitest/runner` / `@vitest/mocker`（嵌套） | 4.1.9 / 4.1.9 |

### 测试执行约束（沿用 B7-B0 结论）

所有测试命令一律从**大写盘符 cwd** 启动：

```bat
cd /d C:\Users\20676\source\LQXM\frontend && node ./node_modules/vitest/vitest.mjs run
```

小写盘符 cwd 会触发 Vitest 收集期崩溃（`@vitest/runner` 模块双实例），与文件数量无关。

### B6-2 未提交改动保留情况

`git diff --stat` 显示 `OfficeAssetView.vue` 由 B6-2 的 55 增/32 删 变为 **121 增/35 删**，即本次在原改动基础上追加 66 增/3 删。B6-2 的以下成果**全部完整保留**：

- `show-header`
- `handleKeywordSearch`（搜索重置页码）
- `emptyForm()` 覆盖 15 个可编辑字段
- `:batch-enabled="canManageOfficeAsset"` + 选择列随权限显隐
- 样式去重与令牌化

---

## 2. 实际缺陷与复现证据

### 2.1 缺陷一（P0）：编辑 → 新建 时历史字段残留进入新建请求载荷

**机理**：两处 `Object.assign` 缺陷叠加。

```
① openEdit: Object.assign(form, asset)
   → 资产对象上的全部属性被搬进 form，包括 id / lastInventoryAt / createdAt / updatedAt
② openCreate: Object.assign(form, emptyForm())
   → Object.assign 只覆盖已有 key、不删除多余 key
   → ① 带进来的非表单字段一直挂在 form 上，下一次保存被一并 POST 出去
```

**复现证据（修复前 e2e 实跑）**：

```text
场景 2 · 编辑 A→关闭→新增→保存
Error: 场景 2：新建请求载荷不应包含 id
Received array: ["name","category","assetCode","status","brand","model",
                 "serialNumber","department","custodian","location",
                 "supplier","notes",
                 "id","lastInventoryAt","createdAt","updatedAt"]
```

`id`、`lastInventoryAt`、`createdAt`、`updatedAt` 四项残留全部出现在 `POST /api/office-assets` 载荷里。

### 2.2 缺陷二（P1，B7-A P1-6）：校验错误态跨次打开遗留

`LqDialog.vue:197` 的 `destroyOnClose: false` 使弹窗关闭后 `el-form` 实例不销毁，上次校验失败的红字错误态保留到下次打开。

**复现证据（修复前 e2e 实跑）**：

```text
场景 6 · 表单校验失败→关闭→重新打开
Error: 重新打开不应遗留上一次的校验错误态
Expected: 0
Received: 2
```

### 2.3 逐项验证结果（修复前 → 修复后）

| # | 场景 | 修复前 | 修复后 |
|---|---|---|---|
| 1 | 新增→关闭→再次新增 | 通过 | 通过 |
| 2 | 编辑 A→关闭→新增（载荷） | **失败**（载荷含 id / lastInventoryAt / createdAt / updatedAt） | 通过 |
| 3 | 编辑 A→编辑 B | 通过 | 通过 |
| 4 | 编辑→新增→保存 | **失败**（载荷含历史资产主键） | 通过 |
| 5 | 保存失败→关闭→重新打开 | 通过 | 通过 |
| 6 | 表单校验失败→关闭→重新打开 | **失败**（遗留 2 处错误态） | 通过 |

> 说明：场景 1/3/5 修复前即通过——界面上的 input 值确实被 `emptyForm()` 覆盖了（B6-2 的成果），但 `id` / `lastInventoryAt` 在表单里**没有对应控件**，界面断言天然抓不到；只有直接断言请求载荷才能发现。这正是本次必须补载荷级回归测试的原因。

## 2.4 后端实际接收与持久化行为（已核对源码）

`OfficeAssetController` 用**实体** `@Valid @RequestBody OfficeAsset asset` 直接接收：

```java
@RequirePermission("officeAsset.manage")
@PostMapping public OfficeAsset create(@Valid @RequestBody OfficeAsset asset) { return service.create(asset); }
```

`OfficeAssetService.create`：

```java
@Transactional
public OfficeAsset create(OfficeAsset asset) {
    asset.setId(null);                                   // 仅兜住 id
    asset.setAssetCode(uniqueCode(asset.getAssetCode()));
    if (blankToNull(asset.getStatus()) == null) asset.setStatus("在库");
    OfficeAsset saved = repository.save(asset);
    ...
}
```

| 字段 | 后端行为 | 是否会造成数据库污染 |
|---|---|---|
| `id` | `setId(null)` 强制清空 | **否**（后端兜住，属防御性冗余） |
| `createdAt` | `@CreationTimestamp` 插入时覆盖 | **否** |
| `updatedAt` | `@UpdateTimestamp` 覆盖 | **否** |
| `lastInventoryAt` | **无任何保护**（`entity:48` 普通列，无审计注解） | **是** —— 新资产会继承上一条资产的最后盘点时间 |
| `assetCode` | `uniqueCode()` 校验唯一；为空则自动生成 | 否（但编辑态输入框已 disabled） |
| 其余 14 个可编辑字段 | 由 `copy()` 在 update 路径复制 | 否（update 路径不受影响） |

**结论**：这不是"疑似污染"，而是**已确认的真实数据正确性缺陷**——`lastInventoryAt` 会被原样持久化。`id` / `createdAt` / `updatedAt` 属"界面状态缺陷"（载荷污染但后端兜住），不应误报为数据库污染。

---

## 3. 完整表单字段生命周期核查表

`OfficeAsset` 类型共 19 个字段，其中 15 个可编辑、5 个由后端维护（有 1 项重叠计数差异，见下表）。

| 字段 | 类型 | 可编辑 | 在 `emptyForm()` 中 | 修复前「编辑→新建」是否残留 | 后端是否兜住 | 判定 |
|---|---|---|---|---|---|---|
| `name` | string | 是 | 是 | 否 | — | 正常 |
| `category` | string | 是 | 是 | 否 | — | 正常 |
| `assetCode` | string | 是 | 是 | 否 | `uniqueCode()` | 正常 |
| `status` | string | 是 | 是 | 否 | — | 正常 |
| `brand` | string? | 是 | 是 | 否 | — | 正常 |
| `model` | string? | 是 | 是 | 否 | — | 正常 |
| `serialNumber` | string? | 是 | 是 | 否 | — | 正常 |
| `department` | string? | 是 | 是 | 否 | — | 正常 |
| `custodian` | string? | 是 | 是 | 否 | — | 正常 |
| `location` | string? | 是 | 是 | 否 | — | 正常 |
| `purchaseDate` | string? | 是 | 是（undefined） | 否 | — | 正常 |
| `purchasePrice` | number? | 是 | 是（undefined） | 否 | — | 正常 |
| `supplier` | string? | 是 | 是 | 否 | — | 正常 |
| `warrantyUntil` | string? | 是 | 是（undefined） | 否 | — | 正常 |
| `notes` | string? | 是 | 是 | 否 | — | 正常 |
| `id` | number | 否 | 否 | **是** | `setId(null)` | 界面状态缺陷（已修） |
| `lastInventoryAt` | string? | 否 | 否 | **是** | **无保护** | **数据正确性缺陷（已修）** |
| `createdAt` | string? | 否 | 否 | 是 | `@CreationTimestamp` | 界面状态缺陷（已修） |
| `updatedAt` | string? | 否 | 否 | 是 | `@UpdateTimestamp` | 界面状态缺陷（已修） |
| `deletedAt` | string? | 否 | 否 | 是 | 逻辑删除字段 | 界面状态缺陷（已修） |

### 三个关键入口在修复前后的实现

| 入口 | 修复前 | 修复后 |
|---|---|---|
| `openCreate` | `Object.assign(form, emptyForm())` | `resetForm(toFormState())` → 先删全部 key，再写默认值，再 `clearValidate()` |
| `openEdit` | `Object.assign(form, asset)` | `resetForm(toFormState(asset))` → 以 `emptyForm()` 打底 + 只取白名单字段 |
| `saveAsset` | `createOfficeAsset(form)` / `updateOfficeAsset(id, form)` | `const payload = toFormState(form)` 后再提交 |

---

## 4. 前端请求载荷与后端字段映射核对

### 4.1 新建路径（POST /api/office-assets）

| 项 | 修复前 | 修复后 |
|---|---|---|
| 载荷构成 | 整个 `form` 对象 | `toFormState(form)`，仅 15 个白名单字段 |
| 携带 `id` | 是（残留） | 否 |
| 携带 `lastInventoryAt` | 是（残留，**会被持久化**） | 否 |
| 携带 `createdAt` / `updatedAt` | 是（残留，后端覆盖） | 否 |
| `assetCode` | `''` → 后端自动生成 | 同（不变） |
| 空值语义 | 字符串字段 `''`，日期/金额 `undefined` | 同（**刻意保持不变**，避免把 `''` 改成 `null` 影响既有展示逻辑） |

### 4.2 编辑路径（PUT /api/office-assets/{id}）

后端 `update` 走 `copy()`，仅复制 14 个可编辑字段（不含 `assetCode`，因其唯一且不可改）。修复后载荷仅含白名单字段，**行为完全等价**，且不再发送无意义的 `id` / 时间戳。

### 4.3 权限

- 新建/编辑入口：`canManageOfficeAsset`（`officeAsset.manage`）——**未改动**
- 后端：`@RequirePermission("officeAsset.manage")` 保持生效——**未改动**
- 批量入口、行级操作、打印逻辑——**未改动**

---

## 5. 修复前后的行为对比

| 维度 | 修复前 | 修复后 |
|---|---|---|
| 新建请求载荷字段数 | 19（含 4 项残留） | 15（仅白名单） |
| `lastInventoryAt` 持久化风险 | **存在**（新建资产继承旧盘点时间） | 消除 |
| 编辑→编辑 字段隔离 | 依赖资产对象完整性 | 以 `emptyForm()` 打底，缺失字段回落默认值 |
| 校验错误态 | 跨次打开遗留 | 每次打开 `clearValidate()` |
| 打印 / 权限 / 业务校验 | — | 完全保持，未改动 |

### 修复前（基线证据，实测）

```text
Running 6 tests using 6 workers
  x  1 场景 1 · 新增→关闭→再次新增 …… page.goto 超时（首屏未预热）
  x  4 场景 4 · 编辑→新增→保存 …… 载荷含 id
  x  5 场景 5 · 保存失败→关闭→重新打开 …… page.goto 超时
  x  6 场景 6 · 表单校验失败→关闭→重新打开 …… 遗留 2 处错误态
  x  2 场景 3 · 编辑 A→编辑 B …… page.goto 超时
  x  3 场景 2 · 编辑 A→关闭→新增→保存 …… 载荷含 id / lastInventoryAt
```

> 注：首轮未预热 dev server，6 个用例全部因 `page.goto` 超时失败；预热后重跑，得到上表"修复前"的真实结果（场景 2/4/6 失败）。

### 修复后（实测）

```text
Running 31 tests using 8 workers   → 31 passed (43.2s)
```

其中包含既有 B6-2 视觉验收 25 条 + 本阶段新增 6 条。

---

## 6. 代码修改清单

### 6.1 修改：`frontend/src/apps/archive/views/product/OfficeAssetView.vue`

**① 新增可编辑字段白名单**（与 `emptyForm()` 同处声明）

```ts
EDITABLE_FIELDS = [
  'name', 'category', 'assetCode', 'status', 'brand', 'model', 'serialNumber',
  'department', 'custodian', 'location', 'purchaseDate', 'purchasePrice',
  'supplier', 'warrantyUntil', 'notes'
] as const,
```

**② 新增表单状态构造函数 `toFormState`**

```ts
function toFormState(source?: Partial<OfficeAsset> | null): Partial<OfficeAsset> {
  const next = emptyForm()
  if (!source) return next
  for (const key of EDITABLE_FIELDS) {
    if (key in source) (next as Record<string, unknown>)[key] = source[key]
  }
  return next
}
```

**③ 新增整体替换函数 `resetForm`**

```ts
function resetForm(next: Partial<OfficeAsset>) {
  for (const key of Object.keys(form) as (keyof OfficeAsset)[]) delete form[key]
  Object.assign(form, next)
  formRef.value?.clearValidate()
}
```

**④ `openCreate` / `openEdit` 改为走上述函数**

```ts
function openCreate() {
  editingId.value = null
  resetForm(toFormState())
  editorVisible.value = true
}
function openEdit(asset: OfficeAsset) {
  editingId.value = asset.id
  resetForm(toFormState(asset))
  editorVisible.value = true
}
```

**⑤ `saveAsset` 提交前做载荷映射**

```ts
// 只提交白名单字段：新建时不携带上一次编辑残留的 id / lastInventoryAt 等
const payload = toFormState(form)
const saved = editingId.value
  ? await updateOfficeAsset(editingId.value, payload)
  : await createOfficeAsset(payload)
```

### 6.2 新增测试

| 文件 | 内容 |
|---|---|
| `frontend/e2e/office-asset-form-lifecycle.spec.ts` | 6 个场景（含 `POST /api/office-assets` 载荷断言、权限断言），全部 `page.route` 拦截，不连库 |
| `frontend/src/components/common/__tests__/OfficeAssetFormLifecycle.contract.test.ts` | 8 条源码级契约，钉死白名单、映射函数、`resetForm`/`clearValidate`、载荷映射不变量 |

### 6.3 未修改

- 其它业务页面
- 公共 UI 组件（含 `LqDialog`、`SearchToolbar`）
- 后端 Controller / Service / DTO / 实体
- 数据库与 Flyway
- `package.json` / `package-lock.json` / `vite.config.js` / `playwright.config.ts`
- 并行任务正在编辑的文件

### 6.4 诊断过程中的临时操作

- 一次性删除 `frontend/node_modules/.vite`（可再生缓存，验证缓存假设时执行，已自动重建）
- 一次性创建并删除若干临时诊断文件（配置文件 / 测试文件 / 探针脚本），**工作区已无残留**（已用 `git status | findstr "b0- b7b0-"` 校验，仅剩本阶段报告文件）

---

## 7. E2E、Vitest、Typecheck、Lint、构建结果

执行环境：均以大写盘符 cwd 启动；dev server 监听 `127.0.0.1:4173`（Playwright `webServer` 复用）；构建输出写入 `C:/Users/20676/AppData/Local/Temp/b1a-build`（未触及 `frontend/dist`）。

| 门禁 | 命令 | 结果 |
|---|---|---|
| 本页 E2E（含既有 B6-2 视觉验收） | `npx playwright test e2e/office-asset-standard-page.spec.ts e2e/office-asset-form-lifecycle.spec.ts --workers=4` | **31 passed**（43.2s） |
| 相关公共组件契约 | `vitest run src/components/common/__tests__` | **22 files / 267 tests passed**（含本阶段新增 1 文件 8 条） |
| 全量 Vitest | `vitest run` | **79/80 文件通过；718/720 用例通过** |
| Typecheck | `npm run typecheck`（vue-tsc 6.0.3） | **通过**（无错误输出，exit 0） |
| Lint | `npm run lint`（eslint src/ --quiet） | **通过**（无错误输出，exit 0） |
| 完整构建 | `npx vite build --outDir <TEMP>\b1a-build --emptyOutDir` | **通过**（33.83s，仅 chunk 体积告警，非本阶段引入） |

**修复前 vs 修复后（本页 E2E 6 条新回归）**

| 场景 | 修复前 | 修复后 |
|---|---|---|
| 1 新增→关闭→再次新增 | 通过 | 通过 |
| 2 编辑 A→关闭→新增→保存（载荷） | 失败 | 通过 |
| 3 编辑 A→编辑 B | 通过 | 通过 |
| 4 编辑→新增→保存 | 失败 | 通过 |
| 5 保存失败→关闭→重新打开 | 通过 | 通过 |
| 6 校验失败→关闭→重新打开 | 失败 | 通过 |

**契约测试的"修复前必失败"验证**：把同一组断言施加到 HEAD（修复前）源码，13 项中有 12 项失败；修复后 8/8 通过。

---

## 8. 遗留问题和下一批建议

### 8.1 遗留问题（本阶段不处理，仅登记）

1. **Tracking 两条既有测试失败**（`TrackingAnalyticsDrilldownCells.test.ts` 场景 1、2）：由提交 `4c6d5432`（2026-10-28，并行 Tracking 任务）把 `TrackingAnalyticsSpecCell.vue` 改为只渲染规格第一段（尺寸）所致，测试仍按旧口径断言。**未代修**，需与并行任务确认边界后处理。
2. **P1-6 的其它页面**：`EmployeeRosterView:78-79` + `:445` 存在同类"校验态跨次打开遗留"问题。本次仅修 `OfficeAssetView`，其它页面待 B7-B3 统一处理。
3. **后端缺少入参字段治理**：`create` 直接绑定实体，`lastInventoryAt` 可被外部传入。建议后续引入独立 `AssetCreateRequest` DTO（属后端范围，本次不动）。
4. **Vite 临时配置残留**：`frontend/` 根目录现有 18 个 `vite.config.js.timestamp-*.mjs`（可再生，不影响执行）。
5. **上游缺陷**：Vitest 4.1.9 / Vite 8 在 Windows 的盘符大小写未归一化问题，未定位到上游 issue / 修复版本。

### 8.2 下一批建议（B7-B1b 及之后）

按"风险 + 依赖关系"排序，而非报告编号：

1. **B7-B1b · 搜索 / 筛选 / 分页重置**（P0，仅次于资产残留）
   - `WebsiteInquiryView:22`（`@search` 直绑 `loadInquiries`，无 `resetPage`）
   - `CertificateView:419` + `:514`（`resetFilters` 无分页能力）
   - `CustomerView`、`PackagingView:468` → `resetAndLoad:462`、`ProductCatalogView:651` → `loadModels(0)`
   - 建议复用 `OfficeAssetView:400` 的 `handleKeywordSearch` 范式，不新建大型 composable

2. **B7-B2 · 批量与行级权限统一**（P0）
   - 6 页 `batch-enabled` 常量 → 绑定 `canManageXxx`
   - 4 页行级操作未传权限 prop：`CertificateView` + `CertListTable:142-143`、`PackagingView` + 详情抽屉、`PackagingMoldView` + `PackagingMoldsMasterDetail:186/190/194`、`MediaAccountView:177-180`、`ProductCatalogView:178`
   - 正确范式：`CustomerView:65/75/97`

3. **B7-B3 · 空实现与共性缺陷**（P0/P1）
   - `PackagingMoldView:305-307` `applyFilters()` 空实现（实际靠子组件 watch）
   - `MediaAccountView:555-558` `useModalForm({ defaultForm: () => ({}) })`
   - 异步回填无守卫 3 处：`PackagingEditorModal:324-331`、`MoldFormDialog:628-691`、`WebsiteProductsView` 三处（有守卫但只校验"是否同一弹窗"）
   - 校验态遗留：`EmployeeRosterView`

4. **B7-C · 视觉与组件迁移**（最后）
   - 表格 / 分页 / 弹窗 / 抽屉 统一接入
   - 重复样式收敛（38 处 `:deep()` 覆盖）

---

## 9. B7-B1b 开始前的验收条件

1. 全量 Vitest 稳定在 **79/80 文件、718/720 用例通过**，且连续两次结果一致（唯一失败为 Tracking 两条，已登记未代修）。
2. Typecheck 与 Lint 均通过；完整构建通过且输出写入独立目录。
3. 本页 E2E 31/31 通过（含 B6-2 视觉基线不回退）。
4. B7-B1a 的修改范围未被扩大：仅 `OfficeAssetView.vue` + 本页测试 + 本报告。
5. 所有测试命令仍从大写盘符 cwd 启动。
6. 修复前先补"修复前稳定失败"的回归用例，确认真实缺陷再改实现。
7. 禁止为让测试通过而删除正常测试或修改断言。

---

**未提交、未推送。未执行 `git clean` / `reset --hard` / `stash`。未删除 `node_modules`，未修改任何依赖与锁文件。**
