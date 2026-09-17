"""Read-only structural audit for the 2026-08-31 H3 product and stock exports."""
from pathlib import Path
from collections import Counter, defaultdict
from decimal import Decimal, InvalidOperation
import json, re, zipfile, xml.etree.ElementTree as ET

ROOT = Path(r"C:/Users/20676/Desktop/氚云/氚云")
OUT = Path(__file__).resolve().parents[1] / "outputs/01a05692-h3-migration/h3-product-stock-analysis.json"
NS = {"m": "http://schemas.openxmlformats.org/spreadsheetml/2006/main"}
REL = "{http://schemas.openxmlformats.org/officeDocument/2006/relationships}id"

def col(ref):
    n = 0
    for c in re.match(r"[A-Z]+", ref)[0]: n = n * 26 + ord(c) - 64
    return n - 1

def read_sheet(path):
    with zipfile.ZipFile(path) as z:
        shared = []
        if "xl/sharedStrings.xml" in z.namelist():
            for _, e in ET.iterparse(z.open("xl/sharedStrings.xml"), events=("end",)):
                if e.tag.endswith("}si"):
                    shared.append("".join(t.text or "" for t in e.findall(".//m:t", NS))); e.clear()
        rels = {e.attrib["Id"]: e.attrib["Target"] for e in ET.fromstring(z.read("xl/_rels/workbook.xml.rels"))}
        sheet = next(s for s in ET.fromstring(z.read("xl/workbook.xml")).findall("m:sheets/m:sheet", NS) if s.attrib["name"] == "Sheet1")
        target = rels[sheet.attrib[REL]]
        target = target.lstrip("/") if target.startswith("/") else "xl/" + target
        rows = []
        for _, e in ET.iterparse(z.open(target), events=("end",)):
            if not e.tag.endswith("}row"): continue
            row = {}
            for c in e.findall("m:c", NS):
                value = c.findtext("m:v", namespaces=NS)
                if c.attrib.get("t") == "s" and value is not None: value = shared[int(value)]
                elif c.attrib.get("t") == "inlineStr": value = "".join(t.text or "" for t in c.findall(".//m:t", NS))
                row[col(c.attrib["r"])] = "" if value is None else str(value).strip()
            e.clear()
            if row: rows.append(row)
    codes, labels = rows[0], rows[1]
    return [{labels[i]: r.get(i, "") for i in labels} for r in rows[2:]], {labels[i]: codes.get(i, "") for i in labels}

def dec(value):
    try: return Decimal(value.replace(",", ""))
    except (InvalidOperation, AttributeError): return None

def top(counter, n=30): return counter.most_common(n)

product_path = ROOT / "产品信息_2026-08-31-17-17-04-145.xlsx"
stock_path = ROOT / "仓库明细_2026-08-31-17-22-10-652.xlsx"
products, product_codes = read_sheet(product_path)
stock, stock_codes = read_sheet(stock_path)

# Product parent cells are merged in H3 exports. A new explicit parent ID starts a new category block.
category = parent_id = ""
for r in products:
    if r["数据ID(不可修改)"]:
        parent_id = r["数据ID(不可修改)"]; category = r["产品类目"]
    r["_parent_id"], r["_category"] = parent_id, category

P = lambda r, n: r.get("产品明细." + n, "")
product_by_code = defaultdict(list)
product_by_child = defaultdict(list)
for r in products:
    product_by_code[P(r,"产品编号")].append(r)
    product_by_child[P(r,"数据ID(不可修改)")].append(r)

stock_by_code = defaultdict(list)
for r in stock: stock_by_code[r["产品编号"]].append(r)
product_code_set = {k for k in product_by_code if k}
stock_code_set = {k for k in stock_by_code if k}
nonzero_stock = [r for r in stock if dec(r["库存数量"]) is not None and dec(r["库存数量"]) != 0]
positive_stock = [r for r in stock if dec(r["库存数量"]) is not None and dec(r["库存数量"]) > 0]
negative_stock = [r for r in stock if dec(r["库存数量"]) is not None and dec(r["库存数量"]) < 0]

