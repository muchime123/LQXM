# 官网存量内容媒体解耦进度（2026-10-30）

## 实际盘点

在 `product_archive` 上只读查询当前生效的 3 条 Banner、20 条页面区块、9 条解决方案。2 条 Banner（ID 4、5）和 6 条区块（ID 3、8、9、14、16、19）仍引用内部文件接口；共 14 处引用、9 个不同源文件。当前生效解决方案未检出内部文件接口引用。每个源文件均唯一定位，原文件 SHA-256 和大小已写入[盘点 JSON](website-content-media-inventory-20261030.json)。

## 本轮已完成

- 复用 2 个已有的官网媒体草稿（ID 2、3）。
- 将其余 7 个源文件复制到 `storage/lq-files/website/images/`，逐件校验 SHA-256；不移动、不删除原文件。
- 在 `website_media_assets` 新增 7 条 `DRAFT` 记录（ID 140–146）。旧 URL 到新媒体 ID 的映射见[登记结果](website-content-media-register-20261030.json)。
- 再次登记新增 0 条；再盘点显示 9/9 个源文件都有经哈希验证的官网副本，见[复核 JSON](website-content-media-inventory-after-20261030.json)。

随后执行了带原值备份的事务切换。原字段见[回退快照](website-content-media-cutover-backup-20261030.json)，执行摘要见[切换结果](website-content-media-cutover-result-20261030.json)：9 个官网媒体变为 `PUBLISHED`，Banner 4、5 写入 `website_media_id`（原 `image_url` 保留），6 条区块的 12 处内部文件 URL 替换为官网媒体 URL。解决方案记录未修改，内部原文件未移动或删除。

切换后的独立[只读复核](website-content-media-inventory-post-cutover-20261030.json)显示：当前生效的 3 条 Banner、20 条区块、9 条解决方案中，内部文件接口引用为 0；14 处官网媒体引用涉及 9 个媒体 ID，全部为已发布且文件 SHA-256 与登记值一致。切换脚本第一次因映射中缺少哈希字段在写入前回滚，修正后重新预检通过才执行；失败尝试未改变数据。

[逐字段复核](website-content-media-cutover-verify-20261030.json)确认 2 条 Banner 的原 `image_url` 保持不变、仅新增官网媒体 ID；6 条区块除计划内的 12 处 URL 替换外，`image_url` 与 `content` 没有其他文本变化。9 个相关官网媒体状态均为 `PUBLISHED`。

## 尚需完成

本机后端 8081 当前未监听。启动现有后端可能触发 Flyway 和启动期账号/文件维护逻辑，因此本轮未进行真实 HTTP 与浏览器页面验收。下一步在正常运行环境中检查 Banner、区块公开响应与图片内容，并核对首页、关于页、产品页的实际呈现；若有差异，使用回退快照恢复原字段。产品、包材、公司内容仍需各自完成独立来源的发布与对拍。
