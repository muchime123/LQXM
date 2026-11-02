"""Read-only check of applied Flyway versions and native website IP table."""
import argparse
import json
from pathlib import Path

from seed_website_company_profile import ROOT, connect


def audit(env_file):
    db = connect(env_file)
    try:
        with db.cursor() as cursor:
            cursor.execute("SET SESSION TRANSACTION READ ONLY")
            cursor.execute("START TRANSACTION READ ONLY")
            cursor.execute("SELECT DATABASE() AS schema_name")
            schema = cursor.fetchone()["schema_name"]
            cursor.execute("SELECT version, description, success FROM flyway_schema_history "
                           "WHERE installed_rank >= (SELECT MAX(installed_rank)-15 FROM flyway_schema_history) "
                           "ORDER BY installed_rank")
            migrations = cursor.fetchall()
            cursor.execute("SELECT COUNT(*) AS n FROM information_schema.TABLES "
                           "WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='website_ip_items'")
            exists = bool(cursor.fetchone()["n"])
            rows = None
            if exists:
                cursor.execute("SELECT status, COUNT(*) AS n FROM website_ip_items GROUP BY status")
                rows = {row["status"]: row["n"] for row in cursor.fetchall()}
            db.rollback()
    finally:
        db.close()
    return {"read_only": True, "schema": schema, "recent_migrations": migrations,
            "website_ip_table_exists": exists, "website_ip_counts": rows}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--env-file", type=Path, default=ROOT / "backend/.env")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    report = audit(args.env_file)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2, default=str) + "\n", encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, default=str))


if __name__ == "__main__":
    main()