def variants(rows, getter): return {getter(r) for r in rows if getter(r)}
code_conflicts = {}
for code, rows in product_by_code.items():
    if not code: continue
    names, specs, units, ids = variants(rows,lambda r:P(r,"产品名称")), variants(rows,lambda r:P(r,"规格")), variants(rows,lambda r:P(r,"单位")), variants(rows,lambda r:P(r,"数据ID(不可修改)"))
    if len(rows)>1 or len(names)>1 or len(specs)>1 or len(units)>1:
        code_conflicts[code] = {"rows":len(rows),"names":len(names),"specifications":len(specs),"units":len(units),"child_ids":len(ids)}

balance_keys = Counter((r["仓库信息"],r["产品编号"],r["单位"]) for r in stock)
duplicate_balances = {"|".join(k):v for k,v in balance_keys.items() if v>1}
stock_product_mismatch = Counter()
for r in stock:
    candidates = product_by_code.get(r["产品编号"], [])
    if not candidates: stock_product_mismatch["code_not_in_product_export"] += 1; continue
    exact = [p for p in candidates if P(p,"产品名称") == r["产品名称"] and P(p,"规格") == r["规格"] and P(p,"单位") == r["单位"]]
    if len(exact)==1: stock_product_mismatch["exact_code_name_spec_unit"] += 1
    elif len(exact)>1: stock_product_mismatch["multiple_exact_product_rows"] += 1
    else:
        if not any(P(p,"单位") == r["单位"] for p in candidates): stock_product_mismatch["unit_mismatch"] += 1
        elif not any(P(p,"产品名称") == r["产品名称"] for p in candidates): stock_product_mismatch["name_mismatch"] += 1
        else: stock_product_mismatch["specification_mismatch"] += 1

referenced_codes = {r["产品编号"] for r in nonzero_stock}
report = {
    "source_files": [product_path.name,stock_path.name],
    "field_codes": {"product":product_codes,"stock":stock_codes},
    "product": {
        "rows":len(products),"parent_groups":len({r['_parent_id'] for r in products if r['_parent_id']}),
        "child_ids":len({P(r,'数据ID(不可修改)') for r in products if P(r,'数据ID(不可修改)')}),
        "distinct_codes":len(product_code_set),"blank_codes":sum(not P(r,'产品编号') for r in products),
        "duplicate_code_count":sum(len(v)>1 for k,v in product_by_code.items() if k),
        "missing_name":sum(not P(r,'产品名称') for r in products),"missing_spec":sum(not P(r,'规格') for r in products),
        "missing_unit":sum(not P(r,'单位') for r in products),
        "statuses":top(Counter(P(r,'产品状态') for r in products)),"units":top(Counter(P(r,'单位') for r in products)),
        "categories":top(Counter(r['_category'] or '(blank)' for r in products),100),
        "code_conflicts_sample":dict(list(sorted(code_conflicts.items()))[:100])
    },
    "stock": {
        "rows":len(stock),"distinct_codes":len(stock_code_set),"warehouses":top(Counter(r['仓库信息'] for r in stock)),
        "categories":top(Counter(r['产品类目'] for r in stock),100),"units":top(Counter(r['单位'] for r in stock)),
        "statuses":top(Counter(r['数据状态'] for r in stock)),"positive_rows":len(positive_stock),"zero_rows":len(stock)-len(nonzero_stock),
        "negative_rows":len(negative_stock),"nonzero_distinct_codes":len(referenced_codes),
        "duplicate_balance_keys":len(duplicate_balances),"duplicate_balance_sample":dict(list(duplicate_balances.items())[:100]),
        "negative_sample":[{k:r[k] for k in ['仓库信息','产品编号','产品名称','规格','单位','库存数量']} for r in negative_stock[:100]]
    },
    "linkage": {
        "stock_rows_to_product":dict(stock_product_mismatch),
        "stock_codes_not_in_product":len(stock_code_set-product_code_set),
        "product_codes_not_in_stock":len(product_code_set-stock_code_set),
        "nonzero_stock_codes_not_in_product":len(referenced_codes-product_code_set),
        "product_rows_relevant_to_nonzero_stock":sum(P(r,'产品编号') in referenced_codes for r in products),
        "product_rows_not_relevant_to_nonzero_stock":sum(P(r,'产品编号') not in referenced_codes for r in products)
    }
}
OUT.parent.mkdir(parents=True,exist_ok=True)
OUT.write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding="utf-8")
print(OUT)
