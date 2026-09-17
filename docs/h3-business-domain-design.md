# 氚云业务域重构设计

> 状态：设计基线（2026-08-21）  
> 数据依据：生产任务单管理、销售出库、收款记录、多订单结清四份氚云全字段导出。  
> 核心口径：合同是可选档案，生产任务是执行事实，销售出库是销售财务事实，收款与分配是资金事实。

## 1. 为什么废弃“销售合同就是订单”的模型

真实数据呈现三条独立但可关联的事实链：

1. 生产任务单存在大量“非合同生产”，可以没有合同和销售出库关联。
2. 包材、库存品、配件和运费可以直接销售出库，不经过生产任务。
3. 收款既可能对应一张出库单，也可能通过“多订单结清”分配到多张出库单。

因此不能继续使用 `sales_contracts` 同时表示合同和销售出库，也不能把回款简单设计成订单子表。

## 2. 目标领域模型

```text
customers ─────────────────────────────┐
company_team_members ──────────────────┤
                                       ↓
                              business_orders
                              统一业务生命周期
                                       │
                     ┌─────────────────┼─────────────────┐
                     ↓                 ↓                 ↓
             sales_contracts_v2  production_orders  sales_outbounds
               可选合同档案        执行事实           销售财务事实
                                       │                 │
                                       ↓                 ↓
                         production_order_components  sales_outbound_items
                                                         │
                                      receipts ──────────┤
                                         │               │
                                         ↓               ↓
                               receipt_allocations ───────┘
                                         │
                                         ↓
                                  利润与提成（后续）
```

统一业务档案不是氚云某张表的复制，而是本系统把多个外部事实串成同一业务生命周期的聚合对象。

## 3. 表结构设计

### 3.1 `business_orders`：统一业务档案

| 字段 | 含义 |
|---|---|
| id | 内部主键 |
| business_no | 本系统业务编号，例如 `BO2026000001` |
| customer_id | 客户档案，可空 |
| salesperson_id | 公司成员，可空 |
| customer_name_snapshot | 客户名称快照 |
| salesperson_name_snapshot | 业务员名称快照 |
| business_type | `EQUIPMENT_CUSTOM` / `INVENTORY_DIRECT` / `MIXED` / `UNKNOWN` |
| lifecycle_status | `PENDING` / `IN_PRODUCTION` / `PRODUCTION_DONE` / `IN_STOCK` / `OUTBOUNDED` / `PARTIALLY_PAID` / `SETTLED` / `CANCELLED` |
| contract_status | `NONE` / `SIGNED` / `UNKNOWN` |
| first_activity_date | 首次业务活动日期 |
| last_activity_date | 最近业务活动日期 |
| match_status | `UNMATCHED` / `AUTO_MATCHED` / `CONFIRMED` / `CONFLICT` |
| created_at / updated_at | 系统时间 |

业务状态由关联事实推导，不允许导入器随意覆盖人工确认结果。

### 3.2 `business_order_links`：事实关联

| 字段 | 含义 |
|---|---|
| business_order_id | 统一业务档案 |
| object_type | `CONTRACT` / `PRODUCTION_ORDER` / `SALES_OUTBOUND` |
| object_id | 对应内部事实表主键 |
| link_method | `EXPLICIT` / `AUTO_RULE` / `MANUAL` |
| confidence | 自动匹配置信度 0～100 |
| match_reason | 匹配依据 |
| confirmed_by / confirmed_at | 人工确认信息 |

使用链接表支持一个业务关联多张合同、多张生产任务和多张出库单，避免在 `business_orders` 中塞固定的三个外键。

### 3.3 `production_orders`：生产执行事实

保存有业务意义且稳定的生产主字段：

- 氚云 `ObjectId`、生产工单流水号、数据状态
- 工单申请日期、计划类型
- 业务员、客户信息、客户选择
- 要求完成、预计完成、实际完成日期
- 产品名称、需求数量
- 盒子尺寸、形状、材质、模具排列
- 电压、插头、品牌、可选配置、生产要求/备注
- 生产完成数、生产进度、是否生产完成
- 入库数量、入库进度、是否入库完成
- 制程、产线、接单员、入库员
- 销售订单编号、关联销售订单、产品订单子 OBJID（均允许为空）
- 来源创建人、部门、创建/修改时间
- `source_hash`、`last_synced_at`

