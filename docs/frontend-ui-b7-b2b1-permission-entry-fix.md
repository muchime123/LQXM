# 前端统一 UI 体系 B7-B2b-1：权限入口与批量写操作门控修复

> 阶段：B7-B2b-1（本批最多 3 个业务页面 / 业务组件）
> 依据：`docs/frontend-ui-b7-b2a-permission-batch-audit.md` 的事实证据
> 状态：完成，已停止在 B7-B2b-1，未提交、未推送
> 边界：只处理**权限表达**，不动后端权限模型、不改业务授权范围、不改跨页选择语义

---

## 1. 最新代码基准与工作区状态

| 项 | 值 |
|---|---|
| HEAD | `bf3684f7d7271a81e71e241524105a91a2abb46c` |
| 当前分支 | `main` |
| 本地 `main` | `bf3684f7`（与 HEAD 一致） |
| `origin/main` | `1999e3c5` |
| ahead / behind | **ahead 10 / behind 0** |
| git stash | **空** |
| 工作区变更总数 | **224 项**（B7-B2a 记录时为 207，期间并行任务持续推进 +17） |

### 1.1 前置成果保留确认

| 成果 | 状态 |
|---|---|
| B6-1 / B6-2（`OfficeAssetView` 等） | ✅ 保留 |
| B7-B1a / B1b / B1c 契约与 E2E | ✅ 保留 |
| B7-B2a 审计报告与 E2E | ✅ 保留（未删减任何断言） |
| 报告文件 | `frontend-ui-b7a-*`、`*b7b0*`、`*b1a*`、`*b1b*`、`*b1c*`、`*b2a*` 均在 |

**未执行** `git clean` / `reset --hard` / `stash`。

---

## 2. 本批选定的 3 个对象及选择理由

### 2.1 并行冲突筛查（前置条件）

对 B7-B2a 清单上的全部候选做了 `git status` 逐文件核对：

| 文件 | 状态 | 结论 |
|---|---|---|
| `employee/EmployeeDetailPanel.vue` | M（B7-B1c 我自己改的） | ✅ 可用 |
| `system/EmployeeRosterView.vue` | 干净 | ✅ 可用 |
| `marketing/MediaAccountView.vue` | 干净 | ✅ 可用 |
| `product/ProductCatalogView.vue` | 干净 | ✅ 可用 |
| `product/PackagingView.vue` | 干净 | ⏸ 下批 |
| `product/mold/PackagingMoldView.vue` | 干净 | ⏸ 下批 |
| `product/CertificateView.vue` | **M（并行任务已改）** | ⛔ **跳过并记录** |
| `marketing/WebsiteInquiryView.vue` | **M（官网解耦任务）** | ⛔ **禁止修改** |
| `customer/CustomerView.vue` | M | ✅ 正确范式，不动 |
| `product/OfficeAssetView.vue` | M（B6-2 试点） | ✅ 已修复，不动 |

### 2.2 选定结果

| # | 对象 | 对应缺陷 | 选择理由 |
|---|---|---|---|
| 1 | `components/employee/EmployeeDetailPanel.vue` | B7-B2a **P0-3** | 用户指定的**第一优先**；且是唯一一个**默认值 fail-open** 的权限 prop，性质最严重 |
| 2 | `apps/archive/views/marketing/MediaAccountView.vue` | B7-B2a **P0-2** | 4 个行级不一致页面中**行级写入口最多**（＋更新 / 编辑 / 删除 3 个），业务风险最高；文件干净无冲突 |
| 3 | `apps/archive/views/product/ProductCatalogView.vue` | B7-B2a **P0-1 + P0-2** | 单文件同时覆盖「批量入口未门控」和「行级删除未门控」两项，性价比最高；文件干净无冲突 |

### 2.3 被跳过的对象（如实记录）

**`CertificateView.vue`** —— B7-B2a 时该文件是干净的，本批前置检查发现它**已被并行任务修改**（10 行 diff，内容为搜索-分页联动修复：`@change="loadList"` → `@change="doSearch"`、`resetFilters(doSearch)` → `resetFilters(applyFilters)`）。

按「如果某个文件有并行修改，应跳过并记录，不得抢改」，本批不碰该文件。其 P0-1（`batch-enabled` 常量）与 P0-2（`CertListTable` 行级）留待下批，且**下批必须重新核对**该文件状态。

