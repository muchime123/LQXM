# 销售出库利润与提成建设方案

> 状态：当前唯一有效的销售、利润与提成业务设计  
> 更新日期：2026-08-29  
> 适用范围：氚云销售出库、钉钉日常报销、订单利润、业务员提成、我的订单  
> 原则：先逻辑脱钩，再迁移数据，最后物理清理；不得修改既有 Flyway 历史脚本。

## 1. 建设目标

老板需要业务员直接看到自己每张销售出库订单的：

- 销售额、采购成本和产品毛利；
- 应收、已收、未收和付款状态；
- 从钉钉归集到该订单的运费、定制费、安装差旅等额外成本；
- 订单实际利润；
- 理论提成、当前可发提成和最终已发提成；
- 每个金额的来源、计算口径、确认状态和调整记录。

本项目不是收款系统、财务核销系统或生产销售匹配平台。氚云内部已经完成的业务流程不在本系统重建。

## 2. 不可突破的业务口径

### 2.1 核算单位

一张有效销售出库就是一张订单核算单元：

```text
order_id = sales_outbounds.id
order_no = sales_outbounds.outbound_no
```

新代码不得先创建或查询 `BusinessOrder` 才能计算销售出库利润或提成。

### 2.2 回款口径

本系统只读取氚云销售出库最终结果：

```text
应收 = sales_outbounds.sales_amount
已收 = sales_outbounds.received_amount
未收 = sales_outbounds.outstanding_amount
付款状态 = sales_outbounds.payment_status
```

以下内容均属于氚云内部流程，本系统不导入、不展示、不维护、不计算：

- 收款记录；
- 销售出库收款详细子表；
- 多订单结清申请；
- 收款分配与核销；
- 银行到账池；
- 收款候选、差异对账和核销时间线。

`receipts`、`receipt_allocations`、`settlement_events`、`sales_outbound_payment_snapshots` 等现存表仅作为历史兼容数据，禁止新增运行时依赖。

### 2.3 产品收入与成本口径

订单汇总优先使用氚云销售出库主表：

```text
销售额 = sales_outbounds.sales_amount
采购成本 = sales_outbounds.purchase_amount
氚云产品毛利参考 = sales_outbounds.source_gross_profit
```

产品下钻使用 `sales_outbound_items` 中的销售单价、销售总价、采购单价和采购总价。

系统不得使用本地产品主档、成本快照或发货日成本重新替换氚云采购成本。遇到采购金额为空、为零或明显异常时，只标记“氚云成本数据异常”，不自动补算。

### 2.4 订单额外成本口径

钉钉提供费用事实，只有满足以下全部条件的费用才影响订单利润：

- 来源审批已完成并有效；
- 明细被明确识别为订单成本；
- 已关联到具体 `sales_outbounds.id`；
- 分配状态为 `CONFIRMED`；
- 未撤销、未删除、未重复归集。

部门费用、公司费用、对公付款核对和无法确认归属的费用不得进入订单利润。

### 2.5 利润口径

```text
产品毛利 = 销售额 - 采购成本
订单额外成本 = 已确认归属于该销售出库的费用合计
订单利润 = 产品毛利 - 订单额外成本 + 利润调整
可计提利润 = MAX(订单利润 - 不参与提成金额, 0)
```

利润调整必须有类型、金额、原因、操作人、时间和审计记录。负利润可以展示，但默认不得产生正向提成。

### 2.6 提成口径

提成规则未经老板和财务书面确认前，只能展示“试算”，不得显示为正式到手提成。

建议将计算拆成三个概念：

```text
理论提成 = 可计提利润 × 提成比例
当前可发提成 = 按已确认规则结合销售出库最终回款情况计算
已发提成 = 已确认提成结算单金额
```

回款条件可能采用全额回款后发放、按回款比例发放或分段门槛，但只能配置已获确认的规则，不能由开发人员推测。

### 2.7 生产任务边界

`production_orders` 只服务生产执行看板，不参与订单利润、提成、费用归集或销售出库匹配。

## 3. 目标主链

```text
氚云销售出库
├─ sales_outbounds：订单、销售额、采购成本、最终回款结果
└─ sales_outbound_items：产品明细
             │
             ├───────────────┐
             │               │
             ↓               ↓
        产品毛利        钉钉日常报销
                            │
                     expense_claims/items
                            │
                     明确关联销售出库
                            │
             ┌──────────────┘
             ↓
       sales_outbound_profit
             │
       commission_rules
             │
             ↓
     sales_outbound_commission
             │
             ↓
          我的订单
```

