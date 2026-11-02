# 生产跟单 P0 数据与代码复核报告

> 主题：人工结案与节点完成状态的现状事实核查（人工结案 139 单、未结案超期 26 单、按需节点 114/97 单）
> 性质：**只读**。本文档全部结论来自本地 MySQL 快照的 SELECT 查询与本地源码阅读，**未修改任何业务代码、未执行数据库迁移、未修改生产数据**。
> 用途：作为《生产跟单人工结案与节点补全 P1 设计方案》的事实基线，供后续复核与回归测试比对。

---

## 0. 快照与复现方式

| 项 | 值 |
| --- | --- |
| 快照时间 | **2026-10-29 16:21:46**（`CURDATE()` = 2026-10-29） |
| 数据库 | `product_archive`（本地开发库） |
| MySQL 版本 | 9.7.1 |
| 代码基线 | 本地工作区（含节点分析缺陷修复提交 `bf3684f7`） |

### 复现命令模板

```bat
cd /d c:\Users\20676\source\LQXM && for /f "tokens=1,* delims==" %i in ('findstr /B "DB_PASSWORD" backend\.env') do set "MYSQL_PWD=%j" && "D:\Program Files\MySQL\MySQL Server 9.7\bin\mysql.exe" --user=root --host=127.0.0.1 --database=product_archive --batch < 脚本路径.sql
```

### 适用范围与口径约定

| 约定 | 说明 |
| --- | --- |
| 「结案」 | `production_tracking_state.closed_at IS NOT NULL`（跟单员主动确认结束，见 §7.1） |
| 「可见任务」 | 本报告的多数聚合用 `JOIN production_tracking_milestones`（或有 `state` 行）近似 `visible()` 谓词；已核对这与节点分析抽屉显示的「覆盖任务 679 单」一致 |
| 「适用制程」 | `CASE WHEN p.production_line LIKE '%封口%' THEN 'SEALER' ELSE 'MACHINE' END`（与 `ProductionTrackingPolicy.KIND_CASE` 同源） |
| 「按需节点」 | `production_tracking_stage_defs.optional=1` 且 `match_keywords IS NOT NULL`；是否适用于本单由 `optional_configuration` / `production_requirement` 的 REGEXP 匹配决定 |
| 「适用节点是否满足」 | SQL 侧用 `NOT EXISTS (SELECT 1 FROM production_tracking_milestones m WHERE m.production_order_id=p.id AND m.stage_code=d.stage_code AND m.stage_status='DONE')` 表达，本报告统称为「无完成记录」 |

> ⚠️ **口径易错点（本报告纠正过两次）**：
> ① JOIN `production_tracking_stage_defs` 时**必须限定 `d.workshop_kind`**，否则同一 `stage_code` 在 SEALER / MACHINE 各有一条定义，会把行数放大近一倍；
> ② 判断「LEFT JOIN 未命中」时必须写 `COALESCE(x.cnt,0)=0`，直接写 `x.cnt=0` 在未命中时为 NULL，`SUM(CASE WHEN ...)` 会静默漏计。

---

## 1. 结论摘要

