# 前端统一 UI 体系 B7-B1c：表单生命周期与异步回填治理

> 阶段：B7-B1c（表单 / 弹窗 / 异步回填）
> 性质：只读复核 + 精确修复 + 回归固化
> 状态：完成，已停止在 B7-B1c，未提交、未推送

---

## 1. 当前代码基准

### 1.1 阶段开始时的基准（首次记录）

| 项 | 值 |
|---|---|
| HEAD | `52cc2dc4` |
| main | `52cc2dc4` |
| origin/main | `1999e3c51e515c283acd1e12dbcd28cd5cf4a4b3` |
| main vs origin/main | ahead 9 |
| 工作区变更总数 | 46 项（21 modified + 25 untracked） |
| 最近提交 | `b7b9b7c` docs(frontend): record B7-B1b search/pagination audit and fixes |

### 1.2 结束时的基准（并行任务持续推进后重新记录）

| 项 | 值 |
|---|---|
| HEAD | `bf3684f7d7271a81e71e241524105a91a2abb46c` |
| main | `bf3684f7d7271a81e71e241524105a91a2abb46c` |
| origin/main | `1999e3c51e515c283acd1e12dbcd28cd5cf4a4b3` |
| main vs origin/main | ahead 10 |
| git stash | 空 |

### 1.3 前置检查结论

| 检查项 | 结论 |
|---|---|
| B6 / B7-B1a / B7-B1b 未提交成果 | 全部保留，未做任何 `clean` / `reset` / `stash` |
| B7-A 报告 | 已读，提取 P0-7 / P1-6 / P1-7 / P1-8 四类表单缺陷 |
| B7-B0 报告 | 已读，沿用「大写盘符 + `vitest.mjs` 直调」的启动方式 |
| B7-B1a / B7-B1b 报告 | 已读，沿用隔离夹具 + 请求侧断言的验证范式 |
| 22 个遗漏组件 | 已逐个只读核对（见 §2） |
| 并行任务占用 | 已核对当前 21 项前端变更，确认修复对象均未被占用 |

---

## 2. 候选业务组件范围与筛选理由

### 2.1 并行任务占用面（决定排除项）

当前工作区中，以下文件正在被其它并行任务修改，本阶段**一律不触碰**：

- 官网解耦：`frontend/src/api/index.ts`、`api/website/*`、`apps/archive/navigation.ts`、`apps/archive/routes.ts`、`views/marketing/Website*` 中的 `WebsiteBannerView` / `WebsiteCompanyProfileView`（含删除 `WebsiteDashboardView`、`WebsiteVisualEditorView`）、`components/website/*`、`types/website.ts`
- 跟单分析：`views/production/ProductionTrackingWeeklyReviewDrawer.vue`、`components/production/Tracking*`
- 后端 / 数据库：`backend/**`（本阶段完全不进入）

据此从 22 个遗漏组件中排除：`components/website/*`（ImageCropper、WebsiteProductVariantsDialog、WebsiteProductGalleryDialog）与 `components/production/Tracking*`（TrackingProductBenchmarkPanel、TrackingGeneralBaselineDialog）。

### 2.2 22 个组件的核对结果

