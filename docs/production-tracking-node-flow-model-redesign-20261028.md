# 生产节点流程模型改造设计（2026-10-28）

> ## ⏸️ 状态：远期设计储备 · 暂缓实施（2026-10-28 定）
>
> **不要按本文创建迁移或改造算法。** 节点前置依赖建模与并行工序预测不属于 LQXM 生产跟单当前建设阶段。
>
> **保留价值**：§2.2「现有推算算法事实」、§2.3「`MATERIAL_READY` 缺口（待库验证）」、§6.4「交期终点定义」继续有效，供未来参考。
> **暂缓范围**：`predecessor_stage_codes` 建模、S3 并行预测算法、参考工期扩建。
>
> ---
>
> **状态：设计方案，未实施。**
> 本文不涉及任何已执行的迁移、不改生产库、不改业务代码。文内 SQL 与算法描述均为**草案**。
>
> **上游**：`docs/production-tracking-analytics-audit-and-decisions-20261028.md`
> **决策依据**：2026-10-28 业务决策（见 §1）
> **性质**：生产流程模型治理，**独立于数据分析抽屉开发**

---

## 1. 已确认的决策

| 项 | 决策 |
| --- | --- |
| 建模方式 | 新增显式 `predecessor_stage_codes`，**不采用相同 `sort_no` 表示并行** |
| `sort_no` | **保留为展示顺序**，不承担流程逻辑 |
| 首节点（无前置） | 以**排产日期** `schedule_time` 作为参考工期起算锚点 |
| 单个前置 | 以前置节点完成日期作为推算依据 |
| 多个前置 | 必须明确是"全部完成才能开始"还是其他条件；**全部完成时取最晚完成日期** |
| 依赖校验 | 不允许循环依赖、跨不兼容制程引用、引用不存在的节点 |
| 历史数据 | 历史节点数据**不因制程定义调整而直接覆盖** |
| 旧节点编码 | 保留（`stage_code` 一经产生里程碑记录不可修改） |
| **并行参考完成日公式** | **本轮不定死**，需结合车间实际流程与现有推算代码验证后再定 |
| 2.6 按需节点纳入 | **必经节点 + 已确认适用的按需节点**均纳入在制分析，分别标识；**保留"待确认"态**；跟单完成判定保持 `requiredStages()` 原规则 |
| 交期预测终点 | **保留** `deliveryStages()` 在 `WAREHOUSE` 截断；入库后工序（木箱）可进过程进度统计，**不进交期预测** |
| 参考工期三态 | 正数 = 有效工期 / `0` = **有效工期且为 0 天** / `NULL` 或无记录 = 无参考工期 |
| 迁移版本号 | **不预先永久占用** V425；创建迁移文件前须重新核对仓库最新版本 |

---

## 2. 现状

### 2.1 制程表（V420）

```16:31:backend/src/main/resources/db/migration/V420__production_tracking_stage_defs.sql
CREATE TABLE IF NOT EXISTS production_tracking_stage_defs (
  ...
  sort_no INT NOT NULL COMMENT '制程顺序（升序）；决定节点条顺序与链式推算顺序',
  optional TINYINT(1) NOT NULL DEFAULT 0 COMMENT '1=按需工序：不计入完成分母，按关键词判定本单是否适用',
  match_keywords VARCHAR(200) NULL COMMENT '按需工序：可选配置/生产要求命中该正则即本单适用',
  exclude_keywords VARCHAR(200) NULL COMMENT '按需工序：命中该正则（否定表达）则本单不适用',
```

`sort_no` 是**单值全序**，注释明确它同时承担「显示顺序」与「**链式推算顺序**」两个职责。当前播种：

| 类别 | 节点序列（`sort_no`） |
| --- | --- |
| SEALER | 备料(1) → 模具生产(2) → 氧化/喷特氟龙(3,按需) → 物料配齐(4) → 组装(5) → 入库(6) → 木箱(7,按需) |
| MACHINE | 画图(1) → 备料(2) → 钣金(3) → 模具(4) → 组装(5) → 调试(6) → 入库(7) → 木箱(8,按需) |

