# 官网包材公开接口独立化（2026-11-01）

## 切换前只读基线

真实数据库 `product_archive` 中，按现行 `/api/website/packaging` 的规则（未软删除、在售、`website_enabled=1`），可公开的内部包材系列为 **0**；官网独立包材系列、规格也均为 **0**。官网包材页在空列表时展示自有材料类别与选配说明，未引用内部包材文件。

证据：`docs/website-packaging-source-audit-20261101.json`。此次基线只说明本次查询时的数据库状态；若旧部署仍允许内部后台发布包材，部署前应再次核对。

## 实施内容

- 保留 `GET /api/website/packaging` 路径，直接读取 `WebsitePackagingItemService.publishedCatalog()`，移除公开控制器对内部 `PackagingService` 的注入和旧来源分支。
- 公开包材响应不再包含内部 `defaultImageAssetId`。官网图片只从 `imageUrl` 指向的官网媒体接口读取，不再拼接内部 `/api/file-assets/packaging_model/...` URL。
- 后台“官网包材”页的提示改为已发布内容直接进入官网目录；原“切换条件”检查作为已发布内容完整性检查继续使用。
- 官网包材页空数据时的材料类别、选配说明和咨询入口保留。没有迁移文件或修改数据库记录。

## 验证与后续

后端公开来源定向测试退出码 0；官网前端测试 5/5、生产构建通过。后续新增包材须在官网后台创建独立系列、规格和官网媒体后发布。产品与公司公开接口仍有各自的旧来源分支，按内容审核与对拍进度分别处理。
