# 官网 B1 产品数据与媒体完整性验收报告（2026-11-02）

范围：`corporate-website/` 当前本地工作区（含未提交改动）。只读校验 + 自动化脚本，不做浏览器/真机视觉验收，不对产品名称、参数、公司事实做真伪判定。机器可读结果见 `docs/website-content-validation-20261102.json`。

结论：**发布闸门未通过**。结构性数据关系正确，但 40 条产品文案仍是生成式占位句、7 个官网媒体 ID 无实际文件、已提交的旧构建产物 sitemap 仍含包材下线路由、且没有任何业务审核签署记录。按审查原则「涉及业务事实不擅自补全」，本轮不修改任何业务内容，只建立可重复校验与报告。

---

## 一、先做差异对照：GitHub `main` 审查结论 vs 本地工作区

本地 `main` 与 `origin/main` 一致于 `b2ae1f12`，但工作区有 18 个官网文件的未提交改动，以及 `scripts/`、`src/utils/publicSummary.js`、若干测试、`website-build-check/`、`docs/website-static-media-readiness-20261102.md` 等未跟踪内容。审查文档基于 GitHub `main`，以下按本地实际代码修正判断。

| 审查项 | GitHub main 结论 | 本地工作区实际 | 本轮处理 |
| --- | --- | --- | --- |
| P0 旧档案图片路径 | 仍保留，待清理 | 仍保留（`website.js` 的 legacy 分支、`ProductDetail.vue` 的 legacy 图库分支）。但 native 产品已有 `product.slug !== undefined` 守卫，不会回退到 `/api/file-assets/product_model/`；`public/content/*.json` 全量扫描也**未出现**内部档案路径。后端 `application.yml` 默认 `product-source: native`，仅显式 `APP_WEBSITE_PRODUCT_SOURCE=legacy` 才回退 | 删除旧分支的前提是确认静态与 API 两模式、以及部署环境都不存在 legacy；留 B3 |
| P0 SEO `productLineName` 映射 | 临时对象 `m` 未赋值 | 确认属实：`ProductDetail.vue` 构造 `m` 时无 `productLineName`，`useSchema.js` 的 `createProductSchema` 用 `productLineName \|\| equipmentCategory` 拼 category，新版产品的 `productLineName` 恒空 | 留 B3 统一 `PublicWebsiteProduct` 时修 |
| P1 产品筛选交互 | 待完善 | 本地已实现「更多筛选」折叠 + 已选条数（`Products.vue`）。仍有两个真实问题：`loadProducts()` 无请求序号，快速切换筛选时旧响应可覆盖新结果；`syncQuery()` 全部用 `router.replace`，浏览器前进/后退不保留筛选历史 | 留 B2，本轮不改 |
| P1 包材下线 | 公开路由已下线，旧组件仍在 | 三条旧路由均重定向 `/products`；`snapshot.js` 对 `/api/website/packaging` 返回 `[]`；导航已移除。`Packaging.vue` 仍在且未标注废弃 | 按建议保留文件；B1 把数据残留列为告警 |
| SEO/GEO | 有配置，抓取有风险 | 本地已有构建期预渲染 `scripts/prerender-products.mjs`（45 个产品 HTML + sitemap），`website-build-check/` 有完整产物；但 `dist/` 是旧产物（0 个产品 HTML，sitemap 仍含 `/supplies`、`/packaging`）；`useSeo.js` 与预渲染脚本域名写死 | 留 B6 |
| 联系方式 | 有兜底值和服务承诺 | 本地已把「24 小时内响应」改为「设备选型与样品试封咨询」；电话/邮箱兜底仍硬编码（值与 `company.json` 一致），还有「工程师免费提供样品试封测试」等承诺 | 列入业务确认清单 |
| 静态快照 | 45 产品 / 139 媒体，需校验数据 | 数量核对一致：45 产品、45 详情、139 清单媒体、139 实际媒体文件、无游离文件。但存在 7 个未映射媒体 ID 与 40 条占位文案 | B1 本报告 |

---

## 二、校验方法与可重复执行方式

新增只读脚本 `corporate-website/scripts/validate-content.mjs`，并接入：