**约束**：`stage_code` 一经产生里程碑记录即不可修改（V420 第 10-11 行）⇒ 改造只加列。

### 2.2 现有推算算法（关键）

`ProductionTrackingSchedulePolicy.calculate()` —— **单游标线性链**，纯函数、无外部依赖：

```36:44:backend/src/main/java/com/liqiang/archive/service/ProductionTrackingSchedulePolicy.java
        LocalDate cursor=scheduled;
        for(String code:active){
            LocalDate done=completed.get(code);
            if("DONE".equals(statuses.get(code))&&done!=null){cursor=done;continue;}
            LocalDate override=manual.get(code);
            BigDecimal days=intervals.get(code);
            cursor=override!=null?override:cursor!=null&&days!=null
                    ?cursor.plusDays(Math.round(days.doubleValue())):null;
        }
        Long slack=deadline!=null&&cursor!=null?ChronoUnit.DAYS.between(cursor,deadline):null;
```

**已核实的事实**

| # | 事实 | 证据 |
| --- | --- | --- |
| F1 | 首节点锚点 = `scheduled`（**排产时间**），与 V401 口径一致 | `:36` `cursor = scheduled` |
| F2 | 严格**单游标线性推进**，无并行概念 | `:37-44` 单 `cursor` 顺序累加 |
| F3 | 参与节点 = 必经节点 + 适用的按需节点，**在 `WAREHOUSE` 处截断**（入库后节点不参与交期） | `deliveryStages():56-63` |
| F4 | 前向输出 `predictedFinishDate` + `slackDays` + `risk`（`PRESSURE`/`NO_BUFFER`/`ON_TRACK`/`UNKNOWN`） | `:45-47` |
| F5 | 反向输出 `latestStartDate` + `targets` + `risk`（`NO_BASELINE`/`SCHEDULE_LATE`/`SCHEDULE_DUE`/`SCHEDULE_NORMAL`） | `:23-34` |
| F6 | `manual.get(code)` 覆盖推算值 ⇒ `manual_reference_date` 是**预测完成日**，不是开始日 | `:40-42`；另见 `ProductionTrackingService:2893-2896` |

### 2.3 现有推算的一个连带后果（本轮新发现）

```50:54:backend/src/main/java/com/liqiang/archive/service/ProductionTrackingSchedulePolicy.java
    static boolean hasCompleteProductBaseline(List<String> codes,Map<String,BigDecimal> intervals,
                                               Map<String,Boolean> applicable){
        var active=deliveryStages(codes,applicable);
        return !active.isEmpty()&&active.contains("WAREHOUSE")&&active.stream().allMatch(code->intervals.get(code)!=null);
    }
```

`MATERIAL_READY`（物料配齐）在 V420 中为**必经节点**（`optional=0`，`sort_no=4`），而 **`V401` 只播种了 4 个节点的参考工期，不含 `MATERIAL_READY`**。

⇒ 若生产库中确实没有 `MATERIAL_READY` 的行，则 `intervals.get("MATERIAL_READY") == null` ⇒ `allMatch` 为 false ⇒ **`hasCompleteProductBaseline` 对 SEALER 车间恒为 false**，封口机的产品级基准完整度判定与相关推算能力均不可用。

> ⚠️ **本条为源码推断，尚未经生产库验证。** 该结论建立在「V401 播种内容 = 生产库现状」这一假设上，但参考工期表**曾因 UI 全量保存被清空过一次**（V401 记录），且 `manufacturing.baseline.manage` 持有者可随时增删改。⇒ 实际状态必须以库内记录为准，见 §6.3 核查语句。
>
> 若核实确认缺失，补 `MATERIAL_READY` 参考工期是**启用任何封口机工期推算的前置条件**。

