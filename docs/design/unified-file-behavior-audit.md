# 文件行为统一抽象审计

更新时间：2026-07-16

## 结论

当前项目已经有统一的物理文件存储入口：`FileController` + `FileStorageService` + `FileCategory`。真正没有统一的是“文件绑定到业务对象之后”的行为：上传后的建档、预览、下载、删除、移动、标签、默认文件、排序、回收站等能力分别落在产品素材、包装素材、公司素材、文档、软件模块里。

因此下一步不应强行合并 `product_assets`、`company_assets`、`documents`、`softwares` 等表。更稳妥的方向是保留现有业务表，抽一层统一文件行为契约和适配层，让各表继续保存自己的业务字段，但对前端和通用服务暴露一致的文件操作语义。

## 目标模型

统一文件行为建议围绕一个轻量文件引用模型展开：

```text
file_handle
  ├── category: main_image / sku_image / detail_image / scene_image / parameter_image
  ├── category: manual / drawing / certificate / brochure / device_video / operation_video
  ├── category: document / software / company_asset / patent / other
  ├── owner_type: product_model / product_variant / packaging_model / packaging_variant
  ├── owner_type: company / certificate / document / software
  ├── owner_id
  ├── asset_id: 现有业务表主键，可为空
  ├── file_name
  ├── original_file_name
  ├── mime_type / file_size
  ├── tags
  ├── default_file
  └── sort_order
```

这里的 `file_handle` 先作为 DTO / 服务层模型，不立即落成一张新表。

## 当前现状

| 模块 | 现有表 | 主要 owner | 已有能力 | 主要差异 |
| --- | --- | --- | --- | --- |
| 产品素材 | `product_assets` | `ProductModel` / `ProductVariant` | 上传后建档、预览、删除、移动、标签、默认、排序、修复 | 行为最完整，但和产品目录 service 强绑定 |
| 包装素材 | `packaging_assets` | `PackagingModel` / `PackagingVariant` | 上传后建档、删除、标签、默认、排序 | 与产品素材相似，但接口和 service 独立 |
| 公司素材 | `company_assets` | 公司全局/公司分类 | 上传后建档、预览、标签、默认、分类移动、排序、回收站 | 物理上传走通用接口，业务分类与存储 category 混用 |
| 文档 | `documents` | 文档自身 | 上传、预览、下载、标签、回收站、批量分类 | 文件就是业务主记录，不适合转成普通 asset |
| 软件 | `softwares` | 软件自身 | 导入、下载、打开目录、回收站、批量分类 | 缺少统一标签/默认文件语义，路径来源更复杂 |
| 通用文件 | 文件系统 | category + modelId | 上传、预览、下载、删除、缩略图、打开目录 | 只认识存储位置，不认识业务 owner |

## 行为差异清单

### 上传

现状：前端已有 `useFileUploadWorkflow` 和 `useFileUpload`，后端已有 `/api/files/upload`。但上传结果只是物理文件结果，业务建档仍由各模块自行调用：

- 产品：上传后调用 catalog asset 创建。
- 包装：上传后调用 packaging asset 创建。
- 公司：上传后调用 company asset 创建。
- 文档：直接在 documents multipart 接口内完成上传和建档。
- 软件：以导入/创建软件记录为主，不走统一上传链路。

建议：保留 `/api/files/upload`，新增可选的 `ownerType`、`ownerId`、`assetCategory` 语义，返回统一 `FileHandle`。业务建档仍可由模块适配器完成，避免一次性改动所有模块。

### 预览与下载

现状：通用素材按 `category + fileName + modelId` 找文件；文档、软件按业务 `id` 找文件。前端因此同时存在 `fileUrl(category, fileName, modelId)`、`documents.previewUrl(id)`、`downloadSoftwareUrl(id)` 等入口。

建议：新增统一解析入口：

```text
GET /api/files/preview?ownerType=&ownerId=&category=&fileName=&assetId=
GET /api/files/download?ownerType=&ownerId=&category=&fileName=&assetId=
```

旧接口继续保留。新接口内部通过 owner adapter 转到对应表和文件路径。

### 删除与回收站

现状：

- 产品素材删除支持是否同时删除物理文件。
- 公司素材以软删除/回收站为主。
- 文档和软件是业务记录级删除、恢复、永久删除。
- 通用 `/api/files/{category}` 是物理文件删除。

建议：统一成三种明确动作：

```text
softDelete(fileHandle)
restore(fileHandle)
permanentDelete(fileHandle, deletePhysical)
```

各模块可按自身语义适配。对文档/软件，`fileHandle.assetId` 可以直接等于业务记录 id。

### 移动

现状：产品素材支持在型号/变体之间移动并移动物理文件；公司素材支持业务分类移动；包装支持变体归属；文档/软件更多是分类字段更新。

建议：移动拆成两个概念：

```text
moveOwner(fileHandle, targetOwnerType, targetOwnerId)
moveCategory(fileHandle, targetCategory)
```

这样产品/包装的型号变体移动、公司素材分类移动、文档/软件分类更新可以使用同一套前端语义，但后端由适配器决定是否需要移动物理文件。

### 标签、默认文件、排序

现状：产品、包装、公司都有标签/默认/排序，但接口名称和入参不完全一致；文档有标签但没有默认文件；软件没有完整标签能力。

建议：统一为可选能力：

```text
updateTags(fileHandle, tags, mode)
setDefault(fileHandle)
reorder(ownerType, ownerId, orderedAssetIds)
```

适配器需要声明 capability。前端根据 capability 决定是否显示默认按钮、排序拖拽、标签批量操作。

## 建议的后端结构

先新增服务层抽象，不新建大表：

