# P1-B 批次 2-A（一）：现有模型能力盘点（2026-10-27）

> 性质：**只读盘点**。未修改业务代码、未建表、未迁移数据、未改评分、未改官网页面。
> 数据源：本机 `product_archive`（只读事务 / 单条 SELECT）；代码 HEAD `3df6f50c`（批次 1 已提交）；前端与官网前端按工作区实际文件核对。
> 目的：回答「已有模型能表达什么、缺少什么」，为《旧规格语义归属与最小模型建议》提供事实底座。
> 边界提醒：结论基于本机数据。其它部署环境的数据分布（尤其机型档位与模具规模）需另行只读复核。

---

## 0. 数据总览（本机，2026-10-27）

| 表 | 行数（约） | 本次角色 |
|---|---:|---|
| `product_models` | 49（已发布 46，其中 `website_featured=1` 44） | 产品主档 |
| `product_machine_tiers` | **3** | 机型档位 |
| `product_variants` | **4**（全部属于产品 67） | 旧规格（待退役） |
| `product_variants_archive` | 86 | 旧规格归档表 |
| `product_assets` | 475（产品 67 有 35，其中 26 条挂旧规格） | 产品素材 |
| `product_asset_machine_tier_links` | **26** | 素材 × 机型档位（多对多） |
| `packaging_models` | 49 | 包材系列 |
| `packaging_variants` | 478 | 包材规格（盒型/尺寸） |
| `packaging_molds` | 77 | 模具 |
| `packaging_mold_groups` | 48 | 模具分组（形状/尺寸） |
| `mold_variant_links` | 65（有效 64） | 模具 × 包材规格 |
| `website_products` / `website_product_variants` / `website_media_assets` / `website_product_categories` | **均 0 行（表已建）** | 官网独立内容模型（方案 A，另一工作线在建） |

---

## 1. `ProductModel`（产品主档 `product_models`）

| 字段 | 当前含义 | 真实数据 | 读写消费者 | 能否表达设备配置 | 是否适合作权威事实来源 |
|---|---|---|---|---|---|
| `id/name/model_code/category/status` | 基础档案 | 49 行，`category` 有值（如「托盒封口机」） | 产品中心 CRUD、`ProductCatalogService`、官网 `PublicProductModelView` | 否（只到「类别」粒度） | ✅ 产品身份与分类 |
| `default_image` / 素材默认图 | 产品主图 | 有值（产品 67 → 主图 asset 5221） | `ModelSummary.defaultImageAssetId`、`defaultImageOwnerType`（按素材 `variant_id` 是否为空派生） | 否 | ✅ 但默认图规则需与素材归属一起裁定 |
| `application_scope / introduction_cn / description_cn` | 产品介绍文案 | 部分有值 | 后台编辑 + 官网「产品介绍」 | 否 | ⚠️ 官网方案 A 下应逐步迁到 `website_products` |
| `capacity_level` | 产能分级 | 有值（如「手动/小批量」） | 官网筛选与展示 | 弱（一级标签） | ⚠️ 可作展示标签，非配置事实 |
| `website_enabled / website_featured / website_slug / website_summary` | 官网发布控制 | 已发布 46、精选 44 | `WebsiteController`、`ProductModelRepository.findWebsiteFeaturedProducts` | 否 | ✅ 发布开关仍属内部（官网解耦后应迁移） |
| （不存在）`voltage / power / packagingSpeed / deviceSize / maxFilmWidth …` | 官网「技术参数」页面要读的字段 | **表内没有这些列** | `corporate-website/src/views/ProductDetail.vue:93-112` 的 `specRows` 读 `m.voltage` 等 14 个字段，而 `PublicProductModelView` 并不输出它们 | — | ❌ 官网「技术参数」区块**今天恒为空**，不依赖旧规格 |

要点：产品主档**不承担**设备配置；官网技术参数字段在当前接口里本就不存在，因此「技术参数空」不是旧规格退役造成的。

## 2. `ProductMachineTier`（机型档位 `product_machine_tiers`）

