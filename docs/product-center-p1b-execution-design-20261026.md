# 产品中心 P1-B 删除与迁移设计（证据版，2026-10-26）

> 取代 2026-10-24 的《产品中心 P1-B 去留与迁移设计》草稿（该草稿保留了存在性描述，但调用方结论有误，见 §7 修订说明）。
>
> 本阶段只做**设计**：不删除任何页面/接口/类，不执行任何数据迁移，不 DROP 表。数据库操作需另行批准。
>
> 基线：代码为本地 HEAD `7ddf837`（2026-10-26 09:18，`851be4e` 是其祖先）；数据为本地 MySQL `product_archive`，全部查询在 `START TRANSACTION READ ONLY` 内完成，可复现脚本见 `docs/product-center-p1b-verification-queries.sql`。

## 0. 结论摘要

| 结论 | 依据 |
|---|---|
| 三套「产品/机型/旧规格 ↔ 包材规格」适配表的**运行代码可以退役**，且前端零改动 | 三表实际 0 行；后端端点确有两个且零前端调用；见 §2.1 |
| 官网 `GET /api/website/products/{slug}` 的 `compatibleMolds` **来自有数据的 `mold_variant_links`（65 行）**，与空适配表混在同一个 Service，删除时必须连带改造 | `WebsiteProductCompatibilityService.java:69` + `WebsiteController.java:419-463` |
| 旧规格 `ProductVariant` **没有管理界面、没有 CRUD 端点**，唯一写入路径是全量重建导入；退役的最大阻力是**官网「可选规格」区块**与**产品默认图归属派生** | §2.2、§1.4、§1.5 |
| 26 条旧规格素材全部属于同一产品（67 / 330 机型）且**全部官网不可见**，迁移风险集中在「默认图」一张（ asset 4698） | §1.3、§1.5 |
| 23 条异常包材素材已取得**精确 ID 与所属失效规格**，全部为 `pkg_main_image`，其中 19 条是该失效规格的默认图；建议按「规格」而非「图片」做决策 | §1.2 |

执行顺序建议（每步单独验收）：**批次 1 空适配链退役 → 批次 2 迁移 26 条素材并收敛归属写通道 → 批次 3 废弃 `ProductVariant` 实体与相关表**。批次 3 之前必须先完成批次 2 与官网区块决策。

---

## 1. 数据侧复核（本次实跑结果）

### 1.1 关联表现状复核

| 关联表 | 行数 | 处置 |
|---|---:|---|
| `product_model_packaging_links` | 0 | 退役候选（代码层面） |
| `product_machine_tier_packaging_links` | 0 | 退役候选（代码层面） |
| `product_variant_packaging_links` | 0 | 退役候选（且仓库已无任何调用） |
| `product_asset_machine_tier_links` | **26** | 保留（机型素材关联，已有数据） |
| `mold_variant_links` | **65（有效 64）** | 保留（模具↔包材规格，已有数据） |
| `packaging_assets`（有效）/ `packaging_variants`（有效） | 242 / 287 | 保留 |

> 提醒：不要把上面两张有数据的表与三张空表一起处理。这点与 P1-A 的判断一致，此处只是再确认。

### 1.2 23 条异常包材素材（有效素材 → 软删除规格）

复现口径与 SQL：`docs/product-center-p1b-verification-queries.sql` §A（A1 计数 / A2 明细 / A3 按规格分组 / A4 同系列可用规格候选）。
口径：`packaging_assets.deleted_at IS NULL AND packaging_variants.deleted_at IS NOT NULL`，实跑 **23** 条，与 P1-A 报告一致。

特征（全部由数据读出，非推断）：

- 23 条**全部**为 `pkg_main_image`，**全部**满足 `asset.model_id = variant.model_id`（素材所属系列与失效规格所属系列一致，不存在跨系列错挂）；
- 涉及 **20 个失效规格**、**4 个系列**：**打包盒系列（PP方盒）9 条 / 圆碗系列（PP碗）7 条 / 酱料盒系列（PP杯+盖）5 条 / 大闸蟹盒·鱼盒系列 2 条**；
- 其中 **19 条是所属失效规格的默认图**（`default_asset=1`），**4 条非默认图**（asset 96 / 127 / 144 / 301）。

