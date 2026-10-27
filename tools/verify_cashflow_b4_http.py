"""Read-only B-4 HTTP acceptance against the disposable 18084 isolated backend."""
import json
import urllib.error
import urllib.parse
import urllib.request

BASE = "http://127.0.0.1:18084/api/cashflow-journal"
OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({}))


def get(path):
    request = urllib.request.Request(BASE + path, headers={"X-Employee-Id": "23"})
    try:
        with OPENER.open(request, timeout=60) as response:
            return response.status, json.loads(response.read())
    except urllib.error.HTTPError as error:
        return error.code, json.loads(error.read())


status, years = get("/fiscal-years")
assert status == 200 and years["items"] == [2027, 2026], (status, years)

status, report = get("/accounts?start=2027-01-01&end=2027-12-31")
assert status == 200 and len(report["items"]) == 35, (status, report)
active = [row for row in report["items"] if row["openingStatus"] == "ACTIVE"]
missing = [row for row in report["items"] if row["openingStatus"] == "MISSING"]
assert len(active) == 1 and len(missing) == 34, (len(active), len(missing))
assert float(active[0]["openingBalance"]) == float(active[0]["closingBalance"]) == 123.4567
assert all(row["openingBalance"] is None and row["closingBalance"] is None for row in missing)
assert report["summary"]["closingComputableCount"] == 1
assert report["summary"]["closingBlockedCount"] == 34
assert sum(row["unknownOpeningCount"] for row in report["totals"]) == 34

account_id = active[0]["id"]
status, monthly = get(f"/accounts/{account_id}/monthly?year=2027")
assert status == 200 and monthly["year"] == 2027 and len(monthly["items"]) == 12
assert all(float(row["closingBalance"]) == 123.4567 for row in monthly["items"])

status, entries = get("/entries?start=2027-01-01&end=2027-12-31")
assert status == 200 and entries["total"] == 0
status, issues = get("/issues?start=2027-01-01&end=2027-12-31")
assert status == 200 and sum(row["issueCode"] == "MISSING_OPENING" for row in issues["items"]) == 34

status, _ = get("/accounts?start=2026-12-31&end=2027-01-01")
assert status == 400, status
status, _ = get("/accounts?start=2028-01-01&end=2028-12-31")
assert status == 400, status
status, _ = get(f"/accounts/{account_id}/monthly?year=2028")
assert status == 400, status

print("B4 isolated: years 2027/2026; 2027 active/missing=1/34; monthly=12; entries=0; missing issues=34; cross-year/unavailable=400")
