-- V182: 审批流水发起人部门真实钉钉 ID 回填
--
-- 来源：审批详情接口 originatorDeptId（随审批单数据返回，不需要通讯录读取权限）。
-- 用途：把 department_external_mappings 从 NAME#MD5(name) 过渡键逐步升级为真实 deptId，
--       跨审批单按真实 deptId 归并部门，消除同名不同部门 / 部门改名导致的错误归并。
ALTER TABLE expense_claims
    ADD COLUMN dingtalk_dept_id VARCHAR(50) NULL
    COMMENT '发起人部门钉钉真实 deptId（审批详情 originatorDeptId）' AFTER submitter_dept_snapshot;

CREATE INDEX idx_ec_dingtalk_dept ON expense_claims(dingtalk_dept_id);
