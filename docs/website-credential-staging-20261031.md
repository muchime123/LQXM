# 官网资质独立内容草稿准备（2026-10-31）

## 实施结果

- 官网当前资质展示来自 `corporate-website/src/data/qualifications.js`，不是内部档案接口。
- 该文件的 4 条资质卡片已逐条复制到 `website_credentials`，全部为 `DRAFT`，不含 `media_id`、颁发机构或有效期。静态 ID 1–4 对应官网草稿 ID 1–4。
- 事务内逐字段校验成功；事务后只读核验 `current_draft_matches_static=true`。再次执行预检因目标已有内容而拒绝重复插入。
- 未启用 `credentials-native-enabled`，未改官网前端读取来源，未发布资质，也未修改内部档案或文件。

## 审核风险

内部资质表有 27 条，其中 23 条能定位到单个文件、4 条未定位到文件。但官网静态卡片与内部记录不是一对一的已核实映射，不能依据名称近似就自动填充证书图片、有效期或发布状态。当前静态文案的真实性及有效性仍须逐条核证；草稿不构成认证结论。

后续按条目核对证书和公开许可，将实际展示图片复制到官网媒体目录并登记官网媒体资产，再填写有效期、颁发机构并发布。发布后的独立接口与静态页面对拍通过后，方可切换官网前端来源。

证据：`website-credential-candidates-20261031.json`、`website-static-credentials-seed-preflight-20261031.json`、`website-static-credentials-seed-result-20261031.json`、`website-static-credentials-seed-verify-20261031.json`。脚本：`tools/audit_website_credential_candidates.py`、`tools/seed_website_static_credentials.py`。
