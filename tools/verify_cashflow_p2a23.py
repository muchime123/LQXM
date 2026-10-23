#!/usr/bin/env python3
"""P2-A 第二阶段（A-2 主体筛选 + A-3 停用）活体验收——写路径，全程 API 自清理。

前置：8081 已部署 A-2/A-3 新 jar；已通过 tools/verify_cashflow_p2a.py（只读 11 项）。
流程：
  1. 建「验收测试主体」（幂等：存在即复用）
  2. 认领 1 个账户 → /cashflow/accounts 与 /cashflow-journal/accounts 行均带 entityId/entityName
  3. A-2 闭环：该主体 accountCount==1；journal 全量中该 entityId 命中恰 1 行（主体筛选=行级字段同源）
  4. A-3 核心：停用该账户 → journal /accounts 仍 35 行、该行 income/expense/closingBalance 与停用前逐位一致
  5. 清理：撤销认领 → 停用还原 → 删除主体 → /cashflow/accounts 恢复 entityId 全 NULL
用法：python tools/verify_cashflow_p2a23.py [base_url]
"""
import json
import sys
import urllib.error
import urllib.request

sys.stdout.reconfigure(encoding="utf-8")
BASE = sys.argv[1] if len(sys.argv) > 1 else "http://127.0.0.1:8081"
ADMIN = "23"
ENTITY_NAME = "验收测试主体"

PASS, FAIL = [], []


def call(method, path, employee=ADMIN, body=None):
    req = urllib.request.Request(BASE + path, method=method,
                                 headers={"X-Employee-Id": employee, "Content-Type": "application/json"},
                                 data=json.dumps(body).encode() if body is not None else None)
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    try:
        with opener.open(req, timeout=30) as resp:
            raw = resp.read().decode("utf-8")
            return resp.status, json.loads(raw) if raw else {}
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8", "replace")
        try:
            return e.code, json.loads(raw)
        except Exception:
            return e.code, {"raw": raw}


def check(name, ok, detail=""):
    (PASS if ok else FAIL).append(name)
    print(("PASS " if ok else "FAIL ") + name + ((" | " + detail) if detail else ""))


def journal_accounts():
    code, data = call("GET", "/api/cashflow-journal/accounts?start=2026-10-01&end=2026-10-22")
    assert code == 200, f"journal accounts {code}"
    return data["items"]


def find_entity():
    code, data = call("GET", "/api/cashflow/entities")
    for item in data.get("items", []):
        if item["name"] == ENTITY_NAME:
            return item["id"]
    code, created = call("POST", "/api/cashflow/entities",
                         body={"name": ENTITY_NAME, "entityType": "OTHER", "note": "A-2/A-3 验收用，验收后删除"})
    check("W-1 创建验收主体", code in (200, 201), f"code={code} {created}")
    return created.get("id")


before = journal_accounts()
entity_id = find_entity()
if entity_id is None:
    print("FATAL: 无法获得验收主体"); sys.exit(1)

# 认领第一个账户
target = before[0]
code, res = call("POST", "/api/cashflow/accounts/assign-entity",
                 body={"assignments": [{"accountId": target["id"], "entityId": entity_id}]})
check("W-2 认领账户", code == 200 and res.get("assigned") == 1, f"code={code} {res}")

after_assign = journal_accounts()
hit = [r for r in after_assign if r.get("entityId") == entity_id]
check("A2-1 journal 行带 entityId 且唯一命中", len(hit) == 1 and hit[0]["id"] == target["id"],
      f"hits={len(hit)}")
check("A2-2 行带 entityName", hit and hit[0].get("entityName") == ENTITY_NAME,
      f"entityName={hit[0].get('entityName') if hit else None}")

code, accs = call("GET", "/api/cashflow/accounts")
mine = [a for a in accs if a["id"] == target["id"]]
check("A2-3 /cashflow/accounts 行带 entityName", mine and mine[0].get("entityName") == ENTITY_NAME)

code, ents = call("GET", "/api/cashflow/entities")
ent = [e for e in ents["items"] if e["id"] == entity_id]
check("A2-4 主体 accountCount 闭环=1", ent and ent[0]["accountCount"] == 1,
      f"count={ent[0]['accountCount'] if ent else None}")
unassigned_after = ents["unassignedCount"]
check("A2-5 unassignedCount 同步减一", unassigned_after == 34, f"unassigned={unassigned_after}")

# ---- A-3：停用不影响历史统计 ----
row_before = next(r for r in after_assign if r["id"] == target["id"])
code, _ = call("PUT", f"/api/cashflow/accounts/{target['id']}/status", body={"status": "INACTIVE"})
check("A3-1 停用可调用", code == 200)

after_inactive = journal_accounts()
row_after = next((r for r in after_inactive if r["id"] == target["id"]), None)
check("A3-2 停用账户仍在历史汇总中", row_after is not None)
same = row_after is not None and all(
    row_before[k] == row_after[k] for k in ("income", "expense", "closingBalance", "entryCount"))
check("A3-3 停用前后该行收入/支出/期末/笔数逐位一致", same,
      f"before={ {k: str(row_before[k]) for k in ('income','expense','closingBalance')} } "
      f"after={ {k: str(row_after[k]) for k in ('income','expense','closingBalance')} if row_after else None }")
check("A3-4 全表仍 35 行且汇总不变",
      len(after_inactive) == 35 and
      sum(r["income"] for r in after_inactive) == sum(r["income"] for r in after_assign))
code, accs2 = call("GET", "/api/cashflow/accounts")
check("A3-5 /cashflow/accounts 默认仍返回停用户（管理侧由前端隐藏）",
      any(a["id"] == target["id"] and a["status"] == "INACTIVE" for a in accs2))

# ---- 清理 ----
code, _ = call("PUT", f"/api/cashflow/accounts/{target['id']}/status", body={"status": "ACTIVE"})
code, _ = call("POST", "/api/cashflow/accounts/assign-entity",
               body={"assignments": [{"accountId": target["id"], "entityId": None}]})
code, _ = call("DELETE", f"/api/cashflow/entities/{entity_id}")
check("C-1 清理：删除验收主体", code == 200, f"code={code}")
code, accs3 = call("GET", "/api/cashflow/accounts")
check("C-2 清理后 entityId 全 NULL、status 全 ACTIVE",
      all(a["entityId"] is None and a["status"] == "ACTIVE" for a in accs3))

print(f"\n==== PASS={len(PASS)} FAIL={len(FAIL)} ====")
for f in FAIL:
    print("  FAIL:", f)
sys.exit(1 if FAIL else 0)
