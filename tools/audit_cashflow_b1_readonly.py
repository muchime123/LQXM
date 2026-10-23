"""Read-only checks for V406/V407 and B-1 baseline; never modifies the database."""
import os
import pymysql

con = pymysql.connect(host=os.environ.get("DB_HOST", "localhost"),
                      user=os.environ.get("DB_USER", "root"),
                      password=os.environ["DB_PASSWORD"],
                      database=os.environ.get("DB_NAME", "product_archive"))
cur = con.cursor()
cur.execute("SELECT version, success FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 5")
print("flyway_recent", cur.fetchall())
tables = ["manufacturing_inspections", "manufacturing_work_reports", "manufacturing_order_operations", "manufacturing_orders", "manufacturing_process_template_rules", "manufacturing_process_template_operations", "manufacturing_process_templates", "manufacturing_operations", "manufacturing_workshops", "request_idempotency"]
for table in tables:
    cur.execute("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name=%s", (table,))
    if cur.fetchone()[0]:
        cur.execute("SELECT COUNT(*) FROM `" + table + "`")
        print(table, cur.fetchone()[0])
    else:
        print(table, "ABSENT")
cur.execute("""SELECT TABLE_NAME,COLUMN_NAME,REFERENCED_TABLE_NAME FROM information_schema.KEY_COLUMN_USAGE
WHERE TABLE_SCHEMA=DATABASE() AND REFERENCED_TABLE_NAME IN
('manufacturing_orders','manufacturing_order_operations','manufacturing_operations','manufacturing_process_templates')
ORDER BY TABLE_NAME,COLUMN_NAME""")
print("foreign_keys", cur.fetchall())
cur.execute("""SELECT TABLE_NAME,COLUMN_NAME,REFERENCED_TABLE_NAME FROM information_schema.KEY_COLUMN_USAGE
WHERE TABLE_SCHEMA=DATABASE() AND REFERENCED_TABLE_NAME IN
('manufacturing_inspections','manufacturing_work_reports','manufacturing_order_operations',
 'manufacturing_orders','manufacturing_process_template_rules',
 'manufacturing_process_template_operations','manufacturing_process_templates','manufacturing_operations')
ORDER BY TABLE_NAME,COLUMN_NAME""")
print("all_drop_target_foreign_keys", cur.fetchall())
cur.execute("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='erp' AND table_name='idem_request'")
print("erp_idem_exists", cur.fetchone()[0])
cur.execute("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='erp' AND table_name='idem_request'")
if cur.fetchone()[0]:
    cur.execute("SELECT COUNT(*), COUNT(DISTINCT request_key) FROM erp.idem_request")
    print("erp_idem_rows_distinct_keys", cur.fetchone())
    cur.execute("""SELECT CHARACTER_SET_NAME,COLLATION_NAME FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA='erp' AND TABLE_NAME='idem_request' AND COLUMN_NAME='request_key'""")
    print("erp_request_key_charset_collation", cur.fetchall())
    cur.execute("""SELECT COUNT(*) FROM (
      SELECT request_key COLLATE utf8mb4_0900_ai_ci AS normalized_key
      FROM erp.idem_request GROUP BY normalized_key HAVING COUNT(*)>1
    ) AS collisions""")
    print("target_collation_collision_groups", cur.fetchone()[0])
cur.execute("SELECT COUNT(*),COUNT(DISTINCT id),SUM(opening_balance) FROM h3_cash_accounts")
print("opening_baseline", cur.fetchone())
cur.execute("SELECT COUNT(*) FROM h3_cashflow_entries")
print("cashflow_entries", cur.fetchone()[0])
con.close()
