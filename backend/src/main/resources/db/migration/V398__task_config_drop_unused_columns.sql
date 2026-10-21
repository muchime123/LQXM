-- 清理 manufacturing_task_config_items 里「建了但没用」的列（2026-10-21 梳理后确认）。
--
-- 这些列要么从未被消费，要么是冗余副本 / 派生值；留着只会让人误以为它们是产品的可信身份：
--   workshop_name        workshop_id 的冗余副本，靠 UPDATE 手工级联（V396 曾按外键对齐过一次）。
--                        2026-10-20 起匹配口径全面改走 workshop_id 反查 manufacturing_workshops，副本不再需要。
--   config_code          三种口径并存（BP-00000001 种子 / BP-H3YUN-<uuid> / BP-<uuid>），页面从不展示，
--                        也没有任何表用它做 JOIN 或外键目标。
--   product_type         由 config_name 字面推导（配件→PART / 模具→MOLD），改名后不重算 ⇒ 数据漂移。
--   option_group_code    全部 UNCLASSIFIED，等于不存在；可选配置与产品之间本就没有关联关系。
--   sort_order           从未使用（列表按 workshop_name, config_type, id 排序）。
--   process_template_id  产品级制程；2026-10-20 起制程按车间分配，写端点已不再接收，仅剩历史只读值。
--
-- 关联键不受影响：production_tracking_product_benchmarks.task_config_id 与
-- manufacturing_product_config_rules.base_product_id 都指向 items(id)（主键），
-- 全库没有任何表用 config_code 做关联键。
--
-- ⚠️ 不可逆：process_template_id 的历史值随列删除一起丢弃（页面早已不展示、写端点不再写入）。

-- 先解外键（V265 建的 fk_task_config_template → manufacturing_process_templates(id)），
-- 再删单列唯一键 uk_task_config_code（V347 建），最后删列本身。
ALTER TABLE manufacturing_task_config_items DROP FOREIGN KEY fk_task_config_template;
ALTER TABLE manufacturing_task_config_items DROP INDEX uk_task_config_code;

ALTER TABLE manufacturing_task_config_items
  DROP COLUMN process_template_id,
  DROP COLUMN workshop_name,
  DROP COLUMN config_code,
  DROP COLUMN product_type,
  DROP COLUMN option_group_code,
  DROP COLUMN sort_order;
