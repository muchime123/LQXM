# 官网公司历程与官方链接独立数据复制（2026-10-31）

- 实际公开历程 3 条，来源 ID 1、2、3；均无附件。官网独立表原有 0 条。
- 按现有公开查询的 `website_enabled=1` 和 `sort_order,id` 顺序复制到 `website_company_milestones`，新增 3 条 `DRAFT`，目标 ID 1、2、3。
- 复制事务内及事务后只读核验：年份、标题、描述、排序均一致；目标均为草稿。
- 当前官网公开官方账号 0 条，`website_social_links` 也为 0；无内容需要复制。
- 当前内部公司记录没有 `logo_file_name`，官网公司草稿 `logo_media_id` 仍为空。不能自动认定品牌资产中的某个文件就是当前公开 Logo。
- 本轮未发布内容，未切换 `company-source`，未修改内部公司档案或文件，未执行 Flyway。

证据文件：`website-company-logo-audit-20261031.json`、`website-company-public-lists-audit-20261031.json`、`website-company-milestones-seed-preflight-20261031.json`、`website-company-milestones-seed-result-20261031.json`、`website-company-public-lists-verify-20261031.json`。

下一步：确定官网 Logo 的独立媒体文件；核对并迁入资质展示内容；通过公司内容发布准备检查后再切换公开接口。
