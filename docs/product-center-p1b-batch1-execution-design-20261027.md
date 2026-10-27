# 产品中心 P1-B 批次 1：空适配链退役执行设计（2026-10-27）

> **已于 2026-10-27 执行完成**，执行结果与验收证据见 `docs/product-center-p1b-batch1-execution-result-20261027.md`。
> **本文 §5 的结论已被推翻（2026-10-27 复核）**：`computeCompletenessScore` 的 `variantCount` 在产品侧实际来自**机型档位数**而非 `product_variants` 行数（`ProductCatalogService:186,:252`；`ProductVariantRepository.countByModelIds:36` 全仓无调用方）。因此**不存在「退役旧规格使产品 67 由 80 分降到 60 分」**，`WebsiteDashboardController:160` 的 `noVariant` 体检同理。§5 保留作历史过程记录，**不得再作为 P1-B 任何批次的设计依据**；由此撤销「批次 3 必须先修改完整度评分」的阻断条件，评分语义优化改列为批次 2-A 的产品资料质量设计议题。
>
> 阶段：执行设计 + 契约准备，**未做任何代码删除，未做任何数据库变更**。
> 基线：代码 HEAD `7ddf837`（含本次新增的契约测试）；数据 `product_archive`（只读事务复核）。
> 配套：《产品中心 P1-B 删除与迁移设计（证据版）》`product-center-p1b-execution-design-20261026.md`；SQL：`product-center-p1b-verification-queries.sql`；脚本：`scripts/capture-website-compatibility-baseline.ps1`。

## 0. 一个必须先纠正的判断

上一版设计把 `WebsiteProductCompatibilityService` 的模具分支写成「唯一的官网风险点，必须保留」。**按行核对后，这个结论要收紧**：

`WebsiteProductCompatibilityService.getForProduct()`（`service/WebsiteProductCompatibilityService.java:45-77`）的数据流是
「机型/产品适配 link（`tierPackagingRepository` `:48`、`modelPackagingRepository` `:54`）→ 过滤出可见包材规格（`:58-60`）→ **若为空则 `:61` 直接返回 `Snapshot.empty()`** → 否则才用 `packagingVariantIds`（`:64-67`）去查模具（`:69-70`）」。

两张 link 表现在**都是 0 行**，且**唯一的写入方**是即将删除的 `ProductPackagingCompatibilityService.replace`（`:47`）。因此：

1. **今天每一个官网产品的 `compatiblePackaging` / `compatibleMolds` 都是空数组**，模具分支（`moldVariantLinkRepository` `:69-70`）**从未被执行**；
2. 所以「涉及 `mold_variant_links` 的读取逻辑要明确保留或迁移」的答案是：**这条读路径既不保留也不迁移**（它不可达），而 `mold_variant_links` **表本身与 65 行数据完整保留**（它是包材模具资产，未来的正式配置模型仍可能读取）；
3. 批次 1 对官网的真实影响 = **0**（前后响应语义一致），前提是严格按下面的顺序执行，且不顺手改动其它字段。

这条结论已用契约测试固化（§4）。

## 1. 逐行依赖清单：`WebsiteProductCompatibilityService`

