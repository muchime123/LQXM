# 官网知识产权独立内容链路（2026-10-31）

## 本轮建设

- 新增 Flyway `V436__create_website_ip_items.sql`。官网表只引用官网媒体资产，不引用内部证书、专利或档案表；历史迁移未改。
- 新增 `website.manage` 保护的草稿管理 API：列表、新增、编辑、发布、下线。发布要求公开编号、申请人、法律状态及可读取的官网证书图片，失效、无效或撤销状态不能作为现行成果发布。
- 新增受 `app.website.ip-native-enabled` 控制的公开查询，默认关闭；只返回已发布记录。媒体公开读取还须独立来源开关开启、媒体已发布且存在已发布知识产权引用。
- 后台增加“官网知识产权”管理入口，官网前端增加 `VITE_WEBSITE_IP_SOURCE=native` 读取分支。默认仍读取原有静态卡片，页面当前展示不变。
- 当前静态知识产权 4 条均缺编号、申请人和图片；未自动发布或复制为数据库草稿。下一步须逐条核验法律状态和公开许可，再迁入官网媒体并审核。

## 验证

- 后端定向测试：`WebsiteIpItemPublicControllerTest`、`WebsiteIpItemServiceTest`、`WebsiteMediaPublicControllerTest`，Maven 退出码 0。
- 官网前端测试 5/5、生产构建通过。
- 后台 TypeScript 类型检查、生产构建通过。全量 ESLint 与定向 ESLint 在本机运行超过预期时间，已中止，本轮不记作通过。
- `git diff --check` 通过。

## 未执行

本轮未运行 Flyway migrate、未在真实数据库创建新表、未修改内部知识产权记录、未切换官网公开来源。部署时需先应用 V436，审核内容和文件后再同时启用后端与官网前端开关。