明细（`asset_id → 失效规格 variant_id（规格名）`，文件名略写，全量文件名见 A2 查询结果）：

| 系列 | asset_id → variant_id（规格名） | 默认图 |
|---|---|---|
| 圆碗系列（PP碗）系列 4 | `96→396(180×130×100mm)`、`127→396`、`136→396` | 96/127 否，136 是 |
| 圆碗系列（PP碗）系列 4 | `100→397(120×90×90mm)` | 是 |
| 圆碗系列（PP碗）系列 4 | `265→568(150×120×100mm 22g)` | 是 |
| 圆碗系列（PP碗）系列 4 | `301→708(C230)`、`302→708(C230)` | 301 否，302 是 |
| 酱料盒系列（PP杯+盖）系列 7 | `141→408(46×20mm)`、`142→410(89×50mm)`、`143→411(89×41mm)`、`144→409(89×60mm)`、`295→581(72×72×40mm)` | 除 144 外均为是 |
| 打包盒系列（PP方盒）系列 2 | `280→547(140×140×25mm 8g)`、`281→548(140×140×35mm 10g)`、`282→549(160×110×35mm 13g)`、`283→550(160×110×50mm 15g)`、`284→551(180×120×25mm 12g)`、`285→552(220×130×60mm 24g)`、`287→554(260×170×30mm 20g)`、`288→555(260×170×30mm 25g)`、`290→557(260×170×50mm 28g)` | 全部是 |
| 大闸蟹盒鱼盒系列 系列 3 | `291→558(260×170×140mm 60g)`、`293→559(300×200×140mm 56g)` | 全部是 |

处置原则：**按规格决策，不按图片决策**。对每个失效规格从「① 该规格仍在售 → 恢复规格；② 图片本属其他有效规格 → 迁移素材；③ 仅供历史查询 → 归档但保留文件」三选一，并逐条记录确认人。禁止统一置空 `variant_id`、禁止批量删除文件（`A4` 查询会给出同系列内的有效规格候选）。

### 1.3 26 条旧规格素材（迁移对象）

复现 SQL：§B（B1 规格本体 / B2 素材明细 / B3 按规格与类目分组 / B4 机型对照）。

| 项 | 结果 |
|---|---|
| 归属 | 全部属于产品主档 **67「利强手压片膜封口机（斜头）」**、机型 **1 / 330 机型** |
| 类目分布 | `detail_image` 17（其中 1 张是默认图 asset **4698**）、`main_image` 5、`scene_image` 3、`sku_image` 1 |
| 官网可见性 | **26 条 `website_visible` 全为 0** → 迁移不影响官网图库输出 |
| 语义来源 | 规格名即语义：`889 方模具，一出一`（spec_key `一出一|方模具`）、`890 方模具，一出二`（`一出二|方模具`）、`892 圆模具，一出一`（`一出一|圆模具`）；`886` 为 baseline 标准版，**无素材引用** |

映射清单（`variant_id → [asset_id]`，可直接作为迁移脚本的输入）：

- `889 方模具，一出一`（7 条，全 `detail_image`）：`4697, 4698(默认图), 4699, 4700, 4701, 4706, 4708`
- `890 方模具，一出二`（8 条）：detail `4713, 4714, 4715, 4716, 4717`；main `4722, 4723`；sku `5249`
- `892 圆模具，一出一`（11 条）：detail `4702, 4703, 4704, 4705, 4707`；main `4720, 4724, 4725`；scene `4728, 4729, 4730`

迁移建议（需业务确认后执行，不在本阶段实施）：

1. 语义承接优先落到**素材 `tags` / 文件命名 / 产品说明**，而不是再造一层结构：`ProductVariantTagPolicy` 已把 `一出一|方模具` 这类 key 作为标签族处理（`service/ProductVariantTagPolicy.java:56,61,68,74-80`），可直接复用；
2. `variant_id` 置空前必须处理 **asset 4698**：它是 `detail_image` 的默认图，而 `ModelSummary.defaultImageOwnerType` 是按 `asset.variant_id` 是否为空派生的（`dto/CatalogDtos.java:44-80`，尤以 `computeCompletenessScore:87` 参与官网完整度评分）。产品 67 同时存在产品级默认图 asset **5221**（`main_image`），建议迁移前先把默认图切换到 5221，或明确接受「ownerType 由 `product_variant` 变为 `product_model`、完整度评分不变」；
3. 同类目 `sort_order` 存在大量 0 值，迁移到产品级后需要重排，否则产品图库顺序会变。

