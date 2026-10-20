# 生产跟单 · 节点适用性设计（2026-10-16）

> ⛔ **已决定不建设（2026-10-19）**：「节点适用性三态 / 任务级节点模板」不做。节点侧维持现状（固定节点 + 按需工序 + 人工结案），不再把适用性作为任何下游功能（含数据分析、节点基准校准）的前提。本文**留作溯因，不是待办清单**。
> 后续若要研究真实节点耗时，从记录质量、完成日期、样本可比性入手 —— 见 `production-tracking-analytics-design-20261019.md` §8 P1。
>
> 对应 LQ 提的 P1「固定生产节点不完全适合真实业务」（已改由其它途径解决，见上）。
> 本文只做**方案与落地清单**，不含已实现声明；真库影响面待 DB 凭据可用后补测（探针 `tools/_probe_stages.java` 已备好）。

## 1. 现状取证（代码事实）

| # | 事实 | 位置 |
|---|---|---|
| 1 | 只有两套节点模板，靠车间名 `production_line LIKE '%封口%'` 二选一 | `ProductionTrackingPolicy.isSealer/stageCodes`（37-39 行） |
| 2 | `MACHINE_STAGES` 8 个 / `SEALER_STAGES` 6 个；`OPTIONAL_STAGES={OXIDATION,WOODEN_CRATE}` | Policy 12-19 行 |
| 3 | **只有那两道按需工序有"适用性"**；其余 6~7 道无条件必经，永远无法标"这台不用走" | Policy 41-44 行 |
| 4 | `applicable` 是**运行时推导、不持久化**：`!optional \|\| 有节点行 \|\| 配置写明` | Service 1606 / 1696 行 |
| 5 | 节点状态枚举固定为 `PENDING/IN_PROGRESS/DONE` | Service 38 行 |
| 6 | 完成率分母＝车间固定必经数（封口 4 / 机器 6）＋适用的按需工序 | Service 39、82-85 行 |
| 7 | ⚠️ 节点集合有**两份独立实现**：Java 侧 `Policy.requiredStages` vs SQL 侧 `TRACKING_FROM` 里硬编码的 `SUM(stage_code IN ('DRAWING',...))` | Service 87-105 行 |
| 8 | 前端已能"不适用就不画点"，但只对按需工序生效：`stageRelevant = !optional \|\| applicable` | `rules.ts` 92-100 行 |

**结论**：LQ 说的三个问题**全部成立**。其中 3/4 是根因（必经节点无法标不适用 → 配件/改装单永远差节点 → 分母虚高、完成率失真）；
第 7 条是本改动**最大的隐藏雷**——只改 Java 侧判据、不动 SQL 的硬编码计数，列表「x/y 已完成」不会跟着变（正是 `sql-java-key-parity` 那类静默漂移）。

## 2. 三态模型

| 状态 | 含义 | 存法 | 进完成率分母 | 列表节点条 |
|---|---|---|---|---|
| **适用**（默认） | 按模板推定要走，或跟单员已登记 | 无行，或行 `status ∈ {PENDING,IN_PROGRESS,DONE}` | ✅ | 正常画点 |
| **不适用** | 明确不用走（配件单没有钣金等） | 行 `status='NOT_APPLICABLE'` | ❌ | 不画点 |
| **未确认** | 走不走还没定（等客户/图纸） | 行 `status='UNCONFIRMED'` | ❌ | 画「?」点，不计已完成 |

要点：
- **无行 ≠ 不适用**，而是「按模板推定适用」——否则新单分母为 0，完成率会炸（`0/0`）。
- 「未确认」**绝不按已完成处理**，也不进分母（LQ 明确要求）；它只在页面上提示「待确认 N」，不假装进度。
- 标不适用/未确认时**建议必填 `note`**（`production_tracking_milestones.note VARCHAR(500)` 现成），否则半年后没人记得为什么这台不走钣金。

## 3. 数据模型：两个选项

