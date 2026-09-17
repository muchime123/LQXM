-- 当前客户档案全部由氚云销售出库事实生成；统一客户来源语义，避免把不同建档批次误认为不同业务来源。
UPDATE customers
SET source = 'H3YUN_SALES_OUTBOUND'
WHERE source IN ('H3YUN_BACKFILL','H3YUN_IMPORT','H3YUN_OUTBOUND','海外导入')
   OR source IS NULL OR TRIM(source)='';

-- 地址仍区分“建档时默认地址”和“后续从历史出库补充的地址”，但明确两者上游都为销售出库。
UPDATE customer_addresses SET source='H3YUN_SALES_OUTBOUND_DEFAULT'
WHERE source='CUSTOMER_ARCHIVE';

UPDATE customer_addresses SET source='H3YUN_SALES_OUTBOUND_HISTORY'
WHERE source='H3YUN_OUTBOUND';
