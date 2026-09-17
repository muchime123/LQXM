-- 收款记录/多订单结清已收敛为只读氚云历史台账；回款口径只取销售出库收款子表。
-- 旧收款匹配候选与批次候选统计不再有写入方或业务消费方，先留档再下线。
CREATE TABLE IF NOT EXISTS receipt_match_candidates_archive_20260916 LIKE receipt_match_candidates;
INSERT IGNORE INTO receipt_match_candidates_archive_20260916
SELECT * FROM receipt_match_candidates;

DROP TABLE receipt_match_candidates;

-- 旧生产工单与销售出库匹配候选同样已无生成方、查询方和业务消费方。
CREATE TABLE IF NOT EXISTS business_match_candidates_archive_20260916 LIKE business_match_candidates;
INSERT IGNORE INTO business_match_candidates_archive_20260916
SELECT * FROM business_match_candidates;

DROP TABLE business_match_candidates;

ALTER TABLE h3_import_batches
  DROP COLUMN candidate_total,
  DROP COLUMN candidate_pending,
  DROP COLUMN candidate_confirmed,
  DROP COLUMN candidate_rejected,
  DROP COLUMN candidate_stats_updated_at;
