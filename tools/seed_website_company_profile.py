"""One-time, guarded copy of the current public company fields into a website draft.

Default mode is a read-only preflight. --apply inserts only when the target is empty.
No archive row, publication state, or runtime source configuration is changed.
"""
import argparse
import json
from pathlib import Path

import pymysql

from audit_website_banner_media import config_value


ROOT = Path(__file__).resolve().parents[1]
FIELDS = (
    "company_name", "company_name_en", "brand_name", "brand_name_en", "brand_slogan",
    "mission", "vision", "values", "core_positioning", "main_business",
    "target_industries", "advantages", "manufacturing_strengths", "enterprise_scale",
    "establishment_date", "office_address", "factory_address", "phone", "email", "website",
)
TARGET_FIELDS = tuple("company_values" if field == "values" else field for field in FIELDS)


def connect(env_file):
    password = config_value("DB_PASSWORD", env_file)
    if password is None:
        raise RuntimeError("DB_PASSWORD is unavailable")
    return pymysql.connect(
        host=config_value("DB_HOST", env_file) or "127.0.0.1",
        port=int(config_value("DB_PORT", env_file) or 3306),
        user=config_value("DB_USERNAME", env_file) or "root", password=password,
        database=config_value("DB_NAME", env_file) or "product_archive",
        charset="utf8mb4", cursorclass=pymysql.cursors.DictCursor, autocommit=False,
    )


def inspect(cursor):
    cursor.execute("SELECT DATABASE() AS schema_name")
    schema = cursor.fetchone()["schema_name"]
    cursor.execute("SELECT id, " + ", ".join("`" + f + "`" for f in FIELDS)
                   + " FROM company_info ORDER BY id")
    sources = cursor.fetchall()
    cursor.execute("SELECT id, status, logo_media_id, " + ", ".join("`" + f + "`" for f in TARGET_FIELDS)
                   + " FROM website_company_profiles WHERE id=1")
    target = cursor.fetchone()
    cursor.execute("SELECT COUNT(*) AS count FROM website_company_profiles")
    target_count = cursor.fetchone()["count"]
    source = sources[0] if sources else None
    blockers = []
    if not source:
        blockers.append("company_info has no source row")
    if target_count:
        blockers.append("website_company_profiles already contains content")
    if source and not source["company_name"]:
        blockers.append("public company name is blank")
    if source:
        for key, value in source.items():
            if key == "id" or value is None:
                continue
            limit = {"company_name": 300, "company_name_en": 300, "brand_name": 200,
                     "brand_name_en": 200, "brand_slogan": 500, "enterprise_scale": 200,
                     "office_address": 500, "factory_address": 500, "phone": 100,
                     "email": 200, "website": 500}.get(key, 20000)
            if isinstance(value, str) and len(value) > limit:
                blockers.append(f"{key} exceeds target length")
    return {"schema": schema, "source_id": source["id"] if source else None,
            "source_row_count": len(sources),
            "source_selection": "lowest id, matching CompanyInfoService.getCompanyInfo()",
            "target_count": target_count, "target_status": target["status"] if target else None,
            "target_logo_media_id": target["logo_media_id"] if target else None,
            "different_public_fields": [field for field, target_field in zip(FIELDS, TARGET_FIELDS)
                                        if source and target and source[field] != target[target_field]],
            "field_presence": {key: source[key] is not None for key in FIELDS} if source else {},
            "ready": not blockers, "blockers": blockers}, source


def seed(env_file, apply):
    db = connect(env_file)
    try:
        with db.cursor() as cursor:
            cursor.execute("SET SESSION TRANSACTION ISOLATION LEVEL REPEATABLE READ")
            cursor.execute("START TRANSACTION" if apply else "START TRANSACTION READ ONLY")
            report, source = inspect(cursor)
            report.update({"applied": False, "status": "DRAFT"})
            if apply and report["ready"]:
                columns = ", ".join("`" + f + "`" for f in TARGET_FIELDS)
                params = [source[f] for f in FIELDS]
                cursor.execute(
                    "INSERT INTO website_company_profiles (id, " + columns + ", logo_media_id, status) "
                    "VALUES (1, " + ", ".join(["%s"] * len(params)) + ", NULL, 'DRAFT')", params,
                )
                cursor.execute("SELECT " + columns + ", status, logo_media_id "
                               "FROM website_company_profiles WHERE id=1")
                saved = cursor.fetchone()
                if any(saved[target] != source[field] for field, target in zip(FIELDS, TARGET_FIELDS)):
                    raise RuntimeError("Inserted company fields differ from source; rolled back")
                if saved["status"] != "DRAFT" or saved["logo_media_id"] is not None:
                    raise RuntimeError("Unexpected publication or logo reference; rolled back")
                db.commit()
                report["applied"] = True
            else:
                db.rollback()
            return report
    except Exception:
        db.rollback()
        raise
    finally:
        db.close()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--env-file", type=Path, default=ROOT / "backend/.env")
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--apply", action="store_true")
    args = parser.parse_args()
    report = seed(args.env_file, args.apply)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2, default=str) + "\n", encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, default=str))


if __name__ == "__main__":
    main()
