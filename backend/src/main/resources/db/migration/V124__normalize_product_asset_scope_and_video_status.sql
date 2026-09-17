-- 产品素材适用范围升级为多机型；视频的使用渠道与内容状态分离。

CREATE TABLE product_asset_machine_tier_links (
    asset_id BIGINT NOT NULL,
    machine_tier_id BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (asset_id, machine_tier_id),
    CONSTRAINT fk_pamtl_asset FOREIGN KEY (asset_id) REFERENCES product_assets(id) ON DELETE CASCADE,
    CONSTRAINT fk_pamtl_tier FOREIGN KEY (machine_tier_id) REFERENCES product_machine_tiers(id) ON DELETE CASCADE,
    INDEX idx_pamtl_tier (machine_tier_id)
);

INSERT IGNORE INTO product_asset_machine_tier_links (asset_id, machine_tier_id)
SELECT id, machine_tier_id FROM product_assets WHERE machine_tier_id IS NOT NULL;

ALTER TABLE product_assets
    ADD COLUMN video_usage_channels VARCHAR(500) NULL AFTER video_usage_status,
    ADD COLUMN video_content_status VARCHAR(40) NULL AFTER video_usage_channels;

UPDATE product_assets
SET video_usage_channels = video_usage_status
WHERE video_usage_status IN ('可发客户', '销售演示', '官网可用', '内部参考');

UPDATE product_assets
SET video_content_status = video_usage_status
WHERE video_usage_status IN ('待剪辑', '画质较差', '已过时');

-- 有机型档位时，将原产品级包材关系迁移到每个机型，随后清除歧义关系。
INSERT IGNORE INTO product_machine_tier_packaging_links
    (machine_tier_id, packaging_variant_id, relation_type, match_source, remark, created_at, updated_at)
SELECT tier.id, link.packaging_variant_id, link.relation_type, 'MIGRATED', link.remark, NOW(6), NOW(6)
FROM product_model_packaging_links link
JOIN product_machine_tiers tier ON tier.model_id = link.product_model_id AND tier.deleted_at IS NULL;

DELETE link FROM product_model_packaging_links link
WHERE EXISTS (
    SELECT 1 FROM product_machine_tiers tier
    WHERE tier.model_id = link.product_model_id AND tier.deleted_at IS NULL
);
