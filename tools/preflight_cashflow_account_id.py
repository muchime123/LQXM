"""Read-only gate to run immediately before the account-ID migration; never changes the target database."""
import os

import pymysql

SCHEMA = os.environ.get("DB_SCHEMA", "product_archive")
conn = pymysql.connect(host="localhost", user=os.environ.get("DB_USERNAME", "root"),
                       password=os.environ["DB_PASSWORD"], database=SCHEMA)
try:
    with conn.cursor() as cur:
        cur.execute("SELECT COUNT(*) FROM h3_cashflow_entries")
        total = cur.fetchone()[0]
        cur.execute("""SELECT COUNT(*) FROM h3_cashflow_entries e
                       JOIN h3_cash_accounts a ON a.external_source=e.external_source
                        AND a.account_name=e.account_name""")
        matched = cur.fetchone()[0]
        cur.execute("""SELECT COUNT(*) FROM h3_cashflow_entries e
                       LEFT JOIN h3_cash_accounts a ON a.external_source=e.external_source
                        AND a.account_name=e.account_name WHERE a.id IS NULL""")
        orphaned = cur.fetchone()[0]
        cur.execute("""SELECT COUNT(*) FROM (
                        SELECT e.id FROM h3_cashflow_entries e
                        JOIN h3_cash_accounts a ON a.external_source=e.external_source
                         AND a.account_name=e.account_name
                        GROUP BY e.id HAVING COUNT(*)<>1
                      ) x""")
        ambiguous = cur.fetchone()[0]
        cur.execute("""SELECT COUNT(*) FROM information_schema.columns
                       WHERE table_schema=DATABASE() AND table_name='h3_cashflow_entries'
                         AND column_name='account_id'""")
        existing_column = cur.fetchone()[0]
        print(f"{SCHEMA}: entries={total} matched={matched} orphaned={orphaned} ambiguous={ambiguous} account_id_column={existing_column}")
        assert total > 0 and matched == total and orphaned == 0 and ambiguous == 0
        assert existing_column == 0, "Account-ID migration must not run against a partially migrated table"
finally:
    conn.close()