---

## 3. 冲突分析（本设计的首要风险）

> 用户明确要求：避免新模型与旧算法同时生效而产生冲突。

**冲突点**：`ProductionTrackingSchedulePolicy` 是详情 / 列表侧 `systemReferenceDate`、`predictedFinishDate`、`risk` 的**唯一推算入口**。若只给节点分析引入 `predecessor_stage_codes` 而不改造此处，将出现：

| 位置 | 算法 | 并行节点表现 |
| --- | --- | --- |
| 详情 / 列表（旧） | 单游标线性 | 串行累加 ⇒ 参考完成日**系统性偏长** |
| 节点分析（新） | 显式前置依赖 | 取前置最晚完成日 ⇒ 正确 |

⇒ **同一张工单在详情页与节点分析抽屉出现两个不同的"参考完成日"**。这是必须避免的。

**处理原则**

1. `predecessor_stage_codes` **先落字段、不改算法**（字段为 NULL 时按 `sort_no` 兜底，行为完全不变）
2. 算法改造**单独一个阶段**，且必须同时覆盖所有消费方（详情、列表、节点分析）
3. 在旧算法完全切换前，**节点分析不得输出"超参考工期"类指标**——否则就是两套算法同时对外

---

## 4. 数据模型变更（草案，未执行）

### 4.1 迁移编号

截稿时已执行最大 **V423**，快照整改草案顺延取 **V425**，本文草案取 **V426**。

> ⚠️ **编号已被实际冲突验证为不可预留**（2026-10-28 实测）：工作区已存在未跟踪的 `V424__create_website_company_milestones.sql`（官网公司里程碑，另一条并行开发流），原定的 V424 / V425 均已不可直接用。
>
> ⇒ 实际创建迁移文件前**必须重新核对** `backend/src/main/resources/db/migration/` 下的最大版本号与所有未跟踪文件并顺延。**禁止直接套用本文编号。**
>
> 本设计只保证**顺序**：节点流程模型迁移须晚于快照迁移，二者均须晚于当时仓库实际最大版本。

### 4.2 草案 `V425__tracking_stage_predecessors.sql`

```sql
-- 制程节点显式前置依赖（草案，未执行）
--
-- 背景：sort_no 是单值全序，无法表达并行工序（如推杆机 / 转盘机的钣金加工与模具生产并行）。
--       本脚本只加列，不改 sort_no 语义的数据，不改任何 stage_code。

ALTER TABLE production_tracking_stage_defs
  ADD COLUMN predecessor_stage_codes VARCHAR(200) NULL
    COMMENT '前置节点编码，逗号分隔；NULL = 尚未配置（按 sort_no 顺序兜底）' AFTER sort_no,
  ADD COLUMN predecessor_mode VARCHAR(20) NOT NULL DEFAULT 'ALL_DONE'
    COMMENT '多前置时的合并规则：ALL_DONE=全部完成才起算（取最晚完成日）；SINGLE=仅声明用' AFTER predecessor_stage_codes;

-- 说明：不设置外键。stage_code 可跨 workshop_kind 重名（如 MATERIAL 两边都有），
--       前置引用必须在同一 workshop_kind 内解析，由应用层校验（见 §5）。
```

### 4.3 语义定义

| `predecessor_stage_codes` | 含义 | 起算锚点 |
| --- | --- | --- |
| `NULL` / 空串 | 首节点（或尚未配置） | `production_orders.schedule_time`（排产时间） |
| 单个编码 | 依赖该节点 | 前置节点 `completed_date` |
| 多个编码（`ALL_DONE`） | 全部完成才能开始 | **各前置 `completed_date` 的最大值** |
| 多个编码（`SINGLE`） | 保留，语义待定 | 暂不实现 |

**未配置时兜底**：按同一 `workshop_kind` 内 `optional=0` 且 `sort_no` 小于当前节点的**最近一个**节点作为前置。⇒ 存量行为完全不变。

