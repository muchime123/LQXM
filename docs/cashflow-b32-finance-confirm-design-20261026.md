# 资金日记账 B-3.2：年度期初财务核对确认设计稿

状态：只读设计审查；未实现接口、权限迁移或数据库写入。B-3.1 正式环境收口仍待三条误写测试键的授权清理与 8081 新代码部署复验。

## 1. 当前实现与边界

- V408 的 `cashflow_account_opening` 已有 `active`、`finance_confirmed`、`confirmed_by`、`confirmed_at`，以及每账户每年度唯一约束；没有核对证据、核对余额、证据日期字段。
- B-3.1 的人工建立为 `active=1, finance_confirmed=0`；`basis` 只是建立依据，不构成财务核对完成证明。2026 年迁移期初同样 `active=1, finance_confirmed=0`。
- V404 目前只有 `cashflow.read` 与 `cashflow.manage`。既有 P2 设计稿将确认端点暂挂 `cashflow.manage`，但财务确认是独立的责任，应细分权限并明确授权人员。
- `AuditLogService` 的通用日志可记录动作，但操作人目前由业务代码拼入文本，无法单独承担核对证据的结构化保存。
- 本阶段仅确认**某账户某年度的期初余额**是否与指定外部凭据一致；不代表该年度流水逐笔对账或上年期间正式结账，也不触发自动结转。

## 2. 状态与操作规则

| 当前状态 | 确认行为 | 结果 |
| --- | --- | --- |
| 无期初记录 | 拒绝，404 | 不建期初 |
| `active=0, finance_confirmed=0` | 拒绝，409 | 候选期初须先按其来源流程生效；本接口不改变 `active` |
| `active=1, finance_confirmed=0` | 凭据与金额核对通过 | 原行 `finance_confirmed=1`，写 `confirmed_by/at` 与独立证据记录；金额、来源、`active` 不变 |
| `active=1, finance_confirmed=1` | 同请求键重放 | 返回原结果，不重复写审计或证据 |
| `active=1, finance_confirmed=1` | 不同请求键再次确认 | 409，提示已确认及原确认人、时间；不覆盖证据 |

账户停用不抹去历史，允许有确认权限的财务核对其历史年度期初。若账户不存在则 404。确认后发现差异，不得直接改金额或将确认信息无痕覆盖；进入 B-3.3 更正流程。更正时保留旧金额与原确认事件（含原凭据、确认人和时间），将当前行 `finance_confirmed` 复位为 0、清空当前 `confirmed_by/at`，记录更正原因与新金额；`active` 是否保持生效由 B-3.3 的更正安全规则单独决定。**新金额必须重新提交独立核对证据并再次确认**，原确认不得自动覆盖新金额。

## 3. 核对证据

建议在 B-3.2 实施时增加一张专用、不可覆写的 `cashflow_opening_confirmation` 记录，而不是把凭据放入 `basis` 或仅写 `audit_logs.changes`。最少字段：`id`、`opening_id`、`opening_amount_snapshot DECIMAL(18,4)`、`evidence_type`、`balance_as_of_date`、`verified_balance DECIMAL(18,4)`、`evidence_ref`、`note`、`confirmed_by`、`confirmed_by_name`（姓名快照）、`confirmed_at`、`request_key`。`request_key` 唯一；`opening_id` 不单独设唯一，以便 B-3.3 更正后保留旧确认并追加新确认。2026-10-26 确定 `evidence_ref` 为**外部凭据编号，由财务保管原件**；系统校验编号非空、长度和格式，无法自动验证外部凭据是否真实存在或可访问，财务确认人对此承担核验责任。不得把编号误表述为已上传的系统文件。不在审计文字里存密钥或完整敏感文件内容。证据表保存已确认的金额快照，不能仅依赖之后可能更正的期初行。

首次确认要求：证据类型（银行对账单、现金盘点或其他可核实凭据）、余额截至日期、凭据引用、凭据所载余额，以及核对说明。`balance_as_of_date` 必须是上年 12 月 31 日期末或本年 1 月 1 日期初；文件出具日期可以晚于该日，但文件必须明确对应这一余额时点。凭据余额必须与当前期初金额按四位小数**精确相等**；不允许自动舍入、容差或隐式修改期初。凭据不对应这一时点或金额不符则 409，本接口不推断中间流水差额。历史账户如无法取得足够证据，保持待确认，不凭导入对拍代替财务核对。

2026 年迁移记录也只能凭真实凭据确认；35/35 金额迁移对拍、`basis` 文本或 P1 报表闭合均不自动设置 `finance_confirmed=1`。

## 4. 权限、并发与事务

- 新增独立动作权限 `cashflow.opening.confirm`，并加入 CASHFLOW 功能授权目录；默认只分配给明确承担期初核对职责的财务角色，管理员是否持有也应由授权配置决定。`cashflow.manage` 不能自动取得核对确认权。控制器声明 `@RequirePermission`，服务层再核查 `CASHFLOW` 数据范围。现有账户范围模型不足时继续 fail-closed，仅允许 `ALL`；不得因请求重放跳过授权。
- 使用 `X-Request-Key` 和当前数据源的 `request_idempotency`；请求哈希包括期初 ID、凭据类型、日期、余额、引用和说明。不同内容复用同键返回冲突。
- 单事务内锁定期初行，校验 `active=1`、`finance_confirmed=0`、金额未变化，然后插入证据、条件更新期初状态、写审计与幂等结果。期初行锁和条件更新保证本轮只成功确认一次；证据表保留后续更正再确认的历史。任何审计或证据写入失败，期初状态与幂等记录均回滚。
- 并发同键应返回同一确认结果；不同键抢同一期初，一次成功、一次 409。异常后的重查只能在失败事务退出之后进行。
- 审计至少记录期初 ID、账户、年度、金额、证据记录 ID、确认人和确认时间；日志仅作追踪索引，证据表是核对事实来源。

## 5. 拟议接口与验收

`POST /api/cashflow/accounts/opening/{id}/finance-confirm`，携带 `X-Request-Key`；请求体包含 `evidenceType`、`balanceAsOfDate`、`verifiedBalance`、`evidenceRef`、`note`。响应返回原期初 ID、两种状态、确认人/时间和证据 ID。可在现有期初 GET 响应中补充证据摘要，但需遵守 `cashflow.read` 与数据范围。

实施前须完成 B-3.1 正式收口，再评审证据类型、历史期初的可取得凭据和权限分配。实施验收使用隔离库，覆盖缺失/候选/已确认、金额不符、停用账户、同键与不同键并发、权限与范围重放、审计失败回滚；2026 正式期初和历史流水不得作为写入测试对象。确认动作本身不得改变报表金额或 `active`。

## 6. 环境检查补充

上轮误写的根因是幂等服务曾固定使用 `product_archive.request_idempotency`，而隔离实例的业务表使用另一个 schema。今后所有隔离写入测试均须先核对 `SELECT DATABASE()`、`JdbcTemplate`、JPA 事务管理器、审计与幂等表的目标 schema，并在测试前后只读检查正式库测试键数量；任一对象跨 schema 即停止测试。
