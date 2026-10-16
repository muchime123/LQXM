"""Read-only audit of potential exact field matches for unresolved workbook rows."""

import importlib.util
import json

import pymysql


spec = importlib.util.spec_from_file_location("tracking_import", "scripts/import-production-tracking-workbook.py")
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
credentials = module.credentials()
db = pymysql.connect(host="localhost", user=credentials["DB_USERNAME"], password=credentials["DB_PASSWORD"],
                     database="product_archive", charset="utf8mb4", cursorclass=pymysql.cursors.DictCursor)
try:
    with db.cursor() as cursor:
        cursor.execute("""SELECT id,work_order_no,product_name,production_line,
            required_completion_date,required_quantity,application_date FROM production_orders WHERE external_source='H3YUN'""")
        orders = cursor.fetchall()
        cursor.execute("SELECT id,payload_json FROM production_tracking_workbook_rows WHERE match_status='PENDING'")
        rows = cursor.fetchall()
        for row in rows:
            source = json.loads(row["payload_json"])
            candidates = [order for order in orders
                          if module.normalize(order["product_name"]) == module.normalize(source["productName"])
                          and module.normalize(order["production_line"]) == module.normalize(source["sheet"])
                          and str(order["required_completion_date"]) == str(source["requiredCompletionDate"])
                          and str(order["required_quantity"]).rstrip("0").rstrip(".") == str(source["quantity"])]
            if len(candidates) == 1:
                target = candidates[0]
                print(source["sheet"], source["rowNumber"], source["sourceOrderNo"],
                      target["id"], target["work_order_no"],source["plannedStartDate"],target["application_date"], sep="\t")
finally:
    db.close()
