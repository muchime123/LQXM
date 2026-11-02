"""Read-only exact-field verification after Banner and page-section media cutover."""
import argparse
import json
from collections import defaultdict
from pathlib import Path

import pymysql

from audit_website_banner_media import config_value
from cutover_website_content_media import replace_fields


ROOT = Path(__file__).resolve().parents[1]


def verify(backup_file, plan_file, env_file):
    backup = json.loads(backup_file.read_text(encoding="utf-8"))
    plan = json.loads(plan_file.read_text(encoding="utf-8"))
    changes = plan["changes"]
    by_section = defaultdict(list)
    by_banner = {}
    for change in changes:
        if change["kind"] == "banner":
            by_banner[change["id"]] = change
        else:
            by_section[change["id"]].append(change)
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
            cursor.execute("SELECT id, image_url, website_media_id FROM website_banners WHERE id IN (4,5)")
            banners = {row["id"]: row for row in cursor.fetchall()}
            cursor.execute("SELECT id, image_url, content FROM website_page_sections WHERE id IN (3,8,9,14,16,19)")
            sections = {row["id"]: row for row in cursor.fetchall()}
            cursor.execute("SELECT id, status FROM website_media_assets WHERE id IN (2,3,140,141,142,143,144,145,146)")
            media = {row["id"]: row["status"] for row in cursor.fetchall()}
            connection.rollback()
    finally:
        connection.close()
    issues = []
    for original in backup["banners"]:
        current = banners.get(original["id"])
        change = by_banner.get(original["id"])
        if not current or not change or current["image_url"] != original["image_url"] or current["website_media_id"] != change["new_media_id"]:
            issues.append(f"Banner {original['id']} differs from exact cutover plan")
    for original in backup["page_sections"]:
        current = sections.get(original["id"])
        if not current or not by_section.get(original["id"]):
            issues.append(f"Section {original['id']} missing from cutover plan")
            continue
        image, content = replace_fields(original, by_section[original["id"]])
        if current["image_url"] != image or current["content"] != content:
            issues.append(f"Section {original['id']} differs beyond exact URL replacements")
    for media_id in {change["new_media_id"] for change in changes}:
        if media.get(media_id) != "PUBLISHED":
            issues.append(f"Website media {media_id} is not published")
    return {"read_only": True, "verified": not issues, "issues": issues,
            "banners": len(backup["banners"]), "sections": len(backup["page_sections"]),
            "references": len(changes), "public_http_verified": False}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--backup", type=Path, default=ROOT / "docs/website-content-media-cutover-backup-20261030.json")
    parser.add_argument("--plan", type=Path, default=ROOT / "docs/website-content-media-cutover-preflight-20261030.json")
    parser.add_argument("--env-file", type=Path, default=ROOT / "backend/.env")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    result = verify(args.backup, args.plan, args.env_file)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(result, ensure_ascii=False))


if __name__ == "__main__":
    main()