### 1.4 旧规格本体 4 条

`886`（baseline=1，无素材）/ `889` / `890` / `892`，均 `machine_tier_id=1`，`model_id=67`，`sort_order=0`；`spec_key` 已完整承载「一出数 + 模具形式」语义。因此**退役 `product_variants` 不等于丢失语义**，但必须在批次 2 完成后才成立。

### 1.5 退役影响面（容易漏掉的）

| 对象 | 现状 | 影响 |
|---|---|---|
| `product_documents` | 0 行，但 `variant_id` 有外键 | 表本身无关数据，退役时需先解外键或一并裁定 |
| `product_variants_archive` / `product_models_archive` / `product_assets_archive` | 存在且非空（information_schema 估算约 86 / 5667 / 114 行） | `ArchiveScheduler.java:71-76` 会把超期软删记录搬入归档表，批次 3 必须同时决定这三张归档表 |
| 产品默认图 | 由 `product_assets.default_asset` 派生（`product_models` 只有 `default_image` 文件名，无 asset_id 列） | 见 §1.3 第 2 点 |

---

## 2. 代码调用方清单（本地 HEAD `7ddf837` 实际核对）

判定标记：**A** = 只服务待退役能力，可整段删除；**B** = 混有其他能力，删除需保留部分；**C** = 零调用/不存在。

### 2.1 空包材适配链

| 对象 | 位置 | 判定 |
|---|---|---|
| `ProductPackagingCompatibilityService`（`getMatrix:29`、`replace:47`） | `service/ProductPackagingCompatibilityService.java:18` | **A**，唯一注入方是 Controller |
| `GET /api/catalog/models/{id}/packaging-compatibility` | `controller/ProductCatalogController.java:213-216`（import `:23`、字段 `:66`） | **A**（端点存在，零调用方） |
| `PUT /api/catalog/models/{id}/packaging-compatibility` | `controller/ProductCatalogController.java:218-223`（`@RequirePermission("product.manage")`） | **A** |
| DTO `PackagingSpecOption` / `MachineTierPackagingAssignment` / `ProductPackagingCompatibilityMatrix` / `ProductPackagingCompatibilityRequest` | `dto/CatalogDtos.java:185,191,193,199` | **A**（只删这 4 个 record，`CatalogDtos` 本体保留） |
| 实体 `ProductModelPackagingLink` / `ProductMachineTierPackagingLink` | `entity/ProductModelPackagingLink.java:14,16`、`entity/ProductMachineTierPackagingLink.java:14,16` | **A**（受下方 Website 分支牵连，须与之同步改造） |
| 仓库 `ProductModelPackagingLinkRepository` / `ProductMachineTierPackagingLinkRepository` | 同名文件 `:11` | **B**（仍被官网 Service 读取） |
| 实体 `ProductVariantPackagingLink` + `ProductVariantPackagingLinkRepository` | `entity/ProductVariantPackagingLink.java:23,26`、`repository/ProductVariantPackagingLinkRepository.java:11,15,19` | **C**（全仓零注入/零引用） |
| `MoveVariantRequest` | `dto/CatalogDtos.java:365` | **C**（无端点使用） |
| **`WebsiteProductCompatibilityService`** | `service/WebsiteProductCompatibilityService.java:24`；`getForProduct:46` 读 `:48` tierLink、`:54` modelLink、`:69` `moldVariantLinkRepository` | **B**：删除前两个 link 仓库的读取后，**必须保留 `:69-76` 模具分支与 `:96-105` 的 `toMold`**，否则官网接不到模具数据 |
| `WebsiteController` | `controller/WebsiteController.java:57`（注入）、`:160`（调用）、`:413-435`（出参组装）、`:440-451` / `:455-462`（两个公开视图） | **B**：官网公开在读，需连带改造 |
| frontend `api/catalog/machineTiers.ts` | 只有机型 CRUD（`fetchMachineTiers:50`、`createMachineTier:54`、`updateMachineTier:58`、`deleteMachineTier:62`） | **C**：文件中**不存在** `fetchProductPackagingCompatibility` / `replaceProductPackagingCompatibility`；全仓 frontend 搜 `packaging-compatibility` = 0 命中 |
| `components/product/ProductMachineConfigurationTab.vue` | `:28` import、` :41` 调 `fetchMachineTiers`（位置在 `components/product/`，**不是** `apps/archive/views/`） | **C**：与适配矩阵无关，只做机型 CRUD |
| 测试 | backend tests / frontend `__tests__` 均无引用 | **C** |

