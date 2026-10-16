"""One-time, reviewable import of the workshop workbook into local follow-up data.

Dry run is the default. --apply writes all source rows, imports only unique exact
matches, and leaves uncertain rows for review in the production follow-up page.
"""

from __future__ import annotations

import argparse
import collections
import datetime as dt
import hashlib
import json
import re
from decimal import Decimal
from pathlib import Path

import openpyxl
import pymysql


ROOT = Path(__file__).resolve().parents[1]
DEFAULT_BOOK = Path(r"C:\Users\20676\Desktop\氚云导入中心 项目建设\生产跟单进度表.xlsx")
MACHINE = ["DRAWING", "MATERIAL", "SHEET_METAL", "MOLD", "ASSEMBLY", "TESTING", "WAREHOUSE"]
SEALER = ["MATERIAL", "MOLD", "ASSEMBLY", "WAREHOUSE"]


def credentials() -> dict[str, str]:
    values = {}
    for line in (ROOT / "backend/.env").read_text(encoding="utf-8").splitlines():
        if "=" in line and not line.startswith("#"):
            key, value = line.split("=", 1)
            values[key] = value.strip().strip("\"'")
    return values


def date(value: object) -> str | None:
    if isinstance(value, dt.datetime):
        return value.date().isoformat()
    if isinstance(value, dt.date):
        return value.isoformat()
    if isinstance(value, (float, int)) and value > 40000:
        return openpyxl.utils.datetime.from_excel(value).date().isoformat()
    return None


def text(value: object) -> str | None:
    if value is None:
        return None
    stripped = str(value).strip()
    return stripped or None


def normalize(value: object) -> str:
    return re.sub(r"[\s\-_/（）()]", "", str(value or "")).lower().replace("车间", "")


def workbook_rows(path: Path) -> list[dict]:
    file_hash = hashlib.sha256(path.read_bytes()).hexdigest()
    workbook = openpyxl.load_workbook(path, read_only=True, data_only=True)
    result = []
    for sheet in workbook:
        is_sealer = "封口" in sheet.title
        codes = SEALER if is_sealer else MACHINE
        stage_start = 12
        shipment_col, remark_col = (16, 18) if is_sealer else (19, 21)
        for row_number, cells in enumerate(sheet.iter_rows(min_row=4, values_only=True), start=4):
            if not any(value is not None for value in cells[:12]):
                continue
            stage_dates = [date(cells[stage_start + i]) for i in range(len(codes))]
            order_no = text(cells[0])
            if isinstance(cells[0], (int, float)) and int(cells[0]) == cells[0]:
                order_no = str(int(cells[0]))
            payload = {
                "sourceFile": path.name,
                "sourceHash": file_hash,
                "sheet": sheet.title,
                "rowNumber": row_number,
                "sourceOrderNo": order_no,
                "productName": text(cells[1]),
                "model": text(cells[2]),
                "dimension": text(cells[3]),
                "quantity": text(cells[4]),
                "plannedStartDate": date(cells[5]),
                "requiredCompletionDate": date(cells[6]),
                "productionCycleDays": text(cells[7]),
                "deliveryResult": text(cells[9]),
                "forecastFinishDate": date(cells[10]),
                "delayReason": text(cells[11]),
                "shipmentStatus": date(cells[shipment_col]) or text(cells[shipment_col]),
                "salesperson": text(cells[shipment_col + 1]),
                "remark": text(cells[remark_col]),
                "milestones": [
                    {"code": code, "completedDate": completed_date}
                    for code, completed_date in zip(codes, stage_dates)
                ],
            }
            result.append(payload)
    return result


def match_rows(rows: list[dict], orders: list[dict], existing_state: set[int]) -> None:
    by_short: dict[int, list[dict]] = collections.defaultdict(list)
    for order in orders:
        match = re.search(r"(\d+)$", order["work_order_no"] or "")
        if match:
            by_short[int(match.group(1)[-4:])].append(order)
    for row in rows:
        short = row["sourceOrderNo"]
        candidates = by_short.get(int(short), []) if short and short.isdigit() else []
        exact = [candidate for candidate in candidates
                 if normalize(candidate["product_name"]) == normalize(row["productName"])
                 and normalize(candidate["production_line"]) == normalize(row["sheet"])
                 and str(candidate["required_completion_date"]) == str(row["requiredCompletionDate"])]
        if len(exact) == 1:
            row["targetId"] = exact[0]["id"]
            row["matchNote"] = "编号、产品、车间和交期一致"
        else:
            row["targetId"] = None
            row["matchNote"] = "缺少订单号" if not short else "编号、产品、车间或交期不一致，请人工核对"
    counts = collections.Counter(row["targetId"] for row in rows if row["targetId"] is not None)
    for row in rows:
        target = row["targetId"]
        if target is not None and counts[target] > 1:
            row["targetId"] = None
            row["matchNote"] = "多行指向同一工单，请人工核对"
        elif target in existing_state:
            row["targetId"] = None
            row["matchNote"] = "该工单已有人工跟单记录，不覆盖"


