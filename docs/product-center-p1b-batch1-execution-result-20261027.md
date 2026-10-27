# 产品中心 P1-B 批次 1：执行结果报告（2026-10-27）

配套设计：`docs/product-center-p1b-batch1-execution-design-20261027.md`、总设计 `docs/product-center-p1b-execution-design-20261026.md`。

代码基线：HEAD `7ddf837` + 本次未提交改动。数据库：`product_archive`（本地），**全程未执行任何 DROP / UPDATE / DELETE**，后端启动时显式 `-Dspring.flyway.enabled=false`。

---

## 1. 决策落地情况

| 你的决策 | 落地情况 |
|---|---|
| 读取侧采用**方案 B**：保留字段、冻结为空数组 | 已实现。`PublicProductDetail` 仍输出 `compatiblePackaging` / `compatibleMolds`，恒定为非 null 空数组；`from(...)` 不再接受 Snapshot 参数 |
| 删除无用的写入链路（Service / 端点 / DTO） | 已删除：`ProductPackagingCompatibilityService`、两个 `/packaging-compatibility` 端点、`CatalogDtos` 中 4 个 record 与孤儿 `MoveVariantRequest`、两个适配实体与其 Repository、孤儿 `ProductVariantPackagingLink` 实体+Repository、`MoldVariantLinkRepository.findWebsiteVisibleByPackagingVariantIds` |
| `ProductMachineTierCapabilityValidator` 孤儿 → 删除 | 已删除（它确实依赖旧实体 `ProductVariant`：`validate(tier, variant)` 读 `variant.getMoldSizeType()/getMoldSizeLabel()`） |
| `PackagingApertureResolver` 仅当具备独立有效业务规则才保留 | **核实不通过 → 已删除**（证据见 §6，可从 Git 一行恢复） |
| 批次 1 不 DROP 表 | 遵守。三张 link 表仍在库中，`ALTER/DROP` 未执行，待批次 3 单独脚本与批准 |

### 已删除文件清单

```
service/WebsiteProductCompatibilityService.java
service/ProductPackagingCompatibilityService.java
service/ProductMachineTierCapabilityValidator.java
service/PackagingApertureResolver.java
entity/ProductMachineTierPackagingLink.java
entity/ProductModelPackagingLink.java
entity/ProductVariantPackagingLink.java
repository/ProductMachineTierPackagingLinkRepository.java
repository/ProductModelPackagingLinkRepository.java
repository/ProductVariantPackagingLinkRepository.java
test/.../service/WebsiteProductCompatibilityContractTest.java（旧服务测试，随服务删除）
```

### 已修改文件清单（本批次）

| 文件 | 改动 |
|---|---|
| `controller/WebsiteController.java` | 移除 `WebsiteProductCompatibilityService` 注入与调用；`PublicProductDetail.from(ModelDetail)`；两个兼容字段标记 `@Deprecated` 并写入移除条件 |
| `controller/ProductCatalogController.java` | 移除两个兼容端点、字段与 import |
| `dto/CatalogDtos.java` | 移除 4 个兼容 record + 孤儿 `MoveVariantRequest`（−22 行） |
| `repository/MoldVariantLinkRepository.java` | 移除仅服务旧链路的 `findWebsiteVisibleByPackagingVariantIds`（−7 行） |
| `test/.../controller/WebsitePublicProductDetailContractTest.java` | 新增：把「两个字段必须存在且为非 null 空数组」固化为正式契约 |

全仓再搜 `packaging-compatibility|ProductPackagingCompatibility|WebsiteProductCompatibilityService|ProductMachineTierCapabilityValidator|PackagingApertureResolver|findWebsiteVisibleByPackagingVariantIds` → **仅命中 docs 目录，代码零残留**。

---

## 2. Step 0 基线：HTTP 层前后对照（已实际运行）

后端已启动（`-Dspring.flyway.enabled=false`，避免任何迁移写入），用 `scripts/capture-website-compatibility-baseline.ps1` 抓取：

| 指标 | before | after |
|---|---|---|
| 已发布产品数 | 45 | 45 |
| `hasCompatibilityKeys`（字段是否仍存在） | 45/45 true | 45/45 true |
| `compatiblePackaging` / `compatibleMolds` 非空的产品数 | 0 | 0 |
| `variants` 非空的产品数 | 0 | 0 |
| 逐产品 `slug / completenessScore / variantCount / variants / compatiblePackaging / compatibleMolds / hasCompatibilityKeys` 变更行 | — | **0 行差异** |

