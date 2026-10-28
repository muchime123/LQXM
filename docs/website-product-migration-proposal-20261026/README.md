# 官网产品迁移映射审阅稿

由 `tools/map_website_product_snapshot.py` 从 2026-10-26 公开接口快照离线生成。`product_proposals.json`、`media_links.json` 和 `media_audit_input.tsv` 中的 ID 均为旧档案 ID，只用于一次性核对；它们不是新官网表的外键或待直接执行的导入 SQL。`PUBLISHED_CANDIDATE` 表示当前公开可见，仍须核对媒体与文案后才能写入官网发布状态。

本次得到 45 条产品候选（43 条精选）、438 条详情图库引用，另有产品 67 的列表主图 5221 单独引用，共 439 个不同旧素材 ID。公开详情中 `variants` 均为空，当前列表显示的 `variantCount` 是机型档位数量，不得自动生成 0 条以外的官网规格。产品分类、容器标签、工艺标签和产能等级需在新官网表中保留筛选口径。

`issues.json` 有 11 项待核对：产品 67 的详情无图库，当前页面使用静态回退图，但列表主图旧素材 5221 不在详情图库；该文件现已找到并复制到隔离目录，不能因找到文件就自动改变详情页显示。另有 9 条 `drawing`、`manual` 或 `brochure` 类资源虽在公开详情响应中，却不被当前图库组件显示。素材 5212 是 PSD，现有缩略图接口返回 415。此类资源不得自动作为公开图片发布，需分别确认文件保留、可下载性和是否制作预览图。

2026-10-27 对 `storage/lq-files` 执行只读文件核验，结果保存于 `media_audit.jsonl`：439 条均有唯一文件，源文件总大小 244,857,102 字节，内容哈希去重后为 394 份。`website_media_manifest.json` 将它们列为 394 条官网媒体草稿候选及 439 条旧引用映射；按当前页面规则，141 条详情图库引用和 45 条列表卡片引用会被实际选中显示，其他 297 条仅保留草稿候选。这个区分反映当前页面行为，不代表可以删除未选中的文件。

已将 394 份不同内容复制到工作区的 `storage/website-migration-stage/website/` 隔离目录，物理大小 189,475,731 字节。最终 439 次复制请求中，新增产品 67 的列表主图 1 次，其余 438 次复用前轮已复制的同哈希文件；所有目标文件重新计算 SHA-256 均与清单一致。逐件状态保存在 `media_copy_stage.jsonl`。该目录不在当前 `app.files.root` 下，未登记到数据库，官网不会读取。正式复制到官网媒体目录与发布仍须在内容审阅和隔离环境对拍后执行。

后续发布前仍需逐项审核问题清单、确认分类 slug 与 SEO 文案，并在隔离环境逐页对拍。旧档案 ID 只保存在迁移报告中，新官网表不保存指向旧档案的外键或来源 ID。

官网媒体后端已增加独立的 `WebsiteMediaAsset` 实体、Repository，以及受 `website.manage` 保护的管理端列表、详情、上传和文件读取接口（`/api/website/admin/media`）。上传只接受经文件头识别的 JPEG、PNG、GIF、WebP、PDF、MP4、WebM，限制 512 MB，按内容哈希存入官网目录并登记为草稿；相同内容重复上传复用已有记录。文件读取与上传均限制在官网专用目录，不接受跨目录路径。尚无迁移清单的批量导入端点；公开页面仍使用原素材接口。

2026-10-27 进展：`tools/import_website_product_display_media.py` 从本清单只选当前详情或列表实际显示的图片，逐件校验文件签名、大小和 SHA-256。已将 136 份独立图片从隔离目录复制到 `storage/lq-files/website/images/`，并登记为 136 条 `DRAFT` 官网媒体。142 条旧引用映射见 `docs/website-product-display-media-import-20261027.json`；重复执行新增文件和记录均为 0，媒体 ID 映射不变。其余未选中展示的媒体仍保留在隔离目录及内部档案，尚未导入。

