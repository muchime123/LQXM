# 资金日记账 B-3.1 隔离验收与正式库测试键处理记录

日期：2026-10-26。B-3.1 仍未收口；本文件不授权正式库删除。

## 正式库误写入记录的只读核对

表：`product_archive.request_idempotency`。三行均为 `action=cashflow.opening.manual`、`created_by=23`、`created_at=2026-10-26 13:41:21`。

| id | 精确 request_key | payload_hash | result_ref |
| ---: | --- | --- | ---: |
| 2210 | `b31-first-72b3c8f1-00ef-44f4-b51c-1696eedabce5` | `66ef3b47d5d9ffbd48b79b3d85a3cfc701186a5464eeed0e6a23f02e8fb7bded` | 1 |
| 2211 | `b31-same-624e7e39-4fc1-4c78-8064-db77cb9743bc` | `90b06472e36eb9975ce3d93d317059dcee637203994f8e190eb63d660d16822e` | 4 |
| 2212 | `b31-824bba56-5943-4b5b-a238-0cb1e3d61b39` | `f582bd163923f736daf5f2d897906d8211f05dca0ec25efa64e4fb1e640f9ee2` | 6 |

`result_ref` 是隔离库自增 ID：隔离库 1/4/6 分别为测试账户 900000001 的 2027/2028/2029 年期初。正式库恰好也有同号 ID，但分别是账户 687/690/692 的 **2026 年 H3_OPENING**；不能对这些正式期初执行任何清理。正式库没有测试账户、没有 2027 年及以后期初。

完整行及当时的 `SHOW CREATE TABLE` 已只读备份到 `backups/cashflow-b31/formal_idempotency_3rows_20261026.json`（Git 忽略，限定本机用户、SYSTEM 与管理员访问，不受 `mvn clean` 影响），SHA-256：`fefe2e57d86cb9a50fc061088aafe73204ac97105ec6ea1c30086e0226b12710`。原 `backend/target/` 副本在哈希校验后已移除。恢复时可依据该文件逐列插回原表；先核对主键和请求键均不存在，再在受控事务中执行，且不得覆盖其他记录。

### 待批准的精确清理方案（未执行）

1. 项目负责人明确批准上述三行正式库删除，执行环境审批通过；再次校验备份哈希及完整三行内容。
2. 在同一事务内以 `id + request_key + action + payload_hash + result_ref + created_by + created_at` 全字段匹配 `SELECT ... FOR UPDATE`；必须恰好返回 3 行，否则回滚并停止。
3. 仅按这 3 个 `id` 及对应精确 `request_key` 删除，必须恰好影响 3 行；否则回滚。提交后只读检查这 3 键不存在，其他幂等记录数量不变。
4. 保留上述备份和执行日志。不得用 `LIKE 'b31-%'` 作为删除条件，不得触碰 `cashflow_account_opening`、2026 年期初或 ERP schema。

实际执行时用参数化 SQL，在同一连接、同一事务中锁定并校验下列三组完整字段后，按 `id` 与精确 `request_key` 删除。锁定结果须与备份中的 `action`、`payload_hash`、`result_ref`、`created_by`、`created_at` 逐字段相等；删除前后分别核对总行数与这三键的存在性。任何差异均回滚。以下只是待批准方案，**不得直接执行**：

```sql
START TRANSACTION;
SELECT * FROM product_archive.request_idempotency
 WHERE (id=2210 AND BINARY request_key=BINARY 'b31-first-72b3c8f1-00ef-44f4-b51c-1696eedabce5')
    OR (id=2211 AND BINARY request_key=BINARY 'b31-same-624e7e39-4fc1-4c78-8064-db77cb9743bc')
    OR (id=2212 AND BINARY request_key=BINARY 'b31-824bba56-5943-4b5b-a238-0cb1e3d61b39')
 FOR UPDATE;
-- 应用层逐字段与备份比较；不等于恰好三行或任一字段不符则 ROLLBACK。
-- 仅在明确批准、执行环境审批通过且上一步通过后，使用相同的精确谓词 DELETE。
-- 必须 ROW_COUNT()=3，否则 ROLLBACK；成功才 COMMIT。
```

自动审批此前拒绝了正式库删除；截至本记录，删除 **未执行**。

## 隔离环境与结果

- 隔离库：`product_archive_b31_isolated`，测试账户 ID 900000001；后端使用 `SPRING_DATASOURCE_URL` 指向此库，`SPRING_FLYWAY_ENABLED=false`，端口 18081。
- 启动故障：系统 `java` shim 卡住、沙箱临时目录不可写、旧隔离进程占用 18081。改用 `JAVA_HOME/bin/java.exe`、工作区临时目录，核实并停止旧进程后，新代码于 14:13:45 启动。
- 代码绑定：`PrimaryDataSourceConfig` 的 `dataSource`、`JdbcTemplate` 和 `JpaTransactionManager` 为同一主数据源；`IdempotencyConfig` 改用未限定 schema 的 `request_idempotency`；审计 `AuditLogService` 通过同一 JPA 事务管理器写入。隔离库真实写入与审计故障回滚验证了此绑定。
- HTTP：首次/重放 200/200；不同键重复创建 409；同键并发 200/200；不同键并发 200/409。精度超限、金额溢出、null/空金额、无效年度、缺依据、无效账户、停用账户为 400；无权限及数据范围受限的重放为 403。负数 `-12.3456` 成功写入，无静默舍入。
- 故意使隔离审计表不可写时 HTTP 500；该失败请求的隔离期初和幂等记录均为 0，说明双回滚。这个 500 属于故障注入；普通重复创建为 409。
- 隔离库 `uk_opening(account_id,fiscal_year)` 存在，直接重复插入得到 MySQL 1062，探针事务已回滚。验收后隔离库 2027/2028/2029/2033/2034/2035 各一条期初，3 条本轮幂等键，6 条期初审计记录。
- 正式库测试前后均为 3 条旧误写入键、0 条 2027 年及以后期初、0 个测试账户；本轮没有新增正式库测试键。
- P1 只读回归：34/34 通过。8081 原运行实例未部署本轮新代码；18081 隔离实例使用本轮修复后的 `target/classes`。

待完成：正式库三条测试键的授权清理与复核；在此之前不将 B-3.1 标记完成，也不进入 B-3.2。