相关迁移脚本：`V67`（创建 `product_variant_packaging_links` 并灌历史数据）、`V111`（改名 `compatibility_status→relation_type` 等）、`V123`（创建两张新 link 表并把数据从 pvpl 迁出）、`V124`（把 model link 再并入 tier link 并清理歧义行）。**最后触碰这些表的脚本是 V124**，之后到当前最高版本再无引用 —— 与「三表均 0 行」自洽。

### 2.2 旧规格 `ProductVariant` 链

**先说结论：管理入口已经不存在了。**

- `controller/ProductCatalogController.java`（全文 385 行）**没有任何 `/variants` 端点**，也没有 `createVariant/updateVariant/deleteVariant` 方法；机型相关只有 `:190-211` 的 machine-tiers CRUD；
- 前端 `api/catalog/models.ts` 中 `variant` 零命中；`specificationAction` 只出现在 `api/catalog/assets.ts:28,53` 的 payload 类型定义里，**没有任何组件传值**；`types/catalog.ts` 的 `CatalogVariant:51` / `ModelDetail.variants:98` / `VariantEditorForm:160` 属于残留类型；
- 产品详情抽屉里的 `ProductCatalogView.vue:111` tab key 仍叫 `'variants'`，但渲染的是 `ProductMachineConfigurationTab`（机型参数），**不是旧规格页**。

现存真实依赖（分「能删 / 要改造 / 要保留」）：

**（1）能随退役整段删除（C/A）**

| 对象 | 位置 | 说明 |
|---|---|---|
| `CatalogTrashService` 的规格回收站方法族 | `listTrashVariants:42`、`permanentDeleteVariant:194-203`、`emptyTrashVariants:248-262`、`batchRestoreVariants:299-304`、`batchPurgeVariants:307-313` | 全仓零调用方（产品回收站没有 Controller 暴露；`PackagingController:182,188` 的同名方法属于 `PackagingService`，与本类无关） |
| `ProductVariantFileAssetAdapter` | `service/file/ProductVariantFileAssetAdapter.java:15-37`（`OwnerType.PRODUCT_VARIANT`） | 由 `FileAssetBehaviorService.java:17-24` 按 `List<FileAssetAdapter>` 自动装配，退役时连同 `OwnerType.PRODUCT_VARIANT` 枚举值一起处理 |

**（2）必须改造后才能删（B）**

| 对象 | 位置 | 改造点 |
|---|---|---|
| `ProductCatalogService` | 级联软删 `:301-303`、`:1123-1124`；素材移动/新增归属 `:391-395`、`:1174`、`:1298-1304`；批量 metadata `:824-828`、`:866-916`；`AssetPayload.variantId:1310` | 删除上述分支，`moveAsset` 的 `targetVariantId` 语义下线 |
| `CatalogImportService` | `physicalResetProductTables:119-126`（清空 `product_variants`）、`createVariant:230-242`、`:177-179`（保存）、`createAssets:244-255` + `isVariantAsset:271-273`（只有 `sku_image` 挂规格）、`ImportResult.variants:196-208` | 全量重建导入改为「产品 + 素材」，SKU 图归属到产品；导入结果结构里的 `variants` 计数需同步下线（前端 `types/catalog.ts:171` 的 `ImportResult.variants` 一并处理）。入口：`CatalogImportController.java:25-27` `POST /api/catalog/import` |
| `CatalogTrashService` | `restoreModel:154-158`、`:282-285`、`permanentDeleteModel:176-191`、`batchRestoreModels`、清理过期 `:358-371` | 产品级联里的「同步恢复/清理规格」分支需要去掉 |
| `ArchiveScheduler` | `:71-76` 归档 `product_variants → product_variants_archive` | 去掉该行；归档表去留另批决策 |
| 文件 owner 类型判定 | `utils/catalog/fileAssetUtils.ts:15`（`product_variant` 分支）+ `frontend/src/utils/catalog/__tests__/fileAssetUtils.test.ts:9-28` | 迁移完成后 `variantId` 恒为空，分支与测试需同步收敛 |
| `ProductAsset`/`ProductVariant` 实体、`ProductVariantRepository`（16 处引用点） | `entity/ProductAsset.java:44-46`、`entity/ProductVariant.java`、`repository/ProductVariantRepository.java` | 批次 3 才删 |

