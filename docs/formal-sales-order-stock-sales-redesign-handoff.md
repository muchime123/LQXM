# 正式销售订单「现货/待生产」改造 — 交接文档

> 生成日期：2026-09-03
> 状态：**已定方案，尚未落地任何代码**（仅完成现状摸底与分析）
> 用途：用户换账号后按本文档继续实施

---

## 0. 需求背景（一句话）

销售订单的产品部分现在只能从"产品管理/机型"里选，但实际上该卖的是**"有现货库存的成品"**（SKU）或**"没现货、要按单生产的成品"**，且订单里遇到**新客户**目前没法直接建档。需要对正式销售订单做产品模型改造。

## 1. 已与用户确认的四项决策（不要再问，直接按此落地）

| # | 问题 | 决策 |
|---|------|------|
| 1 | 卖现货时"锁定/预留"怎么做 | **下单锁可售数，不锁具体批次**：订单确认时校验并扣减 SKU 可售库存计数（预留量派生）；出库时再从该 SKU 可用批次按先进先出扣减 |
| 2 | 订单行产品的"身份"以什么为准 | **以销售 SKU（sales_products）为准**；机型/档位降级为可选补充信息自动带出；行主键与库存台账同键 |
| 3 | "待生产"行的定制信息怎么办 | **保留全部现有定制字段**（机型、档位、定制方式、工装要求、配置改动等），生产下单流程不受影响 |
| 4 | 新客户在订单里怎么处理 | **内联快速建档**：客户下拉旁加"+ 新建客户"，弹小窗填名称/联系人/电话/地址即建档并自动选中，其余字段后补 |

## 2. 现状关键事实（接续者最小上下文）

### 2.1 相关表与迁移版本（最大迁移版本 = **V249**，新脚本用 **V250**）

| 表 | 说明 | 关键字段 |
|----|------|---------|
| `formal_sales_orders` | 正式销售订单头（V221/V240/V248） | `status`(DRAFT/CONFIRMED/IN_PRODUCTION/PARTIALLY_SHIPPED/COMPLETED/CANCELLED/CLOSED)、`customer_name_snapshot`、`salesperson_id/name_snapshot`、`sales_order_ref` |
| `formal_sales_order_items` | 订单行（V221/V236/V240/V246/V248） | **已含 `sales_product_id`（V236 加的列，但订单创建 SQL 目前根本没写它！）**；`product_model_id`、`product_machine_tier_id`、`product_model_name_snapshot`、`machine_tier_snapshot`、`product_name/specification/unit`、定制字段、`quantity`、`unit_price`、`shipped_quantity`、`sales_order_id` |
| `formal_sales_shipments` | 出库单（V220/V230） | 一单一个订单/仓库/客户/业务员 |
| `formal_sales_shipment_items` | 出库明细（V220/V246/V247） | `finished_goods_lot_id`、`formal_sales_order_item_id`、数量/单价快照/成本快照、收款关联 |
| `finished_goods_lots` | 成品批次（V218） | `lot_no`、`finished_goods_name`、`sales_product_id`、`warehouse_id`、`available_quantity`、`status`(AVAILABLE…)、`formal_sales_order_id`、`formal_sales_order_item_id`、客户快照 |
| `finished_goods_movements` | 批次流水（V218） | 每次出入库记录 |
| `manufacturing_orders` | 生产任务（V218/V224） | 挂正式订单行，**不写 sales_product_id**（完工入库时才 ensure MFG- SKU） |
| `product_inventory_balances` | SKU×仓库 库存余额（V236） | `sales_product_id`+`warehouse_id`+`unit` 维度 |
| `product_inventory_transactions` | 库存流水（V236） | 唯一键 `(source_type, source_id, finished_goods_lot_id)` |
| `sales_products` | 销售 SKU 主数据（V128/V235/V236） | `product_code`、`product_name`、`specification`、`unit`、`status`(ACTIVE/PENDING_REVIEW…)、`source`(MFG 等) |

### 2.2 关键服务与行为（backend/src/main/java/com/liqiang/archive/）

