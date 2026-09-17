# 产品档案管理系统 · 功能设计梳理（2026-08）

> 本文基于当前实际代码（backend 迁移至 V62、前端 11 个一级路由、统一文件行为子系统落地后）梳理。
> 与根目录 `PROJECT_ARCHITECTURE.md` / `PROJECT_DEEP_ANALYSIS.md`（2026-07-16 版本）相比，本文反映重构后现状。
> 最后更新：2026-08-11

---

## 目录

1. [系统全景](#一系统全景)
2. [产品管理](#1产品管理productcatalogview核心工作台)
3. [包材管理](#2包材管理packagingview)
4. [模具资产](#3模具资产packagingmoldview台账最重)
5. [自媒体账号](#4自媒体账号mediaaccountview)
6. [知识产权](#5知识产权certificateview全生命周期台账)
7. [公司信息与资质合规](#6公司信息--资质合规companyinfoview--companycertificationsview)
8. [文件库（统一文件资产）](#7文件库filelibraryview统一文件资产中枢)
9. [软件管理](#8软件管理softwareview)
10. [物品资产](#9物品资产officeassetview)
11. [密码管理](#10密码管理passwordmanager)
12. [定价与报价](#11定价与报价嵌入产品管理)
13. [横切工作流](#三横切工作流跨模块统一机制)
14. [设计取向与已知缺口](#四设计取向与已知缺口)

---

## 一、系统全景

### 页面结构（`frontend/src/router/index.ts`）

```
11 个一级页面
├─ 工作台型 (master-detail)    产品管理 / 包材管理(列表+抽屉)
├─ 列表型 (list)              自媒体账号 / 知识产权 / 文件库 / 软件管理 / 资质合规 / 物品资产 / 密码管理
├─ 详情型 (detail)            公司信息
└─ 导航子菜单                  公司信息 → 资质合规
```

### 分层与横切

- 前端：`Views → Composables(≈80+) → api/`
- 后端：`Controller(20) → Service(57) → Repository(47) → Entity(52)`
- 数据库：Flyway 迁移驱动，当前 V62，历史含两套编号（V1_x 与 V2~V37）已冻结
- 横切能力：统一文件资产管理（file-assets）、搜索栏批量管理（FieldBatchEditModal + PUT /batch）、软删除/回收站治理、审计日志、定时归档调度

---

## 2️⃣ 产品管理（ProductCatalogView，核心工作台）

### 数据模型

```
ProductModel（型号）→ ProductVariant（SKU 规格）→ ProductAsset（素材，11 类）
                          ├─ product_selling_points / machine_components / faqs / consumable_relations
                          └─ product_documents（跨产品复用的文件库关联）
```

### 操作流程

```
搜索/筛选（关键词 + 产线 + 车间 + 设备大类 + 状态 + 待办筛选）
  → 列表（两种视图：workbench 左列右详情 / browse 卡片）
  → 选中型号 → 详情 3 个 Tab：概述 / 规格(SKU) / 素材
  ├─ 概述: 档案健康度检查（ARCHIVE_CHECKS）+ 引导动作（补主图/说明书/图纸等）
  ├─ 规格: SKU 增删改 / 克隆 / 拖拽排序 / 批量编辑 / 移动到其他型号
  └─ 素材: 上传（点击/拖拽/粘贴）→ 分类 → 标签 → 设默认图 → 排序
          → 跨型号/变体移动 → 批量操作 → 从文件库关联
  ├─ Excel 导入/导出 / 下载模板 / 素材打包导出（导入走 product_import_staging V43）
  └─ 删除 → 回收站（30 天）→ 永久删 / 恢复
```

### 关键逻辑

- 列表 N+1 优化：`variantCount` / `assetCount` 一次批量聚合；计算字段在内存排序。
- 软删除：`@SQLRestriction("deleted_at IS NULL")` 自动过滤。
- `repair-assets-from-storage`：根据 storage 目录重建素材记录的自愈接口。
- 文件库关联：素材 Tab 支持「从文件库关联」，`product_documents` 跨产品复用通用文件。
- 前端拆成 20+ composables（`useCatalogModels/Editors/Filters/AssetOperations/BatchOps/MoveOperations` 等），弹窗统一 `useDialogStack`（Esc 按优先级关闭）。

---

## 3️⃣ 包材管理（PackagingView）

### 数据模型

```
PackagingModel → PackagingVariant → PackagingAsset
      └── PackagingVariantHistory（规格历史，V30）
```

### 操作流程

```
列表筛选（种类 / 材质 / 状态）
  → 详情（规格卡片 + 素材）
  → 新增 / 编辑型号、规格（复制 / 移动 / 排序）
  → 批量删除 / 批量改字段
  → 回收站（型号级 + 规格级各自独立回收站）
```

### 特色逻辑

- 数据质量报告 + `auto-normalize`（形状 / 容量规范化）。
- `PackagingSpecResolver` 规格解析器。
- 模具汇总卡片：包材详情规格卡片展示「默认模具 + 其他模具总数」（联动模具资产模块）。

---

## 4️⃣ 模具资产（PackagingMoldView，台账最重）

### 数据模型

```
PackagingMold（编号/出模数/刻字版本）
  ├── PackagingMoldGroup（规格族，单一真相源）
  ├── MoldVariantLink（模具 ↔ 规格关联，含 default 标记）
  ├── MoldAccessory（配件）
  ├── PackagingMoldHistory（履历）
  └── MoldImportBatch{/Item}（导入批次 + 逐行明细，V61）
```

### 核心工作流 —— 导入三阶段闭环

```
1) 预览 import/preview  : 上传 Excel → 解析 → 四分类（新增/合并/冲突/待确认）→ 不落库
2) 确认 import/confirm  : 前端回传 preview + 选中行 → 无状态落库 → 生成批次号 + 逐行明细
3) 归档/回滚            : 批次汇总 → rollback-assessment（评估可回滚性）→ rollback（安全回滚）
```

### 数据完整性治理（双管齐下）

- 主动接口：`audit-quality`（只读审计报表）+ `audit-quality/fix`（一键修复）。
  - 双归属不一致：`mold.model_id` 回填为 `group.model_id`
  - 重复默认模具：保留最早创建的默认关联，其余置为非默认
- 定时兜底：05:00 `scanAndFixPackagingMoldIntegrity`（与写路径 `resolveOwnership` 保持一致）。

另支持：按模具 / 按规格反查（`by-variant/{id}`）、按系列汇总规格模具（`summarize/by-model`）、批量删 / 批量改字段、回收站。

---

## 5️⃣ 自媒体账号（MediaAccountView）

### 数据模型

```
MediaAccount + MediaAccountUpdate（更新记录）
             + MediaAccountSnapshot（时间序列快照）
             + MediaPublishTask（发布任务）
```

### 操作流程（运营台账）

```
账号 CRUD（平台 / 运营人 / 发布负责人 / 标签）
  ├─ 手动录入更新记录，或批量「今日更新」
  ├─ 从发布导出 Excel 导入更新记录（预览 → 确认 两段式）
  ├─ 更新频率摘要 + 单账号更新日历（90 天）+ 最近时间线（默认 30 天）
  ├─ 数据分析（30/90 天）：趋势图、平台对比、运营人排行、热度点、快照历史
  ├─ 发布任务、发布负责人分配
  └─ 批量改字段 / 批量删 / 导出 Excel
```

### 关键逻辑

- `STALE_THRESHOLD_DAYS = 14`：超 14 天未更新标记「待关注」。
- 计算排序字段（`updateCount` / `weekUpdates`）内存排序。
- `BATCH_EDITABLE_FIELDS` 白名单批量改字段。
- 明确不迁移搜索栏批量管理：自定义域批量弹窗（选中 vs 全部筛选范围）。

---

## 6️⃣ 知识产权（CertificateView）—— 全生命周期台账

### 数据模型

```
Certificate（专利 / 软著 / 商标 三类别）→ CertificateFeeRecord（缴费记录）
```

### 缴费工作流（内嵌手册章节）

```
缴费前 → 对照 payment-checklist（8 项）核对 → 帮缴 / 自缴
  → 新增缴费记录 FeeRecord
  → 自动回写证书：feeQueryDate = +1 年，feeDueDate = +1 年，
                  lastFeeAmount，feeStatus = 已支付
  → 缴费后检查清单（4 项）+ 归档清单（4 项）→ 台账就绪
```

### 关键逻辑

- 状态枚举中英转换：前端中文 ↔ DB 英文（`AssetStatus` / `FeeStatus`），批量更新时 `toDbStatus`。
- 年费自动计算策略：`AnnualFeeStrategyFactory` + Invention / Utility / Design 三类费率表（如发明 1-3 年 900 元）。
- 到期自动计算：`expireDate = applyDate + legalYears`。
- Stats 三合一到期提醒：专利年费到期 + 专利/软著保护期到期 + **企业资质到期**（跨模块聚合 `CompanyCertification`，查找范围：逾期 1 年 ~ 未来 180 天）。
- 回收站 7 端点完整闭环（2026-07-30 第一批治理落地：软删 + 审计 + 自动清理）。

---

## 7️⃣ 公司信息 + 资质合规（CompanyInfoView / CompanyCertificationsView）

### 八大子资源

```
info（公司简介） / accounts（财务账户） / kb（知识库分类 + 文章，带浏览量）
team-members（团队成员） / milestones（发展历程）
assets（品牌资产，软删 + 回收站） / certifications（资质合规）
```

### 资质合规特色

- 检测报告 / 营业执照 / ISO 等时效性合规文件。
- 商标字段（V58）、永久资质（V59）。
- 批量删除 / 批量改分类 / 通用批量改字段全支持。
- 到期自动合并进知识产权「即将到期」报警。

---

## 8️⃣ 文件库（FileLibraryView）—— 统一文件资产中枢

V62 将旧 Document 重构为通用文件库，配套**统一文件行为子系统**（全系统最重要的横切设计）。

### 统一契约

```
/api/file-assets/{ownerType}/assets/{assetId} ::
  预览 / 下载 / 缩略图 / 打开所在文件夹 / 本地路径 / 标签 / 设默认 / 排序
  / 改分类 / 移动归属 / 软删 / 恢复 / 物理删

8 种 Owner：product_model / product_variant / packaging_model / packaging_variant
           / company / certificate / document / software
每个 Owner 由 Adapter（FileAssetAdapter）声明 Capabilities（能力集）
```

### 边界规则

- 旧 `api/file/file.ts` 收敛为 3 个桥接函数：raw 上传 / 导入浏览 / 兜底打开文件夹。
- Office 高保真预览：后端异步转 PDF + 状态轮询（`preview/pdf/status`），缓存 30 天由定时任务清理。
- 文档本体：标题 / 分类 / 标签 / 封面 / 替换文件 / 下载 / 预览（按扩展名映射 MIME）/ 回收站 / 批量。

---

## 9️⃣ 软件管理（SoftwareView）

### 特色工作流 —— 本地文件夹扫描导入

```
输入文件夹路径 → preview-import（扫描返回文件列表）→ 勾选 → import-from-folder（导入所选）
```

支持：安装状态标记、下载、Excel 导出、导入历史、分类筛选、回收站、批量（删除 / 恢复 / 改分类 / 改安装状态 / 通用改字段）。

---

## 🔟 物品资产（OfficeAssetView）

- 资产档案（编码 / 分类 / 状态 / 部门 / 采购 / 保修）。
- 扫编码定位（`GET /scan?code=`）。
- **动作记录**：`POST /actions` → `OfficeAssetRecord` 形成履职履历。
- 批量改字段。

---

## 1️⃣1️⃣ 密码管理（PasswordManager）

```
PasswordCategory → PasswordEntry
AES-256 加解密（CRYPTO_SECRET_KEY，需 32 字节 Base64）
  ├─ reveal（查看明文，审计 PASSWORD_REVEAL）
  ├─ 历史变更（PasswordHistory）
  ├─ 导出 JSON / CSV（审计）
  ├─ 导入 / 批量移动分类
  └─ 通用批量改字段（密码字段不进白名单）
```

---

## 1️⃣2️⃣ 定价与报价（嵌入产品管理，后端 Price / Quote 两组 API）

### 价格方案工作流

```
按产品建 Plan（方案/版本/币种/生效日/状态 DRAFT→ACTIVE）
  ├─ Tier（档位）：按形状（圆形/方形/不规则）+ 尺寸区间 + 经销/零售价，区间重叠校验
  └─ Addon（选配）：FIXED / 按基础价 % / 按数量，互斥组，需人工确认标记
Excel：整方案导出 / 档位导入 / 选配导入
```

### 报价计算逻辑（`calculateQuote`）

```
输入：型号 + 形状 + 长宽或直径 + 数量 + 经销/零售 + 选配 code 列表 + 人工调整金额
匹配档位（priority 倒序 → id）→ 未命中返回 noMatch 说明
基础价 × 数量 + Σ选配（FIXED/PERCENTAGE/QUANTITY） + 人工调整 = 最终价
互斥组只取首个；requiresManualConfirm 汇总标记
```

### 报价快照（QuoteSnapshot 独立表）

一键存报价 → 按产品 / 客户 / 状态 / 日期范围检索 → 状态流转（跟进 / 成交 / 关闭）。

---

## 三、横切工作流（跨模块统一机制）

### 🅐 搜索栏批量管理（2026-08 试点后大面积推广）

统一模式：

```
SearchToolbar 进批量模式
  → 全选 / 单选（useBatchSelection，Set<number>）
  → FieldBatchEditModal（Schema 驱动，勾选字段）
  → 后端 PUT /batch（字段白名单 + 枚举校验 + 单事务 + 审计日志）
  → 移除旧的 BatchActionBar / prompt 逻辑
```

已接入：产品 / 包材 / 模具 / 知识产权 / 文件库 / 软件 / 物品资产 / 密码 / 自媒体 / 资质合规。
明确不迁移：自媒体（自定义域弹窗）、定价（死 UI）。

### 🅑 生命周期与治理（软删除矩阵）

```
删除 → 软删除（deleted_at，@SQLRestriction 自动隐藏）
  → 回收站（各实体 trash API：list / stats / restore / batch-restore / permanent / empty）
  → 满 30 天 → 定时：
     02:00 产品软删迁入 _archive 表物理清主表
     03:00 各类回收站 autoCleanTrash + 审计日志 90 天清理
     04:00 Office 预览 PDF 缓存清理
```

各域覆盖进度：产品 / 文档 / 软件 / 证书 ✅ 完整；包材 ✅（无自动清理）；模具 ✅；公司品牌资产 ⚠️（仅软删无完整回收站）；其余硬删（自媒体 / 密码 / 定价，密码靠自身 history 兜底）。

### 🅒 审计日志

`AuditLogService` + coverage 矩阵（见 `PROJECT_ARCHITECTURE.md` 尾部表格）：

- 已接入：产品 / 文档 / 软件 / 证书 / 密码 / 定价 / 产品辅助 / 统计 / 模具完整性修复。
- 缺失：自媒体 / 公司 / 包材。
- 令牌化日志：`CERT_CREATE`、`PKG_MOLD_INTEGRITY_FIX`、`PASSWORD_REVEAL` 等。

### 🅓 前端应用骨架

- 多标签页（KeepAlive + `refreshNonce` 强制刷新）。
- 顶部进度条、全局状态横幅、错误边界（ErrorBoundaryOverlay）。
- 全局预览画廊（LqPreview 上 / 下一张）。
- 路由守卫：脏数据离开确认（`useDirty`）。
- 详情 master-detail 工作台（窄屏降级为抽屉）+ `detailSuspended`（弹窗互斥防遮挡）。

### 🅔 定时任务（ArchiveScheduler，统一收口，cron 可配置）

```
02:00 产品软删数据归档（迁入 _archive 表后物理删除主表行）
03:00 回收站 30 天清理 + 操作日志 90 天清理
04:00 Office 预览 PDF 缓存清理
05:00 包材模具数据完整性扫描与自动修复
```

---

## 四、设计取向与已知缺口

### 设计取向

1. **数据库即单一真相**：无独立 schema 文件，一切以 Flyway 迁移为准（V1→V62）；历史两套编号已冻结，新迁移取整数递增。
2. **文件行为收敛于统一契约**：业务表不动、文件行为统一走 file-assets（8 个 Adapter 声明能力集）。
3. **批量改字段收敛为一条白名单模式**：新建页面照抄 `FieldBatchEditModal` + `PUT /batch` 模板即可。
4. **定时任务收口单类**：`ArchiveScheduler`，按时间错峰执行。
5. **前端口袋化组合式函数**：超大视图（ProductCatalogView / PackagingView / MediaAccountView / CompanyInfoView）靠 composables 拆分降重。

### 已知缺口（来自治理矩阵）

- 自媒体 / 公司 / 模具的审计未接入（模具导入审计已部分）。
- 包材无自动回收站清理。
- 展会 / 定价无回收站（展会与内容选题相关表已随 V45 / V46 删除，实体不再包含）。
- 旧文档（PROJECT_ARCHITECTURE / PROJECT_DEEP_ANALYSIS）中的内容选题库、说明书 AI 生成、定价历史等能力在当前代码中已不存在或已迁移。

---

*相关文档：`docs/design/unified-file-behavior-boundaries.md`、`docs/design/company-management-design.md`、`PROJECT_ARCHITECTURE.md`。*
