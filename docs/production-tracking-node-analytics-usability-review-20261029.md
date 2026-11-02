# 节点完成率 · 排产等待分析 易用性审查（2026-10-29）

> **本文性质：只读审查。** 本轮**未修改任何代码、未执行任何迁移、未改动数据库**（连库仅执行 SELECT）。
> 待本文结论逐条确认后再制定实施批次。

**审查对象**

| 项 | 位置 |
| --- | --- |
| 抽屉壳 | `frontend/src/apps/archive/views/production/ProductionTrackingNodeAnalyticsDrawer.vue` |
| 内容组件 | `frontend/src/components/production/TrackingNodeAnalytics.vue` |
| 聚合接口 | `ProductionTrackingAnalyticsService#nodeAnalyticsView`（`GET /manufacturing/followups/analytics/nodes`） |
| 下钻接口 | `ProductionTrackingAnalyticsService#nodeAnalyticsOrders`（`GET /manufacturing/followups/analytics/nodes/orders`） |

**用户提出的四个关注点**：① 节点完成率是否容易被误解；② 排产等待统计是否真正便于跟单；③ 不同车间节点展示是否清晰；④ 下钻表格能否快速找到需要处理的任务。

**证据来源**：源码逐行核对 + 本地库只读核查（2026-10-29，`product_archive`）。真实数据取自「跟单覆盖且有节点记录」的集合（即接口实际统计范围），SQL 见文末附录。

---

## 0. 结论摘要

| # | 结论 | 级别 | 需要后端 |
| --- | --- | --- | --- |
| N1 | 节点下钻「未完成」筛选**恒返回 0 条**（状态值 `OPEN` 在库里不存在） | **P1 功能不可用** | ✅ 一行 SQL |
| N2 | 下钻表**没有「当前预计完成 / 是否超期」**，清单看不出哪单急 | **P1** | ✅ 加两列 |
| N3 | 节点完成率**被 628 张历史已完工单稀释**（占比 91%），几乎不反映在制进度 | **P1 口径误导** | 文案即可 / 拆分待定 |
| N4 | 「排产等待 ≥3 天」49 单中 **39 单已完工**，是历史分布不是待办清单 | **P1** | 拆分需确认 |
| N5 | 完成率不体现规模：按需节点 58% 看着最差，实际只涉及 81 单 | P2 | ❌ 否 |
| N6 | 车间图块视觉权重失衡（625 单与 1 单同宽），排序靠名称启发式 | P2 | ❌ 否 |
| N7 | 点「刷新」后左侧图表更新、右侧下钻残留旧结果且不重查 | P2 | ❌ 否 |
| N8 | 未说明「统计范围＝全部跟单覆盖单（含已完工）、无时间窗」 | P2 | ❌ 否 |
| N9 | 后端 `scheduleWaitAnomalies` 无消费者，前端重复实现同一口径 | P3 | 清理候选 |
| N10 | 18 单无排产时间被静默排除，与「覆盖任务」基数不一致且无提示 | P3 | ❌ 否（文案） |

**一句话**：这个抽屉当前回答的是「历史上这些节点记录得怎么样」，而跟单员打开它是想问「现在我该先处理哪几单」。两者的差就是 N1–N4。

---

## 一、现状口径（已核实）

### 1.1 节点完成率

```562:570:backend/src/main/java/com/liqiang/archive/service/ProductionTrackingAnalyticsService.java
        var nodeSql="SELECT COALESCE(NULLIF(p.production_line,''),'未分车间') workshop,"
                +"d.stage_code stage,COALESCE(d.stage_label,d.stage_code) stageName,"
                +"COUNT(DISTINCT p.id) involved,"
                +"COUNT(DISTINCT CASE WHEN m.stage_status='DONE' THEN p.id END) done "
```

