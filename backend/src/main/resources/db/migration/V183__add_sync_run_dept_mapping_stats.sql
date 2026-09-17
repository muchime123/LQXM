-- V183: 同步运行记录增加「部门映射」统计
-- 用途：每次同步后展示本次新建的真实 deptId 部门映射数，以及用真实 deptId
--       替换 NAME# 临时名称映射（过渡映射升级）的数量，便于管理员在同步页核对效果。
ALTER TABLE dingtalk_sync_runs
    ADD COLUMN dept_mappings_created INT NOT NULL DEFAULT 0
        COMMENT '本次新建真实 deptId 部门映射数',
    ADD COLUMN dept_mappings_upgraded INT NOT NULL DEFAULT 0
        COMMENT '本次真实 deptId 替换 NAME# 临时名称映射数';