## 4. 模块处置清单

### 4.1 核心保留

| 模块 | 作用 |
|---|---|
| `sales_outbounds` | 唯一订单事实和最终回款结果 |
| `sales_outbound_items` | 产品销售与采购成本明细 |
| `expense_claims` / `expense_items` | 钉钉费用事实 |
| `expense_allocations` | 保留分摊能力，目标改为销售出库 |
| `employees` 与外部身份映射 | 业务员归属和行级权限 |
| `production_orders` | 独立生产执行查看 |
| 客户档案 | 客户信息和销售出库下钻 |
| 氚云销售出库导入 | 订单最终结果同步 |
| 钉钉日常报销同步 | 额外成本来源 |

### 4.2 重构

| 当前模块 | 目标模块 |
|---|---|
| `BusinessProfitService` | `SalesOutboundProfitService` |
| `BusinessCommissionService` | `SalesOutboundCommissionService` |
| `expense_allocations.target_type=BUSINESS_ORDER` | `target_type=SALES_OUTBOUND` |
| `MyWorkbench` 的业务单卡片 | 我的销售订单 |
| `BusinessProfitView` | 订单利润 |
| `CommissionCenterView` | 订单提成与结算 |
| `ExpenseCenterView` | 订单费用与非订单费用 |

### 4.3 退出主链

以下旧模块已经退出运行主链。其历史数据由迁移脚本归档，禁止新增运行时依赖：

- `business_orders`、`business_order_links`；
- `business_profit_snapshots`、`business_profit_adjustments`（V262 归档下线）；
- `business_commission_snapshots` 及旧结算明细（V262 归档下线）；
- `receipts`、`receipt_allocations`、`settlement_events`；
- `SettlementLedgerService` 中核销、到账池、对账功能；
- `sales_product_cost_snapshots`、`sales_profit_calculations` 与旧 `sales_commission_*`（V268 归档下线）；
- `sales_products`、`sales_product_source_aliases` 仅供现行库存台账合并使用，不再承担利润核算；
- 业务档案、销售产品、源毛利校验等普通业务入口。

## 5. 目标数据模型

### 5.1 费用归单

短期可继续使用 `expense_allocations`，新增或启用以下语义：

```text
target_type = 'SALES_OUTBOUND'
target_id = sales_outbounds.id
allocation_status = PENDING | CONFIRMED | REJECTED | REVERSED
allocated_amount
match_method = MANUAL | EXACT_OUTBOUND_NO | RECOMMENDED
confidence
confirmed_by
confirmed_at
```

约束：

- 同一费用明细的有效分配总额不得超过其可分配金额；
- 影响利润的只有 `CONFIRMED`；
- 手工确认前，推荐结果必须为 `PENDING`；
- 精确识别唯一完整出库编号时可以自动关联，但仍需保留匹配依据和审计；
- 同业务员、时间接近、客户相似等只能推荐，禁止自动确认。

### 5.2 订单利润

建议新增 `sales_outbound_profits`，每张出库一条当前结果：

```text
id
sales_outbound_id UNIQUE
sales_amount
purchase_amount
product_gross_profit
confirmed_expense_amount
adjustment_amount
accounting_profit
non_commissionable_amount
commissionable_profit
calculation_status
formula_version
calculated_at
updated_at
```

建议新增 `sales_outbound_profit_adjustments`：

```text
id
sales_outbound_id
adjustment_type
amount
direction
affects_commission
reason
status
created_by
created_at
reversed_by
reversed_at
```

第一版不需要复杂利润版本快照。当前结果可重算，人工调整和审计日志提供可追溯性。若财务确认需要“月结锁定”，再增加结账快照，不应预先建设。

### 5.3 订单提成

建议新增 `sales_outbound_commissions`：

```text
id
sales_outbound_id UNIQUE
employee_id
profit_id
rule_id
commissionable_profit
commission_rate
theoretical_commission
receipt_ratio_pct
available_commission
settled_commission
status = TRIAL | WAITING_RULE | NOT_AVAILABLE | AVAILABLE | SETTLED
formula_version
calculated_at
updated_at
```

提成结算单可以继续保留主从结构，但明细必须直接引用：

```text
sales_outbound_commission_id
sales_outbound_id
outbound_no_snapshot
employee_id
profit_snapshot_amount
commission_amount
```

禁止新结算明细依赖 `business_order_id`。

## 6. 数据迁移设计

### 6.1 迁移前审计

先生成只读报告，不写数据：