- `involved` = **已产生该节点里程碑记录**的单数；`done` = 其中 `stage_status='DONE'` 的单数。
- 里程碑不是建单预生成，是跟单员每次跟进 upsert 写入 ⇒ 无记录的单不在分母。
- 接口**无时间窗、无车间参数**，范围是「当前跟单覆盖的全部单」，`visible()` 只做权限收敛。
- 车间类别判定：`p.production_line LIKE '%封口%'` → SEALER，否则 MACHINE（旧审计 1.9，本轮不改）。

**真实结果（2026-10-29，简化可见性）**

| 车间 | 节点 | involved | done | 完成率 |
| --- | --- | --- | --- | --- |
| 封口机车间 | 备料 | 625 | 625 | 100% |
| 封口机车间 | 模具生产 | 624 | 600 | 96.2% |
| 封口机车间 | 氧化/喷特氟龙（按需） | 81 | 47 | 58.0% |
| 封口机车间 | 物料配齐 | 600 | 569 | 94.8% |
| 封口机车间 | 组装 | 623 | 560 | 89.9% |
| 封口机车间 | 入库 | 624 | 561 | 89.9% |
| 封口机车间 | 木箱（按需） | 97 | 70 | 72.2% |
| 半自动气调机车间 | 画图→入库 | 31–34 | 26–33 | 79%–97% |
| 全自动气调机车间 | 画图 | 29 | 26 | 89.7% |
| 全自动气调机车间 | 调试 | 27 | 13 | 48.1% |
| 全自动气调机车间 | 入库 | 27 | 14 | 51.9% |
| 全自动气调机车间 | 木箱（按需） | 4 | 0 | 0% |
| 称重机车间 | 8 个节点 | 1 | 1 | 100% |

### 1.2 排产等待

```585:595:backend/src/main/java/com/liqiang/archive/service/ProductionTrackingAnalyticsService.java
        var waitSql="SELECT t.days day,COUNT(*) cnt FROM (SELECT DISTINCT p.id,"
                +"FLOOR(DATEDIFF(DATE(p.schedule_time),DATE(p.source_created_at))) days "
                +"FROM production_orders p "+P_ASG_JOIN+where
                +" AND p.schedule_time IS NOT NULL AND p.source_created_at IS NOT NULL) t "
                +"GROUP BY t.days ORDER BY t.days";
```

- `schedule_time` = 氚云排产时间（V373 补列，仅填空不覆盖），`source_created_at` = 氚云提交时间。
- 前端把 **0–2 天逐天列出、≥3 天合并为一柱**（`waitBuckets`）；概览卡「排产等待 3 天及以上」由 `scheduleWait` 逐天累加（`day >= 3`），下钻 `waitDaysMin=3` ⇒ 概览卡 / 合并柱 / 下钻**三处口径一致**（旧审计 1.10 记录的 `>3` 与 `waitDaysMin=4` 已不是当前代码）。

**真实分布**

| 等待 | 单数 |
| --- | --- |
| 0 天 | 253 |
| 1–2 天 | 384 |
| 3–7 天 | 25 |
| 8–30 天 | 22 |
| 31 天以上 | 2 |
| **≥3 天合计（概览卡数值）** | **49**（其中在制 10 / 已完工 39） |

- 覆盖：有节点记录 689 单，其中 **18 单无排产时间**被静默排除 ⇒ 分布基数 671，与概览「覆盖任务 689 单」对不上且无提示（N10）。
- 当前数据**无负数**天数，但 SQL 未做 `>= 0` 兜底。

### 1.3 里程碑状态取值（关键）

| stage_status | 行数 | 涉及单数 |
| --- | --- | --- |
| DONE | 3383 | — |
| PENDING | 322 | 103 |
| IN_PROGRESS | 14 | 13 |
| **OPEN** | **0** | **0** |

库里**不存在 `OPEN`**，未完成实际写作 `PENDING` / `IN_PROGRESS`。

### 1.4 在制 vs 已完工

| 分类 | 单数 | 占比 |
| --- | --- | --- |
| 已完工（有实际完工日） | 628 | 91% |
| 在制 | 61 | 9% |

