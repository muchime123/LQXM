# 官网数据解耦方案 A：实施基线与第一工作包

日期：2026-10-26。范围：设计冻结与只读基线；不执行迁移、文件复制或公开接口切换。

## 已执行版本与当前数据

Flyway V409 `drop_certificate_website_publishing_fields` 已在 `product_archive` 成功执行。方案原拟 V409–V413 全部顺延，实施前重新检查最新最大整数版本。当前工作区已有另一项未提交的 V410 迁移，因此官网媒体建表草案使用 V411；合并前仍须复核版本占用。V409 已移除内部资质/知识产权的官网发布字段，不能据此重建原发布选择，需从当前页面、备份或编辑记录核对。

只读事务统计：`website_banners` 7、`website_page_sections` 21、`website_solutions` 9、`website_inquiries` 3；内部产品当前 `website_enabled=1` 且未软删 46；包材与模具同口径均为 0。后两项不能替代公开接口实际响应数量，应在复制前保存接口快照。现有独立官网表继续保留，询盘不迁移。

## 数据所有权和字段边界

新官网表只存对外内容，不建立指向 `product_models`、`product_assets`、`packaging_*`、`company_*`、`certificates` 等内部表的外键。来源 ID 仅可记录在一次性迁移映射表中，不参与公开查询。内部档案更新与删除不触发官网写入；将来复制档案必须由 `website.manage` 用户显式发起并审核。

建议新增：`website_media_assets`；`website_product_categories`、`website_products`、`website_product_variants`、`website_product_media`；`website_packaging_items`、`website_packaging_variants`、`website_molds`；`website_company_profiles`、`website_company_milestones`、`website_social_links`、`website_credentials`、`website_ip_items`。官网适配关联表先不创建：现有三套内部适配表均为 0 行，需先确认新官网是否保留适配展示；若保留，关系只能指向官网记录。

产品表含独立 slug、中文/英文展示名、摘要、正文、公开分类、SEO、发布状态/时间、排序、精选和主图媒体 ID。规格表含明确可筛选字段及受控 JSON 技术参数；不复制内部备注。媒体表含官网专用 `storage_key`、SHA-256、尺寸/时长、alt 文本、状态和审计字段。公开媒体只允许已发布内容引用；草稿预览走授权接口。

## 迁移与切换顺序

1. 只读导出当前公开 API 响应、SEO、图片 URL 与文件存在性；保留快照和内容映射。
2. 新增表与官网专用媒体目录；迁移工具先在隔离环境复制真实文件并逐件核验 SHA-256，缺失文件单列异常。
3. 按公开字段白名单复制当前内容为官网草稿或已发布记录；保留 Banner、区块、方案和询盘原记录，逐步将图片 URL 转为官网媒体 ID。
4. 增设独立 `/api/website/admin/*` 管理控制器，以 `website.manage` 保护；公开 `/api/website/*` 路径不变，但切换为官网 Service。`@PublicApi` 的 `WebsiteController` 不承载管理写接口。
5. 对拍全部页面和媒体后切换数据源。开放新后台编辑前可短期回退旧读取；开放编辑后以新内容为准，问题采取前向修复。

官网产品图片仍由内部 `product_model` 文件资产 URL 构造，必须在产品切换前替换。官网知识库三个公开端点仍读取内部知识库，应核对页面/外部调用后迁移或正式下线。官网工作台应改为只统计官网表；询盘管理读接口需统一权限审查。

## 下一实施门槛

先取得当前公开接口与实际文件的可重复快照，明确 46 个已发布产品、7 个 Banner、21 个区块、9 个方案的媒体与 SEO 状态；核对 V409 后资质/知识产权页面是否仍有应保留内容。完成快照审阅后，再提交 V410 起的具体建表迁移，不修改历史脚本、不删旧字段。

## 2026-10-27 Banner 媒体引用实施进度

V417 已在本地 `product_archive` 执行成功，为 `website_banners` 增加可空的 `website_media_id` 外键，原 `image_url` 和 `image_asset_id` 不删不改。已有 Banner 在未选择新图片时继续返回原图片 URL；后台明确选择官网媒体并保存后，公开响应才改为官网媒体内容路径。公开文件接口只允许读取已发布、未删除且被当前生效 Banner 引用的官网图片；草稿与失效 Banner 的图片不公开。后台 Banner 表单上传与选择走官网媒体库，不再从公司资产接口登记新图片。

Banner 3/4/5 的图片已复制到官网专用目录并登记为草稿。当前公开 Banner 4/5 仍使用旧 URL；`tools/cutover_website_banners.py` 默认只读预检，`--apply` 只有在公开 API、数据库状态和文件哈希全部通过后才以单事务发布两张媒体并关联 Banner，保留旧 URL。2026-10-27 的只读试运行见 `website-banner-cutover-dry-run-20261027.json`：数据库及文件通过，但 `127.0.0.1:8081` 未监听，未执行切换。

## 官网产品草稿管理最小链路

新增 `WebsiteProduct`、`WebsiteProductRepository`、`WebsiteProductService` 与独立管理控制器。`GET/POST /api/website/admin/products`、`GET/PUT /api/website/admin/products/{id}` 统一要求 `website.manage`；创建只能形成 `DRAFT`，编辑仅允许草稿，slug 有格式及唯一性检查，操作写入审计日志。该链路只访问 `website_products`，不接受内部产品 ID，也不隐式复制内部档案。公开 `/api/website/products` 暂不切换，以便先完成迁移映射、媒体复制、后台内容审核和新旧响应对拍。分类、规格、产品媒体及发布工作流仍是后续工作，本次没有写入现有产品数据。
