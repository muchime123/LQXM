"""Build a disposable P2-C database; source schemas are read only."""
import os
from pathlib import Path

import pymysql

SOURCE = "product_archive_b4_isolated"
TARGET = "product_archive_p2c_isolated"
FINANCE = {"cashflow_entities", "h3_cash_accounts", "h3_cashflow_entries", "cashflow_account_opening"}
AUTH = {"employees", "employee_roles", "system_roles", "system_permissions",
        "system_role_permissions", "system_role_feature_grants", "system_features",
        "system_feature_actions", "auth_sessions"}
SQL = Path(__file__).resolve().parents[1] / "backend/src/main/resources/db/migration/V419__cashflow_entry_account_id.sql"

conn = pymysql.connect(host="localhost", user=os.environ.get("DB_USERNAME", "root"),
                       password=os.environ["DB_PASSWORD"], autocommit=True)
try:
    with conn.cursor() as cur:
        cur.execute("SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name=%s", (TARGET,))
        if cur.fetchone()[0]:
            raise RuntimeError("isolated P2-C schema exists; refusing overwrite")
        cur.execute(f"CREATE DATABASE `{TARGET}` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci")
        cur.execute("SELECT table_name FROM information_schema.tables WHERE table_schema=%s AND table_type='BASE TABLE'", (SOURCE,))
        tables = sorted(row[0] for row in cur.fetchall())
        for table in tables:
            cur.execute(f"CREATE TABLE `{TARGET}`.`{table}` LIKE `{SOURCE}`.`{table}`")
        for table in sorted(FINANCE | AUTH):
            if table in tables:
                cur.execute(f"INSERT INTO `{TARGET}`.`{table}` SELECT * FROM `{SOURCE}`.`{table}`")
        cur.execute(f"SELECT COUNT(*) FROM `{TARGET}`.h3_cashflow_entries")
        assert cur.fetchone()[0] == 8751
        cur.execute(f"USE `{TARGET}`")
        statements = "\n".join(line for line in SQL.read_text(encoding="utf-8").splitlines()
                               if not line.lstrip().startswith("--")).split(";")
        for statement in statements:
            if statement.strip():
                cur.execute(statement)
        cur.execute("SELECT COUNT(*),COUNT(account_id),COUNT(DISTINCT account_id) FROM h3_cashflow_entries")
        print(f"{TARGET} migrated: entries/matched/accounts={cur.fetchone()}")
finally:
    conn.close()
