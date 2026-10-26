"""Read-only formal pollution and B-3.2 isolated result counts."""
import os
import pymysql

for schema in ("product_archive", "product_archive_b32_isolated"):
    conn = pymysql.connect(host="localhost", user=os.environ.get("DB_USERNAME", "root"),
                           password=os.environ["DB_PASSWORD"], database=schema)
    try:
        with conn.cursor() as cur:
            cur.execute("SELECT COUNT(*) FROM request_idempotency WHERE request_key LIKE 'b31-%'")
            b31 = cur.fetchone()[0]
            cur.execute("SELECT COUNT(*) FROM request_idempotency WHERE request_key LIKE 'b32-%'")
            b32 = cur.fetchone()[0]
            cur.execute("SELECT COUNT(*) FROM cashflow_account_opening WHERE fiscal_year=2026")
            opening_2026 = cur.fetchone()[0]
            cur.execute("SELECT COUNT(*) FROM cashflow_account_opening WHERE fiscal_year>=2027")
            future_openings = cur.fetchone()[0]
            print(f"{schema}: b31_keys={b31} b32_keys={b32} opening_2026={opening_2026} future_openings={future_openings}")
            if schema == "product_archive":
                cur.execute("SELECT COUNT(*) FROM h3_cashflow_entries")
                print("formal historical entries:", cur.fetchone()[0])
                assert b31 == 3 and b32 == 0 and opening_2026 == 35 and future_openings == 0
            else:
                cur.execute("SELECT COUNT(*) FROM cashflow_opening_confirmation")
                evidence = cur.fetchone()[0]
                cur.execute("SELECT COUNT(*) FROM cashflow_account_opening WHERE finance_confirmed=1")
                confirmed = cur.fetchone()[0]
                cur.execute("SELECT COUNT(*) FROM audit_logs WHERE entity_type='cashflow_account_opening' AND action='finance_confirm'")
                audits = cur.fetchone()[0]
                cur.execute("SELECT COUNT(*) FROM request_idempotency WHERE action='cashflow.opening.confirm'")
                idem = cur.fetchone()[0]
                print(f"isolated confirmed={confirmed} evidence={evidence} audit={audits} idempotency={idem}")
                assert (confirmed, evidence, audits, idem) == (3, 3, 3, 3)
                cur.execute("""SELECT COUNT(*) FROM cashflow_opening_confirmation c
                    JOIN cashflow_account_opening o ON o.id=c.opening_id
                    WHERE o.active=1 AND o.finance_confirmed=1 AND o.confirmed_by=7
                      AND c.confirmed_by=7 AND c.confirmed_by_name<>''
                      AND c.opening_amount_snapshot=o.amount AND c.verified_balance=o.amount""")
                matched = cur.fetchone()[0]
                print("isolated confirmation snapshot/status/actor matches:", matched)
                assert matched == 3
    finally:
        conn.close()