```text
service/file/
  FileHandle.java
  FileOwnerType.java
  FileBehaviorCapability.java
  FileAssetAdapter.java
  FileAssetBehaviorService.java
  adapters/
    ProductAssetAdapter.java
    PackagingAssetAdapter.java
    CompanyAssetAdapter.java
    DocumentFileAdapter.java
    SoftwareFileAdapter.java
```

`FileStorageService` 继续负责物理文件：保存、路径解析、缩略图、预览资源、物理删除、物理移动。

`FileAssetBehaviorService` 负责业务行为编排：根据 `ownerType` / `assetId` 选择 adapter，统一调用标签、默认、删除、恢复、排序、移动等动作。

## 建议的前端结构

前端不要继续让每个页面手写一套文件流程。建议在现有 `useFileUploadWorkflow` 基础上补一层业务文件行为 composable：

```text
composables/file/
  useFileWorkflow.ts
  useFilePreview.ts
  useFileActions.ts
  fileCapabilities.ts
```

统一组件可逐步承接：

- 上传区：选择、拖拽、粘贴、进度、失败提示。
- 预览：复用 `FilePreviewBody` 和 `AssetPreviewModal`。
- 文件操作菜单：下载、打开目录、标签、设默认、移动、删除、恢复。
- 列表卡片：默认标记、标签、排序、文件存在状态。

## 分阶段落地顺序

1. **整理 category 注册表**
   - 在 `FileCategory` 中补齐统一命名和兼容别名，例如 `company_asset`、`software`、`patent`、`other`。
   - 不改现有目录结构，只做输入名到现有 folder/scope 的映射。

2. **新增 FileHandle DTO**
   - 先只作为接口返回和前端类型。
   - 从产品、包装、公司、文档、软件记录映射出统一字段。

3. **新增 FileAssetAdapter 接口**
   - 第一批接入产品素材和公司素材，因为它们行为最多、收益最大。
   - 包装随后接入。
   - 文档/软件以下载、预览、删除、恢复能力先接入，不强行补默认文件。

4. **新增统一行为接口，保留旧接口**
   - 新增 `/api/file-assets/**` 或 `/api/files/actions/**`。
   - 旧的 `/catalog/assets/**`、`/company/assets/**`、`/documents/**`、`/softwares/**` 继续可用。

5. **前端逐页替换**
   - 先替换通用上传与预览。
   - 再替换标签、默认、删除、移动。
   - 每替一个页面跑一次前后端健康检查，避免跨模块连锁问题。

## 不建议现在做的事

- 不建议现在新增一张大而全的 `files` 表并迁移所有数据。
- 不建议把文档、软件强行拆成“业务表 + 文件表”。
- 不建议把产品素材和公司素材表立即合并。
- 不建议继续围绕默认数据库密码做配置收紧；这个部分等主业务稳定后再维护。

## 下一步可执行任务

优先做低风险改造：

1. 新增 `FileHandle` / `FileOwnerType` / `FileBehaviorCapability` 类型。已完成。
2. 为 `ProductAsset`、`PackagingAsset`、`CompanyAsset`、`Document`、`Software` 写统一只读映射方法。已完成。
3. 新增产品素材 `product_model` / `product_variant` adapter。已完成。
4. 新增公司素材 `company` adapter。已完成。
5. 新增包装素材 `packaging_model` / `packaging_variant` adapter。已完成。
6. 新增文档和软件 `document` / `software` adapter。已完成。
7. 新增统一查询接口：按 owner 列出文件、按 assetId 解析统一文件描述、查询 capability。已完成。
8. 新增统一动作接口：标签、默认、排序、分类移动、归属移动、删除、恢复、永久删除。已完成，具体能力以 ownerType capability 为准。
9. 前端新增统一 file handle 类型、查询 API 和动作 API。已完成。
10. 前端新增统一文件行为 composable：封装列表加载、capability 判断和统一动作调用。已完成。
11. `FileHandle` 补充 `storageCategory` / `storageOwnerId`，前端新增统一预览/下载 URL 工具。已完成。
12. 修正文档/软件回收站恢复和永久删除的查询路径，避免软删除过滤导致统一动作无法找到已删除记录。已完成。
13. 统一文件动作接口补充空参校验和不支持能力的 400 响应，避免能力不匹配时落入 500。已完成。
14. 修复包装编辑弹窗模板结构和损坏占位符，恢复前端 typecheck。已完成。
15. 补齐前端已使用的 `company_team` 文件分类，并按图片类校验，避免团队头像上传失败。已完成。
16. 修复产品/包装详情 DTO 包装，确保 `ModelDetail` 返回 `ModelView` 而不是实体。已完成。
17. 新增统一预览/下载入口 `/api/file-assets/{ownerType}/assets/{assetId}/preview|download`，前端 URL 工具改为统一入口。已完成。
18. 新增证书 `certificate` adapter，以字段级 `FileHandle` 暴露 `specFile` / `certFile`，不拆表、不迁移。已完成。
19. 选择产品素材页或公司素材页作为第一处试点替换。

这样可以先把行为抽象跑通，再决定是否需要更深层的数据结构调整。
## 2026-07-16 落地补充

- 证书页已接入统一文件预览入口：优先使用 `/api/file-assets/certificate/assets/{assetId}/preview`，未命中统一 handle 时回退旧的 `/api/files/certificate`。
- 公司素材页已开始消费统一文件行为：单个素材预览、设默认、移动分类、更新标签、删除、恢复、永久删除优先走 `useFileAssets(ownerType: 'company')`，批量操作暂时保留原接口，降低一次性替换风险。
- 产品页已开始消费统一文件行为：主型号素材预览、设默认、更新标签、只删记录/移入回收站优先走 `useFileAssets(ownerType: 'product_model')`；带 `variantId` 的规格素材预览、更新标签、只删记录/移入回收站走 `product_variant` 统一接口；跨型号/规格归属移动、同时删除物理文件仍保留旧接口。
- 包装详情页已开始消费统一文件行为：包装系列素材和规格素材的预览、设默认、更新标签、删除优先走 `packaging_model` / `packaging_variant` 统一接口；排序、上传和规格移动仍保留现有包装业务接口。
- 软件模块已开始消费统一文件行为：软件下载 URL 和单项删除走 `file-assets/software`；批量导入、批量分类、打开目录仍保留软件业务接口。
- 文档模块已开始消费统一文件行为：文档普通预览、下载 URL 和单项删除走 `file-assets/document`；Office PDF 高保真预览、批量状态/分类/部门操作仍保留文档业务接口。
- 当前仍保留 `product_assets`、`company_assets`、文档、软件等业务表，不做强制合表或数据迁移。

