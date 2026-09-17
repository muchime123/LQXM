-- 旧 BusinessOrder 费用向销售出库待确认推荐迁移的来源追踪。
-- 原分配在财务确认新推荐时才软撤销，确保迁移预览和推荐生成均不改变利润。
ALTER TABLE expense_allocations
    ADD COLUMN legacy_source_allocation_id BIGINT NULL
        COMMENT '迁移推荐对应的旧 BUSINESS_ORDER 分配 id；确认新分配时软撤销旧分配' AFTER allocation_run_id,
    ADD UNIQUE KEY uk_ea_legacy_source_target (legacy_source_allocation_id, target_type, target_id);
