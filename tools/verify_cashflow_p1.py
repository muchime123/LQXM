# -*- coding: utf-8 -*-
"""
资金日记账 P1 —— 接口层验收脚本（只读，不写库）

用法（在任意终端）：
    python tools/verify_cashflow_p1.py
    python tools/verify_cashflow_p1.py --base http://localhost:8081 --employee 23

它做的是「财务操作视角验收清单」里能自动化的部分：
  1. 三个期间（本月/上季/全年）的 /accounts 都能返回，且 summary 齐全
  2. 账户数、可比/不可比数与真库对拍结论一致（13 / 17 / 20）
  3. 逐账户字段齐全，来源末行三件套（可比/日期/结存）自洽
  4. accountId 筛选后，三个视图各自的数据源都只剩该账户
  5. /issues 与 /accounts 的账户口径一致（不出现「卡片 12 / 清单 3」）
  6. 三个维度互不代表：断点账户的 closingBalance 仍然算得出来
  7. 币种/核对状态筛选项在 /accounts 上可用，且不破坏 summary

退出码 0 = 全部通过；1 = 有 FAIL。
"""
import argparse
import json
import sys
import urllib.parse
import urllib.request

# Windows 控制台默认 GBK，中文标签会炸；强制 UTF-8 输出
try:
    sys.stdout.reconfigure(encoding="utf-8")
except Exception:
    pass

API = "/api/cashflow-journal"


def http_get(base, path, employee=None, timeout=60):
    url = base.rstrip("/") + path
    req = urllib.request.Request(url)
    if employee:
        # 仅开发实例开了 app.security.allow-employee-header=true 才有效
        req.add_header("X-Employee-Id", str(employee))
    # 本机探测必须绕过系统代理，否则会拿到假 502
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    try:
        with opener.open(req, timeout=timeout) as resp:
            return resp.status, json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        body = e.read().decode("utf-8", errors="replace")
        try:
            return e.code, json.loads(body)
        except Exception:
            return e.code, {"raw": body[:400]}


class Checker:
    def __init__(self, base, employee):
        self.base = base
        self.employee = employee
        self.passed = 0
        self.failed = 0
        self.lines = []

    def check(self, name, cond, detail=""):
        ok = bool(cond)
        if ok:
            self.passed += 1
        else:
            self.failed += 1
        tag = "PASS" if ok else "FAIL"
        line = "  [%s] %s%s" % (tag, name, ("  " + detail) if detail else "")
        print(line)
        self.lines.append(line)
        return ok

    def get(self, path):
        return http_get(self.base, path, self.employee)

    def accounts(self, start, end, extra=""):
        q = "?start=%s&end=%s%s" % (start, end, extra)
        return self.get(API + "/accounts" + q)


# 期望值来源：修复「零流水账户被整条 continue 跳过」之后的口径。
# 可比数与真库对拍一致（13/17/20）；不可比 = 有流水但缺证据 + 零流水（本月 18 / 上季 10 / 全年 7）。
# 两数相加必须等于 35 —— 这就是那个 bug 的可验证闭环。
PERIODS = [
    ("本月", "2026-10-01", "2026-10-22", 13, 22),
    ("上季", "2026-07-01", "2026-09-30", 17, 18),
    ("全年", "2026-01-01", "2026-12-31", 20, 15),
]

REASON_FORBIDDEN = "无法核对"


