# 员工身份 / 订单归属 / 是否计提 三分设计（提成资格与历史身份）

> 状态：实施中 —— §7.2 历史组织代码清理已完成（2026-08-31，后端编译/测试编译/前端 typecheck 通过）；V212 迁移已编写待部署（含建档+归属迁移+清理闭环，部署即执行归属迁移，需与员工资格/映射能力同批确认）
> 更新日期：2026-08-31（修订：**移除"历史组织归属"概念**，梅奥德行政与李燕燕统一按员工档案处理）
> 适用模块：员工名册（EmployeeRosterView）、我的订单（MyOrdersView）、销售出库利润与提成主链（SalesOutboundProfitService / SalesOutboundCommissionService）
> 关联方案：`docs/current-sales-profit-design.md`（销售出库利润与提成主链）
> 核心原则：**员工是谁（档案）、订单归谁（employee_id 归属）、是否计提（提成资格）三者独立建模，互不强耦合，历史身份问题不再塞进利润核算规则。** 不存在"历史组织归属"这一特殊通道——任何历史人员都只是员工档案的一种（`employee_kind=HISTORICAL`）。

---

## 0. 现状核查结论（2026-08-31 只读探查生产库）

| 核查项 | 梅奥德行政 | 李燕燕 |
|---|---|---|
| `employees` 档案 | 不存在 | 不存在 |
| `employee_external_mappings` | 无记录 | 无记录 |
| CONFIRMED 出库数 | 643 张 | 74 张 |
| 销售额合计 | 1,492,581.18 | 382,168.45 |
| `employee_id` 已绑定 | 0（643 全空） | 0（74 全空） |
| 利润行 / 提成行 | 0 / 0 | 0 / 0 |
| 已结算提成 | 0 | 0 |

**历史组织遗留（将被本设计废弃）：**
- V211 曾引入"历史组织归属"概念：`historical_sales_organizations` 表仅 1 条记录（梅奥德行政，INACTIVE），梅奥德行政 643 张订单已写入 `sales_outbounds.historical_organization_id=1`。
- 该概念**从未真正生效**：全局 `EXCLUDED` 提成行 = 0，643 张订单无利润/提成行，"不参与个人提成"从未在提成结果上落库。
- 本设计**整体移除**该概念：梅奥德行政与李燕燕一样，作为员工档案处理；`historical_organization_id` 列与 `historical_sales_organizations` 表在迁移中清理（详见 §5）。

**关键判断：**
1. 两位均无已结算提成 → 迁移不触碰任何历史财务凭证。
2. 李燕燕 74 张订单完全未绑定，全部落入"业务员未匹配"（`EMPLOYEE_UNMATCHED`）。
3. 梅奥德行政 643 张订单因历史组织遗留而 `employee_id` 为空，历史组织清理后同样进入"业务员未匹配"候选，按 §2/§5 统一绑定。
4. 当前 `sales_outbounds` 已绑定 `employee_id` 的订单约 10.5 万张（主体正常）。

---

## 1. 员工档案：三个独立维度

### 1.1 新增字段（`employees`，新迁移 V212）

| 字段 | 类型 | 默认 | 含义 |
|---|---|---|---|
| `employee_kind` | VARCHAR(20) | `NATURAL` | 员工类型：`NATURAL` 自然人员工 / `HISTORICAL` 历史档案（历史员工、代用身份） |
| `commission_eligibility` | VARCHAR(20) | `UNKNOWN` | 提成资格：`UNKNOWN` 待确认 / `PARTICIPATE` 参与计提 / `EXCLUDE` 不参与计提 |
| `commission_eligibility_reason` | VARCHAR(500) | NULL | 提成资格说明；`EXCLUDE` 必填原因；`UNKNOWN` 可填待办说明 |

**不新增** `phone` / `leave_date` / 钉钉 userId 等缺失字段的编造约束——现有 schema 这些列本就可空，历史员工一律留 NULL。

### 1.2 两人档案初始值（本轮不落库，仅作为迁移目标）

| 项目 | 梅奥德行政 | 李燕燕 |
|---|---|---|
| `status` | `RESIGNED`（已离职） | `RESIGNED`（已离职） |
| `employee_kind` | `HISTORICAL` | `HISTORICAL` |
| 登录账号 | 不创建 | 不创建 |
| 备注 | 历史子公司业务代用身份，非自然人员工 | 历史员工，其他资料待补充 |
| `commission_eligibility` | `UNKNOWN`（待确认） | `UNKNOWN`（待确认） |
| phone / leave_date / 钉钉 userId | NULL | NULL |