- **FormalSalesOrderService**（service/，JDBC 风格）
  - `create(CreateOrder)`：行产品经 `resolveProduct` 按机型解析 → 快照落 items；**`sales_product_id` 未写入**；`quantity`、`unit_price` 等。
  - `confirm(orderId)`：`UPDATE ... SET status='CONFIRMED' WHERE id=? AND status='DRAFT'` 单行原子守卫；目前**无任何库存校验**。
  - `cancel/close`：有 produced/shipped 则拦截；DRAFT→取消、已生产/已发后走 close。
- **FormalSalesShipmentService**（service/，JDBC 风格）
  - `create(CreateShipment)`：入参为 `allocations:[{lotId, quantity}]`；强校验：**所有批次必须同一 formal 订单 + 同客户 + 同仓库 + 同业务员** → 锁批次行 FOR UPDATE → 扣 `available_quantity`、插出库单/明细、写 `finished_goods_movements` + `productInventory.post`、回写订单行 `shipped_quantity`、`recomputeOrderStatus`（含订单费用按量分摊）。
  - 有 `reverse`（V230 出库单反向冲销）。
  - **这正是"现货出库"要扩展/复用的地方。**
- **ManufacturingService**（service/）
  - `createFromSalesOrder(orderId,itemId,LaunchProduction)`：由订单行建生产任务。
  - `complete(...)`（完工入库）：`ensureManufacturingProduct` 解析/创建 MFG-xxxx 的 sales_product → 建 `finished_goods_lots`（绑定订单行）→ 写 `COMPLETION_RECEIPT` 流水 + `productInventory.post`。
  - **availableLots**：待出库批次池查询，**只返回"绑定正式订单且订单未取消/未关闭"的批次**（手工任务/无订单批次被排除）→ 这正是现货卖不出去的原因之一。
- **CustomerService**（service/，JPA）
  - `create`：只强制 customerName；customerCode 自动 `CUS…`；status/relationStatus/customerType 有 Java/DB 默认（ACTIVE/NORMAL/OTHER）。
  - `addContact`：联系人必填 name。
- **权限（security/PermissionInterceptor.java + V221/V220/V249 迁移）**
  - `@RequirePermission`：`any=true` 时任一命中即放行；写接口必须声明注解。
  - `SALESPERSON` / `SALES_MANAGER` 已拥有：`formal_sales_order.manage`、`formal_sales_order.read.self/.department`、`sales_shipment.manage`。
  - `customer.manage` **未授予** SALESPERSON/SALES_MANAGER（授予给了 CUSTOMER 角色组）。→ 内联建档**不能**走 POST /api/customers，需在订单域下用 `formal_sales_order.manage` 权限开新端点。

### 2.3 前端相关文件（frontend/src/）

- `views/sales/FormalSalesOrderView.vue`：列表 + 新建订单弹窗（客户下拉、产品行表格：机型/档位/定制…）+ 详情抽屉（下生产任务、出库入口、行删除/修改）+ 订单状态流转。
- `api/sales/formalOrders.ts`：API 封装。
- `views/sales/FormalSalesShipmentView.vue`：出库中心，按订单从"待出库池"选批次出库。
- 客户档案页有自己的建档弹窗可参考交互（CustomerView.vue）。
- 产品选择当前用 catalog 机型列表（`fetchCatalogModels` 之类），**没有 SKU 检索能力**。

---

## 3. 方案总览（核心设计，已推演到实现级）

### 3.1 核心概念

1. **订单行新增 `supply_type`**：`STOCK`（现货）/ `MTO`（待生产），默认 MTO（老数据兼容）。
   - STOCK 行：**必须选 sales_product_id（SKU）**；可选的机型/档位只是补充信息，自动带出/手填，不参与生产。
   - MTO 行：保留现有机型/档位/定制字段（生产下单链路依赖机型找制程模板）；SKU 可选（选了就写入 `sales_product_id`，没选则完工入库时仍走 MFG 自动建 SKU，与现状一致）。
