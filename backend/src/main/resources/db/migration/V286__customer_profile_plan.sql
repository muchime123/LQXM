-- 客户档案 · 地区补全与合并方案（外部整理结果写回档案的暂存、留痕与撤销依据）
--
-- 背景：客户列表「未标注地区」清单导出后由外部整理，产出「正式省市区县 + 同址重复档案合并建议」。
-- 该结果不能直接 UPDATE 档案：需要先按地址选择器口径（ChinaRegionDictionary）校验可识别性、
-- 与现有有效值比对冲突，并留下「原值 → 新值」快照以便整批撤销。
--
-- 三张表职责：
--   customer_region_fill_plan  —— 逐客户的地区补全计划（含原值快照，可撤销）
--   customer_merge_plan        —— 逐组的重复档案合并计划
--   customer_merge_moved_rows  —— 合并实际迁移的行明细（按方案撤销合并的唯一依据）

CREATE TABLE IF NOT EXISTS customer_region_fill_plan (
  id BIGINT NOT NULL AUTO_INCREMENT,
  batch_tag VARCHAR(40) NOT NULL COMMENT '方案批次（如 20260910）',
  customer_code VARCHAR(40) NOT NULL COMMENT '客户编号（方案侧主键）',
  customer_id BIGINT NULL COMMENT '导入时解析出的档案 id',
  customer_name VARCHAR(200) NULL,
  src_type VARCHAR(20) NULL COMMENT '方案地区类型：中国大陆/港澳台/海外/待核对/无法定位',
  src_confidence VARCHAR(20) NULL COMMENT '方案解析可信度：高/中/低/未解析/不适用',
  src_note VARCHAR(500) NULL COMMENT '方案解析说明',
  src_conflict VARCHAR(300) NULL COMMENT '方案自述的原地区冲突',
  merge_group VARCHAR(20) NULL COMMENT '方案建议合并组（可空）',
  plan_province VARCHAR(50) NULL COMMENT '方案给出的正式省份（原样）',
  plan_city VARCHAR(50) NULL,
  plan_district VARCHAR(50) NULL,
  old_province VARCHAR(50) NULL COMMENT '导入时的档案原值快照',
  old_city VARCHAR(50) NULL,
  old_district VARCHAR(50) NULL,
  old_tidy_status VARCHAR(20) NULL,
  final_province VARCHAR(50) NULL COMMENT '归一后可写入的最终值（预览阶段回填）',
  final_city VARCHAR(50) NULL,
  final_district VARCHAR(50) NULL,
  predicted_complete TINYINT(1) NULL COMMENT '预计三级齐全且均可被地址选择器识别',
  status VARCHAR(20) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/READY/SKIPPED/APPLIED/ROLLED_BACK',
  skip_reason VARCHAR(300) NULL,
  previewed_at DATETIME NULL,
  applied_at DATETIME NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_region_fill_plan_batch_code (batch_tag, customer_code),
  KEY idx_region_fill_plan_status (batch_tag, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='客户地区补全计划（外部整理结果 → 档案省市县写回）';

CREATE TABLE IF NOT EXISTS customer_merge_plan (
  id BIGINT NOT NULL AUTO_INCREMENT,
  batch_tag VARCHAR(40) NOT NULL,
  merge_group VARCHAR(20) NOT NULL,
  confidence VARCHAR(20) NULL COMMENT '合并可信度：高/中',
  primary_customer_code VARCHAR(40) NOT NULL COMMENT '方案建议保留的主客户编号',
  primary_customer_id BIGINT NULL,
  primary_customer_name VARCHAR(200) NULL,
  member_codes TEXT NULL COMMENT '成员客户编号（换行分隔，含主客户）',
  member_count INT NULL,
  suggestion VARCHAR(500) NULL COMMENT '方案处理建议',
  region_summary VARCHAR(300) NULL COMMENT '方案地区概览',
  address_summary VARCHAR(300) NULL COMMENT '方案地址概览',
  status VARCHAR(20) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/READY/SKIPPED/APPLIED/ROLLED_BACK',
  skip_reason VARCHAR(300) NULL,
  previewed_at DATETIME NULL,
  applied_at DATETIME NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_merge_plan_batch_group (batch_tag, merge_group),
  KEY idx_merge_plan_status (batch_tag, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='客户合并计划（同址重复档案合并）';

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
