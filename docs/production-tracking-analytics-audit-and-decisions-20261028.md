# LQXM 生产跟单三大分析抽屉设计审计与整改决策（2026-10-28）

> ## ⏸️ 状态：远期设计储备 · 暂缓实施（2026-10-28 定）
>
> **不要按本文启动开发。** LQXM 生产跟单当前阶段定位于「把工作台做好」——让跟单员方便地记录、查询、跟进，而非建设 MES / 排程 / 交期预测体系。
>
> **保留价值**：本文「一、已确认事实」是已核实的代码事实，继续有效，供未来参考。
> **暂缓范围**：快照整改、节点流程模型改造、并行预测算法、参考工期扩建——均不在当前阶段推进。
>
> **唯一需守住的红线**：历史快照功能当前无安全权限边界，**不得直接开放或自动启用**（见 1.3）。在其接通前，该入口应保持不可达或明确隐藏。
>
> ---
>
> **本文性质：设计审计与决策输入，只读。**
> 本轮**不执行任何 Flyway 迁移、不修改生产数据库、不改变现有跟单业务逻辑、不改业务代码**。
> 待下文「二、待业务定夺」六项确认后，再分别制定《快照整改方案》与《节点流程模型改造方案》。
>
> **审计范围**：三个分析抽屉及其后端分析服务
>
> | 抽屉 | 前端 | 后端 |
> | --- | --- | --- |
> | 生产交期达成分析 | `apps/archive/views/production/ProductionTrackingAnalyticsDrawer.vue` | `ProductionTrackingAnalyticsService#analyze` |
> | 节点完成率 · 排产等待分析 | `apps/archive/views/production/ProductionTrackingNodeAnalyticsDrawer.vue` → `components/production/TrackingNodeAnalytics.vue` | `ProductionTrackingAnalyticsService#nodeAnalyticsView` |
> | 跟单周复盘 | `apps/archive/views/production/ProductionTrackingWeeklyReviewDrawer.vue` | `ProductionTrackingWeeklyReviewService` |
>
> **证据来源**：本地工作区源码逐行核对。未做浏览器运行验证、未连库取数，凡涉及真实数据量的条目均在「三、待补数据」中列出待执行的核查语句，结论不等同于运行验收。

---

## 0. 结论摘要

本轮审计推翻了上一轮的两个判断，并新增三项模型层硬约束。

**上一轮判断的更正**

| 上一轮结论 | 本轮核对结果 |
| --- | --- |
| P0「排产等待 >3 天边界不一致」 | **不成立，降级 P3**。概览卡、合并柱、两处下钻各自标签与计算均自洽（详见 1.10）。 |
| 「节点记录完成率的语义可改称『数据记录完备度』」 | **错误，已撤回**。`done / involved` 的分母是"有该节点记录的单"，不能衡量"应有记录是否完整"。真正的完备度必须以**适用节点数**为分母（详见约束 4.1）。 |

**本轮认定的最高优先级问题**

| # | 问题 | 级别 | 性质 |
| --- | --- | --- | --- |
| 1.1 | 历史快照链路从未接通：`snapshotForPeriod` / `readSnapshot` **零调用方**，前端「历史趋势」恒为空态 | P0 | 功能未生效 |
| 1.2 | 快照唯一键含可空 `workshop`，「全部车间」场景**不幂等**，可无限重复插入 | P0 | 数据正确性 |
| 1.3 | 快照无权限维度，而分析结果按用户数据范围收敛 ⇒ **共享快照必然越权** | P0 | 数据安全 |
| 1.4 | 节点完成率是**累计存续比**（分子分母只增不减、无时间窗），回答不了"卡在哪" | P0 | 统计口径 |
| 1.5 | 里程碑**无节点开始日期** ⇒ "节点停留时长"当前数据结构无法计算 | P0 | 模型约束 |
| 1.6 | 制程表是纯线性 `sort_no`，**并行工序无法表达** | P0 | 模型约束 |

---

## 一、已确认事实

### 1.1 历史快照链路从未接通（P0）

**链路现状**：表、写、读、列、前端组件**全部就位，唯独缺触发点**。

| 环节 | 位置 | 状态 |
| --- | --- | --- |
| 快照表 | `V421__production_tracking_analytics_snapshots.sql` | ✅ 已建 |
| 写快照 | `ProductionTrackingAnalyticsService:217-230` `snapshotForPeriod` | ✅ 已实现 |
| 读快照 | `ProductionTrackingAnalyticsService:235-249` `readSnapshot` | ✅ 已实现 |
| 列快照 | `ProductionTrackingAnalyticsService:255-277` `listSnapshots` | ✅ 已实现 |
| 后端端点 | `ProductionTrackingController:169-173` `GET /weekly-review/history` | ✅ 已暴露 |
| 前端组件 | `components/production/TrackingReviewHistory.vue` | ✅ 已实现 |
| 前端挂载 | `ProductionTrackingWeeklyReviewDrawer.vue:102` | ✅ 已挂载 |
| **触发写快照** | —— | ❌ **零调用方** |

**根因**：`ProductionTrackingController:160-163` 的 `/weekly-review` 直接走 `weekly.review()`，而 `ProductionTrackingWeeklyReviewService:36` 只调 `analytics.analyzeUncut()` 现算，从不落库。

