"""Read-only status inventory for website-owned public content sources."""
import argparse
import json
from pathlib import Path

import pymysql

from audit_website_banner_media import config_value


ROOT = Path(__file__).resolve().parents[1]
TABLES = ("website_products", "website_product_categories", "website_packaging_items",
          "website_company_profiles", "website_company_milestones", "website_social_links",
          "website_credentials")


def audit(env_file):
    password = config_value("DB_PASSWORD", env_file)
    if password is None:
        raise RuntimeError("DB_PASSWORD is unavailable")
    connection = pymysql.connect(
        host=config_value("DB_HOST", env_file) or "127.0.0.1",
        port=int(config_value("DB_PORT", env_file) or 3306),
        user=config_value("DB_USERNAME", env_file) or "root",
        password=password, database=config_value("DB_NAME", env_file) or "product_archive",
        charset="utf8mb4", cursorclass=pymysql.cursors.DictCursor, autocommit=False,
    )
    try:
        with connection.cursor() as cursor:
            cursor.execute("SET SESSION TRANSACTION READ ONLY")
            cursor.execute("START TRANSACTION READ ONLY")
            cursor.execute("SELECT DATABASE() AS schema_name")
            schema = cursor.fetchone()["schema_name"]
            cursor.execute("SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE()")
            available = {row["TABLE_NAME"] for row in cursor.fetchall()}
            cursor.execute("SELECT TABLE_NAME, COLUMN_NAME FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE()")
            columns = {}
            for row in cursor.fetchall():
                columns.setdefault(row["TABLE_NAME"], set()).add(row["COLUMN_NAME"])
            counts = {}
            for table in TABLES:
                if table not in available:
                    counts[table] = {"missing": True}
                    continue
                if "status" not in columns[table]:
                    counts[table] = {"missing_status_column": True}
                    continue
                active = "WHERE deleted_at IS NULL" if "deleted_at" in columns[table] else ""
                cursor.execute(f"SELECT status, COUNT(*) AS rows_count FROM {table} {active} GROUP BY status")
                counts[table] = {"active_by_status": {row["status"]: row["rows_count"] for row in cursor.fetchall()},
                                 "has_soft_delete": "deleted_at" in columns[table]}
            if "website_packaging_variants" in available:
                active = "WHERE deleted_at IS NULL" if "deleted_at" in columns["website_packaging_variants"] else ""
                cursor.execute(f"SELECT COUNT(*) AS rows_count FROM website_packaging_variants {active}")
                counts["website_packaging_variants"] = {"active": cursor.fetchone()["rows_count"]}
            connection.rollback()
    finally:
        connection.close()
    return {"read_only": True, "schema": schema, "status_counts": counts}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--env-file", type=Path, default=ROOT / "backend/.env")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    report = audit(args.env_file)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report["status_counts"], ensure_ascii=False))


if __name__ == "__main__":
    main()