def insert_tracking(cursor, row: dict, target: int, payload: str) -> None:
    cursor.execute("INSERT INTO production_tracking_enrollments(production_order_id,origin) VALUES(%s,'WORKBOOK')",
                   (target,))
    cursor.execute("""INSERT INTO production_tracking_state
        (production_order_id,planned_start_date,forecast_finish_date,delay_reason,delivery_result,
         shipment_status,remark,version,last_followup_at)
        VALUES(%s,%s,%s,%s,%s,%s,%s,1,NOW())""",
        (target, row["plannedStartDate"], row["forecastFinishDate"], row["delayReason"],
         row["deliveryResult"], row["shipmentStatus"], row["remark"]))
    for stage in row["milestones"]:
        if stage["completedDate"]:
            cursor.execute("""INSERT INTO production_tracking_milestones
                (production_order_id,stage_code,stage_status,completed_date)
                VALUES(%s,%s,'DONE',%s)""", (target, stage["code"], stage["completedDate"]))
    cursor.execute("""INSERT INTO production_tracking_entries
        (production_order_id,version,content,before_json,after_json,created_by_employee_id,created_by_name)
        VALUES(%s,1,%s,'{}',%s,0,'表格导入')""",
        (target, f"导入车间进度表「{row['sheet']}」第 {row['rowNumber']} 行快照", payload))


def import_blank_exact(cursor, orders: list[dict]) -> int:
    cursor.execute("SELECT id,payload_json FROM production_tracking_workbook_rows WHERE match_status='PENDING'")
    pending = [(record["id"], json.loads(record["payload_json"])) for record in cursor.fetchall()]
    cursor.execute("SELECT production_order_id FROM production_tracking_enrollments")
    enrolled = {record["production_order_id"] for record in cursor.fetchall()}
    matches = []
    for row_id, row in pending:
        if row["sourceOrderNo"] or not row["plannedStartDate"] or not row["requiredCompletionDate"] or not row["quantity"]:
            continue
        candidates = [order for order in orders
                      if normalize(order["product_name"]) == normalize(row["productName"])
                      and normalize(order["production_line"]) == normalize(row["sheet"])
                      and str(order["required_completion_date"]) == row["requiredCompletionDate"]
                      and str(order["application_date"]) == row["plannedStartDate"]
                      and order["required_quantity"] == Decimal(row["quantity"])]
        if len(candidates) == 1 and candidates[0]["id"] not in enrolled:
            matches.append((row_id, row, candidates[0]["id"]))
    target_counts = collections.Counter(target for _, _, target in matches)
    imported = 0
    for row_id, row, target in matches:
        if target_counts[target] > 1:
            continue
        insert_tracking(cursor, row, target, json.dumps(row, ensure_ascii=False))
        cursor.execute("""UPDATE production_tracking_workbook_rows SET match_status='IMPORTED',
            production_order_id=%s,match_note='产品、车间、计划开工、交期和数量唯一一致',imported_at=NOW()
            WHERE id=%s AND match_status='PENDING'""", (target, row_id))
        imported += 1
    return imported


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--workbook", type=Path, default=DEFAULT_BOOK)
    parser.add_argument("--apply", action="store_true")
    parser.add_argument("--import-blank-exact", action="store_true")
    args = parser.parse_args()
    rows = workbook_rows(args.workbook)
    config = credentials()
    connection = pymysql.connect(host="localhost", user=config["DB_USERNAME"], password=config["DB_PASSWORD"],
                                  database="product_archive", charset="utf8mb4",
                                  cursorclass=pymysql.cursors.DictCursor, autocommit=False)
    try:
        with connection.cursor() as cursor:
            cursor.execute("""SELECT id,work_order_no,product_name,production_line,required_completion_date,
                application_date,required_quantity
                FROM production_orders WHERE external_source='H3YUN'""")
            orders = cursor.fetchall()
            if args.import_blank_exact:
                count = import_blank_exact(cursor, orders)
                connection.commit()
                print(f"blank_exact_imported={count}")
                return
            cursor.execute("SELECT production_order_id FROM production_tracking_state")
            existing_state = {r["production_order_id"] for r in cursor.fetchall()}
            cursor.execute("SELECT COUNT(*) AS n FROM production_tracking_workbook_rows")
            staged_count = cursor.fetchone()["n"]
            match_rows(rows, orders, existing_state)
            matched = [r for r in rows if r["targetId"] is not None]
            pending = [r for r in rows if r["targetId"] is None]
            print(f"workbook_rows={len(rows)} ready={len(matched)} pending={len(pending)} staged_in_db={staged_count}")
            print("pending_reasons=", dict(collections.Counter(r["matchNote"] for r in pending)))
            if not args.apply:
                return
            if staged_count:
                raise RuntimeError("Workbook rows are already staged; refusing to import twice")
            for row in rows:
                payload = json.dumps({key: value for key, value in row.items() if key not in ("targetId", "matchNote")},
                                     ensure_ascii=False)
                cursor.execute("""INSERT INTO production_tracking_workbook_rows
                    (sheet_name,sheet_row,source_order_no,product_name,required_completion_date,payload_json,
                     match_status,production_order_id,match_note,imported_at)
                    VALUES(%s,%s,%s,%s,%s,%s,%s,%s,%s,IF(%s IS NULL,NULL,NOW()))""",
                    (row["sheet"], row["rowNumber"], row["sourceOrderNo"], row["productName"],
                     row["requiredCompletionDate"], payload, "IMPORTED" if row["targetId"] else "PENDING",
                     row["targetId"], row["matchNote"], row["targetId"]))
                if row["targetId"] is None:
                    continue
                target = row["targetId"]
                insert_tracking(cursor, row, target, payload)
        connection.commit()
        print("applied=yes")
    except Exception:
        connection.rollback()
        raise
    finally:
        connection.close()


if __name__ == "__main__":
    main()
