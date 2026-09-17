-- 机型档位补充尺寸/重量规格字段：机器尺寸、净重、包装尺寸（木箱/纸箱）、毛重均跟随机型档位走
ALTER TABLE product_machine_tiers
    ADD COLUMN machine_size VARCHAR(100) NULL COMMENT '机器尺寸，如 420×420×700mm',
    ADD COLUMN net_weight_kg VARCHAR(100) NULL COMMENT '净重(kg)',
    ADD COLUMN carton_size VARCHAR(100) NULL COMMENT '纸箱包装尺寸，如 500×500×800mm',
    ADD COLUMN wood_box_size VARCHAR(100) NULL COMMENT '木箱包装尺寸，如 520×520×820mm',
    ADD COLUMN gross_weight_kg VARCHAR(100) NULL COMMENT '毛重(kg)';