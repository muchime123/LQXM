"""Prove FK and import-style upsert in a rolled-back transaction, isolated schema only."""
import os
import uuid

import pymysql

conn = pymysql.connect(host="localhost", user=os.environ.get("DB_USERNAME", "root"),
                       password=os.environ["DB_PASSWORD"], database="product_archive_p2c_isolated",
                       autocommit=False)
key = "p2c-isolated-" + str(uuid.uuid4())
try:
    with conn.cursor() as cur:
        cur.execute("SELECT account_name FROM h3_cash_accounts WHERE id=687")
        name = cur.fetchone()[0]
        sql = """INSERT INTO h3_cashflow_entries
            (external_source,external_id,source_no,account_name,account_id,entry_date,summary,income,expense)
            VALUES('H3YUN',%s,%s,%s,%s,%s,%s,%s,%s)
            ON DUPLICATE KEY UPDATE account_name=VALUES(account_name),account_id=VALUES(account_id),
                                    summary=VALUES(summary),income=VALUES(income),expense=VALUES(expense)"""
        cur.execute(sql, (key, key, name, 687, "2026-10-27", "隔离导入关联验证", 1, None))
        cur.execute(sql, (key, key, name, 687, "2026-10-27", "隔离重复导入更新", 2, None))
        cur.execute("SELECT account_id,income FROM h3_cashflow_entries WHERE external_id=%s", (key,))
        rows = cur.fetchall()
        assert len(rows) == 1 and rows[0][0] == 687 and str(rows[0][1]) == "2.0000", rows
        bad_key = key + "-invalid"
        try:
            cur.execute(sql, (bad_key, bad_key, name, 999999999, "2026-10-27", "无效账户", 1, None))
            raise AssertionError("foreign key accepted a nonexistent account")
        except pymysql.err.IntegrityError as error:
            assert error.args[0] == 1452, error
        conn.rollback()
        cur.execute("SELECT COUNT(*) FROM h3_cashflow_entries WHERE external_id=%s", (key,))
        assert cur.fetchone()[0] == 0
        print("P2-C isolated import-style upsert: one stable row/account_id=687; invalid account FK=1452; rollback left 0 rows")
finally:
    conn.rollback()
    conn.close()