| 组件 | 表单 | A 初始化 | B 校验态 | C 异步回填 | D 关闭 | 严重度 | 处置 |
|---|---|---|---|---|---|---|---|
| `certificate/CertDetailModal.vue` | 有 | 无残留（695-702 先删全部 key） | 无（`:8` 有 destroy-on-close） | 低（仅文件素材回填 738-746） | 有 catch | **低** | 复核后**否定**了上游「高」的评级，仅载荷含只读键 |
| `common/DefaultLayout.vue` | 无 | — | — | — | — | 无 | 公共组件，不纳入 |
| `common/SearchToolbar.vue` | 无 | — | — | — | — | 无 | 公共组件，不纳入 |
| `common/LqDatePicker.vue` | 无 | — | — | — | — | 无 | 公共组件，不纳入 |
| `company/CompanyBrandAssetsTab.vue` | 有 | 整体赋值 951-959 | — | 父级切 Tab 重建 | — | 低 | 登记 B7-B3 |
| `company/CertificationFormDialog.vue` | 有 | 显式映射 235 | — | — | — | 低 | 登记 B7-B3 |
| `company/CompanyAccountsTab.vue` | 有 | 整体赋值 225/283 | — | 父级切 Tab 重建 | — | 低 | 登记 B7-B3 |
| `employee/EmployeeDetailPanel.vue` | 有 | 显式映射，无残留 | **缺陷**（93 无 destroy-on-close，无 clearValidate） | — | — | **中** | ✅ 本轮修复 |
| `media/MediaAnalyticsPanel.vue` | 无 | — | — | **已有 AbortController** 219-253 | — | 无 | 作为正确范式参照 |
| `packaging/PackagingDetailDrawer.vue` | 无 | — | — | **缺陷**（718-726 无 catch；764-772 无竞态守卫） | — | 中 | 登记 B7-B2（PackagingView 为其目标页） |
| `packaging/PackagingAssetPanel.vue` | 无 | — | — | — | — | 无 | — |
| `product/CatalogAssetGallery.vue` | 无 | — | — | — | — | 无 | — |
| `product/ProductEditorModal.vue` | 无 | — | — | — | — | 无 | — |
| `product/ProductParameterSection.vue` | 无 | — | — | — | — | 无 | — |
| `product/ProductIdentitySection.vue` | 无 | — | — | — | — | 无 | — |
| `production/ManufacturingTaskConfigPanel.vue` | 无 | — | — | — | — | 无 | — |
| `production/TrackingProductBenchmarkPanel.vue` | 有 | — | — | 无守卫 195-204 | — | 中 | 登记 B7-B3（production 目录） |
| `production/TrackingGeneralBaselineDialog.vue` | 有 | — | — | 无守卫 26-38 | — | 中 | 登记 B7-B3（production 目录） |
| `production/ManufacturingWorkshopPanel.vue` | 无 | — | — | void load 无 catch | — | 低 | 登记 B7-B3 |
| `website/ImageCropper.vue` | 无 | — | — | — | — | 无 | 并行排除 |
| `website/WebsiteProductVariantsDialog.vue` | 有 | — | — | 无守卫 24-38 | — | 中 | 并行排除 |
| `website/WebsiteProductGalleryDialog.vue` | 有 | — | — | 无守卫 22-32 | — | 中 | 并行排除 |

### 2.3 最终选定的 3 个对象

结合 B7-A 已确认的 P1-6 / P1-7 缺陷点，本轮选定：

| # | 文件 | 缺陷类 | 并行冲突 |
|---|---|---|---|
| C1 | `frontend/src/components/mold/MoldFormDialog.vue` | C 异步回填 + B 校验态 | 无（`components/mold/` 与父级均未改动） |
| C2 | `frontend/src/components/packaging/PackagingEditorModal.vue` | C 异步回填 + A4 载荷 | 无（父级 `PackagingView.vue` 未改动） |
| C3 | `frontend/src/components/employee/EmployeeDetailPanel.vue` | B 校验态 | 无（父级 `EmployeeRosterView.vue` 未改动） |

---

## 3. 确认的真实缺陷

### C1 · `MoldFormDialog.vue`

| ID | 位置 | 缺陷 |
|---|---|---|
| C1-a | `628-656` | `loadForEdit()` 两次 `await` 之后**整对象** `Object.assign(form, …)`，无请求身份校验 |
| C1-b | `686` | `dirty.value = false` 在异步回填之后**无条件**执行，异步初始化会抹掉更晚的用户编辑 |
| C1-c | `2`、`93` | `LqDialog` 未传 `destroy-on-close`，内部 `el-form :rules` 无 `clearValidate` 入口 |
| C1-d | — | `dirty` 全程**从未被置为 true**（模板输入控件无 `@input`/`@change`），`handleBeforeClose` 的未保存确认实际是死代码 |

触发路径：打开模具 A 编辑 → 详情在飞行中，表单显示默认值 → 用户输入模号 → 响应返回 → 输入被整对象覆盖，且 `dirty` 被清零 → 关闭时也不提示。

### C2 · `PackagingEditorModal.vue`

| ID | 位置 | 缺陷 |
|---|---|---|
| C2-a | `324-331` | `onMounted` 内 `await fetchPackagingModel(props.modelId)` 后**整对象** `Object.assign(form, detail.model)`，无守卫 |
| C2-b | `289-302` | `cleanPayload()` 直接展开整个 `form`，把详情带回的 `id` / `createdAt` / `updatedAt` / `deletedAt` / `variantCount` / `assetCount` 一并提交 |
| C2-c | `324-331` | 无 `try/catch`，详情请求失败时被静默吞掉，用户只看到一个空表单且毫无提示 |

### C3 · `EmployeeDetailPanel.vue`