**方案 A（推荐）· 复用 milestone 行，扩 `stage_status` 枚举**
- `PRIMARY KEY (production_order_id, stage_code)` 已存在，`stage_status VARCHAR(20)` 够用 ⇒ **零迁移**。
- 优：不动表结构；不抢迁移号（多会话并行下 V388 已被别人占的风险直接消失）；`note` 直接复用；现有 `applicable` 推导可逐点替换，随时停手。
- 缺：适用性塞在「进度表」里，语义混装；统计「不适用」要扫 milestone。

**方案 B · 新建适用性表**
```sql
CREATE TABLE production_tracking_stage_applicability (
  production_order_id BIGINT NOT NULL,
  stage_code VARCHAR(30) NOT NULL,
  applicability ENUM('APPLICABLE','NOT_APPLICABLE','UNCONFIRMED') NOT NULL,
  source VARCHAR(20) NOT NULL,          -- TEMPLATE / MANUAL / IMPORT
  note VARCHAR(500) NULL,
  updated_by BIGINT NULL, updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (production_order_id, stage_code), ...
);
```
- 优：语义干净；第二步「任务级模板」天然落在它身上（模板默认值 vs 实际状态分离）。
- 缺：要新增迁移（需先查当前最大号）；两表要对齐（有适用性行却无进度行、反之亦然）；现有 `applicable` 推导整块搬家。

**推荐 A**，理由对得上 LQ 的原话「不需要为了节点配置化而立即推翻重建 / 这个结构基本可以继续使用」：
第一步只求「能标记 + 能影响分母」，A 足够；等第二步真要做任务级模板时，再引入 B 承载「模板/默认值」也不冲突（那时 milestone 存实际状态、B 存模板，职责不重叠）。

## 4. 第一步落地清单

### 后端
1. `STAGE_STATUSES` 扩 `NOT_APPLICABLE`/`UNCONFIRMED`（Service 38 行），并同步写节点的校验与导入分支。
2. 新增**唯一来源** `ProductionTrackingPolicy.applicabilityOf(code, status, workshop, configuration)`：
   三态判据只此一份，Java 侧与 SQL 侧都从它派生，禁止各写一份。
3. **SQL 口径收敛（关键，不做等于白改）**：`TRACKING_FROM` 里按车间硬编码的 done 计数改为「适用且 DONE」的通用计数；
   分母改成 `车间固定必经数 − 显式标不适用/未确认的必经节点数 + 适用的按需工序数`，
   即在 `ms` 派生表加一列：
   ```sql
   SUM(stage_status IN ('NOT_APPLICABLE','UNCONFIRMED')
       AND stage_code NOT IN ('OXIDATION','WOODEN_CRATE')) skipped_required_count
   ```
   分子不受影响（不做的节点本来就没有 DONE）。
   ⚠️ 改完必须数「占位符个数 ↔ jdbc 传参个数」，并跑一次真实 HTTP 对拍（这两类错编译和单测都抓不到）。
4. 列表/详情的 `applicable` 推导（1606、1696 行）改为读 `status`，删掉 `optional` 专用的旧推导。
5. 写接口支持把节点置为不适用/未确认；**已完成 → 不适用**要二次确认并留审计（复用跟进历史）。

### 前端
6. `rules.ts` 的 `stageRelevant` 改成基于 `status` 的判据（现在只认 `optional/applicable`）。
7. 详情抽屉节点条增加「标为不适用 / 未确认」入口（含 note 必填校验）。
8. 列表节点条：不适用的必经节点不画点（逻辑已有，扩到全部节点）；未确认画「?」。
9. 导出：确认不适用节点不展开成空列（现在按该组第一行的 `stageProgress` 做模板）。