1. 每个 `business_order` 的 ACTIVE 销售出库数量分布；
2. 每张有效销售出库关联的 ACTIVE `business_order` 数量；
3. `BUSINESS_ORDER` 费用分配中可唯一映射到销售出库的数量和金额；
4. 0 张、1 张、多张销售出库的异常业务单清单；
5. 旧利润与按新公式重算利润的差异；
6. 旧提成与新试算提成的差异；
7. 已结算提成所涉及订单和不可逆历史凭证。

迁移准入标准：

- 绝大多数旧业务单必须能唯一映射一张销售出库；
- 多出库业务单必须人工确认费用如何拆分；
- 已结算提成不得被自动重写；
- 所有迁移结果必须可通过旧 ID 追溯。

### 6.2 费用迁移

对于唯一 ACTIVE 销售出库的旧业务单：

```text
旧：target_type=BUSINESS_ORDER, target_id=business_order.id
新：target_type=SALES_OUTBOUND, target_id=sales_outbound.id
```

推荐新增迁移来源字段或映射表，记录旧 allocation ID、旧 business_order ID 和迁移批次。不要直接覆盖后失去追溯关系。

多出库业务单进入人工处理队列，不允许平均分摊或按销售额自动确认。

### 6.3 利润迁移

新利润全部按新公式重算，不搬运旧计算结果作为当前值。旧利润只作为差异对照：

```text
新利润 = 出库销售额 - 出库采购成本 - 新目标下已确认订单费用 + 新调整
```

旧人工调整如果能唯一映射销售出库，可迁移到新调整表；无法唯一映射的必须人工处理。

### 6.4 提成迁移

- 未结算旧提成：按新订单利润重新试算；
- 已结算旧提成：冻结为历史凭证，不自动修改；
- 新旧差异形成迁移报告，由财务确认；
- 正式切换前，新结果只显示“试算”。

## 7. 费用归单业务设计

### 7.1 页面只保留两个主分类

```text
订单费用
非订单费用
```

订单费用展示：报销单、费用明细、金额、提交人、日期、推荐订单、匹配依据、确认状态。

### 7.2 关联方式

优先级从高到低：

1. 人工选择销售出库；
2. 费用文本包含唯一完整出库编号；
3. 外部流程明确携带销售出库 ID/编号；
4. 客户、业务员、日期等模糊条件仅生成候选。

### 7.3 安全规则

- 模糊推荐绝不自动影响利润；
- 确认、撤销、重新分配必须审计；
- 已进入提成结算的订单费用发生变化时，必须产生异常提醒，不得静默改历史工资；
- 费用同步更新不得删除人工确认关系；
- 重复钉钉审批实例和重复明细必须幂等。

## 8. 订单利润服务设计

`SalesOutboundProfitService` 应提供：

- 分页查询订单利润；
- 查询单张销售出库利润详情；
- 单笔重算；
- 安全批量重算；
- 新增、撤销利润调整；
- 成本异常检测；
- 新旧口径差异报告。

重算触发点：

- 销售出库新增或更新；
- 订单费用确认、撤销或金额变化；
- 利润调整新增或撤销；
- 管理员手工触发修复。

重算必须幂等。同样的输入应得到同样的结果。

## 9. 提成服务设计

`SalesOutboundCommissionService` 应直接读取：

- `sales_outbounds.employee_id`；
- 当前订单利润和可计提利润；
- `sales_outbounds.received_amount`、`sales_amount`；
- 当前生效提成规则。

必须覆盖以下状态：

- 未映射业务员；
- 未配置规则；
- 仅试算；
- 未满足回款条件；
- 可结算；
- 已结算；
- 负利润或零可计提利润；
- 结算后成本发生变化。

已结算记录不得被普通重算覆盖。后续差异只能通过补发、扣回或调整单处理。

## 10. 我的订单与详情页

### 10.1 我的订单

数据源直接为当前员工可见的 `sales_outbounds`，不再查询 `business_orders`。

列表至少包含：

- 出库号、客户、出库日期；
- 销售额、采购成本、产品毛利；
- 订单费用、订单利润；
- 应收、已收、未收、回款率；
- 理论提成、当前可发提成、已发提成；
- 数据异常和待处理状态。

### 10.2 销售订单详情

详情按以下区域组织：

1. 销售信息；
2. 产品及销售/采购金额；
3. 氚云最终回款汇总；
4. 已确认订单费用和待确认推荐费用；
5. 产品毛利、额外成本、调整和订单利润；
6. 提成规则、理论提成、可发提成和结算状态；
7. 数据来源和审计记录。

