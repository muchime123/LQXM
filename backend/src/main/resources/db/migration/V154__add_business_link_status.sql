-- business_order_links 增加 link_status 状态列：
--   ACTIVE   正常关联（参与核算）
--   INACTIVE 已失效（不参与核算，保留历史痕迹）
-- 同时增加 invalid_reason / invalidated_at 用于追踪失效原因与时间

ALTER TABLE business_order_links
    ADD COLUMN link_status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE'
        COMMENT '关联状态：ACTIVE=正常参与核算，INACTIVE=已失效不参与核算' AFTER confidence,
    ADD COLUMN invalid_reason VARCHAR(200) NULL
        COMMENT '失效原因（如：单据变为已取消/草稿/进行中）' AFTER link_status,
    ADD COLUMN invalidated_at DATETIME NULL
        COMMENT '失效时间' AFTER invalid_reason,
    ADD INDEX idx_bol_status (link_status);

-- 补充外键完整性检查：确保 production_order_id 非空时存在对应记录
-- 注意：此处仅做标记检查，不阻塞业务（历史数据可能存在孤儿引用）
UPDATE business_order_links bol
SET bol.invalid_reason = '源单据不存在',
    bol.invalidated_at = NOW(),
    bol.link_status = 'INACTIVE'
WHERE bol.object_type = 'PRODUCTION_ORDER'
  AND NOT EXISTS (SELECT 1 FROM production_orders po WHERE po.id = bol.object_id);

UPDATE business_order_links bol
SET bol.invalid_reason = '源单据不存在',
    bol.invalidated_at = NOW(),
    bol.link_status = 'INACTIVE'
WHERE bol.object_type = 'SALES_OUTBOUND'
  AND NOT EXISTS (SELECT 1 FROM sales_outbounds so WHERE so.id = bol.object_id);
