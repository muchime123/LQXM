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

2026-10-27 后续补充：官网分类草稿通过 `GET/POST /api/website/admin/product-categories` 独立维护，仅创建顶层分类；产品草稿可选择 `website_product_categories.id`，后端拒绝不存在或已软删除的分类。后台 `/website/products` 已提供分类创建与选择。分类和产品均未发布，公开官网接口仍走原有读取链路。针对性测试覆盖普通用户 403、`website.manage` 创建草稿、slug 冲突与分类存在性。本次没有新增迁移或写入真实业务数据。

产品草稿现可选择独立官网媒体作为详情主图和列表卡片图，分别保存到已存在的 `hero_media_id`、`card_media_id`；后端只接受未删除、未归档的 `IMAGE` 记录。媒体选择器由后台分页接口按图片类型和状态过滤，分页总数与列表一致。图片与产品均保持草稿状态，公开产品接口和旧产品图片 URL 未切换。本次没有新增数据库迁移或真实数据写入。

独立官网规格草稿现使用现有 `website_product_variants` 表。`GET/POST /api/website/admin/products/{productId}/variants` 与 `PUT /api/website/admin/products/{productId}/variants/{id}` 受 `website.manage` 保护；创建、编辑仅允许草稿产品，编辑还必须匹配同一官网产品下未软删除的草稿规格。后台产品行可打开规格弹窗，维护公开名称、SKU 标识、展示尺寸、说明和排序。不复制内部 `ProductVariant`，不接受内部规格 ID。技术参数 JSON、规格媒体、审核发布和公开接口切换尚未实施。

现有产品公开快照中真正用于详情图库或列表卡片的 142 条素材引用，对应 136 份不同图片。已用 `tools/import_website_product_display_media.py` 逐件校验并复制到官网专用目录，登记 136 条 `DRAFT` 媒体；重复导入新增 0 条。映射与校验结果见 `website-product-display-media-{preflight,import,rerun}-20261027.json`。尚未把这些媒体关联到新官网产品，也未发布或切换公开 API；未选中展示的旧素材继续保留，后续按资源类型和审核结果处理。

## 2026-10-28 官网公司介绍独立草稿进展

新增 `V423__create_website_company_profile.sql`，建立单条官网公司介绍草稿表 `website_company_profiles`。字段覆盖当前公开 `PublicCompanyView` 的公司名称、品牌、定位、业务、制造能力、成立日期和对外联系方式，并通过 `logo_media_id` 关联官网媒体资产；表内没有内部公司档案外键。迁移只建表，不复制、不发布现有公司信息。

受 `website.manage` 保护的 `/api/website/admin/company-profile` 提供草稿读取和保存；后台「官网公司介绍草稿」页面可独立维护内容和 Logo。保存要求公开公司名称，检查字段长度、官网 Logo 类型及公开网址的 HTTP/HTTPS 协议，已发布记录不能通过草稿接口覆盖，并记录审计。当前未建立官网公司介绍公开查询或数据源切换；`/api/website/company-detail` 和首页公司信息仍读取原档案，以待内容复制、媒体核验和页面对拍。后端定向测试 4 项通过。全量前端类型检查当前因并行中的生产跟单抽屉重复 `load` 定义报错；本轮新页面 ESLint 无错误。V423 尚未执行，数据库数据未改变。

后续增加只读 `GET /api/website/admin/company-profile/readiness` 和页面「发布前检查」。检查独立草稿是否存在、公开名称、主营业务、至少一个联系方式和地址，以及 Logo 是否为已发布的官网图片且真实文件位于官网目录；英文名称、品牌名称和成立日期列为待审阅项。接口仍要求 `website.manage`，检查结果只供人工审核，不执行发布或数据源切换。定向后端测试覆盖缺失草稿、Logo 状态与路径、正常官网文件及 HTTP 403/200 权限行为；前端类型检查已通过。V423 仍未执行。