| 字段 | 当前含义 | 真实数据（全部 3 行） | 读写消费者 |
|---|---|---|---|
| `tier_code / tier_label` | 机型型号 | `330 / 330机型`、`390 / 390机型`、`500 / 500机型`，**全部属于产品 67** | `ProductMachineTierService`（listByModel/create/update/delete）、`ProductCatalogController`（`/api/catalog/machine-tiers*`）、`frontend/src/components/product/MachineTierManager.vue`、`ProductMachineConfigurationTab.vue` |
| `round_diameter_min/max` | 可封口**圆口直径范围**（mm） | 330: 0–200；390: 200–370；500: 200–410 | 同上；写入口只有机型 CRUD（无导入写入） |
| `rect_length_min/max`、`rect_width_min/max` | 可封口**方盒长宽范围**（mm） | 330: 长 0–221 / 宽 0–168；390: 221–370 / 168–270；500: 280–410 / 280–410 | 同上 |
| `machine_size / net_weight_kg / carton_size / wood_box_size / gross_weight_kg` | 机身尺寸、净重、纸箱/木箱尺寸、毛重 | **3 行全为 NULL**（字段存在但无数据） | 机型表单可填，未被任何展示使用 |
| `sort_order / remark` | 排序与备注 | 1/2/3 | — |
| `variantCount`（DTO 派生，非列） | `MachineTierView.variantCount` | 由 `ProductVariantRepository.countByMachineTierId` 统计**旧规格**条数 | `MachineTierView.from`、机型管理界面 |

其它消费者：`ProductCatalogService:186`（完整度评分的 `variantCount` 来源）、`:252`（详情 `variantCount`）、`ProductAsset.machineTier` + `product_asset_machine_tier_links`（素材适用范围）、视频库检索 `machineTierId`（`ProductCatalogController:242` → `searchVideos:547`）、`DeviceVideoLibraryView.vue`。

能否表达设备配置：✅ **能表达「设备型号 + 可封口尺寸范围」**（这正是 330/390/500 的划分依据），❌ **不能表达模具形状与腔数组合**。
是否适合作权威事实来源：✅ 机型与尺寸适配范围（内部事实）；⚠️ 覆盖度极低——49 个产品里**只有产品 67 有档位数据**，且机身/重量等字段无数据；`variantCount` 这一项仍挂在旧规格上，属于需要在后续批次清理的语义污染。

## 3. 模具域 `packaging_molds` / `packaging_mold_groups` / `mold_variant_links`

### 3.1 `packaging_molds`（77 行，全部有 `group_id`）

| 字段 | 当前含义 | 真实数据 |
|---|---|---|
| `mold_code` | 模具编码（含腔数/类型后缀，如 `M20708-8F`、`M20909-4F`） | 77 行 |
| `cavity_count` | **穴数（一出几）** | 分布：1×3、2×5、**3×22**、4×2、**6×16**、**8×9**、9×2、10×4、17×3、18×1、21×2、24×6、27×1、65×1 |
| `mold_type_code` | 类型码 | F 59、B 9、W 4、FB/FW/FG/G/WJ 各 1 |
| `mold_category` | 模具类别/纹路（如「圆筋1」「光面」） | 有值 |
| `mold_role` / `base_mold_code` / `compartment_count` / `surface_structure` / `bottom_engraving` | 角色、基模、格数、表面、底刻 | 部分有值 |
| `raw_caliber / raw_height` | 原始口径/高度文本 | 部分有值 |
| `status` | 在用/停用等 | 有值（`in_use` 等） |
| `website_enabled` | 官网可见 | **77 行全为 0**（模具目前对官网不可见） |
| `website_sort_order` | 官网排序 | 有列 |

### 3.2 `packaging_mold_groups`（48 行）

| 字段 | 含义 | 样例 |
|---|---|---|
| `name` / `normalized_key` / `shape_type` / `length_mm` / `width_mm` / `diameter_mm` | 盒型分组（**形状 + 尺寸**） | `1414方盒` / `RECT:141.000x141.000` / square；`打包桶` / `ROUND:178.700` / round；`牛油杯` / `ROUND:70.500` / round |
| `model_id` | 所属包材系列 | 有值 |

### 3.3 `mold_variant_links`（65 行，有效 64）