```160:163:backend/src/main/java/com/liqiang/archive/controller/ProductionTrackingController.java
    public Map<String,Object> weeklyReview(@RequestParam(defaultValue="") String fromDate,
            @RequestParam(defaultValue="") String toDate,@RequestParam(defaultValue="") String workshop){
        return weekly.review(fromDate,toDate,workshop);
    }
```

**可观测后果**：`TrackingReviewHistory.vue:9` 的空态文案是「暂无历史快照 —— 过去周期被复盘过一次后才会固化」，但**没有任何代码会去固化**，故该区块 100% 恒显示空态。

**次要事实（文档漂移，需一并更正）**：`ProductionTrackingAnalyticsService:212` 注释提到「Flyway 未执行 V419」，文档 `生产跟单三大分析抽屉设计梳理.md:45` 亦写作 V419。但 **V419 实际是 `V419__sealer_material_ready_stage.sql`（物料配齐节点）**，快照表是 **V421**——该注释写于快照迁移尚计划为 V419 之时，落地号变更未回改，属失效注释，不是"迁移未执行"。

---

### 1.2 快照唯一键在「全部车间」场景失效（P0）

`snapshotForPeriod` 用 `blankToNull(workshop)` 归一化车间参数，而该方法把空串折成 `null`：

```334:335:backend/src/main/java/com/liqiang/archive/service/ProductionTrackingAnalyticsService.java
    /** 空串归一成 null：展示侧（前端）用 null 判「—」，避免空串与「未填写」两种假值并存。 */
    private static String blankToNull(String value){return value==null||value.isBlank()?null:value.trim();}
```

V421 唯一键为 `UNIQUE KEY uk_ptas_period_ws (period_from, period_to, workshop)`，其中 `workshop` 可为 `NULL`。MySQL 多列 UNIQUE 中 **NULL 视为互不相同**，故 `ON DUPLICATE KEY UPDATE id=id` 对 `workshop IS NULL` 的行不命中。

| 场景 | workshop 实参 | 幂等性 |
| --- | --- | --- |
| 指定具体车间 | 非 NULL | ✅ 生效 |
| **全部车间（最常用）** | `NULL` | ❌ **不命中，可无限重复插入** |

**隐蔽性**：读路径 `readSnapshot` 与 `listSnapshots` 用的是 null-safe 的 `workshop <=> ?`（第 240、258 行），读行为正常；`readSnapshot` 又有 `LIMIT 1`，会稳定读到**最早那条**。因此表现为"功能正常、数字看着对"，实际固化时刻错误、库内堆积重复行。**不报错，只能通过 `GROUP BY ... HAVING COUNT(*)>1` 查出。**

---

### 1.3 快照无权限维度，共享必然越权（P0）

**分析结果本身是随用户变化的**：`loadRows` 第 288 行走 `tracking.visible(params)`，而 `visible()` 内含 `appendVisibility`，按 `MANUFACTURING` 功能数据范围（`ALL / DEPARTMENT / SELF / CUSTOM`）做行级收敛。

```531:548:backend/src/main/java/com/liqiang/archive/service/ProductionTrackingService.java
    StringBuilder visible(List<Object> params) {
        // 跟单范围：九月起提交的任务自动进入工作台。
        // 另有三类**例外**必须保留可见，否则九月前就在跟的单会突然从列表里消失、跟单记录随之失联：
        //   ① 手工加入跟单（enrollments）② 已被跟单进度表覆盖（workbook_rows）③ 已经真的产生过跟单事实。
        ...
        appendVisibility(where,params);
        return where;
    }
```

**快照键只有 `period_from + period_to + workshop`**（V421）。`generated_by_employee_id` 仅记录"谁生成的"，**不是过滤维度**。

⇒ 同一 `(周期, 车间)`，跟单员与跟单主管的 `dueTasks` 本就不同；共享一条快照，跟单员打开历史趋势即可看到主管口径的全量数字。**此项列为接通快照前的强制验收项。**

---

### 1.4 节点完成率是累计存续比，不是当前状态（P0）

```518:526:backend/src/main/java/com/liqiang/archive/service/ProductionTrackingAnalyticsService.java
        var nodeSql="SELECT COALESCE(NULLIF(p.production_line,''),'未分车间') workshop,"
                +"d.stage_code stage,COALESCE(d.stage_label,d.stage_code) stageName,"
                +"COUNT(DISTINCT p.id) involved,"
                +"COUNT(DISTINCT CASE WHEN m.stage_status='DONE' THEN p.id END) done "
                +"FROM production_orders p "+P_ASG_JOIN
                +"JOIN production_tracking_milestones m ON m.production_order_id=p.id "
                +"JOIN production_tracking_stage_defs d ON d.stage_code=m.stage_code AND d.enabled=1 "
                +"AND d.workshop_kind=CASE WHEN p.production_line LIKE '%封口%' THEN 'SEALER' ELSE 'MACHINE' END "
                +where+" GROUP BY workshop,d.stage_code,d.stage_label,d.sort_no ORDER BY workshop,d.sort_no";
```

**分母语义**：`involved` = 已产生该节点里程碑记录的单数。而里程碑**不是建单时预生成**的，是跟单员每次跟进时逐条 upsert 写入：

