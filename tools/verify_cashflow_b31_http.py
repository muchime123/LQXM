"""HTTP acceptance against isolated B-3.1 schema; never target production port."""
import concurrent.futures
import json
import uuid
import urllib.request
import urllib.error

BASE = "http://127.0.0.1:18081"
PATH = "/api/cashflow/accounts/opening/manual"
ACCOUNT = 900000001

def post(year, amount="-12.3456", basis="隔离测试依据", key=None, employee="23", account=ACCOUNT):
    body = json.dumps({"accountId": account, "fiscalYear": year, "amount": amount,
                       "basis": basis}, ensure_ascii=False).encode("utf-8")
    headers = {"Content-Type": "application/json", "X-Employee-Id": employee,
               "X-Request-Key": key or "b31-" + str(uuid.uuid4())}
    request = urllib.request.Request(BASE + PATH, body, headers, method="POST")
    try:
        with urllib.request.build_opener(urllib.request.ProxyHandler({})).open(request, timeout=15) as response:
            return response.status, json.loads(response.read())
    except urllib.error.HTTPError as error:
        return error.code, json.loads(error.read())

key = "b31-first-" + str(uuid.uuid4())
first = post(2033, key=key)
replay = post(2033, key=key)
different = post(2033)
assert first[0] == 200 and replay[0] == 200 and different[0] == 409, (first, replay, different)
assert first[1]["openingId"] == replay[1]["openingId"]
assert first[1]["active"] in (1, True) and first[1]["financeConfirmed"] in (0, False)

with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
    same_key = "b31-same-" + str(uuid.uuid4())
    same = list(pool.map(lambda _: post(2034, key=same_key), range(2)))
    distinct = list(pool.map(lambda _: post(2035), range(2)))
assert sorted(status for status, _ in same) == [200, 200], same
assert sorted(status for status, _ in distinct) == [200, 409], distinct

invalid = {
    "precision": post(2030, amount="1.12345"),
    "overflow": post(2030, amount="100000000000000.0000"),
    "year": post(2026),
    "basis": post(2030, basis=""),
    "account": post(2030, account=999999999),
    "no_permission": post(2030, employee="16"),
    "replay_no_permission": post(2033, key=key, employee="16"),
}
assert all(status in (400, 403) for status, _ in invalid.values()), invalid
negative = first[1]["amount"]
assert str(negative).startswith("-12.3456"), negative
print("HTTP first/replay/different:", first[0], replay[0], different[0])
print("HTTP same-key concurrent:", [s for s, _ in same])
print("HTTP different-key concurrent:", [s for s, _ in distinct])
print("HTTP invalid:", {k: v[0] for k, v in invalid.items()})