2. **可售现货池（现货库存口径）** = `finished_goods_lots` 中：
   `status='AVAILABLE' AND available_quantity>0 AND (formal_sales_order_id IS NULL OR 其订单 status IN ('CANCELLED','CLOSED','COMPLETED'))`
   —— 即"未承诺给任何活跃订单"的批次。当前系统不支持超量生产，所以实际现货主要来自：已取消/关闭订单的剩余批次、无正式订单的手工/氚云任务批次。
3. **预留量（锁定，派生不落表）** = 对某 SKU 求和：
   ```sql
   SELECT COALESCE(SUM(i.quantity - i.shipped_quantity),0)
   FROM formal_sales_order_items i JOIN formal_sales_orders o ON o.id=i.sales_order_id
   WHERE i.supply_type='STOCK' AND i.sales_product_id=:sku
     AND o.status IN ('CONFIRMED','IN_PRODUCTION','PARTIALLY_SHIPPED');
   ```
   - 确认时校验：`现有预留 + 本单预留 ≤ 现货池`；校验不通过整单回滚（订单保持 DRAFT）。
   - 出库即 `shipped_quantity` 增加 → 预留自动释放；订单 CLOSED/CANCELLED 后预留自动释放（状态条件自然退出）。
   - **为什么派生不落表**：避免锁表与同步问题，且 shipped/取消天然对账。库存口径只在"确认"与"现货出库"两处做原子校验。
   - **并发**：confirm() 里对每个 STOCK 行先 `SELECT id FROM sales_products WHERE id=? FOR UPDATE` 串行化同 SKU 的确认，再校验+更新状态。
4. **现货出库（FIFO 不转单、不改批次归属）**：
   - 在**订单详情页**对 STOCK 行做"现货出库"，而不是改造全局出库中心（降低对 MTO 主链路的回归风险）。
   - 选批次：该 SKU 的现货池批次按 `created_at ASC`（FIFO）列出，用户挑批次+数量（一单只允许同一仓库）。
   - 后端 `createStockShipment`：锁订单行与批次行 → 校验（批次属于现货池、SKU 匹配、数量≤本行剩余应发、同仓库）→ **不修改批次对原订单的归属** → 出库明细的 `formal_sales_order_item_id` 指向**本次售卖订单行**（回写其 shipped_quantity），成本快照取"批次原订单行 unit_cost，无则用售卖行 unit_cost" → 复用与普通出库相同的扣减/流水/余额/费用分摊逻辑。
   - 反向冲销天然安全：批次恢复 available 后仍属现货池（其原订单本就终端态）。
5. **新客户内联建档**：不放开 customer.manage，而是在**订单控制器**下新增：
   `POST /api/formal-sales-orders/quick-customers`，`@RequirePermission("formal_sales_order.manage")`，只建最小客户 + 联系人（客户名称必填；联系人姓名/手机可选），返回客户摘要供下拉自动选中。

### 3.2 推荐实施顺序（每阶段可独立验证）

**P0 阶段一：数据层**
- 新建 `backend/src/main/resources/db/migration/V250__formal_sales_order_supply_type.sql`：
  ```sql
  ALTER TABLE formal_sales_order_items
      ADD COLUMN supply_type VARCHAR(20) NOT NULL DEFAULT 'MTO'
          COMMENT '供货方式: STOCK=现货 / MTO=按单生产';
  -- 现货池与预留统计用的索引
  CREATE INDEX idx_fso_items_supply_lookup
      ON formal_sales_order_items (sales_product_id, supply_type);
  CREATE INDEX idx_finished_goods_lots_pool
      ON finished_goods_lots (sales_product_id, status);
  ```
- 顺手复核 sales_products 是否有 deleted_at（V128 建表），软删过滤写法。

**P1 阶段二：订单侧后端（SKU 化 + 确认锁库存）**
- `FormalSalesOrderDtos`（或对应 DTO 文件）：
  - `CreateOrderItem` 增加：`Long salesProductId`、`String supplyType`。
  - `FormalOrderItem`（列表/详情响应）增加：`Long salesProductId`、`String productCode`（LEFT JOIN sales_products 带出 SKU 编码）、`String supplyType`。
