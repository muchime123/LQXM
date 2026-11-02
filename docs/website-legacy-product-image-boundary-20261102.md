# 官网产品图片来源边界（2026-11-02）

## 当前代码

官网产品列表和详情可收到两种不同结构的公开响应。独立产品响应包含 `product.slug`、`cardMediaId`、`heroMediaId` 和官网媒体图库；旧响应包含 `websiteSlug`、`defaultImageAssetId` 和内部素材图库。公开 API 路径虽然统一为 `/api/website/products*`，产品源在 `app.website.product-source=legacy` 时仍从内部产品档案读取。

本次把两种图片路径的分界写清楚：独立产品无媒体时只使用官网内嵌 SVG 占位图；详情页不会把官网图库关联 ID 当成内部素材 ID 构造 `/api/file-assets/product_model/assets/...` URL。同时修正了产品详情图片加载失败时指向不存在的 `/images/manual-sealer.jpg` 的回退。旧产品响应在正式切换前仍使用原缩略图 URL，以保证已展示的图片不丢失。

## 未清除的依赖与移除条件

旧图片 URL 仍位于 `corporate-website/src/api/website.js` 的 legacy 分支，以及 `corporate-website/src/views/ProductDetail.vue` 的 legacy 详情分支。截止已有只读盘点，官网产品副本为 45 条草稿、已发布 0 条；官网产品图库已复制并建立 141 条关联。若现在直接移除旧分支，当前列表和详情会丢失真实产品图片。

完成以下条件后再删除旧分支：

1. 对所有当前公开产品逐条审核官网副本、主图、图库、SEO 与展示字段，并发布独立内容及其媒体。
2. 将 `app.website.product-source` 切到 native，比较产品列表、详情、首页精选和图片实际 HTTP 响应。
3. 验证静态化构建所需图片 URL 在部署环境可访问；确认没有仍需旧响应的公开页面或书签。

这项残留属于尚未切换的数据源依赖；仅改图片 URL 的命名空间不能使内容或物理文件独立。
