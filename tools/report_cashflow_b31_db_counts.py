"""Read-only B-3.1 database counts; never prints credentials or row payloads."""
import os
import pymysql

for database in ("product_archive", "product_archive_b31_isolated"):
    conn = pymysql.connect(host="localhost", user=os.environ.get("DB_USERNAME", "root"),
                           password=os.environ["DB_PASSWORD"], database=database)
    try:
        with conn.cursor() as cur:
            cur.execute("SELECT COUNT(*) FROM cashflow_account_opening WHERE fiscal_year >= 2027")
            openings = cur.fetchone()[0]
            cur.execute("SELECT COUNT(*) FROM request_idempotency WHERE request_key LIKE 'b31-%'")
            keys = cur.fetchone()[0]
            cur.execute("SELECT COUNT(*) FROM audit_logs WHERE action LIKE 'CASHFLOW_OPENING%'")
            audits = cur.fetchone()[0]
            print(f"{database}: openings_2027_plus={openings}, b31_keys={keys}, opening_audits={audits}")
    finally:
        conn.close()