- `FormalSalesOrderService.create`：
  - 行解析：`supplyType` 归一化（空→MTO，非法→抛错）。
  - STOCK 行：`salesProductId` 必填且存在（软删过滤）；机型/档位不再必填。
  - MTO 行：保留现有机型解析（resolveProduct），`salesProductId` 有则写。
  - INSERT items 补写 `sales_product_id`、`supply_type` 两列。
- `confirm`：
  - 先按上面 3.1-3 锁 SKU 行 + 校验；全部通过再 `UPDATE status='CONFIRMED'`。
  - 错误文案示例：`第 2 行「xxx」现货可售库存不足：需 N，可售 M`。
  - 审计日志带上锁定信息。
- `availableLots` 之外的只读查询新增两个 helper（供 UI 展示可售/预留）：
  - `GET .../stock-overview?productIds=` 或直接在 detail 接口里按行返回 `availableStock`（现货池合计）与 `reservedQuantity`（含本人）。

**P2 阶段三：现货出库后端**
- `FormalSalesShipmentService` 重构（先做小步重构再扩展，避免双份资金/费用逻辑漂移）：
  - 把 `create()` 中"费用分摊 → 插出库单/明细 → 扣批次 → 写流水/余额 → 回写 shipped_quantity → recomputeOrderStatus → 审计"段抽成私有方法（入参：有效订单上下文、已锁批次、各批次数量/单价/成本/订单行ID映射、出库单元数据），`create()` 继续走它。
- 新增：
  - `POST /api/formal-sales-orders/{orderId}/items/{itemId}/stock-shipments`（订单控制器，@RequirePermission({"formal_sales_order.manage","sales_shipment.manage"})）
  - `GET  /api/formal-sales-orders/{orderId}/items/{itemId}/stock-pool`（FIFO 现货池候选列表，@RequirePermission read 系列）
- 校验清单（写死在实现里）：订单 status ∈ CONFIRMED/IN_PRODUCTION/PARTIALLY_SHIPPED；行 supply_type=STOCK；数量 ≤ quantity−shipped_quantity；批次属于现货池且 SKU 匹配；同仓库；数量≤批次 available_quantity。

**P3 阶段四：客户内联建档（后端）**
- `CustomerService` 新增 `quickCreate(...)`（@Transactional，建客户+联系人+审计，customerCode 复用现有生成规则）。
- `FormalSalesOrderController` 新增 `POST /api/formal-sales-orders/quick-customers` 转发调用（权限用 formal_sales_order.manage）。

**P4 阶段五：前端**
1. `api/sales/formalOrders.ts`：类型扩展 + 新增函数：
   - `quickCreateCustomer(payload)`（POST quick-customers）
   - `fetchStockPool(orderId, itemId, params)`、`createStockShipment(orderId, itemId, payload)`
   - SKU 搜索复用 `api/salesProduct/index.ts` 的列表函数（确认其出参结构）。
2. `views/sales/FormalSalesOrderView.vue`（大文件，**按 AGENTS.md 建议用 python 批量改或分块小步 replace**）：
   - 新建订单弹窗客户区：下拉 + "+ 新建客户"，内联小弹窗（名称/联系人/手机/地址）→ 调 quick-customers → 自动选中。
   - 产品行表格改版（可参考列顺序）：
     1. **供货方式**：el-select STOCK/MTO（中文"现货/待生产"），切换控制下面列显隐；
     2. **销售产品（SKU）**：el-select filterable remote，搜 sales_products，label 显示 `code + name + spec + unit`；选中后自动带出 产品名/规格/单位（STOCK 行必选）；
     3. **机型（标准产品）**：STOCK 行可选/自动带出；MTO 行保持现必选逻辑与自动带出；
     4. 档位/定制等列仅 MTO 显示（保留现有）。
   - 行切换 SKU 后建议调一次"该 SKU 可售"接口提示可售数。
   - 详情抽屉：STOCK 行显示 剩余应发（=quantity−shipped_quantity）+ "现货出库"按钮 → 弹窗拉 stock-pool（FIFO），选批次填数量，调 createStockShipment。
   - 列表页/详情行状态标签可显示供货方式（现货/待生产）。
