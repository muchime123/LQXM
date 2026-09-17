-- P5.1i Dry-Run 核对：V136 迁移后的花名册行数 / 业务员标记 / 氚云覆盖率 报告
-- 全部是 SELECT，幂等、只读、不影响数据，Flyway 执行后用户可通过 flyway 日志或 CLI 手动核对
-- 注：V136 已经完成 employees 和 employee_external_mappings 的建表+初始化；
--     本脚本仅输出验收基准数据，以及为 V137+ 的后续"同步花名册"功能提供参考统计。

-- ============================================================
-- C1: 花名册初始化总行数（基准 C1 = company_team_members 有效人数 = C2）
-- ============================================================
SELECT 'C1_employees_total'            AS check_name,
       COUNT(*)                         AS actual,
       'employees 表总行数（含历史上已手工追加的员工；V136 刚执行时应 ≈ C2）' AS expected_hint
FROM employees;

-- ============================================================
-- C2: 源表 company_team_members 有手机号人数（V136 初始化主要来源）
-- ============================================================
SELECT 'C2_company_team_members_valid' AS check_name,
       COUNT(*)                         AS actual,
       'C1 应 >= C2；C1 = C2 说明所有花名册都有手机号（理想情况）' AS expected_hint
FROM company_team_members
WHERE name IS NOT NULL AND name != ''
  AND phone IS NOT NULL AND phone != '';

-- ============================================================
-- C3: V136 自动标记 is_salesperson = 1 的员工人数
-- ============================================================
SELECT 'C3_auto_marked_salespersons'   AS check_name,
       COUNT(*)                         AS actual,
       '理论应 <= C4；花名册中在职的姓名与 fact 表 salesperson_name 完全匹配即可' AS expected_hint
FROM employees
WHERE is_salesperson = 1
  AND created_by = 'V136_INIT_FROM_COMPANY_TEAM_MEMBERS';

-- ============================================================
-- C4: 3 张 fact 表中出现过的唯一业务员姓名数（COLLATE 防御）
-- ============================================================
SELECT 'C4_fact_unique_salespersons'   AS check_name,
       COUNT(DISTINCT salesperson_name COLLATE utf8mb4_unicode_ci) AS actual,
       'fact 表维度的业务员池；C3 应 <= C4（部分业务员可能离职或不在花名册）' AS expected_hint
FROM (
    SELECT salesperson_name FROM production_orders WHERE salesperson_name IS NOT NULL AND salesperson_name != ''
    UNION
    SELECT salesperson_name FROM sales_outbounds   WHERE salesperson_name IS NOT NULL AND salesperson_name != ''
    UNION
    SELECT salesperson_name FROM receipts         WHERE salesperson_name IS NOT NULL AND salesperson_name != ''
) sp;

-- ============================================================
-- C5: 当前 employee_external_mappings 记录数（按系统维度）
--     注意：fact 表没有氚云 OwnerId（仅 source_created_by = 姓名中文），
--           所以 V136 不会自动写映射；后续通过 UI「同步花名册」或 V137+ 接入
--           氚云 / 钉钉 Open API 时批量建立真正的 external_user_id 映射
-- ============================================================
SELECT 'C5_h3yun_mapped_employees'     AS check_name,
       COUNT(DISTINCT employee_id)      AS actual,
       'V136 执行后应为 0；后续手工或 API 同步后才增长（正常现象）' AS expected_hint
FROM employee_external_mappings
WHERE source_system = 'H3YUN';

SELECT 'C5_mappings_by_system'         AS check_name,
       source_system                    AS dimension,
       COUNT(*)                         AS actual,
       '各系统映射条数分布（检查是否有其他系统的存量映射）' AS expected_hint
FROM employee_external_mappings
GROUP BY source_system;

-- ============================================================
-- C6: 氚云导入操作人候选池（source_created_by 中文名 ↔ employees.name 匹配报告）
--     供「同步花名册」功能做自动建议候选，不做自动写入（没有真正的 OwnerId，
--     写了也只是"姓名=姓名"的同义反复，在后续 OwnerId 打通后反而需手动清理）
--     注：contract_archives 暂无 source_created_by 列，故只统计 production_orders / sales_outbounds / receipts
-- ============================================================
SELECT 'C6_h3yun_creator_candidates'   AS check_name,
       COUNT(DISTINCT source_created_by COLLATE utf8mb4_unicode_ci) AS actual,
       'fact 表 source_created_by 去重后的唯一中文名人数；后续可按姓名匹配建议映射' AS expected_hint
FROM (
    SELECT source_created_by FROM production_orders WHERE source_created_by IS NOT NULL AND source_created_by != ''
    UNION
    SELECT source_created_by FROM sales_outbounds   WHERE source_created_by IS NOT NULL AND source_created_by != ''
    UNION
    SELECT source_created_by FROM receipts         WHERE source_created_by IS NOT NULL AND source_created_by != ''
) creators;

-- C6b: 其中可与花名册姓名精确匹配的人数（COLLATE 统一）
--      contract_archives 同理跳过（无 source_created_by）
SELECT 'C6b_name_matched_creators'     AS check_name,
       COUNT(DISTINCT e.name COLLATE utf8mb4_unicode_ci) AS actual,
       'C6 与 employees.name 精确匹配的交集人数；可在同步花名册中一键建议认领' AS expected_hint
FROM employees e
JOIN (
    SELECT source_created_by AS nm FROM production_orders WHERE source_created_by IS NOT NULL AND source_created_by != ''
    UNION
    SELECT source_created_by FROM sales_outbounds   WHERE source_created_by IS NOT NULL AND source_created_by != ''
    UNION
    SELECT source_created_by FROM receipts         WHERE source_created_by IS NOT NULL AND source_created_by != ''
) c ON c.nm COLLATE utf8mb4_unicode_ci = e.name COLLATE utf8mb4_unicode_ci;
