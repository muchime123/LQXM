-- ============================================================================
-- V301：氚云台账补「导入与系统信息」系统列（产品信息 / 仓库明细 / 经销商）
--
-- 背景：氚云全字段导出在业务列之后携带一整套系统列（数据ID(不可修改) / 数据标题 /
--       创建人(必填) / 拥有者(必填) / 所属部门(必填) / 创建时间(必填) / 修改时间(必填) /
--       数据状态）。此前只有经销商台账（V294）落了这些列，产品信息与仓库明细台账只落了
--       业务列 + source_hash，导致同一批氚云数据在详情里有的能溯源、有的不能。
--
-- 实测列布局（2026-09 导出样本，仍以第 2 行中文表头名称匹配为准，勿写死列位）：
--   产品信息 25 列：… 7 采购价格 | 8 产品明细.数据ID(不可修改) | 10 产品明细.数据标题 |
--                   14 数据ID(不可修改) | 15 数据标题 | 16 修改时间(必填) | 17 所属部门(必填) |
--                   19 拥有者(必填) | 20 创建人(必填) | 21 创建时间(必填) | 24 数据状态
--                   （主表系统列只在主记录首行有值 → 解析时向下填充）
--   仓库明细 19 列：… 7 采购价格 | 8 数据ID(不可修改) | 9 数据标题 | 10 创建人 |
--                   11 拥有者(必填) | 12 所属部门(必填) | 13 创建时间 | 14 修改时间 | 18 数据状态
--
-- 语义：source_* 为氚云侧观测值（只读回显，不参与业务比对，避免主记录改一次把其下
--       几十行产品全判成 UPDATE）；last_synced_at 为本行最近一次从氚云同步的时间。
-- ============================================================================

ALTER TABLE h3_product_catalog
  ADD COLUMN source_object_id VARCHAR(64) NULL COMMENT '氚云产品明细行数据ID（不可修改）' AFTER purchase_price,
  ADD COLUMN source_title VARCHAR(200) NULL COMMENT '氚云数据标题' AFTER source_object_id,
  ADD COLUMN owner_name VARCHAR(100) NULL COMMENT '氚云拥有者' AFTER source_title,
  ADD COLUMN department_name VARCHAR(200) NULL COMMENT '氚云所属部门' AFTER owner_name,
  ADD COLUMN created_by_name VARCHAR(100) NULL COMMENT '氚云创建人' AFTER department_name,
  ADD COLUMN data_status VARCHAR(50) NULL COMMENT '氚云数据状态' AFTER created_by_name,
  ADD COLUMN source_created_at DATETIME NULL COMMENT '氚云创建时间' AFTER data_status,
  ADD COLUMN source_modified_at DATETIME NULL COMMENT '氚云修改时间' AFTER source_created_at,
  ADD COLUMN last_synced_at DATETIME NULL COMMENT '最近一次从氚云同步到本行的时间' AFTER source_modified_at,
  ADD COLUMN updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最近一次更新' AFTER last_synced_at;

ALTER TABLE h3_warehouse_stock
  ADD COLUMN source_object_id VARCHAR(64) NULL COMMENT '氚云数据ID（不可修改）' AFTER purchase_price,
  ADD COLUMN source_title VARCHAR(200) NULL COMMENT '氚云数据标题' AFTER source_object_id,
  ADD COLUMN owner_name VARCHAR(100) NULL COMMENT '氚云拥有者' AFTER source_title,
  ADD COLUMN department_name VARCHAR(200) NULL COMMENT '氚云所属部门' AFTER owner_name,
  ADD COLUMN created_by_name VARCHAR(100) NULL COMMENT '氚云创建人' AFTER department_name,
  ADD COLUMN data_status VARCHAR(50) NULL COMMENT '氚云数据状态' AFTER created_by_name,
  ADD COLUMN source_created_at DATETIME NULL COMMENT '氚云创建时间' AFTER data_status,
  ADD COLUMN source_modified_at DATETIME NULL COMMENT '氚云修改时间' AFTER source_created_at,
  ADD COLUMN last_synced_at DATETIME NULL COMMENT '最近一次从氚云同步到本行的时间' AFTER source_modified_at,
  ADD COLUMN updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最近一次更新' AFTER last_synced_at;

-- 经销商台账（V294）已落全部氚云系统列，仅补「最近同步时间」与产品/仓库台账对齐
ALTER TABLE h3_dealer_catalog
  ADD COLUMN last_synced_at DATETIME NULL COMMENT '最近一次从氚云同步到本行的时间' AFTER source_hash;
