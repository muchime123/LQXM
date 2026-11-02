# 前端统一 UI 体系 B7-B2a：权限入口与批量选择行为审计

> 阶段：B7-B2a（权限入口 / 批量模式 / 跨页选择）
> 性质：**只读审计**，未修改任何生产代码
> 状态：完成，已停止在 B7-B2a，未提交、未推送

---

## 1. 代码基准

### 1.1 基准快照

| 项 | 值 |
|---|---|
| HEAD | `bf3684f7d7271a81e71e241524105a91a2abb46c` |
| 当前分支 | `main` |
| 本地 `main` | `bf3684f7`（与 HEAD 一致） |
| `origin/main` | `1999e3c5`（本地 ahead 10） |
| git stash | **空** |
| 工作区变更总数 | 207 项（并行任务持续推进） |

### 1.2 前置成果保留确认

| 项 | 状态 |
|---|---|
| B6-1 / B6-2 未提交成果（`OfficeAssetView` 等） | ✅ 保留 |
| B7-B1a（`OfficeAssetFormLifecycle` 契约 + e2e） | ✅ 保留 |
| B7-B1b（`SearchPaginationLinkage` 契约 + e2e） | ✅ 保留 |
| B7-B1c（`FormLifecycleGuards` 契约 + e2e） | ✅ 保留 |
| 报告 | `docs/frontend-ui-b7a-full-adoption-audit.md`、`*b7b0*`、`*b1a*`、`*b1b*`、`*b1c*` 均在 |

**未执行** `git clean` / `reset --hard` / `stash`。

---

## 2. 页面及子组件覆盖矩阵

### 2.1 六个 `batch-enabled` 页面（B7-A P0-3）

| 页面 | `batch-enabled` 写法 | 顶部按钮权限 | 行内按钮权限 | 批量栏自定义动作 | 跨页选择语义 | 路由权限 |
|---|---|---|---|---|---|---|
| `ProductCatalogView.vue` | **常量** `batch-enabled` (:24) | `v-if="canManageProduct"` (:40/:43/:47) | **无**（标题菜单「删除产品」:178） | `BatchEditModal` (:205-211) | 不清空 → **跨页累积** (`useBatchSelection` :337-340) | `product.read` |
| `PackagingView.vue` | **常量** `batch-enabled` (:15) | `v-if="canManagePackaging"` (:29/:30) | 无行内表格 | `FieldBatchEditModal` (:92-100) | 翻页不清空 → **累积** (:160-162) | `packaging.read` |
| `PackagingMoldView.vue` | **常量** `batch-enabled` (:11) | `v-if="canManageMold"` (:25-28) | 无行内；**子组件 0 判断** | `FieldBatchEditModal` (:50-58) | 子组件内部管理 (:826) | `mold.read` |
| `CertificateView.vue` | **常量** `batch-enabled` (:79) | `v-if="canManageCertificate"` (:91/:92/:117) | **无** → `CertListTable` :142-143 | `FieldBatchEditModal` (:155-161) | `doSearch`/`loadList` 不清空 → **累积** (:245-247) | `certificate.read` |
| `MediaAccountView.vue` | **常量** `batch-enabled` (:27) | `v-if="canManageMedia"` (:48/:52/:56/:58/:69) | **无** (:177/:179/:180) | **已门控** `v-if="canManageMedia"` (:72-76) | 翻页即**丢失** (:582-586) | `media.read` |
| `WebsiteInquiryView.vue` | **常量** `batch-enabled` (:28) | **整页 0 处权限判断** | **无** (:139) | **无门控** (:43-47) | 不清空 → **累积** (:369-381) | `website.manage` |

**正确范式对照**：

| 页面 | 写法 |
|---|---|
| `CustomerView.vue:16` | `:batch-enabled="canManageCustomer"` |
| `OfficeAssetView.vue:37` | `:batch-enabled="canManageOfficeAsset"`（B6-2 试点已修） |

### 2.2 四个行级权限不一致页面（B7-A P0-4）