- `npm run validate:content`：生成 `docs/website-content-validation-20261102.json`，打印逐项 PASS/FAIL/WARN。
- `node scripts/validate-content.mjs --strict`：存在 error 级问题或发布闸门未通过时退出码 1，供 CI/发布流水线使用。
- `corporate-website/tests/content-validation.test.mjs`：2 个测试，钉住结构性不变量与「已知缺口不会被静默忽略」。

校验口径：产品列表 ↔ 详情双向对应、列表/详情字段一致性、全站内容（8 份 JSON）中的媒体 ID → `websiteMediaManifest.js` → `public/content/media/` 物理文件、占位宣传句/无依据承诺、下线内容（路由/导航/快照/页面区块/sitemap/精选）、构建产物覆盖、发布审核签署文件。

---

## 三、逐项结果

| 检查 | 结果 | 数量 | 说明 |
| --- | --- | --- | --- |
| `content.files` 内容快照读取 | PASS | 0 | 8 份 JSON 均可解析 |
| `product.slug` slug/ID 唯一性与格式 | PASS | 0 | 45 个 slug 合法且无重复 |
| `product.detail` 列表 ↔ 详情双向对应 | PASS | 0 | 45 列表 / 45 详情，一一对应 |
| `product.media` 产品媒体 ID 在清单中 | PASS | 0 | 卡片图、主图、全部图库项均映射成功 |
| `product.copy` 占位文案/无依据承诺 | **FAIL** | 40 | 见 B1-01 |
| `product.fields` 展示字段完整性 | WARN | 2 | 见「非阻断告警」 |
| `featured.slugs` 精选/首页产品指向已发布 slug | PASS | 0 | 12 + 12 条全部有效，无下线内容 |
| `featured.flags` 精选标记口径 | WARN | 1 | 43 条 `featured=true` vs 精选快照 12 条 |
| `content.archiveUrls` 内部档案资源引用 | PASS | 0 | 公开内容不含 `/api/file-assets/` 或 `defaultImageAssetId` |
| `media.references` 全站媒体 ID → 实际文件 | **FAIL** | 7 | 见 B1-02 |
| `media.manifest` 清单条目 → 物理文件 | PASS | 0 | 139 条全部存在且非空，文件名与 ID 对应 |
| `media.orphans` 游离媒体文件 | PASS | 0 | 无 |
| `retired.content` 下线内容残留 | WARN | 2 | `page-sections.json` 仍含 `PACKAGING` 区块与 `/molds` 链接 |
| `retired.routes` 旧路由仅跳转 | PASS | 0 | 三条旧路由均 redirect `/products`，导航无引用 |
| `retired.snapshot` 包材快照接口 | PASS | 0 | 返回空列表 |
| `retired.sitemap` sitemap 下线路由 | **FAIL** | 2 | 见 B1-03 |
| `seo.prerenderCoverage` 预渲染覆盖 | PASS | 0 | `website-build-check`：45/45 HTML + 45/45 sitemap 产品条目；`dist`：0/45 |
| `content.solutionArticles` 方案文章 | WARN | 1 | `solutions.json` 无 ARTICLE 分组，`/solutions/:slug` 当前无文章 |
| `content.companyGaps` 公司资料空字段 | INFO | 6 | brandSlogan/mission/vision/values/factoryAddress/enterpriseScale |
| `contact.fallback` 联系方式兜底 | WARN | 1 | 硬编码 4009008878 / 768963166@qq.com |
| `claims.inventory` 待业务确认表述 | INFO | 7 组 / 239 处 | 品质保障 208、样品试封 15、非标定制 6、一站式 5、支持定制 3、免费 1、ISO9001 1 |
| `seo.siteUrl` 域名可配置 | WARN | 2 | `useSeo.js` 与预渲染脚本均写死中文域名 |
| `review.manifest` 发布审核闸门 | **FAIL** | 1 | 见 B1-04 |

**发布闸门：未通过**，阻断项 `product.copy`、`media.references`、`retired.sitemap`、`review.manifest`。

---

## 四、四项阻断问题（须处理后才能进入正式发布产物）

### B1-01 · 40/45 产品仍是生成式占位宣传句

