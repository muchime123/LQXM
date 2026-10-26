"""Read-only inspection and restorable backup of accidental B-3.1 idempotency keys."""
import hashlib
import json
import os
from datetime import datetime
from decimal import Decimal
from pathlib import Path

import pymysql

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "backups" / "cashflow-b31" / "formal_idempotency_3rows_20261026.json"

def serial(value):
    if isinstance(value, (datetime, Decimal)):
        return str(value)
    if isinstance(value, bytes):
        return {"hex": value.hex()}
    return value

conn = pymysql.connect(host="localhost", user=os.environ.get("DB_USERNAME", "root"),
                       password=os.environ["DB_PASSWORD"], database="product_archive",
                       autocommit=False, cursorclass=pymysql.cursors.DictCursor)
try:
    with conn.cursor() as cur:
        cur.execute("SHOW CREATE TABLE request_idempotency")
        ddl = cur.fetchone()["Create Table"]
        cur.execute("SELECT * FROM request_idempotency WHERE request_key LIKE %s ORDER BY id", ("b31-%",))
        rows = cur.fetchall()
        if len(rows) != 3 or any(r["action"] != "cashflow.opening.manual" for r in rows):
            raise RuntimeError("Expected exactly three B-3.1 manual-opening keys; no backup prepared")
        safe_rows = [{k: serial(v) for k, v in row.items()} for row in rows]
        payload = {"schema": "product_archive", "table": "request_idempotency",
                   "ddl": ddl, "rows": safe_rows}
        OUT.parent.mkdir(parents=True, exist_ok=True)
        if OUT.exists():
            raise RuntimeError(f"Backup already exists; refusing to overwrite {OUT}")
        OUT.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
        digest = hashlib.sha256(OUT.read_bytes()).hexdigest()
        print(f"backup={OUT} sha256={digest} rows={len(rows)}")
        for row in safe_rows:
            print(json.dumps(row, ensure_ascii=False))
        for row in rows:
            ref = str(row.get("result_ref") or "")
            if ref.isdecimal():
                cur.execute("SELECT account_id,fiscal_year FROM cashflow_account_opening WHERE id=%s", (int(ref),))
                match = cur.fetchone()
                print(f"result_ref={ref} formal_opening={match}")
        cur.execute("SELECT COUNT(*) AS n FROM cashflow_account_opening WHERE fiscal_year >= 2027")
        print(f"formal_openings_2027_plus={cur.fetchone()['n']}")
finally:
    conn.rollback()
    conn.close()
