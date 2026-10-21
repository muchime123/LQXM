-- 参考间隔基准从「产品工期分组」下沉到「产品本身」：每个产品用自己的历史工单算候选、按产品发布。
-- 分组是同一天（V391）刚引入的中间层，但每个产品的历史样本都足够独立统计，
-- 先归组再算工期会把同组产品的差异抹平，故把这一层整体移除，全库只保留产品级一种口径。

ALTER TABLE production_tracking_product_benchmarks
    ADD COLUMN task_config_id BIGINT NULL COMMENT '任务单产品名称配置行（车间 + 产品名）' AFTER id;

-- 已有分组基准按「分组 → 该组下每个产品」展开成产品级基准，避免已发布值丢失
ALTER TABLE production_tracking_product_benchmarks MODIFY duration_group_id BIGINT NULL;

INSERT INTO production_tracking_product_benchmarks(task_config_id,stage_code,typical_days,sample_count,p50_days,p75_days,
    excluded_count,planned_anchor_count,application_anchor_count,application_p50_days,application_p75_days,zero_day_count,
    baseline_note,computed_at)
SELECT c.id,b.stage_code,b.typical_days,b.sample_count,b.p50_days,b.p75_days,b.excluded_count,b.planned_anchor_count,
    b.application_anchor_count,b.application_p50_days,b.application_p75_days,b.zero_day_count,b.baseline_note,b.computed_at
FROM production_tracking_product_benchmarks b
JOIN manufacturing_task_config_items c
    ON c.duration_group_id=b.duration_group_id AND c.config_type='PRODUCT_NAME'
WHERE b.task_config_id IS NULL;

-- 分组已无关联产品的基准行：随分组一并下线（分组本身不再存在，保留会变成无主数据）
DELETE FROM production_tracking_product_benchmarks WHERE task_config_id IS NULL;

ALTER TABLE production_tracking_product_benchmarks
    DROP FOREIGN KEY fk_tracking_benchmark_group,
    DROP INDEX uk_tracking_group_stage,
    ADD UNIQUE KEY uk_tracking_item_stage (task_config_id,stage_code),
    MODIFY task_config_id BIGINT NOT NULL,
    DROP COLUMN duration_group_id;

ALTER TABLE production_tracking_product_benchmarks
    ADD CONSTRAINT fk_tracking_benchmark_item FOREIGN KEY (task_config_id)
        REFERENCES manufacturing_task_config_items(id) ON DELETE CASCADE;

DROP TABLE production_tracking_duration_groups;

ALTER TABLE manufacturing_task_config_items
    DROP INDEX idx_task_config_duration_group,
    DROP COLUMN duration_group_id;
