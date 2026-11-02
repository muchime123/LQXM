# LQXM 前端统一 UI 体系 B7-B0：Vitest 测试环境诊断与恢复

- 阶段：B7-B0（基础设施修复，不混入任何业务页面改造）
- 执行日期：2026-10-29
- 范围：`frontend/`
- 结论：**测试环境已恢复，可信基线已建立**。之前「78 个测试文件上限」的结论已彻底推翻，真实根因与依赖完整性无关。

---

## 1. 故障复现证据

### 1.1 原始故障表现

在 `c:\Users\20676\source\LQXM\frontend`（**小写盘符**）下执行任意 Vitest 运行：

```
❯ src/composables/common/__tests__/useDirty.test.ts (0 test)

⎯⎯⎯⎯⎯⎯ Failed Suites 1 ⎯⎯⎯⎯⎯
 FAIL  src/composables/common/__tests__/useDirty.test.ts
TypeError: Cannot read properties of undefined (reading 'config')
 ❯ src/composables/common/__tests__/useDirty.test.ts:11:1

 Test Files  1 failed (1)
      Tests  no tests
```

特征：**0 test 被收集**，错误抛在测试文件顶层 `describe(` 调用处（第 11 行），说明故障发生在**模块求值/收集阶段**，而非断言执行阶段。

`--no-isolate` 下同样失败，说明问题不出在 worker 隔离引导。

### 1.2 完整堆栈（本次关键突破）

此前 `--reporter=json` 未输出堆栈。改用「在测试文件内 `try/catch` 包裹 `describe` 调用」的方式捕获到完整堆栈：

```
TypeError: Cannot read properties of undefined (reading 'config')
    at initSuite (file:///C:/Users/20676/source/LQXM/frontend/node_modules/@vitest/runner/dist/chunk-artifact.js:1848:23)
    at createSuiteCollector (file:///C:/Users/20676/source/LQXM/frontend/node_modules/@vitest/runner/dist/chunk-artifact.js:1709:2)
    at Object.suiteFn (file:///C:/Users/20676/source/LQXM/frontend/node_modules/@vitest/runner/dist/chunk-artifact.js:1951:10)
    at chain (file:///C:/Users/20676/source/LQXM/frontend/node_modules/@vitest/runner/dist/chunk-artifact.js:599:14)
    at C:/Users/20676/source/LQXM/frontend/src/utils/__tests__/b7b0-stack.test.ts:5:3
    at VitestModuleEvaluator._runInlinedModule (file:///c:/Users/20676/source/LQXM/frontend/node_modules/vitest/dist/module-evaluator.js:206:4)
    at VitestModuleRunner.directRequest (file:///c:/Users/20676/source/LQXM/frontend/node_modules/vitest/node_modules/vite/dist/node/module-runner.js:1259:59)
    at VitestModuleRunner.cachedRequest (file:///c:/Users/20676/source/LQXM/frontend/node_modules/vitest/node_modules/vite/dist/node/module-runner.js:1166:73)
    at VitestModuleRunner.import (file:///c:/Users/20676/source/LQXM/frontend/node_modules/vitest/node_modules/vite/dist/node/module-runner.js:1120:10)
```

### 1.3 决定性 A/B 对照

**同一棵 `node_modules`、同一个配置文件、同一个测试文件，唯一变量是命令行 cwd 的盘符大小写：**

| cwd 写法 | Vitest 输出 root | 结果 |
|---|---|---|
| `cd /d c:\Users\20676\source\LQXM\frontend` | `c:/Users/20676/source/LQXM/frontend` | **失败**（0 test，TypeError） |
| `cd /d C:\Users\20676\source\LQXM\frontend` | `C:/Users/20676/source/LQXM/frontend` | **通过** |

再对照：在临时隔离目录 `b0-iso`（大写盘符）下调用**项目自己的 vitest 二进制**去跑项目的测试文件：
- `cd /d C:\...\b0-iso && node c:\...\frontend\node_modules\vitest\vitest.mjs run ...` → **失败**（即使依赖完全相同）

即：**成败只取决于启动进程的 cwd 盘符大小写，与依赖树、配置、测试内容无关。**

### 1.4 证据链闭环

堆栈中两条链的 URL 大小写不一致，这是根因的直接证据：

