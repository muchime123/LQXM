# -*- coding: utf-8 -*-
"""
从 MySQL 数据库提取 20 张代表性销售出库单，用于和财务对账。
覆盖场景：正常回款/部分回款/未回款、高/低/负毛利、有费用、有调整、成本异常、
        员工未映射、客户未匹配、回款异常、退货行、历史订单、大额订单等。

用法：python scripts/extract_sample_orders_for_finance.py
输出：LQXM/财务对账_代表性出库单_YYYYMMDD.html  （浏览器打开可打印）
      LQXM/财务对账_代表性出库单_YYYYMMDD.csv   （Excel 可直接打开）
"""

import csv
import datetime as dt
import html
import os
import sys

try:
    import pymysql
except ImportError:
    print("需要 pymysql。正在安装……")
    os.system(f'"{sys.executable}" -m pip install pymysql')
    import pymysql

# ============ 数据库连接 ============
DB_HOST = "localhost"
DB_PORT = 3306
DB_USER = "root"
DB_PWD = "123456"
DB_NAME = "product_archive"
DB_CHARSET = "utf8mb4"

OUTPUT_DIR = r"c:\Users\20676\source\LQXM"
TODAY = dt.date.today().strftime("%Y%m%d")

# ============ 场景定义（每个场景最多抽几张，保证总量约 20，避免重复） ============
# 每个场景：(场景编号, 场景名称, SQL WHERE 条件, 抽取数量, 排序)
SCENARIOS = [
    # —— 基础：回款三段式 ——
    ("S01", "正常-全额回款(已收=应收)",
     "o.outbound_status='CONFIRMED' AND COALESCE(o.sales_amount,0)>0 AND COALESCE(o.purchase_amount,0)>0 "
     "AND ABS(COALESCE(o.received_amount,0)-COALESCE(o.sales_amount,0))<0.02",
     2, "o.ship_date DESC"),
    ("S02", "正常-部分回款(有已收有未收)",
     "o.outbound_status='CONFIRMED' AND COALESCE(o.sales_amount,0)>0 AND COALESCE(o.purchase_amount,0)>0 "
     "AND COALESCE(o.received_amount,0)>0 AND COALESCE(o.outstanding_amount,0)>0",
     2, "o.ship_date DESC"),
    ("S03", "正常-尚未回款(已收=0)",
     "o.outbound_status='CONFIRMED' AND COALESCE(o.sales_amount,0)>0 AND COALESCE(o.purchase_amount,0)>0 "
     "AND COALESCE(o.received_amount,0)<0.02 AND COALESCE(o.outstanding_amount,0)>0.02",
     2, "o.ship_date DESC"),
    # —— 毛利梯度 ——
    ("S04", "高毛利(毛利率>30%)",
     "o.outbound_status='CONFIRMED' AND COALESCE(o.sales_amount,0)>0 AND COALESCE(o.purchase_amount,0)>0 "
     "AND (o.sales_amount-o.purchase_amount)/o.sales_amount>0.30",
     2, "o.ship_date DESC"),
    ("S05", "低毛利(毛利率<10%且>0)",
     "o.outbound_status='CONFIRMED' AND COALESCE(o.sales_amount,0)>0 AND COALESCE(o.purchase_amount,0)>0 "
     "AND (o.sales_amount-o.purchase_amount)/o.sales_amount BETWEEN 0.0001 AND 0.10",
     1, "o.ship_date DESC"),
    ("S06", "零/负毛利(毛利<=0)",
     "o.outbound_status='CONFIRMED' AND COALESCE(o.sales_amount,0)>0 AND COALESCE(o.purchase_amount,0)>=COALESCE(o.sales_amount,0)",
     2, "o.ship_date DESC"),
    # —— 费用与调整 ——
    ("S07", "有确认订单费用(已归集)",
     "o.outbound_status='CONFIRMED' AND EXISTS("
     "SELECT 1 FROM expense_allocations a "
     "WHERE a.target_type='SALES_OUTBOUND' AND a.target_id=o.id AND a.allocation_status='CONFIRMED')",
     2, "o.ship_date DESC"),
    ("S08", "有利润调整记录",
     "o.outbound_status='CONFIRMED' AND EXISTS("
     "SELECT 1 FROM sales_outbound_profit_adjustments a WHERE a.sales_outbound_id=o.id AND a.status='ACTIVE')",
     1, "o.ship_date DESC"),
    # —— 异常场景（需要财务拍板处理方式）——
    ("S09", "采购成本异常(为空/零/负)",
     "o.outbound_status='CONFIRMED' AND COALESCE(o.sales_amount,0)>0 AND COALESCE(o.purchase_amount,0)<=0",
     1, "o.ship_date DESC"),
    ("S10", "员工未映射(employee_id NULL)",
     "o.outbound_status='CONFIRMED' AND o.employee_id IS NULL",
     1, "o.ship_date DESC"),
    ("S11", "客户未匹配(customer_id NULL)",
     "o.outbound_status='CONFIRMED' AND o.customer_id IS NULL",
     1, "o.ship_date DESC"),
    ("S12", "回款金额校验异常(已收+未收≠应收)",
     "o.outbound_status='CONFIRMED' AND COALESCE(o.sales_amount,0)>0 "
     "AND ABS(COALESCE(o.received_amount,0)+COALESCE(o.outstanding_amount,0)-COALESCE(o.sales_amount,0))>0.02",
     1, "o.ship_date DESC"),
    ("S13", "包含退货/冲销行(明细有负数量)",
     "o.outbound_status='CONFIRMED' AND EXISTS("
     "SELECT 1 FROM sales_outbound_items i WHERE i.sales_outbound_id=o.id AND i.quantity<0)",
     1, "o.ship_date DESC"),
    # —— 时间与规模 ——
    ("S14", "历史订单(2026年前发货)",
     "o.outbound_status='CONFIRMED' AND COALESCE(o.ship_date,o.business_date)<'2026-01-01'",
     1, "o.ship_date DESC"),
    ("S15", "大额订单(销售额TOP级别)",
     "o.outbound_status='CONFIRMED' AND COALESCE(o.sales_amount,0)>0 AND COALESCE(o.purchase_amount,0)>0",
     1, "o.sales_amount DESC"),
]