3. 出库中心 `FormalSalesShipmentView.vue` **不改**（MTO 出库仍走原路）。

---

## 4. 建议先复核的现状点（动手前重读，别凭本文档盲改）

1. `FormalSalesOrderService.java` 全文：create 的 INSERT 列清单、OrderItem/DTO 定义位置（是 service 内 record 还是独立 DTO 文件）、confirm/cancel/close/审计写法。
2. `FormalSalesShipmentService.java` 全文：`create()` 的完整入参与内部段（单价/成本来源、费用分摊、recomputeOrderStatus），确认"抽私有方法"的切点。
3. `FormalSalesOrderController.java` + `FormalSalesShipmentController.java`：现权限注解写法（value/any 用法）与路由前缀。
4. `ManufacturingService.availableLots` 完整 SQL（现货池与之相反口径，注意别漏 ORDER BY/分页）。
5. `ProductInventoryService.post(...)`：确认同 source 多 lot 的流水唯一键处理与余额更新方式。
6. `frontend/src/api/salesProduct/index.ts`：SKU 列表/搜索函数签名与返回结构（items/total 字段名）。
7. `frontend/src/views/sales/FormalSalesOrderView.vue` 新建弹窗部分：客户下拉、产品行行的完整结构（这是前端主改点）。
8. 角色与权限：确认有没有别的角色需要下单（如 OFFICE）也应拿到 quick-customers 权限；`formal_sales_order.manage` 授权角色清单在 V221 迁移。
9. `sales_products.status` 的取值语义（ACTIVE/PENDING_REVIEW），定 STOCK 行是否强制 ACTIVE（建议：UI 默认只搜 ACTIVE，后端只做存在性校验，别过度收紧，避免 MFG- 待审 SKU 卖不了）。

### 4.1 复核结论（2026-09-03 复核后落定，与原文建议有出入的地方以下面为准）

1. **`FormalSalesOrderService`** 只有一个 `OrderItem` record（service 内嵌，不是独立 DTO 文件）；`confirm` 原文是"单行原子 UPDATE 守卫"，现在改成"先 `FOR UPDATE` 锁订单行 + 校验 → 再 UPDATE"。
2. **`FormalSalesShipmentService#create`** 的切点确认：从"订单费用结转"开始到 `return shipmentId` 整段可抽，与"锁批次 / 校验同仓同客户同业务员同订单 / 查单价成本快照"这几段无关。已抽成 `postShipment(PostContext)`，`PostContext` 用 `ShipmentMeta`（收货/物流/备注）+ `List<ShipmentLine>` 承载差异。
3. **权限注解**：项目用的是 `@RequirePermission({"a","b"})`（`any` 默认 true），控制器无类级权限注解，逐方法声明。
4. **`ManufacturingService#availableLots`** 的口径是「`formal_sales_order_id IS NOT NULL` 且订单不在 CANCELLED/CLOSED」，现货池正好是它的补集（再放宽"无订单批次 + 原订单 COMPLETED"）。注意 `availableLots` 是 `ORDER BY l.id DESC` + 分页，现货池用 `ORDER BY created_at ASC, id ASC` 的 FIFO 且不分页（候选量小）。
5. **`ProductInventoryService#post`** 唯一键是 `(source_type, source_id, finished_goods_lot_id)`，同一张出库单多个批次天然不冲突（lot 不同），无需特殊处理。
6. **`api/salesProduct/index.ts`**：`fetchSalesProducts({keyword, category, status, page, size})` → `PageResult<SalesProductRow>`（`items/total/page/size`）。
7. **`FormalSalesOrderView.vue`** 复核时 574 行（< 1000），用 `replace_in_file` 分块改即可，无需 python 批量替换。
8. **`formal_sales_order.manage`** 现授予 ADMIN / BOSS / SALES_MANAGER / SALESPERSON（V221）。quick-customers 复用该权限，未新增权限码、未改迁移；若后续 OFFICE 等角色也要下单，只需给其授 `formal_sales_order.manage`。
9. **SKU 搜索不按 `status` 过滤**（与原文建议相反）：现货池里的批次绝大多数是生产入库生成的 `MFG-` 待审（`PENDING_REVIEW`）SKU，若 UI 只搜 ACTIVE 就选不到现货。后端仍只做 `deleted_at IS NULL` 存在性校验，不强制 ACTIVE。