| 页面:行号 | 按钮 | 当前判断 | 缺失 |
|---|---|---|---|
| `MediaAccountView.vue:177` | ＋更新 | **无** | 无写权限者可 `quickAddUpdate` |
| `MediaAccountView.vue:179` | 编辑 | **无** | 无写权限者可 `openEditor` |
| `MediaAccountView.vue:180` | 删除 | **无** | 无写权限者可 `removeAccount` |
| `CertificateView.vue:120-135` → `CertListTable.vue:142-143` | 编辑 / 删除 | **无**（父层 :91/:92 有，未下传） | 需新增 `canManage` prop |
| `PackagingMoldView.vue:39-45` → `PackagingMoldsMasterDetail.vue:186/190/194` | 编辑 / 删除 / 删除 | **无**（父层 :25-28 有，未下传） | 需新增 `canManage` prop |
| `ProductCatalogView.vue:178` | 删除产品 | **无**（:40/:43/:47 有） | 需加 `v-if="canManageProduct"` |

### 2.3 22 个遗漏组件中涉及权限与批量操作的

| 组件（相对 `frontend/src/components`） | 权限判断 | 批量选择 | 写操作 | 权限 prop 及默认值 | 备注 |
|---|---|---|---|---|---|
| `certificate/CertDetailModal.vue` | — | — | 449/488 删文件、546 保存、551 删除、552 编辑 | **无权限 prop** | 只读 / 只读用户可见写入口 |
| `company/CompanyBrandAssetsTab.vue` | — | 28-47、168、180-181、598-611 | 31 批量标签、43 批量删除、58 上传、141 永久删除 | **无权限 prop** | 父级 `CompanyInfoView:537/542` 已有 `canManageCompany` / `isAdmin` 但**未下传** |
| `packaging/PackagingDetailDrawer.vue` | — | 83、100-150、165 | 123/126/133/141/149 批量操作、761 删除主档 | **无权限 prop** | 批量写链路最完整且全无门控 |
| `packaging/PackagingAssetPanel.vue` | — | 43-44、190-214、285-295、356-375、492-502、573-578 | 218/294 批量标签、356 批量删除 | **无权限 prop** | — |
| `product/CatalogAssetGallery.vue` | — | 22-23、38-42、54-55、153-162、256-260 | 139-146 删除/批量移动/批量删除、162 批量标签 | `batchMode?: boolean` 默认 `false`；**无权限 prop** | — |
| `employee/EmployeeDetailPanel.vue` | 5/6/47/84/167…（共 14 处） | — | 5 编辑、6 删除、47 新增映射 | `canManage?: boolean`，默认 **`:288 { canManage: true }`** ⚠️ fail-open | 各调用点显式传参，但漏传即越权 |
| `production/ManufacturingTaskConfigPanel.vue` | **253/255** | 17（批量计算并填充） | 17 → `emit('batch-calculate')` | `canManageBaseline: boolean` (:235) **必填、无默认值** | ✅ 正确范式 |
| `common/DefaultLayout.vue` | 253/255 (`can(item.permission)`) | — | — | 菜单项 `permission` 字段；**无值时 `can(undefined) → true`** | 漏配即全员可见 |
| `common/SearchToolbar.vue` | — | 108-138 | — | `batchEnabled?: boolean` 默认 **`false`** (:208/:229)；**无权限 prop** | 批量栏默认关闭，安全 |

---

## 3. 前后端权限契约

### 3.1 前端判定机制

`can()` 实现 —— `frontend/src/composables/auth/useCurrentUser.ts:78-82`

```ts
function can(permission?: string | string[]) {
  if (!permission) return true          // ① 空/未声明权限 → 放行（fail-open）
  const list = Array.isArray(permission) ? permission : [permission]
  return list.some(x => user.value?.permissions.includes(x))
}
```

- 无 `isAdmin` / `hasRole` / 其它权限辅助（仅导出 `user/loading/token/hasToken/load/can/loginByLocal/logout/selectEmployee`，见 :97-107）
- 数据来源 `GET /api/auth/me`（`api/auth/index.ts:42-44`），模块级 ref 单例 + 请求去重，**无本地缓存**
- 路由守卫：`router/index.ts:81-85`，`to.meta.permission` → `auth.can(required)`，失败跳 `/403`