**（3）不能删、只能替换或显式下线（官网 + 派生口径）**

| 对象 | 位置 | 说明 |
|---|---|---|
| 官网公开接口出参 | `controller/WebsiteController.java:414,416`、`variants()` 组装 `:431`、`PublicVariantView:489-495` | 官网产品详情直接输出旧规格列表 |
| 官网页面渲染 | `corporate-website/src/views/ProductDetail.vue:147-157`（卡片字段）、`:170`（导航「可选规格」）、`:382-397`（区块） | 重要：该区块读取的 `applicableProduct/boxOrMoldShape/packagingSpeed/voltage/power/deviceSize` **在当前 `PublicVariantView` 中已不存在**（只剩 `variantName/specKey/baseline/mold*`），即前端已处于「只显示名称」的空壳状态。退役旧规格 = 明确下线这一区块，而不是数据突变 |
| `ProductVariantTagPolicy` | `service/ProductVariantTagPolicy.java`（被 `CatalogDtos.java:7,321`、`ProductCatalogService.java:839,860,862` 使用）+ `ProductVariantTagPolicyTest.java` | 名字带 Variant，实为产品素材标签词表（方/圆模具、一出数），**与 `product_variants` 表无关**；建议在文档里标注为「命名噪音，不删」 |
| 契约/链接测试 | `backend/src/test/java/com/liqiang/archive/service/ProductCatalogServiceVariantLinkTest.java`、`ProductVariantTagPolicyTest.java` | 前者随批次 2/3 收敛，后者保留 |

需要移除/收敛的测试与样式等细节，在批次执行时以「全树再搜 + 编译通过」为准；本设计不代替执行前的二次扫描。

---

## 3. 分批实施清单（不执行，仅排队）

**批次 1 —— 空适配链退役（低风险，建议优先）**
1. 先改造 `WebsiteProductCompatibilityService`：只去掉 `:48/:54` 对两张空 link 仓库的读取，**保留 `:69-76` 模具分支**，保证 `compatibleMolds` 输出不变（执行前先对同一个 slug 抓一次响应做对照基线）。
2. 删除 §2.1 中标记为 A/C 的全部对象（Service、两个端点、4 个 DTO、2 个实体、孤儿 Repository、`MoveVariantRequest`）。两个 link Repository 在 Website 改造完成后才可去依赖。
3. 前端零改动。
4. 验收：产品详情、机型 CRUD、官网产品详情/包材页响应对照一致。
5. 表结构保留到批次 3 之后统一裁定（`product_model_packaging_links` / `product_machine_tier_packaging_links` / `product_variant_packaging_links`）。

**批次 2 —— 26 条素材迁移 + 归属写通道收敛**
1. 按 §1.3 的映射清单逐格确认「方模/圆模、一出一/一出二」语义落到哪里（标签 / 命名 / 说明 / 模具资料）。
2. 处理默认图 asset 4698 与产品 67 的图序（§1.3 第 2、3 点）。
3. 收敛写通道：`AssetMetadataRequest.specificationAction/variantId`、`MoveAssetRequest.targetVariantId`、`AssetPayload.variantId`、`ProductVariantFileAssetAdapter`、`fileAssetUtils.ts` 判定与测试。
4. 导入侧（`CatalogImportService`）改「只导产品+素材」；回收站去掉规格方法族；`ArchiveScheduler` 去掉 `product_variants` 归档行。
5. 验收：26 条素材在产品 67 的图库中数量、顺序、下载链接与官网可见性一致；导入全量重建可跑通（建议只在测试库验证）。

