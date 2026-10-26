"""Isolated-schema audit rollback and disabled-account HTTP checks."""
import json
import os
import uuid
import urllib.request
import urllib.error
import pymysql

DB = "product_archive_b31_isolated"
con = pymysql.connect(host=os.environ.get("DB_HOST", "localhost"),
                      user=os.environ.get("DB_USER", "root"), password=os.environ["DB_PASSWORD"],
                      database=DB, autocommit=True)
cur = con.cursor()
opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))

def post(year, key, employee="23"):
    body = json.dumps({"accountId": 900000001, "fiscalYear": year,
                       "amount": "5.0000", "basis": "隔离验收"}).encode()
    request = urllib.request.Request("http://127.0.0.1:18081/api/cashflow/accounts/opening/manual",
        body, {"Content-Type": "application/json", "X-Employee-Id": employee,
               "X-Request-Key": key}, method="POST")
    try:
        with opener.open(request, timeout=15) as response:
            return response.status
    except urllib.error.HTTPError as error:
        return error.code

key = "b31-audit-fail-" + str(uuid.uuid4())
cur.execute("RENAME TABLE audit_logs TO audit_logs_b31_disabled")
try:
    audit_status = post(2031, key)
finally:
    cur.execute("RENAME TABLE audit_logs_b31_disabled TO audit_logs")
cur.execute("SELECT COUNT(*) FROM cashflow_account_opening WHERE account_id=900000001 AND fiscal_year=2031")
opening_count = cur.fetchone()[0]
cur.execute("SELECT COUNT(*) FROM request_idempotency WHERE request_key=%s", (key,))
idem_count = cur.fetchone()[0]
assert audit_status >= 400 and opening_count == 0 and idem_count == 0, (audit_status, opening_count, idem_count)

cur.execute("UPDATE h3_cash_accounts SET status='INACTIVE' WHERE id=900000001")
try:
    inactive_status = post(2032, "b31-inactive-" + str(uuid.uuid4()))
finally:
    cur.execute("UPDATE h3_cash_accounts SET status='ACTIVE' WHERE id=900000001")
assert inactive_status == 400, inactive_status
print("audit failure HTTP/status and rollback counts:", audit_status, opening_count, idem_count)
print("inactive account HTTP:", inactive_status)
con.close()
