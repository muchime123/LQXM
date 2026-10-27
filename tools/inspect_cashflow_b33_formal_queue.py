"""Read-only formal Flyway queue status for B-3.3."""
import os

import pymysql

conn = pymysql.connect(host="localhost", user=os.environ.get("DB_USERNAME", "root"),
                       password=os.environ["DB_PASSWORD"], database="product_archive")
try:
    with conn.cursor() as cur:
        cur.execute("""SELECT version,description,checksum,installed_on,success FROM flyway_schema_history
                       WHERE version REGEXP '^41[0-9]$' ORDER BY CAST(version AS UNSIGNED)""")
        print("formal Flyway 410+ rows:", cur.fetchall())
        cur.execute("""SELECT COUNT(*) FROM information_schema.tables
                       WHERE table_schema=DATABASE() AND table_name='cashflow_opening_correction'""")
        print("formal correction table:", cur.fetchone()[0])
        cur.execute("SELECT COUNT(*) FROM cashflow_opening_correction")
        print("formal correction rows:", cur.fetchone()[0])
        cur.execute("""SELECT COUNT(*) FROM system_role_permissions rp
                       JOIN system_permissions p ON p.id=rp.permission_id
                       WHERE p.permission_code='cashflow.opening.correct'""")
        print("formal roles with correction permission:", cur.fetchone()[0])
finally:
    conn.close()