两人与普通员工唯一的区别是 `employee_kind=HISTORICAL` 与默认资格 `UNKNOWN`，**不再依赖任何"历史组织"概念**。

### 1.3 行为约束

- **已离职员工默认不出现在新增业务的人员选择中**：人员选择类接口（`GET /api/employees/search` 及业务员下拉）默认只返回 `ACTIVE`；历史查询（订单详情、员工详情）不受影响。需要查离职员工时显式传 `status=RESIGNED`。
- **唯一性**：`uk_employees_name_phone (name, phone)` 在 phone=NULL 时不阻止重名（MySQL 唯一索引 NULL 可重复），历史员工建档必须在 Service 层做 `employee_kind + name` 防重校验（同类型同姓名不得重复建档），并保留"同名不同员工"的可能性（不自动合并）。

---

## 2. 历史身份映射（员工名册现有流程扩展）

现有映射流程（`ExternalIdentityImportDialog` + `EmployeeExternalImportService` + `H3EmployeeIdentityService` + `EmployeeService.addMapping`）已具备"选择已有员工 / 新建档案"。本次扩展三点：

### 2.1 新建员工增加类型选择

`ApplyIdentityItem` 与前端"未匹配新建"增加字段：

```
createKind: ACTIVE（在职员工） | HISTORICAL（历史员工）
```

- 选 `HISTORICAL`：落库 `status=RESIGNED`、`employee_kind=HISTORICAL`、`commission_eligibility=UNKNOWN`，不自动授予 SALESPERSON 角色、不参与人员选择下拉。
- 选 `ACTIVE`：维持现状（`status=ACTIVE` + 自动业务员角色逻辑）。

### 2.2 按来源姓名生成候选订单（新增接口）

```
GET /api/salesperson-identity-candidates?source=H3YUN&name=李燕燕
→ { orders: [{ outbound_no, ship_date, customer_name, sales_amount, outbound_status }],
    count, minDate, maxDate, totalAmount }
```

候选条件（与现有"业务员未匹配"口径一致）：

```
sales_outbounds.external_source='H3YUN'
AND TRIM(salesperson_name)=来源姓名
AND employee_id IS NULL
```

> 历史组织列清理后（§5），梅奥德行政 643 张订单同样命中该候选条件，与李燕燕走同一套映射流程。

**姓名仅用于生成候选，绝不自动合并。** 管理员在员工名册看到候选订单的数量、日期范围、客户与金额后，执行：
1. 选择目标员工（已有档案）或新建（在职/历史）；
2. 确认绑定 → 写 `employee_external_mappings`（`H3YUN` + `external_name=来源姓名`，无 userId 时 `external_user_id` 用 `NAME_LINK#<姓名>` 占位，与钉钉现有约定一致）+ 批量回填 `sales_outbounds.employee_id` + 写映射依据与处理日志。

### 2.3 映射依据与处理日志（新增表）

```
CREATE TABLE salesperson_identity_candidates (
  id              BIGINT AUTO_INCREMENT PRIMARY KEY,
  source_system   VARCHAR(20) NOT NULL,          -- H3YUN / DINGTALK
  source_name     VARCHAR(100) NOT NULL,         -- 来源姓名（原样保留）
  employee_id     BIGINT NULL,                   -- 确认绑定的员工
  order_count     INT NOT NULL DEFAULT 0,
  min_ship_date   DATE NULL,
  max_ship_date   DATE NULL,
  total_amount    DECIMAL(14,2) NOT NULL DEFAULT 0,
  status          VARCHAR(20) NOT NULL DEFAULT 'PENDING',  -- PENDING / CONFIRMED / CONFLICT / SKIPPED
  mapping_basis   VARCHAR(500) NULL,             -- 映射依据（人工填写，审计）
  resolved_by     VARCHAR(100) NULL,
  resolved_at     DATETIME NULL,
  created_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
)
```

规则：
- **后续导入遇到已确认映射时复用**：命中 `employee_external_mappings` 中唯一确定的 `(H3YUN, 姓名)` 且 `employee_id` 唯一 → 直接按现有 `H3EmployeeIdentityService` 回填（保留现有语义）。
- **遇到多个候选或已有不同归属时进入冲突队列**：`source_name` 对应多个不同 `employee_id`，或订单已存在归属且与本次绑定冲突 → 状态 `CONFLICT`，人工处理，不自动覆盖已有归属。

