"""Read-only match audit for the workshop follow-up workbook against imported H3 tasks."""

from __future__ import annotations

import collections
import datetime as dt
import os
import re
from pathlib import Path

import openpyxl
import pymysql


ROOT = Path(__file__).resolve().parents[1]
BOOK = Path(r"C:\Users\20676\Desktop\氚云导入中心 项目建设\生产跟单进度表.xlsx")


def env_values() -> dict[str, str]:
    result = {}
    for line in (ROOT / "backend/.env").read_text(encoding="utf-8").splitlines():
        if "=" in line and not line.startswith("#"):
            key, value = line.split("=", 1)
            result[key] = value.strip().strip("\"'")
    return result


def plain(value: object) -> str:
    return re.sub(r"[\s\-_/（）()]", "", str(value or "")).lower().replace("车间", "")


def date(value: object) -> dt.date | None:
    if isinstance(value, dt.datetime):
        return value.date()
    if isinstance(value, dt.date):
        return value
    if isinstance(value, (float, int)) and value > 40000:
        return openpyxl.utils.datetime.from_excel(value).date()
    return None


def main() -> None:
    config = env_values()
    connection = pymysql.connect(
        host="localhost", user=config["DB_USERNAME"], password=config["DB_PASSWORD"],
        database="product_archive", charset="utf8mb4", cursorclass=pymysql.cursors.DictCursor,
    )
    try:
        with connection.cursor() as cursor:
            cursor.execute("""SELECT id, work_order_no, product_name, production_line,
                required_completion_date, required_quantity, application_date, source_created_at
                FROM production_orders WHERE external_source='H3YUN'""")
            orders = cursor.fetchall()
    finally:
        connection.close()

    by_suffix: dict[int, list[dict]] = collections.defaultdict(list)
    for order in orders:
        match = re.search(r"(\d+)$", order["work_order_no"] or "")
        if match:
            by_suffix[int(match.group(1)[-4:])].append(order)

    book = openpyxl.load_workbook(BOOK, data_only=True, read_only=True)
    stats = collections.Counter()
    matched_due = collections.Counter()
    matched_target_ids = []
    examples: dict[str, list[str]] = collections.defaultdict(list)
    for sheet in book:
        for excel_row, cells in enumerate(sheet.iter_rows(min_row=4, values_only=True), start=4):
            if not any(value is not None for value in cells[:12]):
                continue
            short_no = cells[0]
            product = cells[1]
            due = date(cells[6])
            if isinstance(short_no, (int, float)) and int(short_no) == short_no:
                candidates = by_suffix[int(short_no)]
            else:
                candidates = []
            matching = [order for order in candidates if plain(product) == plain(order["product_name"])
                        and plain(sheet.title) == plain(order["production_line"])]
            if len(matching) > 1 and due:
                matching = [order for order in matching if order["required_completion_date"] == due]
            if len(matching) == 1:
                kind = "matched"
                matched_target_ids.append(matching[0]["id"])
                matched_due["equal" if matching[0]["required_completion_date"] == due else "different"] += 1
            elif not candidates:
                kind = "missing_number" if short_no is not None else "blank_number"
            elif not matching:
                kind = "number_but_mismatch"
            else:
                kind = "ambiguous"
            stats[(sheet.title, kind)] += 1
            if len(examples[kind]) < 8:
                examples[kind].append(
                    f"{sheet.title}!{excel_row}: {short_no!r} {product!r} due={due} "
                    f"candidates={[(c['id'], c['work_order_no'], c['product_name'], c['production_line']) for c in candidates[:3]]}"
                )
            if kind != "matched":
                alternatives = [order for order in orders if plain(sheet.title) == plain(order["production_line"])
                                and order["required_completion_date"] == due]
                if len(examples["alternative"]) < 25:
                    examples["alternative"].append(
                        f"{sheet.title}!{excel_row}: {short_no!r} {product!r} due={due} "
                        f"same_line_due={[(o['id'], o['work_order_no'], o['product_name']) for o in alternatives[:8]]} "
                        f"count={len(alternatives)}"
                    )
    for key, count in sorted(stats.items()):
        print(*key, count, sep="\t")
    print("TOTAL", sum(stats.values()))
    print("MATCHED_DUE", dict(matched_due), "DUPLICATE_TARGETS", [x for x,n in collections.Counter(matched_target_ids).items() if n>1])
    for kind, rows in examples.items():
        print("EXAMPLES", kind)
        for row in rows:
            print(row)


if __name__ == "__main__":
    main()