同日，`tools/import_website_product_drafts.py` 先只读确认官网分类与产品表均为 0 行、136 个图片 ID 均为有效草稿，然后在一个事务中导入 9 条分类及 45 条产品，状态一律为 `DRAFT`，`published_at` 全部为空。分类 slug 是待编辑审阅的建议值。产品 67 缺详情主图，保留空值并列入人工核对；45 条公开候选并未直接发布。中文长描述按纯文本转义后存为草稿 HTML，避免把旧内容中的标记当作可执行 HTML。当前公开 API 未切换，内部产品与图片记录未改动。只读复核再次确认 9 条分类、45 条产品、136 个媒体映射均在预期草稿状态。预检、导入和核验明细分别见 `docs/website-product-draft-{preflight,import,verification}-20261027.json`。

`tools/import_website_product_gallery.py` 随后将当前公开详情实际选中的 141 条图片引用写入 `website_product_media`，关联 44 个官网产品草稿，保持快照中的逐产品展示顺序。同一产品内有 2 组不同旧素材映射到相同内容文件；关联按原展示次数保留，避免切换时少图。目标表原本为空，写入前与写入后分别完成只读预检和逐项映射核验。对应报告为 `docs/website-product-gallery-{preflight,import,verification}-20261027.json`。这些关联随产品保持草稿，不会由现有公开 API 返回。

下一步是编辑审核分类、文案、SEO、图片角色及 11 项素材问题，再实现独立官网产品公开读取与逐页对拍。当前官网仍读取旧档案，不能把草稿导入视为完成解耦。

后台官网产品列表现提供「审阅」入口，通过 `GET /api/website/admin/products/{id}/review`（`website.manage`）读取独立官网表中的分类、文案、标签、SEO、图库顺序和媒体状态。图片经受权的官网媒体读取接口预览；草稿详情文字以纯文本呈现，避免审核页执行未批准的 HTML。此入口只供内容对拍，不参与公开官网读取。发布与公开接口切换仍需单独验收。

2026-10-28 使用 `tools/audit_website_product_parity.py` 进行只读对拍，报告见 `docs/website-product-draft-parity-20261028.json`。45 条官网草稿的名称、分类、摘要、描述、筛选标签、排序、精选标记、主图和卡片图与冻结快照一致；141 条图库关联顺序也一致，没有额外产品或图库记录。产品 67 的详情主图仍为空，45 条草稿的独立 SEO 标题与描述仍待编辑审阅。旧官网详情原本以产品名称、型号和介绍动态生成 SEO，因此这里的空值是待内容决策项，并不表示旧页面没有 SEO。

官网媒体公开读取现同时允许“有效 Banner 引用”或“已发布官网产品引用”。产品主图、卡片图和产品图库均可作为引用来源；关联到规格的图片还要求该官网规格已发布。媒体本身必须为已发布图片，草稿媒体仍返回 404。当前 45 条产品和相关图片均为草稿，这项权限规则不会提前公开它们。

独立产品读取服务 `WebsiteNativeProductQueryService` 已实现已发布产品的分页筛选、首页精选、分类和详情查询，所有 SQL 仅使用 `website_*` 表。分类若尚未发布，其下产品不会进入读取结果；图片只输出已发布媒体 ID；图库与规格各自只取已发布记录。受 `website.manage` 保护的 `/api/website/admin/native-products` 提供切换前只读对拍入口。它目前按发布规则返回空列表，因为 45 条候选仍是草稿。公开路径 `/api/website/products*` 尚未切换。只读 MySQL SQL 预检报告为 `docs/website-native-product-query-smoke-20261028.json`：当前 45 条草稿，筛选查询 0 条公开结果，草稿详情和关联表查询语法通过。

官网前端的产品卡片与详情页已兼容独立产品读取 DTO：识别独立 slug、分类、公开型号、SEO 和官网媒体 ID，详情图库从 `/api/website/media/{id}/content` 加载，富文本介绍在渲染前清理。旧公开 DTO 的内部素材地址及字段回退仍可正常构建。此项只为切换准备；公开 API 尚未切换，也没有将草稿发布。`corporate-website` 生产构建通过。