---

## 3. 前后端权限码对应矩阵

| # | 对象 | 前端权限码 | 前端判定方式 | 后端 Controller 注解 | 路由权限 | 三者是否同口径 |
|---|---|---|---|---|---|---|
| 1 | `EmployeeDetailPanel` | `system.employee.manage` | prop `canManage`（父级传 `can('system.employee.manage')`） | `EmployeeController:26` 类级 `@RequirePermission("system.employee.manage")` | `routes.ts:247` `system.employee.manage` | ✅ 一致 |
| 2 | `MediaAccountView` | `media.manage` | `computed(() => can('media.manage'))` (`:362`) | 类级 `media.read` + 写方法 `media.manage` | `media.read` | ✅ 写口径一致 |
| 3 | `ProductCatalogView` | `product.manage` | `computed(() => can('product.manage'))` (`:304`) | 类级 `product.read` + 写方法 `product.manage` | `product.read` | ✅ 写口径一致 |

### 3.1 前端权限机制（本批沿用，未改动）

`frontend/src/composables/auth/useCurrentUser.ts:78-82`

```ts
function can(permission?: string | string[]) {
  if (!permission) return true          // 空/未声明 → 放行（fail-open）
  const list = Array.isArray(permission) ? permission : [permission]
  return list.some(x => user.value?.permissions.includes(x))
}
```

**重要**：`can()` 本身是 **fail-open** 的。这更凸显了权限 **prop 默认值必须 fail-closed** —— 两处默认逻辑必须反向，否则「漏传 + 漏判」会叠加成完全无门控。

### 3.2 后端独立授权（安全边界，本批未触碰）

`backend/.../security/PermissionInterceptor.java:52-58`

```java
if (rule == null) {
    if (isReadMethod(req.getMethod())) return true;
    throw new SecurityException("接口未声明权限注解，已拒绝此写操作，请联系管理员补充 @RequirePermission 或 @LoginOnly");
}
```

**本批修复不改变安全边界**：所有涉及写入口的后端授权原本就存在且独立生效。修复解决的是**前端误导性 UI**（暴露必然 403 的入口），而非越权。

---

## 4. 修复前的真实失败证据

### 4.1 取证方法

按「先红后绿」要求：**先**按 B7-B2a 清单写好用例，**再**临时回退三处修复跑一遍取证，**最后**回补修复跑绿。

- 环境：真实 Playwright + Edge（`playwright.config.ts` 的 `edge` project）
- 数据：隔离 Mock API（`page.route` 拦截全部 `/api/`），不连接后端、无写入副作用
- 权限注入：拦截 `GET /api/auth/me` 返回指定 permissions 列表
- 无权限用户构造：**路由权限允许进入、但 permissions 中无对应 manage 码**，不强行 push 不可达路由制造假阳性

### 4.2 E-1 红态实录

用例：挂载 `EmployeeDetailPanel` 且**不传** `can-manage`（生产环境真实形态 —— 唯一父级此前完全没传），断言不出现写操作入口。

```
Error: expect(locator).toHaveCount(expected) failed
Locator:  getByRole('button', { name: '编辑档案' })
Expected: 0
Received: 1
```

页面快照证明确实渲染了详情数据（说明不是挂载失败导致的假红）：

```yaml
- generic [ref=e3]:
  - generic [ref=e4]:
    - navigation [ref=e5]:
      - button "基本信息" ...
      - button "身份映射 1" ...
      - button "角色权限" ...
      - button "登录账号" ...
      - button "档案文件 0" ...
    - generic [ref=e13]:
      - button "编辑档案" [ref=e14]
      - button "删除 / 离职" [ref=e16]
  - generic [ref=e19]:
    - generic [ref=e20]:
      - generic [ref=e21]: 在职
      - generic [ref=e22]: LQ001
```

**结论**：修复前，即使用户**完全没有** `system.employee.manage`，面板依然暴露「编辑档案」「删除 / 离职」，以及「新增映射」、文件删除等写操作入口。缺陷成立。

### 4.3 绿态对照

回补修复后同一用例转绿：

```
ok 22 › E · EmployeeDetailPanel 权限默认值 › E-1 未显式传入 can-manage 时，不得暴露写操作入口（fail-closed） (2.3s)
```

