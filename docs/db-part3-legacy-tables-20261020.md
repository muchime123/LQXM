# Part 3｜遗留表 / 空表 / 空洞表 治理清单（2026-10-20）

> **口径**：行数全部来自逐张 `SELECT COUNT(*)` 精确计数；体积来自 `INFORMATION_SCHEMA.INNODB_TABLESPACES.FILE_SIZE`
> （`TABLES.DATA_LENGTH` 删行后不刷新，本机实测曾差 3,000 MB，不可信）。
> **本节不含任何 DROP** —— 决策权在 LQ。每条都给了「删了会怎样」的证据。
> 探针：`tools/_probe_legacy_tables.java`（全量盘点）、`tools/_probe_residual.java`（残留/空洞）。

---

## 零、先纠正一个既有错误结论

原报告与脚本 Part 3 把 `receipts`(156 MB)、`receipt_allocations`(80 MB) 归在「83 张空表」里，
并暗示「空表可考虑清理」。**这是错的，且方向相反**：

| 表 | 精确行数 | 物理体积 | 真实性质 |
|---|---|---|---|
| `receipts` | **0** | **156 MB** | 结算台账核心事实表（`SettlementLedgerService` / `H3FactImportService` / `H3ResetService` 等 14+ 处 SQL），**只是还没重导数据** |
| `receipt_allocations` | **0** | **80 MB** | 核销分配事实表（`SettlementLedgerService` 的 `FACT_RECEIPT_PREDICATE` 就在它上面） |
| `h3_reset_identity_snapshots` | **0** | **25 MB** | `H3ResetService` 整库重置用的快照表 |
| `h3_production_reports` | **0** | **21 MB** | 氚云生产报工导入目标表 |

⇒ 这四张**不是废表，是「已删除历史行、等待重新导入」的活表**。它们占的空间是**空洞（DATA_FREE）**，
正确处置是 **`ALTER TABLE ... ROW_FORMAT=DYNAMIC, ALGORITHM=COPY` 重建回收**，而不是 DROP。

**全库空洞账**：`receipts` 156 + `receipt_allocations` 80 + `h3_reset_identity_snapshots` 25 +
`h3_production_reports` 21 + `h3_invoice_requests` 18 ≈ **300 MB 可在不动任何数据的前提下回收**。

---

## 一、遗留表（32 张 / 精确 213 MB）

判定依据：命名含 `bak_` / `_archive_` / `_before_` / `_legacy_`，且建表时间集中在 09-04 ~ 10-08 的几次大重构。

### 1.1 判定结果总表