在制 61 单中：**15 单预计完成日已过**、3 单无预计完成日。

同一批在制单的**节点完成情况**（与展示的完成率对比）

| 节点 | 在制涉及 | 在制已完成 | 在制完成率 | 抽屉显示的整体完成率 |
| --- | --- | --- | --- | --- |
| 组装 | 61 | 14 | **23%** | 89.9% |
| 入库 | 61 | 12（39+22 口径按车间拆分） | ≈20% | 89.9% |
| 物料配齐 | 31 | 5 | 16% | 94.8% |
| 模具生产 | 39 | 15 | 38% | 96.2% |

---

## 二、问题清单

### N1（P1，功能不可用）节点下钻「未完成」筛选恒返回 0 条

**现象**：点节点 → 右侧工具条「全部 / 已完成 / 未完成」，选「未完成」永远 0 条。

**证据**：

```667:669:backend/src/main/java/com/liqiang/archive/service/ProductionTrackingAnalyticsService.java
            if(status!=null&&!"ALL".equalsIgnoreCase(status)&&!status.isBlank()){
                whereTail+="AND m.stage_status=? ";p.add("DONE".equalsIgnoreCase(status)?"DONE":"OPEN");
            }
```

前端 `statusOptions` 只发 `ALL / DONE / OPEN`，非 DONE 一律落到 `OPEN`；而库内 `OPEN` 行数 = 0（见 1.3）。

**附带**：同一列的前端渲染是 `DONE→已完成 / OPEN→未完成 / 其他→—`，因此 322 行 `PENDING` 与 14 行 `IN_PROGRESS` 在下钻表「该节点」列**全部显示「—」**。

**影响**：直接命中关注点 ④ —— 最想要的那张清单（该节点还没做完的单）取不到，只能看「全部」再人工找。

**建议**：把「未完成」判定从"等于某个状态"改成 `m.stage_status <> 'DONE'`（未来新增状态自动纳入，不再依赖枚举值同步）；前端「该节点」列渲染同步改为 `DONE→已完成 / 其余→未完成`，或保留三态但按实际取值显示。
**范围**：仅该下钻 SQL 与前端渲染，不动聚合口径，不动其他抽屉。

---

### N2（P1）下钻表看不出哪单急

**现象**：节点/等待下钻的附加列只有「提交 / 排产 / 等待(天) / 该节点」，没有预计完成日，也没有是否超期。

**证据**：`nodeAnalyticsOrders` 的 `cols` 未取 `expected_completion_date`；前端 `#extra` 插槽（`:44-51`）只放了四列。同期周复盘下钻已有「当前预计 / 当前超期」两列并在本轮前移到初始可见区。

**数据佐证**：在制 61 单中 15 单预计完成日已过 —— 这 15 单在下钻表里与其余 46 单长得一模一样。

**影响**：关注点 ④ 的另一半 —— 就算取到了清单，也判断不出先处理谁。

**建议**：下钻行增加「当前预计完成」「当前超期（天）」两列（与周复盘同款文案、同类列序），并复用上一轮的做法——需要时通过**可选排序参数**让超期严重的排前面，**不改该接口默认排序**（该接口同样只服务本抽屉，但默认顺序保持 `p.id DESC` 更安全）。
**范围**：后端加两列 + 可选排序；前端加两列。

---

### N3（P1，口径误导）完成率是「历史记录比例」，不是「当前进度」

**现象**：抽屉看到的完成率普遍 89%–100%，但那是因为分母里 91% 是已经完工的历史单。

**证据**：628 / 689 已完工（1.4）；封口机「组装」展示 89.9%，而**在制单里组装只完成 23%**（14/61）。

**影响**：关注点 ① 的核心。当前头部已有一句说明「只说明记录情况，不代表生产瓶颈或全部任务进度」，但这句话**只在抽屉顶部出现一次**，图表区（真正看数字的地方）没有。且旧审计约束 4.1 明确：不得把它改名为「记录完备度」。

