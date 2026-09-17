-- 旧版状态刷新通过 company_team_members 内连接更新；
-- 业务员未映射时整行不会更新，导致已有 ACTIVE 事实关联的业务单仍停留 UNMATCHED。
UPDATE business_orders bo
SET bo.match_status = CASE
    WHEN EXISTS (
        SELECT 1 FROM business_order_links p
        WHERE p.business_order_id=bo.id AND p.object_type='PRODUCTION_ORDER' AND p.link_status='ACTIVE'
    ) AND EXISTS (
        SELECT 1 FROM business_order_links o
        WHERE o.business_order_id=bo.id AND o.object_type='SALES_OUTBOUND' AND o.link_status='ACTIVE'
    ) THEN 'CONFIRMED'
    WHEN EXISTS (
        SELECT 1 FROM business_order_links l
        WHERE l.business_order_id=bo.id AND l.link_status='ACTIVE'
    ) THEN 'ARCHIVED_ONLY'
    ELSE 'ORPHAN'
END
WHERE bo.match_status='UNMATCHED';