```2956:2962:backend/src/main/java/com/liqiang/archive/service/ProductionTrackingService.java
            for(var milestone:milestones){
                jdbc.update("INSERT INTO production_tracking_milestones(production_order_id,stage_code,stage_status,completed_date,note,manual_reference_date,manual_reference_reason) "
                                +"VALUES(?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE stage_status=VALUES(stage_status),"
```

**三条推论**：

1. 分子分母**都只增不减**，且 `nodeAnalyticsView` **没有任何日期/车间筛选**（`fetchTrackingNodeAnalytics()` 无参，仅 `visible()` 权限范围）。已完工一年的单永久留在分母中。
2. 完成率梯度主要是**工序先后顺序的投影**：靠前节点（备料/画图）天然高，靠后节点（入库/调试）天然低，因为它只计数"已走到这一步的单"。
3. ⇒ **"哪个节点最需要改善"这个问题，当前完成率回答不了**；按完成率排名会永远指向最后一道工序。

---

### 1.5 里程碑无节点开始日期（P0，模型约束）

```7:16:backend/src/main/resources/db/migration/V369__production_tracking_milestones.sql
CREATE TABLE production_tracking_milestones (
  production_order_id BIGINT NOT NULL,
  stage_code VARCHAR(30) NOT NULL,
  stage_status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
  completed_date DATE NULL,
  note VARCHAR(500) NULL,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (production_order_id, stage_code),
```

**只有 `completed_date`，没有任何"进入/开始该节点"的日期**（V402 增加的两个字段是预测值，见 1.6 与约束 4.2）。

⇒ **"节点停留时长"在当前数据结构下无法直接计算。** 唯一可用锚点：

| 锚点 | 字段 | 适用 |
| --- | --- | --- |
| 上游节点完成日 | `production_tracking_milestones.completed_date` | 非首节点 |
| 氚云排产时间 | `production_orders.schedule_time` | **首节点**（现行口径，见 1.7） |
| 提交日 | `production_orders.source_created_at` | 兜底 |

**`manual_reference_date` 不是开始日期**：

```2893:2896:backend/src/main/java/com/liqiang/archive/service/ProductionTrackingService.java
            if(milestone.manualReferenceDate()!=null){
                if("DONE".equals(milestone.status()))throw new IllegalArgumentException("已完成节点不需要人工预计日期");
                if(milestone.manualReferenceReason()==null||milestone.manualReferenceReason().isBlank())
                    throw new IllegalArgumentException("填写人工预计日期时请说明原因");
```

已完成节点禁止填、填写必填原因，且它覆盖的是系统按参考工期推算的 `systemReferenceDate`（`ProductionTrackingService:2483-2487`）。**它是"人工覆盖的节点预计完成日"，属预测值。**

---

### 1.6 制程表纯线性，并行工序无法表达（P0，模型约束）

```16:31:backend/src/main/resources/db/migration/V420__production_tracking_stage_defs.sql
CREATE TABLE IF NOT EXISTS production_tracking_stage_defs (
  ...
  sort_no INT NOT NULL COMMENT '制程顺序（升序）；决定节点条顺序与链式推算顺序',
  optional TINYINT(1) NOT NULL DEFAULT 0 COMMENT '1=按需工序：不计入完成分母，按关键词判定本单是否适用',
  match_keywords VARCHAR(200) NULL COMMENT '按需工序：可选配置/生产要求命中该正则即本单适用',
  exclude_keywords VARCHAR(200) NULL COMMENT '按需工序：命中该正则（否定表达）则本单不适用',
```

`sort_no` 是**单值全序**，且注释明确它同时承担"显示顺序"与"**链式推算顺序**"两个职责。V420 播种中 MACHINE 车间把 `SHEET_METAL(3) → MOLD(4)` 定义为先后关系。

⇒ 业务上并行的工序（如推杆机 / 转盘机的钣金加工与模具生产）**在数据模型中没有表达方式**。两层后果：

1. 节点完成率的排序对并行节点无意义
2. 链式推算的参考完成日对并行节点**系统性偏长**（它假设串行累加）

**约束**：`stage_code` 一经产生里程碑记录即不可修改（V420 第 10-11 行），改造只能**加字段**。

---

### 1.7 参考工期覆盖不全，且表存在被 UI 清空的历史事故（P1）

**覆盖缺口**：`production_tracking_node_benchmarks` 仅有 SEALER 4 个节点。

```15:21:backend/src/main/resources/db/migration/V401__tracking_node_benchmarks_restore_sealer.sql
INSERT INTO production_tracking_node_benchmarks(workshop_kind,stage_code,typical_days,sample_count,baseline_note,computed_at) VALUES
    ('SEALER','MATERIAL',0.0,313,'中位数：排产时间→备料完成（2026-10-21 新口径实测，零天占 90%）','2026-10-22'),
    ('SEALER','MOLD',3.0,507,'中位数：备料完成→模具生产完成（2026-10-21 新口径实测）','2026-10-22'),
    ('SEALER','ASSEMBLY',2.0,460,'中位数：模具生产完成→组装完成（2026-10-21 新口径实测，按需工序氧化不占链）','2026-10-22'),
    ('SEALER','WAREHOUSE',0.0,472,'中位数：组装完成→入库完成（2026-10-21 新口径实测，零天占 90%）','2026-10-22')
```