**批次 3 —— `ProductVariant` 实体与相关表退役**
前置：批次 2 完成且验收通过、官网「可选规格」区块已按业务决策处理（下线或替换为新的参数体系展示）。
内容：删除 `ProductVariant` 实体/Repository/JSON 出口，处理 `product_documents.variant_id` 外键、`product_variants_archive` 归档表，最后才考虑 `DROP TABLE product_variants`（单独脚本、单独批准）。

**不在本设计范围（后续另批）**：设备视频库交互重做、模具/机型界面简化、知识产权与物品资产导航归属调整、`packaging_variants`/`packaging_assets` 的 23 条异常处置（见 §1.2，属于包材侧独立任务）。

---

## 4. 23 条异常素材的决策表模板

复现 SQL 见 SQL 脚本 §A。建议按「失效规格」一行一格让业务确认（20 行而不是 23 行）：

| variant_id | 规格名 | 系列 | 涉及 asset | 默认图数 | 处置（三选一） | 确认人 | 备注 |
|---|---|---|---:|---:|---|---|---|
| 396 | 180×130×100mm | 圆碗系列 | 96,127,136 | 1 | 恢复规格 / 迁移到有效规格 / 归档保留 | | |
| 397 | 120×90×90mm | 圆碗系列 | 100 | 1 | | | |
| …（其余按 §1.2 明细与 A3 分组结果填写） | | | | | | | |

约束（与 P1-A 一致）：不得统一置空 `variant_id`；不得批量恢复规格；不得删除物理文件；每条决策都要能追溯到确认人与原因。

---

## 5. 明确不动 / 延后

- `product_models`（48 行，46 个官网发布）、`product_machine_tiers`（3 行）、`packaging_models` / `packaging_variants` / `packaging_assets`、`packaging_molds`（77 行）、`mold_variant_links`（65）、`product_asset_machine_tier_links`（26）、视频相关资料与检索能力：全部保留。
- `sales_products.product_model_id` 非空为 0 说明该外键当前无实际关联记录，**不代表可以删产品主档**（官网、工序、导入、出库台账侧仍以产品身份存在）。
- 导航归属类调整（知识产权、物品资产）只动导航不动数据。

---

## 6. 待业务确认的问题

1. 官网「可选规格」区块（`ProductDetail.vue:382-397`）在旧规格退役后是**下线**还是**用新的参数体系重建**？这决定批次 3 能否进入。
2. 26 条素材的「一出数 + 模具形式」语义，接受用**素材标签**承接，还是需要单独的模具资料页承接？
3. 产品 67 的默认图是否切换到 asset 5221（产品级 `main_image`）？
4. 20 个失效包材规格中，哪些仍在售需要恢复？（决定 23 条素材是「迁移」还是「恢复」）

---

## 7. 相对 2026-10-24 草稿的修订

| 草稿原文 | 本次核对结果 |
|---|---|
| 「`api/catalog/machineTiers.ts` 的 `fetchProductPackagingCompatibility` / `replaceProductPackagingCompatibility`」 | **这两个函数不存在**（`machineTiers.ts` 只有机型 CRUD 4 个函数）；全仓 frontend 零命中 → frontend 无需改动 |
| 「`ProductMachineConfigurationTab.vue` 内包材适配矩阵，页面仍调用矩阵读写 API」 | 该组件在 `components/product/`（不在 views），`:28,41` 只调 `fetchMachineTiers`，**不调用适配 API** |
| 未识别 | 新增：`WebsiteProductCompatibilityService:69` 混读 `mold_variant_links`（65 行），是唯一的官网风险点 |
| 「素材 ID：889 → 4697…」等映射 | 已实跑复核，映射一致，并补齐类目分布、官网可见性、默认图影响三处此前缺失的信息 |
| 未包含 | 新增：可复现 SQL 脚本（`docs/product-center-p1b-verification-queries.sql`），含 23 条明细语句与 26 条迁移清单查询 |