后台产品「审阅」现在会调用只读的 `GET /api/website/admin/products/{id}/publication-readiness`，列出未发布分类、未发布或缺失的官网图片、空图库及 SEO 待审阅项。检查验证媒体文件确实位于官网目录；接口受 `website.manage` 保护，不修改内容状态，也不是发布按钮。当前草稿分类和媒体仍未发布，检查结果应显示阻碍项。前端 TypeScript 检查通过；后端定向测试暂被同工作区生产跟单模块的编译错误阻断，不能视为已通过。

2026-10-28 后续：生产跟单两处测试构造参数已按现行 Service 构造函数补齐，官网发布前检查的服务与 HTTP 权限测试现已通过。管理端另增加明确的媒体、分类、产品发布命令（`/api/website/admin/publication/{media|categories|products}/{id}`），均要求 `website.manage` 并记录审计。媒体发布先验证专用目录里的真实文件；产品发布必须通过上述只读检查。已发布状态重复请求直接返回原记录。此轮只建设接口，没有调用发布命令；当前 45 条产品及相应媒体、分类仍为草稿，公开产品 API 仍使用旧链路。发布命令尚无前端按钮，编辑审核和逐页对拍仍是实际发布前的工作。后端定向测试共 7 项，0 失败、0 错误。

后台现可通过 `GET /api/website/admin/native-products/drafts/{id}` 读取草稿对应的拟公开产品 DTO，包括官网媒体 ID、图库顺序、技术参数和规格；它仅查询 `website_*` 表，受 `website.manage` 保护。官网产品「审阅」弹窗显示该 DTO 的卡片式预览，并通过受权媒体接口加载草稿图片。这是字段与内容预览，尚不是官网页面的逐像素对拍；公开接口与当前网站页面仍未切换。该接口的 HTTP 权限和 SQL 范围测试 3 项通过，前端 TypeScript 检查通过。

切换前检索契约检查发现官网页面一直把分类筛选作为 `equipmentCategory` 发给 API，但公开后端实际接收 `category`。现已让调用层发送 `category`，页面地址栏仍接受旧 `equipmentCategory` 参数，旧书签保持可用。官网产品页面新增三项原生 Node 回归测试，验证分类请求参数、独立媒体 URL 和旧素材 URL 回退。未来独立数据源的分类与筛选项由 `WebsiteNativeProductQueryService.filterOptions()` 从已发布 `website_*` 记录生成，受权管理预检路径为 `/api/website/admin/native-products/filter-options`，不会读取内部档案分类或固定的内部选项。后端定向测试 5 项通过，官网前端测试 3 项通过，生产构建通过；公开产品数据源尚未切换。

公开产品读取现具备显式数据源门闩：`app.website.product-source` 由 `APP_WEBSITE_PRODUCT_SOURCE` 配置，默认 `legacy`。仅在明确设置为 `native` 时，`/api/website/products`、`/products/featured`、`/products/{slug}`、`/product-categories` 以及 `/home` 的产品数量和卡片改读官网专属产品查询服务。首页公司资料与发展历程仍是原数据源，完整官网解耦尚未完成。当前 45 条产品仍为草稿，因此**不得启用 `native`**，否则产品列表会为空。此轮只增加代码路径与配置，未改动实际运行配置，也未发布或切换数据。开关测试、旧详情契约和独立查询测试共 8 项通过，0 失败。

新增只读的全站技术门槛 `GET /api/website/admin/native-products/cutover-readiness`（`website.manage`）：汇总官网产品、分类、媒体的草稿与已发布数量，并逐条复核已发布产品的分类、图片和文件可读性。后台「官网产品草稿」的「切换检查」按钮展示阻碍和提示，不执行发布或开关切换。服务与 HTTP 权限测试 3 项通过。`tools/audit_website_product_cutover.py` 在本机 `product_archive` 用只读事务生成 `docs/website-product-cutover-readiness-20261028.json`：45 条产品草稿、9 条分类草稿、139 条媒体草稿；有效已发布产品、精选和图库关联均为 0，技术门槛不通过。这里的 139 条媒体含先前 Banner 草稿，不能直接等同于产品图片数。该检查不代替人工内容审核和真实页面对拍。

