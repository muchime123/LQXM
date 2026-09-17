ALTER TABLE company_certifications
  ADD COLUMN is_permanent TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否长期有效（1=长期有效，0=有到期日）' AFTER expiry_date;

-- 营业执照默认标记为长期有效
UPDATE company_certifications
SET is_permanent = 1
WHERE category LIKE '%营业执照%' OR category LIKE '%许可%' OR category LIKE '%资质%';
