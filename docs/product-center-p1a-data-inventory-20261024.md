# LQXM 产品中心 P1-A 数据盘点报告

2026-10-24；数据源：本机 MySQL 9.7.1，`product_archive`。使用已获授权的本机账号，在 `START TRANSACTION READ ONLY` 内执行 SELECT；未执行迁移或数据修改。服务器 `@@read_only=0`，账号本身不是只读账号，因此本报告只证明查询过程使用只读事务。

## 主表实况

| 表 | 总数 | 有效 | 软删除 | 说明 |
|---|---:|---:|---:|---|
| product_models | 48 | 48 | 0 | 46 个官网发布 |
| product_machine_tiers | 3 | 3 | 0 | 与产品主档有外键 |
| product_variants | 4 | 4 | 0 | 3 个规格被有效素材引用 |
| product_assets | 475 | 475 | 0 | 26 条指向旧规格、26 条指向机型；440 条官网可见 |
| packaging_models | 49 | 12 | 37 | 名称唯一 |
| packaging_variants | 478 | 287 | 191 | 规格依赖包材系列 |
| packaging_molds | 77 | 77 | 0 | 模具编号唯一；官网发布 0 |
| product_model_packaging_links | 0 | 0 | 不适用 | 产品级适配表为空 |
| product_machine_tier_packaging_links | 0 | 0 | 不适用 | 机型级适配表为空 |

附属表：`product_asset_machine_tier_links` 26 行；`packaging_assets` 306 行（有效 242、软删除 64）；`mold_variant_links` 65 行（有效 64、软删除 1）；`product_variant_packaging_links` 0 行；`product_documents` 0 行。当前未找到名为 `file_assets` 的表；文件资源由各业务素材表及文件资产适配逻辑引用，不能据此判断物理文件可删。

## 引用与约束

数据库外键确认：`product_assets.variant_id` 与 `product_documents.variant_id` 指向旧规格；`product_variants.machine_tier_id` 指向机型；两套新适配表分别指向产品或机型及包材规格；`mold_variant_links` 指向模具和包材规格。`finished_goods_lots` 对产品、机型、模具有外键，当前 0 行。`sales_products` 对产品主档有外键，总计 12,229 行，但 `product_model_id` 非空为 0。视频收藏、最近浏览、合集项目对产品素材有外键，当前均为 0 行。机型素材关联表有 26 行。应用层仍使用旧规格处理素材分配、导入、文件资产和回收站；官网仍使用产品与包材公开数据。历史备份表和 JSON/文本中的潜在 ID 尚未逐项核实。

当前唯一约束包括产品名称、官网 slug、机型的 `(model_id,tier_code)`、有效旧规格的 `(model_id,active_spec_key)`、两套适配关系各自的对象与包材规格组合、模具编号及模具与包材规格组合。以上来自实际 `information_schema`，不是仅凭迁移脚本推断。

## 数据质量与退役边界

两套新适配表均为空，旧规格包材适配表也为空；本次没有发现这些表中的孤儿或指向软删除对象的关系。旧规格不能直接退役：4 行均有效，26 条有效素材引用旧规格。有效产品素材指向软删除产品或旧规格的记录为 0；有效机型指向软删除产品的记录为 0。有效模具关联的孤儿和软删除目标为 0。

发现 **23 条有效包材素材指向软删除的包材规格**；这需要在设计迁移前逐条核对展示、恢复和文件归属。有效包材规格指向软删除包材系列、有效包材素材指向软删除系列的记录均为 0。尚未完成全域重复文件、业务文本/JSON 引用及实际访问频率检查。

**可作为废弃候选的仅是空关联表对应的旧操作入口或过时界面**，仍须核对接口调用方及官网行为。不能据此删除产品主档、旧规格、机型、包材、模具、素材表或物理文件。进入 P1-B 时，应先制定 26 条旧规格素材的归属映射、核对 23 条包材素材异常，并补充跨模块普通 ID/JSON 引用和业务使用频率证据。