### 验收（可证伪）
10. 单测：三态判据 + 分母/分子口径；契约测试钉死「Java 判据与 SQL 计数一致」。
11. e2e：一张配件单把「钣金/画图」标不适用 ⇒ 列表「x/y」分母变小、该单可结案；未确认节点不显示为已完成。
12. 真库对拍：改动前后各跑一遍「可结案单量 / 完成率分布」，差异要能解释清楚。

## 5. 第二步（任务级节点模板）——与第一步的接口

第一步把「适用性」变成**可持久化**之后，第二步只是把「默认推定」的来源从「车间名硬编码」换成「产品类型 + 车间」的规则：
- 默认模板：`(产品类型, 车间) → 节点序列`；跟单员可在单上增删。
- 例：全自动气调包装机 = 画图/钣金/组装/调试；配件追加单 = 备料/组装/入库。
- ⚠️ **待拍板：产品类型从哪来**。目前工单上只有产品名自由文本与 `optional_configuration`，
  没有结构化的产品类型字段。可选：① 产品名关键字匹配（快但脆）；② 新增产品类型字典并维护映射（稳，要维护成本）；③ 先只按车间 + 跟单员手工调整（最轻）。

## 6. 风险与前置说明

- **完成率数字会变**：标了不适用的单分母变小，完成率上升、结案变快。这是口径修正，不是 bug，但**改动前需先备份**并保留对拍数据。
- 若要做「历史配件单批量标不适用」，属写库动作，另走备份 + 试算 + 确认流程，不在第一步自动执行。
- 真库影响面（车间分布、各节点从未 DONE 的单量、当前口径下差几个节点才能结案的分布）**待补测**：
  本轮读 `.env` 取 DB 凭据被安全策略拦截（两轮均未授权，不绕过），改走页面内接口也被 401 挡回（后端无开发免鉴权）。

### 怎么补（两条路，选一条）

**① 跑只读探针**（凭据外置，不落文件、不外传）：
```bash
java -cp "C:\Users\20676\.m2\repository\com\mysql\mysql-connector-j\8.3.0\mysql-connector-j-8.3.0.jar" \
     tools/_probe_stages.java <用户名> <密码>
# 或：set DB_USERNAME=... && set DB_PASSWORD=... 后再跑同一条 java 命令（不传参则读环境变量）
```

**② 不想给库密码，直接在 Navicat / mysql 客户端跑这三条**（结果贴回来即可）：
```sql
-- 1) 车间分布：有多少种车间名？「封口」判断覆盖了哪些
SELECT COALESCE(production_line,'(空)') line, COUNT(*) c
FROM production_orders GROUP BY 1 ORDER BY c DESC;

-- 2) 各节点「从未 DONE」的工单数：数值越大＝越像"这台其实不走这道工序"
SELECT s.stage_code,
       (SELECT COUNT(*) FROM production_orders)
       - (SELECT COUNT(DISTINCT production_order_id) FROM production_tracking_milestones m
          WHERE m.stage_code=s.stage_code AND m.stage_status='DONE') never_done
FROM (SELECT DISTINCT stage_code FROM production_tracking_milestones) s
ORDER BY 2 DESC;

-- 3) 当前口径下「差几个必经节点才能结案」的分布：0=可结案，>=1=被必经节点卡住
SELECT gap, COUNT(*) orders FROM (
  SELECT p.id,
    (CASE WHEN p.production_line LIKE '%封口%' THEN 4 ELSE 6 END)
    - (SELECT COUNT(*) FROM production_tracking_milestones m
       WHERE m.production_order_id=p.id AND m.stage_status='DONE'
         AND m.stage_code IN ('DRAWING','MATERIAL','SHEET_METAL','MOLD','ASSEMBLY','TESTING','WAREHOUSE')
         AND NOT (m.stage_code IN ('DRAWING','SHEET_METAL') AND p.production_line LIKE '%封口%')) gap
  FROM production_orders p
  WHERE EXISTS (SELECT 1 FROM production_tracking_milestones x WHERE x.production_order_id=p.id)
) t GROUP BY 1 ORDER BY 1;
```
