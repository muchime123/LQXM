-- 模具表保存导入原始口径/高度，便于未匹配规格时仍可显示尺寸并辅助匹配
ALTER TABLE packaging_molds
    ADD COLUMN raw_caliber VARCHAR(80)  NULL COMMENT '导入时原始口径（如 141×141 / Ø120 / C178.7）',
    ADD COLUMN raw_height  VARCHAR(200) NULL COMMENT '导入时原始高度（如 25+35+45+55 / 50）';