| ID | 位置 | 缺陷 |
|---|---|---|
| C3-a | `93-101` | `LqDialog` 未传 `destroy-on-close`，内部 `el-form :rules="mappingRules"` 实例被复用 |
| C3-b | `432-442` | `resetMappingForm()` 只重置值，**不清除校验态**；全组件无 `clearValidate` |
| C3-c | — | 父级 `EmployeeRosterView.vue:56` 只在「切换员工」时重建面板，同一员工内反复开关弹窗即命中 C3-a |

触发路径：切到「身份映射」→ 新增 → 不填必填项 → 保存 → 红字 → 关闭 → 再打开 → 红字仍在。

---

## 4. 修复前失败证据

全部取自修复前的真实浏览器运行（Playwright + Edge，Mock API 支持延迟与乱序）：

| 用例 | 断言 | 修复前实测 |
|---|---|---|
| C1 ① 加载窗口内输入不被覆盖 | 模号输入框 = `E2E-TYPED-CODE` | 实得 `M20910-3F`（被旧响应覆盖） |
| C1 ② A 慢 B 快 | 模号 = `P23128-6W`，备注 = `B 备注` | 实得 `M20910-3F`（旧响应后到并覆盖） |
| C1 ③ 关闭后旧响应 | 模号 = `''` | 实得 `M20910-3F`（已关闭仍回填） |
| C1 ⑤ 校验态残留 | `.el-form-item__error` 数量 = 0 | 实得 1 |
| C2 ① 详情未返回时输入 | 名称 = `E2E-PKG-NAME` | 实得 `示例包材 A` |
| C2 ② 载荷字段 | 禁用键集合为空 | 实得含 `id` / `variantCount` / `assetCount` |
| C2 ③ 详情 500 | 出现 1 条错误提示 | 实得 0（无 catch，错误被吞） |
| C3 ① 校验态残留 | `.el-form-item__error` 数量 = 0 | 实得 2 |

首轮合计：**9 failed / 2 passed**（另有 2 条失败经复核为**夹具选择器错误**，非产品缺陷，见 §4.1）。

### 4.1 区分真实缺陷与夹具错误

首轮 C3 的三条用例原本全部报 `Error: expect(locator).toBeVisible() failed / element(s) not found`。回看源码后确认是**夹具根选择器写错**（组件根元素是 `.employee-detail-panel`，不是 `.employee-detail`），修正后：

- C3 ① → 真实缺陷，稳定失败（received 2）
- C3 ② / ③ → **本来就是通过的**，修正选择器后无需修复

另有一条 `C1·MoldFormDialog › ②` 在修复后仍失败，复核发现是**断言指向了错误控件**：`.mold-form textarea` 的第一个是「杯底刻字」（`maxlength="300"`），而非「备注」（`maxlength="1000"`）。改为 `textarea[maxlength="1000"]` 后转绿。

### 4.2 核查结论：`watch(visible)` 与 `onMounted` 是否重复触发

`MoldFormDialog.vue:300` 的 `visible = ref(props.visible)` 在注册 `watch(visible, …)`（793）之前就已取到 `props.visible` 的值；该 watcher 未声明 `immediate`，因此**挂载时不会触发**。

实测：一次打开，`GET /api/packaging-molds/101` 的请求次数为 **1**（修复前后均为 1）。

结论：**不存在重复触发**。该条为核查结论，非缺陷项。但两个入口都调 `loadForEdit()` 的结构本身脆弱，故本次一并加了序号守卫，使其不依赖调用入口的唯一性。

---

## 5. 精确改动文件与 diff

### 5.1 生产代码（3 个业务组件）

```
 frontend/src/components/employee/EmployeeDetailPanel.vue    |  19 +-
 frontend/src/components/mold/MoldFormDialog.vue              | 215 +++++++++++++--------
 frontend/src/components/packaging/PackagingEditorModal.vue   | 128 ++++++++++--
 3 files changed, 259 insertions(+), 103 deletions(-)
```

**未修改任何公共组件**（`LqDialog` / `LqDrawer` / `SearchToolbar` 等一律未动），未修改后端、数据库、Flyway，未修改其它并行任务的文件。

### 5.2 C1 · `MoldFormDialog.vue`

