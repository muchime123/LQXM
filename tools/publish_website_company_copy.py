"""Guarded one-time publication of an exact website-owned company copy.

Default mode is read-only. --apply atomically publishes the company draft,
its website Logo, and exact draft milestone copies. The public source switch
is deliberately separate. Audit rows and a status backup are written.
"""
import argparse
import json
from pathlib import Path

from audit_website_banner_media import digest
from seed_website_company_profile import ROOT, FIELDS, TARGET_FIELDS, connect


LOGO = ROOT / "corporate-website/public/images/logo-only.png"


def check(cursor, file_root):
    cursor.execute("SELECT id, " + ", ".join("`" + f + "`" for f in FIELDS)
                   + " FROM company_info ORDER BY id LIMIT 1")
    source = cursor.fetchone()
    cursor.execute("SELECT id, status, published_at, logo_media_id, "
                   + ", ".join("`" + f + "`" for f in TARGET_FIELDS)
                   + " FROM website_company_profiles WHERE id=1")
    profile = cursor.fetchone()
    cursor.execute("SELECT id, year, title, description, sort_order FROM company_milestones "
                   "WHERE website_enabled=1 ORDER BY sort_order, id")
    source_milestones = cursor.fetchall()
    cursor.execute("SELECT id, milestone_year, title, description, sort_order, status, published_at "
                   "FROM website_company_milestones WHERE deleted_at IS NULL ORDER BY sort_order, id")
    milestones = cursor.fetchall()
    cursor.execute("SELECT COUNT(*) AS n FROM media_accounts WHERE website_official=1")
    old_social = cursor.fetchone()["n"]
    cursor.execute("SELECT COUNT(*) AS n FROM website_social_links WHERE deleted_at IS NULL")
    new_social = cursor.fetchone()["n"]
    media = None
    if profile and profile["logo_media_id"] is not None:
        cursor.execute("SELECT id, storage_key, sha256, size_bytes, media_type, mime_type, status, deleted_at "
                       "FROM website_media_assets WHERE id=%s", (profile["logo_media_id"],))
        media = cursor.fetchone()
    blockers = []
    if not source or not profile or profile["status"] != "DRAFT":
        blockers.append("Source or website company draft is missing")
    if source and profile and any(source[f] != profile[t] for f, t in zip(FIELDS, TARGET_FIELDS)):
        blockers.append("Public company fields differ")
    if not source_milestones or len(source_milestones) != len(milestones):
        blockers.append("Public milestone counts differ or source has no milestones")
    if any((a["year"], a["title"], a["description"], a["sort_order"] or 0)
           != (b["milestone_year"], b["title"], b["description"], b["sort_order"])
           or b["status"] != "DRAFT" for a, b in zip(source_milestones, milestones)):
        blockers.append("Public milestones differ from website drafts")
    if old_social != 0 or new_social != 0:
        blockers.append("Official social links require separate parity review")
    source_logo = LOGO.resolve(strict=True)
    expected_sha = digest(source_logo)
    if (media is None or media["deleted_at"] is not None or media["media_type"] != "IMAGE"
            or media["mime_type"] != "image/png" or media["status"] != "DRAFT"
            or media["sha256"] != expected_sha or media["size_bytes"] != source_logo.stat().st_size):
        blockers.append("Website Logo media is missing or differs from the current website Logo")
    else:
        website_root = (file_root / "website").resolve(strict=True)
        file = (file_root / media["storage_key"]).resolve(strict=True)
        if not file.is_relative_to(website_root) or digest(file) != expected_sha:
            blockers.append("Website Logo file is outside website storage or differs")
    if profile:
        if not profile["company_name"] or not profile["main_business"]:
            blockers.append("Company name or business description is blank")
        if not profile["phone"] and not profile["email"]:
            blockers.append("Public contact is blank")
        if not profile["office_address"] and not profile["factory_address"]:
            blockers.append("Public address is blank")
    return {"ready": not blockers, "blockers": blockers,
            "source_company_id": source["id"] if source else None,
            "public_field_differences": 0 if source and profile and not any(
                source[f] != profile[t] for f, t in zip(FIELDS, TARGET_FIELDS)) else None,
            "source_milestone_ids": [row["id"] for row in source_milestones],
            "website_milestone_ids": [row["id"] for row in milestones],
            "source_social_count": old_social, "website_social_count": new_social,
            "logo_media_id": media["id"] if media else None,
            "profile_status_before": profile["status"] if profile else None,
            "logo_status_before": media["status"] if media else None}, profile, milestones, media


