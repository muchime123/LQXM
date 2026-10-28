# 前端统一 UI 体系 B1：公共组件契约测试基线报告

> 阶段性质：只新增测试文件与设计文档。**未修改任何公共组件、业务页面、CSS、数据库或后端配置**，未提交、未推送。
> 上游输入：`docs/frontend-ui-p0a-audit.md`、`docs/frontend-ui-p0b0-remediation-plan.md`
> 生成日期：2026-10-27
>
> **2026-10-27 修订（业务方口径）**：① 测试按「现状契约测试 / 新增能力测试」二分，新增能力测试**允许先失败**（以 `it.fails()` 标记，不阻断 CI），不再要求未实现的新功能在旧代码上先通过；② `LqTable` 属性透传规则由「永久禁止显式声明」改为**条件式**：声明即须同步实现向内部 `el-table` 的转发，并用兼容测试证明行为等价；③ 阶段编号按业务方确认的新顺序对齐（**B2 = `LqPagination` 解耦，B3 = `LqTable` 类型与接口完善**），`LqDialog` 收敛后移至 B4。

---

## 1. 执行基准与修改文件

### 1.1 版本基准

| 项 | 值 |
|---|---|
| 分支 / HEAD | `main` / `42bc081c271124620254a7fe79fff55d00a7e904`（与 P0-A / P0-B0 同一提交，未前进） |
| 是否已提交 | 否（工作区改动） |
| 前端测试框架 | **vitest `^4.1.9`** + **@vue/test-utils `^2.4.11`** + **happy-dom `^20.10.6`** |
| 测试配置位置 | **内联在 `frontend/vite.config.js` 的 `test` 段**（`environment: 'happy-dom'`、`include: ['src/**/*.{test,spec}.{js,ts}']`、`globals: true`）。**不存在** `vitest.config.ts` |
| 类型检查 | `vue-tsc --noEmit --skipLibCheck`（`vue-tsc ^2.2.12`） |
| Lint | `eslint src/ --quiet`（eslint `^9.39.4` + eslint-plugin-vue `^9.33.0`） |

**测试配置补充：无。** 现有 `vite.config.js` 的 `test` 段已满足需求，本轮**未新增任何配置文件、未安装任何依赖**，全部复用项目既有工具与命令。

### 1.2 本轮修改的文件（B1 产出）

**新增 4 个测试文件：**

| 文件 | 用途 |
|---|---|
| `frontend/src/components/common/__tests__/LqTable.attrs.test.ts` | `LqTable` 属性透传 / 空态 / 实例方法契约 + 真实 Element Plus 集成 |
| `frontend/src/components/common/__tests__/LqDialog.close.contract.test.ts` | `LqDialog` 关闭通道 / 事件唯一性 / beforeClose / 嵌套 / 生命周期契约 + 真实 Element Plus 集成 |
| `frontend/src/components/common/__tests__/LqPagination.contract.test.ts` | `LqPagination` 对象模式回归 + B3 独立状态模式待实现用例 |
| `frontend/src/components/common/__tests__/SearchToolbar.debounce.test.ts` | `SearchToolbar` 防抖 / 提交入口 / 输入法 / 生命周期 / 状态同步契约 |

**修改 1 个既有测试文件：**

| 文件 | 改动方式 |
|---|---|
| `frontend/src/composables/list/__tests__/useListPagination.test.ts` | **仅在文件末尾追加** 3 个 `describe`（响应字段优先级 4 条、边界条件 6 条、实例隔离 1 条）。**未修改、未删除任何既有用例**（原有 21 条全部保留） |

**修订 1 个既有设计文档**（按业务方 2026-10-27 口径修正，非代码）：

| 文件 | 改动方式 |
|---|---|
| `docs/frontend-ui-p0b0-remediation-plan.md` | 三处定向修订，不改动任何结论性数据：① §3.1 新增「测试二分」原则（现状契约必须绿 / 新增能力允许先失败 + `it.fails()` 约定）；② §3.2 `LqTable` 属性透传由「永久禁止声明」改为**条件式规则**（声明即须转发 + 测试证明等价）；③ §6 顶部新增**阶段编号对齐表**（B2 分页 / B3 表格 / B4 弹窗+搜索栏 / B5 抽屉+视觉 / B6 试点 / B7 推广），覆盖文档内的旧编号 |