| 缺口 | 说明 |
| --- | --- |
| MACHINE 车间全部节点 | 「样本不足，不播种，未配置即不推算」（V401 第 14 行）⇒ 超参考工期指标在机器车间**恒为空** |
| SEALER `MATERIAL_READY`（物料配齐） | V420 播种中它是必经节点（sort_no 4），但无参考工期 ⇒ **链式推算在此断裂** |
| 产品级基准 | V394 已下沉到产品本身（`production_tracking_product_benchmarks`，V391 分组层已 DROP）；V401 记录「50 个产品里 43 个没有任何产品级发布值」 |

**锚点口径已变更**：V382 定义为「上游完成 → 本节点完成」，V401 改为「**首节点以氚云排产时间起算**」。任何基于参考工期的推算必须沿用 V401 口径。

**运维风险（已发生过一次）**：

```1:6:backend/src/main/resources/db/migration/V401__tracking_node_benchmarks_restore_sealer.sql
-- 通用参考（车间级兜底）恢复封口机 4 个节点（2026-10-22）。
--
-- 背景：V382 播种过这 4 行，但 production_tracking_node_benchmarks 现在是 0 行——
-- 「通用参考设置」是全量提交、清空即删行，一次空值保存就把整表清掉了。
-- 后果不只是通用参考没了：50 个产品里 43 个没有任何产品级发布值，两级都空 ⇒
-- 这些产品的节点参考日期与「生产工期」全部推不出来。
```

⇒ 参考工期**可被前端一次空值保存整表清空**，且无软删除/审计。补录参考工期前应先治理该写入路径，否则补了还会再丢。

**重要区分**：备料与入库的 `typical_days = 0.0` 是**实测中位数**（零天占 90%，V401 第 11-13 行刻意保留），**不是缺失值**。缺失表现为"表中无行 ⇒ 不推算"。UI 与统计必须区分二者（详见约束 4.4）。

---

### 1.8 `scheduleWaitAnomalies` 无前端消费者（P2）

`ProductionTrackingAnalyticsService:553-562` 有一条 `LIMIT 50` 的"排产等待异常单"查询，结果放入 `scheduleWaitAnomalies`。

- 前端 `api/productionTracking.ts:549` **仅在类型声明中出现**
- 三个抽屉组件**无任何消费**

而概览卡「排产等待 >3 天」的数字是 `TrackingNodeAnalytics.vue:105-109` 在**客户端拿 `scheduleWait` 逐天分布累加**出来的。

⇒ 服务端算了两遍同一口径，一遍无人使用。要么删除该 SQL，要么让概览卡改为消费它（当前是前端重复实现服务端逻辑）。

---

### 1.9 车间归属依赖名称子串匹配（P1）

节点归属车间类别靠 `p.production_line LIKE '%封口%'` 判定 SEALER / MACHINE（`AnalyticsService:525`、`nodeAnalyticsOrders:618`）。

风险：
- 车间改名、或新增含"封口"字样的车间 ⇒ 串档
- `production_line` 为空时 `COALESCE(...,'未分车间')`，在节点分析中**自成一组**，与生产分析抽屉的分组不是同一套口径
- 同类判定在 `ProductionTrackingPolicy` 中已抽象为 `kindOf(workshop)` / `isSealer(...)`，节点分析却没复用

---

### 1.10 排产等待边界自洽，仅命名易混淆（P3，更正上一轮 P0 判定）

四个环节逐个追到后端 SQL，链条**自洽**：

| 环节 | 位置 | 口径 | 与自身标签 |
| --- | --- | --- | --- |
| 概览卡数值 | `TrackingNodeAnalytics.vue:105-109` | `day > 3`（即 ≥4） | 标签「>3 天」✅ |
| 概览卡下钻 | 同上 `:13` `drillWaitRange('排产等待 >3 天', 4)` | `waitDaysMin=4` → SQL `>= 4` | ✅ |
| 合并柱 | 同上 `:96-101` `waitBuckets` | `day >= 3` | 标签「3 天及以上」✅ |
| 合并柱下钻 | 同上 `:178` `drillWaitRange('排产等待 3 天及以上', 3)` | `waitDaysMin=3` → SQL `>= 3` | ✅ |

不存在"等待恰好 3 天的任务出现在合并柱却不属于概览卡指标"的数字错配——二者本就是**两个不同口径、且各自标注了不同名称**的指标。真实问题仅是两个相邻指标命名接近，用户可能误以为同源。**属 P3 文案优化，不是统计边界错误。**

---

### 1.11 三个抽屉的容器与指标卡不统一（P2）

- 容器：三个抽屉均为裸 `el-drawer size="90%" :with-header="false" append-to-body` + 自绘 `h1` 头部，未接入 `components/common/LqDrawer.vue`（该组件已有 `docs/frontend-ui-lqdrawer-usage.md` 与契约测试）
- 指标卡：**仅周复盘**使用 `MetricGrid` / `MetricCard`（`ProductionTrackingWeeklyReviewDrawer.vue:31-36`），生产分析与节点分析均为手搓
- 筛选栏：三者各自实现，未抽取

---

## 二、待业务定夺

> 每项给出**推荐方案 + 理由 + 备选**。确认后进入方案设计。

**决策状态（2026-10-28）**