| # | 表名 | 精确行数 | 真实 MB | 代码引用 | 结论 |
|---|---|---|---|---|---|
| 1 | `business_commission_snapshots_archive_20260904` | 666,630 | 112.0 | V262 迁移（一次性归档） | **可删**（占全批 53%） |
| 2 | `business_profit_snapshots_archive_20260904` | 67,677 | 18.0 | V262 迁移 | **可删** |
| 3 | `sales_profit_calculations_archive_20260907` | 27,456 | 13.0 | V268 迁移 | **可删** |
| 4 | `bak_20260921_sales_products` | 4,920 | 9.0 | 无 | **可删**（见 1.3 风险①） |
| 5 | `bak_sh_h3_cashflow_entries` | 7,588 | 9.0 | 无 | **可删** |
| 6 | `bak_sh_h3_cashier_log` | 6,088 | 9.0 | 无 | **可删** |
| 7 | `sales_product_cost_snapshots_archive_20260907` | 3,983 | 9.0 | V268 迁移 | **可删** |
| 8 | `customer_contacts_before_v282` | 0 | 9.0 | V282 迁移 | **可删**（纯空洞） |
| 9 | `customer_salesperson_relations_before_v282` | 0 | 0.89 | V282 迁移 | **可删** |
| 10 | `bak_sh_h3_sales_invoices` | 4,174 | 0.55 | 无 | **可删** |
| 11 | `bak_20260921_product_inventory_transactions` | 1,691 | 0.50 | 无 | **可删** |
| 12 | `bak_sh_h3_input_invoices` | 2,382 | 0.36 | 无 | **可删** |
| 13 | `bak_20260921_product_inventory_balances` | 1,690 | 0.25 | 无 | **可删** |
| 14 | `bak_sh_h3_supplier_payables` | 696 | 0.19 | 无 | **可删** |
| 15 | `receipt_match_candidates_archive_20260916` | 0 | 0.17 | V299 迁移 | **可删** |
| 16 | `production_tracking_entries_legacy_20261008` | 0 | 0.14 | V366 / V367.pending | **可删** |
| 17 | `production_tracking_state_legacy_20261008` | 0 | 0.14 | V366 / V367.pending | **可删** |
| 18 | `manufacturing_order_follow_ups_archive_20261007` | 0 | 0.13 | V359 / V367.pending | **可删** |
| 19 | `production_followup_logs_archive_20261007` | 0 | 0.13 | V359（子表） | **可删，但须后删**（见 1.2） |
| 20 | `production_followup_records_archive_20261007` | 0 | 0.13 | V359（父表） | **可删，须先删子表** |
| 21 | `bak_mo_20260921` | 1 | 0.11 | 无 | **可删** |
| 22 | `bak_moo_20260921` | 3 | 0.11 | 无 | **可删** |
| 23 | `bak_sh_h3_cash_accounts` | 35 | 0.11 | 无 | **可删** |
| 24 | `business_commission_settlement_items_archive_20260904` | 10 | 0.11 | V262 迁移 | **可删** |
| 25 | `business_commission_settlements_archive_20260904` | 2 | 0.11 | V262 迁移 | **可删** |
| 26 | `business_profit_adjustments_archive_20260904` | 0 | 0.11 | V262 迁移 | **可删** |
| 27 | `manufacturing_followup_operation_snapshot_archive_20261007` | 0 | 0.11 | V359 迁移 | **可删** |
| 28 | `manufacturing_followup_order_snapshot_archive_20261007` | 0 | 0.11 | V359 迁移 | **可删** |
| 29 | `sales_commission_calculations_archive_20260907` | 29 | 0.11 | V268 迁移 | **可删** |
| 30 | `sales_commission_rules_archive_20260907` | 1 | 0.11 | V268 迁移 | **可删** |
| 31 | `sales_commission_settlements_archive_20260907` | 1 | 0.11 | V268 迁移 | **可删** |
| 32 | `production_tracking_workbook_archive_rules` | 0 | 0.13 | **`ProductionTrackingService` 活代码** | ⛔ **禁止删** |
| — | `product_models_archive` | — | 9.0 | **`ArchiveScheduler` 每晚 02:00 写入** | ⛔ **禁止删** |

> `product_models_archive` 不在 32 张里（名字是 `_archive` 结尾不带日期），但同样属于「名字像垃圾表、实际是活的」，
> 必须单独列出来，否则下次有人按 `%_archive` 批量匹配会误杀。

### 1.2 两张必须点名的「假遗留表」

**① `production_tracking_workbook_archive_rules`** —— 名字里含 `archive`，实为**在用的规则表**：
```
ProductionTrackingService:558  SELECT ... FROM production_tracking_workbook_archive_rules WHERE revoked_at IS NULL
ProductionTrackingService:627  SELECT product_key,product_label FROM ... WHERE id=? AND revoked_at IS NULL
ProductionTrackingService:630  UPDATE ... SET revoked_at=NOW() ...
ProductionTrackingService:743  SELECT COUNT(*) FROM ... WHERE product_key=? AND revoked_at IS NULL
ProductionTrackingService:746  INSERT INTO production_tracking_workbook_archive_rules(...)
ProductionTrackingService:644  audit.write(... "product_archive.production_tracking_workbook_archive_rules" ...)
```
删了 = 跟单「归档规则」功能整体失效 + 撤销/新增都报错。**当前 0 行是因为规则还没人配，不是因为废弃。**

**② `product_models_archive`** —— `ArchiveScheduler#archiveSoftDeletedProductRows` 每晚 02:00：
```
ArchiveScheduler:73  long modelsArchived = archiveTable("product_models", "product_models_archive", cutoff);
```
删了 = 第二天凌晨定时任务直接抛异常。**这是「归档目标表」，是被写入方，不是遗留物。**

### 1.3 三个必须接受的风险