且 `E-3`（只读用户态）同样通过，证明**数据可正常浏览、仅写操作被门控**。

---

## 5. 精确代码修改

本批共改 3 个业务文件（+1 个必要父级接线），**公共组件零改动**。

### 5.1 `frontend/src/components/employee/EmployeeDetailPanel.vue`

**改动点**：`withDefaults` 的默认值 `canManage: true` → `false`。

```diff
   defineProps<{
     employeeId: number | null
-    /** 是否可维护员工档案（employee.manage 权限） */
+    /**
+     * 是否可维护员工档案（`system.employee.manage` 权限）。
+     *
+     * <p>默认 **false**（fail-closed）：写权限必须由父级**显式授予**。
+     * 此前默认为 `true`，而唯一父级 `EmployeeRosterView` 并未传该 prop，
+     * 相当于「漏传即放行」—— 与本仓库其它权限 prop 的口径相反。
+     */
     canManage?: boolean
     /** 身份映射来源系统选项 */
     filterOptions?: EmployeeFilterOptions | null
   }>(),
-  { canManage: true, filterOptions: null }
+  { canManage: false, filterOptions: null }
 )
```

**不动的部分（保留既有防护）**：

- `:5` / `:6` 头部按钮已有 `v-if="canManage"` —— 保留
- `:47` 新增映射、`:84` 操作列、`:167/:182/:198/:238` 各动作区已有 `v-if="canManage"` —— 保留
- `:388/:481/:502/:523/:545/:652` 写函数内部的 `if (!detail.value || !props.canManage) return` 二次校验 —— 保留

即：本改动只是把**兜底默认值**从「放行」改成「拒绝」，真正的门控逻辑早已存在且未被触碰。

### 5.2 `frontend/src/apps/archive/views/system/EmployeeRosterView.vue`

**改动点**：补上此前完全缺失的显式传参 + 权限口径定义。

```diff
 import { notify } from '@/composables/ui/useToast'
+import { useCurrentUser } from '@/composables/auth/useCurrentUser'
 import type { EmployeeDetail, EmployeeFilterOptions, EmployeePayload, EmployeeSummary } from '@/types/employee'

+// ========== 权限 ==========
+/**
+ * 员工档案写权限：`system.employee.manage`。
+ *
+ * <p>与后端 `EmployeeController` 类级 `@RequirePermission("system.employee.manage")`
+ * 及本页路由 `routes.ts:247` 的 `meta.permission` 三者同口径。
+ *
+ * <p>必须**显式**传给 `EmployeeDetailPanel`：该 prop 默认为 `false`（fail-closed），
+ * 漏传不会放开写操作。
+ */
+const { can } = useCurrentUser()
+const canManageEmployee = computed(() => can('system.employee.manage'))
+
 // ========== 筛选 / 分页 ==========
 const route = useRoute()
```

```diff
       <EmployeeDetailPanel
         :key="`${selectedEmployeeId ?? 'none'}-${detailRefreshKey}`"
         :employee-id="selectedEmployeeId"
         :filter-options="filterOptions"
+        :can-manage="canManageEmployee"
         @edit="openEditFromPanel"
         @delete="confirmDelete"
       />
```

**为什么这样改**：该文件此前**完全没有** `can('...')` 调用，也没有传 `can-manage`，因此子组件永远走默认值。修复必须两侧同时落地，否则单改默认值会让管理员也失去写能力。

### 5.3 `frontend/src/apps/archive/views/marketing/MediaAccountView.vue`

**改动点**：行级 3 个写按钮加 `v-if="canManageMedia"`；**「详情」不加**（读操作）。

```diff
         <el-table-column label="操作" fixed="right" width="280" align="center">
           <template #default="{ row }">
-            <button type="button" class="row-btn accent" @click="quickAddUpdate(row)">＋更新</button>
-            <button type="button" class="row-btn" @click="openDetail(row)">详情</button>
-            <button type="button" class="row-btn" @click="openEditor(row)">编辑</button>
-            <button type="button" class="row-btn danger" @click="removeAccount(row)">删除</button>
+            <!-- 写操作与顶部「新建账号 / 导入」同口径：`media.manage`。
+                 「详情」是读操作，对只读用户保留。 -->
+            <button v-if="canManageMedia" type="button" class="row-btn accent" @click="quickAddUpdate(row)">
+              ＋更新
+            </button>
+            <button type="button" class="row-btn" @click="openDetail(row)">详情</button>
+            <button v-if="canManageMedia" type="button" class="row-btn" @click="openEditor(row)">
+              编辑
+            </button>
+            <button v-if="canManageMedia" type="button" class="row-btn danger" @click="removeAccount(row)">
+              删除
+            </button>
           </template>
         </el-table-column>
```