---

## 5. 依赖校验规则

配置保存时由应用层校验，**不允许**以下情形落库：

| 规则 | 说明 |
| --- | --- |
| R1 存在性 | 被引用的 `stage_code` 必须在**同一 `workshop_kind`** 内存在且 `enabled=1` |
| R2 跨制程 | 不允许引用其他 `workshop_kind` 的节点（跨不兼容制程引用） |
| R3 自引用 | 不允许引用自身 |
| R4 无环 | 依赖图必须是有向无环图（DFS 检测），发现环即拒绝 |
| R5 顺序一致性 | 前置的 `sort_no` 应小于自身（仅告警，不阻断；并行场景允许同序号） |

**停用 / 删除的保护**（沿用 V420 既有策略并扩展）：

- 有里程碑记录的节点**只允许停用**（`enabled=0`），不允许删除
- **新增**：若某节点被其他节点的 `predecessor_stage_codes` 引用，停用前必须解除引用，否则拒绝

**历史数据保护**：制程定义调整**不影响**已有 `production_tracking_milestones` 行；`stage_code` 不改，历史记录不成为孤儿。

---

## 5.4 节点适用性纳入规则（决策 2.6 · 2026-10-28 已确认）

> **核心**：所有**适用**的生产工序都应纳入当前在制节点分析，但**必经节点与按需节点必须分别标识，不能混为一谈**。

| 节点类型 | 进入节点分析 | 进入风险分析 | 纳入跟单完成判定 |
| --- | --- | --- | --- |
| 必经节点 | 是 | 有参考依据时参与 | 是 |
| **已确认适用的按需节点** | 是 | 有参考依据时参与 | 按现有完成规则，**不擅自改变** |
| 明确不适用的按需节点 | 否 | 否 | 否 |
| **适用性尚未确定的按需节点** | **单独提示"待确认"** | **暂不判定异常** | **不直接认定为未完成** |

**两个概念必须分开**

- **跟单完成判定** → 继续遵循 `requiredStages()` 口径，**不变**
- **生产过程分析** → 必经工序 + **已确认适用的**按需工序

⇒ 例：封口机订单需要「氧化/喷特氟龙」，该工序即使非必经，也应出现在该订单的生产风险分析中；否则生产分析会**遗漏真正影响交期的环节**。

**关键约束：未命中关键词 ≠ 明确不适用**

`configuredOptionalStage()` 依赖可选配置与任务单备注的关键词判定适用性（`ProductionTrackingPolicy:57-65`）。**没有命中关键词，不代表业务上明确不适用**。

⇒ 新统计模型**必须保留"待确认"状态**：

| 禁止 | 要求 |
| --- | --- |
| ❌ 把未命中的按需节点直接归入"不适用" | 归入"待确认"，单独提示跟单员确认 |
| ❌ 把未命中的按需节点直接计入"未完成" | 不参与未完成计数 |
| ❌ "待确认"节点参与异常/风险判定 | 暂不判定异常 |

**与现有实现的关系**

| 现有能力 | 位置 | 复用方式 |
| --- | --- | --- |
| `requiredStages(workshop)` 必经节点 | `ProductionTrackingPolicy:36-40` | 直接复用 |
| `isOptionalStage(code, workshop)` | 同上 `:52-55` | 直接复用 |
| `configuredOptionalStage(code, config, remark)` | 同上 `:63-65` | 判定"已确认适用" |
| `applicable` 字段 | `ProductionTrackingService:2635` | 详情链路已算，可作为"待确认"判据的输入 |

⇒ **适用性判定不需要重新设计**，只需把"未命中"从二元判定扩展为三态（适用 / 不适用 / **待确认**）。

### 5.5 最终指标定义（三个指标互不等价）

> **核心原则：节点未完成数 ≠ 节点异常数。**

