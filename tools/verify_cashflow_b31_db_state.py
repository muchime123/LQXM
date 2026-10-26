"""Read-only formal pollution check plus isolated unique-index probe in rollback-only transaction."""
import os
import pymysql

kwargs = dict(host="localhost", user=os.environ.get("DB_USERNAME", "root"),
              password=os.environ["DB_PASSWORD"], autocommit=False)
for schema in ("product_archive", "product_archive_b31_isolated"):
    conn = pymysql.connect(database=schema, **kwargs)
    try:
        with conn.cursor() as cur:
            cur.execute("SELECT COUNT(*) FROM request_idempotency WHERE request_key LIKE 'b31-%'")
            keys = cur.fetchone()[0]
            cur.execute("SELECT COUNT(*) FROM cashflow_account_opening WHERE fiscal_year>=2027")
            openings = cur.fetchone()[0]
            cur.execute("SELECT COUNT(*) FROM h3_cash_accounts WHERE id=900000001")
            test_accounts = cur.fetchone()[0]
            print(f"{schema}: b31_keys={keys}, openings_2027_plus={openings}, test_accounts={test_accounts}")
            if schema == "product_archive":
                assert (keys, openings, test_accounts) == (3, 0, 0)
            else:
                cur.execute("SELECT fiscal_year,COUNT(*) FROM cashflow_account_opening WHERE account_id=900000001 GROUP BY fiscal_year ORDER BY fiscal_year")
                print("isolated years/counts:", cur.fetchall())
                cur.execute("SHOW INDEX FROM cashflow_account_opening WHERE Non_unique=0")
                print("unique index columns:", [(r[2], r[4]) for r in cur.fetchall()])
                cur.execute("SELECT COUNT(*) FROM audit_logs WHERE entity_type='cashflow_account_opening'")
                print("isolated opening audit rows:", cur.fetchone()[0])
                assert keys == 3 and openings == 6 and test_accounts == 1
                # Unique constraint must reject a duplicate account/year even with a new key.
                try:
                    cur.execute("""INSERT INTO cashflow_account_opening(account_id,fiscal_year,amount,source,active,finance_confirmed,basis)
                                   VALUES(900000001,2033,1.0000,'MANUAL_OPENING',1,0,'unique probe')""")
                    raise AssertionError("Database accepted duplicate account/year")
                except pymysql.err.IntegrityError as error:
                    assert error.args[0] == 1062, error
                    print("duplicate account/year SQL error:", error.args[0])
    finally:
        conn.rollback()
        conn.close()