---

## 3. 是否计提独立设置（员工级 + 订单级例外）

### 3.1 员工级（`employees.commission_eligibility`）

| 设置 | 系统行为 |
|---|---|
| `UNKNOWN`（待确认） | 正常核算利润；提成显示"资格待确认" |
| `PARTICIPATE`（参与计提） | 按适用规则试算；无规则时提示"缺少规则" |
| `EXCLUDE`（不参与计提） | 正常核算利润；提成显示"不参与计提"，展示原因 |

**与在职状态完全分开**：`status=RESIGNED` 不影响资格判断；`commission_eligibility` 不影响在职状态。

### 3.2 订单级例外（`sales_outbound_commissions` 新增列）

| 字段 | 说明 |
|---|---|
| `eligibility_override` | `NULL` 继承员工设置 / `PARTICIPATE` 此单参与 / `EXCLUDE` 此单不参与 |
| `eligibility_override_reason` | 订单级原因（必填） |
| `eligibility_status` | 生效资格（含继承结果）：`UNKNOWN` / `PARTICIPATE` / `EXCLUDE` |
| `eligibility_source` | 来源：`EMPLOYEE_DEFAULT` / `ORDER_OVERRIDE` |

**优先级：订单例外 > 员工默认。** 已结算记录（`status='SETTLED'`）不被普通修改覆盖（沿用现有 `recalculate` 的 SETTLED 保护）。

### 3.3 计算改造（`SalesOutboundCommissionService.recalculate`）

```
effective_eligibility = COALESCE(override, employee.commission_eligibility, 'UNKNOWN')
```

- 身份未绑定（`employee_id IS NULL`）→ 维持"身份未匹配"（`status='WAITING_RULE'`），**不再与资格混排**。
- `effective_eligibility='EXCLUDE'` → 提成金额置 0，`eligibility_status='EXCLUDE'`，展示"不参与计提"。
- `effective_eligibility='UNKNOWN'` → 正常试算但 `eligibility_status='UNKNOWN'`，展示"资格待确认"。
- `effective_eligibility='PARTICIPATE'` → 无规则 `status='WAITING_RULE'` 展示"缺少规则"；有规则按现状试算。

**提成状态机清理**：`status` 只表示计算阶段（TRIAL/WAITING_RULE/NOT_AVAILABLE/AVAILABLE/SETTLED），**移除 `EXCLUDED`**（历史组织专属、全局 0 行，无数据依赖）。"不参与计提"统一由 `eligibility_status='EXCLUDE'` 表达，资格是独立维度，二者组合成前端"提成待办"分组（见 §6）。

---

## 4. 修改资格时先预览影响

```
POST /api/employees/{id}/commission-eligibility/preview   { eligibility, reason }
→ {
    unsettledCount,      // 涉及该员工的未结算订单数
    recalcOrders: [...], // 将重新试算的订单（未结算、无订单级例外、未 SETTLED）
    settledCount,        // 已结算、不会修改的订单数
    overriddenCount,     // 已单独设置订单级例外、不受员工默认值影响的订单数
    settleProtected: [...]  // 已结算订单列表（只读提示）
  }

POST /api/employees/{id}/commission-eligibility            { eligibility, reason }
```

确认后：
1. 写审计日志：修改前值、修改后值、操作人、原因、时间（`EMP_COMMISSION_ELIGIBILITY_CHANGE`）。
2. 只刷新**相关未结算订单的提成结果**（`SalesOutboundCommissionService.recalculateByEmployee` 语义），**不重算销售额、成本、回款与实际利润**（不动 `sales_outbound_profits`）。
3. 已结算订单、已单独设置订单例外的订单不被动。

订单级例外的设置/撤销走 `POST /api/sales-outbound-commissions/{outboundId}/eligibility`，同样写审计 + 单张重算，已结算订单拒绝修改。

---

## 5. 迁移：历史组织概念移除 + 两人员工归属

### 5.1 判定（基于 §0 核查）

- 历史组织概念（V211）**只有数据、无生效逻辑**：`historical_organization_id` 有 643 行，`EXCLUDED` 提成 0 行，两位无已结算记录 → 清理零风险。
- 迁移目标：**删除历史组织概念，两人统一为员工档案归属 + 资格待确认**。

### 5.2 迁移步骤（在 §1、§2、§3 能力就绪后执行，建议放同一部署批次）