## 5. 开放问题 / 需拍板（新账号开工前 1 次问清即可）

1. ~~现货成本口径~~ **已拍板（2026-09-03）**，见 §5.1。
2. 现货行是否允许改价/优惠（建议允许，等同现有 unit_price 编辑）。→ **沿用建议：允许**（unit_price 本就是行级可编辑字段，无需额外开关）。
3. 现货池是否要包含"期初开账余额但无批次"的部分（当前库存盘点/开账走 product_inventory_balances、无 finished_goods_lots，**无法按批次出库**）。→ **本期不做**，维持"现货池 = 可追溯的成品批次"口径，需按批次出库以保证成本可溯源；无批次库存单独一期处理（开账产生批次或虚拟批次）。
4. 是否需要"现货不足时拆行自动转 MTO"的快捷交互（本期可不做，先让用户手动切供货方式）。→ **本期不做**。

### 5.1 现货成本口径（已定）

**规则**：现货出库行的成本单价按优先级取值，并把来源写进 `formal_sales_shipment_items.unit_cost_source`：

| 优先级 | 取值 | `unit_cost_source` |
|---|---|---|
| 1 | 批次**原订单行**（`finished_goods_lots.formal_sales_order_item_id`）的 `unit_cost`，且 > 0 | `LOT_ORIGIN` |
| 2 | 本单**售卖行**的 `unit_cost` | `ORDER_ITEM` |
| 3 | 0 | `ZERO` |

**理由**：
- 出库成本（COGS）应反映"实际发出去的这批货"的真实成本。现货批次是当初为某张订单生产的，其成本记录在原订单行上；售卖行填的 `unit_cost` 是本次接单时的报价型采购价，销售员对现货往往填 0 或随手填。
- 若采用售卖行 `unit_cost`：同一批货卖给不同客户会算出不同利润，且销售员漏填即利润虚高，财务无法对账。
- 回退链保证历史数据缺成本时不会导致出库失败；`unit_cost_source` 落库 + 审计日志让财务可追溯"这批货成本取自哪张订单哪一行"。
- 单价（`unit_price`）**始终取售卖行**，收入口径不受影响；因此毛利 = 本单售价 − 批次真实成本。

**落地位置**：`FormalSalesShipmentService#resolveStockUnitCost`（已实现）。现货池接口 `stock-pool` 会回传每个批次的 `originUnitCost` / `originOrderNo`，出库弹窗里直接显示"成本来源：原订单行 ¥x（SO-xxxxxx）"或"回退本单采购价"。

## 6. 验证清单（每阶段验收）

### 6.1 已完成（2026-09-03，代码已落地，待联调/走查）

