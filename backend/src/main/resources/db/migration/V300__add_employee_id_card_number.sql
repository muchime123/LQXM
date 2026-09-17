-- 员工名册新增身份证号字段（V300）
-- 敏感字段：仅在员工详情（system.employee.manage 才可达）下发，列表摘要不返回；
-- 18 位（17 位数字 + 1 位数字或 X），Service 层校验格式。员工档案为业务身份源，
-- 供报销/提成/权限等场景消歧，宽松可空。
ALTER TABLE employees
    ADD COLUMN id_card_number VARCHAR(18) NULL COMMENT '身份证号（18 位：17 位数字 + 1 位数字或 X），敏感字段，仅详情接口返回' AFTER phone;