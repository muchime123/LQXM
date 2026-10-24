"""Read-only production data; temporary session tables test divergent legacy/new opening."""
import os
import pymysql

con = pymysql.connect(host=os.environ.get("DB_HOST", "localhost"),
                      user=os.environ.get("DB_USER", "root"),
                      password=os.environ["DB_PASSWORD"],
                      database=os.environ.get("DB_NAME", "product_archive"))
cur = con.cursor()
try:
    cur.execute("CREATE TEMPORARY TABLE h3_cash_accounts (id BIGINT, account_name VARCHAR(50), external_source VARCHAR(20), opening_balance DECIMAL(18,4))")
    cur.execute("CREATE TEMPORARY TABLE cashflow_account_opening (account_id BIGINT, fiscal_year INT, amount DECIMAL(18,4), active INT)")
    cur.execute("INSERT INTO h3_cash_accounts VALUES (1,'B2_TEST','H3YUN',100)")
    cur.execute("INSERT INTO cashflow_account_opening VALUES (1,2026,250,1)")
    cur.execute("""SELECT opening.amount AS opening_balance FROM h3_cash_accounts a
      LEFT JOIN cashflow_account_opening opening ON opening.account_id=a.id
       AND opening.fiscal_year=2026 AND opening.active=1 WHERE a.id=1""")
    assert cur.fetchone()[0] == 250
    cur.execute("""SELECT COUNT(*) FROM h3_cash_accounts a
      LEFT JOIN cashflow_account_opening opening ON opening.account_id=a.id
       AND opening.fiscal_year=2026 AND opening.active=1
      WHERE a.external_source='H3YUN' AND opening.amount IS NULL""")
    assert cur.fetchone()[0] == 0
    cur.execute("UPDATE cashflow_account_opening SET active=0 WHERE account_id=1")
    cur.execute("""SELECT COUNT(*) FROM h3_cash_accounts a
      LEFT JOIN cashflow_account_opening opening ON opening.account_id=a.id
       AND opening.fiscal_year=2026 AND opening.active=1
      WHERE a.external_source='H3YUN' AND opening.amount IS NULL""")
    assert cur.fetchone()[0] == 1
    print("B-2 counterexample passed: new amount wins; inactive opening is reported missing")
finally:
    con.close()