| 指标 | 核心含义 | 分母 / 判据 | 启用阶段 |
| --- | --- | --- | --- |
| ① 节点记录完成率 | 已有记录中已完成的比例 | 已完成 ÷ **有该节点记录**的任务 | 现状保留，**不重命名**（见审计文档约束 4.1） |
| ② 当前在制节点未完成数 | 当前在制任务中**适用**但尚未完成该节点的数量 | 在制任务 × 适用节点（必经 + 已确认适用的按需） | S4 |
| ③ 节点风险任务数 | 按**有效参考日期** + 实际进度 + 风险规则识别的任务数 | 依赖统一后的预测算法 | **S3 完成后才可启用** |

**约束**

- ① 不得改称"数据记录完备度"——真正的完备度须以**适用节点数**为分母，是另一个独立指标
- ② 按 §5.4 四象限判定适用性；"待确认"节点不计入未完成
- ③ **必须等 S3 并行预测算法统一后启用**；在 S3 完成前，节点分析**不得**输出任何"超参考工期"类指标，否则两套算法同时对外（见 §3）

---

## 6. 参考工期原则（已确认口径）

| 事项 | 确定口径 |
| --- | --- |
| `typical_days = 0` | **有效参考值，不等于缺失**（备料 / 入库零天占 90%，V401 刻意保留） |
| 无参考工期记录 | 显示「**暂无参考值**」，不参与异常判定 |
| 人工参考日期 `manual_reference_date` | **预测完成日期**，不能作为节点开始日；已完成节点禁止填写，填写须说明原因 |
| 首节点时间锚点 | **排产时间** `schedule_time`（V401 口径） |
| 后续节点 | 按**显式前置依赖**推算 |
| 全量保存参考设置 | **不允许**因空值提交而无提示删除有效配置 |
| 参考工期新增或修改 | 保留修改记录，避免无痕覆盖 |

### 6.1 「0 天」与「缺失」必须区分

| 情形 | 表现 | 处理 |
| --- | --- | --- |
| 已发布 `typical_days = 0.0` | 表中**有行**，值为 0 | 有效参考值，正常参与推算 |
| 未配置 | 表中**无行** | 显示「暂无参考值」，不推算 |

⇒ UI 与统计**不得**把真实的 0 天误判为缺失，也**不得**把缺失填成 0。

### 6.2 参考设置保存模式改造（独立子项）

**现状问题**（`V401` 记录，已发生过一次）：

```1:6:backend/src/main/resources/db/migration/V401__tracking_node_benchmarks_restore_sealer.sql
-- 通用参考（车间级兜底）恢复封口机 4 个节点（2026-10-22）。
--
-- 背景：V382 播种过这 4 行，但 production_tracking_node_benchmarks 现在是 0 行——
-- 「通用参考设置」是全量提交、清空即删行，一次空值保存就把整表清掉了。
-- 后果不只是通用参考没了：50 个产品里 43 个没有任何产品级发布值，两级都空 ⇒
-- 这些产品的节点参考日期与「生产工期」全部推不出来。
```

**改造目标**：全量覆盖保存 → **按项更新 + 明确删除 + 记录审计**

| 项 | 要求 |
| --- | --- |
| 保存语义 | 按 `(workshop_kind, stage_code)` **逐项 upsert**，不因未提交的项删除既有行 |
| 删除 | 独立删除动作，二次确认，逐项执行 |
| 空值提交 | **拒绝并提示**，不得静默清空 |
| 审计 | 记录变更前后值、操作人、时间（可复用现有审计日志设施） |

**入口**：`ProductionTrackingService:1841`「保存节点参考基准」，权限 `manufacturing.baseline.manage`（`ProductionTrackingController:451-533`）。

### 6.3 库内现状核查（实施前必做）

本节全部结论（含 §2.3）在**未连库**情况下均属推断，实施前必须执行：

