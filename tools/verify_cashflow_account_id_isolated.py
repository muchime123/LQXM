"""Read-only API checks and one reversible account-name change in the disposable P2-C schema."""
import json
import os
import urllib.request

import pymysql

SCHEMA = "product_archive_p2c_isolated"
BASE = "http://127.0.0.1:18085"
conn = pymysql.connect(host="localhost", user=os.environ.get("DB_USERNAME", "root"),
                       password=os.environ["DB_PASSWORD"], database=SCHEMA, autocommit=True)
opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))


def get(path):
    req = urllib.request.Request(BASE + path, headers={"X-Employee-Id": "23"})
    with opener.open(req, timeout=12) as response:
        return json.loads(response.read())


try:
    with conn.cursor() as cur:
        cur.execute("SELECT account_name FROM h3_cash_accounts WHERE id=687")
        original_name = cur.fetchone()[0]
        cur.execute("SELECT COUNT(*),COUNT(account_id) FROM h3_cashflow_entries")
        assert cur.fetchone() == (8751, 8751)
    period = "start=2026-01-01&end=2026-12-31"
    before = get("/api/cashflow-journal/accounts?" + period)
    original = next(row for row in before["items"] if row["id"] == 687)
    source_before = get("/api/h3-imports/ledger/cashflowJournal/accounts")
    source_original = next(row for row in source_before["items"] if row["id"] == 687)
    entries_before = get("/api/cashflow-journal/entries?accountId=687&" + period)
    assert entries_before["total"] > 0
    try:
        with conn.cursor() as cur:
            cur.execute("UPDATE h3_cash_accounts SET account_name=%s WHERE id=687", (original_name + "（隔离改名）",))
        after = get("/api/cashflow-journal/accounts?" + period)
        renamed = next(row for row in after["items"] if row["id"] == 687)
        source_after = get("/api/h3-imports/ledger/cashflowJournal/accounts")
        source_renamed = next(row for row in source_after["items"] if row["id"] == 687)
        entries_after = get("/api/cashflow-journal/entries?accountId=687&" + period)
        monthly = get("/api/cashflow-journal/accounts/687/monthly?year=2026")
        for field in ("openingBalance", "income", "expense", "closingBalance", "entryCount"):
            assert renamed[field] == original[field], (field, original[field], renamed[field])
        assert entries_after["total"] == entries_before["total"] and len(monthly["items"]) == 12
        assert renamed["accountName"] == original_name + "（隔离改名）"
        for field in ("openingBalance", "income", "expense", "closingBalance", "entryCount"):
            assert source_renamed[field] == source_original[field], (field, source_original[field], source_renamed[field])
        assert source_renamed["accountName"] == renamed["accountName"]
        assert entries_after["items"][0]["account_name"] == original_name  # Raw source label remains intact.
        print(f"account 687 rename isolated: entries={entries_after['total']}; opening/income/expense/closing/count unchanged")
    finally:
        with conn.cursor() as cur:
            cur.execute("UPDATE h3_cash_accounts SET account_name=%s WHERE id=687", (original_name,))
finally:
    conn.close()