## 2026-07-16 继续收口

- 文档模块的单项恢复、永久删除已改为走 `/api/file-assets/document/assets/{assetId}/restore` 和统一永久删除入口；批量恢复/清空仍保留文档业务接口。
- 软件模块的单项恢复、永久删除已改为走 `/api/file-assets/software/assets/{assetId}/restore` 和统一永久删除入口；批量恢复/清空仍保留软件业务接口。
- 产品回收站素材视图补充 `variantId`，前端可按 `variantId` 判断 `owner_type`，单个素材恢复/永久删除改为走 `product_model` / `product_variant` 统一文件资产接口。
- 公司素材 API 的单项恢复、永久删除 fallback 已改为统一 `file-assets/company`，避免回收站列表未命中当前 handle 时退回旧业务路径。
- 产品素材页补回统一 file handle 加载：主型号素材预览、设默认、更新标签、只删记录优先走 `product_model` 统一接口；规格图同步删除和规格素材只删记录走 `product_variant` 统一接口；需要同时删除物理文件的路径仍保留旧 catalog 业务接口。
- 公司素材 API 的单项删除、标签、默认、分类移动、排序也已改为 `file-assets/company`；批量标签和批量分类仍保留公司业务聚合接口。
- 包装素材 API fallback 支持传入 `packaging_model` / `packaging_variant`，包装详情页模板中的设默认按钮统一走 `makeDefault()`，上传后自动设默认按规格归属选择统一 ownerType。
- 修复 `CompanyInfoController` 的 DTO 返回映射，避免 controller 声明 DTO 但直接返回实体导致后端编译失败。
- 产品素材 composable `useCatalogAssets` 的只删记录、设默认已改为统一文件资产接口；同时删除物理文件仍保留 catalog 业务接口。
- 包装列表页拖拽上传系列主图后的自动设默认显式走 `packaging_model`；包装详情拖拽排序在单一 ownerType 列表内走统一 `reorderFileAssets`，混合 ownerType 时继续回退包装业务排序。
- 产品素材 API wrapper 的单项设默认、更新标签、只删记录已默认走 `file-assets/product_model`，并支持调用方显式传入 `product_variant`；同时删除物理文件、跨产品/规格移动、批量标签和排序仍保留 catalog 业务接口。
- 统一文件行为 service/controller 增加空参校验：`ownerType`、`assetId`、`tags`、`category`、`targetOwnerType`、`targetOwnerId`、`reorder items` 缺失时统一返回 400，避免空请求体或坏排序项落入 NPE/500。
- 前端统一文件 API wrapper 增加标签和排序参数标准化：标签可接收字符串或字符串数组，排序可接收 `sortOrder` 或历史 `order` 字段，减少后续替换旧业务接口时的适配成本。
- `useFileAssets` 修复乱码提示文案，并同步使用统一的标签/排序 payload 类型；产品、公司、证书页的 `FileHandle` 查找逻辑抽到 `findFileAssetHandle`，避免每个页面重复写 assetId/fileName 匹配规则。
- 前端文件工具继续补齐 `fileAssetPreviewUrlById` / `fileAssetDownloadUrlById`，并统一 `product_model` / `product_variant`、`packaging_model` / `packaging_variant` 的 ownerType 判断；产品页、包装详情页、产品素材 composable 已改为复用这些工具，减少页面内手拼统一文件 URL 和重复判断。
- 文档、软件 API wrapper 的单项删除、恢复、永久删除、下载 URL/下载 blob 已统一委托 `api/file` 和文件 URL 工具；文档页、软件页不再直接传 `document` / `software` ownerType 调用 `deleteFileAsset`，页面层继续只面对本模块 API。
- 产品、包装、公司、产品回收站 API wrapper 的单项文件行为继续收口：默认、标签、删除、分类移动、排序、恢复、永久删除均委托 `api/file`；前端 `file-assets` 路径目前只保留在统一文件 API 和统一 URL 工具内，业务 wrapper 不再各自拼统一后端路径。
- 后端统一文件能力集合抽为 `FileAssetCapabilities`，`FileHandle.capabilities` 和各 adapter 的 `capabilities()` 共用同一份常量；同时修正包装素材 handle 曾暴露未实现的 `MOVE_OWNER` / `MOVE_CATEGORY`。统一预览/下载入口现在会先检查 `PREVIEW` / `DOWNLOAD` 能力，避免未声明支持的 ownerType 误走重定向。
- 前端 `useFileAssets` 修复残留乱码提示，并在执行标签、默认、排序、分类移动、归属移动、删除、恢复前自动补加载 capabilities；页面即使忘记显式调用 `loadCapabilities()`，也不会误判统一文件行为不支持。
- 新增 `useFileAssetActions` 处理运行时才知道 ownerType 的文件动作，并带 capabilities 缓存与校验；产品页、包装详情页、`useCatalogAssets` 的 variant/混合 ownerType 场景已改为复用该 composable。页面/业务 composable 层不再直接调用底层 `deleteFileAsset` / `setDefaultFileAsset` / `updateFileAssetTags` / `reorderFileAssets`，这些调用集中在统一文件 composable 内。
- 前端文件能力加载、缓存、unsupported 提示抽为 `useFileAssetCapabilities`，`useFileAssets` 与 `useFileAssetActions` 共用同一套能力判断；统一文件 composable 内残留乱码提示已清理，自动加载能力后也会同步 `capabilities` 暴露值。
- `useFileAssetCapabilities` 的能力缓存提升为模块级共享缓存，并增加同 ownerType 并发加载去重；多个页面或 composable 复用同一 ownerType 时不再重复请求 capabilities，`capabilitiesLoaded` 也可响应共享缓存变化。
- 前端统一文件 API 导出 `FileAssetDeleteOptions`，`useFileAssets` / `useFileAssetActions` 删除参数复用同一类型，下载接口显式标注 Blob 响应；同时清理运行时文件动作 composable 中残留的乱码提示文案。
- `useFileAssetActions` 补齐恢复、分类移动、归属移动和 `hasCapability` 暴露，运行时 ownerType 场景与固定 ownerType 的 `useFileAssets` 保持同一套能力校验和通知行为。
- 新增 `createFileAssetApi(ownerType)` 绑定式统一文件 API，文档、软件、公司这类固定 ownerType 模块的删除、恢复、永久删除、标签、默认、排序、分类移动、预览/下载 URL 不再各自重复写 ownerType 和永久删除参数；动态 ownerType 的产品/包装模块暂时保持显式传参。
- `createFileAssetApi` 下沉为构建函数，并新增带缓存的 `fileAssetApi(ownerType)`；产品、包装、产品回收站这类动态 ownerType wrapper 已改为通过 `fileAssetApi(ownerType)` 调用删除、恢复、永久删除、默认和标签行为，不再直接依赖底层 `deleteFileAsset` / `restoreFileAsset` 等函数。
- `useFileAssets` 与 `useFileAssetActions` 的动作调用也改为统一经 `fileAssetApi`，底层 `deleteFileAsset` / `setDefaultFileAsset` / `updateFileAssetTags` / `reorderFileAssets` 等函数只保留在 `frontend/src/api/file` 内部；同时再次清理两个 composable 的乱码提示文案。
- 上传链路继续收口：`useFileUpload` / `useFileUploadWorkflow` 清理乱码提示并补齐上传参数类型，包装模块的 `uploadPackagingFile` 改为复用统一 `uploadFile`，前端除 `api/file/file.ts` 外不再直接拼 `/files/upload`。
- 打开目录行为开始统一：后端新增 `FileAssetLocationService` 和 `/api/file-assets/{ownerType}/assets/{assetId}/local-path|open-folder`，并按 `OPEN_FOLDER` capability 校验；前端 `fileAssetApi`、`useFileAssets`、`useFileAssetActions` 暴露 `localPath/openFolder`；产品素材画廊和软件模块已优先走统一 open-folder，旧 `category/fileName` 接口保留兼容 fallback。
- 统一预览弹窗 `AssetPreviewModal` 支持可选 `ownerType/assetId`，打开目录时优先走统一 file-assets open-folder，缺少统一身份时回退旧 `category/fileName/modelId`；全局 `useAssetPreview`、产品预览事件、包装详情预览、公司素材预览已开始传递统一文件身份。
- 证书字段级附件继续接入统一行为：`certificate` capability 补齐 `OPEN_FOLDER`，证书主页面/弹窗预览向 `AssetPreviewModal` 传递 `ownerType=certificate` 与虚拟 `assetId`；删除已保存证书字段附件时优先走统一 `certificateFileAssets.remove()` 清空字段引用，只有新建/未保存的临时上传文件继续 fallback 到旧物理删除。
- 修复统一打开目录失败链路：本机启动脚本默认启用 `LOCAL_OPEN_FOLDER_ENABLED`，新增 `-DisableOpenFolder` 可显式关闭；`FileAssetLocationService` 对路径解析和系统打开异常统一返回 `{ success:false, error }`，避免冒成 500；`AssetPreviewModal` 在统一 open-folder 请求失败时会尝试旧接口 fallback，并清理打开目录按钮乱码。
- 产品素材画廊缩略图继续收口：有 `asset.id` 的图片预览改为 `/api/file-assets/{ownerType}/assets/{assetId}/preview`，仅无 id 的历史/临时素材继续 fallback 到旧 `fileUrl(category,fileName,modelId)`。
- 包装详情缩略图继续收口：规格轮播、规格图片管理、系列主图、通用素材预览优先走 `packaging_model` / `packaging_variant` 统一预览 URL，只有找不到 asset id 的历史图片回退旧 `fileUrl`。
- 证书详情弹窗对齐当前统一上传/文件 composable：修复上传工作流、文件 handle 刷新、缴费事件名和 `fileUrl` 参数类型，避免后续统一文件行为改动被旧组件类型错误阻断。
- 证书详情弹窗继续收口缩略图预览：打开弹窗时加载 `certificate` 统一文件 handle，说明书/证书图片优先使用 `certificateFileAssets.previewUrl(handle)`，只有找不到虚拟 handle 时回退旧 `fileUrl('certificate', fileName, null)`。
- 包装列表默认图继续收口：后端 `PackagingDtos.ModelSummary` 补充 `defaultImageAssetId`，列表页有资产 id 时使用 `/api/file-assets/packaging_model/assets/{assetId}/preview`，旧数据或未命中资产时继续回退 `fileUrl('pkg_main_image', fileName, modelId)`。
- 公司品牌素材缩略图继续收口：顶部主 Logo、素材网格、回收站网格和预览弹窗统一复用 `companyAssetPreviewUrl(asset)`，优先使用 `useFileAssets(ownerType: 'company')` 解析出的统一预览 URL，未命中 handle 时才回退旧 `fileUrl('company', fileName, null)`。
- 证书主页面预览继续收口：页面加载时预取 `certificate` 文件 handle，列表/卡片触发预览时按文件名匹配虚拟 handle，命中后向 `AssetPreviewModal` 传递 `ownerType=certificate` 和 `assetId`，打开目录与预览优先走统一文件行为。
- 产品主图缩略图继续收口：`ProductHeroImage` 和产品编辑侧栏的已建档主图优先使用 `product_model` 统一预览 URL；后端 `CatalogDtos.ModelSummary` 补充 `defaultImageAssetId`，产品列表卡片/工作台列表有资产 id 时使用 `/api/file-assets/product_model/assets/{assetId}/preview`，旧数据或未命中资产时继续回退 `fileUrl('main_image', fileName, modelId)`。
- 产品默认主图预览身份继续收口：产品列表点击默认图预览、产品编辑侧栏默认主图预览会在能定位资产时传递 `ownerType=product_model` 与 `assetId`，使 `AssetPreviewModal` 的预览和打开目录优先走统一文件行为。
- 全局 `useAssetPreview` 继续收口：当调用方提供 `ownerType/assetId` 但未显式提供 `fileUrl` 时，composable 会自动生成 `/api/file-assets/{ownerType}/assets/{assetId}/preview`，减少页面层手动拼统一预览 URL 的需求。
- 公司营业执照预览继续收口：`licenseFileName` 字段仍保留在公司信息表，但展示和预览会按文件名尝试匹配 `company` 文件 handle，命中后传递 `ownerType=company` 与 `assetId`，未命中时继续回退旧 `company` 文件 URL。
- 产品关联文档标签页清理命名歧义：原 `fileUrl/previewUrl` 导入已改为 `documentDownloadUrl/documentPreviewUrl` 别名，明确它们来自 `api/document` 的 `file-assets/document` 统一入口，而不是旧的 `category/fileName` 通用文件路径。
- 公司财务收款码与团队头像暂不强行并入 `company_assets`，但前端已把 `company_finance` / `company_team` 字段级文件 URL 集中到 `companyFinanceFileUrl` / `companyTeamFileUrl`；后续若新增字段级虚拟 handle，只需替换这两个入口，不再散落在模板中。
- 导入记录文件浏览页清理命名歧义：该页浏览的是物理分类目录文件，不是业务 asset，导入的 `fileUrl` 已改名为 `physicalFileUrl`，避免后续统一文件行为扫描时误判为未收口的业务文件入口。
- 前端页面层继续清理旧入口命名：产品主图、包装主图、证书附件、公司字段级文件、产品素材画廊和全局预览兜底中的旧物理路径调用均改为 `legacy*FileUrl` / `legacyOpenFileFolder` 别名；页面/组件/composable 层已不再出现裸 `fileUrl(...)` 或 `openFileFolder(...)` 调用，剩余旧路径均是明确标注的兼容 fallback。
- 公司字段级文件继续接入统一行为：后端 `CompanyFileAssetAdapter` 以负数虚拟 `assetId` 暴露财务收款码 `company_finance` 和团队头像 `company_team`，能力限定为预览、下载、删除、打开目录；前端 `companyFinanceFileUrl` / `companyTeamFileUrl` 优先匹配统一 `company` handle，未命中时再回退旧物理分类 URL。该步骤不合并 `company_assets`，也不做表结构迁移。
- 公司字段级文件预览身份继续收口：财务收款码和团队头像点击预览时会按 `category + fileName` 查找虚拟 `company` handle，命中后向 `AssetPreviewModal` 传递 `ownerType=company` 与虚拟 `assetId`，使预览弹窗里的打开目录也优先走统一文件行为。
- 软件页面打开目录继续收口：`SoftwareView` 不再直接调用软件 API wrapper 的打开目录函数，改为复用 `useFileAssetActions.openFolder('software', id)`，由统一文件动作 composable 负责 capability 校验、通知和错误处理；同时 `AssetPreviewModal` 标记为 TypeScript 组件并补齐统一文件 props 默认值，避免统一文件身份扩展继续被模板类型问题阻断。
- 打开目录 fallback 逻辑继续收口：新增 `openFileAssetFolderWithFallback` 工具，统一封装“优先按 `ownerType/assetId` 调用 file-assets open-folder，失败后按 `category/fileName/modelId` 回退旧接口”的流程；`AssetPreviewModal` 和产品素材画廊已复用该工具，不再各自手写 fallback。顺带修复 `FilePreviewBody` 与 `MarkdownEditor` 的 TypeScript 边界类型，保持前端 typecheck 可用。
- 预览 URL fallback 逻辑继续收口：新增 `fileAssetPreviewUrlWithFallback` 工具，统一封装“有统一 `ownerType/assetId` 时使用 file-assets preview，没有统一身份时回退旧物理分类 URL”的流程；产品主图、产品列表默认图和包装列表默认图已改为复用该工具，页面层不再重复手写统一 URL 与 legacy URL 的二选一逻辑。
- 后端统一行为能力校验继续收口：`FileAssetBehaviorService` 不再只看 ownerType 级别 capability，执行标签、默认、移动、删除、恢复、永久删除、预览/下载/打开目录前都会解析当前 `FileHandle.capabilities` 并做单文件校验。字段级虚拟文件（如 `company_finance` / `company_team`）只会允许自身声明的行为，误调用未支持动作时统一返回 400，而不会落到真实素材表逻辑。
- 统一排序能力继续收口：`reorder` 这类批量动作现在会校验每一个排序项对应的 `FileHandle.capabilities` 是否包含 `REORDER`，避免字段级虚拟文件或其他只读/有限能力文件混入排序请求后被当作普通素材写入业务排序逻辑。
- 统一能力错误继续收口：后端能力不支持的异常消息现在包含 `ownerType`、`assetId` 和具体 capability，前端看到 400 或排查日志时能直接定位是哪类文件、哪个文件、哪个动作不被支持。
- 前端固定 ownerType 文件动作继续收口：`useFileAssets` 新增单文件能力预判，执行标签、默认、排序、分类/归属移动、删除、恢复、打开目录前，如果当前文件 handle 已加载且不包含对应 capability，会直接使用统一“不支持该能力”提示并阻止请求，减少字段级虚拟文件误触发后端 400。
- 产品列表默认图继续收口：`ProductListPanel` 的模型、分页、筛选和完整度 props 已补齐 TypeScript 边界，默认图预览 URL 统一走 `fileAssetPreviewUrlWithFallback`；有 `defaultImageAssetId` 时走 `product_model` 统一预览，没有统一身份时才回退旧 `main_image/fileName/modelId` 路径。
- 产品主图组件继续收口：`ProductHeroImage` 复用公共 `CatalogAsset` / `CatalogModel` 类型，主图和展开缩略图统一通过 `fileAssetPreviewUrlWithFallback` 生成预览 URL；拖拽排序事件补齐类型和 `dataTransfer` 空值保护，后续替换旧主图文件行为时不再被模板 `unknown/never` 类型问题阻断。
- 产品素材画廊继续收口：`CatalogAssetGallery` 补齐素材、分类、分组、菜单、标签、拖拽排序和上传事件的 TypeScript 边界；缩略图预览继续优先按 `product_model` / `product_variant` 的 `assetId` 使用统一预览 URL，打开目录继续复用 `openFileAssetFolderWithFallback`，旧物理路径只作为无统一身份时的兼容 fallback。
- 产品说明书生成弹窗继续清理类型阻断：`CatalogManualBuilder` 补齐模板、章节、缺失字段、pane/mode、通知/确认和说明书 API 参数类型；未保存产品缺少 `model.id` 时会在前端阻止生成/保存请求，避免手册类文件行为收口时被旧 `never[]` / 空 id 问题阻断。
- 产品营销资料标签页继续清理类型阻断：`CatalogMarketingTab` 补齐卖点、FAQ、耗材关联、产品简表和表单状态类型，消除旧 `never[]` 推断导致的模板报错；后续若将营销资料中的附件/素材接入统一文件行为，不会再被基础数据结构类型问题阻断。
- 产品定价标签页继续清理类型阻断：`CatalogPricingTab` 补齐定价数据、表单和选配加价项类型，并规范 JSON 选配项解析与保存错误处理；该组件已不再因 `never[]` / unknown catch 影响统一文件行为的整体验证。
- 产品完整度面板继续清理类型阻断：`CompletenessDashboard` 补齐健康项、完整度维度、模型来源和手册状态类型，维度展开 key 改为明确联合类型；素材/文档维度跳转仍保持原行为，后续统一文件行为验收时可稳定定位到对应入口。
- 产品关联文档选择弹窗继续收口：`DocumentSelectorModal` 补齐文档列表、分页返回、筛选参数、已知文档缓存和批量关联 payload 类型；该弹窗继续通过 `api/document` 使用已收口的 `file-assets/document` 文档入口，产品文档关联链路不再被 `never[]` / 参数类型问题阻断。
- 机器配置编辑器继续清理类型阻断：`MachineConfigEditor` 补齐机器配置行、保存 payload、显示模式、分类筛选和 CSV 导入解析类型，并清理未使用的模板过滤残留；完整度配置维度可继续稳定跳转到机器配置入口。
- 产品关联文档主标签页继续收口：`ProductDocumentsTab` 补齐产品关联文档、分组、筛选、预览/下载/取消关联动作类型，并将文档预览、下载、删除关联统一转为数字 `documentId` 调用；该页继续使用 `api/document` 的 `file-assets/document` 预览/下载 URL。
- 产品编辑侧栏素材面板继续收口：`SidebarAssetPanel` 补齐编辑表单、主图素材、上传事件和预览 payload 类型；默认主图和缩略图继续优先走 `product_model` 统一预览 URL，无统一资产 id 时才回退旧 `main_image` 物理路径。
- 产品编辑主弹窗继续清理类型阻断：`ProductEditorModal` 补齐编辑表单、主图素材、预览 payload、字段管理、动态字段分组和选项索引类型，并声明 `imageBroken` prop；产品编辑入口可继续稳定向侧栏素材面板传递统一文件预览身份。
- 机器配置模板管理器继续清理类型阻断：`TemplateManagerModal` 补齐模板行、保存 payload、显示模式、分类筛选和排序类型，模板排序 payload 对齐为 `{ id, order }`；机器配置相关入口已不再阻断统一文件行为整体验证。
- 页面级预览 URL fallback 继续下沉：产品页、公司页、证书列表页和证书详情弹窗中残留的“统一 handle 预览 URL 或旧物理 URL”判断已改为复用 `fileAssetPreviewUrlWithFallback`；页面层不再直接导入证书/公司旧 `fileUrl` 别名，旧物理路径仅保留在统一 fallback 工具内部。
- 组件级预览 URL fallback 继续下沉：产品素材画廊、产品编辑侧栏、包装详情抽屉和全局 `useAssetPreview` 已统一复用 `fileAssetPreviewUrlWithFallback`；页面/组件/composable 层不再直接导入旧物理 `fileUrl` 作为预览 fallback，旧路径集中保留在统一工具内部。
- 旧物理文件动作入口继续收口：移除未使用的 `uploadPackagingFile` 空壳包装后，前端 `uploadFile` 只剩 `useFileUpload` 统一上传 composable 调用，`deleteFile` / `getLocalPath` 只剩旧 API 定义，`openFileFolder` 只剩 `openFileAssetFolderWithFallback` 作为明确兼容 fallback；页面、组件和业务 API 层不再直接调用旧物理上传、删除、本地路径或打开目录动作。
- 上传后登记业务资产继续收口：新增 `useRegisteredFileUpload`，统一封装“先走物理上传，再调用业务表登记 asset”的流程；产品素材单项上传、产品素材批量上传和包装列表主图拖拽上传已接入该流程，业务表仍保留 `product_assets` / 包装素材表，不做强制合并。
- 上传工作流登记钩子继续收口：`useFileUploadWorkflow` 新增 `registerUploaded`，拖拽、粘贴、选择文件上传成功后可统一执行业务 asset 登记，并把登记结果带入 batch 结果；包装详情抽屉和公司品牌素材库主上传已从 `onUploaded` 手写登记迁移到该钩子，`onUploaded` 继续保留给财务收款码、团队头像这类字段赋值场景。
- 上传工作流类型边界继续收口：`useFileUploadWorkflow` 导出 `UploadWorkflowResult`、`UploadOverrideOptions`、上传成功 payload 和登记 payload 类型，并允许登记结果显式为 `null`；包装详情和公司字段级上传回调已去掉多余 `any` 标注，后续接入统一上传登记时可直接依赖上下文类型。
- 固定 ownerType 批量行为继续收口：`useFileAssets` 新增 `batchUpdateTags`、`batchMoveCategory` 和 `batchRemove`，统一处理多文件能力预检、逐项调用、一次刷新和一次通知；公司品牌素材库的批量标签、批量分类移动、批量删除已改为统一 file-assets 优先，只有未命中统一 handle 的历史记录继续 fallback 到旧公司业务批量接口。
- 动态 ownerType 批量行为继续收口：`useFileAssetActions` 新增 `batchUpdateTags` 和 `batchRemove`，产品素材页会按 `product_model` / `product_variant` 对选中素材分组；批量标签和“只删记录”的批量删除已走统一 file-assets 行为，“同时删除物理文件”仍保留旧 catalog 接口以维持物理删除兼容语义。
- 回收站素材批量行为继续收口：`useFileAssetActions` 新增 `batchRestore`，产品回收站的素材批量恢复和批量永久删除已按 `product_model` / `product_variant` 分组走统一 file-assets 行为；产品/规格记录本身的批量恢复和永久删除仍保留 catalog 回收站业务接口。
- 后端统一能力门禁补充测试：新增 `FileAssetBehaviorServiceTest`，锁定 ownerType 级 capability 与单文件 `FileHandle.capabilities` 的双层校验规则，避免字段级虚拟文件未来误暴露恢复、永久删除、标签、排序等未声明行为。
- 后端测试链路继续收口：修复 `DuplicateValueNormalizerTest` 的 testCompile 阻塞，并验证 `FileAssetBehaviorServiceTest` / `DuplicateValueNormalizerTest` 可通过；统一文件能力门禁现在有可执行回归测试保护。
- 旧业务批量文件入口继续清理：产品素材批量标签和产品回收站素材批量恢复/永久删除已由统一 file-assets 批量动作替代，前端删除对应未使用 API wrapper；保留“同时删除物理文件”和公司历史记录 fallback 这类明确兼容入口。
- 统一文件行为验收继续收口：后端全量 `mvn test` 与前端 `vue-tsc` 均已恢复通过；当前前端旧物理上传、删除、本地路径、打开目录调用只剩统一上传 composable、旧物理 API 定义和统一 fallback 工具。公司素材旧批量标签/分类接口仅作为未命中统一 handle 的历史记录 fallback 保留，密码模块同名批量分类不属于文件行为。