| 项 | 状态 | 落地文档 |
| --- | --- | --- |
| 2.1 快照归档权限 | ✅ 已决策（方案 B + 读写权限分离 + 显式归档 + 新迁移修 V421） | `docs/production-tracking-history-snapshot-redesign-20261028.md` |
| 2.2 快照触发方式 | ✅ 已决策（两条链路 + `source_type` 三值） | 同上 |
| 2.3 统计时间窗口 | ✅ 已决策（方案 A：快照固化 `asOf`） | 同上 |
| 2.4 节点异常判断 | ✅ 已决策（三指标拆分 + 三态参考工期；**风险任务数须待 S3 统一算法后启用**） | `docs/production-tracking-node-flow-model-redesign-20261028.md` |
| 2.5 并行工序建模 | ✅ 已决策（显式 `predecessor_stage_codes` + `sort_no` 分离） | 同上 |
| 2.6 按需节点纳入 | ✅ 已决策（必经 + 已确认适用的按需节点；**保留"待确认"态**） | 同上 |

**补充决策（2026-10-28 第二轮）**

| 项 | 决策 |
| --- | --- |
| 交期预测终点 | **保留** `deliveryStages()` 在 `WAREHOUSE` 截断；入库后工序（木箱）可进过程进度统计，**不进交期预测** |
| 参考工期三态 | 正数 = 有效工期 / `0` = **有效工期且为 0 天** / `NULL` 或无记录 = 无参考工期 |
| 迁移版本号 | **不预先永久占用** V424 / V425；创建迁移文件时须重新核对仓库最新版本 |
| 定时归档身份 | 定时任务**不得**依赖登录用户上下文，须定义经授权的系统级全量统计执行身份 |

### 2.1 快照归档权限模型（P0）

**问题**：快照键无权限维度，而分析结果随用户数据范围变化（见 1.3）。

| 方案 | 做法 | 代价 | 评价 |
| --- | --- | --- | --- |
| A | 快照键加 `scope_code + employee_id`，各存各的 | 快照量 × 人数；趋势图按人隔离，失去"团队周报"意义 | 不推荐 |
| **B（推荐）** | **仅 `ALL` 数据范围（跟单主管 / 管理员）可生成与查看快照**；跟单员的历史趋势区块显示"仅主管归档后可见" | 跟单员看不到历史趋势 | 快照本就是**管理口径的归档物**；与 2.2 的"两条独立链路"天然吻合 |
| C | 快照只存不区分权限的聚合 | 与"周期复盘"需求冲突 | 不推荐 |

**推荐 B**。理由：周复盘归档是管理动作，不是个人查询动作；个人视角的周期回看继续走实时链路即可，不需要快照。

---

### 2.2 快照触发方式与归档类型（P0）

**问题**：不能采用"查询即固化"。首次回溯查询算的是**今日库中的历史区间状态**，不是该周期末的真实状态；且自定义周期会产生大量重叠快照。

**推荐：两条独立链路**

| 链路 | 触发 | 数据源 | 是否落库 | 用途 |
| --- | --- | --- | --- | --- |
| **实时周复盘** | 用户查询 | 现算 `analyzeUncut` | ❌ 永不固化 | 可下钻、可刷新 |
| **归档周期** | 显式触发：① 自然周结束后定时任务 ② 主管手工点击「固化本周周报」 | 读取快照 | ✅ | 历史趋势、周报归档 |

**归档类型（新增字段 `source_type`）**

| 值 | 含义 | 展示要求 |
| --- | --- | --- |
| `SCHEDULED` | 周期结束后定时任务自动固化 | 正常展示 |
| `MANUAL` | 主管在周期内手工固化 | 正常展示 |
| `BACKFILL` | 补录：周期已过才首次生成 | **必须显著标注「回溯生成，非当时真实状态」** |

⇒ 满足约束 4.3：历史回溯生成的数据与当时真实固化的数据必须可区分。

---

### 2.3 统计时间窗口（P1）

**问题**：`analyzeUncut` 用 `summarize(rows, LocalDate.now())`（`AnalyticsService:200`），**无论查哪个周期，未完工数与超期天数都拿今天算**。回看 8 月周期时，未完工随时间自然衰减到 0、未完工超期天数无上限增长。

| 方案 | 说明 |
| --- | --- |
| **A（推荐）** | 快照内固化 `asOf`，归档快照按"固化日"定格，不再随查询日变化 |
| B | 历史周期查询支持"按周期截止日重算"开关 | 需改造 `summarize` 支持传入 asOf，成本较高 |

**推荐 A**。另需与节点分析的时间窗口（2.4）一并设计，避免两处窗口语义不同。

---

### 2.4 节点异常判断方式（P0）

**问题**：见 1.4、1.5。核心是**完成率不等于风险**，且当前无开始日期可用。

**推荐：拆成三个互不替代的指标**

| 指标 | 口径 | 用途 | 现状 |
| --- | --- | --- | --- |
| ① 节点记录完成率 | 已完成该节点的任务 ÷ **有该节点记录**的任务 | 反映已有记录中的完成比例 | 现有 `done / involved`，**保留、不重命名**（见约束 4.1） |
| ② 当前节点在制数 | 在制任务中**适用**该节点且尚未完成的任务 | 进度监控 | 需新建，适用性判定可复用现成实现 |
| ③ 节点异常任务数 | 链式间隔超过参考工期阈值的任务 | 异常优先级 | 需新建，依赖 1.5 / 1.7 缺口补齐 |