| 写法 | 出现次数 | 典型文件:行号 | 评价 |
|---|---|---|---|
| `computed(() => can('x'))` → `canManageXxx` | 10 | `ProductCatalogView:304`、`CustomerView:189` | ✅ 推荐 |
| 裸调 `can('x')` | 10 | `PackagingView:146`、`CertificateView:229` | ✅ 可用 |
| `auth.can('x')` | 16 | `router/index.ts:84`、`TrackingDetailDrawer:136` | ⚠️ 与上一种不统一 |
| `permissions.includes(...)` | 1 | 仅 `useCurrentUser.ts:81` 内部 | ❌ 禁止业务使用 |
| `roles?.includes('ADMIN')` | 1 | `CompanyInfoView:542` | ❌ 应收敛为权限码 |

### 3.2 后端独立授权（**关键证据**）

`PermissionInterceptor.java`（`security/PermissionInterceptor.java`）执行两层控制：

```java
// 1) 标注 PublicApi 的接口：匿名与登录用户均可访问
if (m.hasMethodAnnotation(PublicApi.class) || m.getBeanType().getAnnotation(PublicApi.class) != null) return true;
// 2) 强制认证：匿名访问非公开接口一律 401
if (!user.isAuthenticated()) throw new NotAuthenticatedException("请先登录");
// 3) 标注 LoginOnly：仅要求登录
if (m.hasMethodAnnotation(LoginOnly.class) || m.getBeanType().getAnnotation(LoginOnly.class) != null) return true;
// 4) 注解授权
RequirePermission rule = m.getMethodAnnotation(RequirePermission.class);
if (rule == null) rule = m.getBeanType().getAnnotation(RequirePermission.class);
if (rule == null) {
    // 未声明权限：读方法放行，写方法一律拒绝
    if (isReadMethod(req.getMethod())) return true;
    throw new SecurityException("接口未声明权限注解，已拒绝此写操作，请联系管理员补充 @RequirePermission 或 @LoginOnly");
}
Set<String> owned = user.permissions();
boolean ok = rule.any() ? Arrays.stream(rule.value()).anyMatch(owned::contains)
                        : Arrays.stream(rule.value()).allMatch(owned::contains);
if (!ok) throw new SecurityException("没有执行此操作的权限");
```

**结论**：后端对每个写接口**独立强制授权**，且**未声明权限的写方法一律拒绝**。因此：

- 前端缺门控 **不等于** 后端失守 —— 职责分离**成立**；
- 但反过来也成立：**前端隐藏按钮绝不能被视为后端权限保障**（本阶段未违反，后续须保持）；
- 前端缺陷的准确定性是**误导性 UI**：暴露必然 403 的入口，浪费用户操作并制造困惑。

### 3.3 逐模块契约矩阵

| 模块 | 前端权限码 | 后端类级 | 后端写方法 | 批量端点 | 契约是否成立 |
|---|---|---|---|---|---|
| 知识产权 certificate | `certificate.manage` (:229) | `certificate.read` (:35) | `certificate.manage` | `PUT /certificates/batch` (:90-91) | 码一致 ✅；**入口未门控** ✗ |
| 包材 packaging | `packaging.manage` (:146) | `packaging.read` (:42) | `packaging.manage` | `PUT /packaging/models/batch` (:110-111) | 码一致 ✅；**入口未门控** ✗ |
| 模具 mold | `mold.manage` (:128) | — | `mold.manage` | `PUT /batch` (:172-173) | 码一致 ✅；**入口未门控** ✗ |
| 产品 product | `product.manage` (:304) | `product.read` (:57) | `product.manage` | `PUT /catalog/models/batch` (:149-150) | 码一致 ✅；**入口未门控** ✗ |
| 自媒体 media | `media.manage` (:362) | `media.read` (:42) | `media.manage` | `PUT|DELETE /media-accounts/batch` (:83-90) | 码一致 ✅；**行级未门控** ✗ |
| 询盘 website | **无定义** | `website.manage` (:32) | `website.manage` | `PUT /batch` (:75-76) | 路由权限=后端权限 ⇒ **无实际越权**，但仍属误导性 UI |

**重要更正**：`WebsiteInquiryView` 虽是唯一「连 `canManageXxx` 都没定义」的页面，但它的**路由权限就是 `website.manage`**，与后端要求完全一致。也就是说：**能进这个页面的用户本来就拥有批量写权限**。因此该页的