**风险① `bak_20260921_sales_products`（4,920 行 / 9 MB）是唯一一份 09-21 之前的销售产品快照。**
删除后无法回答「9 月 21 日改价格/改型号之前是什么」。若 LQ 认为这类追溯已无价值 → 可删；
若有历史报价纠纷可能翻账 → 建议保留到年底。

**风险② 全部 32 张删完后，`flyway_schema_history` 里 V262/V268/V282/V299/V359/V366 这些迁移记录仍在。**
若将来对这 401 条迁移做 `flyway clean` + 全量重放，这些表会被**重新建出来**（迁移脚本里有 `CREATE TABLE ... SELECT`）。
⇒ 删表是「一次性清场」，不是「永久根治」。若要根治，得再写一条 `V394__drop_archive_tables.sql` 并在迁移里显式 DROP —— 但那会让重放变慢。
**建议：只手工 DROP，不写迁移**（迁移是历史，不该被改写）。

**风险③ 外键顺序**：`production_followup_logs_archive_20261007` 有一条外键
`fk_pfl_record → production_followup_records_archive_20261007`。
虽然两张都是 0 行且都要删，但**必须先删子表再删父表**，否则报 `ERROR 1217`。
其余 30 张无外键牵连（`KEY_COLUMN_USAGE` 已确认）。

---

## 二、探针残留表（3 张 / 48.1 MB，我自己留下的，可直接删）

`H3SnapshotCleanupService#buildKeepTables()` 建的是**持久表**而非会话临时表（原因见代码注释），
靠 `finally` 里的 `dropKeepTables()` 兜底。进程被 kill 时会留残留。实测留下：

| 表名 | 真实 MB | 建表时间 | 来源 | 处置 |
|---|---|---|---|---|
| `_t_latest` | 16.0 | 14:11:28 | 早期探针 `_probe_snapshot_prune` 的物化表 | **可删** |
| `_h3sc_prune_20261020_ids` | 32.0 | 14:30:38 | 快照治理的审计 id 清单（192,133 行，已导出 CSV） | **可删**（CSV 已留档） |
| `_t_keepbatch` | 0.11 | 14:11:43 | 早期探针的物化表 | **可删** |

> `h3sc_latest`（16 MB）当前**不在库里**——说明那次 `pruneAll` 走到了 finally 正常删除。
> 但 `_t_latest` 的存在证明：**异常路径确实会留残留**。代码已加注释说明该取舍，不再改成 TEMPORARY（跨连接删不掉更危险）。

`_h3sc_prune_20261020_ids` 的 32 MB 里，数据只占 0.03 MB，其余全是**空洞**——192,133 行删完后
`DATA_LENGTH` 显示 0.03 MB 但 `FILE_SIZE` 还是 32 MB，与 `h3_import_rows` 完全同一个现象。

---

## 三、空表（81 张 / 约 250 MB）—— 只列分类，不做处置建议

`TABLE_ROWS = 0` 的共 88 张，逐张 `COUNT(*)` 复核后：

- **真空表 81 张**（精确 0 行）
- **估算偏差 7 张**（`TABLE_ROWS=0` 但实际有行）：`bak_mo_20260921`(1)、`bak_moo_20260921`(3)、
  `sales_commission_rules_archive_20260907`(1)、`sales_commission_settlements_archive_20260907`(1)、
  `knowledge_categories`、`office_asset_records`、`production_tracking_duration_groups`、
  `production_tracking_product_benchmarks`

**⚠️ 78 张必须保留，只有 3 张 + 已在第一节列出的遗留表可删。**

按性质分四类（这四类都**不能删**）：

| 类别 | 代表表 | 为什么不能删 |
|---|---|---|
| **核心事实表（待重导）** | `receipts`(156MB)、`receipt_allocations`(80MB)、`h3_production_reports`(21MB)、`h3_invoice_requests`(18MB)、`h3_cashflow_entries`(15MB)、`h3_cashier_log`(13MB)、`h3_sales_invoices`(11MB)、`h3_input_invoices`(10MB) | 代码里有 INSERT/SELECT，只是数据被清空等重导 |
| **本地制造域** | `manufacturing_orders`、`finished_goods_lots`、`material_items` 等 8 张 | LQ 明确「等新生产管理结构稳定后再议」 |
| **客户治理** | `customer_split_runs`(11MB)、`customer_merge_history`、`customer_merge_plan_undo` 等 | 拆分/合并的审计与撤销链，删了无法回滚 |
| **产品/包装关联** | `product_model_packaging_links`、`product_variant_packaging_links`、`packaging_mold_histories` 等 | JPA 实体映射，删了启动即报 `Table not found` |