```
大写 C:  →  node_modules/@vitest/runner/dist/chunk-artifact.js
大写 C:  →  src/utils/__tests__/b7b0-stack.test.ts
小写 c:  →  node_modules/vitest/dist/module-evaluator.js
小写 c:  →  node_modules/vitest/node_modules/vite/dist/node/module-runner.js
```

- worker/运行时的原生模块链 → **小写 `c:`**
- vite 解析并 externalize 出的 URL（经 realpath 规范化）→ **大写 `C:`**

两者是同一物理文件的两个不同 URL 字符串，ESM 模块缓存按字符串键 → **加载出两个 `chunk-artifact.js` 实例**。

`@vitest/runner` 收集器使用模块级变量保存 runner：

```js
function getRunner() {
  assert(runner, "the runner");   // 第二实例中 runner 为 undefined
  return runner;
}
function createDefaultSuite(runner) {
  const config = runner.config.sequence;   // ← 报错行（chunk-artifact.js:1848）
  ...
}
function clearCollectorContext(file, currentRunner) {
  runner = currentRunner;                  // 仅在第一实例上执行过
  if (!defaultSuite) defaultSuite = createDefaultSuite(currentRunner);
  ...
}
```

运行时 worker 在**小写实例**上执行 `clearCollectorContext`（`runner` 被赋值）；测试文件从**大写实例**拿到 `describe`，该实例 `runner` 从未赋值 → `undefined.config` → TypeError。

---

## 2. 确认的根因及仍未排除的假设

### 2.1 确认根因

**Windows 下 cwd 盘符大小写未归一化，导致 Vitest/Vite 模块 URL 大小写不一致，进而产生 `@vitest/runner` 收集器模块的双实例，`runner` 上下文丢失。**

- 类别：**执行环境问题（路径大小写），非代码问题、非依赖问题、非缓存问题**
- 触发条件：启动 Vitest 的进程 cwd 使用**小写盘符**（如 `c:\...`）
- 为什么此前一直复现：本工作区路径就是小写 `c:/Users/20676/source/LQXM`，VS Code 集成终端 cwd 继承该写法
- 为什么"78 文件上限"是错的：单文件也必然失败，与并发/文件数无关；该结论是在小写盘符环境下观测到的假象

### 2.2 已排除的假设

| 假设 | 结论 | 排除依据 |
|---|---|---|
| `node_modules` 损坏 / 安装不完整 | **排除** | frontend 与对照环境逐文件哈希比对：vitest 包 130 个文件、`@vitest/runner` 13 个文件完全一致 |
| Vite / Vitest 缓存污染 | **排除** | 删除 `frontend/node_modules/.vite` 后，小写盘符下仍 100% 复现失败 |
| 根级 Vite 5.4.21 版本不兼容 | **排除** | 将对照环境根级 vite 降到 5.4.21 后，大写盘符下仍通过 |
| 项目配置（插件/别名/optimizeDeps）问题 | **排除** | 仅保留插件、仅保留别名、仅保留 optimizeDeps 的最小配置，大写盘符下均通过 |
| `environment: happy-dom` 问题 | **排除** | 改为 `--environment=node` 后，小写盘符下仍失败 |
| worker 隔离引导问题 | **排除** | `--no-isolate` 下同样失败 |
| `@vitest/mocker` 缺失（B7-A 提出的重点） | **排除，属误判** | 磁盘上存在 `node_modules/vitest/node_modules/@vitest/mocker`（v4.1.9）；锁文件中条目为 `node_modules/vitest/node_modules/@vitest/mocker`，二者**一致**。根级 `require.resolve('@vitest/mocker')` 失败属**预期的嵌套安装布局**，不是缺失 |

### 2.3 仍未排除 / 待观察

1. **上游缺陷定位**：这是 Vitest 4.1.9 / Vite 8.x 在 Windows 的路径归一化缺陷，未定位到具体上游 issue，也未验证是否有修复版本。
2. **其它子系统是否受影响**：`corporate-website/`（同为 Vite 项目）未在本阶段验证，若其工作路径也为小写盘符，可能存在同类风险。
3. **并行任务交互影响**：本次运行时机器上存在 6 个 `node.exe`、4 个 `java.exe` 进程（其它并行任务），未观察到干扰，但未能完全排除资源竞争。
4. **杀毒软件拦截**：未取得拦截记录，无法确认或排除。