**刻意不做的**：
- 未改 `batch-enabled`（留待下批，避免与行级改动耦合）
- 未动 `:72-76` 的 `#batch-actions` 槽（那里**已有** `v-if="canManageMedia"`，是正确范式）
- 未碰跨页选择语义（明确禁止）

### 5.4 `frontend/src/apps/archive/views/product/ProductCatalogView.vue`

**改动点**：`batch-enabled` 常量 → 绑定；行级「删除产品」补门控。

```diff
         :view-toggle-visible="!isCompactViewport"
-        batch-enabled
+        :batch-enabled="canManageProduct"
         :batch-mode="batchMode"
```

```diff
-          <button type="button" class="danger" @click="handleTitleMenuRemove">删除产品</button>
+          <!-- 与顶部「新建产品 / 导入 / 导出」同口径：`product.manage` -->
+          <button v-if="canManageProduct" type="button" class="danger" @click="handleTitleMenuRemove">
+            删除产品
+          </button>
         </div>
```

**口径依据**：顶部 `:40` 导出、`:43` 导入、`:47` 新建产品**均已**用 `canManageProduct` 门控。行级删除现在与它们同口径，没有任何扩权或收权。

### 5.5 关于 `SearchToolbarBatchBar`（未修改，说明理由）

用户要求「不要把 `SearchToolbarBatchBar` 改造成全局权限引擎」。本批**未改该组件**。

原因：`batch-enabled` 已经是页面侧的权限钩子，门控责任在页面。把权限判断塞进批量栏会引入「组件猜页面权限」的反向依赖。本批只让页面正确传参。

---

## 6. 修复后权限双态 E2E 结果

### 6.1 本批新增：`frontend/e2e/b7-b2b1-permission-entry.spec.ts`

| 用例 | 权限态 | 断言 | 红态 | 绿态 |
|---|---|---|---|---|
| **E-1** | 有 manage，父级**未传** prop | 写操作入口不得暴露 | ✗ 实得 1 个「编辑档案」 | ✅ |
| **E-2** | 有 manage，父级**显式传入** | 写操作入口正常可见（管理员不回归） | — | ✅ |
| **E-3** | 仅 read（无 manage） | 数据可浏览 + 写入口不暴露 | — | ✅ |

**E-1 / E-2 构成完整的双态对照**：
- E-2 证明修复**没有削弱**管理员（有权限 + 显式传参 ⇒ 按钮照常出现）
- E-1 证明修复**堵住了**漏传 ⇒ 不放开

### 6.2 门禁实跑

```
64 passed (1.2m)
```

覆盖 7 个 spec 文件：

| spec | 结果 |
|---|---|
| `b7-b2b1-permission-entry.spec.ts`（本批新增 3 条） | ✅ |
| `b7-b2a-permission-batch.spec.ts`（4 条） | ✅ 零回归 |
| `b7-b1c-form-lifecycle.spec.ts` | ✅ |
| `b7-b1b-search-pagination.spec.ts` | ✅ |
| `office-asset-form-lifecycle.spec.ts`（4 条） | ✅ |
| `office-asset-standard-page.spec.ts` | ✅ |
| `operation-log-standard-list.spec.ts` | ✅ |

---

## 7. 对只读选择和正常管理功能的回归验证

### 7.1 只读浏览不回归

| 验证项 | 结果 |
|---|---|
| E-1：只读态下详情数据仍正常渲染（`.employee-detail-panel` 含「示例员工」） | ✅ |
| E-3：仅 read 权限用户可正常查看档案 | ✅ |
| `MediaAccountView` 行内「详情」按钮对只读用户**保留** | ✅ 契约断言 |
| `CustomerView` / `OfficeAssetView` 的 `:batch-enabled` 绑定未被改动 | ✅ 契约断言 |