**建议（本轮可做，纯文案）**：
1. 每个车间图块标题下方补一句「含历史已完工单，不代表当前在制进度」；
2. 图表 tooltip / label 保留 `done/involved`，但把「涉及 N 单」写进 tooltip 文案（如「已完成 560 / 涉及 623 单（含已完工）· 89.9%」）。

**待决策（超出展示层）**：是否增加「在制维度」（旧审计 2.4 指标②「当前节点在制数」）。那是新口径、需先定"节点适用性"五态规则，属模型层，**本轮不建议夹带**。

---

### N4（P1）「排产等待」是历史分布，不是待办清单

**现象**：概览卡「排产等待 3 天及以上 49 单」点进去，绝大多数是早就做完的单。

**证据**：49 单中**在制 10 单、已完工 39 单（80%）**（1.2）。

**影响**：关注点 ②。跟单员真正需要的是"现在还有哪些单卡在排产前"，而这个数字回答的是"历史上有哪些单排产慢"。

**建议（分两档）**：
- 纯文案（本轮可做）：概览卡副标由「提交→排产 3 天及以上」改为「提交→排产 ≥3 天（含已完工单）」，避免当成待办数。
- 待决策：分布/概览增加「其中在制 N 单」。这需要后端在分布查询里按完工状态多切一维（`actual_completion_date IS NULL`），**属新增维度**，请确认是否接受。

---

### N5（P2）完成率不体现规模，按需节点容易被读成"最差环节"

**证据**：封口机「氧化/喷特氟龙」58% 是全表最低，但只涉及 81 单；「木箱」72% 涉及 97 单；而「备料」100% 涉及 625 单。柱长只编码百分比，规模只在 label 的小字里。

**建议**（纯前端）：按需节点（或 involved 明显小于该车间覆盖数的节点）在 tooltip 补一句「仅涉及 N 单，非全部任务适用」。不改变任何数字。

---

### N6（P2）车间展示：权重失衡 + 排序靠名称启发式

**现象/证据**：

```89:92:frontend/src/components/production/TrackingNodeAnalytics.vue
  return groups.sort((a, b) => {
    const priority = (name: string) => name.includes('封口机') ? 0 : 1
    return priority(a.workshop) - priority(b.workshop) || a.workshop.localeCompare(b.workshop, 'zh-CN')
  })
```

- 4 个车间：封口机车间 625、半自动气调机车间 34、全自动气调机车间 29、**称重机车间 1 单**；每个车间一个等宽图块，1 单的车间的 8 个节点全是 100%，噪音大。
- `includes('封口机')` 是字符串启发式（当前恰好命中"封口机车间"）；后端另有一处 `LIKE '%封口%'` 判 SEALER/MACHINE（旧审计 1.9）—— 两处都不是读 `workshop_kind` 显式列。

**建议**（纯前端）：① 图块标题带单数（「封口机车间 · 节点完成率 · 625 单」）；② 排序改为按覆盖单数降序（数据已在前端，无需后端）；③ 单量过少的车间标注「样本过少」或折叠（阈值需你定）。

---

### N7（P2）刷新后下钻结果与图表不一致

**现象**：点「刷新数据」只执行 `load()`（重载聚合 + 重绘图表），**右侧下钻面板保留上一次的结果且不重查**；抽屉关闭再打开同样残留上次下钻。

**证据**：`ProductionTrackingNodeAnalyticsDrawer.vue:25` `refresh()` → `contentRef.value?.load()`；`TrackingNodeAnalytics.vue:155-162` 的 `load()` 只处理 `data` 与图表，`drillState` / `ddItems` 未动。

**建议**：`load()` 结束时若 `drillState` 非空则重跑一次下钻；抽屉关闭（`modelValue` 转 false）时清空 `drillState`。

---

### N8（P2）未说明统计范围与时间窗

