"""Create an isolated B-3.1 schema. Never changes product_archive tables."""
import os
import pymysql

source = os.environ.get("DB_NAME", "product_archive")
target = "product_archive_b31_isolated"
con = pymysql.connect(host=os.environ.get("DB_HOST", "localhost"),
                      user=os.environ.get("DB_USER", "root"),
                      password=os.environ["DB_PASSWORD"], autocommit=True)
cur = con.cursor()
cur.execute("SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name=%s", (target,))
if cur.fetchone()[0]:
    raise SystemExit("isolated schema already exists; inspect it instead of overwriting")
cur.execute(f"CREATE DATABASE `{target}` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci")
cur.execute("SELECT table_name FROM information_schema.tables WHERE table_schema=%s AND table_type='BASE TABLE'", (source,))
tables = [row[0] for row in cur.fetchall()]
for table in tables:
    cur.execute(f"CREATE TABLE `{target}`.`{table}` LIKE `{source}`.`{table}`")
auth = ["employees", "employee_roles", "system_roles", "system_permissions",
        "system_role_permissions", "system_role_feature_grants", "system_features",
        "system_feature_actions"]
for table in auth:
    if table in tables:
        cur.execute(f"INSERT INTO `{target}`.`{table}` SELECT * FROM `{source}`.`{table}`")
cur.execute(f"INSERT INTO `{target}`.h3_cash_accounts SELECT * FROM `{source}`.h3_cash_accounts ORDER BY id LIMIT 1")
cur.execute(f"UPDATE `{target}`.h3_cash_accounts SET id=900000001,account_name='B31_ISOLATED_TEST',status='ACTIVE'")
print(f"isolated schema ready: {target}; tables={len(tables)}; accounts=1")
con.close()