- **实际安全风险**：无（无越权可能）
- **问题等级**：**P2**（口径缺失：页面内无权限表达，后人新增入口时易漏判），而非 P0。

这条正好说明为什么本阶段要求「不得假设所有批量模式都等同于批量编辑或删除」，也说明**必须先核对路由权限与后端权限的对应关系，再定级**。

---

## 4. 批量选择状态矩阵

| 页面 | 进入批量模式 | 全选/取消全选 | 跨页保留 | 筛选变化 | 删除/修改成功后 | 操作失败后 |
|---|---|---|---|---|---|---|
| `ProductCatalogView` | 手动「批量管理」 | `toggleSelectAll` (:57-63) | **保留** (:69 不清空) | **保留** (:651-664 不清空) | 清空 | 保留 |
| `PackagingView` | 手动 | `isAllPageSelected` (:162) | **保留** (:56 不清空) | 保留 | 清空 (:250/:274) | 保留 |
| `PackagingMoldView` | 手动 | 子组件内部 | **保留** (子控) | 由子 watch 驱动 | 清空 | 保留 |
| `CertificateView` | 勾选行触发 (:249-255) | `toggleSelectAll` (:263-266) | **保留** (:444/:483 不清空) | **保留** | 清空 (:342-344/:364-366) | 保留 (:345-349/:368-370) |
| `MediaAccountView` | 勾选行触发 (:585) | `toggleSelectAll` (:397) | **丢失** ⚠️ (:582-586) | 丢失 | 清空 (:445-446/:469-470) | 保留 |
| `WebsiteInquiryView` | 勾选行触发 | `toggleSelectAll` | **保留** (:325-356) | 保留 | 清空 | 保留 |

### 4.1 跨页语义分歧（P1）

**5 页保留**（`ProductCatalogView` / `PackagingView` / `PackagingMoldView` / `CertificateView` / `WebsiteInquiryView`）：`selectedIds` 是独立于 el-table 的 reactive Set，翻页只换渲染数据、不清 Set ⇒ 跨页累积。

**1 页丢失**（`MediaAccountView:582-586`）：

```ts
function handleSelectionChange(selection: any[]) {
  selectedIds.clear()
  selection.forEach(row => row.id != null && selectedIds.add(row.id))
  batchMode.value = selection.length > 0
}
```

依赖 el-table 的 `selection-change` **重建**整个 Set。翻页后新页没有选中行 ⇒ `selection` 为空 ⇒ `clear()` 把之前跨页累积的选择**静默清空**；更糟的是 `batchMode` 被置回 false，批量栏直接消失。

**这一点不能靠「全站统一」解决**：批量删除 / 批量修改在产品语义上是「对已勾选的一组记录执行操作」，跨页累积是正确的；而 el-table 的 selection 只是「当前页视图状态」。两者混用才是问题根源。

---

## 5. 已证实缺陷

### P0-1 · 六个页面 `batch-enabled` 写常量，批量入口无权限门控

| 项 | 内容 |
|---|---|
| 文件:行号 | `ProductCatalogView.vue:24`、`PackagingView.vue:15`、`PackagingMoldView.vue:11`、`CertificateView.vue:79`、`MediaAccountView.vue:27`、`WebsiteInquiryView.vue:28` |
| 权限码 / 动作 | `*.manage` × 批量编辑、批量删除 |
| 当前行为 | 常量 `batch-enabled` 恒为 true；只要绑定了 `@batch-edit` / `@batch-remove` 监听器，批量栏与按钮就渲染。**无权限用户勾选行即可进入批量模式并看到写操作入口** |
| 正确预期 | `:batch-enabled="canManageXxx"`，无 `*.manage` 时不出现选择列与批量栏 |
| 前端证据 | `SearchToolbar.vue:447` `showBatchBar = props.batchEnabled && props.batchMode`；`:453-454` `hasBatchEditListener()` / `hasBatchRemoveListener()` —— **判据是监听器是否存在，不含权限语义** |
| 后端证据 | `PermissionInterceptor.java:52-58` 未声明权限的写方法一律拒绝；六个 Controller 的写方法均带 `@RequirePermission("*.manage")` ⇒ 点击只会 403 |
| 最小修复 | 6 处逐个改为 `:batch-enabled="canManageXxx"`；`WebsiteInquiryView` 先补 `const canManageInquiry = computed(() => can('website.manage'))` |
| 验收测试 | e2e 注入 `permissions: []` 与 `['*.manage']` 两种态，断言选择列与批量栏的可见性 |
| 并行风险 | `PackagingView` / `PackagingMoldView` / `MediaAccountView` / `ProductCatalogView` / `WebsiteInquiryView` 当前均未被并行任务修改 → **无冲突**；但 `WebsiteInquiryView` 属官网解耦任务目录，修复前需确认 |

