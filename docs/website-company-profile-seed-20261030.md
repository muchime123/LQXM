# 官网公司介绍独立草稿复制记录（2026-10-30）

## 本轮结果

- 实际 schema：`product_archive`。
- 内部 `company_info` 有 2 行；现有 `CompanyInfoService.getCompanyInfo()` 按 ID 升序选择首行，本轮来源为 ID 2。
- 只复制 `WebsiteController.PublicCompanyView.from(CompanyInfo)` 使用的 20 个公开字段，目标 `website_company_profiles.id=1`。
- 目标原为空；新增 1 条 `DRAFT`。`logo_media_id` 留空，待官网专用媒体复制及审阅。
- 事务内逐字段核验；事务后的只读核验：20 个字段差异 0。再次运行预检发现目标已有内容，拒绝重复插入。
- 未修改内部公司档案、官网公开来源配置、发布状态、Flyway 或任何既有官网内容。

## 待完成

1. 将当前展示所需 Logo 文件复制到官网专用媒体目录，核验 SHA-256，选入公司资料草稿。
2. 人工核对公司介绍文字、公开联系方式和英文内容，完成发布准备检查。
3. 官网公司历程、社交链接、资质仍无独立内容；这些入口完成独立内容后才能整体切换公司来源。

证据：`website-company-profile-seed-preflight-20261030.json`、`website-company-profile-seed-result-20261030.json`、`website-company-profile-seed-verify-20261030.json`。执行工具：`tools/seed_website_company_profile.py`。