详情页不展示收款流水、核销记录或多订单结清。

## 11. 导航收敛

建议普通导航调整为：

```text
工作台
└─ 我的订单

销售管理
├─ 销售出库
├─ 客户档案
└─ 生产执行

利润与提成
├─ 订单费用
├─ 订单利润
└─ 提成管理

系统管理
├─ 氚云导入
├─ 日常报销（含钉钉同步/台账）
├─ 员工
└─ 权限
```

退出普通导航：

- 业务档案；
- 销售产品；
- 源毛利校验；
- 任何收款核销、匹配、数据质量中心入口。

旧地址先重定向到新页面，稳定运行后再删除旧页面文件。

## 12. API 设计

建议新增独立 API，避免继续沿用带 `business` 语义的接口：

```text
GET  /api/sales-outbounds/{id}/accounting
GET  /api/sales-outbound-profits
GET  /api/sales-outbound-profits/{outboundId}
POST /api/sales-outbound-profits/{outboundId}/recalculate
POST /api/sales-outbound-profits/recalculate/preview
POST /api/sales-outbound-profit-adjustments
POST /api/sales-outbound-profit-adjustments/{id}/reverse

GET  /api/sales-outbound-commissions
GET  /api/sales-outbound-commissions/{outboundId}
POST /api/sales-outbound-commissions/{outboundId}/recalculate

GET  /api/expense-allocations/targets/sales-outbounds
POST /api/expense-allocations
POST /api/expense-allocations/{id}/confirm
POST /api/expense-allocations/{id}/reverse

GET  /api/my-orders
GET  /api/my-orders/summary
```

所有写接口必须声明明确的 `@RequirePermission`。

## 13. 权限与数据范围

建议权限：

```text
sales_order.read.self
sales_order.read.department
sales_order.read.all
sales_order.manage

order_expense.read.self
order_expense.read.department
order_expense.manage

order_profit.read.self
order_profit.read.department
order_profit.read.all
order_profit.manage

order_commission.read.self
order_commission.read.department
order_commission.read.all
order_commission.manage
order_commission.settle
```

行级范围统一通过 `DataScopeSupport.appendByEmployeeId`，员工列使用 `sales_outbounds.employee_id`。不得重新按姓名实现私有数据范围。

业务员可看本人订单利润和提成；部门负责人按部门范围；财务/老板按 ALL。利润调整、费用确认和提成结算必须使用独立管理权限。

## 14. 分阶段实施计划

### 阶段 0：冻结与基线

- 创建代码标签和数据库备份；
- 固化本文件为当前唯一口径；
- 记录前后端构建、测试和关键页面基线；
- 输出旧链依赖清单和数据审计报告；
- 冻结产品、包材、官网、资产等非本次范围模块。

完成标准：可以准确说明每张出库当前经过哪些旧表计算利润和提成。

### 阶段 1：销售出库只读事实收口

- 氚云导入只保留生产任务和销售出库；
- 销售出库最终已收、未收直接写入主表；
- 停止解析收款详细子表；
- 删除所有收款/结清普通入口；
- 工作台和老板看板统一读取销售出库最终结果。

完成标准：任何正常业务查询均不需要 `receipts`、`receipt_allocations` 或收款子表。

### 阶段 2：费用直接关联销售出库

- 增加 `SALES_OUTBOUND` 目标；
- 建立销售出库候选搜索；
- 精简订单费用 UI；
- 模糊自动归集降级为推荐；
- 迁移可唯一映射的旧业务单费用；
- 多出库异常进入人工队列。

完成标准：新确认订单费用不再写 `BUSINESS_ORDER`。

### 阶段 3：订单利润直算

- 新建订单利润和调整模型；
- 实现新利润服务和 API；
- 建立差异报告；
- 新订单利润页面上线；
- 旧 BusinessProfit 只读冻结。

完成标准：订单利润可完全由销售出库与确认费用重建。

### 阶段 4：订单提成直算

- 新建订单提成模型；
- 等待老板确认正式规则；
- 未确认前只展示试算；
- 迁移未结算提成；
- 冻结已结算旧凭证；
- 建立结算后成本变化预警。

完成标准：新提成计算不查询 `business_order_id`。

### 阶段 5：我的订单与导航切换

- 工作台改为我的订单；
- 建设销售订单详情；
- `/business` 重定向销售出库或历史只读页；
- 下线销售产品、源毛利校验主入口；
- 更新老板看板利润和提成指标。

