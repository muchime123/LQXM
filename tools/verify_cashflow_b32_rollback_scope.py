"""Isolated B-3.2 replay scope and audit-failure transaction checks."""
import json
import os
import uuid
import urllib.error
import urllib.request

import pymysql

conn = pymysql.connect(host="localhost", user=os.environ.get("DB_USERNAME", "root"),
                       password=os.environ["DB_PASSWORD"], database="product_archive_b32_isolated",
                       autocommit=True)
cur = conn.cursor()
opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))

def post(opening_id, year, key):
    body = json.dumps({"evidenceType": "BANK_STATEMENT", "balanceAsOfDate": f"{year-1}-12-31",
                       "verifiedBalance": "12.3456", "evidenceRef": "B32-EXT-001",
                       "note": "已核对原件"}).encode()
    req = urllib.request.Request(f"http://127.0.0.1:18083/api/cashflow/accounts/opening/{opening_id}/finance-confirm",
        body, {"Content-Type": "application/json", "X-Employee-Id": "7", "X-Request-Key": key}, method="POST")
    try:
        with opener.open(req, timeout=15) as response:
            return response.status
    except urllib.error.HTTPError as error:
        return error.code

cur.execute("""SELECT o.id,c.request_key FROM cashflow_account_opening o
JOIN cashflow_opening_confirmation c ON c.opening_id=o.id WHERE o.fiscal_year=2040""")
opening_id, old_key = cur.fetchone()
cur.execute("""SELECT g.role_id,g.data_scope FROM system_role_feature_grants g
JOIN employee_roles er ON er.role_id=g.role_id
WHERE er.employee_id=7 AND g.feature_code='CASHFLOW'""")
grants = cur.fetchall()
assert grants
try:
    for role_id, _ in grants:
        cur.execute("UPDATE system_role_feature_grants SET data_scope='SELF' WHERE role_id=%s AND feature_code='CASHFLOW'", (role_id,))
    scoped_replay = post(opening_id, 2040, old_key)
finally:
    for role_id, old_scope in grants:
        cur.execute("UPDATE system_role_feature_grants SET data_scope=%s WHERE role_id=%s AND feature_code='CASHFLOW'", (old_scope, role_id))
assert scoped_replay == 403, scoped_replay

cur.execute("SELECT id FROM cashflow_account_opening WHERE fiscal_year=2046 AND account_id=900000001")
rollback_id = cur.fetchone()[0]
key = "b32-audit-fail-" + str(uuid.uuid4())
cur.execute("RENAME TABLE audit_logs TO audit_logs_b32_disabled")
try:
    audit_failure = post(rollback_id, 2046, key)
finally:
    cur.execute("RENAME TABLE audit_logs_b32_disabled TO audit_logs")
cur.execute("SELECT finance_confirmed FROM cashflow_account_opening WHERE id=%s", (rollback_id,))
confirmed = cur.fetchone()[0]
cur.execute("SELECT COUNT(*) FROM cashflow_opening_confirmation WHERE opening_id=%s", (rollback_id,))
evidence_count = cur.fetchone()[0]
cur.execute("SELECT COUNT(*) FROM request_idempotency WHERE request_key=%s", (key,))
idem_count = cur.fetchone()[0]
assert (audit_failure, confirmed, evidence_count, idem_count) == (500, 0, 0, 0), (
    audit_failure, confirmed, evidence_count, idem_count)
print("restricted-scope replay HTTP:", scoped_replay)
print("audit failure HTTP / confirmed / evidence / idempotency:", audit_failure, confirmed, evidence_count, idem_count)
conn.close()