**未触碰：** 所有公共组件、业务页面、CSS、`package.json`、`vite.config.js`、eslint 配置、后端与数据库。

### 1.3 工作区中的并行任务改动（非本轮产生，未触碰）

执行期间 `git status` 显示以下改动来自其它并行工作区，本轮**未读取修改、未清理、未 stash、未 checkout 覆盖**：

- `backend/src/main/java/.../ProductionTrackingAnalyticsService.java`、`ProductionTrackingPolicy.java`、`ProductionTrackingService.java`、`ProductionTrackingWeeklyReviewService.java`
- `backend/src/test/java/.../ProductionTrackingServiceTest.java`、`ProductionTrackingWorkbookImportServiceTest.java`
- `backend/src/main/resources/db/migration/V419__*.sql`（2 个新文件）
- `frontend/src/apps/archive/views/production/ProductionTrackingView.vue`
- `tools/_exec_part3_drop.java`、`tools/classes/`
- `生产跟单设计梳理.md`
- `docs/frontend-ui-p0a-audit.md`、`docs/frontend-ui-p0b0-remediation-plan.md`（前两轮本任务产出）

---

## 2. 新增用例与分类

共新增 **92 条用例**：4 个新文件 81 条 + `useListPagination.test.ts` 追加 11 条。
按业务方 2026-10-27 修订的**二分口径**分为三类：

| 类别 | 条数 | 判定 | 当前状态 |
|---|---:|---|---|
| **现状契约测试** | **84** | 描述当前已存在的正确行为 | **必须先在未改动的组件上跑绿**（B1 验收口径，全部通过） |
| **新增能力测试** | **7** | 描述 B2/B3 才要实现的能力 | 以 `it.fails()` 标记「**期望失败**」：真实执行（非 skip）、失败不阻断 CI；实现后转为通过，vitest 报「expected to fail but passed」即为批次交接信号 |
| **编译期契约登记** | **1** | `LqTable` 泛型类型 | 运行时不可观测，保留 `it.skip`，由 `vue-tsc` 在 13 个调用方上保障 |

> 二分解决了原设计的矛盾：若要求「尚未实现的新功能在旧代码上先测试通过」，则新增能力测试要么被迫 skip（失去缺口证据），要么使构建长期飘红。改用 `it.fails()` 后，缺口**持续可见**且**不阻断交付**。

### 2.1 `LqTable.attrs.test.ts`（22 条：19 现状通过 / 2 期望失败 / 1 编译期登记）

| 分类 | 条数 | 用例 |
|---|---:|---|
| 属性透传契约 | 11 | 透传 `row-key`；透传 `max-height`；透传 `span-method`（函数引用不变）；透传 `default-sort`；透传 `height`/`show-summary`/`tree-props`；`stripe`/`border` 显式绑定；`row-class-name` 函数形式委派；`row-class-name` 字符串形式；未传 `row-class-name` 返回空串；未声明事件（`expand-change`/`select`）留在 `$attrs`；已声明事件不在 `$attrs` |
| 结构与空态契约 | 6 | `density="compact"` / 默认 `default` class；默认渲染 `EmptyState`（「暂无数据」）；`empty-text` 覆盖；`#empty` 插槽覆盖；默认插槽渲染 |
| 暴露实例方法契约 | 1 | 一次覆盖 9 个方法（`clearSelection`/`toggleRowSelection`/`toggleAllSelection`/`toggleRowExpansion`/`setCurrentRow`/`clearSort`/`clearFilter`/`doLayout`/`sort`）并校验入参透传 |
| 真实 Element Plus 集成 | 1 | 不用 stub，`row-key` / `max-height` 到达真实 `el-table` props |
| **新增能力（B3，期望失败）** | 2 | `emptyHint` 渲染空态补充提示；`emptySize` 控制空态尺寸 class |
| **编译期契约登记（skip）** | 1 | 泛型类型契约：`data` / 行事件载荷随 `T` 推导。运行时不可观测，由 `vue-tsc` 在 13 个调用方上保障 |