| 项 | 事实 |
|---|---|
| 实际含义 | **模具 ↔ 包材规格（盒型尺寸）的生产适配关系**，不是「模具 ↔ 机器」 |
| `relation_type` | 有效行**全部是 `main`**（无 COMPATIBLE 等其它取值） |
| `is_default` | **全部 0**（数据层从未选出推荐模具） |
| 规模 | 41 个模具 ↔ 46 个包材规格，64 条有效关联 |
| 形状实证（模具类型码 ↔ 关联包材的形状） | F → rectangle 49 + square 9 + irregular 3 + 长方形 1；FG → round 1；FW → round 1。**W/B/G/WJ 四类模具当前没有任何关联数据** |
| 消费者（后端） | `PackagingMoldService`（`addVariantLink`/`updateVariantLink`/删除/列表，`linkRepo` 多处）、`PackagingMoldImportService.matchVariants`（三级容差评分，导入时匹配）、`ArchiveScheduler`（归档/清理） |
| 消费者（前端） | `api/packagingMold/index.ts`（`createMoldVariantLink`/`updateMoldVariantLink`/`deleteMoldVariantLink`、`fetchMoldsByVariant`、`fetchVariantMoldSummaryByModel`）、`components/packaging/PackagingMoldsMasterDetail.vue`、`components/mold/MoldFormDialog.vue`、`composables/mold/*`、`utils/…/shapeMatch.ts`（与后端同口径的 TS 镜像） |

能否表达设备配置：⚠️ **能表达「哪个模具做哪个盒型」**（包材生产侧），❌ **不能表达「某台机器可选哪种模具」**——库里没有任何「机型 × 模具」关系。
是否适合作权威事实来源：✅ 模具主数据与模具↔包材适配（内部事实）；`cavity_count` 是「一出几」的现成权威字段；⚠️ 形状语义在模具侧证据不完整（只有 F/FG/FW 有关联可验证），反而在包材侧更完整。

### 3.4 包材形状 `packaging_variants.shape_type`（478 行分布）

`rectangle 114`、`round 59`、`oval 30`、`square 22`、`irregular 7`、`长方形 8`、`圆形 8`、`NULL 39`。

→ **方/圆形状在包材规格上是显式字段**，这与旧规格 `spec_key` 里的「方模具 / 圆模具」语义对应：**封口模具形状由被封口的盒型决定**，因此形状的事实来源在包材侧（或模具分组的 `shape_type`/`normalized_key`），而不是机器侧。

## 4. 旧规格 `ProductVariant`（4 行，全部属于产品 67）

| id | variant_name | spec_key | machine_tier_id | baseline |
|---|---|---|---|---|
| 886 | 利强手压半自动卷膜封口机（斜头） / 标准版 | NULL | 1（330机型） | 1 |
| 889 | 方模具，一出一 | `一出一|方模具` | 1 | 0 |
| 890 | 方模具，一出二 | `一出二|方模具` | 1 | 0 |
| 892 | 圆模具，一出一 | `一出一|圆模具` | 1 | 0 |

- `mold_size_type / mold_length_mm / mold_width_mm / mold_diameter_mm / mold_size_label`：**4 行全为 NULL**。旧规格**没有承载任何尺寸数据**，只有名称与 `spec_key` 文字标签。
- 全部指向同一个机型档位（330），说明 889/890/892 是**同一机型下的三种模具组合**，不是三个机型——实证支持「机型档位 ≠ 可选配置」。

读写消费者（剩余）：

| 消费者 | 作用 | 性质 |
|---|---|---|
| `CatalogImportService` | 全量重建导入时写入旧规格（唯一正常写入口） | 写 |
| `CatalogTrashService` | 回收站：`listTrashVariants` / `restoreVariant` / 批量恢复 / 永久删除 / `emptyTrashVariants` / 过期清理 | 写 |
| `ProductCatalogService` | 软删级联（`:301`）、素材归属（`:914` `assignAssetToVariant`）、移动素材时目标规格校验（`:391-395`）、`addAsset:1174` | 写/读 |
| `ProductAsset.variant` | 素材的旧规格外键（26 条） | 读/写 |
| `ProductVariantRepository.countByMachineTierId` → `MachineTierView.variantCount` | 机型界面显示的「配置数」 | 读（语义污染点） |
| `CatalogStatsExportService` | 导出 | 读 |
| `ProductVariantFileAssetAdapter` | 文件子系统按「规格」定位素材 | 读 |
| `ArchiveScheduler:72` | `product_variants` → `product_variants_archive` | 写 |
| `ProductVariantTagPolicy` | 标签族策略（命名噪音，与表无关） | 读（策略） |

能否表达设备配置：⚠️ 只有**文字标签**（方/圆 × 一出几），无尺寸、无腔数、无型号；且这三类信息在模具域已有更权威的字段（`cavity_count`、包材 `shape_type`）。
是否适合作权威事实来源：**否**（这正是本次要退役的原因）。

## 5. 素材 `ProductAsset`（`product_assets` + `product_asset_machine_tier_links`）

