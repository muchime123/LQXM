-- V311 (2026-09-17): 第 15 类「生产报工」（G3 取证：工序完工报工）
-- 数据源：氚云「报工」表单导出件（LQ 2026-09-17 提供），12 252 行 / 32 列实测（.workbuddy/tmp/probe-report-20260917.json）。
-- 粒度 = 生产工单 × 零部件制程（工序完工报工，非材料领料：
--   「零部件流水号」全表仅 10 值 = 10 个固定工序模板；生产任务单实测「是否使用零部件库存」全否、库存调用全 0，
--   氚云从未启用领料 → G3 只补「报工」一半，材料实耗/损耗仍缺（ERP 设计输入）。
-- 关联键 = work_order_no（生产工单编号 SCGD…）→ production_orders（实测 4 997/5 691 = 87.8% 工单有报工）。
-- 无业务单号 → source_no 统一存数据ID UUID 兜底（同开票申请 18 单先例）；行级历史键用 external_id。
-- ⚠️ 同名列 ×2（调拨「仓管员」同款串名）：「报工人」F0000005（主）/F0000038（辅助 22 人）→ 解析层改名「报工人(辅助)」；
--   「生产零部件」F0000039/F0000002 实测同值（distinct/nonnull/样例一致），只落 F0000039，F0000002 仅作导入告警对比。
-- 不落的空壳列（2026-09-17 实测，均无信息量）：销售订单编号（全空）、已生产数量（9 996 非空全 0）、
--   上传设计文件/图片（1 非空且值为 0）、零部件文本（= 生产零部件 F0000039 同值）。
-- 状态口径：数据状态全「已生效」（报工即时生效无审批流）→ data_status 单值；氚云无撤销原语（缺口 C7），
--   误报在氚云不可撤 → ERP 侧须设计报工冲销（设计输入，勿在导入层造红冲）。

CREATE TABLE IF NOT EXISTS h3_production_reports (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  external_source VARCHAR(32) NOT NULL DEFAULT 'H3YUN',
  external_id VARCHAR(64) NOT NULL COMMENT '数据ID UUID（幂等键）',
  source_no VARCHAR(64) NOT NULL COMMENT '无业务单号 → 全部存数据ID UUID 兜底',
  report_time DATETIME NULL COMMENT '报工时间',
  work_order_no VARCHAR(64) NULL COMMENT '生产工单编号（SCGD…，关联 production_orders）',
  work_order_text VARCHAR(512) NULL COMMENT '生产任务单原文（编号+产品+尺寸+排列+数量拼接串）',
  component_serial VARCHAR(64) NULL COMMENT '零部件流水号（全表 10 值 = 工序模板）',
  component_name VARCHAR(128) NULL COMMENT '生产零部件（工序名）',
  report_qty DECIMAL(18, 4) NULL COMMENT '报工数量（需求带出非自由填，实测 29 个去重值）',
  over_flag VARCHAR(16) NULL COMMENT '是否超额生产（实测全否）',
  over_qty DECIMAL(18, 4) NULL COMMENT '超额生产数量（实测全 0）',
  order_demand_qty DECIMAL(18, 4) NULL COMMENT '订单需求数量',
  remaining_qty DECIMAL(18, 4) NULL COMMENT '工单剩余需求数量',
  reporter VARCHAR(64) NULL COMMENT '报工人（主，含 Anonymous 未登录扫码）',
  reporter_assist VARCHAR(64) NULL COMMENT '报工人(辅助)（源列同名第二列，解析层改名）',
  reporter_dept VARCHAR(128) NULL COMMENT '报工人所属部门（3 值 = 生产主体，Q2 实证）',
  production_line VARCHAR(64) NULL COMMENT '产线（封口机/全自动气调机/半自动气调机）',
  part_serials_text VARCHAR(512) NULL COMMENT '零部件流水号集合（多行文本原文）',
  has_component_flag VARCHAR(16) NULL COMMENT '生产工单中是否有该零部件（实测全 0）',
  stock_in_clerk VARCHAR(64) NULL COMMENT '入库员（一行多角色：报工+接单+入库同表）',
  order_clerk VARCHAR(64) NULL COMMENT '接单员',
  is_custom_qty VARCHAR(16) NULL COMMENT '是否自定义报工数量（实测全否）',
  data_status VARCHAR(32) NULL COMMENT '数据状态（实测全「已生效」）',
  data_title VARCHAR(255) NULL,
  owner_name VARCHAR(190) NULL,
  department_name VARCHAR(190) NULL COMMENT '所属部门(必填)（系统列 OwnerDeptId）',
  created_by_name VARCHAR(190) NULL,
  source_created_at DATETIME NULL,
  source_updated_at DATETIME NULL,
  source_hash VARCHAR(64) NULL,
  last_synced_at DATETIME NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_h3pr_external (external_source, external_id),
  UNIQUE KEY uk_h3pr_no (source_no),
  KEY idx_h3pr_wo (work_order_no),
  KEY idx_h3pr_time (report_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='氚云生产报工（工序完工事实层，2026-09-17 第 15 类；G3「报工」侧取证）';