def publish(env_file, file_root, backup_file, apply):
    db = connect(env_file)
    try:
        with db.cursor() as cursor:
            cursor.execute("START TRANSACTION" if apply else "START TRANSACTION READ ONLY")
            if apply:
                cursor.execute("SELECT id FROM website_company_profiles WHERE id=1 FOR UPDATE")
                cursor.execute("SELECT id FROM website_company_milestones WHERE deleted_at IS NULL FOR UPDATE")
            report, profile, milestones, media = check(cursor, file_root)
            report.update({"applied": False, "public_source_changed": False})
            if apply and report["ready"]:
                backup = {"profile": {"id": 1, "status": profile["status"],
                                      "published_at": profile["published_at"]},
                          "logo": {"id": media["id"], "status": media["status"]},
                          "milestones": [{"id": row["id"], "status": row["status"],
                                          "published_at": row["published_at"]} for row in milestones]}
                if backup_file.exists():
                    raise ValueError("Status backup already exists; use a new backup path")
                backup_file.parent.mkdir(parents=True, exist_ok=True)
                backup_file.write_text(json.dumps(backup, ensure_ascii=False, indent=2, default=str) + "\n",
                                       encoding="utf-8")
                cursor.execute("UPDATE website_media_assets SET status='PUBLISHED' "
                               "WHERE id=%s AND status='DRAFT' AND deleted_at IS NULL", (media["id"],))
                if cursor.rowcount != 1:
                    raise RuntimeError("Logo media changed concurrently")
                cursor.execute("UPDATE website_company_profiles SET status='PUBLISHED', published_at=NOW() "
                               "WHERE id=1 AND status='DRAFT' AND logo_media_id=%s", (media["id"],))
                if cursor.rowcount != 1:
                    raise RuntimeError("Company draft changed concurrently")
                for row in milestones:
                    cursor.execute("UPDATE website_company_milestones SET status='PUBLISHED', "
                                   "published_at=NOW() WHERE id=%s AND status='DRAFT' AND deleted_at IS NULL",
                                   (row["id"],))
                    if cursor.rowcount != 1:
                        raise RuntimeError("Milestone changed concurrently")
                    cursor.execute("INSERT INTO audit_logs (entity_type, entity_id, model_name, action, changes, created_at) "
                                   "VALUES ('WebsiteCompanyMilestone', %s, %s, 'WEBSITE_COMPANY_MILESTONE_PUBLISH', "
                                   "'发布官网发展历程', NOW())", (row["id"], row["title"][:200]))
                cursor.execute("INSERT INTO audit_logs (entity_type, entity_id, model_name, action, changes, created_at) "
                               "VALUES ('WebsiteCompanyProfile', 1, %s, 'WEBSITE_COMPANY_PROFILE_PUBLISH', "
                               "'发布官网独立公司介绍', NOW())", (profile["company_name"][:200],))
                db.commit()
                report["applied"] = True
                report["published_milestones"] = len(milestones)
                report["backup"] = str(backup_file.relative_to(ROOT))
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
    parser.add_argument("--file-root", type=Path, default=ROOT / "storage/lq-files")
    parser.add_argument("--backup", type=Path, default=ROOT / "docs/website-company-publish-backup-20261102.json")
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--apply", action="store_true")
    args = parser.parse_args()
    report = publish(args.env_file, args.file_root.resolve(strict=True), args.backup, args.apply)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False))


if __name__ == "__main__":
    main()
