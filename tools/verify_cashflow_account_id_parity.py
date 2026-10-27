"""Compare legacy name join and new account-ID join for all 2026 month ends in isolation."""
import os

import pymysql

conn = pymysql.connect(host="localhost", user=os.environ.get("DB_USERNAME", "root"),
                       password=os.environ["DB_PASSWORD"], database="product_archive_p2c_isolated")
checked = 0
try:
    with conn.cursor() as cur:
        cur.execute("""SELECT id,account_name FROM h3_cash_accounts
                       WHERE external_source='H3YUN' ORDER BY id""")
        accounts = cur.fetchall()
        assert len(accounts) == 35
        for account_id, name in accounts:
            for month in range(1, 13):
                end = f"2026-{month + 1:02d}-01" if month < 12 else "2027-01-01"
                cur.execute("""SELECT COUNT(*),COALESCE(SUM(COALESCE(income,0)-COALESCE(expense,0)),0)
                               FROM h3_cashflow_entries
                               WHERE external_source='H3YUN' AND account_name=%s
                                 AND entry_date>='2026-01-01' AND entry_date<%s""", (name, end))
                old = cur.fetchone()
                cur.execute("""SELECT COUNT(*),COALESCE(SUM(COALESCE(income,0)-COALESCE(expense,0)),0)
                               FROM h3_cashflow_entries
                               WHERE external_source='H3YUN' AND account_id=%s
                                 AND entry_date>='2026-01-01' AND entry_date<%s""", (account_id, end))
                new = cur.fetchone()
                assert old == new, (account_id, month, old, new)
                checked += 1
        print(f"account-ID parity: {len(accounts)}/35 accounts, {checked}/420 month-end counts and net flows identical")
finally:
    conn.close()