不稳定、停用、打印辅助和重复计算字段进入原始导入快照，不全部进入正式列。

### 3.4 `production_order_components`：零部件制程子表

来源为 `零部件制程.*`：

- 外部明细 ID、序号、标题
- 零部件名、关联零部件、零部件流水号
- 需求数量、需求系数
- 库存数量、库存调用数量
- 生产完成数量、生产完成比例、完成时间
- 是否最终工序、生产是否完成
- 工艺图、报工二维码等引用信息

### 3.5 `sales_outbounds`：实际销售出库

由现有 `sales_contracts` 中 `order_type=NORMAL` 的语义迁出：

- 氚云 `ObjectId`、销售出库编号、状态
- 发货日期、业务日期
- 客户、经销商、业务员、业务归属
- 地区、联系方式、地址
- 运输方式、包邮、运杂费
- 仓库、仓管员
- 付款状态、订单备注、收款备注
- 采购金额、销售金额、氚云直接利润
- 已收款、未收款、已开票、未开票、开票员
- 来源标题、部门、创建人、创建/修改时间、附件引用
- `source_hash`、`last_synced_at`

氚云利润仅命名为 `source_gross_profit`，后续正式利润使用独立核算表，不能混为同一口径。

### 3.6 `sales_outbound_items`：实际出库明细

- 外部明细 ID、序号、标题
- 行类型：`PRODUCT` / `FREIGHT` / `SERVICE` / `DISCOUNT` / `OTHER`
- 类目、产品编号、产品名称、规格、单位
- 出库数量、销售单价、销售总价
- 采购单价快照、采购总价快照
- 氚云单产品利润快照、库存数量快照
- 产品备注、图片/附件引用

第一阶段保留产品快照，不强制建立销售产品主数据。待取得独立“产品信息”导出并验证稳定产品 ID 后，再建立 `sales_products` 并回填 `sales_product_id`。不能把出库明细 `ObjectId` 误当产品主键。

### 3.7 `receipts`：收款主单

统一承接“收款记录”和“多订单结清”的主记录：

| 字段 | 含义 |
|---|---|
| receipt_no | 收款记录流水号或多订单结清流水号 |
| receipt_type | `SINGLE_OUTBOUND` / `MULTI_OUTBOUND` |
| external_id | 氚云主记录 ObjectId |
| status | 草稿/进行中/已生效等标准化状态 |
| receipt_date | 收款日期 |
| customer_id / dealer_name | 收款对象 |
| salesperson_id | 归属业务员 |
| payment_method | 收款方式 |
| receipt_amount | 本次实际收款金额；允许负数表示退款/冲销 |
| receivable_amount | 相关应收金额 |
| allocated_amount | 已分配金额 |
| unallocated_amount | 未分配金额 |
| remark / attachment_info | 备注和附件 |
| source_hash / last_synced_at | 同步控制 |

草稿状态不得进入“已回款”经营指标。负数收款必须保留符号，不能用非负校验截断。

### 3.8 `receipt_allocations`：回款分配

这是新模型的关键表，用于表达一笔钱如何核销一张或多张销售出库：

- receipt_id
- sales_outbound_id（匹配不到时可空）
- outbound_no_snapshot
- external_line_id、序号、标题
- sales_date_snapshot
- sales_amount_snapshot
- received_before_snapshot
- outstanding_before_snapshot
- allocated_amount
- allocation_status：`PENDING` / `CONFIRMED` / `REVERSED`

“收款记录”中的单订单引用也转换成一条 allocation；“多订单结清”的每个 `出库单明细.*` 转换成一条 allocation。这样利润和提成只读取统一分配结果。

### 3.9 合同档案

现有 `sales_contracts` 中 `order_type=CONTRACT` 的数据最终迁移到 `sales_contracts_v2`（正式命名实施时再定）。合同只保存约定事实，不参与实际销售额口径。

## 4. 四类 Excel 的唯一键和子表

