"""Read-only account-ID migration checks for the selected schema."""
import os

import pymysql

schema = os.environ.get("DB_SCHEMA", "product_archive_p2c_isolated")
conn = pymysql.connect(host="localhost", user=os.environ.get("DB_USERNAME", "root"),
                       password=os.environ["DB_PASSWORD"], database=schema)
try:
    with conn.cursor() as cur:
        cur.execute("SELECT COUNT(*),COUNT(account_id),COUNT(DISTINCT account_id) FROM h3_cashflow_entries")
        total, linked, used_accounts = cur.fetchone()
        cur.execute("""SELECT COUNT(*) FROM h3_cashflow_entries e
                       LEFT JOIN h3_cash_accounts a ON a.id=e.account_id
                       WHERE a.id IS NULL OR a.external_source<>e.external_source""")
        invalid = cur.fetchone()[0]
        cur.execute("""SELECT COUNT(*) FROM h3_cashflow_entries e
                       JOIN h3_cash_accounts a ON a.id=e.account_id
                       WHERE a.account_name<>e.account_name""")
        renamed_sources = cur.fetchone()[0]
        cur.execute("""SELECT COUNT(*) FROM information_schema.key_column_usage
                       WHERE table_schema=DATABASE() AND table_name='h3_cashflow_entries'
                         AND column_name='account_id' AND referenced_table_name='h3_cash_accounts'
                         AND referenced_column_name='id'""")
        foreign_key = cur.fetchone()[0]
        cur.execute("""SELECT COUNT(*) FROM information_schema.columns
                       WHERE table_schema=DATABASE() AND table_name='h3_cashflow_entries'
                         AND column_name='account_id' AND is_nullable='NO'""")
        non_null = cur.fetchone()[0]
        print(f"{schema}: entries={total} linked={linked} used_accounts={used_accounts} "
              f"invalid={invalid} renamed_source_labels={renamed_sources} fk={foreign_key} not_null={non_null}")
        assert total > 0 and linked == total and invalid == 0
        assert foreign_key == 1 and non_null == 1
        # Source labels are historical evidence; a later account rename may make them differ.
finally:
    conn.close()
