"""Read-only B-1 acceptance; run only after an authorized Flyway migration."""
import json
import os
from decimal import Decimal
from pathlib import Path
import pymysql

backup = json.loads(Path("db_backup_cashflow_b1_20261023.json").read_text(encoding="utf-8"))
expected = {int(row["id"]): Decimal(row["opening_balance"]) for row in backup["rows"]}
assert len(expected) == 35
con = pymysql.connect(host=os.environ.get("DB_HOST", "localhost"),
                      user=os.environ.get("DB_USER", "root"),
                      password=os.environ["DB_PASSWORD"],
                      database=os.environ.get("DB_NAME", "product_archive"))
cur = con.cursor()
cur.execute("SELECT account_id,amount,source,active,finance_confirmed FROM cashflow_account_opening WHERE fiscal_year=2026")
rows = cur.fetchall()
assert len(rows) == 35
seen = set()
for account_id, amount, source, active, confirmed in rows:
    assert account_id in expected and account_id not in seen
    seen.add(account_id)
    assert amount == expected[account_id]
    assert (source, active, confirmed) == ("H3_OPENING", 1, 0)
assert seen == set(expected)
cur.execute("SELECT COUNT(*) FROM h3_cashflow_entries")
assert cur.fetchone()[0] == 8751
print("B-1: 35/35 opening rows, amounts and states match; 8751 entries unchanged")
con.close()