| 编号 | 结论 | 关键数据 |
| --- | --- | --- |
| C1 | 人工结案共 **139 单**；其中 **18 单**在结案时仍有非 DONE 的节点行，共 **31 行** | 139 / 18 / 31 |
| C2 | 结案单的 31 行缺口集中在**木箱（8 行）与氧化（6 行）**，其余分散在钣金、调试、模具、组装等 | 见 §3 表 |
| C3 | 「配置了木箱但无完成记录」全库 **114 单**，其中已结案 **87 单**、完全无节点行 **83 单**、有行未标完成 **31 单**、有采购入库凭证 **84 单** | 木箱 114 |
| C4 | 「配置了氧化但无完成记录」全库 **97 单**，其中已结案 **54 单**、完全无节点行 **63 单**、有行未标完成 **34 单**、有采购入库凭证 **65 单** | 氧化 97 |
| C5 | **26 单当前超期**里有 **17 单（65%）只差按需节点**，7 单只差必选节点，2 单两类都差 | 26 = 7+17+2 |
| C6 | **18 单与 26 单互不重叠**：26 单的判据要求 `closed_at IS NULL`，18 单要求 `closed_at IS NOT NULL` | 互斥 |
| C7 | `manual_reference_date` 语义是**前瞻预计**（V402 迁移注释 + `effective=manual?:systemDate`），**不可用于存放推断的完成日期** | 见 §5 |
| C8 | `completed_date` 直接决定交付日、整单完工日、P50/P75 与入库待登记提醒；虚构日期会污染全部四项 | 见 §6 |
| C9 | `loadOrderSamples` 用 `completed != null` 过滤，**「DONE 且日期为空」天然被排除出 P50 样本** | `ProductionTrackingService:1941` |
| C10 | `changeClosure` 已写 `production_tracking_entries`（含 before/after JSON 与操作人），但**未调用通用审计 `audit.log`** | 见 §7.3 |
| C11 | 节点「适用性」目前**完全没有持久化**，按 SQL 实时算（`OPTIONAL_CONFIGURED_HIT`），氚云字段一变结论就变 | 见 §7.4 |

---

## 2. 人工结案 139 单的统计范围

### 2.1 范围定义

```sql
SELECT COUNT(*) FROM production_orders p
JOIN production_tracking_state t ON t.production_order_id=p.id AND t.closed_at IS NOT NULL;
```

| 项 | 值 | 说明 |
| --- | --- | --- |
| 已结案工单 | **139** | `closed_at` 非空 |
| 其中同时 `stopped_at` 非空 | 需单独核对 | 两者在设计上互斥（`changeClosure` 校验「已停止不能结案」） |

### 2.2 结案单里「有非 DONE 节点行」的子集

```sql
SELECT COUNT(DISTINCT p.id) orders, COUNT(*) rows
  FROM production_orders p
  JOIN production_tracking_state t ON t.production_order_id=p.id AND t.closed_at IS NOT NULL
  JOIN production_tracking_milestones ms ON ms.production_order_id=p.id
 WHERE ms.stage_status<>'DONE';
```

→ **18 单 / 31 行**（这是 C1 的来源）

---

## 3. 31 行缺口的准确分类

### 3.1 修正后的分布（限定制程）

```sql
SELECT d.workshop_kind,d.stage_code,d.stage_label,COUNT(*) open_rows
  FROM production_orders p
  JOIN production_tracking_state t ON t.production_order_id=p.id AND t.closed_at IS NOT NULL
  JOIN production_tracking_milestones ms ON ms.production_order_id=p.id AND ms.stage_status<>'DONE'
  JOIN production_tracking_stage_defs d ON d.stage_code=ms.stage_code AND d.enabled=1
   AND d.workshop_kind=CASE WHEN p.production_line LIKE '%封口%' THEN 'SEALER' ELSE 'MACHINE' END
 GROUP BY d.workshop_kind,d.stage_code,d.stage_label
 ORDER BY open_rows DESC;
```

| 制程 | 节点编码 | 节点名 | 行数 |
| --- | --- | --- | --- |
| SEALER | WOODEN_CRATE | 木箱 | 7 |
| SEALER | OXIDATION | 氧化/喷特氟龙 | 6 |
| MACHINE | SHEET_METAL | 钣金 | 4 |
| MACHINE | TESTING | 调试 | 4 |
| MACHINE | MOLD | 模具 | 3 |
| MACHINE | ASSEMBLY | 组装 | 3 |
| MACHINE | DRAWING | 画图 | 1 |
| MACHINE | MATERIAL | 备料 | 1 |
| MACHINE | WAREHOUSE | 入库 | 1 |
| MACHINE | WOODEN_CRATE | 木箱 | 1 |
| | | **合计** | **31** ✅ |