---

## 3. 检查过的依赖和配置

### 3.1 运行时版本

| 项 | 实际版本 |
|---|---|
| Node.js | v24.18.0 |
| npm | 11.16.0 |
| vitest | 4.1.9 |
| vite（根级） | 5.4.21 |
| vite（`vitest/node_modules/` 嵌套） | 8.1.3 |
| `@vitest/runner` | 4.1.9 |
| `@vitest/mocker`（嵌套） | 4.1.9 |
| `@vitest/expect` / `utils` / `snapshot` / `spy` / `pretty-format` | 均 4.1.9 |
| happy-dom | 20.10.6 |
| `@vitejs/plugin-vue` | 5.2.4 |
| vue | 3.5.35 |

### 3.2 依赖树与锁文件一致性

- `package-lock.json`：`lockfileVersion: 3`，244,897 字节。
- 锁文件中 vitest 相关条目：
  - `node_modules/@vitest/{expect,pretty-format,runner,snapshot,spy,utils}` → 4.1.9
  - `node_modules/vite` → 5.4.21
  - `node_modules/vitest` → 4.1.9
  - `node_modules/vitest/node_modules/@vitest/mocker` → 4.1.9
  - `node_modules/vitest/node_modules/estree-walker` → 3.0.3
  - `node_modules/vitest/node_modules/vite` → 8.1.3
- `npm ls vitest vite @vitest/runner @vitest/mocker happy-dom` 输出无 `invalid` / `missing` / `UNMET` 标记，嵌套结构与锁文件一致。
- 结论：**依赖安装完整，锁文件与磁盘一致**。

### 3.3 配置

`frontend/vite.config.js`（唯一配置，无独立 `vitest.config.*`）：

- `plugins`：`@vitejs/plugin-vue` + `no-conditional-dev-cache`（dev 期剥离条件请求头的中间件）
- `resolve.alias`：`@` → `src`
- `optimizeDeps`：`include` vue / vue-router / axios / element-plus / @element-plus/icons-vue / dayjs；`exclude` chart.js / xlsx
- `test`：`environment: 'happy-dom'`，`include: ['src/**/*.{test,spec}.{js,ts}']`，`globals: true`
- `scripts.test`：`vitest run`；`typecheck`：`vue-tsc --noEmit --skipLibCheck`；`lint`：`eslint src/ --quiet`

配置本身无异常，且已在最小配置对照中被排除。

### 3.4 缓存与临时文件

- `frontend/node_modules/.vite/`：诊断过程中被**删除一次**（验证缓存假设），运行后由 Vitest 重建，现有 `vitest/` 子目录。
- `frontend/` 根目录存在 **18 个 `vite.config.js.timestamp-*.mjs`**（Vite 加载配置时中断残留）。B7-A 已登记过 7 个，本次增长到 18 个，多为本次反复启动 Vitest 产生。属可再生文件，不影响测试执行结果。

### 3.5 并行任务环境

执行期间机器上存在 6 个 `node.exe`、4 个 `java.exe` 进程（其它并行任务，含 tracking 与后端）。本次所有诊断与门禁运行均：
- 不修改 `frontend/package.json`、不修改 `package-lock.json`；
- 不执行任何 `npm install` / `npm ci` / 依赖重写；
- 不删除 `node_modules`（仅删其中可再生的 `.vite` 缓存子目录）；
- 构建输出写入临时目录，**未触及 `frontend/dist`**，不影响官网等并行任务产物。

---

## 4. 实际执行的恢复操作

严格按「第一层 → 第二层 → 第三层」顺序执行，**最终未进入第三层（无破坏性修复）**：

### 第一层：不修改文件的验证

1. 依赖树检查（`npm ls`）—— 无缺失/非法项。
2. 模块解析检查（`require.resolve`）—— vitest / @vitest/runner / vite / plugin-vue / happy-dom / @vue/test-utils 均正常；`@vitest/mocker` 因嵌套布局在根级不可解析（预期行为）。
3. 最小 Vitest 启动验证 —— 单文件、多文件、全量均执行。
4. 配置加载验证 —— 通过临时配置文件与项目配置文件两种方式加载，均成功。