> 说明：事件唯一性与 `$attrs` 通道已由既有 `LqTable.test.ts`（6 条）覆盖，本文件补齐的是**属性**侧，两者互补不重复。

### 2.2 `LqDialog.close.contract.test.ts`（26 条：全部通过）

| 分类 | 条数 | 用例 |
|---|---:|---|
| 关闭通道转发契约 | 7 | 默认允许 ESC；`loading` 关闭 ESC；默认禁用遮罩；`closeOnClickModal=true` 开启遮罩；`loading` 期间即使 `closeOnClickModal=true` 也关闭遮罩；透传 `destroyOnClose`；恒传 `append-to-body` |
| 关闭事件唯一性 | 7 | 遮罩路径（update+close 各一次）；ESC 路径；关闭按钮（`v-model` 响应，close 只由 `@close` 派发）；关闭按钮（父级不回写，兜底一次且不重复）；取消按钮（`cancel`+`close` 各一次）；确认按钮**不自动关闭**；`loading` 不渲染关闭按钮 |
| `beforeClose` 语义契约 | 7 | 返回 `false` 阻止；返回 `true` 立即关；回调式（`void` + `done`）；`Promise.resolve(false)` 阻止；`Promise.resolve(true)` 放行；`Promise.reject` 不关闭且不抛出；拦截时取消按钮也不派发 `cancel` |
| 嵌套与生命周期 | 3 | 子弹窗关闭不影响父弹窗；嵌套可分别指定 `zIndex`；关闭请求未完成时卸载不抛错 |
| 真实 Element Plus 集成 | 2 | 内容被 Teleport 到 body 且三段式结构（head/body/foot）完整；关闭后内容移除 |

### 2.3 `LqPagination.contract.test.ts`（20 条：15 现状通过 / 5 期望失败）

| 分类 | 条数 | 用例 |
|---|---:|---|
| 0/1 基换算契约 | 5 | 第 0 页显示为第 1 页；第 2 页（0 基）显示为第 3 页；点击第 N 页换算回 0 基并写入 `pageInfo`；点击第 1 页换算为 0；`resetPage` 后回到第 1 页且 `size` 不变 |
| 请求触发契约 | 4 | 单次翻页只触发一次 `change`；仅 `update:currentPage`（无 `current-change`）**不**触发 `change`；单次改页大小只触发一次 `change` 且页码归零；两次独立交互触发两次 `change` |
| 属性透传契约 | 5 | `full` layout 含 total/sizes；`compact` layout 只保留翻页；透传 `pageSizes`/`pagerCount`/`disabled`/`hideOnSinglePage`；`total` 跟随 `pageInfo.total`；挂载不因 aria 兜底抛错 |
| 真实 Element Plus 集成 | 1 | 点击 `.btn-next` 真实触发 0 基换算与一次 `change` |
| **新增能力（B2，期望失败）** | 5 | 独立状态模式：`:total`/`:page`/`:page-size` 独立接入；切页派发 0 基 `update:page`；改页大小派发 `update:page-size` 且页码归零；外部回写 `page` 显示同步且不额外触发 `change`；`page-change`/`size-change` 细分事件与 `change` 并存 |

### 2.4 `SearchToolbar.debounce.test.ts`（13 条：全部通过）

| 分类 | 条数 | 用例 |
|---|---:|---|
| 防抖契约 | 3 | 默认 300ms（299ms 不发、300ms 发一次）；自定义 `debounce` 生效；连续输入合并为一次并 trim |
| 提交入口契约 | 4 | 回车立即查询并取消已排队任务；输入框清空 → 取消待发 + `search('')`；「清除筛选」→ 只派发 `clear` 不派发 `search`；`submit` 模式仅查询按钮触发 |
| 中文输入法契约 | 2 | 组词期间不提交、`compositionend` 后补提交；文本筛选框组词期间也不提交 `filter-change` |
| 生命周期与状态同步 | 4 | 卸载后清理定时器不再派发；外部回写 `keyword` 不触发 `search`；外部更新 `fieldValues` 时文本镜像跟随且不反向派发 `filter-change`；切换批量模式不产生意外搜索 |

