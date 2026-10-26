"""Build a disposable B-3.2 fixture from B-3.1 isolation; never read or write product_archive."""
import os
from pathlib import Path

import pymysql

SOURCE = "product_archive_b31_isolated"
TARGET = "product_archive_b32_isolated"
MIGRATION = Path(__file__).resolve().parents[1] / "backend/src/main/resources/db/migration/V410__cashflow_opening_finance_confirmation.sql"
TABLES = ["employees", "employee_roles", "system_roles", "system_permissions",
          "system_role_permissions", "system_role_feature_grants", "system_features",
          "system_feature_actions", "h3_cash_accounts", "cashflow_account_opening",
          "audit_logs", "request_idempotency"]
AUTH = set(TABLES[:8])

conn = pymysql.connect(host="localhost", user=os.environ.get("DB_USERNAME", "root"),
                       password=os.environ["DB_PASSWORD"], autocommit=True)
try:
    with conn.cursor() as cur:
        cur.execute("SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name=%s", (SOURCE,))
        if cur.fetchone()[0] != 1:
            raise RuntimeError("B-3.1 isolated source missing")
        cur.execute("SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name=%s", (TARGET,))
        if cur.fetchone()[0]:
            raise RuntimeError("B-3.2 isolated target already exists; refusing overwrite")
        cur.execute(f"CREATE DATABASE `{TARGET}` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci")
        for table in TABLES:
            cur.execute(f"CREATE TABLE `{TARGET}`.`{table}` LIKE `{SOURCE}`.`{table}`")
            if table in AUTH:
                cur.execute(f"INSERT INTO `{TARGET}`.`{table}` SELECT * FROM `{SOURCE}`.`{table}`")
        cur.execute(f"INSERT INTO `{TARGET}`.h3_cash_accounts SELECT * FROM `{SOURCE}`.h3_cash_accounts WHERE id=900000001")
        cur.execute(f"SELECT COUNT(*) FROM `{TARGET}`.h3_cash_accounts")
        if cur.fetchone()[0] != 1:
            raise RuntimeError("Fixture account missing")
        cur.execute(f"USE `{TARGET}`")
        sql = "\n".join(line for line in MIGRATION.read_text(encoding="utf-8").splitlines()
                        if not line.lstrip().startswith("--"))
        for statement in sql.split(";"):
            if statement.strip():
                cur.execute(statement)
        for year in range(2040, 2047):
            cur.execute("""INSERT INTO cashflow_account_opening
                        (account_id,fiscal_year,amount,source,active,finance_confirmed,basis)
                        VALUES(900000001,%s,12.3456,'MANUAL_OPENING',%s,0,'B32 isolated fixture')""",
                        (year, 0 if year == 2043 else 1))
        cur.execute("SELECT DATABASE()")
        assert cur.fetchone()[0] == TARGET
        cur.execute("SELECT COUNT(*) FROM cashflow_account_opening")
        print(f"schema={TARGET} openings={cur.fetchone()[0]} migration_fixture=V410")
finally:
    conn.close()