**结论：81 张空表里，一张都不建议 DROP。** 它们的价值在「空」而不是「占地方」，
真正的问题是其中 12 张还占着 300+ MB 空洞 —— 那是第四节的活。

---

## 四、空洞表（不动数据、只回收空间）

判据：精确 0 行（或极少行）但 `FILE_SIZE` 远大于有效数据。**用 `ALGORITHM=COPY` 重建，不删表、不动数据、不改结构。**

| 表名 | 精确行数 | 当前 MB | 说明 |
|---|---|---|---|
| `receipts` | 0 | **156.0** | 收益最大，无数据可丢 |
| `receipt_allocations` | 0 | **80.0** | 同上 |
| `_h3sc_prune_20261020_ids` | 0 | 32.0 | 直接 DROP 即可，不必 COPY |
| `h3_reset_identity_snapshots` | 0 | 25.0 | 重置快照，可清空重建 |
| `h3_production_reports` | 0 | 21.0 | 待重导 |
| `h3_invoice_requests` | 0 | 18.0 | 待重导 |
| `_t_latest` | ? | 16.0 | 直接 DROP |
| `h3_cashflow_entries` | 0 | 15.0 | 待重导 |
| `h3_cashier_log` | 0 | 13.0 | 待重导 |
| `h3_sales_invoices` | 0 | 11.0 | 待重导 |
| `customer_split_runs` | 0 | 11.0 | 客户拆分运行记录（0 行，空洞） |

**合计可回收 ≈ 340 MB**，且**零业务风险**（全部是已删除行的物理残留）。

---

## 五、另一个被漏掉的发现：`V367` 迁移躺在仓库里没执行

`backend/src/main/resources/db/migration/` 下有且只有一个未执行迁移：

```
V367__drop_manufacturing_execution_tables.sql.pending   （2,351 B）
```

它要删「本地制造执行链」全部 15 张表（排产/报工/检验/返工/完工入库/领料退料/成品批次）。
原报告把这块归为「LQ 明确等新结构稳定再议」—— 但实测发现情况已经变了：

| 事实 | 数据 |
|---|---|
| V367 列的 15 张表，实际还在的 | **只有 5 张**（其余 10 张早已被其它迁移删掉，V367 里是幂等的 `IF EXISTS`） |
| 这 5 张的精确行数 | **全部 0 行** |
| 这 5 张的物理体积合计 | **1.23 MB** |
| 这 5 张在 `backend/src/main/java` 的引用数 | **0**（`grep` 无任何命中） |

在库的 5 张：`manufacturing_orders`(0.39MB) / `finished_goods_lots`(0.28MB) /
`manufacturing_order_outputs`(0.20MB) / `manufacturing_completion_receipt_items`(0.20MB) /
`finished_goods_movements`(0.16MB)

**它们已经不占空间，删了也只省 1.23 MB**，所以从瘦身角度毫无价值。
但 `V367.pending` 长期滞留本身是个隐患：它是全仓唯一的 pending 迁移，说明
「生产执行链下线」这件事**代码删了、库没删、迁移没跑**，三处状态不一致。
同一文件第 17-19 行还要 `DROP COLUMN linked_manufacturing_order_id / _no`
（实测两列都还在 `production_orders` 上，是历史回链的死列）。

⇒ **建议：这件事不走 Part 3，单独作为一个「迁移收口」议题交 LQ**，
要么执行 V367（含死列清理），要么把它删掉重新编号。
**不建议在本轮顺手执行** —— 它会 `DROP COLUMN`，属于结构变更，超出「瘦身」范围。

另有 2 条外键仍指着 `finished_goods_lots`（V367 第 13-14 行就是解它们的）：
`product_inventory_transactions.fk_pit_lot`、`sales_outbound_finished_goods_allocations.fk_outbound_lot_allocation_lot`。
不先解这两条，V367 第 35 行 `DROP TABLE finished_goods_lots` 必然失败。

---

## 六、可执行语句（按风险从低到高，逐段执行）

> ⚠️ 每段执行前先跑「核对」语句，把数字和本文件对照；不一致就停手。