分类加总 31 ＝ 总行数 31，口径闭合。

### 3.2 曾经的错误（留档）

前一版报告的明细表 JOIN 未限定 `d.workshop_kind`，导致同一 `stage_code` 匹配到两条制程定义，**加总虚高到 47 行**（如"SEALER 木箱 8 + MACHINE 木箱 8"）。已作废，以上表为准。

---

## 4. 木箱 114 单 / 氧化 97 单

### 4.1 统计条件

「源单配置（`optional_configuration`）或生产要求（`production_requirement`）写明需要该按需工序，但该节点没有 `stage_status='DONE'` 的记录」。

```sql
SELECT d.stage_code,d.stage_label,
       SUM(CASE WHEN COALESCE(ex.has_row,0)=0 THEN 1 ELSE 0 END) no_row_at_all,
       SUM(CASE WHEN ex.has_row=1 AND COALESCE(ex.done,0)=0 THEN 1 ELSE 0 END) row_not_done,
       SUM(CASE WHEN ib.cnt>0 THEN 1 ELSE 0 END) has_receipt,
       SUM(CASE WHEN t.closed_at IS NOT NULL THEN 1 ELSE 0 END) closed,
       COUNT(*) concerned_orders
  FROM production_orders p
  JOIN production_tracking_state t ON t.production_order_id=p.id
  JOIN production_tracking_stage_defs d ON d.enabled=1 AND d.optional=1
   AND d.workshop_kind=CASE WHEN p.production_line LIKE '%封口%' THEN 'SEALER' ELSE 'MACHINE' END
   AND d.match_keywords IS NOT NULL AND ( /* configuration 或 requirement 命中 keywords，且未被 exclude_keywords 排除 */ )
  LEFT JOIN (SELECT production_order_id,stage_code,COUNT(*) has_row,MAX(stage_status='DONE') done
               FROM production_tracking_milestones GROUP BY production_order_id,stage_code) ex
    ON ex.production_order_id=p.id AND ex.stage_code=d.stage_code
  LEFT JOIN (SELECT production_order_no,COUNT(*) cnt FROM purchase_receipt_items
              WHERE production_order_no IS NOT NULL GROUP BY production_order_no) ib
    ON ib.production_order_no=p.work_order_no
 WHERE NOT EXISTS (SELECT 1 FROM production_tracking_milestones md
                    WHERE md.production_order_id=p.id AND md.stage_code=d.stage_code AND md.stage_status='DONE')
 GROUP BY d.stage_code,d.stage_label;
```

### 4.2 结果

| 节点 | 关注单数 | 完全没有节点行 | 有行但未标完成 | 有采购入库凭证 | 已结案 |
| --- | --- | --- | --- | --- | --- |
| 木箱 WOODEN_CRATE | **114** | **83** | 31 | 84 | **87** |
| 氧化/喷特氟龙 OXIDATION | **97** | **63** | 34 | 65 | **54** |

> 「已结案 87 / 54」即前一版报告一度误述为总体的两个数字，实为**已结案子集**。

### 4.3 为什么不能直接认定这批是「漏记」

抽样 10 条命中原文（验证关键词匹配质量，全部命中真实需求描述）：

| 工单号 | 源单配置 | 生产要求 | 命中节点 |
| --- | --- | --- | --- |
| SCGD20260006084 | 出口木箱 | 225x178互换 220x150 … | 木箱 |
| SCGD20260005762 | 出口木箱 | 370x270用278x197磨具一个 … | 木箱 |
| SCGD20260005937 | 出口木箱;易撕口 | 圆形70 手推自动卷膜 … | 木箱 |
| SCGD20260006178 | 出口木箱;特氟龙;灌装机 | …模具氧化，自动落杯… | 氧化/喷特氟龙 |
| SCGD20260005601 | 特氟龙;不锈钢落地柜;手账轴… | …上磨具镀特氟龙 | 氧化/喷特氟龙 |