**根因（公共侧）**：`SearchToolbarBatchBar` 只按监听器存在与否渲染按钮，把「是否展示写操作」完全交给页面自觉传 `batch-enabled`。这就是 B7-A §7 记录的「按钮显隐只看监听器，无权限语义」。

### P0-2 · 四个页面行级写按钮与顶部口径不一致

| 项 | 内容 |
|---|---|
| 文件:行号 | `MediaAccountView.vue:177/179/180`；`CertListTable.vue:142-143`；`PackagingMoldsMasterDetail.vue:186/190/194`；`ProductCatalogView.vue:178` |
| 权限码 / 动作 | 编辑 / 删除 / ＋更新 / 删除产品 |
| 当前行为 | 顶部按钮有 `v-if="canManageXxx"`，行内与子组件**没有**，也未向下传权限 prop ⇒ **只读用户可见并点击行内写操作** |
| 正确预期 | 顶部、行内、批量栏三处使用**同一权限口径**；子组件通过 prop 接收 |
| 前端证据 | `CertListTable.vue:140-145` 逐字：`<el-button link @click.stop="$emit('edit', row)">编辑</el-button>` / `<el-button class="lq-row-danger-action" size="small" @click.stop="$emit('delete', row)">删除</el-button>` —— 无任何 `v-if`。`MediaAccountView.vue:175-181` 同理 |
| 后端证据 | 同上，写入口均有 `@RequirePermission` ⇒ 点击 403 |
| 最小修复 | 子组件新增 `canManage?: boolean`（**建议默认 `false`**），父层传入现有 `canManageXxx` 并用 `v-if` 包裹 |
| 验收测试 | e2e 双态断言行内按钮可见性；有权限态断言可见 |
| 并行风险 | `MediaAccountView` 未被并行修改 → 无冲突 |

### P0-3 · `EmployeeDetailPanel` 的 `canManage` 默认 `true`（fail-open）

| 项 | 内容 |
|---|---|
| 文件:行号 | `EmployeeDetailPanel.vue:284`（声明）、`:288`（默认值） |
| 权限码 / 动作 | `employee.manage` × 编辑档案 / 新增映射 |
| 当前行为 | `{ canManage: true, filterOptions: null }` —— **漏传即放行** |
| 正确预期 | 默认 `false`，由父层显式授予 |
| 前端证据 | 逐字：`), { canManage: true, filterOptions: null }` |
| 后端证据 | `EmployeeController` 带 `@RequirePermission`，后端不受影响 |
| 最小修复 | 改为 `withDefaults(..., { canManage: false, filterOptions: null })`；确认 `EmployeeRosterView` 已显式传参（已确认） |
| 验收测试 | 契约测试断言默认值为 `false`；e2e 无权限态断言按钮不可见 |
| 并行风险 | 该文件由我上一阶段（B7-B1c）修改，无他人并行 → 无冲突 |

### P1-1 · `MediaAccountView` 跨页选择静默丢失（与其余 5 页语义相反）