def connect():
    return pymysql.connect(
        host=DB_HOST, port=DB_PORT, user=DB_USER, password=DB_PWD,
        database=DB_NAME, charset=DB_CHARSET, cursorclass=pymysql.cursors.DictCursor
    )


def query_one(conn, sql, *args):
    with conn.cursor() as c:
        c.execute(sql, args)
        return c.fetchone()


def query_all(conn, sql, *args):
    with conn.cursor() as c:
        c.execute(sql, args)
        return c.fetchall()


def m(v, d=""):
    """安全格式化值：None → d"""
    if v is None:
        return d
    return v


def money(v, decimals=2):
    if v is None:
        return "-"
    try:
        n = float(v)
    except (TypeError, ValueError):
        return str(v)
    return f"{n:,.{decimals}f}"


def pct(v, decimals=2):
    if v is None:
        return "-"
    try:
        n = float(v)
    except (TypeError, ValueError):
        return str(v)
    return f"{n:.{decimals}f}%"


# ============ 主流程 ============
def main():
    conn = connect()
    print(f"✓ 已连接 {DB_HOST}:{DB_PORT}/{DB_NAME}")

    selected_ids = set()  # 去重
    rows = []  # [{场景, 场景序号, 订单...}]

    BASE_SELECT = """
    SELECT o.id, o.outbound_no, o.ship_date, o.business_date,
           o.customer_id, o.customer_name, o.employee_id, o.salesperson_name, o.department_name,
           o.sales_amount, o.purchase_amount, o.source_gross_profit,
           o.received_amount, o.outstanding_amount, o.payment_status, o.outbound_status,
           p.accounting_profit AS sys_accounting_profit,
           p.product_gross_profit AS sys_product_gross_profit,
           p.confirmed_expense_amount AS sys_confirmed_expense,
           p.adjustment_amount AS sys_adjustment,
           p.commissionable_profit AS sys_commissionable_profit,
           p.cost_abnormal AS sys_cost_abnormal,
           c.theoretical_commission AS sys_theoretical_commission,
           c.receipt_ratio_pct AS sys_receipt_ratio_pct,
           c.status AS sys_commission_status
    FROM sales_outbounds o
    LEFT JOIN sales_outbound_profits p ON p.sales_outbound_id = o.id
    LEFT JOIN sales_outbound_commissions c ON c.sales_outbound_id = o.id
    """

    for scode, sname, where, limit, orderby in SCENARIOS:
        exc_ids = ",".join(["0"] + [str(x) for x in selected_ids])
        sql = (
            f"{BASE_SELECT} WHERE ({where}) AND o.id NOT IN ({exc_ids})"
            f" ORDER BY {orderby} LIMIT {limit}"
        )
        result = query_all(conn, sql)
        for r in result:
            selected_ids.add(r["id"])
            r["_scene_code"] = scode
            r["_scene_name"] = sname
            # 计算财务手工对账字段（空栏，留给财务手填）
            r["fin_sales"] = ""
            r["fin_purchase"] = ""
            r["fin_gross"] = ""
            r["fin_expense"] = ""
            r["fin_adjust"] = ""
            r["fin_profit"] = ""
            r["fin_commission_base"] = ""
            r["fin_commission_rate"] = ""
            r["fin_commission"] = ""
            r["fin_receipt"] = ""
            r["fin_remark"] = ""
            rows.append(r)
        print(f"  {scode} {sname}: 抽到 {len(result)} 张（limit={limit}）")

    if not rows:
        print("✗ 一张出库单都没抽到，请确认数据库里 sales_outbounds 表是否有数据。")
        sys.exit(1)

    print(f"\n★ 合计抽取 {len(rows)} 张代表性出库单，覆盖 {len({r['_scene_code'] for r in rows})} 个场景")

    # ============ 补充：每张订单的费用明细 + 产品明细（用于详情抽屉） ============
    detail_cache = {}  # id -> {expenses:[], items:[]}
    for r in rows:
        oid = r["id"]
        expenses = query_all(conn, """
            SELECT a.allocated_amount, a.match_method, a.allocation_status,
                   c.claim_no, c.title, c.expense_category, c.claim_date,
                   c.submitter_name_snapshot
            FROM expense_allocations a
            JOIN expense_claims c ON c.id = a.expense_claim_id
            WHERE a.target_type='SALES_OUTBOUND' AND a.target_id=%s
            ORDER BY a.id
        """, oid)
        items = query_all(conn, """
            SELECT sequence_no, product_name, product_code, specification, unit, quantity,
                   sales_unit_price, sales_total, purchase_unit_price, purchase_total, source_profit
            FROM sales_outbound_items WHERE sales_outbound_id=%s
            ORDER BY COALESCE(sequence_no, 999999), id
        """, oid)
        adjustments = query_all(conn, """
            SELECT adjustment_type, direction, amount, affects_commission, reason, status, created_by
            FROM sales_outbound_profit_adjustments WHERE sales_outbound_id=%s ORDER BY id DESC
        """, oid)
        detail_cache[oid] = dict(expenses=expenses, items=items, adjustments=adjustments)

    conn.close()

    # ============ 输出 CSV（Excel 打开） ============
    csv_path = os.path.join(OUTPUT_DIR, f"财务对账_代表性出库单_{TODAY}.csv")
    CSV_COLS = [
        ("场景编号", "_scene_code"),
        ("场景说明", "_scene_name"),
        ("出库单号", "outbound_no"),
        ("发货日期", "ship_date"),
        ("业务员", "salesperson_name"),
        ("部门", "department_name"),
        ("客户名称", "customer_name"),
        ("出库状态", "outbound_status"),
        ("【系统】销售额", "sales_amount"),
        ("【系统】采购成本", "purchase_amount"),
        ("【系统】产品毛利", "sys_product_gross_profit"),
        ("【系统】订单费用", "sys_confirmed_expense"),
        ("【系统】利润调整", "sys_adjustment"),
        ("【系统】订单利润", "sys_accounting_profit"),
        ("【系统】可计提利润", "sys_commissionable_profit"),
        ("【系统】已收金额", "received_amount"),
        ("【系统】未收金额", "outstanding_amount"),
        ("【系统】回款率%", "sys_receipt_ratio_pct"),
        ("【系统】理论提成", "sys_theoretical_commission"),
        ("【系统】提成状态", "sys_commission_status"),
        ("【系统】成本异常", "sys_cost_abnormal"),
        ("【财务填写】确认销售额", "fin_sales"),
        ("【财务填写】确认采购成本", "fin_purchase"),
        ("【财务填写】确认毛利", "fin_gross"),
        ("【财务填写】确认订单费用", "fin_expense"),
        ("【财务填写】确认利润调整", "fin_adjust"),
        ("【财务填写】确认订单利润", "fin_profit"),
        ("【财务填写】提成基数", "fin_commission_base"),
        ("【财务填写】提成比例%", "fin_commission_rate"),
        ("【财务填写】确认提成额", "fin_commission"),
        ("【财务填写】确认已回款", "fin_receipt"),
        ("【财务填写】差异/备注", "fin_remark"),
    ]
    with open(csv_path, "w", encoding="utf-8-sig", newline="") as f:
        w = csv.writer(f)
        w.writerow([c[0] for c in CSV_COLS])
        for r in rows:
            row = []
            for label, key in CSV_COLS:
                row.append(m(r.get(key)))
            w.writerow(row)
    print(f"✓ CSV 已生成：{csv_path}")

    # ============ 输出 HTML（浏览器打开可打印，美观对账清单） ============
    html_path = os.path.join(OUTPUT_DIR, f"财务对账_代表性出库单_{TODAY}.html")
    now = dt.datetime.now().strftime("%Y-%m-%d %H:%M")

    def fmt_date(v):
        if v is None:
            return "-"
        if isinstance(v, (dt.date, dt.datetime)):
            return v.strftime("%Y-%m-%d")
        return str(v)[:10]

    # 汇总统计
    total_sales = sum(float(r["sales_amount"] or 0) for r in rows)
    total_cost = sum(float(r["purchase_amount"] or 0) for r in rows)
    total_received = sum(float(r["received_amount"] or 0) for r in rows)
    total_sys_profit = sum(float(r["sys_accounting_profit"] or 0) for r in rows)
    total_sys_commission = sum(float(r["sys_theoretical_commission"] or 0) for r in rows)

    # ============== 生成 HTML 主体 ==============
    parts = []
    parts.append(f"""<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="UTF-8" />
<title>财务对账 · 代表性销售出库单样本 ({TODAY})</title>
<style>
  * {{ box-sizing: border-box; }}
  body {{
    font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", "PingFang SC",
                 "Hiragino Sans GB", "Microsoft YaHei", sans-serif;
    margin: 0; padding: 24px 28px; color: #1f2937; background: #fafafa;
    font-size: 13px; line-height: 1.5;
  }}
  h1 {{ margin: 0 0 4px; font-size: 22px; }}
  .sub {{ color: #6b7280; margin-bottom: 20px; }}
  .summary-box {{
    display: grid; grid-template-columns: repeat(6, 1fr); gap: 12px;
    background: #fff; border: 1px solid #e5e7eb; border-radius: 8px;
    padding: 16px 20px; margin-bottom: 20px;
  }}
  .summary-box .k {{ color: #6b7280; font-size: 12px; }}
  .summary-box .v {{ font-size: 18px; font-weight: 600; margin-top: 4px; }}
  .summary-box .v.pos {{ color: #059669; }}
  .summary-box .v.neg {{ color: #dc2626; }}
  .summary-box .v.warn {{ color: #d97706; }}

  .legend {{
    background: #fffbeb; border: 1px solid #fde68a; border-radius: 8px;
    padding: 12px 18px; margin-bottom: 22px; color: #92400e;
  }}
  .legend b {{ color: #78350f; }}
  .legend ul {{ margin: 6px 0 0 18px; padding: 0; }}
  .legend li {{ margin: 2px 0; }}

  h2 {{
    margin: 28px 0 12px; padding-left: 10px; border-left: 4px solid #2563eb;
    font-size: 16px;
  }}

  table {{ width: 100%; border-collapse: collapse; background: #fff; margin-bottom: 24px; }}
  thead th {{
    background: #1f2937; color: #fff; text-align: left;
    padding: 8px 10px; font-weight: 500; font-size: 12px;
    border: 1px solid #1f2937; white-space: nowrap;
  }}
  thead th.fin {{ background: #7c3aed; border-color: #7c3aed; }}
  tbody td {{ padding: 7px 10px; border: 1px solid #e5e7eb; vertical-align: top; }}
  tbody tr:nth-child(even) {{ background: #f9fafb; }}
  tbody tr:hover {{ background: #eff6ff; }}
  td.num {{ text-align: right; font-variant-numeric: tabular-nums; white-space: nowrap; }}
  td.num.neg {{ color: #dc2626; }}
  td.tag {{
    display: inline-block; padding: 2px 8px; border-radius: 10px;
    font-size: 11px; margin-right: 4px; white-space: nowrap;
  }}
  .tag-S01,.tag-S02,.tag-S03 {{ background: #dcfce7; color: #166534; }}
  .tag-S04,.tag-S05,.tag-S06 {{ background: #dbeafe; color: #1e3a8a; }}
  .tag-S07,.tag-S08 {{ background: #e0e7ff; color: #3730a3; }}
  .tag-S09,.tag-S10,.tag-S11,.tag-S12,.tag-S13 {{ background: #fee2e2; color: #991b1b; }}
  .tag-S14,.tag-S15 {{ background: #fef3c7; color: #92400e; }}

  .fin-cell {{
    background: repeating-linear-gradient(
      45deg, #f5f3ff, #f5f3ff 6px, #ede9fe 6px, #ede9fe 12px
    );
    min-width: 90px;
  }}
  .remark-cell {{ min-width: 160px; }}

  /* 详情抽屉样式（每张订单一行详情） */
  details.order-detail summary {{
    cursor: pointer; padding: 4px 8px; font-weight: 600; color: #2563eb;
    list-style: none;
  }}
  details.order-detail summary::-webkit-details-marker {{ display: none; }}
  details.order-detail summary::before {{ content: "▶ "; font-size: 10px; color: #9ca3af; }}
  details[open].order-detail summary::before {{ content: "▼ "; color: #2563eb; }}
  .detail-inner {{
    margin: 6px 8px 10px; padding: 10px 14px;
    background: #f8fafc; border: 1px dashed #cbd5e1; border-radius: 6px;
    font-size: 12px;
  }}
  .detail-inner h4 {{ margin: 10px 0 6px; font-size: 12px; color: #334155; }}
  .detail-inner table {{ margin: 0 0 10px; font-size: 11px; }}
  .detail-inner th {{ background: #475569; padding: 4px 8px; font-size: 11px; border-color: #475569; }}
  .detail-inner td {{ padding: 3px 8px; }}

  .sign-area {{
    margin-top: 40px; display: grid; grid-template-columns: repeat(3, 1fr);
    gap: 24px; padding-top: 20px; border-top: 1px dashed #9ca3af;
  }}
  .sign-area .col {{ text-align: center; }}
  .sign-area .line {{
    border-bottom: 1px solid #1f2937; width: 80%; margin: 0 auto 6px; height: 40px;
  }}

  @media print {{
    body {{ padding: 12px; background: #fff; font-size: 11px; }}
    .summary-box {{ break-inside: avoid; }}
    table {{ break-inside: auto; }}
    .legend {{ break-inside: avoid; }}
    details, details>summary {{ display: block; }}
    details[open] .detail-inner {{ break-inside: avoid; }}
  }}
</style>
</head>
<body>
<h1>📋 财务对账 · 代表性销售出库单样本</h1>
<div class="sub">
  生成时间：{now}　|　样本来源：{DB_NAME}.sales_outbounds　|　样本数：{len(rows)} 张 / 覆盖 {len({r['_scene_code'] for r in rows})} 个业务场景
</div>

<div class="summary-box">
  <div><div class="k">样本 销售总额</div><div class="v">¥{money(total_sales)}</div></div>
  <div><div class="k">样本 采购成本总额</div><div class="v warn">¥{money(total_cost)}</div></div>
  <div><div class="k">样本 产品毛利总额</div><div class="v pos">¥{money(total_sales - total_cost)}</div></div>
  <div><div class="k">样本 系统订单利润</div><div class="v {'pos' if total_sys_profit >= 0 else 'neg'}">¥{money(total_sys_profit)}</div></div>
  <div><div class="k">样本 已回款总额</div><div class="v">¥{money(total_received)}</div></div>
  <div><div class="k">样本 理论提成合计</div><div class="v warn">¥{money(total_sys_commission)}</div></div>
</div>

<div class="legend">
  <b>📌 使用说明：</b>
  <ul>
    <li>「系统」列 = 当前系统按硬编码公式算出的值（<b>未经财务确认</b>，可能全错）。</li>
    <li>「财务填写」列（<span style="background:#ede9fe;padding:0 6px;">紫色斜纹</span>）请财务手写填写，是<b>唯一权威</b>口径。</li>
    <li>点击每行出库单号左侧的 ▶ 可展开该订单的<b>产品明细 / 费用归集明细 / 利润调整记录</b>。</li>
    <li>「差异/备注」列请财务标注系统值与财务账的差异原因，这就是后续改代码的依据。</li>
    <li>建议对完后财务<b>签字确认</b>，作为系统今后利润核算口径的基准验收样本。</li>
  </ul>
</div>
""")

    # ============== 场景分组展示 ==============
    # 按场景分组，以便财务批量对比同场景的处理
    from collections import OrderedDict
    groups = OrderedDict()
    for r in rows:
        key = (r["_scene_code"], r["_scene_name"])
        groups.setdefault(key, []).append(r)

    for (scode, sname), grp in groups.items():
        parts.append(f"<h2>{scode} · {sname}（{len(grp)} 张）</h2>")
        parts.append("""
<table>
<thead>
<tr>
  <th width="40">#</th>
  <th>场景</th>
  <th>出库单号 / 详情</th>
  <th>发货日期</th>
  <th>业务员 / 部门</th>
  <th>客户名称</th>
  <th class="num">系统<br/>销售额</th>
  <th class="num">系统<br/>采购成本</th>
  <th class="num">系统<br/>产品毛利</th>
  <th class="num">系统<br/>订单费用</th>
  <th class="num">系统<br/>利润调整</th>
  <th class="num">系统<br/>订单利润</th>
  <th class="num">系统<br/>已收</th>
  <th class="num">系统<br/>回款率</th>
  <th class="num fin">财务<br/>确认销售额</th>
  <th class="num fin">财务<br/>确认成本</th>
  <th class="num fin">财务<br/>确认毛利</th>
  <th class="num fin">财务<br/>确认费用</th>
  <th class="num fin">财务<br/>确认利润</th>
  <th class="num fin">财务<br/>确认提成</th>
  <th class="fin remark-cell">差异 / 备注</th>
</tr>
</thead>
<tbody>
""")
        for idx, r in enumerate(grp, 1):
            oid = r["id"]
            det = detail_cache[oid]
            sales = float(r["sales_amount"] or 0)
            cost = float(r["purchase_amount"] or 0)
            gross = sales - cost
            sys_profit = r.get("sys_accounting_profit") or 0
            sys_profit_f = float(sys_profit)
            cls_neg = ' neg' if gross < 0 else ''
            cls_pn = ' neg' if sys_profit_f < 0 else (' pos' if sys_profit_f > 0 else '')

            # 构建详情抽屉 HTML
            items_rows = ""
            if det["items"]:
                items_rows = "<h4>📦 产品明细（销售/采购金额）</h4><table><thead><tr>"
                items_rows += "<th>行号</th><th>产品名称/规格</th><th class='num'>数量</th>"
                items_rows += "<th class='num'>销售单价</th><th class='num'>销售小计</th>"
                items_rows += "<th class='num'>采购单价</th><th class='num'>采购小计</th>"
                items_rows += "<th class='num'>源毛利</th></tr></thead><tbody>"
                for it in det["items"]:
                    q = it["quantity"] or 0
                    if isinstance(q, (int, float)) and q < 0:
                        qc = ' style="color:#dc2626"'
                    else:
                        qc = ''
                    items_rows += f"<tr><td>{m(it['sequence_no'],'-')}</td>"
                    items_rows += f"<td>{html.escape(str(m(it['product_name'],'-')))}<br/><small style='color:#6b7280'>{html.escape(str(m(it['product_code'],'')))} {html.escape(str(m(it['specification'],'')))}</small></td>"
                    items_rows += f"<td class='num'{qc}>{m(it['quantity'],'-')}</td>"
                    items_rows += f"<td class='num'>{money(it['sales_unit_price'])}</td>"
                    items_rows += f"<td class='num'>{money(it['sales_total'])}</td>"
                    items_rows += f"<td class='num'>{money(it['purchase_unit_price'])}</td>"
                    items_rows += f"<td class='num'>{money(it['purchase_total'])}</td>"
                    items_rows += f"<td class='num'>{money(it['source_profit'])}</td></tr>"
                items_rows += "</tbody></table>"
            else:
                items_rows = "<h4>📦 产品明细</h4><p style='color:#9ca3af;margin:0'>— 无明细数据 —</p>"

            exp_rows = ""
            if det["expenses"]:
                exp_rows = "<h4>💳 费用归集明细</h4><table><thead><tr>"
                exp_rows += "<th>报销单号</th><th>费用类别</th><th>标题</th><th>日期</th><th>提交人</th>"
                exp_rows += "<th class='num'>金额</th><th>匹配方式</th><th>状态</th></tr></thead><tbody>"
                for e in det["expenses"]:
                    st = e["allocation_status"] or ''
                    if st == "CONFIRMED":
                        st_tag = '<span style="color:#059669">已确认✓</span>'
                    elif st == "PENDING":
                        st_tag = '<span style="color:#d97706">待确认</span>'
                    elif st == "DRAFT":
                        st_tag = '<span style="color:#6b7280">草稿</span>'
                    else:
                        st_tag = f'<span>{st}</span>'
                    exp_rows += f"<tr><td>{m(e['claim_no'],'-')}</td>"
                    exp_rows += f"<td>{m(e['expense_category'],'-')}</td>"
                    exp_rows += f"<td>{html.escape(str(m(e['title'],'-')))}</td>"
                    exp_rows += f"<td>{fmt_date(e['claim_date'])}</td>"
                    exp_rows += f"<td>{m(e['submitter_name_snapshot'],'-')}</td>"
                    exp_rows += f"<td class='num'>{money(e['allocated_amount'])}</td>"
                    exp_rows += f"<td>{m(e['match_method'],'-')}</td>"
                    exp_rows += f"<td>{st_tag}</td></tr>"
                exp_rows += "</tbody></table>"
            else:
                exp_rows = "<h4>💳 费用归集明细</h4><p style='color:#9ca3af;margin:0'>— 未归集任何费用 —</p>"

            adj_rows = ""
            if det["adjustments"]:
                adj_rows = "<h4>✏️ 利润调整记录</h4><table><thead><tr>"
                adj_rows += "<th>类型</th><th>方向</th><th class='num'>金额</th><th>参与提成</th>"
                adj_rows += "<th>原因</th><th>状态</th><th>操作人</th></tr></thead><tbody>"
                for a in det["adjustments"]:
                    d = a.get("direction") or ''
                    dtag = f'<span style="color:#059669">+PLUS增利</span>' if d == 'PLUS' else (
                        f'<span style="color:#dc2626">−MINUS减利</span>' if d == 'MINUS' else d
                    )
                    act = '是' if a.get("affects_commission") in (1, True, '1') else (
                        '<span style="color:#6b7280">否</span>'
                    )
                    sts = a.get("status") or ''
                    stag = sts if sts != 'REVERSED' else '<span style="color:#9ca3af;text-decoration:line-through">已撤销</span>'
                    adj_rows += f"<tr><td>{m(a['adjustment_type'],'-')}</td>"
                    adj_rows += f"<td>{dtag}</td>"
                    adj_rows += f"<td class='num'>{money(a['amount'])}</td>"
                    adj_rows += f"<td>{act}</td>"
                    adj_rows += f"<td>{html.escape(str(m(a['reason'],'-')))}</td>"
                    adj_rows += f"<td>{stag}</td>"
                    adj_rows += f"<td>{m(a['created_by'],'-')}</td></tr>"
                adj_rows += "</tbody></table>"
            else:
                adj_rows = "<h4>✏️ 利润调整记录</h4><p style='color:#9ca3af;margin:0'>— 无调整 —</p>"

            detail_html = f"""
<details class="order-detail">
  <summary>{html.escape(str(r['outbound_no']))} · 客户：{html.escape(str(m(r['customer_name'],'—')))}</summary>
  <div class="detail-inner">
    {items_rows}
    {exp_rows}
    {adj_rows}
  </div>
</details>
"""

            parts.append(f"""<tr>
  <td>{idx}</td>
  <td><span class="tag tag-{scode}">{scode}</span><br/><small style="color:#6b7280">{html.escape(sname)}</small></td>
  <td>{detail_html}</td>
  <td style="white-space:nowrap">{fmt_date(r['ship_date'])}<br/><small style="color:#9ca3af">业务日：{fmt_date(r['business_date'])}</small></td>
  <td>{m(r['salesperson_name'],'<span style="color:#dc2626">⚠未映射</span>')}<br/><small style="color:#6b7280">{m(r['department_name'],'')}</small></td>
  <td>{m(r['customer_name'],'<span style="color:#dc2626">⚠未匹配</span>')}</td>
  <td class="num">{money(r['sales_amount'])}</td>
  <td class="num">{money(r['purchase_amount'])}{'<br/><span style=\"color:#dc2626;font-size:11px\">⚠成本异常</span>' if r.get('sys_cost_abnormal') else ''}</td>
  <td class="num{cls_neg}">{money(gross)}<br/><small style=\"color:#6b7280\">{pct(gross/sales*100 if sales>0 else 0)}</small></td>
  <td class="num">{money(r.get('sys_confirmed_expense'))}</td>
  <td class="num">{money(r.get('sys_adjustment'))}</td>
  <td class="num{cls_pn}">{money(sys_profit)}<br/><small style="color:#6b7280">可计提 {money(r.get('sys_commissionable_profit'))}</small></td>
  <td class="num">{money(r['received_amount'])}<br/><small style="color:#6b7280">未收 {money(r['outstanding_amount'])}</small></td>
  <td class="num">{m(r.get('sys_receipt_ratio_pct'),'-')}{'%' if r.get('sys_receipt_ratio_pct') is not None else ''}</td>
  <td class="num fin fin-cell">&nbsp;</td>
  <td class="num fin fin-cell">&nbsp;</td>
  <td class="num fin fin-cell">&nbsp;</td>
  <td class="num fin fin-cell">&nbsp;</td>
  <td class="num fin fin-cell">&nbsp;</td>
  <td class="num fin fin-cell">&nbsp;</td>
  <td class="fin remark-cell fin-cell">&nbsp;</td>
</tr>
""")

        parts.append("</tbody></table>")

    # ============== 签字区域 ==============
    parts.append("""
<div class="sign-area">
  <div class="col"><div class="line"></div>财务负责人签字<br/><small>日期：______年____月____日</small></div>
  <div class="col"><div class="line"></div>业务负责人签字<br/><small>日期：______年____月____日</small></div>
  <div class="col"><div class="line"></div>系统对接人签字<br/><small>日期：______年____月____日</small></div>
</div>
""")

    parts.append("</body></html>")

    with open(html_path, "w", encoding="utf-8") as f:
        f.write("".join(parts))
    print(f"✓ HTML 对账单已生成：{html_path}")

    # ============== 收尾 ==============
    print("\n" + "=" * 60)
    print("✅ 完成！请用浏览器打开上面的 HTML 文件，按 Ctrl+P 可打印。")
    print("   或用 Excel 打开 CSV 文件直接录入。")
    print("=" * 60)


if __name__ == "__main__":
    main()