| 行 | 依赖 | 数据现状 | 处置 |
|---|---|---|---|
| `:25` 字段 `tierPackagingRepository` | `product_machine_tier_packaging_links` | 0 行 | 删（Step 2/3） |
| `:26` 字段 `modelPackagingRepository` | `product_model_packaging_links` | 0 行 | 删（Step 2/3） |
| `:27` 字段 `moldVariantLinkRepository` | `mold_variant_links` 65 行（有效 64） | **表保留**，本类对它的依赖删 |
| `:28` 字段 `apertureResolver` | 纯函数工具类 | 本类删除后见 §1.1 |
| `:48-53` 遍历机型 link（`COMPATIBLE` 过滤、tierLabel→tierCode 回退） | `:25` | 删 |
| `:54-57` 遍历产品 link（scope「产品通用」） | `:26` | 删 |
| `:58-60` 可见性过滤（包材系列 `websiteEnabled=true` 且 `status=在售`） | `PackagingModel/PackagingVariant` | 删（随兼容概念整体下线；不影响包材主数据本身） |
| `:61` **提前返回空快照** | — | **今天所有产品都走这里** |
| `:63-67` 包材投影 + `packagingVariantIds` 去重 | `:58-60` 结果 | 删 |
| `:69-70` 模具查询 `findWebsiteVisibleByPackagingVariantIds` | `:64-67` 结果 | 删（不可达） |
| `:71-76` 按「包材规格:模具」去重 | `:69-70` 结果 | 删 |
| `:79-87` `toPackaging` | — | 删 |
| `:96-105` `toMold`（含 group/rawCaliber 回退） | — | 删；此即「明确保留或迁移」的最终答案：**不迁移** |
| 内部类 `CompatiblePackaging:30` / `CompatibleMold:36` / `Snapshot:41` | 仅本类 + `WebsiteController` | 随方案决定（见 §2） |

### 1.1 连带孤儿（删除后无调用方，需要一并裁定）

| 对象 | 当前唯一使用者 | 建议 |
|---|---|---|
| `MoldVariantLinkRepository.findWebsiteVisibleByPackagingVariantIds`（`repository/MoldVariantLinkRepository.java:27`） | 仅本类 `:70` | **删方法**，`Repository` 本体保留（包材模具其它能力在用） |
| `PackagingApertureResolver`（`service/PackagingApertureResolver.java`） | 本类 `:28` + `ProductPackagingCompatibilityService:25` | 批次 1 后即成孤儿。**建议保留类但记录用途**（封口口径归一化，未来正式配置模型大概率还要用）；若要连带删除请在本次确认 |
| `ProductMachineTierCapabilityValidator`（`service/ProductMachineTierCapabilityValidator.java`） | 仅 `ProductPackagingCompatibilityService:24` | 同上，删除后为孤儿。建议与上一项一起决定「保留待用 / 一并删除」 |

## 2. 读取侧两种方案（请你二选一，未确定前不动手）

两种方案对**当前线上输出**完全等价（兼容区块都不渲染），差别只在 JSON 契约字面形态：

| | 方案 A（推荐）彻底下线 | 方案 B 保守冻结 |
|---|---|---|
| 做法 | 删取整条读链路：`WebsiteProductCompatibilityService` 类 + `PublicProductDetail` 的 `compatiblePackaging/compatibleMolds` 字段 + `WebsiteController` 相关 record 与组装代码 | 保留类与字段，`getForProduct` 首行改为返回 `Snapshot.empty()`，仅移除两个空 link 依赖 |
| 官网 JSON | 不再包含这两个字段 | 仍包含，值为 `[]` |
| 官网页面 | `ProductDetail.vue:159-161` 已是 `detail.xxx \|\| []`，`hasCompatibility:161` 为 false → 区块不渲染 | 同左 |
| 后续（正式配置模型落地时） | 由新模型重新定义字段，干净 | 需再次改造（字段可能已从不展示变成有值），易产生二次回归 |
| 适用前提 | 已确认「未来配置不再复用旧 `ProductVariant`」——你已明确该方向 | 希望在新的配置模型落地前，保留聚合代码作为参考实现 |

推荐 **方案 A**：它把「旧链路死亡」这件事变成代码事实，而不是靠注释维持的默契；而且 §4 的契约测试已经把「有数据时如何聚合」的行为锁定成用例，未来要重写时仍有据可依。

> 无论选哪个方案，`mold_variant_links` 数据与 `PackagingMold*` 相关能力**都不受影响**。

## 3. 删除清单与执行顺序（顺序即依赖顺序）

**Step 0 —— 冻结基线（可先于一切执行）**
1. 运行 `mvn test -Dtest=WebsiteProductCompatibilityContractTest`（已新增，实测 4/4 通过，记录于 §4）；
2. 后端启动后执行 `scripts/capture-website-compatibility-baseline.ps1 -OutFile docs/p1b-batch1/website-product-baseline-before.json`，预期「products with non-empty compatibility = 0」；若不为 0，本设计作废需重新评估。