| 项 | 内容 |
|---|---|
| 文件:行号 | `MediaAccountView.vue:582-586`（`handleSelectionChange`） |
| 权限码 / 动作 | 无直接权限码，属状态缺陷 |
| 当前行为 | 用 el-table 当前页 selection 重建 `selectedIds`；翻页 / 筛选后选择被清空，且 `batchMode` 退回 false |
| 正确预期 | 与其余 5 页一致：选择是跨页累积的独立状态，不受翻页 / 筛选影响 |
| 前端证据 | `selectedIds.clear()` 后按 `selection` 重建 —— 只覆盖当前渲染页 |
| 后端证据 | 不适用（纯前端状态） |
| 最小修复 | 改为「按行增量同步」而非整体重建：对离开的选择做 `delete`、对进入的做 `add`，不清空全表；或改用 `useBatchSelection` 的 `toggleSelection` 驱动 |
| 验收测试 | e2e：勾选 → 翻页 → 断言计数仍为 N；筛选变化 → 断言不变 |
| 并行风险 | 无（文件未被并行修改） |

### P1-2 · 子组件权限 prop 缺失（结构性）

| 组件 | 缺失 | 建议 |
|---|---|---|
| `CertListTable.vue` | 无 `canManage` | 新增，默认 `false` |
| `PackagingMoldsMasterDetail.vue` | 无 `canManage`（`enableBatch`/`batchMode` 有） | 新增，默认 `false` |
| `PackagingDetailDrawer.vue` | 无 `canManage`（仅 `modelId`/`focusVariantId`） | 新增，默认 `false` |
| `PackagingAssetPanel.vue` | 无 `canManage` | 新增，默认 `false` |
| `CatalogAssetGallery.vue` | 无 `canManage`（有 `batchMode` 默认 `false`） | 新增，默认 `false` |
| `CompanyBrandAssetsTab.vue` | 无 `canManage`（父级有但未下传） | 新增，默认 `false`；父层传入 `canManageCompany` |

**默认值口径建议**：权限类 prop 一律默认 **`false`**（fail-closed），与 `can()` 的 fail-open 形成反向保护。现状唯一的权限 prop（`EmployeeDetailPanel:288`）恰恰默认 `true`，是明确的反向证据。

---

## 6. 合理例外（**不**应改）

按「不得假设所有批量模式都等同于批量编辑或删除」的要求，以下场景保留批量选择是合理的：

| # | 场景 | 理由 |
|---|---|---|
| 1 | `CustomerView` 的**导出清单 CSV**（含「未收录写法」导出） | 选择用于**导出**而非写入，只读用户也需要 |
| 2 | `PackagingView` / `ProductCatalogView` 的导出 Excel | 同上，属读操作 |
| 3 | `MediaAccountView` 的「登记更新 / 分配发布人 / 创建发布任务」批量动作 | 是**特殊业务操作**，且已由 `:72` 的 `v-if="canManageMedia"` 正确门控 —— 属正面范例 |
| 4 | `CertificateView` 的分类汇总 Tab 切换 | 属视图切换，不涉及选择状态 |
| 5 | `SearchToolbar` 的 `batchEnabled` 默认 `false` | 公共侧默认关闭，安全 |
| 6 | `ManufacturingTaskConfigPanel` 的 `canManageBaseline` 必填无默认值 | 强制调用方显式传入，属正确范式 |

**特别说明**：`MediaAccountView:72-76` 证明了「批量模式 ≠ 批量编辑/删除」—— 该页的批量动作是业务登记类操作，且已正确门控。修复时**不得**把该页的批量模式一刀切地关掉，只应修 `batch-enabled` 绑定与行级按钮。

---

## 7. B7-B2b 分批修复计划

原则：**每批最多 3 个业务页面及其直接必要子组件**；禁止一次性重构权限框架；禁止把前端隐藏按钮视为后端权限保障；不为统一而改变原有合法业务权限范围。

### 批次 1（低风险 · 纯绑定改正）

| # | 页面 | 改动 |
|---|---|---|
| 1 | `CertificateView.vue` | `:batch-enabled="canManageCertificate"`（:79） |
| 2 | `PackagingView.vue` | `:batch-enabled="canManagePackaging"`（:15） |
| 3 | `ProductCatalogView.vue` | `:batch-enabled="canManageProduct"`（:24） |

- 涉及子组件：`CertListTable`（随 #1 加 `canManage` prop）
- 依赖：无
- 验收：三页 e2e 双态断言 + `FormLifecycleGuards` 契约测试扩展

### 批次 2（低-中风险 · 子组件权限 prop）

