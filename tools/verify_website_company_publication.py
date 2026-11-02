"""Read-only verification of the published website company copy and audit rows."""
import argparse
import json
from pathlib import Path

from audit_website_banner_media import digest
from seed_website_company_profile import ROOT, FIELDS, TARGET_FIELDS, connect
from publish_website_company_copy import LOGO


def verify(env_file, file_root):
    db = connect(env_file)
    try:
        with db.cursor() as cursor:
            cursor.execute("SET SESSION TRANSACTION READ ONLY")
            cursor.execute("START TRANSACTION READ ONLY")
            cursor.execute("SELECT id, " + ", ".join("`" + f + "`" for f in FIELDS)
                           + " FROM company_info ORDER BY id LIMIT 1")
            source = cursor.fetchone()
            cursor.execute("SELECT id, status, logo_media_id, "
                           + ", ".join("`" + f + "`" for f in TARGET_FIELDS)
                           + " FROM website_company_profiles WHERE id=1")
            profile = cursor.fetchone()
            cursor.execute("SELECT year, title, description, sort_order FROM company_milestones "
                           "WHERE website_enabled=1 ORDER BY sort_order, id")
            old_milestones = cursor.fetchall()
            cursor.execute("SELECT milestone_year, title, description, sort_order, status "
                           "FROM website_company_milestones WHERE deleted_at IS NULL ORDER BY sort_order, id")
            new_milestones = cursor.fetchall()
            cursor.execute("SELECT id, status, storage_key, sha256, deleted_at FROM website_media_assets "
                           "WHERE id=%s", (profile["logo_media_id"],))
            logo = cursor.fetchone()
            cursor.execute("SELECT action, COUNT(*) AS n FROM audit_logs WHERE "
                           "(entity_type='WebsiteCompanyProfile' AND entity_id=1) OR "
                           "(entity_type='WebsiteCompanyMilestone' AND entity_id IN (1,2,3)) "
                           "GROUP BY action")
            audits = {row["action"]: row["n"] for row in cursor.fetchall()}
            cursor.execute("SELECT COUNT(*) AS n FROM media_accounts WHERE website_official=1")
            source_social = cursor.fetchone()["n"]
            cursor.execute("SELECT COUNT(*) AS n FROM website_social_links "
                           "WHERE deleted_at IS NULL AND status='PUBLISHED'")
            target_social = cursor.fetchone()["n"]
            db.rollback()
    finally:
        db.close()
    company_diff = [f for f, t in zip(FIELDS, TARGET_FIELDS) if source[f] != profile[t]]
    milestone_matches = len(old_milestones) == len(new_milestones) and all(
        (a["year"], a["title"], a["description"], a["sort_order"] or 0)
        == (b["milestone_year"], b["title"], b["description"], b["sort_order"])
        and b["status"] == "PUBLISHED" for a, b in zip(old_milestones, new_milestones)
    )
    website_root = (file_root / "website").resolve(strict=True)
    file = (file_root / logo["storage_key"]).resolve(strict=True)
    logo_matches = logo["status"] == "PUBLISHED" and logo["deleted_at"] is None
    logo_matches = logo_matches and file.is_relative_to(website_root)
    logo_matches = logo_matches and digest(file) == digest(LOGO) == logo["sha256"]
    ready = (profile["status"] == "PUBLISHED" and not company_diff and milestone_matches
             and logo_matches and source_social == target_social == 0
             and audits.get("WEBSITE_COMPANY_PROFILE_PUBLISH", 0) >= 1
             and audits.get("WEBSITE_COMPANY_MILESTONE_PUBLISH", 0) >= len(new_milestones))
    return {"read_only": True, "ready_for_source_switch": ready,
            "profile_status": profile["status"], "public_field_differences": company_diff,
            "source_milestones": len(old_milestones), "published_milestones": len(new_milestones),
            "milestone_fields_match": milestone_matches,
            "source_social_links": source_social, "published_social_links": target_social,
            "logo_media_id": logo["id"], "logo_file_hash_matches": logo_matches,
            "publication_audit_counts": audits}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--env-file", type=Path, default=ROOT / "backend/.env")
    parser.add_argument("--file-root", type=Path, default=ROOT / "storage/lq-files")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    result = verify(args.env_file, args.file_root.resolve(strict=True))
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(result, ensure_ascii=False))


if __name__ == "__main__":
    main()