**Step 1 —— 读取侧改造**（方案 A）
- `controller/WebsiteController.java`：删 `import:23`、字段 `:57`、`:160` 调用、record 字段 `:417-418`、组装 `:431-435`、`PublicCompatiblePackagingView:440-451`、`PublicCompatibleMoldView:455-462`，以及由此产生的无用 import。
- 删类 `service/WebsiteProductCompatibilityService.java`。
- 删 `MoldVariantLinkRepository.findWebsiteVisibleByPackagingVariantIds`（`:27`）。

**Step 2 —— 写入侧删除**（无外部调用方，已在上一轮全树核对）
- 删类 `service/ProductPackagingCompatibilityService.java`；
- 删端点 `controller/ProductCatalogController.java:213-216`（GET）、`:218-223`（PUT）、`import:23`、字段 `:66`；
- 删 `dto/CatalogDtos.java` 的 `PackagingSpecOption:185`、`MachineTierPackagingAssignment:191`、`ProductPackagingCompatibilityMatrix:193`、`ProductPackagingCompatibilityRequest:199`（**只删这 4 个 record，文件保留**）；
- 视 §1.1 决定 `PackagingApertureResolver` / `ProductMachineTierCapabilityValidator` 的去留。

**Step 3 —— 实体与仓库删除**（此时才无引用）
- 删 `entity/ProductMachineTierPackagingLink.java`、`entity/ProductModelPackagingLink.java` 及其 Repository；
- 删孤儿 `entity/ProductVariantPackagingLink.java` + `repository/ProductVariantPackagingLinkRepository.java`（本就零调用，可与 Step 2 并行）；
- 删孤儿 `dto/CatalogDtos.java:365 MoveVariantRequest`。

**Step 4 —— 前端**
- 方案 A 下 `corporate-website/src/views/ProductDetail.vue` 的 `:159-161`、`:171`、`:400-443` 兼容区块成为死代码；**建议本批次不删**（保持零改动），留到「正式配置模型」落地时一次性替换，避免两次前端风险。
- 后台 frontend 零改动。

**Step 5 —— 表退役：不在本批次**
`product_model_packaging_links` / `product_machine_tier_packaging_links` / `product_variant_packaging_links` 三张表**保留**。相关迁移历史：`V67`（建 pvpl 并灌历史数据）、`V111`（改名）、`V123`（建两张新表并从 pvpl 迁移）、`V124`（再迁移并清理歧义行），最后一次触碰是 V124。DROP 需单独脚本与单独批准。

**回滚点**：Step 1/2/3 各自独立提交；任一 Step 失败可单独 `git revert`，因为它们之间没有数据写入。

## 4. 契约基线：已交付的测试（当前代码下全部通过）

新增 `backend/src/test/java/com/liqiang/archive/service/WebsiteProductCompatibilityContractTest.java`（纯 Mockito，不连库）：

| 用例 | 锁定的事实 |
|---|---|
| `emptyCompatibilityLinksYieldEmptySnapshotAndNeverTouchMoldLinks` | 两张 link 表为空 → 快照两个空数组，且 `verifyNoInteractions(moldVariantLinkRepository)`：证明模具读路径**从未被触发**（现状） |
| `nonCompatibleRelationTypesAreIgnored` | `relationType != COMPATIBLE` 不参与输出 |
| `hiddenPackagingModelsAreFilteredOutBeforeMoldLookup` | 包材系列未发布 / 非在售 → 过滤在前，不触发模具查询 |
| `aggregatesTierAndModelCompatibilityAndDeduplicatesMolds` | 「假如 link 表有数据」时的聚合行为：机型级 + 产品级各一条、模具按「规格:模具」去重、口径/模穴等字段计算方式 —— 作为未来正式配置模型的**行为参照** |

实测：`mvn test -Dtest=WebsiteProductCompatibilityContractTest` → **Tests run: 4, Failures: 0, Errors: 0**（BUILD SUCCESS）。

