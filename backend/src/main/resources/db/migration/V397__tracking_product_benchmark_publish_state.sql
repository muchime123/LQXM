-- 产品节点参考间隔：区分「人工审核发布」与「按历史样本 P50 批量填充」。
--
-- 背景：typical_days（发布值）此前只有 baseline_note 一个自由文本描述来源，
-- 「这条是谁定的、是不是机器填的」只能靠读字符串；撤销发布后与「从未设置」也无法区分。
-- 现拆成两个结构化字段：
--   published_source  MANUAL＝人工审核发布 / P50＝按历史样本批量填充 / NULL＝未发布（含已撤销）
--   published_at      最近一次发布时间
-- 候选统计（sample_count / p50 等）与发布状态解耦：重算候选不碰这两列，
-- 撤销发布只清发布值与来源，候选留着供下次参考。
-- 幂等：可重复执行。

ALTER TABLE production_tracking_product_benchmarks
  ADD COLUMN published_source VARCHAR(20) NULL COMMENT 'MANUAL 人工审核 / P50 按历史样本批量填充；NULL＝未发布',
  ADD COLUMN published_at DATETIME NULL COMMENT '最近一次发布时间';

-- 回填：既有发布值按 baseline_note 判来源；没有单独记过发布时间，取该行 updated_at。
UPDATE production_tracking_product_benchmarks
SET published_source = CASE WHEN baseline_note LIKE '%P50%' THEN 'P50' ELSE 'MANUAL' END,
    published_at = updated_at
WHERE typical_days IS NOT NULL
  AND published_source IS NULL;