`products.json`、`featured.json`、`home.json`、`product-details.json` 中 40 条产品的 `summary` 与 `seoDescription` 均为「{产品名}，专业{类别}，品质保障。」。

影响：

- `src/utils/publicSummary.js` 的 `reviewedSummary()` 在运行时隐藏该句，页面简介不显示；但预渲染脚本的 SEO 描述退化为通用兜底 `「{name}｜{category}。查看设备图片与已公开技术参数。」`，社交分享/抓取拿不到真实卖点。
- 45 条详情中仅 5 条有 `descriptionHtml`，40 条「产品介绍」区块不渲染。
- `claims.inventory` 中 208 处「品质保障」主要来自这批文案。

处理：由销售/产品负责人逐条填写并审核「名称、公开型号、简介、SEO 描述、正文」；脚本不自动补全、不生成宣传承诺。文案替换后重跑 `npm run validate:content`，`product.copy` 应归零，`claims.inventory` 应显著下降。

### B1-02 · 7 个官网媒体 ID 无对应文件（140–146）

全站共引用 146 个不同媒体 ID，清单只有 139 个。缺失引用全部来自 `page-sections.json`，与 `docs/website-static-media-readiness-20261102.md` 记录一致：

| 页面区块 | 缺失 ID |
| --- | --- |
| HOME / showcase | 141、142、143 |
| PRODUCTS / hero | 144 |
| SOLUTIONS / hero | 146 |
| ABOUT / hero | 145 |
| ABOUT / intro | 143、145 |
| ABOUT / manufacturing | 140、141、142、143 |

`snapshot.js` 的 `rewriteMediaUrls` 会把无法映射的官网媒体 URL 改写为空字符串，因此**不会**回退请求内部档案图片；但会导致 Hero / 制造展示图缺失，`Home.vue` 只展示有有效图片的记录。

处理：从官网独立媒体资产取回这些 ID 的实际文件，确认画面内容与授权后复制到 `public/content/media/` 并更新 `websiteMediaManifest.js`，重跑测试、构建与页面视觉检查。禁止按 ID 猜测图片，禁止改引用内部档案文件。

### B1-03 · 已提交的旧构建产物 `dist/sitemap.xml` 仍含下线路由

- `public/sitemap.xml`：4 个 URL，干净。
- `website-build-check/sitemap.xml`：49 个 URL（4 + 45 产品），干净。
- `corporate-website/dist/sitemap.xml`（已纳入版本控制的旧产物）：6 个 URL，仍含 `/supplies`、`/packaging`；`dist/products/` 下 0 个产品 HTML。

处理：属 B6「静态预渲染与部署验证」范围。统一重建发布产物，并确认部署源是重建后的目录而不是仓库里的旧 `dist`；不要只手改这一份 sitemap，避免 JS 资源与 HTML 版本错配。

### B1-04 · 没有业务审核签署文件

`corporate-website/content-review.json` 不存在。脚本据此把 45 条产品、公开联系方式、公开服务承诺全部标记为「待审核」，发布闸门保持阻断。这是按审查要求「未审核内容不能进入正式发布产物」实现的闸门，不是脚本缺陷。

签署文件预期结构（脚本已内置校验，业务填写后自动生效）：

```json
{
  "reviewedAt": "2026-11-02",
  "reviewer": "业务审核人",
  "approvedProductSlugs": ["全部 45 个已发布 slug"],
  "approvedContact": true,
  "approvedPhone": "company.json 中的 phone",
  "approvedEmail": "company.json 中的 email",
  "approvedClaims": ["已逐条确认的公开承诺"]
}
```

签署后脚本会核对覆盖全部已发布 slug、联系方式与 `company.json` 一致、承诺清单已确认；未覆盖的产品仍会被单独报出。

---

## 五、非阻断告警（影响 B2/B3 设计，但不阻断数据关系）