基线文件：`docs/p1b-batch1/website-product-baseline-before.json`、`-after.json`。
比对方式：`Compare-Object` 对上述 7 个字段逐产品对照，`diff_rows=0`。

这条 HTTP 证据同时**反向证实**了设计阶段的推断：在今天的数据状态下，旧链路从未产生任何输出，批次 1 对官网是零影响。

---

## 3. 测试与编译边界

- 新契约测试 `WebsitePublicProductDetailContractTest`：**3/3 通过**（字段非 null 且为空、JSON 序列化仍带 `"compatiblePackaging":[]` 与 `"compatibleMolds":[]`、`variantCount` 透传不受影响）。
- 后端**全量** `mvn test`：**Tests run: 528, Failures: 0, Errors: 0, Skipped: 15 → BUILD SUCCESS**。

### 验收范围的明确边界（不得被读作「全量等价验证」）

按复核意见写入，避免证据被过度解读：

1. **528 个测试通过 / 15 个 skipped** 只覆盖本项目后端现有测试 suite；它证明本次删除未破坏已覆盖的功能，不代表未被覆盖的行为也不受影响。
2. **HTTP 对照只覆盖 45 个已发布产品的官网产品详情（`/api/website/products/{slug}`）**，且只比对 7 个字段；它**不等于**验证过所有可能的外部 API 消费者——其它部署环境、其它协议消费者（含未来可能的第三方集成）仍需另行验证。
3. 本机 `product_archive` 的两张 link 表为 0 行，**只能支撑本机结论**；其它部署环境实施前必须再次只读确认（Step 0 要求，已列入文档）。
4. 数据库侧结论：**未执行任何 DROP / TRUNCATE / DELETE，未新增任何迁移脚本**（本次提交不含 `db/migration` 变更）。
- 旧聚合逻辑（「假如 link 表有数据」机型级+产品级各一条、模具按 `规格:模具` 去重、口径标签计算）**不再作为新服务的契约**，已从测试代码移除；其行为描述保留在总设计文档 §2.1 与本报告 §6 作为历史参考，算法本体可从 Git 历史（`7ddf837` 的 `WebsiteProductCompatibilityService`）恢复。这正是你要求的「历史参考归文档，正式契约锁定空数组」。

---

## 4. 重大更正（已正式推翻旧结论，不得再作为 P1-B 设计依据）

上一轮报告「退役旧规格会让产品 67 从 80 分降到 60 分」，**该推断已于 2026-10-27 逐行复核推翻**，并已同步标记在设计文档 `product-center-p1b-batch1-execution-design-20261027.md` §5。由此：

1. **撤销「批次 3 必须先修改完整度评分」的阻断条件**——它建立在错误的数据来源判断之上（真实数据来自机型档位数）。
2. **评分语义优化仍列入批次 2-A 研究**，但性质是「产品资料质量设计」，不是旧规格退役的技术前置条件；评分行为本批次保持不变。
3. `variantCount` 字段实际表达「机型档位数」，语义澄清（DTO/前端展示）建议在后续批次逐步处理，**本次提交不重命名该字段**，避免扩大接口变更面。

`computeCompletenessScore`（`dto/CatalogDtos.java:87-103`）的入参 `variantCount` 在产品侧**来自机型档位数，不是 `product_variants` 行数**：

- 列表/首页：`ProductCatalogService:186` 用 `machineTierRepository.countByModelIds(...)`
- 详情：`ProductCatalogService:252` 用 `machineTierRepository.findByModelIdOrderBySortOrderAscIdAsc(id).size()`
- `ProductVariantRepository.countByModelIds`（`:36`）**全仓没有任何调用方**
- 同样地，`WebsiteDashboardController:160-161` 的体检项 `noVariant「缺少规格参数」` 读的是同一个 `ModelSummary.variantCount`，也是机型档位数

因此：**退役 `product_variants` 不会改变任何产品的完整度评分**，批次 3 少一个前置条件（不必为保分而先改评分规则）。

产品 67 今天 80 分的真实成因（已按评分规则与真实数据逐项核对）：主图 20 + 手写摘要 0（`website_summary` 以模板句「…，专业封口机，品质保障。」结尾，按 `:94-96` 不计分）+ **机型档位 3 个 → 20** + 设备类别 20 + 素材 ≥1 → 20 = 80。

### 首页精选的实际判断条件（你要求核查而非推断）

