-- 恢复「客户合并留痕」两张表
--
-- 背景：V292 清理已下线的「客户档案整改计划」时，连同 customer_merge_plan_undo /
-- customer_merge_moved_rows 一并 DROP。但 SourceIdentityService.mergeByPlan()
-- （按外部整理方案执行单次合并，复用 mergeCustomerInto 的同一套迁移逻辑）仍在代码中保留，
-- 且是**当前唯一支持"指定主档手工合并"的公开入口**——自动批量入口
-- confirmCustomerAddressMerges() 的判定键是「名称主体 + 完整地址」，覆盖不到
-- 「名称不同、地址缺失」的碎片档案（如 铨威刘小姐[548271] / 北京-铨威 / 铨威3线）。
--
-- 该方法的留痕写入依赖这两张表，缺表时直接抛 1146，能力实际不可用。本次按需恢复，
-- 建表语句与 V286 保持一致。这两张表为纯记账表，不被任何业务查询读取，无副作用。
--
-- 注意：customer_merge_history / customer_source_cluster_mappings 未被 V292 删除，
-- 仍由 SourceIdentityService 正常使用。

CREATE TABLE IF NOT EXISTS customer_merge_plan_undo (
  id BIGINT NOT NULL AUTO_INCREMENT,
  batch_tag VARCHAR(40) NOT NULL,
  merge_group VARCHAR(20) NULL,
  customer_id BIGINT NOT NULL,
  customer_code VARCHAR(40) NULL,
  prev_name VARCHAR(200) NULL,
  prev_short_name VARCHAR(100) NULL,
  prev_address VARCHAR(500) NULL,
  prev_status VARCHAR(20) NULL,
  prev_remark TEXT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_merge_undo_batch_customer (batch_tag, customer_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='合并撤销快照（被并入档案与主客户改名前状态）';

CREATE TABLE IF NOT EXISTS customer_merge_moved_rows (
  id BIGINT NOT NULL AUTO_INCREMENT,
  batch_tag VARCHAR(40) NOT NULL,
  merge_group VARCHAR(20) NULL,
  from_customer_id BIGINT NOT NULL COMMENT '被合并掉的客户',
  to_customer_id BIGINT NOT NULL COMMENT '保留的主客户',
  table_name VARCHAR(64) NOT NULL,
  column_name VARCHAR(64) NOT NULL DEFAULT 'customer_id' COMMENT '被迁移的引用列（customer_id / target_id）',
  row_id BIGINT NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_merge_moved_batch (batch_tag, merge_group),
  KEY idx_merge_moved_from (from_customer_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='客户合并迁移行明细（按方案撤销合并的依据）';