### 7.2 管理员能力不回归

| 验证项 | 结果 |
|---|---|
| E-2：有 manage + 显式传参 ⇒ 「编辑档案」「删除 / 离职」照常可见 | ✅ |
| `EmployeeRosterView` 显式传 `canManageEmployee`（= `can('system.employee.manage')`） | ✅ |
| 路由 `/system/employees` 本身要求 `system.employee.manage` ⇒ 能进页面的用户 `can()` 恒为真 | ✅ |

**关键点**：由于路由已要求 `system.employee.manage`，任何能到达该页面的用户 `canManageEmployee` 都为 `true`，因此**管理员现有操作能力完全不变**。这正是「保持合法管理员的现有操作能力」的落地方式 —— 不是靠绕过权限，而是靠口径对齐。

### 7.3 合理例外未受影响

导出类、只读选择类能力均未被本批改动波及（详见 B7-B2a §6）。

---

## 8. 全量测试与构建结果

Vitest 使用 B7-B0 验证成功的规范化大写盘符路径（`cd /d C:\Users\20676\source\LQXM\frontend`）。

| 门禁项 | 命令 | 结果 |
|---|---|---|
| 本批新增权限 E2E | `npx playwright test e2e/b7-b2b1-permission-entry.spec.ts …` | ✅ 3 passed |
| 既有 B7-B2a E2E | 同上（合跑） | ✅ 4 passed |
| B6 / B7 已有业务 E2E | 同上（合跑 7 个 spec） | ✅ **64 passed**，零回归 |
| 公共组件契约 | `vitest run`（全量） | ✅ 见下 |
| 全量 Vitest | `node ./node_modules/vitest/vitest.mjs run` | ✅ **82 passed / 1 failed**（文件）；**751 passed / 2 failed**（用例） |
| Typecheck | `npm run typecheck`（`vue-tsc --noEmit --skipLibCheck`） | ✅ 无错误 |
| Lint | `npm run lint`（`eslint src/ --quiet`） | ✅ 无错误、无警告 |
| 完整构建 | `npx vite build --outDir <独立临时目录> --emptyOutDir` | ✅ 成功（`index.html` + 完整 `assets/`） |

**新增契约测试**：`src/components/common/__tests__/PermissionEntryGates.contract.test.ts`（11 条断言），纳入全量 Vitest。

### 8.1 Tracking 两条既有失败（单独登记，不代修）

```
Test Files  1 failed | 82 passed (83)
     Tests  2 failed | 751 passed (753)
```

失败项仍是 `TrackingAnalyticsSpecCell › 只有规格时不留下孤零零的分隔符`，与 memory 记录的遗留一致，**非本阶段引入**。本阶段未修改任何生产跟单业务。

---

## 9. 未处理的权限问题及下批建议

### 9.1 未完成事项（如实说明）

| # | 事项 | 现状 | 原因 |
|---|---|---|---|
| 1 | `MediaAccountView` 行级门控的**浏览器验证** | 仅源码级契约断言 | 该页在 `script setup` 顶层 `await loadAll()`，隔离夹具下挂载链路依赖较多（列表 + 统计 + 运营/发布人/平台配置等多路接口），首轮驱动失败 |
| 2 | `ProductCatalogView` 批量入口的**浏览器验证** | 仅源码级契约断言 | 同上：顶层 `await Promise.all([loadModels(0), loadInitialFilters()])` |
| 3 | `CertificateView` 的 P0-1 / P0-2 | 未修 | 文件已被并行任务修改，按规则跳过 |
| 4 | 其余 3 个 `batch-enabled` 常量页 | 未修 | 超出本批 3 个对象的上限 |

### 9.2 B7-B2b-2 建议批次

**批次 2-A（补齐本批浏览器验证）**

1. 为 `MediaAccountView` / `ProductCatalogView` 建立能命中顶层 `await` 的 Mock（需覆盖 `/api/media-accounts`、`/api/media-accounts/stats`、`/api/operators`、`/api/publish-owners`、`/api/platform-configs`；`/api/catalog/models`、`/api/catalog/models/filters`）
2. 为本批 3 个对象补齐「有 manage / 仅 read」双态浏览器断言

**批次 2-B（剩余批量入口）**