### 2.5 `useListPagination.test.ts` 追加（11 条：全部通过）

| 分类 | 条数 | 用例 |
|---|---:|---|
| 响应字段优先级 | 4 | `number` 与 `page` 同时存在时以 `number` 为准；`totalElements=0` 时取 0 不回退 `total`；`totalPages=0` 时 `pages` 取 0；`size=0` 时保留 0 |
| 边界条件 | 6 | `onPageChange(0)` 与负值 clamp 到 0；`resetPage` 只重置 page 不改 size/total；`resetPage` 后 `indexMethod` 回到 1；`getPageParams` 返回拷贝；`queryParams` 响应式；`pages` 已给出时 `totalPages` 不受 total 影响 |
| 实例隔离 | 1 | 多实例不共享分页状态 |

---

## 3. 实际执行命令与结果

### 3.1 命令清单（原文）

```bash
# 单文件验证（边写边验）
cd frontend && npx vitest run src/components/common/__tests__/LqTable.attrs.test.ts
cd frontend && npx vitest run src/components/common/__tests__/LqDialog.close.contract.test.ts
cd frontend && npx vitest run src/components/common/__tests__/LqPagination.contract.test.ts
cd frontend && npx vitest run src/components/common/__tests__/SearchToolbar.debounce.test.ts \
                src/components/common/__tests__/LqPagination.contract.test.ts

# 全量
cd frontend && npx vitest run

# 门禁
cd frontend && npm run typecheck          # vue-tsc --noEmit --skipLibCheck
cd frontend && npm run lint               # eslint src/ --quiet
cd frontend && npx eslint <新增/修改的 5 个测试文件>
```

### 3.2 结果汇总

| 范围 | 通过 | 失败 | 期望失败 | 跳过 | 合计 |
|---|---:|---:|---:|---:|---:|
| **全量 `npx vitest run`** | **612** | **0** | **7** | **1** | **620**（74 个测试文件全部通过，退出码 0） |
| `LqTable.attrs.test.ts` | 19 | 0 | 2 | 1 | 22 |
| `LqDialog.close.contract.test.ts` | 26 | 0 | 0 | 0 | 26 |
| `LqPagination.contract.test.ts` | 15 | 0 | 5 | 0 | 20 |
| `SearchToolbar.debounce.test.ts` | 13 | 0 | 0 | 0 | 13 |
| `useListPagination.test.ts`（含追加） | 32 | 0 | 0 | 0 | 32 |

**B1 验收口径核对**：现状契约测试 **84 条全部通过**、失败 **0**；7 条期望失败全部为新增能力用例（B2 占 5、B3 占 2），1 条跳过为编译期契约登记。`npm test` 退出码为 **0**，CI 不受影响。

> 期望失败用例执行时会输出 `[Vue warn]: Missing required prop: "pagination"`（`LqPagination` 尚不接受独立 props），这正是缺口本身的证据，属预期 stderr 噪声，不影响结果。

门禁状态：

| 命令 | 结果 |
|---|---|
| `npm run typecheck` | **通过**（无输出错误） |
| `npm run lint`（`--quiet`） | **通过**（0 error） |
| `npx eslint` 对 5 个新增/修改测试文件 | **0 error**；初始 4 条 `vue/order-in-components` warning 已修复，复跑后仅剩 2 条 `vue/one-component-per-file` warning（测试内多个 host 组件所致，`--quiet` 下不显现） |

### 3.3 编写过程中出现过的失败（均为测试自身预期写错，已修正，非组件缺陷）

| 现象 | 原因 | 处理 |
|---|---|---|
| `show-summary` 断言 `true` 收到 `''` | 模板中 `show-summary` 为裸属性，值为 `''` | 改为 `:show-summary="true"` |
| `rowClassName` 断言收到 `mergedRowClass` 而非业务函数 | `LqTable` 始终绑定自己的 `mergedRowClass` 包装函数再委派 | **修正测试预期**：改为断言委派语义（函数/字符串/未传三种形态） |
| `append-to-body` 断言 `true` 收到 `''` | 裸属性 + stub 未声明 Boolean 类型 | 改为断言「恒被传入」，真实行为由真实 EP 集成用例（Teleport 到 body）验证 |
| 空态断言找不到 `.empty-text` / `.custom-empty` | stub 模板只渲染默认插槽，未渲染 `empty` 具名插槽 | 修正 stub 模板为 `<slot /><slot name="empty" /><slot name="append" />` |
| `total 跟随 pageInfo.total` 断言失败 | 测试里挂载了第二个实例去 nextTick | 改为在同一实例上 `await nextTick()` |

