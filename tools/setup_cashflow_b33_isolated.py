"""Apply candidate V414 to the disposable B-4 schema only and grant FINANCE for testing."""
import os
from pathlib import Path

import pymysql

SCHEMA = "product_archive_b4_isolated"
SQL = Path(__file__).resolve().parents[1] / "backend/src/main/resources/db/migration/V414__cashflow_opening_correction.sql"
conn = pymysql.connect(host="localhost", user=os.environ.get("DB_USERNAME", "root"),
                       password=os.environ["DB_PASSWORD"], database=SCHEMA, autocommit=True)
try:
    with conn.cursor() as cur:
        cur.execute("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='cashflow_opening_correction'")
        if cur.fetchone()[0]:
            raise RuntimeError("isolated correction table already exists; refusing to reapply")
        source = "\n".join(line for line in SQL.read_text(encoding="utf-8").splitlines()
                           if not line.lstrip().startswith("--"))
        for statement in source.split(";"):
            if statement.strip():
                cur.execute(statement)
        cur.execute("SELECT id FROM system_permissions WHERE permission_code='cashflow.opening.correct'")
        permission_id = cur.fetchone()[0]
        cur.execute("INSERT IGNORE INTO system_role_permissions(role_id,permission_id) VALUES(5,%s)", (permission_id,))
        cur.execute("""UPDATE system_role_feature_grants
                          SET selected_actions=CONCAT(selected_actions,',cashflow.opening.correct')
                        WHERE role_id=5 AND feature_code='CASHFLOW'
                          AND FIND_IN_SET('cashflow.opening.correct',selected_actions)=0""")
        cur.execute("SELECT COUNT(*) FROM cashflow_opening_correction")
        print(f"{SCHEMA}: candidate V414 applied; FINANCE role 5 granted in isolation; corrections={cur.fetchone()[0]}")
finally:
    conn.close()