## 2026-07-18 包装文件行为继续收口

- 包装素材纯文件行为继续统一：包装列表拖拽上传主图后的自动设默认、包装详情上传后的自动设默认、详情内设默认、标签更新和删除均改为调用 `useFileAssetActions`，按 `packaging_model` / `packaging_variant` 显式 ownerType 执行统一文件行为。
- `api/packaging` 中移除纯转发的 `deletePackagingAsset`、`setDefaultPackagingAsset`、`updatePackagingAssetTags`，仅保留素材登记和混合 ownerType 排序这类包装业务语义。

## 2026-07-18 公司素材单项行为继续收口

- 公司品牌素材页仍保留 `api/company` 单项文件动作兼容层，以避免大体量历史编码页面在本轮继续产生结构风险；这些 wrapper 内部统一转发 `file-assets/company`，不再各自拼接后端路径。
- 批量标签和批量分类接口暂时保留为未命中统一 handle 的历史记录 fallback；公司页后续建议先完成编码治理或拆小组件，再继续直接替换到 `useFileAssets({ ownerType: 'company' })`。

## 2026-07-18 文档与软件删除行为继续收口

- 文档页单项删除改为 `useFileAssetActions.remove('document', id)`，并移除 `api/document` 中纯转发的 `deleteDocument`。
- 软件页单项删除改为 `useFileAssetActions.remove('software', id)`，并移除 `api/software` 中纯转发的 `deleteSoftware`；下载 URL、导入、批量分类/状态等模块业务接口继续保留。

