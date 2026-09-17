-- v2 审批详情不返回发起人姓名；历史逻辑误把 originatorDeptName（子公司/部门）写成提单人。
-- 标准审批标题为“张三提交的日常报销”，据此恢复姓名；userId 仍是最终身份唯一键。
UPDATE expense_claims
SET submitter_name_snapshot = TRIM(SUBSTRING_INDEX(title, '提交的', 1)),
    source_created_by = TRIM(SUBSTRING_INDEX(title, '提交的', 1)),
    updated_at = CURRENT_TIMESTAMP
WHERE external_source = 'DINGTALK'
  AND title LIKE '%提交的%'
  AND TRIM(SUBSTRING_INDEX(title, '提交的', 1)) <> '';