- **参数与规格全空**：45/45 详情 `technicalParameters` 为空、45/45 `variants` 为空 → 详情页「技术参数」「可选规格」区块对全部产品不渲染。B3 收口展示模型时应按「无审核资料则隐藏」处理，不要填假参数。
- **筛选标签几乎为空**：44/45 产品 `applicationTags` 为空、44/45 `containerTags` 为空 → B2 的「更多筛选」里「包装产品」「包装容器」两个下拉几乎无可选值。需业务补标签，否则交互优化也是空壳。
- **数据瑕疵 2 处**：`product-80` 的 `capacityLevel` 为空字符串（应为 `null`）；`product-128` 的 `publicModel` 为 `LQ-A14-F6 `（尾部空格）。
- **精选口径不一致**：`products.json` 中 `featured=true` 43 条，精选快照 `featured.json` 只有 12 条。12 条均已发布且无下线内容，功能正确，但标记语义需确认。
- **下线内容数据残留**：`page-sections.json` 仍保留 `PACKAGING` 页面区块与首页 showcase 中的 `/molds` 链接；`Home.vue` 已在渲染前过滤 `/molds`，导航、路由、快照接口均已下线。建议 B6 清理内容快照时一并删除，不要恢复展示。
- **方案文章为空**：`solutions.json` 无 `ARTICLE` 分组，文章路由存在但无内容；B5 内容审核时处理，不能编造客户案例。
- **公司资料空字段 6 项**、**联系方式硬编码兜底**：需业务确认后迁到独立官网内容配置；实际收信与拨号仍需人工测试。
- **公开承诺清单**：239 处待确认表述，含「样品试封」「非标定制」「一站式」「免费」「ISO9001」等。审查明确要求不得自动写「支持非标定制与样品试封」这类通用承诺；本轮仅出清单，不删不改。
- **结构化数据存在无依据字段**：`useSchema.js` 的 `createProductSchema` 无条件写 `priceCurrency: CNY`、`availability: InStock`，但公开数据没有任何价格/库存字段；预渲染 HTML 的 JSON-LD 不含该 offers 节点，导致运行时与静态抓取的结构化数据不一致。B3 统一模型时一并处理。
- **域名写死**：`useSeo.js`、预渲染脚本、`public/robots.txt`、静态 sitemap 均固定 `https://www.liqiangbz.com`；`index.html` 的 `og:image` 还是相对路径。B6 按部署域名生成，英文站不得复用中文 canonical。
- **既有测试环境兼容（本轮顺带修复）**：`prerender-products.test.mjs` 原先因 `fs.rm({recursive:true})` 被当前环境的 `safe-delete` 拦截而失败。已改为在系统临时目录建产物、并用 `readdir/unlink/rmdir` 逐层清理（不改任何断言）。修复后 `npm test` 全量 14/14 通过，本轮新增的 2 个 B1 测试也在其中。

---

## 六、验收边界与交接

已验证：静态 JSON 结构、slug↔详情、媒体 ID↔文件、下线内容隔离、`--strict` 闸门退出码为 1、`npm test` 全量 14/14 通过。本轮代码改动仅涉及校验脚本、测试与 `package.json` 的一个 npm script，未改任何 Vue 页面/CSS/业务数据，因此未重跑 Vite 生产构建；完整构建与抓取验证属 B6。

未验证：浏览器实际视觉效果、320/390/768/1024/1440px 五种宽度、图片内容/版权、真实收信与拨号、搜索抓取、45 条产品业务事实真伪。代码正确 ≠ 数据正确 ≠ 视觉验收。

交接顺序：

1. **B1 收尾**：业务按 B1-01/B1-02/B1-04 填写审核文案、补齐 7 个媒体文件、签署 `content-review.json`；B6 重建产物时清掉 B1-03。
2. **B2 产品中心交互**：除折叠筛选外，需补 `loadProducts()` 的请求序号防竞态，并把筛选 URL 改为 `router.push`（分类/条件变化）与 `router.replace`（搜索输入）区分，保证前进后退恢复筛选；桌面/手机截图另做视觉验收。注意 44/45 空标签问题先有结论。
3. **B3 产品详情模型收口**：统一 `PublicWebsiteProduct` 展示模型，修 `m.productLineName`、清理旧档案图片分支（前提是确认部署环境无 `APP_WEBSITE_PRODUCT_SOURCE=legacy`）、处理结构化数据 offers 与运行时/预渲染不一致；输出详情字段与媒体来源对照表。
4. 三项完成后再进入 B4/B5 视觉与内容审核、B6 预渲染与抓取验证。