## 2026-07-18 文档与软件回收站 wrapper 继续清理

- 文档页批量删除改为 `useFileAssetActions.batchRemove('document', ids)`，不再调用 `batchDeleteDocuments` 业务 wrapper；状态、分类、部门这类文档业务批量变更继续保留原接口。
- `api/document` 清理未使用的 `batchDeleteDocuments`、`restoreDocument`、`permanentDeleteDocument`、`batchRestoreDocuments`、`batchPurgeDocuments`、`emptyTrashDocuments`。
- `api/software` 清理未使用的 `restoreSoftware`、`permanentDeleteSoftware`、`emptyTrash`、`batchDeleteSoftwares`、`batchRestoreSoftwares`；导入、下载 URL、分类/安装状态批量变更继续保留模块业务接口。

## 2026-07-18 产品旧 wrapper 继续清理

- `api/catalog/assets` 清理未使用的 `setDefaultCatalogAsset`、`updateCatalogAssetTags`；产品设默认、标签更新已由页面/composable 直接走统一文件动作。
- `api/catalog/trashAudit` 清理未使用的资产级 `restoreAsset`、`permanentDeleteAsset` 旧回收站路径；产品素材恢复/永久删除继续由 `useFileAssetActions` 按 `product_model` / `product_variant` 执行。
- 继续保留 `deleteCatalogAsset(deleteFile=true)`、`moveCatalogAsset`、`reorderCatalogAssets`，因为它们仍承载物理删除、跨产品/规格移动或混合排序等 catalog 业务语义。