结论：**关键词匹配本身是准确的**，但「源单写了要做」在业务上至少对应五种不同情况，数据层无法自动区分：

1. 实际执行了，跟单员没登记；
2. 源单要求，但实际没执行（例如客户后改方案）；
3. 配置发生过变更，源单没同步；
4. 有记录但状态不是 DONE（本报告的 31 / 34 行）；
5. 该节点对当前工艺并不适用（关键词误命中或工艺路线不同）。

因此这 114 / 97 单**不能批量自动标记完成**，只能作为「待跟单员确认」的清单。

---

## 5. 未结案超期 26 单的判定与缺口分类

### 5.1 判定条件（与上一轮节点分析修复后的口径一致）

```
未停产（t.stopped_at IS NULL）
AND 未取消（p.status 不在取消名单）
AND 未结案（t.closed_at IS NULL）
AND 当前有效预计已过（COALESCE(t.forecast_finish_date,p.expected_completion_date) < CURDATE()）
AND 整单仍在产（OPEN_TRACKING = 1，即「最后适用节点未完成」）
```

### 5.2 缺口构成（决定处理方向）

| 缺口类型 | 单数 | 占比 |
| --- | --- | --- |
| 只差**必选**节点 | 7 | 27% |
| 只差**按需**节点（木箱/氧化） | **17** | **65%** |
| 两类都差 | 2 | 8% |
| **合计** | **26** | 100% |

**含义**：这批「当前超期」的判定高度依赖按需节点的适用性——而按需节点的适用性目前只是「源单配置关键词的正则匹配」。这也是 §4.3 结论的第二个佐证：**适用性需要一个可持久化的人工确认结果**。

### 5.3 与 18 单的关系

| 集合 | 判据 | 与另一集合的关系 |
| --- | --- | --- |
| 18 单 | `closed_at IS NOT NULL` 且有非 DONE 节点行 | 与 26 单**必然互斥**（26 单要求 `closed_at IS NULL`） |
| 26 单 | `closed_at IS NULL` 且仍在产且预计已过 | 同上 |

处理路径也不同：**18 单靠"结案时确认"解决，26 单必须先"登记/确认节点"，结案规则本身到不了它们**。

---

## 6. `manual_reference_date` 的读写全景与语义

### 6.1 表结构出处

`V402__tracking_manual_node_reference.sql`（注释原文）：

```sql
-- Per-order node forecasts entered by follow-up staff. Product/general benchmarks remain unchanged.
ALTER TABLE production_tracking_milestones
  ADD COLUMN manual_reference_date DATE NULL,
  ADD COLUMN manual_reference_reason VARCHAR(300) NULL;
```

**语义明确为「跟单员填写的节点『预计』日期」**。

### 6.2 全部读写入口

| 位置 | 类型 | 内容 |
| --- | --- | --- |
| V402 迁移 | 定义 | 注释写 `forecasts`（前瞻） |
| `ProductionTrackingService.java:517-518` | DTO | `Milestone(code,status,completedDate,note,manualReferenceDate,manualReferenceReason)` |
| `:1712` | 读 | 列表批量派生 `attachScheduleSignals` 的 SELECT 列之一 |
| `:2485-2487` | **读（计算）** | `LocalDate effective = anchor==null?null: manual!=null?manual:systemDate;` —— **人工预计日期优先于系统推算**，派生 `predictedFinishDate` / `scheduleRisk` / `primaryAction` |
| `:2624-2641` | 读 | 详情返回 `manualReferenceDate` / `manualReferenceReason` |
| `:2893-2897` | **校验** | DONE 节点**禁止**填写该列；填写必须写原因（≤300 字） |
| `:2927-2934` | 校验 | 只允许修改「适用且未完成」节点的人工预计日期；变更要与旧值比较 |
| `:2957-2962` | 写 | 与 `completed_date` 同一次 `INSERT ... ON DUPLICATE KEY UPDATE` |
| `:2912 / :2965` | 审计 | before/after 快照都包含这两列 |
| `:2997-3000` | 读 | 生成跟进内容（变更描述） |
| 《制程管理与生产参考工期设计梳理.md》185/191 | 设计文档 | 「正推：从 schedule_time 起逐节点加 typical_days（**可用 manual_reference_date 覆盖**）」「列表批量派生：支持跟单员手工覆盖某节点的参考日期」 |

