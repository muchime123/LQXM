"""Additional isolated HTTP rejection cases; no successful writes expected."""
import json
import uuid
import urllib.error
import urllib.request

url = "http://127.0.0.1:18081/api/cashflow/accounts/opening/manual"
opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))

def status(amount, year=2036, basis="isolated basis"):
    data = json.dumps({"accountId": 900000001, "fiscalYear": year,
                       "amount": amount, "basis": basis}).encode()
    request = urllib.request.Request(url, data, {"Content-Type": "application/json",
        "X-Employee-Id": "23", "X-Request-Key": "b31-edge-" + str(uuid.uuid4())}, method="POST")
    try:
        with opener.open(request, timeout=15) as response:
            return response.status
    except urllib.error.HTTPError as error:
        return error.code

cases = {"null_amount": status(None), "empty_amount": status(""),
         "negative_year": status("1.0000", year=-1),
         "year_overflow": status("1.0000", year=10000),
         "basis_blank": status("1.0000", basis="  ")}
assert all(code == 400 for code in cases.values()), cases
print(cases)