## 2026-07-21 最终清边界：物理删除入口替换 + 明确兼容边界

### 本次替换

- 产品素材“同时删除物理文件”的旧入口 `deleteCatalogAssetAndFile` 已整体替换为统一文件行为：
  - `ProductCatalogView.vue` 单项删除（同时删文件）改为 `fileAssetActions.remove(ownerType, id, { permanent: true, deletePhysical: true })`。
  - `ProductCatalogView.vue` 批量删除（同时删文件）改为按 `product_model` / `product_variant` 分组调用 `fileAssetActions.batchRemove(..., { permanent: true, deletePhysical: true })`。
  - `useCatalogAssets.ts` 的 `remove({ deleteFile: true })` 同样改为统一永久删除。
  - `api/catalog/assets.ts` 移除 `deleteCatalogAsset` 与 `deleteCatalogAssetAndFile` 导出，仅保留 `addCatalogAsset`、`moveCatalogAsset`、`reorderCatalogAssets`。
  - `api/catalog/__tests__/assets.test.ts` 同步更新为测试剩余函数。
- 后端清理已确认无前端调用的死接口：
  - `ProductCatalogController`：移除 `/assets/{id}/default`、`/assets/{id}` DELETE、`/assets/{id}/tags`、`/assets/batch-tags`、`/assets/batch-variant`。
  - `PackagingController`：移除 `/assets/{id}` DELETE、`/assets/{id}/default`、`/assets/{id}/tags`。
  - `CompanyInfoController`：移除 `/assets/{id}` DELETE、`/assets/{id}/tags`、`/assets/batch-tags`、`/assets/{id}/default`、`/assets/batch-category`、`/assets/reorder`、`/assets/trash/{id}/restore`、`/assets/trash/{id}` DELETE。
  - `FileController`：移除 `/{category}` DELETE（物理删除）、`/info/local-path`（本地路径查询）。

