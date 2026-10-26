"""Read-only cross-schema check of the three accidental result references."""
import os
import pymysql

for schema in ("product_archive", "product_archive_b31_isolated"):
    conn = pymysql.connect(host="localhost", user=os.environ.get("DB_USERNAME", "root"),
                           password=os.environ["DB_PASSWORD"], database=schema)
    try:
        with conn.cursor() as cur:
            for ref in (1, 4, 6):
                cur.execute("SELECT account_id,fiscal_year,source FROM cashflow_account_opening WHERE id=%s", (ref,))
                print(schema, ref, cur.fetchone())
    finally:
        conn.close()
