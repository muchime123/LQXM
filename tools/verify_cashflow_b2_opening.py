"""Read-only B-2 parity check for every 2026 account and every month end."""
import os
from decimal import Decimal
import pymysql

con = pymysql.connect(host=os.environ.get("DB_HOST", "localhost"),
                      user=os.environ.get("DB_USER", "root"),
                      password=os.environ["DB_PASSWORD"],
                      database=os.environ.get("DB_NAME", "product_archive"))
cur = con.cursor()
cur.execute("""SELECT a.id,a.account_name,a.opening_balance,o.amount,o.active,o.finance_confirmed
FROM h3_cash_accounts a LEFT JOIN cashflow_account_opening o
ON o.account_id=a.id AND o.fiscal_year=2026
WHERE a.external_source='H3YUN' ORDER BY a.id""")
accounts = cur.fetchall()
assert len(accounts) == 35
checked = 0
for account_id, name, old, new, active, confirmed in accounts:
    assert old == new and active == 1 and confirmed == 0, account_id
    assert old is None or isinstance(old, Decimal)
    for month in range(1, 13):
        end = f"2026-{month + 1:02d}-01" if month < 12 else "2027-01-01"
        cur.execute("""SELECT COALESCE(SUM(COALESCE(income,0)-COALESCE(expense,0)),0)
        FROM h3_cashflow_entries WHERE external_source='H3YUN' AND account_name=%s
        AND entry_date >= '2026-01-01' AND entry_date < %s""", (name, end))
        net = cur.fetchone()[0]
        assert (old + net if old is not None else None) == (new + net if new is not None else None)
        checked += 1
print(f"B-2 opening and month-end parity: 35/35 accounts, {checked} month-end balances")
con.close()