**现象**：接口无参数、无时间窗，是"当前跟单覆盖的全部任务"的存量视角；生产分析与周复盘都有周期/区间筛选，唯独这里没有。

**建议**：头部说明补一句「统计范围：当前跟单覆盖的全部任务（含已完工），不含周期筛选」。纯文案。

---

### N9（P3）`scheduleWaitAnomalies` 无消费者

后端仍执行 `LIMIT 50` 的异常单查询并放入响应（`Service:597-619`），前端**只在类型声明里出现**（旧审计 1.8 / 实施表第 11 项已列）。本轮仅确认现状未变，建议作为后续清理项，不与本轮混做。

---

### N10（P3）18 单无排产时间被静默排除

**证据**：689 单中 18 单 `schedule_time IS NULL` ⇒ 分布基数 671，与概览「覆盖任务 689 单」不一致，界面无任何说明。

**建议**：分布图下方标注基数与排除数（纯文案）。另：SQL 无 `days >= 0` 兜底，当前数据无负数，属健壮性建议，可不动。

---

## 三、明确不做（本轮边界）

| 不做 | 原因 |
| --- | --- |
| 节点依赖模型 / 并行工序 / `predecessor_stage_codes` | 旧审计 2.5，已决策暂缓 |
| 历史快照、统计时间窗口（asOf） | 旧审计 1.1–1.3、2.1–2.3，已决策暂缓 |
| 「当前节点在制数」等指标 ②、节点异常指标 ③ | 旧审计 2.4，属模型层，需先定适用性五态 |
| 车间识别改读 `workshop_kind` 显式列 | 旧审计实施表第 9 项；当前数据下判定正确，影响面大于展示优化 |
| 已验收的「生产交期达成分析」「跟单周复盘」两个抽屉 | 用户明确不触碰 |
| 六类原因词典 / 逾期等级 / 周期预设 | 与上一轮边界一致 |

---

## 四、建议的实施批次（待你确认后执行）

| 批次 | 内容 | 性质 |
| --- | --- | --- |
| 第一批 | N5（按需节点规模提示）、N6（标题带单数 + 按量排序）、N7（刷新一致性）、N8 + N4 前半（范围与「含已完工」说明）、N10（基数提示） | 纯前端/文案，不动接口 |
| 第二批 | N1（未完成判定改 `<> 'DONE'` + 前端三态渲染）、N2（下钻加「当前预计 / 当前超期」两列 + 可选紧急度排序，默认顺序不变） | 后端受限改动 |

**需你先决策的两项**：
1. N3 后半 —— 是否在制维度（新指标，模型层）；
2. N4 后半 —— 排产等待是否按「在制 / 已完工」拆分（新维度）。

若两项都暂缓，第一批 + 第二批落地后，抽屉能做到的上限是：「口径说得清楚、清单能筛出未完成、能看出哪单超期」，但完成率仍将是历史比例 —— 这点需要靠文案兜住，不能靠改名。

---

## 附录：核查 SQL（只读，可复核）

