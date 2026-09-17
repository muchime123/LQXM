-- 按业务确认清空氚云身份解析及所有既有映射结论，供全量重导后重新生成。
-- 保留 customers / employees / sales_products / warehouses 主档及业务事实中的既有外键；
-- 本迁移只删除身份解析、别名映射、客户身份簇治理记录和身份保护快照。

-- 已创建但尚未执行的重置令牌依赖旧身份快照，必须作废，避免误用。
UPDATE h3_reset_runs
   SET status = 'INVALIDATED'
 WHERE status = 'PREPARED';

DELETE FROM h3_reset_identity_snapshots;

-- 客户身份簇和拆分工作台的决策记录。
DELETE FROM customer_source_cluster_mappings;
DELETE FROM customer_split_run_items;
DELETE FROM customer_split_runs;

-- 统一身份解析工作台的全部四类来源身份及人工/自动/忽略结论。
DELETE FROM source_identity_aliases;

-- 清理会在下次同步时重新灌入统一身份表的旧产品、员工映射来源。
DELETE FROM sales_product_source_aliases
 WHERE source_system = 'H3YUN';

DELETE FROM employee_external_mappings
 WHERE source_system = 'H3YUN';

-- 已下线的旧业务员身份候选表仍可能留有历史确认依据，一并清空。
DELETE FROM salesperson_identity_candidates
 WHERE source_system = 'H3YUN';
