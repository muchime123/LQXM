"""Read-only baseline for the two legacy public company lists and native drafts."""
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
            cursor.execute("SELECT id, year, title, description, sort_order, file_name "
                           "FROM company_milestones WHERE website_enabled=1 "
                           "ORDER BY sort_order, id")
            milestones = cursor.fetchall()
            cursor.execute("SELECT id, platform, account_name, link FROM media_accounts "
                           "WHERE website_official=1 ORDER BY platform, id")
            social = cursor.fetchall()
            cursor.execute("SELECT COUNT(*) AS n FROM website_company_milestones")
            native_milestones = cursor.fetchone()["n"]
            cursor.execute("SELECT id, milestone_year, title, description, sort_order, status "
                           "FROM website_company_milestones ORDER BY sort_order, id")
            native_milestone_rows = cursor.fetchall()
            cursor.execute("SELECT COUNT(*) AS n FROM website_social_links")
            native_social = cursor.fetchone()["n"]
            db.rollback()
    finally:
        db.close()
    exact_fields = len(milestones) == len(native_milestone_rows) and all(
        (a["year"], a["title"], a["description"], a["sort_order"] or 0)
        == (b["milestone_year"], b["title"], b["description"], b["sort_order"])
        for a, b in zip(milestones, native_milestone_rows)
    )
    return {"read_only": True, "legacy_public_milestones": len(milestones),
            "legacy_public_milestone_ids": [row["id"] for row in milestones],
            "milestones_with_file": [row["id"] for row in milestones if row["file_name"]],
            "native_milestones": native_milestones,
            "native_milestone_fields_match": exact_fields,
            "native_milestone_statuses": sorted({row["status"] for row in native_milestone_rows}),
            "legacy_public_social_links": len(social),
            "legacy_public_social_ids": [row["id"] for row in social],
            "social_missing_link_ids": [row["id"] for row in social if not row["link"]],
            "native_social_links": native_social}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--env-file", type=Path, default=ROOT / "backend/.env")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    result = audit(args.env_file)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(result, ensure_ascii=False))


if __name__ == "__main__":
    main()
