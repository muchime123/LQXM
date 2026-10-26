# 官网公开内容迁移基线（2026-10-26）

只读采集：本机运行中的 `/api/website/*` 接口与 `product_archive`。未执行写入、迁移或文件复制。响应哈希按 HTTP 实际内容的 UTF-8 字节计算，用于切换前后对拍；动态字段可能使哈希变化，应结合字段级比较。

| 公开接口 | HTTP | 字节 | SHA-256 |
|---|---:|---:|---|
| `/home` | 200 | 22496 | `8cb28d6e2befc443c3ddf8e398e4555927fb2b3e746e83a52d5a018e758a0150` |
| `/products?page=0&size=500` | 200 | 21235 | `25c7c1b2a7796315afa1e452dd468f50ff832c78cf36e0fa5860ae1bf2f41198` |
| `/products/featured?limit=500` | 200 | 20336 | `ae27fe554cff741ecbaa50c734219064d0efdfe94c1a1f0a3ce4c5efee29f5d0` |
| `/product-categories` | 200 | 713 | `417c8e10e98bc218a037dd8e9139e565c7cd4692c1a70397fc6e3752ac642443` |
| `/packaging` | 200 | 2 | 空数组 |
| `/molds` | 200 | 2 | 空数组 |
| `/company-detail` | 200 | 1737 | `1db438e14751188ae211eefaa2e99645110aed544f6f51012a8c140b5e4a755b` |
| `/milestones` | 200 | 367 | `ce24cf1ff07bb36e75395538be3744a95579b901d9a0d9ca82c7c466fddb589b` |
| `/official-media-accounts` | 200 | 2 | 空数组 |
| `/banners/public` | 200 | 1530 | `56d6852696ad5476129fe24147313640bed7a7a458fc7032df481133c22862a3` |
| `/page-sections/HOME` | 200 | 3249 | `d30a396ed3b4c833763034a0931a330d103c22a5c8a5918a27ca846058ccfd8b` |
| `/solutions/public` | 200 | 4825 | `a8bfb6fa6b497249866722abaf12c7aff227b1c5b8deff3f8f250f41cd8564c0` |

公开产品列表实际 `total=45`、`items=45`；精选接口返回 43。数据库中 `website_enabled=1 AND deleted_at IS NULL` 的内部产品为 46，差异来自公开筛选或完整度规则，迁移工具不能简单复制 46 条为已发布。公开 Banner 1 条；数据库 `website_banners` 共 7 条。包材、模具及官方自媒体当前公开均为 0 条。

未进入公开列表的内部产品 ID 为 `5985`；迁移时先作为待审核草稿核对，不能丢弃，也不能未经核对直接发布。下一步逐一确认公开图片/详情 URL 是否可读取，再生成独立官网记录与媒体映射。当前此文件是汇总快照，不包含完整响应正文，不能单独用于自动迁移。

## 产品详情与媒体核验

45 个公开产品详情均返回 200；45 张主图缩略图均返回 200。产品详情图库共引用 438 个不同素材 ID（438 次图库引用），其中 437 个缩略图可读取；素材 `5212` 返回 HTTP 415。数据库记录显示它属于产品 `87`，类别 `drawing`，文件类型为 PSD，`storage_key`、`mime_type` 和 `file_size` 均未登记。该素材应作为原始设计文件单独保留与核验；迁移时不能假定它能生成网页缩略图，应决定是否制作经审核的预览图。其余缩略图可读不代表原始文件已做 SHA-256 核验。

只读文件预检工具 `tools/audit_website_product_media.py` 按 `FileStorageService.productDirs` 的规范及历史目录规则定位当前标记官网可见的 438 条产品素材，并对源文件计算 SHA-256。结果：438 条均找到且各有唯一候选路径；缺失 0、重复候选 0。预检未复制文件，哈希结果目前仅在运行输出中，正式迁移工具须保存逐件映射与复制后的哈希对照。PSD 素材 `5212` 虽可在文件系统定位，网页缩略图仍不支持。
