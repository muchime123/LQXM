-- P3 修正（2026-09-17）：氚云内勤会把整段开票信息粘贴进「税号」字段（实测最长 140 字符，
-- 例：「四川盐帮铧樾食品有限公司统一社会信用代码:91510300MABYTBLC8W开户银行:…」）。
-- 按「如实落库、不清洗」原则加宽 tax_no；blob 值在 credit_code 等值匹配中天然不命中，无副作用。
ALTER TABLE h3_customer_invoice_profiles MODIFY tax_no VARCHAR(255) NULL;
ALTER TABLE h3_invoice_requests MODIFY tax_no VARCHAR(255) NULL;
