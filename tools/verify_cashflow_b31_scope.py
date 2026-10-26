"""Check permission and CASHFLOW data-scope gates on replay in isolated schema."""
import json
import os
import urllib.request
import urllib.error
import pymysql

con = pymysql.connect(host=os.environ.get("DB_HOST", "localhost"),
                      user=os.environ.get("DB_USER", "root"), password=os.environ["DB_PASSWORD"],
                      database="product_archive_b31_isolated", autocommit=True)
cur = con.cursor()
cur.execute("""SELECT i.request_key FROM request_idempotency i
JOIN cashflow_account_opening o ON CAST(i.result_ref AS UNSIGNED)=o.id
WHERE i.action='cashflow.opening.manual' AND o.account_id=900000001 AND o.fiscal_year=2033
ORDER BY i.id DESC LIMIT 1""")
key = cur.fetchone()[0]
body = json.dumps({"accountId": 900000001, "fiscalYear": 2033,
                   "amount": "-12.3456", "basis": "隔离测试依据"}, ensure_ascii=False).encode()
def status(employee):
    req = urllib.request.Request("http://127.0.0.1:18081/api/cashflow/accounts/opening/manual",
        body, {"Content-Type": "application/json", "X-Employee-Id": employee,
               "X-Request-Key": key}, method="POST")
    try:
        with urllib.request.build_opener(urllib.request.ProxyHandler({})).open(req, timeout=15) as res:
            return res.status
    except urllib.error.HTTPError as error:
        return error.code

unauthorized = status("16")
cur.execute("""SELECT g.role_id,g.data_scope FROM system_role_feature_grants g
JOIN employee_roles er ON er.role_id=g.role_id WHERE er.employee_id=23 AND g.feature_code='CASHFLOW'""")
grants = cur.fetchall()
assert grants
try:
    for role_id, _ in grants:
        cur.execute("UPDATE system_role_feature_grants SET data_scope='SELF' WHERE role_id=%s AND feature_code='CASHFLOW'", (role_id,))
    scoped = status("23")
finally:
    for role_id, original in grants:
        cur.execute("UPDATE system_role_feature_grants SET data_scope=%s WHERE role_id=%s AND feature_code='CASHFLOW'", (original, role_id))
assert unauthorized == 403 and scoped == 403, (unauthorized, scoped)
print("replay without permission / restricted scope HTTP:", unauthorized, scoped)
con.close()