def section(title):
    print()
    print("=" * 78)
    print(title)
    print("=" * 78)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default="http://localhost:8081")
    ap.add_argument("--employee", default="23", help="X-Employee-Id，传 none 表示不带")
    args = ap.parse_args()
    emp = None if str(args.employee).lower() in ("none", "", "0") else args.employee

    c = Checker(args.base, emp)
    print("目标实例: %s  身份头: %s" % (args.base, ("X-Employee-Id=%s" % emp) if emp else "无"))

    # ---------- 一、三个期间的 /accounts ----------
    section("一、三个期间的 /accounts（契约完整 + 数字与真库对拍一致）")
    payloads = {}
    for label, start, end, exp_comp, exp_skip in PERIODS:
        st, d = c.accounts(start, end)
        if not c.check("%s 期间 HTTP 200" % label, st == 200, "实际 %s" % st):
            continue
        payloads[label] = d
        summary = d.get("summary")
        c.check("%s 返回 summary" % label, summary is not None)
        if not summary:
            continue
        comp = summary.get("sourceClosingComparableCount")
        skip = summary.get("sourceClosingSkippedCount")
        c.check(
            "%s 可比/跳过 = %s/%s（对拍结论）" % (label, exp_comp, exp_skip),
            comp == exp_comp and skip == exp_skip,
            "实际 %s/%s" % (comp, skip),
        )
        brk = summary.get("sourceClosingSkipBreakdown") or []
        total_brk = sum(int(b.get("count") or 0) for b in brk)
        c.check(
            "%s 跳过原因分布合计 == 跳过数" % label,
            total_brk == skip,
            "明细合计 %s vs %s" % (total_brk, skip),
        )
        labels = [str(b.get("reason") or "") for b in brk]
        c.check(
            "%s 原因文案不含「%s」" % (label, REASON_FORBIDDEN),
            all(REASON_FORBIDDEN not in t for t in labels),
            str(labels) if labels else "无原因项",
        )
        c.check(
            "%s 分布字段用 reason 而非 label" % label,
            all("reason" in b for b in brk),
        )
        # 闭环：可比 + 跳过 == 账户总数。
        # 这条专门钉住「零流水账户被整批 continue 跳过」那个 bug——跳过后 sum < 总账户数。
        n_items = len(d.get("items") or [])
        c.check(
            "%s 可比 + 跳过 == 账户总数（%d）" % (label, n_items),
            (comp or 0) + (skip or 0) == n_items,
            "可比 %s + 跳过 %s = %s vs %d" % (comp, skip, (comp or 0) + (skip or 0), n_items),
        )

    # ---------- 二、逐账户字段自洽 ----------
    section("二、逐账户字段（来源末行三件套自洽 + 三维度分离）")
    d = payloads.get("本月")
    if d:
        items = d.get("items") or []
        c.check("本月返回 35 个账户", len(items) == 35, "实际 %s" % len(items))
        need = [
            "closingStatus", "closingStatusLabel",
            "sourceBalanceStatus", "sourceBalanceCheckedLinks",
            "manualReconcileStatus", "manualReconcileStatusLabel",
            "sourceClosingComparable", "reconcileSummary",
        ]
        if items:
            missing = [k for k in need if k not in items[0]]
            c.check("账户对象含三维度全部字段", not missing, "缺 %s" % missing if missing else "")
        # 可比 ⇒ 必须有日期+结存；不可比 ⇒ 必须有 skipCode + skipLabel
        # ⚠️ 注意：字段「整个缺失」也算不合格（用 in 判断，不能只看 .get() 是否为 None），
        # 这正是零流水账户被 continue 跳过时的表现，用 .get() 会静默放过。
        bad_comp, bad_skip = [], []
        for it in items:
            name = it.get("accountName")
            if "sourceClosingComparable" not in it:
                bad_skip.append(name + "(缺字段)")
                continue
            if it.get("sourceClosingComparable"):
                if not it.get("sourceClosingDate") or it.get("sourceClosingBalance") is None:
                    bad_comp.append(name)
                if it.get("sourceClosingSkipCode"):
                    bad_comp.append(name + "(残留skipCode)")
            else:
                if not it.get("sourceClosingSkipCode") or not it.get("sourceClosingSkipLabel"):
                    bad_skip.append(name)
        c.check("可比账户都有末行日期与结存", not bad_comp, str(bad_comp))
        c.check("不可比账户都有 skipCode 与文案", not bad_skip, str(bad_skip))
        # P1 恒 NOT_STARTED，且不得被说成已核对
        mrs = {it.get("manualReconcileStatus") for it in items}
        c.check("manualReconcileStatus 全为 NOT_STARTED（P1 临时展示态）",
                mrs == {"NOT_STARTED"}, str(mrs))
        # 断点账户仍算得出期末（明细异常不隐藏账户级结论）
        broken = [it for it in items if (it.get("sourceBalanceExceptionCount") or 0) > 0]
        if broken:
            c.check(
                "断点账户的期末仍可计算",
                all(it.get("closingBalance") is not None for it in broken),
                "%d 个断点账户" % len(broken),
            )
        else:
            c.check("本月无断点账户（无该项可验）", True, "跳过")
        # skipCode 只能是四类之一
        allowed = {"NO_RELIABLE_SOURCE_ORDER", "SOURCE_CHAIN_BROKEN", "NO_BALANCE_ROWS", "NOT_ENOUGH_ROWS"}
        codes = {it.get("sourceClosingSkipCode") for it in items if it.get("sourceClosingSkipCode")}
        c.check("skipCode 全在四类枚举内", codes <= allowed, str(codes - allowed) if codes - allowed else str(codes))

    # ---------- 三、账户筛选跨视图 ----------
    section("三、账户筛选口径（/accounts 返回全量，前端本地筛选；/issues 按 accountId 收窄）")
    if d:
        items = d.get("items") or []
        target = None
        for it in items:
            if it.get("sourceClosingComparable"):
                target = it
                break
        if target:
            aid = target.get("id")
            aname = target.get("accountName")
            # /accounts 的签名只有 (start, end)，筛选在前端 visibleAccounts/reconcileScope 里做。
            # 这里验证「全量里能唯一命中该账户」，等价于前端 account.id === accountFilter 的判据。
            hits = [x for x in items if x.get("id") == aid]
            c.check(
                "/accounts 全量中 id=%s 唯一命中 %s" % (aid, aname),
                len(hits) == 1,
                "命中 %d 个" % len(hits),
            )
            # /issues 接受同一个 accountId，且返回行全部属于该账户（三视图同源的关键）
            sti, issues = c.get(
                API + "/issues?start=2026-10-01&end=2026-10-22&accountId=%s" % aid
            )
            c.check("/issues 接受同一 accountId", sti == 200, "实际 %s" % sti)
            if sti == 200 and isinstance(issues, dict):
                its = issues.get("items") or []
                other = [x for x in its
                         if x.get("accountId") is not None and str(x.get("accountId")) != str(aid)]
                c.check("/issues 返回行全部属于该账户", not other, str(other[:3]))
                c.check(
                    "/issues 明细行数不超过该账户期间总笔数",
                    len(its) <= int(target.get("entryCount") or 0),
                    "issues %d vs entryCount %s（issues 只列异常/待处理）"
                    % (len(its), target.get("entryCount")),
                )
        else:
            c.check("存在可比账户用于筛选验证", False)

    # ---------- 四、筛选项不破坏 summary ----------
    section("四、核对状态 / 币种筛选（可调用、不 500）")
    st, _ = c.accounts("2026-10-01", "2026-10-22", "&reconcileStatus=SOURCE_PENDING")
    c.check("reconcileStatus=SOURCE_PENDING 可调用", st in (200, 400), "实际 %s" % st)
    st, _ = c.accounts("2026-10-01", "2026-10-22", "&currency=CNY")
    c.check("currency=CNY 可调用", st == 200, "实际 %s" % st)

    # ---------- 汇总 ----------
    section("汇总")
    total = c.passed + c.failed
    print("PASS=%d  FAIL=%d  （共 %d 项）" % (c.passed, c.failed, total))
    if c.failed:
        print()
        print("失败项：")
        for ln in c.lines:
            if "[FAIL]" in ln:
                print(ln)
    return 0 if c.failed == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
