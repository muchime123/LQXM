import os, re, json
root = r'C:\Users\20676\source\LQXM'
# 要核查的表名（遗留 32 + 残留 3 + 空表中体积较大的）
tables = """bak_20260921_product_inventory_balances
bak_20260921_product_inventory_transactions
bak_20260921_sales_products
bak_mo_20260921
bak_moo_20260921
bak_sh_h3_cash_accounts
bak_sh_h3_cashflow_entries
bak_sh_h3_cashier_log
bak_sh_h3_input_invoices
bak_sh_h3_sales_invoices
bak_sh_h3_supplier_payables
business_commission_settlement_items_archive_20260904
business_commission_settlements_archive_20260904
business_commission_snapshots_archive_20260904
business_profit_adjustments_archive_20260904
business_profit_snapshots_archive_20260904
customer_contacts_before_v282
customer_salesperson_relations_before_v282
manufacturing_followup_operation_snapshot_archive_20261007
manufacturing_followup_order_snapshot_archive_20261007
manufacturing_order_follow_ups_archive_20261007
production_followup_logs_archive_20261007
production_followup_records_archive_20261007
production_tracking_entries_legacy_20261008
production_tracking_state_legacy_20261008
production_tracking_workbook_archive_rules
receipt_match_candidates_archive_20260916
sales_commission_calculations_archive_20260907
sales_commission_rules_archive_20260907
sales_commission_settlements_archive_20260907
sales_product_cost_snapshots_archive_20260907
sales_profit_calculations_archive_20260907
product_models_archive
_t_latest
_t_keepbatch
h3sc_latest
_h3sc_prune_20261020_ids""".split()

scan_dirs = [
    os.path.join(root, 'backend', 'src', 'main'),
    os.path.join(root, 'backend', 'src', 'test'),
    os.path.join(root, 'apps'),
    os.path.join(root, 'scripts'),
    os.path.join(root, 'db'),
]
skip_ext = {'.jar', '.class', '.png', '.jpg', '.zip', '.xlsx', '.pdf', '.ico'}
skip_dir = {'node_modules', 'target', 'dist', '.git', '_cls'}

hits = {t: [] for t in tables}
for base in scan_dirs:
    if not os.path.isdir(base):
        continue
    for d, dirs, files in os.walk(base):
        dirs[:] = [x for x in dirs if x not in skip_dir]
        for f in files:
            if os.path.splitext(f)[1].lower() in skip_ext:
                continue
            p = os.path.join(d, f)
            try:
                txt = open(p, encoding='utf-8', errors='ignore').read()
            except Exception:
                continue
            low = txt
            for t in tables:
                if t in low:
                    hits[t].append(os.path.relpath(p, root))

for t in tables:
    lst = hits[t]
    print(f'{t}\t{len(lst)}\t' + ';'.join(lst[:6]))
