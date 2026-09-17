-- V261: 清理历史遗留 contract_archives / contract_archive_items 死表
-- 背景：V133 创建合同档案表（§7.5 设计目标），但从未有任何 Java/前端代码读写
--       实际氚云导入与历史销售合同数据均未使用它们。
-- 2026-09-04 核查：两张表均 0 行，且后端无 Entity/Repository/JDBC 引用 → 判定为死表清理。
-- 幂等：DROP TABLE IF EXISTS。
DROP TABLE IF EXISTS contract_archive_items;
DROP TABLE IF EXISTS contract_archives;