- [x] **P0 迁移 V250**：`formal_sales_order_items.supply_type`（默认 MTO）+ 两个索引 + `formal_sales_shipment_items.unit_cost_source`。
- [x] **P1 订单侧**：`OrderItem` 增 `salesProductId/supplyType`；`create` 归一化供货方式、STOCK 必选 SKU（只做存在性校验，不强制 ACTIVE）、SKU 带出名称/规格/单位、INSERT 写两列；`detail` 每行带出 `productCode/availableStock/reservedQuantity`；`confirm` 按 SKU `FOR UPDATE` 串行化 + 预留校验（不通过整单回滚保持 DRAFT）；新增 `GET /api/formal-sales-orders/stock-overview?productIds=`。
- [x] **P2 现货出库**：`create()` 的"费用分摊→插单/明细→扣批次→流水/余额→回写 shipped_quantity→推导订单状态→审计"抽为 `postShipment(PostContext)`，MTO 与现货共用；新增 `GET /{id}/items/{itemId}/stock-pool`、`POST /{id}/items/{itemId}/stock-shipments`；成本按 §5.1 三级回退并写 `unit_cost_source`；`ManufacturingService#createFromSalesOrder` 增加"现货行不得下达生产"拦截。
- [x] **P3 内联建档**：`CustomerService#quickCreate(QuickCustomerPayload)` + `POST /api/formal-sales-orders/quick-customers`（`@RequirePermission("formal_sales_order.manage")`，不动 customer.manage）。
- [x] **P4 前端**：`api/sales/formalOrders.ts` 类型与 4 个新函数；`FormalSalesOrderView.vue` 新增供货方式列、SKU 远程搜索列（带现货可售提示）、客户"+ 新建客户"内联弹窗、详情行"现货出库"弹窗（FIFO 批次 + 成本来源展示）；STOCK 行隐藏档位/定制列与"下生产任务"。
- [x] 后端 `mvn compile`、前端 `vue-tsc` + `npm run build` 均通过。

### 6.2 联调验收

**（2026-09-03 已在现有库跑通：`scripts/tmp-lq/run-lian-tiao.mjs` 37/37 全绿）**

- [x] P1：创建 STOCK 行订单 → 确认成功；再造一单超可售 → 确认被拦截且订单保持 DRAFT；取消订单 → 预留释放可再次下单。
- [x] P2：现货出库 → 库存余额/批次 available/流水/订单 shipped_quantity 均正确；反向冲销后批次回池；**MTO 老出库回归无影响（拿历史出库单复测，重点看费用分摊与 `postShipment` 重构）**。
  - 整批出库金额/成本/毛利/`unitCostSource=ORDER_ITEM`、两批费用分摊（123.45 → 49.38/74.07 尾差结转）、反冲销回池、订单状态流转（COMPLETED↔PARTIALLY_SHIPPED↔CONFIRMED）均通过。
- [x] P3：SALESPERSON 角色在订单页 + 新建客户 → 建成功并选中；无权限角色被 403。
- [ ] P4：新建订单、详情、出库交互走查（人工 UI）。
- [~] 迁移：现有库可执行（本次联调在 V250 已应用的库上验证）；空库执行未单独验证，老 DRAFT 单默认 MTO 兼容由 `supply_type NOT NULL DEFAULT 'MTO'` 保证。

**联调过程中修正的两处（非代码缺陷，属环境/脚本问题）**：
1. 统一库存台账基线缺失：本库 `product_inventory_balances` 为空（V236 在批次数据灌入前已标记执行，未按 `finished_goods_lots` 可用量回填），出库过账扣统一库存报「统一产品库存不足」。已按 V236 口径用 `scripts/tmp-lq/seed-inventory-baseline.sql` 回填（余额 1 + 流水 PIM00000007），再跑即绿。其他环境同样场景可复用该脚本。
2. A2 整批出库反冲销后回 CONFIRMED 重新占住预留，费用分摊 B 单会因「可售 0」被拦 —— 脚本先取消 A2 释放预留再建 B 单（代码行为符合设计：反冲后订单仍持有预留）。

## 7. 一句话提醒

- 最大迁移版本当前 **V249**，新脚本一律 **V250**（不要建子版本号）。
- 后端所有新写接口必须显式声明 `@RequirePermission`，否则被 PermissionInterceptor 拒绝（POST/PUT/DELETE/PATCH 一律 403）。
- `FormalSalesOrderView.vue` 是超大文件（>1000 行），改前按 AGENTS.md 策略：优先 python/sed 精准替换，避免整文件重写。
- docs/h3-product-stock-redesign-20260901.md 与本改造同属"库存口径统一（sales_products + product_inventory_balances）"大方向，动 sales_products/库存前可交叉参考。