### 第 1 段｜删探针残留（零风险，48 MB）

```sql
-- 核对：三张都应该是探针残留，看建表时间是否都是今天
SELECT TABLE_NAME, CREATE_TIME FROM information_schema.TABLES
 WHERE TABLE_SCHEMA='product_archive'
   AND TABLE_NAME IN ('_t_latest','_t_keepbatch','h3sc_latest','_h3sc_prune_20261020_ids');

-- CSV 已留档于 _archive_export/20261020/snapshot_prune_ids_20261020-143037.csv（192,133 行）
DROP TABLE IF EXISTS `_t_latest`;
DROP TABLE IF EXISTS `_t_keepbatch`;
DROP TABLE IF EXISTS `h3sc_latest`;
DROP TABLE IF EXISTS `_h3sc_prune_20261020_ids`;
```

### 第 2 段｜回收空洞（零风险，不动数据，≈340 MB）

```sql
-- 核对：确认这几张表确实是 0 行（若不是 0 行，ALGORITHM=COPY 依然安全，只是耗时更久）
SELECT TABLE_NAME, TABLE_ROWS FROM information_schema.TABLES
 WHERE TABLE_SCHEMA='product_archive'
   AND TABLE_NAME IN ('receipts','receipt_allocations','h3_reset_identity_snapshots',
                      'h3_production_reports','h3_invoice_requests','h3_cashflow_entries',
                      'h3_cashier_log','h3_sales_invoices','customer_split_runs');

-- ⚠️ OPTIMIZE TABLE 对 InnoDB 常是空转（本机实测 0.3s 无变化），必须用 COPY 重建
-- ⚠️ 逐条执行，不要一串跑；每张 0 行表通常 1~5 秒
ALTER TABLE receipts                   ROW_FORMAT=DYNAMIC, ALGORITHM=COPY;
ALTER TABLE receipt_allocations        ROW_FORMAT=DYNAMIC, ALGORITHM=COPY;
ALTER TABLE h3_reset_identity_snapshots ROW_FORMAT=DYNAMIC, ALGORITHM=COPY;
ALTER TABLE h3_production_reports      ROW_FORMAT=DYNAMIC, ALGORITHM=COPY;
ALTER TABLE h3_invoice_requests        ROW_FORMAT=DYNAMIC, ALGORITHM=COPY;
ALTER TABLE h3_cashflow_entries        ROW_FORMAT=DYNAMIC, ALGORITHM=COPY;
ALTER TABLE h3_cashier_log             ROW_FORMAT=DYNAMIC, ALGORITHM=COPY;
ALTER TABLE h3_sales_invoices          ROW_FORMAT=DYNAMIC, ALGORITHM=COPY;
ALTER TABLE customer_split_runs        ROW_FORMAT=DYNAMIC, ALGORITHM=COPY;

-- 核对：总体积应明显下降
SELECT ROUND(SUM(FILE_SIZE)/1048576,1) AS 全库MB
  FROM INFORMATION_SCHEMA.INNODB_TABLESPACES WHERE NAME LIKE 'product_archive/%';
```

### 第 3 段｜删除 31 张遗留表（≈213 MB，有风险，需 LQ 逐张确认）