### 产品素材排序场景确认

- `CatalogAssetGallery` 的拖拽排序发生在**同一 category 内**，但同一产品的同一 category（如 `main_image`）可能同时包含 `product_model` 素材和 `product_variant` 素材。
- 统一接口 `reorder` 要求单次调用所有 items 属于同一 `ownerType`，因此**产品素材混合 ownerType 排序不能直接替换**。
- `handleReorderMainImages`（主图 gallery）和 `handleReorderAssets`（素材 tab 卡片）继续保留 `reorderCatalogAssets`。

---

## 明确兼容边界（保留清单）

以下旧入口经评估后**必须保留**，后续不再反复扫描或尝试替换：

| 保留入口 | 所在文件/模块 | 保留理由 |
|---------|-------------|---------|
| `moveCatalogAsset` | `api/catalog/assets.ts` | 跨型号/规格移动涉及 `targetModelId` + `targetVariantId` + `targetCategory` 的 catalog 特有语义，与统一接口的 `moveOwner`/`moveCategory` 拆分概念不完全等价 |
| `reorderCatalogAssets` | `api/catalog/assets.ts` | 产品/包装素材可能在同一列表内混合 `product_model` / `product_variant` 进行排序，统一接口要求单一 `ownerType` |
| `addCatalogAsset` | `api/catalog/assets.ts` | 上传后业务登记，需写入 `product_assets` 表并关联 model/variant/category |
| `FileController`（后端） | `backend/controller/FileController.java` | 原始物理文件上传、预览、下载入口；`useFileUpload` 统一上传、字段级文件、导入扫描均依赖它 |
| `legacyOpenFileFolder` | `utils/ui/fileAssetUrls.ts` | 无统一 `assetId` 的历史数据 fallback，已明确标注 `legacy` |
| `/api/files/...` URL 生成 | `utils/ui/fileUtils.ts` | 旧物理文件预览/下载 URL，用于无统一 handle 时的 fallback |
| `listPhysicalFiles` | `ImportRecordsView.vue` | 导入页浏览的是物理分类目录，不是业务 asset |
| 包装 `reorder` 业务接口 | `api/packaging/packaging.ts` | 包装素材同样存在混合 `packaging_model` / `packaging_variant` 排序场景 |
| 公司旧批量标签/分类 | `api/company/company.ts` | 未命中统一 handle 的历史记录 fallback；公司页后续建议先完成编码治理再继续替换 |
| 文档 Office PDF 高保真预览 | `api/document/documents.ts` | 文档业务特有预览链路，不属于纯文件行为 |
| 软件导入/批量分类/状态 | `api/software/softwares.ts` | 软件模块业务语义，不属于纯文件行为 |

### 判定规则

后续若遇到新的旧入口，按以下规则判定是否保留：

1. **纯文件行为**（预览、下载、标签、默认、删除、恢复、永久删除、打开目录）优先走统一 `file-assets` 接口。
2. **涉及混合 ownerType 的批量操作**（排序、跨归属移动）保留原业务接口。
3. **业务登记/创建**（上传后写入业务表）保留原业务接口。
4. **物理目录浏览**（导入扫描、字段级临时文件）保留原物理文件接口。
5. **模块特有语义**（文档 Office 预览、软件导入、批量状态/分类变更）保留原业务接口。