### 第二层：隔离环境验证

- 在 `C:\Users\20676\AppData\Local\Temp\b0-iso` 建立隔离环境（独立 `node_modules`、独立 cacheDir），以 `root` 指向项目目录运行项目测试文件，用于切分「目录/依赖树」与「cwd 盘符大小写」两个变量。
- 采用单向覆盖实验（将项目依赖版本镜像进隔离环境）完成交叉验证，**未反向覆盖工作区依赖**。

### 第三层：最小范围修复 —— **未执行**

定位到根因后，无需任何依赖重装、缓存清理或锁文件修改即可恢复：
- 未删除 `node_modules`
- 未执行 `npm ci` / `npm install`
- 未修改 `package-lock.json`
- 未修改 `vite.config.js`
- 未删除或修改任何正常测试、未修改任何断言

**唯一的操作性变更**：`frontend/node_modules/.vite` 被删除一次（可再生缓存，已自动重建）。

### 环境约束（必须遵守，否则必现故障）

运行任何 Vitest 命令前，cwd 必须使用**大写盘符**：

```bat
cd /d C:\Users\20676\source\LQXM\frontend && npm test
```

---

## 5. 修改、创建或删除的文件

| 类型 | 路径 | 说明 |
|---|---|---|
| 删除（可再生缓存） | `frontend/node_modules/.vite/` | 诊断中一次性删除，已由 Vitest 重建 |
| 创建后删除（临时诊断） | `frontend/b0-bis-min.mjs`、`frontend/b0-bis-plugins.mjs`、`frontend/b0-bis-alias.mjs`、`frontend/b0-bis-odeps.mjs` | 配置二分用，**已全部删除** |
| 创建后删除（临时诊断） | `frontend/src/composables/common/__tests__/b0-sample.test.js`、`b0-sample-ts.test.ts`、`b0-impdirty.test.ts`、`b0-resolve.test.js` | 文件级二分用，**已全部删除** |
| 创建后删除（临时诊断） | `frontend/src/utils/__tests__/b7b0-stack.test.ts` | 捕获完整堆栈用，**已删除** |
| 创建后删除（临时探针） | `frontend/node_modules/vitest/b0-probe.mjs` | 模块解析探针，**已删除** |
| 创建（隔离环境，Temp） | `C:\Users\20676\AppData\Local\Temp\b0-iso\` | 一次性对照环境，已被实验污染，可弃用 |
| 创建（诊断脚本，Temp） | `C:\Users\20676\AppData\Local\Temp\b0-*.cjs`、`b7b0-*.cjs/bat` | Temp 下诊断脚本，不影响工作区 |
| **新增交付物** | `docs/frontend-ui-b7b0-test-environment-recovery.md` | 本报告 |

**未修改**：所有业务组件、公共 UI 组件、后端代码、`package.json`、`package-lock.json`、`vite.config.js`。

---

## 6. 修复前后测试对比

### 修复前（小写盘符 `c:\...`）

| 场景 | 结果 |
|---|---|
| 单文件 `useDirty.test.ts` | 失败，0 test 收集 |
| 单文件（纯 js 冒烟） | 失败，0 test 收集 |
| `--no-isolate` | 失败 |
| `--environment=node` | 失败 |
| 全量 | 大量文件在收集阶段失败（此前记录为 78 文件失败） |

### 修复后（大写盘符 `C:\...`）

| 场景 | 结果 |
|---|---|
| 单文件 `useDirty.test.ts` | 通过 |
| 单文件（纯 js / ts / 含导入变体） | 通过 |
| 全量 | 79 文件 / 712 用例，78 通过 / 710 通过，仅 Tracking 1 文件 2 用例失败 |

**关键更正**：上一轮会话报告的「83 文件 / 716 用例」包含我创建的 4 个临时调试测试文件（4 文件 4 用例）。清理后的**干净基线为 79 文件 / 712 用例**。

### 依赖关系说明

- 故障与并发数、文件数、测试内容、项目配置、依赖版本**均无关**。
- 故障与启动进程 cwd 的盘符大小写**严格相关**，可 100% 双向复现。

---

## 7. 全量测试与其他门禁结果

| # | 门禁 | 命令 | 结果 |
|---|---|---|---|
| 1 | 最小工具函数测试 | `vitest run src/utils/__tests__/websiteRichTextMedia.test.ts` | **通过**（2/2） |
| 2 | 公共组件契约测试 | `vitest run src/components/common/__tests__/OperationLogPilot.contract.test.ts` | **通过**（7/7） |
| 3 | 公共 UI 组件测试子集 | `vitest run src/components/common/__tests__` | **通过**（22 文件 / 267 用例） |
| 4 | 全量 Vitest | `vitest run` | 78/79 文件通过；710/712 用例通过（唯一失败为 Tracking，见第 8 节） |
| 5 | Typecheck | `npm run typecheck`（vue-tsc 6.0.3） | **通过**（无输出，exit 0） |
| 6 | Lint | `npm run lint`（eslint src/ --quiet） | **通过**（无输出，exit 0） |
| 7 | 完整构建 | `npx vite build --outDir <TEMP>\b7b0-build --emptyOutDir` | **通过**（30.28s，仅 chunk 体积告警） |

构建告警：
```
(!) Some chunks are larger than 1000 kB after minification.
```
属既有告警（`chunkSizeWarningLimit: 1000`），非本阶段引入。

---

## 8. Tracking 两条既有失败的状态

失败文件：`frontend/src/components/production/__tests__/TrackingAnalyticsDrilldownCells.test.ts`

| 用例 | 期望 | 实际 |
|---|---|---|
| `TrackingAnalyticsSpecCell > 规格与模具出数连成一行，用「 / 」分隔`（:19） | `'120 / 圆形 / PP / 一出四'` | `'120 / 一出四'` |
| `TrackingAnalyticsSpecCell > 只有规格时不留下孤零零的分隔符`（:26） | `'95 / 方形 / PP'` | `'95'` |

### 性质判定（比上一轮判断更精确）

这**不是**"与本次问题无关的既有噪声"，而是**并行 Tracking 任务引入的规格/测试不一致**：

1. 组件 `frontend/src/components/production/TrackingAnalyticsSpecCell.vue` 当前实现为**只取 `specification` 的第一段（尺寸）**，形状与材质不在列表展示：

```js
const line = computed(() => {
  // specification 形如「250 / 圆形 / 铝箔」，取第一段作为尺寸；模具出数单独显示。
  const size = props.specification?.split('/')[0]?.trim()
  return [size, props.moldLayout?.trim()].filter(Boolean).join(' / ')
})
```

2. 该行为由提交 `4c6d5432`（2026-10-28「move stage definitions to catalog and analytics snapshots」）引入，**是有意的产品决策**（组件注释已写明：LQ 2026-10-21 要求一行展示，完整规格在详情与导出保留）。
3. 测试文件最后修改于 `5bc9a80a`（2026-10-21），仍按**旧口径**断言完整规格 → 二者分叉。

**结论**：这是真实存在的 P0 候选（规格展示口径与测试契约不一致），应列入 B7-B1/B3 业务缺陷清单处理。按 B7-B0 纪律，本阶段**未修改实现、未修改断言**，仅登记。

---

## 9. B7-B1 开始前的验收条件

进入 B7-B1（资产表单残留 / 搜索分页联动 / 筛选重置）前，必须同时满足：

### 9.1 环境类

1. 所有测试命令在**大写盘符 cwd** 下执行，且全量 Vitest 结果稳定在 **78/79 文件、710/712 用例通过**（唯一失败为 Tracking 2 条，已登记）。
2. **连续两次**全量运行结果一致（排除偶发）。
3. `npm run typecheck` 与 `npm run lint` 均通过。
4. 不修改 `package.json` / `package-lock.json` / `vite.config.js`（如确需改动，须单独说明并评估对并行任务的依赖与缓存影响）。

### 9.2 纪律类

5. **禁止**为让测试通过而删除正常测试或修改断言。
6. 修复 OfficeAssetView 字段残留前，必须**先核实前端最终请求载荷、后端 DTO 与保存逻辑**三处，再动代码；不得仅凭表单对象存在某字段就认定数据已污染。
7. 每个 P0 修复配套**回归测试**（尤其是「编辑 → 新增」路径）。

### 9.3 范围类

8. B7-B1 开始时应先确认本次整改是否把 `components/` 中承担独立业务交互的组件纳入范围（见下节遗漏清单），并明确纳入/排除的理由。
9. 建议按「风险 + 依赖关系」重排施工顺序，而非按报告编号：优先处理会造成**持久化数据错误**的缺陷（资产字段残留），其次搜索/分页/筛选联动，再批量与行级权限表达。

### 9.4 已知风险提示

10. Tracking 2 条失败与 `TrackingNodeAnalytics.vue` 当前处于并行任务修改中相关，处理 Tracking 相关 P0 前建议先与并行任务确认边界，避免互相覆盖。

---

## 附录 A：B7-A 审计覆盖范围核对（components/ 遗漏清单）

### A.1 口径核对

- B7-A 审计文档声明的扫描范围：`frontend/src/apps/archive/views/**/*.vue`（**40 个 .vue**，其中 32 个路由级页面 + 8 个子视图/抽屉/表格/弹窗），`frontend/src/components/**` 仅作为「公共组件源码与调用方」被核对，**未作为独立审计对象**。
- 因此：`components/` 中自身承担独立业务交互（实际操作按钮、弹窗、抽屉）的组件，**大部分未被列入审计矩阵**。

### A.2 统计

| 指标 | 数量 |
|---|---|
| `components/` 下 `.vue` 总数 | 160 |
| 含交互元素（按钮/弹窗/抽屉/上传/开关等） | 109 |
| 含 API 调用或权限判断或路由跳转 | 71 |
| **含"写操作或权限判断"（P0 重点候选）** | **24** |
| 其中被审计文档明确提及 | 2 |
| **未被提及（本次补充遗漏清单）** | **22** |

### A.3 补充遗漏清单（22 个，按目录分组，行数为规模参考）

- **certificate（1）**：`CertDetailModal.vue`（1580）
- **common（3）**：`DefaultLayout.vue`（1024）、`SearchToolbar.vue`（671）、`LqDatePicker.vue`（67）
- **company（3）**：`CompanyBrandAssetsTab.vue`（1146）、`CertificationFormDialog.vue`（561）、`CompanyAccountsTab.vue`（528）
- **employee（1）**：`EmployeeDetailPanel.vue`（859）
- **media（1）**：`MediaAnalyticsPanel.vue`（853）
- **packaging（2）**：`PackagingDetailDrawer.vue`（805）、`PackagingAssetPanel.vue`（677）
- **product（4）**：`CatalogAssetGallery.vue`（982）、`ProductEditorModal.vue`（828）、`ProductParameterSection.vue`（226）、`ProductIdentitySection.vue`（114）
- **production（4）**：`ManufacturingTaskConfigPanel.vue`（415，含权限）、`TrackingProductBenchmarkPanel.vue`（306）、`TrackingGeneralBaselineDialog.vue`（75）、`ManufacturingWorkshopPanel.vue`（74）
- **website（3）**：`ImageCropper.vue`（464）、`WebsiteProductVariantsDialog.vue`（113）、`WebsiteProductGalleryDialog.vue`（100）

> 说明：清单基于源码特征扫描（API 导入 / 写操作调用 / 权限判断）生成，用于**补充范围核对**，不等同于「已确认存在缺陷」。B7 后续阶段应逐一确认其权限入口、状态联动与公共组件接入情况。

---

## 附录 B：基准快照

| 项 | 值 |
|---|---|
| HEAD | `433cc563808d09a260f7ea56b7f1ad77d8dd8aac` |
| 当前分支 | `main` |
| 本地 `main` | `433cc563`（与 HEAD 一致） |
| `origin/main` | `1999e3c5`（**本地领先，未推送**） |
| 最后提交 | `433cc563` 2026-10-29 10:18:48 +0800「周复盘未完工清单按紧急程度排序（仅该处启用，不改公共下钻默认顺序）」 |
| `frontend/` 工作区变更 | 31 项（含并行任务改动与未跟踪新文件） |
| Node / npm | v24.18.0 / 11.16.0 |
| 锁文件 | lockfileVersion 3，244,897 字节 |

**本次未提交、未推送任何内容。**
