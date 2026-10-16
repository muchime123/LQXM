-- V363: 删除生产执行链（完工/报工/返工）相关表
-- 背景：2026-10-06 ERP 前端下线，生产执行链（建单/审批/报工/冲正/检验/完工入库/返工）
--       的前端页面与 API 客户端已整体删除，后端 Controller/Service 同期拆除。
--       这 6 张表无任何代码引用，且无业务数据写入，直接 DROP。

DROP TABLE IF EXISTS production_execution_completion_items;
DROP TABLE IF EXISTS production_execution_completions;
DROP TABLE IF EXISTS production_execution_rework_reports;
DROP TABLE IF EXISTS production_execution_reworks;
DROP TABLE IF EXISTS production_execution_component_states;
DROP TABLE IF EXISTS production_execution_reports;