> 以上 5 处均为**测试代码**问题。本轮**未因任何测试失败而修改生产组件**，也未删除或弱化任何断言。

---

## 4. 真实缺陷与测试环境问题

### 4.1 对 P0-A / P0-B0 判断的再次验证（B1 实测）

| 结论 | 验证方式 | 结果 |
|---|---|---|
| `LqTable` 的 `$attrs` 透传**完好**，`span-method`/`max-height`/`default-sort`/`expand-change` 均可透传 | 新增 11 条属性透传用例 + 真实 EP 集成 | **证实**，P0-A 的 T-2/T-3 判断已由 P0-B0 推翻，B1 进一步实测确认 |
| `LqTable` **不存在事件双派发** | 断言已声明事件不在 `$attrs`；既有用例断言派发一次 | **证实** |
| `LqDialog` 两种消费模式下 `close` 均只派发一次 | 4 条关闭路径用例（遮罩/ESC/关闭按钮两种模式/取消） | **证实**：`v-model` 模式由 `handleClose` 单次派发；`:model-value + @close` 模式由 `nextTick` 兜底单次派发并受 `suppressNextClose` 压制 |
| `LqDialog` `loading` 期间禁止关闭 | `closeOnPressEscape=false`、`closeOnClickModal=false`、关闭按钮不渲染 | **证实**，三种入口全部封锁 |
| `LqDialog` `beforeClose` 四种形态均可控 | 7 条用例（同步 false/true、done、Promise resolve/reject、cancel 拦截） | **证实**，含 `Promise.reject` 被内部吞掉不抛出 |
| `LqPagination` 单次交互只触发一次 `change` | 4 条请求触发用例 | **证实**：`change` 只挂在 `current-change`/`size-change`，`update:*` 不触发 |
| `SearchToolbar` 防抖/IME/清空/外部回写符合预期 | 13 条用例 | **证实**，无缺陷 |

### 4.2 真实缺陷（记录，不在本轮整改）

| # | 缺陷 | 证据 | 本轮处理 |
|---|---|---|---|
| D-A | `LqDialog.vue:58` `append-to-body` 恒为 `true`，导致业务侧 `:deep` 无法命中弹窗内容 | 真实 EP 集成用例确认内容被 Teleport 到 `document.body`；`ProductionTrackingView.vue:939` 注释自曝 | 仅记录（P0-A 已列入 P2），**未修改组件** |
| D-B | `LqPagination.vue:62-68` 用 `onMounted` + `querySelector` 命令式补 `aria-label` | 新增「挂载不因 aria 兜底抛错」用例覆盖 | 仅记录（P2），**未修改组件** |
| D-C | `LqPreview.vue:96` 传入 `EmptyState` 不存在的 `description` prop，文案被丢弃 | P0-A/P0-B0 已记录 | 不在 B1 范围（B7 一行修复） |
| D-D | 边界观察：`useListPagination` 在 `size=0` 时 `totalPages` 为 `Infinity`（`Math.ceil(total/0)`） | 新增用例只断言 `pageInfo.size === 0`，**刻意不断言 `Infinity`**，避免把偶发行为固化成契约 | 记录为观察项，未断言、未修复 |

### 4.3 测试环境问题（非缺陷）