```diff
-import { ref, reactive, computed, watch, onMounted } from 'vue'
+import { ref, reactive, computed, watch, onMounted, onUnmounted, nextTick } from 'vue'

 const formRef = ref<FormInstance>()
 const saving = ref(false)
 const dirty = ref(false)
 const loadingGroups = ref(false)
+
+/**
+ * 当前加载会话的序号：每次打开弹窗 / 切换模具都会递增。
+ *
+ * <p>详情请求是异步的，返回时会话可能已经变了（用户关了弹窗、或改用另一个模具）。
+ * 只有在返回时序号仍然是最新值，回填才允许落到表单上。
+ */
+let editLoadSeq = 0
+/** 组件已卸载：卸载后到达的响应一律丢弃，避免写入已失效的状态 */
+let disposed = false
+onUnmounted(() => (disposed = true))

-const form = reactive<MoldPayload & { id?: number; engravingType?: string }>({
-  modelId: undefined,
-  ... 20 个字段 ...
-})
+type MoldFormState = MoldPayload & { id?: number; engravingType?: string }
+function buildDefaultMoldForm(): MoldFormState { return { id: undefined, ... 20 个字段 ... } }
+const form = reactive<MoldFormState>(buildDefaultMoldForm())
+
+/** 程序化写入（初始化 / 异步回填）期间不标记为「用户已改」（flush: 'sync' 保证回调可被屏蔽） */
+let applyingForm = false
+watch(form, () => { if (!applyingForm) dirty.value = true }, { deep: true, flush: 'sync' })
+
+function applyForm(patch: Partial<MoldFormState>) {
+  applyingForm = true
+  try { Object.assign(form, patch) } finally { applyingForm = false }
+}
+
+/** 异步回填：只覆盖用户尚未修改（仍等于默认值）的字段 */
+function mergeLoadedMoldForm(loaded: Record<string, unknown>) {
+  const defaults = buildDefaultMoldForm() as Record<string, unknown>
+  const patch: Record<string, unknown> = {}
+  for (const key of Object.keys(defaults)) {
+    if (sameValue(form[key as keyof MoldFormState], defaults[key])) patch[key] = loaded[key] ?? defaults[key]
+  }
+  applyForm(patch)
+}
+function hasUserEdits() { /* 逐字段比对默认值 */ }
+async function clearFormValidation() {
+  await nextTick()
+  formRef.value?.clearValidate()
+}

 async function loadForEdit() {
   if (!props.moldId) return
+  const seq = ++editLoadSeq
   try {
     const data = await fetchPackagingMold(props.moldId)
+    // 请求返回时会话可能已经结束（关闭 / 切到另一个模具 / 组件已卸载）—— 旧响应直接丢弃
+    if (disposed || seq !== editLoadSeq) return
     const v = data.mold
     await loadMoldGroups(v.modelId)
+    if (disposed || seq !== editLoadSeq) return
-    Object.assign(form, { id: v.id, ... 19 个字段 ... })
+    const touchedBeforeMerge = hasUserEdits()
+    mergeLoadedMoldForm((v ?? {}) as unknown as Record<string, unknown>)
     ...
-    dirty.value = false
+    // 异步回填不得抹掉用户在加载窗口内已经做出的编辑
+    if (!touchedBeforeMerge) dirty.value = false
   } catch (e: any) {
+    // 会话已结束（关闭 / 切换 / 卸载）→ 不再向用户抛出「加载模具失败」
+    if (disposed || seq !== editLoadSeq) return
     ElMessage.error(e?.message || '加载模具失败')
     visible.value = false
   }
 }

-function resetForm() {
-  Object.assign(form, { modelId: undefined, ... id: undefined })
-  moldGroups.value = []
-  accessories.value = []
-  dirty.value = false
-}
+function resetForm() {
+  applyForm(buildDefaultMoldForm())
+  moldGroups.value = []
+  accessories.value = []
+  dirty.value = false
+}

 watch(visible, v => {
-  if (v) {
-    loadProductModels()
-    ...
-  }
+  if (!v) {
+    // 弹窗关闭 → 之前发出的详情请求立即失效，其响应不得再落到表单上
+    editLoadSeq += 1
+    return
+  }
+  clearFormValidation()
+  loadProductModels()
+  ...
 })

 onMounted(() => {
-  if (props.visible) {
-    loadProductModels()
-    ...
-  }
+  if (!props.visible) return
+  clearFormValidation()
+  loadProductModels()
+  ...
 })
```

