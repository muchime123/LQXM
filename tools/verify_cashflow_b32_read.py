"""Read-only HTTP checks against the B-3.2 isolated backend on port 18083."""
import json
import urllib.error
import urllib.request

BASE = "http://127.0.0.1:18083/api/cashflow/accounts"
OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({}))


def get(path: str, employee: str = "7"):
    request = urllib.request.Request(BASE + path, headers={"X-Employee-Id": employee})
    try:
        with OPENER.open(request, timeout=15) as response:
            return response.status, json.loads(response.read())
    except urllib.error.HTTPError as error:
        return error.code, json.loads(error.read())


status, opening = get("/900000001/opening/2040")
assert status == 200 and opening["openingStatus"] == "ACTIVE", (status, opening)
opening_id = opening["openingId"]
status, history = get(f"/opening/{opening_id}/confirmations")
assert status == 200 and len(history) == 1, (status, history)
record = history[0]
assert record["evidenceRef"] == "B32-EXT-001", record
assert record["openingAmountSnapshot"] == record["verifiedBalance"] == opening["amount"], record
assert record["confirmedByName"] and record["confirmedAt"], record

status, empty = get("/900000001/opening/2044")
assert status == 200 and empty["openingId"], (status, empty)
status, records = get(f"/opening/{empty['openingId']}/confirmations")
assert status == 200 and records == [], (status, records)

status, _ = get("/opening/999999999/confirmations")
assert status == 404, status
status, _ = get(f"/opening/{opening_id}/confirmations", employee="16")
assert status == 403, status

print("isolated read: confirmed 200/1, unconfirmed 200/0, missing 404, unauthorized 403")