```sql
-- ⚠️ 先删子表再删父表（唯一一条外键）
DROP TABLE IF EXISTS production_followup_logs_archive_20261007;
DROP TABLE IF EXISTS production_followup_records_archive_20261007;

-- 其余 29 张（无外键牵连）
DROP TABLE IF EXISTS business_commission_snapshots_archive_20260904;
DROP TABLE IF EXISTS business_profit_snapshots_archive_20260904;
DROP TABLE IF EXISTS business_profit_adjustments_archive_20260904;
DROP TABLE IF EXISTS business_commission_settlements_archive_20260904;
DROP TABLE IF EXISTS business_commission_settlement_items_archive_20260904;
DROP TABLE IF EXISTS sales_profit_calculations_archive_20260907;
DROP TABLE IF EXISTS sales_commission_calculations_archive_20260907;
DROP TABLE IF EXISTS sales_commission_rules_archive_20260907;
DROP TABLE IF EXISTS sales_commission_settlements_archive_20260907;
DROP TABLE IF EXISTS sales_product_cost_snapshots_archive_20260907;
DROP TABLE IF EXISTS customer_contacts_before_v282;
DROP TABLE IF EXISTS customer_salesperson_relations_before_v282;
DROP TABLE IF EXISTS manufacturing_followup_order_snapshot_archive_20261007;
DROP TABLE IF EXISTS manufacturing_followup_operation_snapshot_archive_20261007;
DROP TABLE IF EXISTS manufacturing_order_follow_ups_archive_20261007;
DROP TABLE IF EXISTS production_tracking_entries_legacy_20261008;
DROP TABLE IF EXISTS production_tracking_state_legacy_20261008;
DROP TABLE IF EXISTS receipt_match_candidates_archive_20260916;
DROP TABLE IF EXISTS bak_20260921_sales_products;
DROP TABLE IF EXISTS bak_20260921_product_inventory_balances;
DROP TABLE IF EXISTS bak_20260921_product_inventory_transactions;
DROP TABLE IF EXISTS bak_mo_20260921;
DROP TABLE IF EXISTS bak_moo_20260921;
DROP TABLE IF EXISTS bak_sh_h3_cash_accounts;
DROP TABLE IF EXISTS bak_sh_h3_cashflow_entries;
DROP TABLE IF EXISTS bak_sh_h3_cashier_log;
DROP TABLE IF EXISTS bak_sh_h3_input_invoices;
DROP TABLE IF EXISTS bak_sh_h3_sales_invoices;
DROP TABLE IF EXISTS bak_sh_h3_supplier_payables;

-- ⛔ 这两张绝不在上面任何一条里，再确认一次它们仍在：
--    production_tracking_workbook_archive_rules   （ProductionTrackingService 活代码）
--    product_models_archive                       （ArchiveScheduler 每晚写入）
SELECT TABLE_NAME FROM information_schema.TABLES
 WHERE TABLE_SCHEMA='product_archive'
   AND TABLE_NAME IN ('production_tracking_workbook_archive_rules','product_models_archive');
```

### 第 4 段｜删后核对

```sql
SELECT COUNT(*) AS 表数, ROUND(SUM(FILE_SIZE)/1048576,1) AS 全库MB
  FROM INFORMATION_SCHEMA.INNODB_TABLESPACES WHERE NAME LIKE 'product_archive/%';

-- 活表必须仍在且可用
SELECT COUNT(*) FROM receipts;
SELECT COUNT(*) FROM settlement_outbound_links;
SELECT COUNT(*) FROM sales_outbounds;
SELECT COUNT(*) FROM production_orders;
```

---

## 七、收益汇总

| 段 | 动作 | 回收 | 风险 | 状态 |
|---|---|---|---|---|
| 1 | 删 4 张探针残留 | 64 MB | 无 | ✅ **已执行 2026-10-20** |
| 2 | 9 张空洞表 COPY 重建 | 348 MB | 无（不动数据） | ✅ **已执行 2026-10-20** |
| 3 | 删 31 张遗留表 | ≈213 MB | **低但不可逆** | ⏸ 待 LQ 确认 |
| — | 81 张空表 | 0 | — | ❌ 不建议删 |

**已落账：全库 3,145 MB → 2,733 MB（-412 MB）**，执行脚本 `tools/_exec_part3_lowrisk.java`（`--exec` 才真跑）。
第 3 段若批准，最终约 2,520 MB。

### 第 1+2 段执行实录（2026-10-20 15:0x）

```
第 1 段：DROP _t_latest / _t_keepbatch / h3sc_latest / _h3sc_prune_20261020_ids  → 64 MB
         ⚠️ _t_latest 实际有 126,808 行（不是空表，是探针物化中间结果），_t_keepbatch 6 行
第 2 段：ALTER ... ROW_FORMAT=DYNAMIC, ALGORITHM=COPY  ×9                     → 348 MB
         receipts 156→0.1MB / receipt_allocations 80→0.1MB / 其余 7 张共 91MB→1MB
         每张耗时 0.1s（0 行表重建极快）

核对（全部通过）：
  9 张表行数 0 → 0 全部一致（重建不改数据）
  受保护活表：sales_outbounds 109,379 / production_orders 6,030 / customers 48,871 均未变
  32 张遗留表一根未动
  下划线残留表：0
```
