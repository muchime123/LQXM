# 产品中心 P1-B 去留与迁移设计

范围：设计判定，不执行删除或迁移。基线 `851be4e`；实际数据库 `product_archive`，只读事务核对。业务使用频率未知，空表不等于接口无调用。

## 可先下线的操作入口

| 层级 | 对象 | 证据 | 实施前检查 |
|---|---|---|---|
| 页面 | `ProductMachineConfigurationTab.vue` 内包材适配矩阵 | 三套产品包材适配表均 0 行；页面仍调用矩阵读写 API | 保留 `MachineTierManager` 和机型参数展示；确认其他页面没有矩阵入口 |
| 前端 API | `api/catalog/machineTiers.ts` 的 `fetchProductPackagingCompatibility` / `replaceProductPackagingCompatibility` | 当前仅配置组件调用 | 删除调用方后才删函数 |
| REST | `ProductCatalogController` 的 `GET/PUT /models/{id}/packaging-compatibility` | 三套关联表均空 | 检查外部客户端和官网接口；保留机型 CRUD |
| Service/Repository | `ProductPackagingCompatibilityService`、`ProductModelPackagingLinkRepository`、`ProductMachineTierPackagingLinkRepository` | 服务仅由上述 REST 使用，关联表 0 行 | 全树重新搜索后可移除运行时代码；表结构暂保留 |
| 旧规格适配 | `ProductVariantPackagingLinkRepository` 与旧适配入口 | `product_variant_packaging_links` 0 行 | 区分旧规格本体；不可连带删除 `ProductVariantRepository` |

## 旧规格迁移设计

4 条有效规格均属产品 `67`、机型 `1`。规格 `886` 无有效素材；`889` 关联 7 条、`890` 关联 8 条、`892` 关联 11 条。26 条素材的 `machine_tier_id` 已全部是 `1`。因此可以**保留现有产品与机型绑定、移除旧规格绑定**，但必须先逐张确认旧规格表达的方模/圆模及一出一/一出二差异应落在素材标签、说明文字或独立模具资料中的哪一处；不能仅把 `variant_id` 置空而丢失语义。

素材 ID：`889 → 4697,4698,4699,4700,4701,4706,4708`；`890 → 4713,4714,4715,4716,4717,4722,4723,5249`；`892 → 4702,4703,4704,4705,4707,4720,4724,4725,4728,4729,4730`。先保存逐条映射、文件与默认图状态快照，再小批量迁移并比对详情、官网和下载链接；完成后才考虑旧规格接口、导入链、文件适配器及表退役。

旧规格的现存调用还包括 `ProductCatalogService` 素材移动/批量归属、`CatalogImportService` 创建规格、`CatalogTrashService` 回收站、`ProductVariantFileAssetAdapter`、`ProductVariantRepository`、`ProductAssetRepository`、素材 DTO。`product_documents` 当前 0 行，但其外键仍指向旧规格；清理需另行设计迁移。

## 23 条包材素材异常

复现口径：`packaging_assets.deleted_at IS NULL` 且所指 `packaging_variants.deleted_at IS NOT NULL`，共 23 条；均为 `pkg_main_image`，素材系列与规格系列 ID 一致。素材 ID → 规格 ID：`96,127,136→396`；`100→397`；`141→408`；`144→409`；`142→410`；`143→411`；`280→547`；`281→548`；`282→549`；`283→550`；`284→551`；`285→552`；`287→554`；`288→555`；`290→557`；`291→558`；`293→559`；`265→568`；`295→581`；`301,302→708`。

每条需由业务确认：规格仍销售则恢复规格；图片属于其他有效规格则迁移素材；仅用于历史查询则归档并保持文件可追溯。不得统一置空、批量恢复或删除文件。应结合文件内容、规格名称、官网可见性、默认图标记核对后形成逐条决策表。

复现 SQL（只读事务内运行）：

```sql
SELECT a.id AS asset_id, a.model_id, a.variant_id, a.category,
       a.default_asset, a.deleted_at AS asset_deleted_at,
       v.model_id AS variant_model_id, v.deleted_at AS variant_deleted_at
FROM packaging_assets a
JOIN packaging_variants v ON v.id = a.variant_id
WHERE a.deleted_at IS NULL AND v.deleted_at IS NOT NULL
ORDER BY v.id, a.id;
```

## 保留与边界

保留 `ProductModel`、`ProductMachineTier`、包材系列与 SKU、模具及其 64 条有效规格关联、产品/包材素材和设备视频检索。知识产权及物品资产只调整导航归属，路由与权限保持。实际数据库中 `sales_products.product_model_id` 非空为 0，`finished_goods_lots` 为 0；这不排除普通 ID、JSON、文本和外部系统引用。官网产品、包材、模具公开接口及视频库要在实施前做响应对照。

推荐实施顺序：先隐藏空适配矩阵并移除其 API 调用，验证产品和机型读取；再逐条确认并迁移 26 条旧规格素材；单独处理 23 条异常包材素材；最后提案退役旧代码与表。每一步均单独验收，数据库迁移另行批准。
