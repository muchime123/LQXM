"""Read-only checks for V406/V407 and B-1 baseline; never modifies the database."""
import pymysql

con = pymysql.connect(host="localhost", user="root", password="123456", database="product_archive")
cur = con.cursor()
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
cur.execute("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='erp' AND table_name='idem_request'")
print("erp_idem_exists", cur.fetchone()[0])
cur.execute("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='erp' AND table_name='idem_request'")
if cur.fetchone()[0]:
    cur.execute("SELECT COUNT(*), COUNT(DISTINCT request_key) FROM erp.idem_request")
    print("erp_idem_rows_distinct_keys", cur.fetchone())
cur.execute("SELECT COUNT(*),COUNT(DISTINCT id),SUM(opening_balance) FROM h3_cash_accounts")
print("opening_baseline", cur.fetchone())
cur.execute("SELECT COUNT(*) FROM h3_cashflow_entries")
print("cashflow_entries", cur.fetchone()[0])
con.close()