完成标准：业务员从一张销售出库即可看到完整利润和提成。

### 阶段 6：停止旧链运行

- 停止创建 `business_orders` 和 links；
- 停止旧利润、提成快照写入；
- 移除运行时 Receipt/Settlement 查询；
- 删除死 API、死页面和错误文案；
- 将旧文档移入 `docs/legacy/`。

完成标准：生产日志和数据库审计中不再出现旧链新增写入。

### 阶段 7：物理清理

稳定运行并完成数据备份后，才通过新的 Flyway 迁移逐批删除废弃表和字段。不得修改 V1～当前已执行迁移。

## 15. 测试与验收

### 15.1 公式测试

至少覆盖：

- 无费用订单；
- 一笔费用对应一张订单；
- 一笔费用拆分多张订单；
- 待确认费用不影响利润；
- 撤销费用恢复利润；
- 采购成本为空/零；
- 负利润；
- 未回款、部分回款、已收清；
- 无提成规则；
- 已结算后费用发生变化。

### 15.2 权限测试

- 业务员只能查看本人；
- 部门负责人只能查看本部门；
- 财务可以确认费用和管理利润；
- 只有提成结算权限可以生成结算单；
- 越权访问返回 403；
- 所有写操作有审计日志。

### 15.3 数据验收样本

由财务选择至少 20 张代表性销售出库，人工核对：

- 销售额；
- 采购成本；
- 已收和未收；
- 钉钉费用归属；
- 订单利润；
- 提成试算；
- 与现有工资或财务底稿的差异原因。

### 15.4 上线门槛

- 前端类型检查、构建通过；
- 后端全部测试通过；
- 迁移脚本在生产副本演练通过；
- 新旧利润差异报告已由财务确认；
- 已结算历史数据不会被重算覆盖；
- 可以一键关闭新利润/提成入口并回退到只读状态。

## 16. 风险与回滚

| 风险 | 控制措施 |
|---|---|
| 旧 BusinessOrder 非 1:1 | 迁移前审计，多出库人工处理 |
| 费用误归属影响工资 | 推荐不自动确认，结算后变化预警 |
| 提成规则尚未确定 | 只显示试算，不允许正式结算 |
| 历史已结算提成被覆盖 | 冻结旧凭证，差异通过调整单处理 |
| 氚云采购成本异常 | 标记异常，不本地替换成本 |
| 双链并行导致口径混淆 | 页面明确“旧口径/新试算”，分阶段切换 |
| 一次性删表破坏依赖 | 先逻辑脱钩，稳定后新增迁移清理 |

回滚原则：每个阶段都必须可以独立关闭新入口；迁移使用新增列、表或映射记录，不通过不可逆覆盖切断历史追溯。

## 17. 工程与文档治理

- 本文件为当前口径最高优先级文档；
- 所有与“本地收款核销”“BusinessOrder 财务核心”“本地成本替换氚云成本”冲突的文档移入 `docs/legacy/`；
- 临时脚本整理到 `tools/database`、`tools/import-debug`、`tools/legacy`；
- 测试数据整理到 `data/test-fixtures`；
- 根目录不保留临时输出和一次性分析文件；
- 在 AGENTS.md 中加入本方案第 2 节的硬性口径摘要，防止后续 AI 恢复旧架构。

## 18. 明确禁止事项

1. 禁止在本系统重新建设收款记录、核销或多订单结清流程。
2. 禁止新代码依赖 `business_order_id` 才能找到销售出库。
3. 禁止用本地产品成本快照替换氚云采购成本。
4. 禁止模糊匹配费用后自动确认并影响利润。
5. 禁止在提成规则未确认时显示“正式到手提成”。
6. 禁止普通重算修改已结算提成历史。
7. 禁止直接修改已执行 Flyway 历史脚本。
8. 禁止在完成依赖审计前直接删除旧业务表。
9. 禁止把生产任务重新接入利润或提成主链。
10. 禁止借本次改造扩大到产品、包材、官网、资产等无关模块。

## 19. 最终验收定义

项目完成后，业务员应能从“我的订单”打开任意一张属于自己的销售出库，并在同一详情中看到：

```text
氚云最终销售额
- 氚云最终采购成本
= 产品毛利
- 已确认钉钉订单费用
± 有审计记录的利润调整
= 订单实际利润
× 已确认提成规则
结合氚云最终回款结果
= 理论/可发/已发提成
```

整个过程不依赖 BusinessOrder、收款核销、本地产品成本重算或生产任务匹配。这是本次建设的最终完成标准。