**指标 ② 必须区分的五种状态**：

| 状态 | 判定 |
| --- | --- |
| 节点适用且已完成 | 有记录且 `stage_status='DONE'` |
| 节点适用、进行中 | 有记录且非 DONE，且上游已完成 |
| 节点适用、未开始 | 无记录，但属必经节点（或按需已确认适用） |
| **节点不适用** | 按需工序且未命中 `match_keywords`，或命中 `exclude_keywords` |
| 状态未知 / 缺少记录 | 无记录且无法判定适用性 ⇒ 单列，不得并入"未开始" |

**适用性判定已有现成实现，不需要重新设计**：

```36:40:backend/src/main/java/com/liqiang/archive/service/ProductionTrackingPolicy.java
    /** 必经节点＝全部节点去掉按需工序；「跟单完成」只看必经节点。 */
    static List<String> requiredStages(String workshop) {
        return ProductionTrackingStageCatalog.enabled(kindOf(workshop)).stream()
                .filter(def->!def.optional()).map(ProductionTrackingStageCatalog.StageDef::code).toList();
    }
```

配合 `isOptionalStage(code, workshop)`（`:52-55`）、`configuredOptionalStage(code, configuration, remark)`（`:63-65`），以及详情接口已在计算的 `applicable`（`ProductionTrackingService:2635`）。

**指标 ③ 的锚点（待定，见 2.5）**：串行节点可用上游完成日；并行节点需要独立前置依赖。

---

### 2.5 并行工序建模（P0）

> ✅ **已决策（2026-10-28）**：采用显式 `predecessor_stage_codes`，不采用相同 `sort_no` 表示并行；`sort_no` 保留为展示顺序。
> 落地设计见 `docs/production-tracking-node-flow-model-redesign-20261028.md`。
> **并行参考完成日计算公式本轮不定死**，见该文档 §8 开放项。

**问题**：`sort_no` 单值全序同时承担显示排序与链式推算，无法表达并行（见 1.6）。

**推荐方案（符合约束 4.2）**

| 步骤 | 做法 |
| --- | --- |
| 1 | 新增 `predecessor_stage_codes VARCHAR(200) NULL`（逗号分隔），**显式表达前置依赖** |
| 2 | `sort_no` 降级为**仅显示排序**，不再参与推算 |
| 3 | 首节点前置为空 ⇒ 锚点取 `schedule_time`（与 V401 口径一致） |
| 4 | 并行节点各自声明自己的前置（如钣金与模具均以"备料"为前置） |
| 5 | 未配置 `predecessor_stage_codes` 时，按 `sort_no` 取上一个必经历程节点兜底，保证存量行为不变 |

**约束**：`stage_code` 不可修改（V420），本次只加列，不动既有数据。

---

### 2.6 按需节点纳入规则（P1）

**问题**：存在第三处口径打架——`ProductionTrackingPolicy.requiredStages` 明确「跟单完成只看必经节点」，而节点分析的 `involved` 是"有该节点记录的单"（含按需节点）。

> ✅ **已决策（2026-10-28）**：当前在制分析**包含必经节点 + 已确认适用的按需节点**，两者分别标识；跟单完成判定保持 `requiredStages()` 原规则；节点风险分析须待统一预测算法完成后启用。

**确认的规则**

| 节点类型 | 进入节点分析 | 进入风险分析 | 纳入跟单完成判定 |
| --- | --- | --- | --- |
| 必经节点 | 是 | 有参考依据时参与 | 是 |
| **已确认适用的按需节点** | 是 | 有参考依据时参与 | 按现有完成规则，**不擅自改变** |
| 明确不适用的按需节点 | 否 | 否 | 否 |
| **适用性尚未确定的按需节点** | **单独提示"待确认"** | **暂不判定异常** | **不直接认定为未完成** |

**两个概念必须分开**

- **跟单完成判定**：继续遵循 `requiredStages()` 口径，不变
- **生产过程分析**：既要观察必经工序，也要观察已确认适用的按需工序

⇒ 例：封口机订单需要「氧化/喷特氟龙」，该工序即使非必经，也应出现在该订单的生产风险分析中。否则会遗漏真正影响交期的环节。

**关键约束：未命中关键词 ≠ 明确不适用**

`configuredOptionalStage()` 依赖可选配置 / 备注关键词判定适用性。未命中**不代表业务上明确不适用**。

⇒ 新统计模型**必须保留"待确认"状态**；不得把未命中的按需节点直接归入"不适用"，也不得直接计入未完成。

---

## 三、待补数据

> 本节所有条目均需**连库核查**后方可定稿。审计阶段未执行。

### 3.1 各车间参考工期

| 项 | 现状 | 来源 | 补录要求 |
| --- | --- | --- | --- |
| MACHINE 车间全部节点（画图 / 备料 / 钣金 / 模具 / 组装 / 调试 / 入库） | 无行 ⇒ 不推算 | `production_tracking_node_benchmarks` | **样本不足则不补**，按约束 4.4 显示"暂无参考值" |
| SEALER `MATERIAL_READY`（物料配齐） | 必经节点但无参考工期，链式推算断裂 | 同上 | 需实测后补，否则明确标注断链位置 |
| 产品级基准 | 50 个产品中 43 个无发布值（V401 记录） | `production_tracking_product_benchmarks` | 按产品样本量决定，样本不足不发布 |

