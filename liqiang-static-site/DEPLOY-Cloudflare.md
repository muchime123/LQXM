# 利强包装 · 静态官网部署到 Cloudflare Pages

本目录下的 `dist/` 是**完全自包含的纯静态多页站**（无后端、无数据库、无构建依赖），可直接部署到 Cloudflare Pages 免费版。

## 本地预览
```bash
cd liqiang-static-site
npx serve dist          # 或任意静态服务器
# 打开 http://localhost:3000
```

## 部署步骤（Cloudflare Pages · 免费）
1. 登录 Cloudflare 控制台 → Workers & Pages → Create → Pages → Connect to Git（推荐，自动构建）
   - 若用 Git 连接，把 `dist` 目录放进仓库；Framework preset 选 **None**，Build command 留空，
     Build output directory 填 `dist`（或直接用 Wrangler 上传 dist）。
2. 或者直接拖拽上传：Workers & Pages → Create → Pages → Upload assets，选择 `dist` 文件夹。
3. 绑定自定义域 `liqiangpacking.com`（域名已在阿里云注册，按 Cloudflare 提示添加 CNAME/NS 记录）。
4. 首次部署后，在 Pages 项目的域设置里把 `liqiangpacking.com` 设为自定义域名，DNS 指向 Pages。

## 说明
- 全部页面为真实 .html（利于 SEO），已附带 `sitemap.xml` 与 `robots.txt`。
- `_redirects` 已配置：`/products/{slug}` 等无后缀路由会 200 转发到对应 .html，兼容旧书签。
- 询盘表单为**无后端降级方案**：提交时通过 `mailto:` 组装邮件发送到 768963166@qq.com，不依赖任何服务端接口。
- 图片已按 Web 优化（最大边 1400px、JPEG q85），单个文件均远低于 Cloudflare 25MB 限制。
- 若日后接入真实表单后端/CRM，可替换 `contact.html` 中表单的提交逻辑即可。
