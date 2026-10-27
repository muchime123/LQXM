"""B-3.3 write acceptance against product_archive_b4_isolated only."""
import concurrent.futures
import json
import os
import uuid
import urllib.error
import urllib.request

import pymysql

SCHEMA = "product_archive_b4_isolated"
BASE = "http://127.0.0.1:18084/api/cashflow/accounts/opening"
conn = pymysql.connect(host="localhost", user=os.environ.get("DB_USERNAME", "root"),
                       password=os.environ["DB_PASSWORD"], database=SCHEMA, autocommit=True)
cur = conn.cursor()
opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))


def http(path, body=None, key=None, employee=7):
    headers = {"X-Employee-Id": str(employee)}
    if key:
        headers["X-Request-Key"] = key
    if body is not None:
        headers["Content-Type"] = "application/json"
    request = urllib.request.Request(BASE + path,
        data=json.dumps(body).encode() if body is not None else None,
        headers=headers, method="POST" if body is not None or path.endswith("/apply") else "GET")
    try:
        with opener.open(request, timeout=15) as response:
            return response.status, json.loads(response.read())
    except urllib.error.HTTPError as error:
        raw = error.read()
        try:
            return error.code, json.loads(raw)
        except ValueError:
            return error.code, raw.decode(errors="replace")


def counts():
    values = []
    for table in ("cashflow_opening_correction", "request_idempotency", "audit_logs"):
        cur.execute(f"SELECT COUNT(*) FROM `{table}`")
        values.append(cur.fetchone()[0])
    return tuple(values)


def opening(year):
    cur.execute("SELECT id,amount,finance_confirmed FROM cashflow_account_opening WHERE account_id=687 AND fiscal_year=%s", (year,))
    return cur.fetchone()


def payload(old, new):
    return {"expectedOldAmount": old, "newAmount": new,
            "reason": "隔离库更正验收", "evidenceRef": "B33-TEST-20271027"}


try:
    assert str(opening(2027)[1]) == "123.4567", opening(2027)
    opening_id = opening(2027)[0]
    before = counts()
    key = "b33-request-" + str(uuid.uuid4())
    first = http(f"/{opening_id}/corrections", payload("123.4567", "124.4567"), key)
    replay = http(f"/{opening_id}/corrections", payload("123.4567", "124.4567"), key)
    pending_conflict = http(f"/{opening_id}/corrections", payload("123.4567", "125.4567"), "b33-other-" + str(uuid.uuid4()))
    assert (first[0], replay[0], pending_conflict[0]) == (200, 200, 409), (first, replay, pending_conflict)
    correction_id = first[1]["id"]
    assert replay[1]["id"] == correction_id and str(opening(2027)[1]) == "123.4567", (first, replay, opening(2027))
    assert counts() == (before[0] + 1, before[1] + 1, before[2] + 1), counts()
    unauthorized = http(f"/{opening_id}/corrections", payload("123.4567", "124.4567"), key, employee=23)
    assert unauthorized[0] == 403, unauthorized

    apply_key = "b33-apply-" + str(uuid.uuid4())
    applied = http(f"/corrections/{correction_id}/apply", {}, apply_key)
    apply_replay = http(f"/corrections/{correction_id}/apply", {}, apply_key)
    assert (applied[0], apply_replay[0]) == (200, 200), (applied, apply_replay)
    assert applied[1]["status"] == "APPLIED" and apply_replay[1]["id"] == correction_id
    assert str(opening(2027)[1]) == "124.4567" and opening(2027)[2] == 0
    assert counts() == (before[0] + 1, before[1] + 2, before[2] + 2), counts()
    history = http(f"/{opening_id}/corrections")
    assert history[0] == 200 and history[1][0]["id"] == correction_id, history
    print("B3.3 isolated: request/replay=200/200; pending conflict=409; unauthorized replay=403; apply/replay=200/200; amount=124.4567; counts +1 correction/+2 idempotency/+2 audit")
finally:
    conn.close()
