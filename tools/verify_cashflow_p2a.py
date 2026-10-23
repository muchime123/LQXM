#!/usr/bin/env python3
"""P2-A 第一阶段（A-0 权限 + A-1 主体字典）只读验收。

前置：8081 已部署含 V404/V405 的新 jar。
覆盖：
  A-0  1) 无 cashflow.read 的员工访问 /api/cashflow-journal/** → 403（存量收口）
       2) 无权限员工访问 /api/cashflow/entities → 403
       3) ADMIN 可读（200）
  A-1  4) entities：items 为空（D1 不预置）、unassignedCount=35=totalAccounts（闭环）
       5) suggest：suggestedCount + unrecognizedCount == 35（闭环），建议不落库
       6) accounts：35 行、entityId 全 NULL（未认领）、status 全 ACTIVE
用法：python tools/verify_cashflow_p2a.py [base_url]   # 默认 http://127.0.0.1:8081
"""
import json
import sys
import urllib.error
import urllib.request

sys.stdout.reconfigure(encoding="utf-8")
BASE = sys.argv[1] if len(sys.argv) > 1 else "http://127.0.0.1:8081"
ADMIN, TRACER = "23", "16"  # 23=管理员(有 cashflow.read)；16=生产跟单员(无)

PASS, FAIL = [], []


def call(path, employee):
    req = urllib.request.Request(BASE + path, headers={"X-Employee-Id": employee})
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    try:
        with opener.open(req, timeout=30) as resp:
            return resp.status, json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        return e.code, None


def check(name, ok, detail=""):
    (PASS if ok else FAIL).append(f"{name}{(' — ' + detail) if detail and not ok else ''}")
    print(("PASS " if ok else "FAIL ") + name + ((" | " + detail) if detail else ""))


def expect(name, employee, path, want_code):
    code, _ = call(path, employee)
    check(name, code == want_code, f"got {code}, want {want_code}")


# ---- A-0 权限收口 ----
expect("A0-1 跟单员(16) 访问资金日记账 accounts → 403", TRACER, "/api/cashflow-journal/accounts?start=2026-10-01&end=2026-10-22", 403)
expect("A0-2 跟单员(16) 访问 /api/cashflow/entities → 403", TRACER, "/api/cashflow/entities", 403)
expect("A0-3 管理员(23) 访问 /api/cashflow/entities → 200", ADMIN, "/api/cashflow/entities", 200)
expect("A0-4 管理员(23) 访问资金日记账 accounts → 200（不回退）",
       ADMIN, "/api/cashflow-journal/accounts?start=2026-10-01&end=2026-10-22", 200)

# ---- A-1 主体字典 ----
code, entities = call("/api/cashflow/entities", ADMIN)
if code == 200 and entities is not None:
    items = entities.get("items", [])
    total, unassigned = entities.get("totalAccounts"), entities.get("unassignedCount")
    check("A1-1 不预置任何主体（D1）", len(items) == 0, f"items={len(items)}")
    check("A1-2 unassigned=total=35（闭环）", total == 35 and unassigned == 35, f"total={total}, unassigned={unassigned}")
else:
    check("A1-1 entities 可读", False, f"code={code}")

code, suggest = call("/api/cashflow/entities/suggest", ADMIN)
if code == 200 and suggest is not None:
    sug, unrec = suggest.get("suggestedCount"), suggest.get("unrecognizedCount")
    check("A1-3 suggest 闭环：建议+待认领=35", sug is not None and sug + unrec == 35, f"{sug}+{unrec}")
    check("A1-4 允许识别不出（不强求 35 户全有建议）", unrec is not None and unrec >= 0, f"unrecognized={unrec}")
else:
    check("A1-3 suggest 可读", False, f"code={code}")

code, accounts = call("/api/cashflow/accounts", ADMIN)
if code == 200 and accounts is not None:
    check("A1-5 accounts=35 行", len(accounts) == 35, f"rows={len(accounts)}")
    null_entity = [a["accountName"] for a in accounts if a.get("entityId") is None]
    active = [a for a in accounts if a.get("status") == "ACTIVE"]
    check("A1-6 未认领前 entityId 全 NULL", len(null_entity) == 35, f"null={len(null_entity)}")
    check("A1-7 status 全 ACTIVE（迁移默认值）", len(active) == 35, f"active={len(active)}")
else:
    check("A1-5 accounts 可读", False, f"code={code}")

print(f"\n==== PASS={len(PASS)} FAIL={len(FAIL)} ====")
for f in FAIL:
    print("  FAIL:", f)
sys.exit(1 if FAIL else 0)
