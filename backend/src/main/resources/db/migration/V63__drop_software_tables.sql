-- 软件管理模块已下线：删除 software_file_mappings 与 softwares 两张表
-- 历史脚本 V16__software_file_mappings_seed.sql 仅写入种子数据，建表由 Hibernate 自动完成；
-- 此处统一 DROP，IF EXISTS 兼容从未建过表的环境。

DROP TABLE IF EXISTS software_file_mappings;
DROP TABLE IF EXISTS softwares;