| # | 页面 | 改动 |
|---|---|---|
| 4 | `PackagingMoldView.vue` + `PackagingMoldsMasterDetail.vue` | `:batch-enabled="canManageMold"`（:11）+ 子组件新增 `canManage`（默认 `false`）门控 :186/:190/:194 |
| 5 | `MediaAccountView.vue` | `:batch-enabled="canManageMedia"`（:27）+ 行内 :177/:179/:180 加 `v-if` |

- 依赖：批次 1 完成（统一口径后便于回归对比）
- 验收：双态断言 + 跨页选择语义专项用例

### 批次 3（中风险 · 抽屉 / 面板类批量写链路）

| # | 页面 | 改动 |
|---|---|---|
| 6 | `PackagingDetailDrawer.vue` | 新增 `canManage` prop（默认 `false`）门控 :83 批量入口、:100-150 批量栏、:761 删除主档 |
| 7 | `PackagingAssetPanel.vue` / `CatalogAssetGallery.vue` | 新增 `canManage` prop 门控批量标签 / 批量删除 |
| 8 | `CompanyBrandAssetsTab.vue` | 父级下传 `canManageCompany` 并门控 :31/:43/:141 |

- 依赖：批次 1、2（先确立 prop 约定）
- 风险：抽屉类组件调用方多，需逐个确认传参

### 批次 4（收尾 · 跨页语义与 fail-closed）

| # | 事项 |
|---|---|
| 9 | 统一 `MediaAccountView:582-586` 的跨页选择语义（改为增量同步） |
| 10 | `EmployeeDetailPanel.vue:288` 默认值改 `false` |
| 11 | 复核 `DefaultLayout:253/255` 菜单 `permission` 漏配项 |

- 依赖：批次 1~3（口径统一后再动语义）

### 暂缓（有并行冲突或属他阶段）

| 事项 | 归属 |
|---|---|
| `WebsiteInquiryView` 补 `canManageInquiry` 定义 | 官网解耦任务目录，修复前需确认 |
| `TrackingGeneralBaselineDialog` / `TrackingProductBenchmarkPanel` / `ManufacturingWorkshopPanel` 的批量与权限 | 并行 tracking 任务目录 |
| `WebsiteProductVariantsDialog` / `WebsiteProductGalleryDialog` | 并行官网任务目录 |

---

## 8. 回归测试建议

### 8.1 本阶段已落地

| 文件 | 内容 |
|---|---|
| `frontend/e2e/b7-b2a-permission-batch.spec.ts` | 4 条用例：只读用户批量栏可见（P0-1）、只读用户行内写按钮可见（P0-2）、有权限对照 |
| `frontend/e2e/fixtures/certificate-batch.{html,ts}` | CertificateView + `LqConfirmDialog` 宿主 |
| `frontend/e2e/fixtures/media-account.{html,ts}` | MediaAccountView + `LqConfirmDialog` 宿主 |

### 8.2 修复阶段须补

1. **双态矩阵用例**：对批次 1~3 的每个页面，注入 `permissions: []` 与 `['*.manage']`，断言：选择列、批量入口、行内写按钮三处可见性一致。
2. **跨页选择专项**：勾选 → 翻页 → 断言计数不变；改筛选 → 断言计数不变。当前 `MediaAccountView` 会失败，修复后转绿。
3. **批量成功 / 失败**：成功后选择清空；失败后选择保留（已按源码核对，见下）。
4. **契约测试扩展**：`FormLifecycleGuards.contract.test.ts` 增加「权限 prop 默认值为 `false`」的断言。

### 8.3 关于「批量成功 / 失败后选择变化」的说明

这两条**未纳入自动化用例**，原因是页面删除链路走全局 `askConfirm`，由 `App.vue` 挂载的 `LqConfirmDialog` 负责渲染，且该组件靠 props 逐个接收弹窗内容；隔离夹具下无法稳定驱动确认弹窗，强驱动会引入与产品无关的不稳定断言。

已按源码逐行核对，结论如下（`CertificateView.vue`）：