| # | 现象 | 影响 | 处理 |
|---|---|---|---|
| E-A | 全量运行时 `ListPageLayout.test.ts` 输出 `[Vue warn]: Failed to resolve component: el-button / el-icon`（来自 `DataState` 内部未 stub 的 EP 组件） | **存量现象**，测试仍通过 | 未处理（非本轮引入，避免扩大范围） |
| E-B | `LqDialog` 真实 EP 集成中，Element Plus 的关闭过渡由框架负责，happy-dom 下不保证 `close` 事件在有限 tick 内到达 | 关闭链路断言不能只依赖真实 EP | **分层处理**：关闭语义用 stub 精确断言（26 条中的 24 条），真实 EP 只验证 Teleport 与结构渲染。已写入测试文件头注释说明契约边界 |
| E-C | P0-A / P0-B0 记录 `LqDialog.test.ts` 为「32 用例」，实测为 **36 用例** | 文档数字偏差 | 已在本文档更正 |
| E-D | 全量 `npx vitest run` 约 15 秒，管道输出会被截断 | 需落盘日志再读取 | 用 `Out-File` 落盘后读取汇总，不改变结果 |

---

## 5. 新增能力测试（期望失败，不纳入现状基线）

按业务方修订口径，这些用例描述**未来批次才实现**的能力，允许先失败。实现约定为 vitest `it.fails()`：真实执行 → 失败 → 计入「expected fail」且不阻断 CI；批次实现后转为通过 → vitest 报「expected to fail but passed」→ 移除 `.fails` 转为必须通过。

| 文件 | 目标批次 | 条数 | 内容 |
|---|---|---:|---|
| `LqPagination.contract.test.ts` | **B2（`LqPagination` 解耦）** | 5 | ① `:total`/`:page`/`:page-size` 独立接入（不依赖 `useListPagination`）；② 切页派发 `update:page` 且为 **0 基**；③ 改页大小派发 `update:page-size` 且页码归零；④ 外部回写 `page` 时显示同步且不额外触发 `change`；⑤ `page-change`/`size-change` 细分事件与兼容事件 `change` 并存 |
| `LqTable.attrs.test.ts` | **B3（`LqTable` 类型与接口完善）** | 2 | ① `emptyHint` 渲染空态补充提示；② `emptySize` 控制空态尺寸 class |
| `LqTable.attrs.test.ts` | **B3（编译期，仅登记）** | 1 | 泛型类型契约：`data` / 行事件载荷随 `T` 推导。运行时不可观测，保留 `it.skip`，由 `vue-tsc` 保障 |

**规则**：这 7 条用例在对应批次实施前**必须保持期望失败状态**，不得为了使它们通过而提前修改 `LqPagination.vue` / `LqTable.vue`；也不得为了让输出「干净」而把它们改成 `skip`——一旦 skip 就失去了持续可见的缺口证据。B2/B3 实施时按 P0-B0 §3.3 / §3.2 的兼容性策略补齐实现，再逐条移除 `.fails`。

---

## 6. B2 开始前需要满足的条件

> 阶段编号已按业务方 2026-10-27 确认的新顺序对齐：**B2 = `LqPagination` 解耦**（独立状态模式），B3 = `LqTable` 类型与接口完善，B4 = `LqDialog`/`SearchToolbar` 稳定性完善，B5 = `LqDrawer` 与公共视觉规范，B6 = 业务页面试点，B7 = 全站推广与防回退规则。

以下为进入 **B2（`LqPagination` 解耦）** 的前置门槛，全部达成后方可开工：

### 6.1 基线门槛（已达成）

| # | 条件 | 当前状态 |
|---|---|---|
| 1 | 全量 `npx vitest run`：**612 passed / 0 failed**（另有 7 expected fail + 1 skipped），退出码 0 | ✅ 已达成 |
| 2 | `npm run typecheck` 零错误 | ✅ 已达成 |
| 3 | `npm run lint`（`--quiet`）零 error | ✅ 已达成 |
| 4 | 本轮新增**现状契约测试 84 条在未修改的组件上全部通过**（「先绿再改」） | ✅ 已达成 |
| 5 | `LqPagination` 现状契约（15 条）已锁定对象模式全部行为，可作为改造后的回归依据 | ✅ 已达成 |

### 6.2 B2 实施约束（必须遵守）

