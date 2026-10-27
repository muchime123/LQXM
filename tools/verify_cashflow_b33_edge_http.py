"""B-3.3 concurrency, confirmation history and audit rollback; isolated schema only."""
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


def post(path, body, key, employee=7):
    req = urllib.request.Request(BASE + path, json.dumps(body).encode(),
        {"Content-Type": "application/json", "X-Employee-Id": str(employee), "X-Request-Key": key}, method="POST")
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    try:
        with opener.open(req, timeout=20) as response:
            return response.status, json.loads(response.read())
    except urllib.error.HTTPError as error:
        return error.code, error.read().decode(errors="replace")


def opening(year):
    cur.execute("SELECT id,amount,finance_confirmed FROM cashflow_account_opening WHERE account_id=687 AND fiscal_year=%s", (year,))
    return cur.fetchone()


def request(oid, old, new, key):
    return post(f"/{oid}/corrections", {"expectedOldAmount": old, "newAmount": new,
        "reason": "隔离边界测试", "evidenceRef": "B33-EDGE-20271027"}, key)


def apply(cid, key):
    return post(f"/corrections/{cid}/apply", {}, key)


try:
    for year in (2028, 2029, 2030, 2031):
        if opening(year):
            if str(opening(year)[1]) != "10.0000" or (year != 2028 and opening(year)[2] != 0):
                raise RuntimeError(f"isolated opening {year} has unexpected state; refusing overwrite")
            continue
        cur.execute("""INSERT INTO cashflow_account_opening
            (account_id,fiscal_year,amount,source,active,finance_confirmed,basis)
            VALUES(687,%s,10.0000,'MANUAL_OPENING',1,0,'B33 isolated edge')""", (year,))

    # One historical confirmation is retained after the applied correction.
    confirmed_id = opening(2028)[0]
    cur.execute("UPDATE cashflow_account_opening SET finance_confirmed=1,confirmed_by=7,confirmed_at=NOW() WHERE id=%s", (confirmed_id,))
    cur.execute("SELECT id FROM cashflow_opening_confirmation WHERE opening_id=%s AND evidence_ref='B33-OLD-REF'", (confirmed_id,))
    existing = cur.fetchone()
    if existing:
        old_confirmation_id = existing[0]
    else:
        old_ref = "b33-edge-old-" + str(uuid.uuid4())
        cur.execute("""INSERT INTO cashflow_opening_confirmation
            (opening_id,opening_amount_snapshot,evidence_type,balance_as_of_date,verified_balance,
             evidence_ref,note,confirmed_by,confirmed_by_name,request_key)
            VALUES(%s,10.0000,'BANK_STATEMENT','2027-12-31',10.0000,
                   'B33-OLD-REF','隔离旧确认',7,'隔离财务',%s)""", (confirmed_id, old_ref))
        cur.execute("SELECT id FROM cashflow_opening_confirmation WHERE request_key=%s", (old_ref,))
        old_confirmation_id = cur.fetchone()[0]
    proposal = request(confirmed_id, "10.0000", "11.0000", "b33-confirmed-" + str(uuid.uuid4()))
    assert proposal[0] == 200 and proposal[1]["originalConfirmationId"] == old_confirmation_id, proposal
    assert apply(proposal[1]["id"], "b33-confirmed-apply-" + str(uuid.uuid4()))[0] == 200
    assert str(opening(2028)[1]) == "11.0000" and opening(2028)[2] == 0
    cur.execute("SELECT COUNT(*) FROM cashflow_opening_confirmation WHERE id=%s AND opening_amount_snapshot=10.0000 AND evidence_ref='B33-OLD-REF'", (old_confirmation_id,))
    assert cur.fetchone()[0] == 1
    reconfirm = post(f"/{confirmed_id}/finance-confirm", {
        "evidenceType": "BANK_STATEMENT", "balanceAsOfDate": "2027-12-31",
        "verifiedBalance": "11.0000", "evidenceRef": "B33-NEW-REF", "note": "新金额重新核对"
    }, "b33-reconfirm-" + str(uuid.uuid4()))
    assert reconfirm[0] == 200 and opening(2028)[2] == 1, reconfirm
    cur.execute("SELECT COUNT(*) FROM cashflow_opening_confirmation WHERE opening_id=%s", (confirmed_id,))
    assert cur.fetchone()[0] == 2

    # Same key concurrent requests should both replay the one persisted correction.
    same_id = opening(2029)[0]
    same_key = "b33-same-" + str(uuid.uuid4())
    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
        pair = list(pool.map(lambda _: request(same_id, "10.0000", "12.0000", same_key), range(2)))
    assert sorted(item[0] for item in pair) == [200, 200], pair
    assert pair[0][1]["id"] == pair[1][1]["id"]
    assert apply(pair[0][1]["id"], "b33-same-apply-" + str(uuid.uuid4()))[0] == 200

    # Distinct keys race for the same business record: only one pending proposal.
    different_id = opening(2030)[0]
    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
        different = list(pool.map(lambda n: request(different_id, "10.0000", "13.0000",
            "b33-different-" + str(uuid.uuid4())), range(2)))
    assert sorted(item[0] for item in different) == [200, 409], different
    winner = next(item[1]["id"] for item in different if item[0] == 200)
    assert apply(winner, "b33-different-apply-" + str(uuid.uuid4()))[0] == 200

    # Force audit insert failure. The amount, correction state and idempotency key must all roll back.
    rollback_id = opening(2031)[0]
    rollback_proposal = request(rollback_id, "10.0000", "14.0000", "b33-rollback-request-" + str(uuid.uuid4()))
    assert rollback_proposal[0] == 200, rollback_proposal
    correction_id = rollback_proposal[1]["id"]
    apply_key = "b33-rollback-apply-" + str(uuid.uuid4())
    cur.execute("""CREATE TRIGGER b33_isolated_fail_audit BEFORE INSERT ON audit_logs
        FOR EACH ROW BEGIN
          IF NEW.action='correction_apply' THEN
            SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='b33 isolated audit failure';
          END IF;
        END""")
    try:
        failed = apply(correction_id, apply_key)
        assert failed[0] >= 400, failed
        assert str(opening(2031)[1]) == "10.0000"
        cur.execute("SELECT status FROM cashflow_opening_correction WHERE id=%s", (correction_id,))
        assert cur.fetchone()[0] == "PENDING"
        cur.execute("SELECT COUNT(*) FROM request_idempotency WHERE BINARY request_key=BINARY %s", (apply_key,))
        assert cur.fetchone()[0] == 0
    finally:
        cur.execute("DROP TRIGGER IF EXISTS b33_isolated_fail_audit")
    assert apply(correction_id, apply_key)[0] == 200
    print("B3.3 edge: confirmed history retained/reconfirmed; same-key concurrent=200/200; different-key=200/409; audit failure rolled back amount/correction/idempotency")
finally:
    cur.execute("DROP TRIGGER IF EXISTS b33_isolated_fail_audit")
    conn.close()