```ts
async function applyBatchEdit(payload: Record<string, unknown>) {   // :334
  const ids = batchSelection.getSelectedIds().map(Number)
  ...
  try {
    await batchUpdateCertificates(ids, payload)                      // :339
    notify(`已更新 ${ids.length} 个证书`)
    batchEditModal.visible = false
    batchSelection.clearSelection()                                  // :342  ← 成功：清空
    batchMode.value = false                                          // :343
    await Promise.all([loadList(), loadStats()])
  } catch (err) {
    notify.errorWithDetail('批量修改失败', err)                       // :346  ← 失败：不清空
  }
}
```

`handleBatchDelete`（:352-371）结构相同：成功 `clearSelection()`（:364）+ `batchMode=false`（:365），失败仅 `notify.errorWithDetail`（:369）。

**评价**：该语义是**正确**的 —— 成功后清空避免对已删除记录重复操作；失败后保留让用户可直接重试，无需重新勾选。本阶段**不建议改动**。

---

## 9. 与其他并行任务的边界

### 9.1 本阶段实际改动

**生产代码：零改动。** 本阶段为只读审计。

新增文件（均为测试与文档，不影响运行时）：

| 文件 | 类型 |
|---|---|
| `frontend/e2e/b7-b2a-permission-batch.spec.ts` | E2E 用例 |
| `frontend/e2e/fixtures/certificate-batch.html` / `.ts` | 测试夹具 |
| `frontend/e2e/fixtures/media-account.html` / `.ts` | 测试夹具 |
| `docs/frontend-ui-b7-b2a-permission-batch-audit.md` | 本报告 |

### 9.2 并行任务占用面（本阶段未触碰）

| 目录 / 文件 | 并行任务 |
|---|---|
| `backend/**` | 官网 / 后端任务（本阶段完全未进入） |
| `corporate-website/**` | 官网解耦任务 |
| `frontend/src/api/index.ts`、`api/website/*` | 官网解耦任务 |
| `frontend/src/apps/archive/navigation.ts`、`routes.ts` | 官网解耦 + 路由任务 |
| `frontend/src/apps/archive/views/marketing/Website*` | 官网解耦任务 |
| `frontend/src/components/website/*` | 官网解耦任务 |
| `frontend/src/components/production/Tracking*` | tracking 任务 |
| `frontend/src/apps/archive/views/customer/CustomerView.vue` | 客户区域任务 |
| `frontend/src/apps/archive/views/product/OfficeAssetView.vue` | B6-2 试点（我上一阶段成果，保留） |

### 9.3 审计对象与并行任务的关系

本阶段审计的 6 个页面中，`PackagingView`、`PackagingMoldView`、`MediaAccountView`、`ProductCatalogView` **当前均未被并行任务修改** → 修复阶段无冲突。

`WebsiteInquiryView` 属官网解耦任务目录 → 其修复（P2，补权限定义）**已登记，本阶段不修**。

### 9.4 边界声明

- 未修改任何生产代码
- 未修改任何公共组件（`SearchToolbar` / `SearchToolbarBatchBar` / `LqDialog` / `LqConfirmDialog` 等）
- 未修改后端、数据库、Flyway
- 未执行 `git clean` / `reset --hard` / `stash`
- 未提交、未推送
- 未查询生产库、未做任何数据清理
- 测试产物写入独立临时目录，未删除其它任务产物

---

## 10. 门禁结果

本阶段**无生产代码改动**，按约定只执行必要的只读验证与测试基线核对。

| 门禁项 | 结果 |
|---|---|
| 三个组件 E2E（B7-B1c） | ✅ 11 passed |
| 新增权限 / 批量 E2E（B7-B2a） | ✅ 4 passed |
| 既有 E2E 全量（5 个 spec） | ✅ 61 passed（57 既有 + 4 新增，零回归） |
| 全量 Vitest | ✅ 738 passed / 2 failed（Tracking 既有遗留，与本阶段无关） |
| Typecheck（`vue-tsc --noEmit`） | ✅ 通过 |
| Lint（`eslint src/ --quiet`） | ✅ 通过 |
| 完整构建 | ✅ `built in 26.85s`（独立临时目录 `C:/Users/20676/AppData/Local/Temp/b2a-build`） |

**2 条 Tracking 既有失败单独记录**：`TrackingAnalyticsDrilldownCells.test.ts › TrackingAnalyticsSpecCell › 只有规格时不留下孤零零的分隔符`。本阶段未修改任何生产跟单业务。