官网产品草稿新增独立的「筛选信息」编辑入口，可维护应用、容器、工艺标签和目标产能。`PUT /api/website/admin/products/{id}/selection` 只更新 `website_products` 的这四列，限定 `website.manage` 和 `DRAFT` 状态，标签做数量、长度、去重校验并记录审计；不写内部产品档案。后端服务与 HTTP 权限测试 3 项通过，前端类型检查与 ESLint 通过。详情富文本和技术参数仍只读展示，需待官网专用媒体上传与编辑控件契约明确后再开放编辑。未执行发布、迁移或数据源切换。

官网产品技术参数现可在独立草稿里编辑：后台从拟公开 DTO 读取现有 JSON，编辑后调用 `PUT /api/website/admin/products/{id}/technical-parameters`，只更新 `website_products.technical_parameters`。接口要求 `website.manage`、产品仍为 `DRAFT`，并限制最多 50 项、20 KB，参数值为文字、数字或布尔值；复杂嵌套对象不会被自动改写。服务与 HTTP 权限测试共 3 项通过，前端类型检查通过，ESLint 无错误。详情富文本仍待独立编辑；本轮没有执行数据库写入、发布或数据源切换。

官网产品分类草稿现可在后台列表中逐条编辑名称、URL 标识和排序。`PUT /api/website/admin/product-categories/{id}` 限定 `website.manage` 及 `DRAFT` 分类，校验 slug 格式与重复值并记录审计；已发布分类不允许直接修改。服务与 HTTP 权限测试共 3 项通过，前端类型检查通过。分类数据仍未发布。现有通用富文本组件的图片上传会写入内部公司素材库，不能直接用于官网产品详情；需先建立官网专用编辑器的媒体链路和服务端内容清理规则。

产品发布前检查现额外扫描官网产品中英文详情正文：发现内嵌图片、视频、`source` 标签，或 `/api/files/`、`/api/file-assets/`、`product_model/assets/` 等内部文件路径时返回阻碍项，不允许带着内部媒体依赖发布。对应发布前检查与发布服务定向测试通过。当前仍未提供官网正文的内嵌媒体编辑能力；图片应先使用独立官网媒体库登记和产品图库关联。此项只改代码及测试，没有修改现存正文、媒体或发布状态。

官网产品草稿增加中英文详情正文的纯文本编辑入口。后台读取官网独立草稿中的现有正文，只允许段落和换行排版进入此编辑器；遇到图片、链接、标题、列表等其他标记时拒绝打开，以免改写时丢失内容。`PUT /api/website/admin/products/{id}/description` 要求 `website.manage` 且产品仍为 `DRAFT`，将提交文本转义后写入 `website_products.description_html` 与 `description_html_en`，支持清空。接口不接收 HTML 或媒体 URL。服务与 HTTP 权限测试 3 项通过，前端类型检查通过。本轮未调用写接口、发布产品或切换公开数据源。

官网产品草稿现在可维护产品级图库：后台从官网媒体库选图，新增 `website_product_media` 关联，或修改已有图片的角色、替代文本与显示顺序。管理接口 `/api/website/admin/products/{productId}/gallery` 受 `website.manage` 保护，要求产品仍为草稿，新增时仅接受有效官网图片；更新时用产品 ID、关联 ID 和媒体 ID 同时定位，避免改到其他产品或规格图库。此轮没有提供删除入口，已迁入的 141 条图库关系仍保留。服务与 HTTP 权限测试 3 项通过，前端类型检查通过，ESLint 无错误；没有调用写接口、发布图片或切换公开数据源。

后台官网产品「审阅」增加显式发布操作：显示主图、卡片图与图库中去重后的官网媒体状态，草稿图片须逐张确认；分类草稿在分类列表中单独确认发布；产品按钮仅在发布前检查通过时可用，并在提交前再次向服务端检查。所有操作由现有 `website.manage` 保护的发布接口执行，需确认弹窗，成功后刷新状态。此轮只建设操作界面，未调用发布接口；45 条产品、9 条分类及相关媒体的实际发布仍须人工内容审核，公开数据源配置仍保持 `legacy`。后端发布、权限和发布前检查定向测试通过；前端类型检查及 ESLint（0 错误）通过。