### 6.3 结论

**不可复用为「推断完成日期」的存储位**，理由：

1. **方向相反**：该列的用途是「未来某节点的参考/预计日」，推断完成日期是「过去某个已发生节点的参考日」；
2. **有活跃计算链路**：改一次就会改变 `predictedFinishDate` 与 `scheduleRisk`，直接显示在列表/详情上；
3. **有专属校验冲突**：`2894` 规定 DONE 节点不许填该列，而推断日期恰好只出现在「已完成的节点」上；
4. **会把"预计"和"事实"混在同一列**，今后再也无法区分。

> 补充事实：现状库里 `stage_status='DONE'` 且 `completed_date IS NULL` 的行数 = **0**（三道写入闸：后端 `2889`、前端 `useTrackingDetail.ts:589-591`、批量完成 `useTrackingListCompletion.ts:56`）。

---

## 7. `completed_date` 的影响面

| 消费点 | 位置 | 表达式 | 被污染的后果 |
| --- | --- | --- | --- |
| **已交付日 DELIVERED_IN_DATE** | `ProductionTrackingService:233` | `COALESCE(ms.warehouse_in_date, ib.first_in)` | 列表交付日、逾期判定、工作台待办全部失真 |
| **整单完工日 COMPLETE_DATE** | `ProductionTrackingAnalyticsService:25` | `COALESCE(ms.warehouse_in_date, iq.full_in)` | 生产分析抽屉、周复盘抽屉（已验收）整单完工日失真 |
| **历史实测工期 P50/P75** | `loadOrderSamples:1927-1943` + `productDuration:2088-2119` | 按节点 `completed_date` 与氚云排产锚点算天数 | 工期分位数与样本量被虚构样本污染；基准表播种数据同源 |
| **入库待登记提醒 INBOUND_UNLOGGED** | `:302` / `:319` | `ib.first_in IS NOT NULL AND ms.warehouse_in_date IS NULL` | 一旦回填提醒永久消失，等于销毁「有没有人确认过入库」的信息 |
| 节点完成率分子 | Policy `STAGE_DONE_CASE` / 派生表 | `m.stage_status='DONE'`（**不看日期**） | 不受回填影响 ✅ |

> 关键兼容性发现（C9）：`loadOrderSamples` 在第 **1941** 行以 `if(stageCode!=null && completed!=null)` 过滤，
> 因此 **「DONE 且 completed_date 为 NULL」的节点天然不进入 P50 样本** —— 这是 P1 允许「已完成·日期不详」的安全性基础，但必须有回归测试钉死。

---

## 8. 结案 / 节点 / 审计的实现现状

### 8.1 人工结案

| 项 | 现状 |
| --- | --- |
| 入口 | `POST /api/manufacturing/followups/{id}/closure`（`ProductionTrackingController:443-449`） |
| 权限 | `@RequirePermission("manufacturing.followup")` |
| 请求体 | `ClosureRequest{version, closed, note}`（仅三个字段） |
| 校验 | version ≥ 0；note 非空；`stopped_at != null` → 拒绝；**未登记交付且说明 <6 字 → 拒绝** |
| **是否校验节点齐全** | **否**——完全不触碰 `TRACKING_COMPLETE` 与节点记录，且这是**有意为之**（V389 注释、前端文案「节点未补齐无需补充」） |
| 写入 | `closed_at` / `closed_by_name` / `closed_note` + `version+1` + `last_followup_at` |
| 撤销 | 同一接口 `closed=false`，三列置 NULL |