```sql
-- 1) 制程表
SELECT workshop_kind,stage_code,stage_label,sort_no,optional,enabled
  FROM production_tracking_stage_defs ORDER BY workshop_kind,sort_no;

-- 2) 里程碑状态取值
SELECT stage_status,COUNT(*) FROM production_tracking_milestones GROUP BY stage_status;
SELECT COUNT(*) FROM production_tracking_milestones WHERE stage_status='OPEN';   -- = 0

-- 3) 节点完成率（简化可见性，不含权限收敛）
SELECT COALESCE(NULLIF(p.production_line,''),'未分车间') ws,d.stage_code,d.stage_label,
       COUNT(DISTINCT p.id) involved,
       COUNT(DISTINCT CASE WHEN m.stage_status='DONE' THEN p.id END) done
  FROM production_orders p
  JOIN production_tracking_milestones m ON m.production_order_id=p.id
  JOIN production_tracking_stage_defs d ON d.stage_code=m.stage_code AND d.enabled=1
       AND d.workshop_kind=CASE WHEN p.production_line LIKE '%封口%' THEN 'SEALER' ELSE 'MACHINE' END
 GROUP BY ws,d.stage_code,d.stage_label,d.sort_no ORDER BY ws,d.sort_no;

-- 4) 排产等待覆盖与分布
SELECT COUNT(DISTINCT p.id) covered,
       SUM(p.schedule_time IS NULL) no_schedule,
       SUM(p.source_created_at IS NULL) no_created
  FROM production_orders p JOIN production_tracking_milestones m ON m.production_order_id=p.id;

SELECT CASE WHEN d<0 THEN 'negative' WHEN d=0 THEN '0' WHEN d<=2 THEN '1-2'
            WHEN d<=7 THEN '3-7' WHEN d<=30 THEN '8-30' ELSE '31+' END bucket, COUNT(*) cnt
  FROM (SELECT DISTINCT p.id,FLOOR(DATEDIFF(DATE(p.schedule_time),DATE(p.source_created_at))) d
          FROM production_orders p JOIN production_tracking_milestones m ON m.production_order_id=p.id
         WHERE p.schedule_time IS NOT NULL AND p.source_created_at IS NOT NULL) t
 GROUP BY bucket;

-- 5) 已完工 / 在制
SELECT CASE WHEN p.actual_completion_date IS NULL THEN '在制' ELSE '已完工' END kind,
       COUNT(DISTINCT p.id) cnt
  FROM production_orders p JOIN production_tracking_milestones m ON m.production_order_id=p.id
 GROUP BY kind;

-- 6) 在制单的节点完成情况 & 超期
SELECT d.stage_label,
       COUNT(DISTINCT p.id) involved_open,
       COUNT(DISTINCT CASE WHEN m.stage_status='DONE' THEN p.id END) done_open
  FROM production_orders p
  JOIN production_tracking_milestones m ON m.production_order_id=p.id
  JOIN production_tracking_stage_defs d ON d.stage_code=m.stage_code AND d.enabled=1
       AND d.workshop_kind=CASE WHEN p.production_line LIKE '%封口%' THEN 'SEALER' ELSE 'MACHINE' END
 WHERE p.actual_completion_date IS NULL
 GROUP BY d.stage_label,d.sort_no ORDER BY d.sort_no;

SELECT COUNT(DISTINCT p.id) open_orders,
       COUNT(DISTINCT CASE WHEN p.expected_completion_date IS NULL THEN p.id END) no_expected,
       COUNT(DISTINCT CASE WHEN p.expected_completion_date < CURDATE() THEN p.id END) overdue
  FROM production_orders p JOIN production_tracking_milestones m ON m.production_order_id=p.id
 WHERE p.actual_completion_date IS NULL;

-- 7) 等待 ≥3 天的构成
SELECT CASE WHEN p.actual_completion_date IS NULL THEN '在制' ELSE '已完工' END kind,COUNT(DISTINCT p.id) cnt
  FROM production_orders p JOIN production_tracking_milestones m ON m.production_order_id=p.id
 WHERE p.schedule_time IS NOT NULL AND p.source_created_at IS NOT NULL
   AND FLOOR(DATEDIFF(DATE(p.schedule_time),DATE(p.source_created_at)))>=3
 GROUP BY kind;
```

**说明**：以上查询用「有里程碑记录」近似接口的 `visible()` 范围，未叠加 `MANUFACTURING` 数据权限收敛（SELF/DEPARTMENT 会比这里更少）。结论性比例（91% 已完工、49 单中 39 单已完工）在管理员全量口径下成立；跟单员视角绝对值更小，但**构成比例不受影响**（权限收敛按业务员/部门，与完工状态无关）。

**另注**：`tools/_verify_node_analytics.java`、`tools/_verify_node_orders.java` 是此前会话留下的未跟踪验证脚本，本轮未使用、未删除（可能有并行会话在用），如需清理请另行确认。