| # | 约束 | 违反后果 |
|---|---|---|
| 6 | **只改 `LqPagination.vue`**；本批次不动其它组件、不动 `<style>` | 扩大影响面 |
| 7 | **模式 A（对象模式）优先级高于模式 B（独立 props）**，判定唯一：`props.pagination` 存在 → A | 15 个现有调用方行为改变 |
| 8 | 新增 props（`page`/`page-size`/`total`）与 emits（`update:page`/`update:page-size`/`page-change`/`size-change`）**全部可选** | 模式 A 调用方需改动 |
| 9 | **0/1 基转换仍固定在组件内**（`+1` 显示 / `-1` 回写），不扩散到调用方 | 迁移会让 15 个现有调用方全部要改 |
| 10 | 公开 `page` 取 **0 基**（与 `pageInfo.page` 及后端一致） | 若公开 1 基，每个调用方都要 `-1`，制造新的 0/1 混乱 |
| 11 | **请求触发仍只有 `@change`**；`update:*` 只同步状态不触发请求 | 两个信号都触发会产生双请求 |
| 12 | **`change` 语义与触发次数完全不变**（单次交互一次、仅 `update:*` 不触发） | 4 条请求触发契约用例立即失败 |
| 13 | `onMounted` 的 `querySelector` aria 兜底保留 | 删除即可访问性回归 |

### 6.3 B2 验收与交接信号

| # | 条件 |
|---|---|
| 14 | B2 完成后重跑全量：**现状契约仍 0 failed**；`LqPagination.contract.test.ts` 中 5 条 `it.fails` 转为真实通过（须先移除 `.fails`，否则 vitest 报「expected to fail but passed」） |
| 15 | 15 个现有 `LqPagination` 调用方**零改动**且行为不变（人工抽查 `OperationLogView`、`CashflowJournalView`、`MediaAccountView` 三页翻页） |
| 16 | typecheck / lint 仍绿 |
| 17 | B3（`LqTable` 类型与接口完善）须在 B2 之后：`LqTable.attrs.test.ts` 中 2 条 `it.fails`（`emptyHint`/`emptySize`）同样先移除 `.fails` 再改实现 |

### 6.4 关于 `LqTable` 属性透传：**条件式规则**（取代「永久禁止声明」）

B1 实测确认：当前 `row-key`/`max-height`/`span-method`/`default-sort`/`height`/`show-summary`/`tree-props` 与 `expand-change`/`select` 经 `$attrs` 透传**全部正常**，这是合理且受保护的现状。

但这**不等于永久禁止显式声明**。B3 的约束应为：

| 规则 | 说明 |
|---|---|
| **声明即须转发** | 若未来为上述任一项显式声明 prop 或 emit，**必须同步实现向内部 `el-table` 的转发**（属性 → 目标 prop，事件 → 目标 emit） |
| **转发须被测试证明等价** | 必须让 `LqTable.attrs.test.ts` 中对应用例继续通过：目标 prop 名一致、函数引用不变、事件单次派发、字符串/函数/未传三种 `row-class-name` 形态保持 `mergedRowClass` 委派语义 |
| **允许声明的理由** | 显式声明可带来精确的 TypeScript 类型，有利于未来完善组件 API；只要等价性被测试证明，就不是回归 |

> 也就是说，B3 的判据不是「有没有声明」，而是「**声明后透传行为是否与现状一致**」。本报告的 11 条属性透传用例就是这条判据的执行者。

### 6.5 `LqDialog`：建议延后至 B4，不随 B2 重构

B1 契约测试（26 条全部通过）表明 `LqDialog` 当前行为**稳定**：

- `v-model` 模式：`close` 由 `el-dialog` 的 `@close` 唯一派发；
- `:model-value + @close` 模式：`nextTick` 兜底派发一次，并由 `suppressNextClose` 压制后续重复；
- **两种消费模式下 `close` 均恰好派发一次**，未复现双派发；
- `loading` 三种关闭入口全部封锁；`beforeClose` 七种形态（同步 false/true、`done` 回调、Promise resolve/reject、`cancel` 拦截）全部可控。

**结论与建议**：按业务方意见，若契约测试表明行为稳定，则**保留 `suppressNextClose` 等兼容机制**，不立即重构 `LqDialog`；把 B2 的精力集中在真正阻碍业务页面复用的**分页与列表问题**上。`LqDialog` 的稳定性完善（`suppressNextClose` 重构、关闭语义单一化）并入 **B4**，届时以本报告 26 条用例作为回归基线。