要点：
- 请求身份 = `editLoadSeq`；**每次打开递增、每次关闭也递增**，因此关闭后到达的旧响应 `seq !== editLoadSeq` 被丢弃。
- 卸载失效 = `disposed` + `onUnmounted`。
- 用户编辑保护 = `mergeLoadedMoldForm` 只覆盖仍等于默认值的字段，**不依赖 `dirty` 标志**（`dirty` 原本从未被置位）。
- 顺带补上 `applyingForm` 屏蔽的 `watch(form)`，让 `dirty` 真正可用，`handleBeforeClose` 的未保存确认从死代码变为生效。

### 5.3 C2 · `PackagingEditorModal.vue`

```diff
 const form = reactive({
   ...
   sortOrder: 0
-  ,
   websiteEnabled: false,
-  websiteSortOrder: 0
+  websiteSortOrder: 0,
+  /** 主图文件名：由素材面板维护，保存时随包材原样回写后端 */
+  defaultImage: ''
 })
+type PackagingModelForm = typeof form
+function buildDefaultForm(): PackagingModelForm { /* 与上面一致的完整默认值 */ }
+
+/**
+ * 详情对象带回、但**不应进入表单 / 保存请求**的只读与派生属性。
+ *
+ * <p>`id` / `createdAt` / `updatedAt` / `deletedAt` 是服务端主键与审计时间戳；
+ * `variantCount` / `assetCount` 是后端派生统计。后端 `PackagingService.updateModel`
+ * 对入参逐字段白名单赋值（共 22 个业务字段），多传无意义，显式剔除。
+ *
+ * <p>注意：`defaultImage` / `defaultImageAssetId` 属于后端会回写的业务字段，
+ * 因此**不在**剔除名单内，避免更新时被清空。
+ */
+const EDITABLE_MODEL_FIELDS = [
+  'name', 'nameEn', 'modelCode', 'packagingKind', 'filmForm', 'shapeType',
+  'material', 'colorStyle', 'capacityMl', 'applicable', 'advantages',
+  'moq', 'unitPrice', 'leadTime', 'customizable', 'status', 'tags',
+  'remark', 'sortOrder', 'websiteEnabled', 'websiteSortOrder',
+  'defaultImage'
+] as const
+
+/** 异步回填：只覆盖用户尚未修改的字段 */
+function mergeLoadedForm(loaded: Record<string, unknown>) { /* 同 C1 */ }

-function cleanPayload() {
-  return Object.fromEntries(
-    Object.entries(form).map(([key, value]) => [ ... ])
-  )
-}
+function cleanPayload() {
+  return Object.fromEntries(
+    EDITABLE_MODEL_FIELDS.map(key => { /* 仅白名单字段 + 原归一化逻辑 */ })
+  )
+}

-onMounted(async () => {
-  if (props.modelId) {
-    const detail = await fetchPackagingModel(props.modelId)
-    Object.assign(form, detail.model)
-    form.packagingKind = normalizeKindKey(form.packagingKind)
-    form.shapeType = normalizeShapeKey(form.shapeType)
-    dirty.value = false
-  }
+/** 组件已卸载：卸载后到达的响应一律丢弃，不再写表单、不再弹提示 */
+let disposed = false
+onUnmounted(() => (disposed = true))
+
+onMounted(async () => {
+  if (props.modelId) {
+    // 请求期间 modelId 可能被切走，或组件可能已被父级 v-if 卸载 —— 记下本次请求针对的对象
+    const requestedId = props.modelId
+    try {
+      const detail = await fetchPackagingModel(requestedId)
+      // 旧响应（已卸载 / 已切换到另一个包材）直接丢弃
+      if (disposed || props.modelId !== requestedId) return
+      // 只回填用户尚未动过的字段，避免覆盖加载窗口内的输入
+      mergeLoadedForm((detail.model ?? {}) as unknown as Record<string, unknown>)
+      form.packagingKind = normalizeKindKey(form.packagingKind)
+      form.shapeType = normalizeShapeKey(form.shapeType)
+      dirty.value = false
+    } catch (error) {
+      if (disposed || props.modelId !== requestedId) return
+      notify.errorWithDetail('加载包材详情失败', error)
+    }
+  }
   await nextTick()
```

要点：
- 回填改为 `mergeLoadedForm`，只写未编辑字段，只读 / 派生键不再进入 `form`
- `cleanPayload()` 改为**显式白名单**产出，与后端逐字段对照（详见 §6.2）
- 补 `try/catch` + `notify.errorWithDetail`，详情失败不再静默

### 5.3.1 C2 载荷白名单的后端逐字段对照

`PackagingService.updateModel(Long id, PackagingModel dto)`（后端 `:130-165`）按下列 22 个字段白名单赋值，与本阶段 `EDITABLE_MODEL_FIELDS` 一一对应：