| 项 | 事实 |
|---|---|
| 归属机制 | **三套并存**：`model_id`（产品级，全部 475 行）、`variant_id`（旧规格级，26 行）、机型级（`machine_tier_id` 单值列 26 行 **＋** `product_asset_machine_tier_links` 多对多 26 行） |
| 26 条旧规格素材 | 889:7、890:8、892:11；类别 detail 17 / main 5 / scene 3 / sku 1；`website_visible` 全 0 |
| 机型归属 | `product_asset_machine_tier_links` 26 行**全部指向机型档位 1（330）**，创建时间统一为 `2026-08-21 11:46:42`（由 `V124:13-14` 从 `product_assets.machine_tier_id` 回填，非人工操作） |
| 默认图 | 产品 67 有 **2 个默认图**：asset **4698**（`variant_id=889`，`detail_image`）与 asset **5221**（`variant_id` 为空，`main_image`，产品级）。`defaultImageOwnerType` 按素材 `variant_id` 是否为空派生 |
| 其它官网字段 | `website_visible`、`website_sort_order` | 
| 消费者 | `ProductCatalogService`（`applyMachineTierAction:882` 维护机型集合、`AssetView.machineTierIds/Labels`）、`CatalogAssetGallery.vue`、`AssetTagEditor.vue`、`useCatalogAssetOperations.ts`、`useCatalogEditors.ts`、`ProductModelFileAssetAdapter` / `ProductVariantFileAssetAdapter` |

**关键结论（对批次 2-B 直接有用）**：26 条素材**已经拥有机型档位归属**（多对多链接表 + 单值列并存，均指向 330 机型）。因此批次 2-B 的实质工作不是「找一个新归属」，而是：① 裁定默认图 4698；② 决定是否在 4698 处理完后清理 `variant_id`；③ 确认两套机型归属机制（列 vs 链接表）以哪个为准。

## 6. 官网侧：`website_products` / `website_product_variants`（表已建，0 行）

| 表 | 关键字段 | 现状 |
|---|---|---|
| `website_products` | `name/name_en/public_model/slug/summary/description_html`、`application_tags/container_tags/process_tags/capacity_level`（JSON）、**`technical_parameters`（JSON）**、`card_media_id/hero_media_id`、`featured/status/published_at` | 0 行；管理端最小链路（`WebsiteProductService` + `/api/website/admin/products`）已在另一工作线落地 |
| `website_product_variants` | `product_id/name/public_sku/description/dimensions/`**`technical_parameters`（JSON）**`/sort_order/status` | 0 行 |
| `website_media_assets`、`website_product_categories`、`website_product_media` | — | 0 行 |
| 现有官网页面 | `corporate-website/src/views/ProductDetail.vue`：「技术参数」读 `m.voltage` 等**接口并不输出**的字段 → 恒空；「可选规格」读 `detail.variants`（后端恒返回 `List.of()`）→ 恒空；「兼容包材/模具」已随批次 1 冻结为空数组 | 三个区块今天都不渲染 |

→ 官网「基础参数」与「可选配置」的正式载体**已经建好且空着**，这正是方案 A 要求的独立内容模型；本轮不需要再设计第三套。

---

## 7. 盘点结论速览（供第二份文档引用）

1. 机型档位与可选配置**是两件事**，数据层面有实证：3 个档位按封口尺寸范围划分，4 条旧规格全部落在同一个档位下，是同一机型的三种模具组合。
2. **「一出几」= 模具穴数**（`packaging_molds.cavity_count`，数据 1–65，与 `mold_code` 后缀一致）→ 模具属性，不需要新字段。
3. **「方模 / 圆模」由被封口的盒型决定**，事实在包材侧（`packaging_variants.shape_type`、`packaging_mold_groups.shape_type` + `normalized_key`）；模具侧 `mold_type_code` 只覆盖了 64 条关联中的 64 条（F/FG/FW），W/B/G/WJ 无关联可验证，证据不足。
4. 旧规格只存了**文字标签**，尺寸类字段全空；其承载的语义在模具域与包材域已有更权威来源。
5. 26 条素材**已有机型档位归属**（`product_asset_machine_tier_links` 26 行，全部 330 机型，由 V124 回填）。
6. 官网侧：`website_products`（含 `technical_parameters` JSON）与 `website_product_variants` 已建且为 0 行，足以承接「基础参数 / 可选配置」的展示内容；官网三个相关区块今天都不渲染，改造不会造成可见回退。