**核查语句**

```sql
-- 通用参考覆盖情况
SELECT workshop_kind, COUNT(*) nodes,
       SUM(typical_days = 0) zero_day_nodes, MIN(sample_count) min_sample
FROM production_tracking_node_benchmarks GROUP BY workshop_kind;

-- 制程表中有、但无参考工期的必经节点（断链点）
SELECT d.workshop_kind, d.stage_code, d.stage_label, d.sort_no
FROM production_tracking_stage_defs d
LEFT JOIN production_tracking_node_benchmarks b
  ON b.workshop_kind = d.workshop_kind AND b.stage_code = d.stage_code
WHERE d.enabled = 1 AND d.optional = 0 AND b.id IS NULL
ORDER BY d.workshop_kind, d.sort_no;
```

**前置治理**：先修复"通用参考设置"全量提交可清空整表的问题（1.7），否则补录数据仍会丢失。

---

### 3.2 节点历史记录质量

需核查里程碑记录是否足以支撑新的在制口径。

```sql
-- 每单已登记节点数 vs 该车间类别必经节点数
SELECT p.production_line, COUNT(DISTINCT p.id) orders,
       ROUND(AVG(ms.cnt), 1) avg_stages, MIN(ms.cnt) min_stages, MAX(ms.cnt) max_stages
FROM production_orders p
JOIN (SELECT production_order_id, COUNT(*) cnt
      FROM production_tracking_milestones GROUP BY production_order_id) ms
  ON ms.production_order_id = p.id
GROUP BY p.production_line;

-- 有记录但状态既非 DONE 也非显式未完成的占比（脏状态）
SELECT stage_status, COUNT(*) FROM production_tracking_milestones GROUP BY stage_status;
```

**关注点**：`stage_status` 的取值域（V369 默认 `'PENDING'`，前端按 `DONE / OPEN / 其他→—` 渲染，`TrackingNodeAnalytics.vue:49`）。若存在 `PENDING` 与 `OPEN` 混用，指标 ② 的"进行中 / 未开始"判定需先统一取值。

---

### 3.3 节点适用性

- `match_keywords` / `exclude_keywords` 当前仅覆盖 `OXIDATION` 与 `WOODEN_CRATE`（V420 播种）
- 需确认是否还有其他按需工序未登记关键词

```sql
SELECT workshop_kind, stage_code, stage_label, optional, match_keywords, exclude_keywords
FROM production_tracking_stage_defs WHERE enabled = 1 ORDER BY workshop_kind, sort_no;
```

---

### 3.4 未分车间任务

`production_line` 为空时归为 `'未分车间'`，在节点分析中自成一组并走 MACHINE 制程表（1.9）。

```sql
SELECT COUNT(*) FROM production_orders
WHERE external_source = 'H3YUN' AND (production_line IS NULL OR production_line = '');
```

**需业务定夺**：未分车间任务是否应纳入节点分析。若纳入，其 `workshop_kind` 取值需明确规则；若不纳入，需在 UI 单列说明。

---

## 四、硬性约束（本轮确认，写入后续方案）

### 4.1 不得把「节点记录完成率」命名为「数据记录完备度」

两者不是一回事。现有 `done / involved` 的分母是"**有该节点记录的单**"，只能说明**已有记录中的完成比例**，不能衡量**应有记录是否完整**。

真正的记录完备度必须以**适用节点数量**为分母，形如：

```
记录完备度 = 已登记状态的适用节点数 ÷ 适用节点总数（按 requiredStages + configuredOptionalStage 判定）
```

⇒ 指标 ① 保留原义与原名；"记录完备度"若要做，是**另一个独立指标**，需另建口径。

> 本条撤回上一轮「其含义改成数据记录完备度是准确的」的判断。

### 4.2 不得直接使用上游节点完成日判断所有工序的异常

串行节点可如此设计；**钣金与模具等并行工序需要独立的前置依赖关系**。

- 未来通过显式 `predecessor_stage_codes` 表达依赖
- `sort_no` **只负责显示排序，不承担流程逻辑**

### 4.3 历史快照必须记录统计截止日与统计范围

至少包含：`asOf`（统计截止日）、`source_type`（归档方式）、`generated_by_employee_id`（生成者）、**权限范围**。

历史回溯生成（`BACKFILL`）的数据，必须与当时真实固化（`SCHEDULED` / `MANUAL`）的数据**在存储与展示上明确区分**，不得混算、不得互相覆盖。

### 4.4 不得为启用节点风险分析而强行补齐参考工期

- 样本不足的节点显示「**暂无参考值**」，不参与异常判定
- 允许后续建立**经审核的人工参考工期**，但需与实测值区分标注
- **不得默认 0 天**
- ⚠️ 注意区分：备料 / 入库的 `typical_days = 0.0` 是**实测中位数**（零天占 90%），属有效值；缺失表现为**表中无行 ⇒ 不推算**。UI 与统计不得把真实的 0 天误判为缺失，也不得把缺失填成 0。

---

## 五、实施决策表