`name`、`nameEn`、`modelCode`、`packagingKind`、`filmForm`、`shapeType`、`material`、`colorStyle`、`capacityMl`、`applicable`、`advantages`、`moq`、`unitPrice`、`leadTime`、`customizable`、`status`、`tags`、`defaultImage`、`sortOrder`、`websiteEnabled`、`websiteSortOrder`、`remark`

**未列入白名单且不应提交的**：`id`（路径参数即主键）、`createdAt` / `updatedAt` / `deletedAt`（审计时间戳）、`variantCount` / `assetCount`（派生统计）。

**刻意保留的**：`defaultImage` —— 后端 `existing.setDefaultImage(dto.getDefaultImage())` 会原样回写，若从载荷中剔除会导致更新时主图被清空。**`variants` / `assets` 本就不属于本保存载荷**（各自走独立端点），本轮未删减任何原有保存行为。

### 5.4 C3 · `EmployeeDetailPanel.vue`

```diff
-import { computed, reactive, ref, watch } from 'vue'
+import { computed, reactive, ref, watch, nextTick } from 'vue'

+/**
+ * 打开映射弹窗时清掉上一次的校验错误态。
+ *
+ * <p>本组件的 `LqDialog` 未传 `destroy-on-close`，关闭只隐藏 `el-dialog` 而不销毁内部
+ * `el-form`；而父级只在「切换员工」时才重建整个面板。两者叠加，同一员工内第二次打开
+ * 弹窗就会看到上一次 `validate()` 留下的红字。
+ *
+ * <p>这里只调 `clearValidate()` 清错误态，**不**用 `resetFields()`：后者会把字段重置成
+ * `el-form` 挂载时的初值，反而覆盖掉 `openEditMapping()` 刚写入的当前行数据。
+ */
+async function clearMappingValidation() {
+  await nextTick()
+  mappingFormRef.value?.clearValidate()
+}
+
 function openAddMapping() {
   if (!detail.value) return
   resetMappingForm()
   mappingEditorVisible.value = true
+  clearMappingValidation()
 }

 function openEditMapping(row: EmployeeMappingSummary) {
   Object.assign(mappingForm, { ...6 个字段... })
   editingMappingId.value = row.id
   mappingEditorVisible.value = true
+  clearMappingValidation()
 }
```

要点：
- 清错误态放在**打开入口**而非 `resetMappingForm()`，保证「新增」与「编辑」两条路径都覆盖
- 用 `clearValidate()` 而非 `resetFields()`，避免覆盖刚写入的行数据
- 不改变已有值、权限判断（`:5/:47/:84` 的 `v-if="canManage"`）与提交行为

---

## 6. 表单初始化与异步回填修复方案

### 6.1 统一守卫模式（C1 / C2 共用）

```
打开表单                        → seq = ++editLoadSeq（建立当前会话身份）
关闭表单 / 切到另一个对象        → editLoadSeq += 1（旧会话立即失效）
组件卸载                        → disposed = true

await 详情请求
  ↓
if (disposed || seq !== editLoadSeq) return   ← 旧响应丢弃，不写表单、不弹提示
  ↓
mergeLoadedForm(响应)   ← 只覆盖「仍等于默认值」的字段，保护用户已编辑内容
```

三条防线相互独立：
1. **会话序号** —— 处理「关闭 / 切换对象」
2. **卸载标记** —— 处理「组件已销毁」
3. **默认值比对** —— 处理「用户在加载窗口内已经输入」

第 3 条尤其关键：它**不依赖 `dirty` 标志**。`MoldFormDialog` 原本从来没有把 `dirty` 置为 true（`handleBeforeClose` 的未保存确认形同虚设），因此不能用 `dirty` 作为唯一依据。本次同步补上 `applyingForm` 屏蔽的 `watch(form, …, { flush: 'sync' })`，使 `dirty` 恢复可用。

### 6.2 载荷治理

- **负面剔除 → 正面白名单**：`cleanPayload()` 不再展开整个 `form`，改由 `EDITABLE_MODEL_FIELDS` 显式产出
- **白名单来源**：与后端 `PackagingService.updateModel` 逐字段对照（22 个业务字段）
- **防误删**：`defaultImage` 等后端会回写的字段刻意保留；`variants` / `assets` 本就不在本载荷内，未做删减

### 6.3 校验态治理