方案 A 执行后前三个用例随类一起删除，需替换为「`PublicProductDetail` 不再输出兼容字段」的断言（可用反射断言 record 组件不含这两个名字）；方案 B 执行后仅保留第一个用例（其余随之调整）。这部分调整在你确定方案后再写。

HTTP 层基线由脚本抓取并落 JSON，用于变更前后逐 slug 对照（`hasCompatibilityKeys` + 两个 count + `variantCount` + `completenessScore`）。当前后端未启动，脚本**未实际运行**，仅完成交付；执行前请保证数据未被改动，否则基线不可比。

## 5. ~~跨批次风险：官网完整度评分依赖 `variantCount`~~（**结论已推翻，2026-10-27**）

> ⚠️ **本节内容据 2026-10-27 逐行复核已推翻，不再成立。** 真实数据流：完整度评分的 `variantCount` 入参在产品侧来自机型档位数（`machineTierRepository`），与 `product_variants` 行数无关；退役旧规格不改变任何产品的评分。正式决定见顶部横幅与结果报告 §4。以下原文保留作历史记录，**禁止引用**。

这是本轮新发现、且直接影响你提出的「批次依赖调整」（原文，已证伪）：

- `CatalogDtos.computeCompletenessScore`（`dto/CatalogDtos.java:87-103`）把「至少一个规格」计 20 分；`WebsiteController` 用该分数做门槛过滤：列表 ≥ `WEBSITE_LIST_MIN_SCORE=40`（`:49,:137`）、官网首页精选 ≥ `WEBSITE_FEATURED_MIN_SCORE=60`（`:51,:87`）；
- `WebsiteDashboardController:160-161` 还有一条体检项 `noVariant「缺少规格参数」`，同样按 `variantCount` 判定；
- 现役数据：只有**产品 67** 拥有旧规格（4 条），其余 45 个已发布产品 `variantCount=0`。

实测产品 67 的评分解构：主图 20 + 手写摘要 0 + **规格 20** + 设备类别 20 + 素材 20 = **80**；退役旧规格后将变为 **60**。

含义：

- 60 ≥ 40（列表仍可见）、60 ≥ 60（首页精选「刚好踩线」）—— 不会立刻掉出官网，但**安全余量已归零**；
- 因此**批次 3（退役旧规格）的前置条件必须补充一条**：要么新的正式配置数据能补上这 20 分，要么先调整评分规则（例如「至少一个规格」改为「至少有素材 + 至少一条正式参数」），否则产品 67 随时可能因其它素材变动掉出官网首页。

这条已写回批次依赖：**批次 2-A（旧规格出口设计）必须先定义「什么是新的规格/配置事实来源」，评分规则随之调整，之后才允许批次 3 切断读取链路。**

## 6. 验收清单（批次 1 完成的定义）

- [ ] `mvn test` 全绿（含新增契约测试 / 方案调整后的替代断言）
- [ ] 官网列表 `GET /api/website/products` 的 `total` / 产品集合与 before 基线一致
- [ ] 全部已发布 slug 的详情：除 `compatiblePackaging/compatibleMolds`（按方案 A 字段缺失 / 方案 B 为空数组）外，**其余字段逐字段与 before 一致**（`variants`、`gallery`、`completenessScore`、`variantCount` 等不得变化）
- [ ] `GET /api/catalog/models/{id}/packaging-compatibility` 返回 404（端点已删），后台产品详情页的机型 tab 无任何请求失败
- [ ] 产品目录机型 CRUD 正常、`/api/website/packaging`、`/api/website/molds` 响应不变
- [ ] 三张 link 表仍存在且为空，**无**任何 DROP 执行

## 7. 需要你确认的两点（确认后才动手）

1. **读取侧选方案 A 还是方案 B**（见 §2，推荐 A）；
2. `PackagingApertureResolver` 与 `ProductMachineTierCapabilityValidator` 在批次 1 后成为孤儿：**保留待用**还是**一并删除**（见 §1.1）。

（批次 2 的素材语义承接、默认图裁定、新排序等三个业务问题仍按上一版 §6 保留为前置条件，不在本批次替你拍板。）
