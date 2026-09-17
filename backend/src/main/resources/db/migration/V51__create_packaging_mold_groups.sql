-- V51: 新增模具规格族表，改造 packaging_molds 支持 group_id
-- 修复 Excel 导入时将"产品名称"错误创建为 PackagingModel 的架构问题

-- 1. 创建 packaging_mold_groups（模具规格族）
CREATE TABLE IF NOT EXISTS packaging_mold_groups (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    model_id BIGINT NOT NULL COMMENT '归属的包材大系列 packaging_models.id',
    name VARCHAR(200) NOT NULL COMMENT '规格族名称，如"1414方盒"',
    shape_type VARCHAR(40) COMMENT '形状：square/round/rectangle',
    length_mm VARCHAR(40) COMMENT '归一化长度（方盒/矩形）',
    width_mm VARCHAR(40) COMMENT '归一化宽度（方盒/矩形）',
    diameter_mm VARCHAR(40) COMMENT '归一化直径（圆形）',
    remark VARCHAR(1000),
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at DATETIME NULL,
    CONSTRAINT fk_pkg_mold_group_model FOREIGN KEY (model_id) REFERENCES packaging_models(id) ON DELETE CASCADE,
    INDEX idx_pkg_mold_group_model (model_id),
    INDEX idx_pkg_mold_group_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='模具规格族（从Excel产品名归一化）';

-- 2. 为 packaging_molds 添加 group_id 外键
ALTER TABLE packaging_molds
    ADD COLUMN group_id BIGINT NULL COMMENT '所属模具规格族 packaging_mold_groups.id' AFTER model_id,
    ADD CONSTRAINT fk_pkg_mold_group FOREIGN KEY (group_id) REFERENCES packaging_mold_groups(id) ON DELETE SET NULL,
    ADD INDEX idx_pkg_mold_group_id (group_id);