- 打开入口统一调用 `clearValidate()`
- 仅清错误态，不动值 —— 避免 `resetFields()` 覆盖刚写入的行数据
- 覆盖「新增」与「编辑」两条打开路径

### 6.4 不转移职责的边界

本轮**未**把表单数据生命周期职责转移到 `LqDialog` / `LqDrawer`。守卫与初始化全部落在业务组件内部，公共组件零改动。

---

## 7. 修复后验证结果

### 7.1 三个组件 E2E（新增）

```
Running 11 tests using 3 workers
  11 passed (27.7s)
```

| 组 | 用例 | 结果 |
|---|---|---|
| C1 | ① 加载窗口内输入不被覆盖 | ✅ |
| C1 | ② A 慢 B 快最终展示 B | ✅ |
| C1 | ③ 关闭后旧响应不回填 | ✅ |
| C1 | ④ 一次打开只请求一次 | ✅（核查项） |
| C1 | ⑤ 校验失败后重开无残留 | ✅ |
| C2 | ① 详情未返回时输入不被覆盖 | ✅ |
| C2 | ② 载荷不含只读 / 派生键 | ✅ |
| C2 | ③ 详情 500 有错误提示 | ✅ |
| C3 | ① 校验失败后重开无残留 | ✅ |
| C3 | ② 新增默认 / 编辑取行值 | ✅（核查项） |
| C3 | ③ 清除校验不清空输入 | ✅（核查项） |

### 7.2 公共组件契约（新增）

```
Test Files  1 passed (1)
     Tests  12 passed (12)
```

覆盖：
- MoldFormDialog：请求身份建立、两次 await 后均校验身份、卸载失效标记、合并不覆盖用户编辑、打开清校验
- PackagingEditorModal：记录 requestedId、await 后校验 modelId 与 disposed、合并不覆盖用户编辑、白名单载荷
- EmployeeDetailPanel：新增 / 编辑两个入口都清校验、只用 `clearValidate` 不用 `resetFields`

### 7.3 全量 Vitest

```
Test Files  1 failed | 81 passed (82)
     Tests  2 failed | 738 passed (740)
Duration  18.67s
```

- 相对阶段开始基准（80/81 文件、726/728 用例）新增 **1 文件 / 12 用例**，全部为本次契约测试
- **2 条失败即 Tracking 既有遗留**：`TrackingAnalyticsDrilldownCells.test.ts › TrackingAnalyticsSpecCell › 只有规格时不留下孤零零的分隔符`（期望 `"95 / 方形 / PP"`，实得 `"95"`）。本阶段**未修改**任何跟单业务实现或断言，独立登记

### 7.4 Typecheck

```
> vue-tsc --noEmit --skipLibCheck
（无输出，退出码 0）
```

### 7.5 Lint

```
> eslint src/ --quiet
（无输出，退出码 0）
```

### 7.6 完整构建

```
✓ built in 16.44s
```

输出目录为独立临时目录 `C:/Users/20676/AppData/Local/Temp/b1c-build`，未删除其它任务产物。

---

## 8. 未处理问题及归属

### 8.1 本轮核验但未修复（超出 3 个上限）

| # | 文件:行 | 问题 | 归属 |
|---|---|---|---|
| 1 | `production/TrackingGeneralBaselineDialog.vue:26-38` | `@open` 内 `await fetchTrackingNodeBenchmarks()` 后整体赋值，无 abort / 序号守卫；常驻挂载，多次打开会并发，后发先至被覆盖。**仓库内已有正确范式**：`MediaAnalyticsPanel.vue:219-253` 用 AbortController | B7-B3（现属并行 tracking 任务目录，暂缓） |
| 2 | `production/TrackingProductBenchmarkPanel.vue:195-204` | `save(row)` 用 `props.taskConfigId` + `row.stageCode` 定位记录，缺少「当前记录」守卫，列表刷新后易错位 | B7-B3 |
| 3 | `production/ManufacturingWorkshopPanel.vue:51-58` | `void load()` 无 `catch`、无 loading 态，失败静默 | B7-B3 |
| 4 | `packaging/PackagingDetailDrawer.vue:718-726` | `loadDetail()` 无 `catch`：请求失败后 `loading` 复位但 `detail` 保留上一次数据，用户以为加载完成 | **B7-B2**（PackagingView 为其目标页，避免同页多处改动叠加） |
| 5 | `packaging/PackagingDetailDrawer.vue:764-772` | `watch(() => props.modelId)` 无竞态守卫 | B7-B2 |
| 6 | `website/WebsiteProductVariantsDialog.vue:24-38` | 无守卫；常驻 | B7-B3（现属并行官网任务，暂缓） |
| 7 | `website/WebsiteProductGalleryDialog.vue:22-32` | 无守卫；且 `reset()` 的 `sortOrder` 取自**上一个产品**的 `review.gallery.length` | B7-B3 |
| 8 | `employee/EmployeeDetailPanel.vue` 的 `sourceSystem` 校验 `trigger: 'change'` | 仅下拉变更触发；若初始为空且未改动，需显式 validate 才报错，与其它表单口径略有差异 | B7-B3 |
| 9 | `mold/MoldFormDialog.vue` 的 `dirty` 从未置位（本次已修） | — | 已修 |
| 10 | `company/CompanyBrandAssetsTab.vue:951-959`、`CompanyAccountsTab.vue:225/283` | 整体赋值无守卫，但父级 `v-else-if` 切 Tab 会重建组件，暴露窗口小 | B7-B3 |

