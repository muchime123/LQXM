# 钉钉审批接入与并行实施路线

## 当前完成度（2026-08-24）

- 已完成：统一业务事实、业务匹配、业务档案、员工主档与外部身份映射。
- 已完成：`expense_claims / expense_items` 钉钉费用只读事实层与同步审计。
- 已完成：`expense_allocations` 一笔费用拆分到多业务/部门/公司/对公付款。
- 已完成：费用管理、员工名册、钉钉同步诊断页面。
- 本次完成：BusinessProfit V2、利润调整、版本快照和利润核算页面。
- 待外部条件：钉钉企业内部应用权限、AppKey/AppSecret、审批 ProcessCode 和接口联调。

## 钉钉接入边界

钉钉只负责提供审批事实，不直接决定费用归属、业务利润或提成。链路固定为：

`钉钉审批实例 → expense_claims/items → 人工费用归集 → BusinessProfit → Commission`

AppSecret 仅允许存在于后端环境变量，不写数据库、不传给前端、不打印到日志。

## API 恢复后的最小验收顺序

1. 验证企业内部应用 access token。
2. 获取“日常报销”模板 ProcessCode，并确认应用具有审批实例读取权限。
3. 先同步 1 天、最多 10 条，保存原始响应摘要和同步运行记录。
4. 核对审批状态、提单人 userId、金额、发生日期、表单明细和附件字段。
5. 建立 DINGTALK userId → employee_id 映射，未命中的记录允许入库但进入待映射清单。
6. 扩大到 30 天增量同步，验证外部实例 ID + source_hash 幂等更新。
7. 对一条费用执行多业务分摊，确认利润快照随重算变化。

## 不依赖钉钉、可以继续推进

1. BusinessCommission V2：基于 `business_profit_snapshots.commissionable_profit`、employee_id 和回款比例。
2. 权限模型：self / department / all，以及 expense/profit/commission/integration 权限。
3. 将业务详情页的费用、利润占位替换为真实数据组件。
4. 老板看板增加业务费用、核算利润、预计提成指标。

## 当前阶段不做

- 不把钉钉报销做成普通员工一级菜单；普通入口保持“费用管理”。
- 不让费用事实表直接持有单一 business_order_id。
- 不用 salespersonName/customerName 作为新核算关系的核心外键。
- 不基于旧 SalesProfit 产品明细层直接生成最终提成。
