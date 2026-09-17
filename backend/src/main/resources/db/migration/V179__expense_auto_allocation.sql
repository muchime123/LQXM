-- P2 费用归集自动化：规则表 + 运行记录表 + Allocation 来源/规则追溯列
-- 注意：V178 已经被"批量列补齐"占用，因此本脚本用 V179。

-- ===== 1) 归集规则表 =====
CREATE TABLE expense_allocation_rules (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  rule_name VARCHAR(200) NOT NULL,
  rule_description VARCHAR(500) NULL,
  rule_priority INT NOT NULL DEFAULT 100,   -- 越小越先执行；内置规则用 < 100，用户自定义从 100 开始
  rule_enabled TINYINT(1) NOT NULL DEFAULT 1,
  -- 匹配维度（多项 AND 生效；空=不过滤）
  match_expense_category VARCHAR(50) NULL,   -- expense_category 精确匹配（如"差旅费/招待费/办公费"）
  match_department_name VARCHAR(200) NULL,   -- submitter_dept_snapshot 精确匹配（可选）
  match_submitter_name VARCHAR(100) NULL,    -- submitter_name_snapshot 精确匹配（可选）
  match_claim_date_from DATE NULL,
  match_claim_date_to DATE NULL,
  match_title_keyword VARCHAR(200) NULL,     -- claim.title / expense_items.item_desc 的 LIKE 关键字（单关键字）
  -- 目标策略：FIXED_BUSINESS_ORDER / SAME_SALESPERSON_SALES / SAME_CUSTOMER_LATEST / DEPARTMENT_OVERHEAD / COMPANY_OVERHEAD
  target_strategy VARCHAR(50) NOT NULL,
  -- target_strategy = FIXED_BUSINESS_ORDER 时必填
  fixed_target_type VARCHAR(30) NULL,
  fixed_target_id BIGINT NULL,
  fixed_target_no_snapshot VARCHAR(100) NULL,
  fixed_target_name_snapshot VARCHAR(300) NULL,
  -- 分摊算法：FULL / EVEN / BY_SALES_AMOUNT / REVENUE_WEIGHTED
  allocation_algorithm VARCHAR(50) NOT NULL DEFAULT 'FULL',
  -- SAME_SALESPERSON_SALES / BY_SALES_AMOUNT 等窗口参数
  candidate_window_days INT NULL DEFAULT 180,  -- 以 claim_date 为中心，向前 N 天作为业务候选窗口
  candidate_top_n INT NULL DEFAULT 20,          -- 候选上限（分摊目标数）
  created_by VARCHAR(100) NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  INDEX idx_ear_priority(rule_priority, rule_enabled),
  INDEX idx_ear_category(match_expense_category)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='P2 费用自动归集规则配置';

-- ===== 2) 自动归集运行记录（便于审计） =====
CREATE TABLE expense_allocation_runs (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  expense_claim_id BIGINT NOT NULL,
  trigger_type VARCHAR(30) NOT NULL,        -- MANUAL_CLICK / BATCH_JOB / PROFIT_REFRESH
  run_status VARCHAR(20) NOT NULL,          -- SUCCESS / NO_RULE_MATCHED / PARTIAL / FAILED
  hit_rule_id BIGINT NULL,
  hit_rule_name VARCHAR(200) NULL,
  targets_found INT NOT NULL DEFAULT 0,
  allocations_created INT NOT NULL DEFAULT 0,
  allocations_total_amount DECIMAL(14,2) NOT NULL DEFAULT 0,
  message VARCHAR(1000) NULL,
  source_allocation_ids TEXT NULL,           -- 逗号分隔的 created allocation id
  created_by VARCHAR(100) NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_earun_claim(expense_claim_id, created_at),
  INDEX idx_earun_status(run_status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='P2 费用自动归集运行记录';

-- ===== 3) expense_allocations 增加规则与来源追溯 =====
ALTER TABLE expense_allocations
  ADD COLUMN allocation_source VARCHAR(30) NOT NULL DEFAULT 'MANUAL' COMMENT 'MANUAL / AUTO_RULE / ADJUSTMENT' AFTER allocation_status,
  ADD COLUMN allocation_rule_id BIGINT NULL COMMENT '命中的 expense_allocation_rules.id（AUTO_RULE 时）' AFTER allocation_source,
  ADD COLUMN allocation_run_id BIGINT NULL COMMENT '命中的 expense_allocation_runs.id' AFTER allocation_rule_id,
  ADD INDEX idx_ea_source(allocation_source),
  ADD INDEX idx_ea_rule(allocation_rule_id),
  ADD INDEX idx_ea_run(allocation_run_id);