```sql
-- 1) 参考工期实际覆盖（验证 §2.3 的 MATERIAL_READY 缺口是否真实存在）
SELECT workshop_kind, stage_code, typical_days, sample_count, computed_at
FROM production_tracking_node_benchmarks ORDER BY workshop_kind, stage_code;

-- 2) 必经节点中缺参考工期的（断链点）
SELECT d.workshop_kind, d.stage_code, d.stage_label, d.sort_no
FROM production_tracking_stage_defs d
LEFT JOIN production_tracking_node_benchmarks b
  ON b.workshop_kind = d.workshop_kind AND b.stage_code = d.stage_code
WHERE d.enabled = 1 AND d.optional = 0 AND b.id IS NULL
ORDER BY d.workshop_kind, d.sort_no;

-- 3) 区分「真实 0 天」与「无记录」（二者语义不同，见 §6.1）
SELECT workshop_kind,
       SUM(typical_days > 0)  positive_days,
       SUM(typical_days = 0)  zero_days,
       COUNT(*)               configured
FROM production_tracking_node_benchmarks GROUP BY workshop_kind;
```

**判定**：若查询 2 对 SEALER 返回 `MATERIAL_READY`，则 §2.3 成立，`hasCompleteProductBaseline` 对封口机恒 false；否则该推断作废。

### 6.4 交期预测终点：保留 `WAREHOUSE` 截断

现行 `deliveryStages()` 在 `WAREHOUSE` 处截断（入库后工序如木箱不参与交期倒排）：

```56:63:backend/src/main/java/com/liqiang/archive/service/ProductionTrackingSchedulePolicy.java
    private static List<String> deliveryStages(List<String> codes,Map<String,Boolean> applicable){
        var filtered=codes.stream().filter(code->!ProductionTrackingPolicy.isOptionalStage(code)
                ||Boolean.TRUE.equals(applicable.get(code))).toList();
        // #39: 交期口径终点＝入库（WAREHOUSE）；入库后的节点（如木箱）不参与交期倒排与预测完成。
        int warehouseIdx=filtered.indexOf("WAREHOUSE");
        return warehouseIdx>=0&&warehouseIdx<filtered.size()-1
                ?filtered.subList(0,warehouseIdx+1):filtered;
    }
```

**决策**：**保留**该定义，**本次改造不同步改变**。

| 场景 | 是否纳入 |
| --- | --- |
| 交期预测 / 倒排 / `predictedFinishDate` | ❌ 入库后工序**不参与**（维持现状） |
| 生产过程进度统计（在制节点分析） | ✅ 可纳入展示 |
| 节点风险分析 | 待业务确认后再定 |

⇒ 需要展示木箱等入库后工序时，走**过程进度统计**；**未经业务确认不得**重新纳入交期预测。

---

## 7. 分阶段实施

| 阶段 | 内容 | 是否改变现有行为 | 前置 |
| --- | --- | --- | --- |
| **S1 · 落字段** | V425 加列；配置页支持编辑前置；校验规则 R1-R5 | ❌ 否（全 NULL，走 `sort_no` 兜底） | 无 |
| **S2 · 补数据** | 车间确认实际流程；配置并行节点前置；补 `MATERIAL_READY` 参考工期 | ❌ 否（不配置即不变） | S1 |
| **S3 · 算法改造** | `ProductionTrackingSchedulePolicy` 改为按前置依赖推进；**同步覆盖所有消费方** | ⚠️ 是 | S2 + §8 公式确认 |
| **S4 · 节点分析接入** | 新在制口径与异常指标按新模型输出 | ⚠️ 是 | S3 |

> **S3 与 S4 不可颠倒**。在 S3 完成前，节点分析**不得**输出"超参考工期"类指标。

---

## 8. 开放项：并行工序参考完成日的计算公式（本轮不定死）

按用户要求，**本轮只记录问题与约束，不定公式**。

**待确认清单**

