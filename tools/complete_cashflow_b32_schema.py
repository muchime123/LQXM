"""Fill only missing empty tables in the B-3.2 fixture from B-3.1 isolation."""
import os
import pymysql

source = "product_archive_b31_isolated"
target = "product_archive_b32_isolated"
conn = pymysql.connect(host="localhost", user=os.environ.get("DB_USERNAME", "root"),
                       password=os.environ["DB_PASSWORD"], autocommit=True)
try:
    with conn.cursor() as cur:
        cur.execute("SELECT table_name FROM information_schema.tables WHERE table_schema=%s AND table_type='BASE TABLE'", (source,))
        source_tables = {r[0] for r in cur.fetchall()}
        cur.execute("SELECT table_name FROM information_schema.tables WHERE table_schema=%s AND table_type='BASE TABLE'", (target,))
        target_tables = {r[0] for r in cur.fetchall()}
        assert "cashflow_opening_confirmation" in target_tables
        missing = sorted(source_tables - target_tables)
        for table in missing:
            cur.execute(f"CREATE TABLE `{target}`.`{table}` LIKE `{source}`.`{table}`")
        print(f"target={target} copied_empty_structures={len(missing)} total={len(target_tables) + len(missing)}")
finally:
    conn.close()