1. **建档**：新建唯一"梅奥德行政"与"李燕燕"员工档案（`RESIGNED + HISTORICAL + UNKNOWN + 备注`），不创建登录账号、不编造缺失信息。
2. **梅奥德行政归属迁移**：`UPDATE sales_outbounds SET employee_id=<梅奥德行政档案id> WHERE historical_organization_id=1 AND employee_id IS NULL`（当前 643 张全部命中）。
3. **李燕燕归属映射**：走 §2 历史身份映射流程（74 张候选预览 → 确认绑定）。
4. **清理历史组织**（同批 V212 迁移 SQL，幂等）：
   ```sql
   ALTER TABLE sales_outbounds DROP FOREIGN KEY fk_outbound_historical_org;
   ALTER TABLE sales_outbounds DROP INDEX idx_outbound_historical_org;
   ALTER TABLE sales_outbounds DROP COLUMN historical_organization_id;
   DROP TABLE IF EXISTS historical_sales_organizations;
   ```
   清理前置断言（迁移脚本中校验）：`historical_sales_organizations` 无剩余订单引用、无 `SETTLED`/`EXCLUDED` 提成行。
5. **删除代码引用**（见 §7.2 清理清单）。
6. **资格**：两人订单初始化后 `eligibility_status='UNKNOWN'`（资格待确认），**不再自动排除计提**；若订单已有历史遗留人工设置，保留不覆盖。
7. **写映射依据**：两条 `salesperson_identity_candidates`（CONFIRMED + mapping_basis="历史组织概念移除迁移"/"历史员工建档映射"）。

---

## 6. 页面最终效果

| 页面 | 处理后表现 |
|---|---|
| 我的订单 · 业务员未匹配 | 两人已确认归属的 643 + 74 张订单不再出现在 `EMPLOYEE_UNMATCHED` |
| 员工名册 | 可查两人的已离职档案、`employee_kind=HISTORICAL` 标记、来源映射（`salesperson_identity_candidates`）与名下订单 |
| 订单详情 | 显示员工姓名 + 已离职标记 + 提成资格（待确认/参与/不参与）+ 设置来源（员工默认/订单例外）；**移除"历史组织归属 · 不参与个人提成"提示** |
| 提成待办 | 按资格与身份分组，单列"资格待确认"，不再与"身份未匹配""缺少规则"混排 |

**提成待办分组规则（前端派生，不新增复杂状态）：**

```
身份未绑定               → employee_id IS NULL
资格待确认               → eligibility_status = UNKNOWN
不参与计提               → eligibility_status = EXCLUDE（展示原因）
缺少规则                 → eligibility_status = PARTICIPATE AND status = WAITING_RULE
试算 / 可结算 / 已结算   → 其余
```

---

## 7. 变更清单

### 7.1 数据模型（新迁移 V212）

```
-- 员工提成资格
ALTER TABLE employees
  ADD COLUMN employee_kind VARCHAR(20) NOT NULL DEFAULT 'NATURAL'
      COMMENT 'NATURAL 自然人员工 / HISTORICAL 历史档案',
  ADD COLUMN commission_eligibility VARCHAR(20) NOT NULL DEFAULT 'UNKNOWN'
      COMMENT 'UNKNOWN 待确认 / PARTICIPATE 参与计提 / EXCLUDE 不参与计提',
  ADD COLUMN commission_eligibility_reason VARCHAR(500) NULL,
  ADD KEY idx_employees_eligibility (commission_eligibility);

-- 订单级提成资格例外
ALTER TABLE sales_outbound_commissions
  ADD COLUMN eligibility_override VARCHAR(20) NULL
      COMMENT 'NULL 继承员工 / PARTICIPATE / EXCLUDE',
  ADD COLUMN eligibility_override_reason VARCHAR(500) NULL,
  ADD COLUMN eligibility_status VARCHAR(20) NOT NULL DEFAULT 'UNKNOWN'
      COMMENT '生效资格（含继承结果）',
  ADD COLUMN eligibility_source VARCHAR(20) NOT NULL DEFAULT 'EMPLOYEE_DEFAULT'
      COMMENT 'EMPLOYEE_DEFAULT / ORDER_OVERRIDE',
  ADD KEY idx_soc_eligibility (eligibility_status);

-- 历史身份映射依据与处理日志
CREATE TABLE salesperson_identity_candidates ( ...见 §2.3... );

-- 历史组织概念清理（前置断言通过后执行）
ALTER TABLE sales_outbounds DROP FOREIGN KEY fk_outbound_historical_org;
ALTER TABLE sales_outbounds DROP INDEX idx_outbound_historical_org;
ALTER TABLE sales_outbounds DROP COLUMN historical_organization_id;
DROP TABLE IF EXISTS historical_sales_organizations;

-- 权限码
INSERT IGNORE INTO system_permissions(permission_code, permission_name) VALUES
 ('employee_commission.manage', '管理员工提成资格');
-- 分配给 ADMIN / BOSS / FINANCE（沿用 V205 的角色分配模式）
```