- `WebsiteController:69`、`:109`：`>= WEBSITE_FEATURED_MIN_SCORE`（**≥ 60，闭区间**，不是 `>`）
- 前置硬过滤：`ProductModelRepository:121-123` → `websiteEnabled = true AND websiteFeatured = true`
- 兜底：精选集合为空时才走列表门槛 `>= 40` 取前 6（`WebsiteController:85-89`）
- 实测：`website_featured=1` 的已发布产品 **44 个**，产品 67 在其中

结论：按当前规则，即使评分真的发生变化到 60 分，仍然满足 `>= 60`；而实际它不会变化，所以**批次 3 不影响首页精选**。评分规则本身仍建议在批次 2-A 重新定义语义（见 §7），但那是语义治理问题，不是退役阻断项。

---

## 5. 附带发现（对批次 2-A 有利）

今日实测 `GET /api/website/products/{slug}`（45 个已发布产品逐个请求）：**`variants` 数组在所有产品上都是空的**，包括唯一拥有旧规格的产品 67（它有 4 条旧规格，`variantCount` 显示 3）。

原因是 `ProductCatalogService.get(id)`（`:248-256`）构造 `ModelDetail` 时，第二项直接给了 `List.of()`——旧规格列表在官网详情链路里**已经不再被读取**。

含义：官网「可选规格」区块今天对所有产品都不渲染，批次 2-A 把该区块正式隐藏/下线时，**不会产生可见变化**；这印证了你「不再复用旧 `ProductVariant` 作为官网配置数据源」的方向。

---

## 6. `PackagingApertureResolver` 核实结论（按你设定的条件判定：不满足，已删除）

| 核查项 | 结果 |
|---|---|
| 批次 1 后的调用方 | 0（`ProductPackagingCompatibilityService`、`WebsiteProductCompatibilityService` 均在本次删除） |
| 自身是否有未使用的方法 | 有：`fromSpecification(...)`、`normalizeMoldShape(...)` 在全仓无任何调用 |
| 是否依赖已退役结构 | 不依赖旧 DTO/实体，依赖的是仍在服役的 `PackagingVariant`/`PackagingModel` |
| 是否承载仍然有效的**权威**业务规则 | **否**。应用中实际生效的口径/形状匹配规则是 `PackagingMoldImportService.matchVariants` 与其前端镜像 `frontend/src/composables/mold/shapeMatch.ts`（三级容差评分、±2/6mm、权重 length 40/width 40/diameter 50/height 20、形状硬过滤），与本类（仅 round/oval 归一 + 标签拼接）不是同一套口径 |
| 结论 | 按你给的准则「必须能证明封装的是仍然有效的业务规则」→ **不满足，删除** |

恢复方式（若日后确有需要）：`git show 7ddf837:backend/src/main/java/com/liqiang/archive/service/PackagingApertureResolver.java`。

---

## 7. 收口状态与下一步

批次 1 验收项：

- [x] 单元测试：新增契约 3/3；全量 528 通过
- [x] HTTP 契约：45 个产品前后 JSON 逐字段零差异（含字段存在性）
- [x] 编译与应用启动：Spring 上下文正常加载（无悬空注入），`/actuator/health` UP
- [x] 写入入口已全部退出，三张 link 表不可能再被写入（多次全仓搜索确认）
- [x] 数据库表保留，未执行任何 DROP / DDL

仍为**未完成**且留给你的事项（不在本批次范围）：

1. 两个 `@Deprecated` 字段的移除条件：已在 `PublicProductDetail` javadoc 写明「批次 2-A 确定官网正式配置模型后，再决定接管或删除」。
2. 批次 2-A：官网三层数据语义重新定义（产品基础参数 / 可选配置 / 展示与推荐），并顺带清理评分规则中「至少一个规格」的语义（它现在其实是「至少一个机型档位」）。
3. 批次 2-B：26 条旧规格素材迁移映射 + 默认图裁定（asset 4698 仍是前置阻断项，`ModelSummary.defaultImageOwnerType` 由素材的 `variant_id` 派生，与 §4 的 `variantCount` 不是同一回事）。
4. 批次 3：切断导入写入、回收站方法、归档任务后再评估实体与表的退役；先从备份/回滚方案开始，不物理 DROP。
5. 23 条异常包材素材：独立数据治理任务，决策表见 `docs/packaging-anomaly-decision-table-20261027.md`，业务确认前不恢复、不迁移、不删除。

> 备注：本地 8081 后端目前处于运行状态（新代码），如需停用请告知。
