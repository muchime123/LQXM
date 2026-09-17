-- 移除公司多地址管理功能（前端从未接入，地址已通过 CompanyInfo 单字段存储）
-- 关联的 Entity/Repository/Service/Controller/DTO/前端 API 已全部移除
DROP TABLE IF EXISTS company_addresses;
