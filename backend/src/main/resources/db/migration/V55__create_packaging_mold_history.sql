-- ============================================================
-- V55: 模具资产变更历史表
-- 记录 PackagingMold 关键字段（status/cavityCount/engravingType 等）的变更轨迹，
-- 对齐 PackagingVariantHistory 的设计，补齐模具资产的状态机与审计闭环。
-- ============================================================

CREATE TABLE packaging_mold_histories (
    id              BIGINT NOT NULL AUTO_INCREMENT,
    mold_id         BIGINT NOT NULL,
    field_key       VARCHAR(80) NOT NULL COMMENT '变更字段键（status/cavityCount/engravingType...）',
    field_label     VARCHAR(120) COMMENT '字段中文标签，便于前端直接展示',
    old_value       TEXT COMMENT '变更前值（字符串化）',
    new_value       TEXT COMMENT '变更后值（字符串化）',
    change_type     VARCHAR(40) NOT NULL DEFAULT 'field_update' COMMENT 'status_change | field_update',
    change_source   VARCHAR(80) NOT NULL DEFAULT 'manual' COMMENT 'manual | import | migration',
    change_reason   VARCHAR(500) COMMENT '变更原因（状态流转时建议填写）',
    changed_by      VARCHAR(120) COMMENT '操作人',
    created_at      DATETIME DEFAULT CURRENT_TIMESTAMP,

    PRIMARY KEY (id),
    INDEX idx_mold_history_mold_id (mold_id),
    INDEX idx_mold_history_created_at (created_at),
    CONSTRAINT fk_mold_history_mold FOREIGN KEY (mold_id)
        REFERENCES packaging_molds(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
