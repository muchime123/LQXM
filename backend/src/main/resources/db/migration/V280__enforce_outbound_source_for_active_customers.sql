-- 所有当前有效客户档案均由销售出库事实生成，按已确认业务事实统一来源，不依赖历史来源文本编码。
UPDATE customers
SET source='H3YUN_SALES_OUTBOUND'
WHERE deleted_at IS NULL;
