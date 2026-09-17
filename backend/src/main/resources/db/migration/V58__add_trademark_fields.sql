-- 商标管理扩展字段
ALTER TABLE certificates ADD COLUMN nice_class INT COMMENT '国际分类号（尼斯分类1-45），仅商标使用';
ALTER TABLE certificates ADD COLUMN nice_class_description TEXT COMMENT '核定使用商品/服务项目，仅商标使用';
ALTER TABLE certificates ADD COLUMN applicant_address VARCHAR(500) COMMENT '注册人地址，仅商标使用';