已知但不在 B2 范围：`append-to-body` 恒 `true` 导致业务侧 `:deep` 无法命中（P2，真实 EP 集成用例已佐证），建议与 B5 的 `LqDrawer` 一并决策。

---

## 附录 A：本轮未做的事（对照限制条款）

- 未修改 `LqTable.vue` / `LqDialog.vue` / `LqPagination.vue` / `SearchToolbar.vue` 的生产实现 —— ✅
- 未修改任何业务页面 —— ✅
- 未修改数据库或后端 —— ✅
- 未修改现有产品视觉和交互（纯测试新增） —— ✅
- 未操作其它并行任务文件（见 §1.3，仅 `git status` 读取，未 `clean`/`reset`/`stash`/`checkout`） —— ✅
- 未自动提交、未推送 —— ✅
- 未安装依赖、未新增测试配置文件 —— ✅

---

## 附录 C：本轮修订记录（相对 B1 首版）

| # | 修订项 | 修订前 | 修订后 |
|---|---|---|---|
| 1 | 测试分类 | 新增能力测试一律 `it.skip` | **二分**：现状契约必须绿；新增能力用 `it.fails()` **允许先失败**（真实执行、不阻断 CI），实现后报「expected to fail but passed」作为交接信号 |
| 2 | `LqTable` 透传规则 | 「**严禁**为 `span-method`/`default-sort`/`expand-change` 等声明 props 或 emits」 | **条件式**：允许声明，但**必须同步实现向内部 `el-table` 的转发，并用兼容测试证明行为等价**。为未来完善 TypeScript 类型留出空间 |
| 3 | 阶段编号 | P0-B0 原 B1~B10 | 对齐业务方新顺序：**B2 分页 → B3 表格 → B4 弹窗/搜索栏 → B5 抽屉/视觉 → B6 试点 → B7 推广**；`LqDialog` 收敛由「紧随 B1」后移至 **B4** |
| 4 | 用例归属 | `LqTable` 泛型标为 B2、`LqPagination` 独立状态标为 B3 | 按新编号：**`LqPagination` 独立状态 = B2（5 条），`LqTable` 空态扩展/泛型 = B3（2 条 + 1 登记）** |
| 5 | §6 内容 | B2 前置条件写的是 `LqTable` 泛型化 | 重写为 **`LqPagination` 解耦**的前置门槛与约束；`LqTable` 规则移入 §6.4；新增 §6.5「`LqDialog` 建议延后至 B4」 |

数据变化：首版 612 passed / 0 failed / 6 skipped（618）→ 修订版 **612 passed / 0 failed / 7 expected fail / 1 skipped（620）**。差异仅来自 5 条 skip 转期望失败 + `LqTable` 新增 2 条期望失败；**现状契约测试通过数不变（84 条全绿）**。

## 附录 B：关键证据索引

| 结论 | 证据 |
|---|---|
| `LqTable` attrs 透传生效 | `frontend/src/components/common/LqTable.vue:5,37`；调用方 `MoldImportPreviewTable.vue:70-72`、`CashflowAccountTable.vue:2`、`CompanyCertificationsTab.vue:78` |
| `row-class-name` 绑定的是 `mergedRowClass` | `LqTable.vue:10,71-76` |
| `LqDialog` 关闭双路径与兜底 | `LqDialog.vue:250,258-271,274-280` |
| `loading` 封锁三种关闭入口 | `LqDialog.vue:47-48,77` |
| `append-to-body` 恒 true | `LqDialog.vue:58` |
| `LqPagination` 0/1 基与 change 挂载点 | `LqPagination.vue:45-52,54-60` |
| `LqPagination` aria 兜底 | `LqPagination.vue:62-68` |
| `SearchToolbar` 防抖 / IME / 清空 / 外部回写 | `SearchToolbar.vue:246-258,19-20,288-291,271-281,414-428` |
| vitest 配置位置 | `frontend/vite.config.js:34-38` |
