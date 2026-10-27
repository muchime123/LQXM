"""Read-only B-3.3 preview acceptance against the disposable B-4 database."""
import json
import os
import urllib.error
import urllib.parse
import urllib.request

import pymysql

SCHEMA = "product_archive_b4_isolated"
BASE = "http://127.0.0.1:18084/api/cashflow/accounts/opening"
conn = pymysql.connect(host="localhost", user=os.environ.get("DB_USERNAME", "root"),
                       password=os.environ["DB_PASSWORD"], database=SCHEMA)
opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))


def snapshot():
    with conn.cursor() as cur:
        cur.execute("SELECT id,account_id,fiscal_year,amount,active,finance_confirmed FROM cashflow_account_opening ORDER BY id")
        openings = cur.fetchall()
        counts = []
        for table in ("request_idempotency", "audit_logs", "cashflow_opening_confirmation", "h3_cashflow_entries"):
            cur.execute(f"SELECT COUNT(*) FROM `{table}`")
            counts.append(cur.fetchone()[0])
        return openings, counts


def preview(opening_id, amount, employee=23):
    query = urllib.parse.urlencode({"newAmount": amount})
    request = urllib.request.Request(f"{BASE}/{opening_id}/correction-preview?{query}",
                                     headers={"X-Employee-Id": str(employee)})
    try:
        with opener.open(request, timeout=10) as response:
            return response.status, json.loads(response.read())
    except urllib.error.HTTPError as error:
        return error.code, json.loads(error.read())


try:
    before = snapshot()
    with conn.cursor() as cur:
        cur.execute("SELECT id FROM cashflow_account_opening WHERE account_id=687 AND fiscal_year=2027")
        opening_id = cur.fetchone()[0]
    ok = preview(opening_id, "124.4567")
    same = preview(opening_id, "123.4567")
    precision = preview(opening_id, "123.45678")
    missing = preview(999999999, "124.4567")
    assert ok[0] == 200, ok
    assert ok[1]["oldAmount"] == "123.4567" and ok[1]["newAmount"] == "124.4567", ok
    assert ok[1]["balanceDelta"] == "1.0000" and ok[1]["applied"] is False, ok
    assert ok[1]["affectedFrom"] == "2027-01-01" and ok[1]["affectedThrough"] == "2027-12-31", ok
    assert (same[0], precision[0], missing[0]) == (400, 400, 400), (same, precision, missing)
    assert before == snapshot(), "read-only preview changed isolated database"
    print("B3.3 preview: success=200, same/precision/missing=400; delta=1.0000; database unchanged")
finally:
    conn.close()