3. `PackagingView.vue:15` → `:batch-enabled="canManagePackaging"`
4. `PackagingMoldView.vue:11` → `:batch-enabled="canManageMold"` + `PackagingMoldsMasterDetail.vue` 行级门控
5. `MediaAccountView.vue:27` → `:batch-enabled="canManageMedia"`

**批次 2-C（冲突解除后）**

6. `CertificateView.vue` —— **必须重新核对**是否被并行任务释放；其 P0-1 + `CertListTable` 行级一并处理
7. `WebsiteInquiryView.vue` —— 属官网解耦任务目录，修复前需与官网任务确认

### 9.3 结构性建议（下批或更后）

- 权限 prop 默认值统一为 **fail-closed**（`false`），与本批口径一致
- 涉及写操作的子组件，权限一律由父级显式传入，不依赖默认值
- `DefaultLayout.vue:253/255` 的菜单 `permission` 漏配项需补（当前 `can(undefined) → true`）

---

## 10. 并行任务和提交边界

### 10.1 本批改动清单

**生产代码（3 个业务文件 + 1 个必要父级接线）：**

| 文件 | 改动 |
|---|---|
| `frontend/src/components/employee/EmployeeDetailPanel.vue` | `canManage` 默认值 `true` → `false` |
| `frontend/src/apps/archive/views/system/EmployeeRosterView.vue` | 补 `:can-manage="canManageEmployee"` + `can('system.employee.manage')` |
| `frontend/src/apps/archive/views/marketing/MediaAccountView.vue` | 行级 ＋更新 / 编辑 / 删除 加 `v-if="canManageMedia"` |
| `frontend/src/apps/archive/views/product/ProductCatalogView.vue` | `batch-enabled` 改绑定 + 行级「删除产品」加 `v-if="canManageProduct"` |

**测试与文档（不影响运行时）：**

| 文件 | 类型 |
|---|---|
| `frontend/e2e/b7-b2b1-permission-entry.spec.ts` | E2E（3 条） |
| `frontend/e2e/fixtures/employee-detail-default.{html,ts}` | 夹具 |
| `frontend/e2e/fixtures/product-catalog.{html,ts}` | 夹具 |
| `frontend/src/components/common/__tests__/PermissionEntryGates.contract.test.ts` | 契约（11 条） |
| `docs/frontend-ui-b7-b2b1-permission-entry-fix.md` | 本报告 |

### 10.2 并行任务占用面（本批未触碰）

| 目录 / 文件 | 并行任务 |
|---|---|
| `backend/**` | 官网 / 后端任务（完全未进入） |
| `corporate-website/**` | 官网解耦 |
| `frontend/src/api/index.ts`、`api/website/*` | 官网解耦 |
| `frontend/src/apps/archive/navigation.ts`、`routes.ts` | 官网解耦 + 路由（仅**只读**，未改） |
| `frontend/src/apps/archive/views/marketing/Website*` | 官网解耦 |
| `frontend/src/components/website/*` | 官网解耦 |
| `frontend/src/components/production/Tracking*` | tracking |
| `frontend/src/apps/archive/views/customer/CustomerView.vue` | 客户区域（正确范式，不动） |
| `frontend/src/apps/archive/views/product/CertificateView.vue` | **已被占用 → 跳过** |
| `frontend/src/apps/archive/views/product/OfficeAssetView.vue` | B6-2 试点（已修，不动） |

### 10.3 边界声明

- 未修改任何**公共组件**（`SearchToolbar` / `SearchToolbarBatchBar` / `LqDialog` / `LqConfirmDialog` 等）
- 未修改后端 Controller、Service、`PermissionInterceptor`
- 未修改数据库与 Flyway
- 未修改 Tracking、Website 等并行任务文件
- 未修改已稳定的分页、表格、弹窗、抽屉公共逻辑
- **未修改 `MediaAccountView` 的跨页选择语义**
- 未调整任何权限码、未扩大或缩小合法业务授权范围
- 未删减任何既有测试断言（B7-B2a 的 4 条 E2E 全部保留并通过）
- 未执行 `git clean` / `reset --hard` / `stash`
- **未提交、未推送**
- 未删除其他 Agent 生成的文件
- 测试产物写入独立临时目录（`C:/Users/20676/AppData/Local/Temp/b2b1-*`）