### 8.2 上游评级的复核纠正

B7-A 曾把 `certificate/CertDetailModal.vue` 的「A2/A3 字段残留」评为**高**。本轮逐行核对后**判定不成立**：

```ts
// CertDetailModal.vue:695-702
watch(() => props.data, newData => {
  Object.keys(modalForm).forEach(k => delete (modalForm as Record<string, unknown>)[k])  // 先删全部 key
  Object.assign(modalForm, newData)
}, { immediate: true })
```

它**先删除全部 key 再赋值**，且父级 `CertificateView.vue:568` 每次新增都传全新 `{}` 对象；模板 `:8` 也带了 `destroy-on-close` → 无字段残留、无校验态残留。

仅剩的低风险问题：`buildPayload()`（722-726）只剔除 `FEE_FIELDS`，载荷仍带 `id` / 时间戳等键。后端按 `CertificatePayload` 逐字段白名单赋值，多传被忽略 → 属**载荷卫生**问题，非数据风险。

**这条正好说明为什么 B7-A 要强调「不得机械替换所有 `Object.assign`」** —— `Object.assign` 本身不是缺陷，是否先删 key、是否有守卫才是。

### 8.3 已达上限的完整登记

`EmployeeRosterView.vue`、`MediaAccountView.vue`、`WebsiteProductsView.vue` 的表单类缺陷（P1-6 / P1-7 / P1-8）已登记，本轮**不继续扩大范围**。

---

## 9. B7-B2 权限治理建议

本轮复核中发现的**权限 / 批量操作**相关问题按约定不在本阶段处理，汇总如下供 B7-B2 统一决策：

| # | 位置 | 发现 | 建议 |
|---|---|---|---|
| 1 | `production/ManufacturingTaskConfigPanel.vue`（415 行，含权限判断） | 权限分支与配置写入混在同一层，缺少「只读态」的显式禁用语义 | B7-B2 统一「无权限 / 只读」的展示口径 |
| 2 | `employee/EmployeeDetailPanel.vue:5/47/84` | `v-if="canManage"` 散落在模板多处（编辑档案 / 新增映射 / 行操作） | 抽成统一的权限判定，避免新增入口时漏判 |
| 3 | `customer/CustomerView.vue`「地区分布」下钻 | 应用筛选会**清空**批量选择，而翻页 / 改筛选**不清空** —— 同一页内两套口径 | B7-B2 统一跨页选择语义 |
| 4 | `ProductionTrackingWeeklyReviewDrawer.vue` | 抽屉内含权限相关操作，处于并行 tracking 任务修改中 | B7-B2 与 tracking 任务确认后再动 |
| 5 | 全局 | 批量勾选在「改查询条件」时的保留口径依赖各页自行实现（`useBatchSelection`） | B7-B2 明确契约并加公共契约测试 |

---

## 10. 边界声明

- 生产代码仅修改 3 个业务组件：`EmployeeDetailPanel.vue`、`MoldFormDialog.vue`、`PackagingEditorModal.vue`
- **未修改任何公共组件**（`LqDialog` / `LqDrawer` / `SearchToolbar` 等）
- 未修改后端、数据库、Flyway
- 未修改其它并行任务的文件（官网解耦 / 跟单分析 / 后端与官网）
- 未执行 `git clean` / `reset --hard` / `stash`
- 未提交、未推送
- 未查询生产库、未做任何数据清理（本轮缺陷均为界面层，不产生错误写入）
- 测试产物写入独立临时目录，未删除其它任务产物
