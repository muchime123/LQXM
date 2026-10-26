"""B-3.2 real HTTP acceptance on port 18083 and disposable isolated database only."""
import concurrent.futures
import json
import os
import uuid
import urllib.error
import urllib.request

import pymysql

DB = "product_archive_b32_isolated"
BASE = "http://127.0.0.1:18083/api/cashflow/accounts/opening"
conn = pymysql.connect(host="localhost", user=os.environ.get("DB_USERNAME", "root"),
                       password=os.environ["DB_PASSWORD"], database=DB, autocommit=True)
cur = conn.cursor()
opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))

def opening(year):
    cur.execute("SELECT id FROM cashflow_account_opening WHERE account_id=900000001 AND fiscal_year=%s", (year,))
    return cur.fetchone()[0]

ids = {year: opening(year) for year in range(2040, 2047)}

def post(year, key=None, employee="7", balance="12.3456", date=None, ref="B32-EXT-001", note="已核对原件"):
    body = json.dumps({"evidenceType": "BANK_STATEMENT", "balanceAsOfDate": date or f"{year-1}-12-31",
                       "verifiedBalance": balance, "evidenceRef": ref, "note": note}).encode()
    request = urllib.request.Request(f"{BASE}/{ids.get(year, 999999999)}/finance-confirm", body,
        {"Content-Type": "application/json", "X-Employee-Id": employee,
         "X-Request-Key": key or "b32-" + str(uuid.uuid4())}, method="POST")
    try:
        with opener.open(request, timeout=15) as response:
            return response.status, json.loads(response.read())
    except urllib.error.HTTPError as error:
        return error.code, json.loads(error.read())

key = "b32-first-" + str(uuid.uuid4())
first = post(2040, key=key)
replay = post(2040, key=key)
duplicate = post(2040)
assert (first[0], replay[0], duplicate[0]) == (200, 200, 409), (first, replay, duplicate)
assert first[1]["evidenceId"] == replay[1]["evidenceId"]

with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
    same_key = "b32-same-" + str(uuid.uuid4())
    same = list(pool.map(lambda _: post(2041, key=same_key), range(2)))
    distinct = list(pool.map(lambda _: post(2042), range(2)))
assert sorted(code for code, _ in same) == [200, 200], same
assert sorted(code for code, _ in distinct) == [200, 409], distinct

invalid = {
    "candidate": post(2043),
    "mismatch": post(2044, balance="12.3457"),
    "bad_date": post(2044, date="2044-02-01"),
    "precision": post(2044, balance="12.34567"),
    "missing_ref": post(2044, ref=" "),
    "missing_opening": post(9999),
    "admin_no_confirm_permission": post(2045, employee="23"),
    "other_no_permission": post(2045, employee="16"),
    "replay_no_permission": post(2040, key=key, employee="23"),
}
expected = {"candidate": 409, "mismatch": 409, "bad_date": 400, "precision": 400,
            "missing_ref": 400, "missing_opening": 404,
            "admin_no_confirm_permission": 403, "other_no_permission": 403,
            "replay_no_permission": 403}
assert {case: result[0] for case, result in invalid.items()} == expected, invalid

cur.execute("SELECT COUNT(*) FROM cashflow_opening_confirmation")
evidence_count = cur.fetchone()[0]
cur.execute("SELECT COUNT(*) FROM request_idempotency WHERE action='cashflow.opening.confirm'")
idem_count = cur.fetchone()[0]
cur.execute("SELECT COUNT(*) FROM audit_logs WHERE entity_type='cashflow_account_opening' AND action='finance_confirm'")
audit_count = cur.fetchone()[0]
cur.execute("SELECT COUNT(*) FROM cashflow_account_opening WHERE fiscal_year BETWEEN 2040 AND 2046 AND finance_confirmed=1")
confirmed_count = cur.fetchone()[0]
assert (evidence_count, idem_count, audit_count, confirmed_count) == (3, 3, 3, 3), (evidence_count, idem_count, audit_count, confirmed_count)
print("first/replay/duplicate:", first[0], replay[0], duplicate[0])
print("same-key concurrent:", [r[0] for r in same])
print("different-key concurrent:", [r[0] for r in distinct])
print("invalid/authorization:", {case: result[0] for case, result in invalid.items()})
print("isolated confirmed/evidence/idempotency/audit:", confirmed_count, evidence_count, idem_count, audit_count)
conn.close()
