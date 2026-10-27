# 官网 Banner 媒体草稿导入记录

2026-10-27 在本机 `product_archive` 核对。Flyway V411、V413、V417 均显示 `success=1`；此前关于 V417“尚未执行”的状态已过时。本轮未再次运行 Flyway。

只读盘点见 `website-banner-media-inventory-20261027.json`：7 条 Banner 中 3 条带有公司目录旧图片，分别为 ID 3、4、5；全部找到唯一源文件。ID 4、5 当前公开，ID 3 已软删除。公开接口此时返回 Banner 2、4、5，其中 4、5 使用旧 `/api/files/company` 图片 URL。

三份原图先复制到工作区 `storage/website-migration-stage/website/images/` 并核对 SHA-256。随后使用 `tools/import_website_banner_media.py --apply` 将同内容文件复制到 `storage/lq-files/website/images/`，在 `website_media_assets` 新增三条 `DRAFT` 记录。映射：Banner 3 → 官网媒体 1，Banner 4 → 官网媒体 2，Banner 5 → 官网媒体 3。三条目标文件的哈希均与源文件一致。重复执行导入后映射 ID 不变，未产生重复登记。

**本轮没有更新 `website_banners.website_media_id`。**只读复查该字段在 7 条 Banner 上仍全部为 `NULL`；公开 Banner 2、4、5 及 ID 4、5 的旧图片 URL 保持不变。历史 Banner 3 的媒体只保留草稿，不公开。没有删除旧图片或改动询盘、产品及其他官网内容。

接下来的切换应先在隔离环境验证管理端选图、公开媒体访问与图片对拍，再对当前公开 Banner 4、5 明确执行引用切换。Banner 3 不应自动恢复或发布。

后续只读切换预检见 `website-banner-cutover-preflight-20261027.json`：Banner 4/5 的数据库映射、原图与官网副本哈希均通过；但本机 `127.0.0.1:8081` 当时拒绝连接，无法对拍正在运行的公开接口。`ready_for_runtime_cutover_test=false`，因此未更新 `website_banners.website_media_id`，也未发布媒体。待服务启动后重新运行 `tools/verify_website_banner_cutover.py`，确认公开接口仍返回旧图，再进行隔离环境切换测试。