| 数据源 | 主业务键 | 外部稳定键 | 子表 |
|---|---|---|---|
| 生产任务单 | 生产工单流水号 `SCGD...` | 主表 `ObjectId` | `零部件制程.*` |
| 销售出库 | 销售出库编号 `CK...` | 主表 `数据ID(不可修改)` | `出库产品明细.*`、嵌入式 `收款详细.*`（仅作校验） |
| 收款记录 | 流水号 `SK...` | 主表 `ObjectId` | `收款记录.*` |
| 多订单结清 | 流水号 | 主表 `ObjectId` | `出库单明细.*` |

导入幂等优先使用外部 ObjectId，业务编号作为可读唯一键和回退键。

## 5. 关联策略

### 5.1 明确关联优先

1. 生产任务的销售订单编号、关联销售订单、产品订单子 OBJID。
2. 收款记录的销售出库编号。
3. 多订单结清子表的销售出库编号或出库 ObjectId。

### 5.2 自动候选匹配

明确关联为空时，只生成候选，不直接强绑定：

- 同一客户或标准化客户名称
- 同一业务员
- 产品名称/规格相似
- 生产完成日期早于且接近发货日期
- 数量一致或合理拆分

达到阈值标记 `AUTO_MATCHED`，低置信度进入人工确认队列。

### 5.3 禁止的关联方式

- 不能仅凭客户名称把一个客户的所有记录并成一个业务。
- 不能把合同金额当实际销售金额。
- 不能把出库明细 ID 当产品主数据 ID。
- 不能用出库表“已收款金额”替代收款与分配流水。

## 6. 指标口径

| 指标 | 唯一事实来源 |
|---|---|
| 签约金额 | 合同档案 |
| 在制业务、待交付数量 | 生产任务单 |
| 实际销售额 | 已生效销售出库 |
| 氚云直接毛利 | 销售出库及明细快照 |
| 实际回款 | 已生效 receipts + confirmed allocations |
| 未回款 | 出库应收减已确认分配，出库汇总字段仅校验 |
| 最终利润 | 后续独立成本与利润核算 |
| 可结算提成 | 最终利润 + 已确认回款分配 + 提成规则 |

## 7. 现有模块迁移策略

1. 保留 V117～V126 及当前页面运行，停止继续向 `sales_contracts` 添加新业务语义。
2. 新建独立表与独立导入服务，不直接重命名或破坏现表。
3. 首轮全量导入四类数据到新表，与旧订单页面并行核对。
4. 将旧 `NORMAL` 记录按订单编号迁移/关联到 `sales_outbounds`。
5. 将旧 `CONTRACT` 记录迁移到合同档案。
6. 新建“我的业务”页面并验证后，再下线旧“销售订单”聚合页面。

## 8. 实施顺序

### P1：事实层

1. `production_orders`、`production_order_components` 和生产任务导入。
2. `sales_outbounds`、`sales_outbound_items` 和销售出库导入。
3. `receipts`、`receipt_allocations`，同时支持收款记录和多订单结清。
4. 每类导入继续复用批次、原始 JSON、内容指纹、新增/更新/无变化机制。

### P2：统一业务层

1. `business_orders`、`business_order_links`。
2. 明确外键自动关联。
3. 候选匹配和人工确认队列。
4. 生命周期状态推导服务。

### P3：产品、成本、利润和提成

1. 取得独立产品信息、采购入库和开票导出。
2. 建立稳定销售产品主数据与成本快照。
3. 财务逐单验证利润。
4. 建立提成规则、计算与结算。

### P4：替换用户界面

1. 生产执行看板。
2. 销售出库与回款核销页面。
3. “我的业务”生命周期工作台。
4. 老板经营看板。

## 9. 本阶段验收标准

- 合同为空的生产任务能够形成合法业务。
- 没有生产任务的库存品出库能够形成合法业务。
- 一张出库可接受多笔收款。
- 一笔多订单结清可分配到多张出库。
- 退款/冲销负数金额被完整保存。
- 草稿收款不计入实际回款。
- 重复导入只更新变化记录，不产生重复事实。
- 所有自动关联均可解释、可撤销、可人工确认。