| # | 待确认项 |
| --- | --- |
| Q1 | 推杆机 / 转盘机的实际并行关系：钣金加工与模具生产是**完全并行**还是**部分重叠** |
| Q2 | 并行分支汇合点的语义：后续节点（如组装）的前置是"全部完成"还是"任一完成" |
| Q3 | 并行分支的工期如何影响 `predictedFinishDate`：取分支最长？取关键路径？ |
| Q4 | 反向倒排 `latestStartDate` 在并行下的计算方式（现有 `:23-34` 是线性回推） |
| Q5 | `deliveryStages()` 在 `WAREHOUSE` 处截断的规则，在并行下是否仍成立 |
| Q6 | 并行分支中某分支 `manual_reference_date` 被填写时，如何与另一分支合成 |

**约束（无论公式如何，均须满足）**

1. 不得与 `ProductionTrackingSchedulePolicy` 现有算法**同时对外生效**（同一工单不得出现两个参考完成日）
2. 无参考工期的分支显示「暂无参考值」，**不参与**该分支的推算；不得默认 0 天
3. 计算结果必须可解释：能在 UI 上说明"该日期由哪些前置、按什么规则推出"
4. 历史已产生的里程碑数据不被重算覆盖

---

## 9. 验收标准

| # | 阶段 | 验收项 | 判定 |
| --- | --- | --- | --- |
| B1 | S1 | 加列后所有现有推算结果零差异 | 改造前后对同一批工单输出的 `systemReferenceDate` / `predictedFinishDate` 逐单比对 |
| B2 | S1 | 配置循环依赖被拒绝并返回明确错误 | R4 |
| B3 | S1 | 跨 `workshop_kind` 引用被拒绝 | R2 |
| B4 | S1 | 被引用节点无法被停用/删除，除非先解除引用 | 数据完整性 |
| B5 | S2 | `MATERIAL_READY` 补工期后，`hasCompleteProductBaseline` 对 SEALER 不再恒为 false | 直接调用验证 |
| B6 | S3 | 并行节点的参考完成日**不再**累加另一分支工期 | 构造钣金/模具并行样例比对 |
| B7 | S3 | 详情页与节点分析抽屉对同一工单输出**同一个**参考完成日 | 冲突消除 |
| B8 | S6 | 空值提交参考设置不再删除既有行，且有提示 | §6.2 |
| B9 | 全程 | 任何 `stage_code` 未被修改，历史里程碑记录不成为孤儿 | V420 约束 |
| B10 | S1 | 依赖图无环；被发现环时拒绝保存并给出环路径 | R4 |
| B11 | S3 | 多前置按 `ALL_DONE` 取**最晚完成日**；`SINGLE` 未实现时不得静默按 `ALL_DONE` 处理 | §4.3 |
| B12 | S3 | `manual_reference_date` 作为**预测完成日**参与推算，不被当作节点开始日；已完成节点仍禁止填写 | F6 / §6 表 |
| B13 | S3 | 缺参考工期的节点**不猜测**：显示「暂无参考值」，不参与推算，不默认 0 天 | §6.1 |
| B14 | S3 | 真实 `typical_days = 0` 被正常参与推算，不被当作缺失 | §6.1 三态 |
| B15 | S3 | **列表与详情**对同一工单输出的参考完成日与节点分析一致（无两套算法同时对外） | §3 冲突分析 |
| B16 | S4 | 未命中关键词的按需节点进入「**待确认**」，既不计入不适用也不计入未完成 | §5.4 |
| B17 | S4 | 已确认适用的按需节点进入在制分析，且**不改变** `requiredStages()` 的跟单完成判定 | §5.4 |
| B18 | S3 | 交期预测仍在 `WAREHOUSE` 截断；木箱等入库后工序不进交期预测 | §6.4 |

---

## 10. 与快照整改的关系

- 本设计**独立**于《历史快照整改设计》
- 快照当前只固化 `analyzeUncut`（交期达成口径），**不含节点分析**
- ⇒ 节点口径在 S4 稳定前，**不得**扩展 `snapshot_json` 纳入节点指标
