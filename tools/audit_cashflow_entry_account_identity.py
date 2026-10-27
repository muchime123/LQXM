"""Read-only preflight before linking historical cashflow entries to account IDs."""
import os

import pymysql

conn = pymysql.connect(host="localhost", user=os.environ.get("DB_USERNAME", "root"),
                       password=os.environ["DB_PASSWORD"], database="product_archive")
try:
    with conn.cursor() as cur:
        cur.execute("""SELECT COUNT(*) FROM h3_cashflow_entries""")
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
                       ) ambiguous""")
        ambiguous = cur.fetchone()[0]
        cur.execute("""SELECT external_source,COUNT(*) FROM h3_cashflow_entries GROUP BY external_source""")
        sources = cur.fetchall()
        cur.execute("""SELECT COUNT(*) FROM h3_cash_accounts WHERE external_source='H3YUN'""")
        accounts = cur.fetchone()[0]
        print(f"entries={total} matched={matched} orphaned={orphaned} ambiguous={ambiguous} accounts={accounts} sources={sources}")
        assert total == matched and orphaned == 0 and ambiguous == 0
finally:
    conn.close()
