"""Probe the live 8081 cashflow code using GET requests only."""
import json
import urllib.error
import urllib.request

BASE = "http://127.0.0.1:8081"
opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))


def get(path):
    request = urllib.request.Request(BASE + path, headers={"X-Employee-Id": "23"})
    try:
        with opener.open(request, timeout=10) as response:
            return response.status, json.loads(response.read())
    except urllib.error.HTTPError as error:
        return error.code, error.read().decode(errors="replace")


years = get("/api/cashflow-journal/fiscal-years")
opening = get("/api/cashflow/accounts/687/opening/2026")
print("fiscal years HTTP:", years[0], "items:", years[1].get("items") if isinstance(years[1], dict) else None)
print("opening HTTP:", opening[0])
if opening[0] == 200 and isinstance(opening[1], dict) and opening[1].get("openingId"):
    opening_id = opening[1]["openingId"]
    history = get(f"/api/cashflow/accounts/opening/{opening_id}/corrections")
    print("correction history HTTP:", history[0], "rows:", len(history[1]) if isinstance(history[1], list) else None)
else:
    print("correction history: skipped; opening endpoint unavailable")