存量数据：现有在职员工 `commission_eligibility` 统一默认 `UNKNOWN`（保守口径，与"规则尚未正式确认"一致）；后续由老板在员工名册按员工/批量确认，不预设参与或排除。

### 7.2 历史组织代码清理清单（随 V212 一起实施）

| 文件 | 改动 |
|---|---|
| `backend/.../service/HistoricalSalesOrganizationService.java` | 整体删除 |
| `backend/.../controller/HistoricalSalesOrganizationController.java` | 整体删除 |
| `backend/src/test/.../HistoricalSalesOrganizationServiceTest.java` | 整体删除 |
| `frontend/src/components/customer/HistoricalOrganizationAction.vue` | 整体删除 |
| `backend/.../service/SalesOutboundProfitService.java` | ① READINESS_CASE：`WHEN o.employee_id IS NULL AND o.historical_organization_id IS NULL` → `WHEN o.employee_id IS NULL`；② 两处 `SELECT o.historical_organization_id` 移除；③ "业务员未匹配"计数 `AND historical_organization_id IS NULL` 移除 |
| `backend/.../service/SalesOutboundCommissionService.java` | ① SELECT 移除 `o.historical_organization_id`；② 删除 `historicalOrganization` 分支（`EXCLUDED` 状态） |
| `backend/.../service/H3FactImportService.java` | ① UPSERT 中 `employee_id=IF(historical_organization_id IS NULL,COALESCE(...),NULL)` → `employee_id=COALESCE(employee_id,VALUES(employee_id))`；② `syncUnchangedOutbound` 条件 `AND historical_organization_id IS NULL` 移除 |
| `backend/.../service/H3EmployeeIdentityService.java` | 回填条件 `AND o.historical_organization_id IS NULL` 移除 |
| `frontend/src/views/sales/MyOrdersView.vue` | ① 移除 `<HistoricalOrganizationAction>` 入口；② 移除"历史组织归属 · 不参与个人提成"alert；③ `commissionTag` 移除 `EXCLUDED` 条目 |
| `frontend/src/api/salesOutbound/orderProfit.ts` | 移除 `historicalOrganizationId` 字段 |
| `frontend/src/views/workbench/BossDashboardView.vue` | 核对无历史组织文案（当前"规则待确认"文案保留） |

---

## 8. 实施顺序

1. **员工提成资格**（§1.1 + §3 + §4）：字段、计算改造、预览弹窗、审计、权限码。
2. **历史员工建档与映射**（§1.2 + §2）：`employee_kind`、映射流程扩展、候选队列与冲突处理。
3. **历史组织移除与两人员工归属迁移**（§5 + §7.2）：建档 → 归属迁移 → 清理历史组织列/表与代码 → 资格 UNKNOWN。
4. **订单展示及回归验证**（§6）：详情字段、提成待办分组、`EMPLOYEE_UNMATCHED` 回归。

## 9. 明确禁止事项

1. 本轮不修改两人历史数据（档案、映射、订单归属均不动，等 §8 能力就绪后按迁移步骤执行）。
2. 禁止按姓名自动合并员工（姓名只生成候选，不自动建立归属）。
3. 禁止把提成资格塞进 `sales_outbound_commissions.status` 状态机（资格独立成 `eligibility_*` 维度）。
4. 禁止修改已结算提成记录；资格变更不得重算销售额、成本、回款与实际利润。
5. 禁止在 `employee_external_mappings` 编造缺失的 userId / 手机号 / 离职日期。
6. **历史组织清理必须与员工建档、归属迁移同批部署**：禁止先删表后迁移归属；清理前必须在迁移脚本中断言无剩余订单引用、无 `SETTLED`/`EXCLUDED` 提成行依赖。
7. 禁止在订单展示层继续使用"历史组织 · 不参与个人提成"等文案（已由员工档案 + 提成资格取代）。