现有权限清单（供 P1 参考）：`manufacturing.followup`、`manufacturing.baseline.manage`、`manufacturing.listcolumns.manage`、`manufacturing.template.manage`、`system.config.manage`、`system.employee.manage` 等。

### 8.2 节点写入

| 写入路径 | 位置 | 是否允许 DONE 无日期 |
| --- | --- | --- |
| 详情保存 / 仅节点推进 | `ProductionTrackingService:2956-2963`（统一 INSERT ... ON DUPLICATE KEY UPDATE） | ❌ 不允许（`2889-2891` 抛错） |
| 表格导入 | `:932-937` | 跳过无日期节点 |
| 导入复核应用 | `ProductionWorkbookReviewService:198-200` | 必带日期 |

节点表无「操作人」「created_at」列，可追溯性仅靠 `updated_at` 与 entries 快照。

### 8.3 审计

| 环节 | 现状 |
| --- | --- |
| `changeClosure` | 写了 `production_tracking_entries`（`2858-2860`），含 before/after JSON 与 `created_by_name` ✅ |
| 通用割面审计 `audit.log(...)` | 在节点/产品基准相关操作已使用（`:1879`、`:2276`、`:2285`、`:2334`、`:2356`），**结案路径未使用** ❌ |
| 节点变更 | `2976-2978` 写 entries；但同样无 `audit.log` |

### 8.4 适用性（applicability）

| 项 | 现状 |
| --- | --- |
| 默认值来源 | `OPTIONAL_CONFIGURED_HIT`（:65）：REGEXP 匹配 `p.optional_configuration` / `p.production_requirement` |
| 分母计算 | `STAGE_TOTAL_WITH_OPTIONAL`（:132-135）＝ 必选总数 ＋ 已登记按需数 ＋ 配置需要但没登记的 |
| 完成判定 | `TRACKING_COMPLETE`（:136）＝ done 数 ＝ 上面的分母 |
| **人工确认结果是否持久化** | **完全没有**——每次查 SQL 都实时从源单字段重算。氚云重新导入后，人工确认过的结果会被源单字段变化直接推翻 |

---

## 9. 附录：核查 SQL 索引

| 编号 | 用途 | 对应结论 |
| --- | --- | --- |
| Q14-② | 结案单必选节点缺口 | C1 |
| Q16-A / A2 | 31 行的制程限定分类（**修正版**） | C2 |
| Q17-C | 木箱/氧化分层（`COALESCE` 修正版，**修正版**） | C3、C4 |
| Q18 | 26 单缺口构成 | C5 |
| Q16-D | 关键词命中原文抽样 | §4.3 |
| Q17-B | 18 单与 26 单互斥 | C6 |
| Q-ddl | 表结构与快照时间 | §0、§8.2 |

### 两个需留档的统计错误

| 错误 | 表现 | 修正 |
| --- | --- | --- |
| JOIN 未限定 `workshop_kind` | 31 行被放大成 47 行明细 | 加 `AND d.workshop_kind=CASE ... END` |
| LEFT JOIN 未命中判定写成 `cnt=0` | 「完全无行」显示为 0，实际是 83 / 63 | 改为 `COALESCE(cnt,0)=0` |

---

## 10. P0 未做的事（留待 P1）

1. 未区分「可以自动认定」与「必须人工确认」的边界（P1 §3）；
2. 未设计适用性的持久化方式（P1 §4）；
3. 未评估 114 / 97 单的历史批量处理路径（P1 §8 阶段四）；
4. 未改动任何统计口径与校对代码。

结论一句话：**现状的安全边界是「看得见的都可信、看不见的一律当未完成」，缺陷在于「没登记」和「不适用」无法区分；修复的正确方向是让人把结论说出来并记下来，而不是让系统替人编日期。**
