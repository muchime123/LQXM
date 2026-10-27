"""Create a disposable B-4 read test database. Formal schema is read only."""
import os

import pymysql

FORMAL = "product_archive"
AUTH_SOURCE = "product_archive_b32_isolated"
TARGET = "product_archive_b4_isolated"
AUTH_TABLES = (
    "employees", "employee_roles", "system_roles", "system_permissions",
    "system_role_permissions", "system_role_feature_grants", "system_features",
    "system_feature_actions", "auth_sessions", "request_idempotency",
)
FINANCE_TABLES = (
    "cashflow_entities", "h3_cash_accounts", "h3_cashflow_entries",
    "cashflow_account_opening",
)

conn = pymysql.connect(host="localhost", user=os.environ.get("DB_USERNAME", "root"),
                       password=os.environ["DB_PASSWORD"], autocommit=True)
try:
    with conn.cursor() as cur:
        cur.execute("SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name=%s", (TARGET,))
        if cur.fetchone()[0]:
            raise RuntimeError("B-4 isolated target already exists; refusing overwrite")
        cur.execute(f"CREATE DATABASE `{TARGET}` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci")
        cur.execute("SELECT table_name FROM information_schema.tables WHERE table_schema=%s AND table_type='BASE TABLE'", (AUTH_SOURCE,))
        tables = sorted(row[0] for row in cur.fetchall())
        for table in tables:
            cur.execute(f"CREATE TABLE `{TARGET}`.`{table}` LIKE `{AUTH_SOURCE}`.`{table}`")
        for table in AUTH_TABLES:
            if table in tables and table != "request_idempotency":
                cur.execute(f"INSERT INTO `{TARGET}`.`{table}` SELECT * FROM `{AUTH_SOURCE}`.`{table}`")
        for table in FINANCE_TABLES:
            cur.execute(f"INSERT INTO `{TARGET}`.`{table}` SELECT * FROM `{FORMAL}`.`{table}`")
        cur.execute(f"SELECT id FROM `{TARGET}`.h3_cash_accounts ORDER BY id LIMIT 1")
        account_id = cur.fetchone()[0]
        cur.execute(f"""INSERT INTO `{TARGET}`.cashflow_account_opening
                    (account_id,fiscal_year,amount,source,active,finance_confirmed,basis)
                    VALUES(%s,2027,123.4567,'MANUAL_OPENING',1,0,'B4 isolated test')""", (account_id,))
        for table in FINANCE_TABLES:
            cur.execute(f"SELECT COUNT(*) FROM `{TARGET}`.`{table}`")
            print(f"{TARGET}.{table}: {cur.fetchone()[0]}")
        print(f"2027 isolated active opening accountId={account_id}; formal database read only")
finally:
    conn.close()