> 优先级：P0 = 阻断，须先解决；P1 = 影响正确性或可维护性；P2 = 优化。
> 顺序：**快照安全与归档机制 → 节点流程模型及统计口径 → 数据质量与冗余清理 → 三个抽屉的功能与 UI 优化**。
> 本表在「二、待业务定夺」确认后才进入实施；**当前阶段不执行任何迁移与代码改动**。

| # | 事项 | 级别 | 涉及 DB 迁移 | 影响现有统计结果 | 验收标准 |
| --- | --- | --- | --- | --- | --- |
| 1 | 修复 V421 唯一键（`workshop` 改 `NOT NULL DEFAULT ''` 或表达式索引） | **P0** | ✅ 是 | ❌ 否（修复前无有效快照） | 连续两次固化同一"全部车间"周期，`COUNT(*)` 仍为 1 |
| 2 | 快照增加权限维度，确定归档可见范围（推荐方案 B：仅 ALL 范围可归档/查看） | **P0** | ✅ 是（加列） | ❌ 否 | 跟单员账号打开历史趋势看不到超出自身数据范围的数字 |
| 3 | 接通快照归档入口（显式触发；`source_type` 区分 SCHEDULED / MANUAL / BACKFILL） | **P0** | ❌ 否（表已有） | ❌ 否（实时链路不变） | 归档后 `listSnapshots` 返回该周期；重查同一周期数字不变 |
| 4 | 快照固化 `asOf`，归档快照不再随查询日变化 | **P0** | ✅ 是（加列） | ⚠️ 仅影响归档视图 | 改期后重查已归档周期，`totals` 与归档时一致 |
| 5 | 新增 `predecessor_stage_codes`，`sort_no` 降级为仅显示排序 | **P0** | ✅ 是（加列） | ❌ 否（存量无值走兜底） | 并行节点各自推算的参考日期不累加另一分支工期 |
| 6 | 新建「当前节点在制数」口径（适用 + 五态区分） | **P0** | ❌ 否 | ❌ 否（与指标 ① 并存） | 各节点在制数之和 ≥ 在制总单数；五态互斥不重不漏 |
| 7 | 定义节点异常判定方式（锚点 + 阈值），无参考值显示"暂无参考值" | **P0** | ❌ 否 | ❌ 否 | 机器车间节点显示"暂无参考值"而非 0 |
| 8 | 节点分析补充时间窗口与车间筛选 | P1 | ❌ 否 | ⚠️ 新增维度，原全量视图保留 | 选定时间窗后下钻条数与卡片数一致 |
| 9 | 车间识别改为读 `workshop_kind` 显式列，废弃 `LIKE '%封口%'` | P1 | ❌ 否 | ⚠️ 可能改变分组归属 | 新增含"封口"字样的车间不串档 |
| 10 | 按需节点纳入规则定稿（指标 ②③ 默认只算必经） | P1 | ❌ 否 | ⚠️ 影响在制口径 | 按需未适用的单不计入任何节点分母 |
| 11 | 清理 `scheduleWaitAnomalies`（删除或改为概览卡数据源） | P2 | ❌ 否 | ❌ 否 | 概览卡数字与服务端唯一来源一致 |
| 12 | 排产等待边界命名优化（区分「>3 天」与「3 天及以上」） | P2 | ❌ 否 | ❌ 否（仅文案） | 两处标签与各自下钻范围肉眼可辨 |
| 13 | 参考工期"通用参考设置"写入路径治理（防止整表清空） | **P1** | ❌ 否 | ❌ 否 | 空值保存不再删除既有行；有变更审计 |
| 14 | 三个抽屉统一容器（`LqDrawer`）、筛选栏、指标卡（`MetricCard` / `MetricGrid`） | P2 | ❌ 否 | ❌ 否 | 三抽屉视觉与交互一致；下钻行为不变 |
| 15 | 重明确三抽屉定位：交付表现 / 过程与风险 / 周期问题与改善 | P2 | ❌ 否 | ❌ 否 | 卡片区不再三处同源重复 |

### 阶段划分

| 阶段 | 包含事项 | 前置条件 |
| --- | --- | --- |
| **一 · 快照安全与归档机制** | 1、2、3、4 | 2.1、2.2、2.3 确认 |
| **二 · 节点流程模型及统计口径** | 5、6、7、8、10 | 2.4、2.5、2.6 确认；3.1、3.2、3.3 核查完成 |
| **三 · 数据质量与冗余清理** | 9、11、12、13 | 3.4 核查完成 |
| **四 · 三个抽屉的功能与 UI 优化** | 14、15 | 一~三完成，业务口径稳定 |

---

## 附：本轮审计未覆盖项

以下不属于本次范围，但会影响后续验收，**需在实施前另行核查**：

1. `production_orders` 与 `production_tracking_milestones` 的真实数据量与分布（见三、待补数据）
2. 快照表的实际行数与是否存在重复（`GROUP BY period_from, period_to, workshop HAVING COUNT(*) > 1`）
3. 三个抽屉与生产跟单主列表之间的口径差异（下钻条数 vs 列表筛选条数）
4. `production_tracking_product_benchmarks`（产品级）与通用参考的优先级与回退链
5. RBAC / 权限路径中与 `manufacturing.read.*` 相关的实际生效配置
6. 三个抽屉的 e2e 测试（`frontend/e2e/production-tracking-*.spec.ts`）在口径改造后的基线更新范围
