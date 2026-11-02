"""Guarded Banner/section media cutover with original-field backup.

Run without --apply for read-only preflight. --apply publishes mapped website
media and replaces exact legacy references in one database transaction.
"""
import argparse
import json
from collections import defaultdict
from pathlib import Path

import pymysql

from audit_website_banner_media import config_value, digest
from preflight_website_content_media_cutover import preflight


ROOT = Path(__file__).resolve().parents[1]


def replace_fields(row, changes):
    image = row["image_url"]
    content = row["content"]
    for change in changes:
        field = change["field"]
        value = image if field == "image_url" else content
        if value is None or value.count(change["old_url"]) != 1:
            raise ValueError("Current section field no longer matches exact cutover plan")
        value = value.replace(change["old_url"], change["new_url"])
        if field == "image_url":
            image = value
        else:
            content = value
    return image, content


def cutover(inventory_file, mapping_file, file_root, env_file, backup_file, apply):
    plan = preflight(inventory_file, mapping_file, file_root, env_file)
    if not apply or not plan["ready"]:
        return {"applied": False, "preflight": plan}
    mapping = json.loads(mapping_file.read_text(encoding="utf-8"))["mapping"]
    inventory = json.loads(inventory_file.read_text(encoding="utf-8"))
    hashes = {row["url"]: row["candidates"][0]["sha256"]
              for row in inventory["source_files"] if row["candidate_count"] == 1}
    by_id = {row["website_media_id"]: row for row in mapping}
    changes_by_section = defaultdict(list)
    banner_changes = []
    for change in plan["changes"]:
        (banner_changes if change["kind"] == "banner" else changes_by_section[change["id"]]).append(change)
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
            cursor.execute("START TRANSACTION")
            cursor.execute("SELECT id, image_url, website_media_id FROM website_banners WHERE id IN (4,5) FOR UPDATE")
            banners = {row["id"]: row for row in cursor.fetchall()}
            section_ids = sorted(changes_by_section)
            cursor.execute("SELECT id, image_url, content, enabled, deleted_at FROM website_page_sections WHERE id IN (" + ",".join(["%s"] * len(section_ids)) + ") FOR UPDATE", section_ids)
            sections = {row["id"]: row for row in cursor.fetchall()}
            media_ids = sorted(by_id)
            cursor.execute("SELECT id, storage_key, sha256, status, media_type, deleted_at FROM website_media_assets WHERE id IN (" + ",".join(["%s"] * len(media_ids)) + ") FOR UPDATE", media_ids)
            media = {row["id"]: row for row in cursor.fetchall()}
            if set(banners) != {4, 5} or set(sections) != set(section_ids) or set(media) != set(media_ids):
                raise ValueError("Cutover rows changed after preflight")
            for change in banner_changes:
                row = banners[change["id"]]
                if row["website_media_id"] is not None or row["image_url"] != change["old_url"]:
                    raise ValueError("Banner changed after preflight")
            updated_sections = {}
            for section_id, changes in changes_by_section.items():
                row = sections[section_id]
                if not row["enabled"] or row["deleted_at"] is not None:
                    raise ValueError("Section is no longer public")
                updated_sections[section_id] = replace_fields(row, changes)
            website_root = (file_root / "website").resolve(strict=True)
            for media_id, mapped in by_id.items():
                row = media[media_id]
                target = (file_root / row["storage_key"]).resolve(strict=True)
                expected_hash = hashes[mapped["legacy_url"]]
                if (row["storage_key"] != mapped["storage_key"] or row["sha256"] != expected_hash
                        or row["media_type"] != "IMAGE" or row["deleted_at"] is not None
                        or row["status"] not in ("DRAFT", "PUBLISHED")
                        or not target.is_relative_to(website_root) or digest(target) != expected_hash):
                    raise ValueError("Website media changed after preflight")
            backup = {"banners": list(banners.values()), "page_sections": list(sections.values()),
                      "media_statuses": {str(key): row["status"] for key, row in media.items()}}
            backup_file.parent.mkdir(parents=True, exist_ok=True)
            if backup_file.exists():
                raise ValueError("Backup file already exists; refusing to overwrite")
            backup_file.write_text(json.dumps(backup, ensure_ascii=False, indent=2, default=str) + "\n",
                                   encoding="utf-8")
            for media_id, row in media.items():
                if row["status"] == "DRAFT":
                    cursor.execute("UPDATE website_media_assets SET status='PUBLISHED' WHERE id=%s AND status='DRAFT'", (media_id,))
                    if cursor.rowcount != 1:
                        raise ValueError("Website media publication failed")
            for change in banner_changes:
                cursor.execute("""UPDATE website_banners SET website_media_id=%s
                    WHERE id=%s AND website_media_id IS NULL AND image_url=%s AND website_enabled=TRUE AND deleted_at IS NULL""",
                    (change["new_media_id"], change["id"], change["old_url"]))
                if cursor.rowcount != 1:
                    raise ValueError("Banner image rebind failed")
            for section_id, (image, content) in updated_sections.items():
                old = sections[section_id]
                cursor.execute("""UPDATE website_page_sections SET image_url=%s, content=%s
                    WHERE id=%s AND enabled=TRUE AND deleted_at IS NULL
                      AND image_url <=> %s AND content <=> %s""",
                    (image, content, section_id, old["image_url"], old["content"]))
                if cursor.rowcount != 1:
                    raise ValueError("Section image rebind failed")
            connection.commit()
    except Exception:
        connection.rollback()
        raise
    finally:
        connection.close()
    return {"applied": True, "published_media": len(by_id), "rebound_banners": len(banner_changes),
            "rebound_sections": len(changes_by_section), "replaced_section_urls":
                sum(len(rows) for rows in changes_by_section.values()),
            "backup_file": str(backup_file), "public_http_verified": False}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--inventory", type=Path, default=ROOT / "docs/website-content-media-inventory-20261030.json")
    parser.add_argument("--mapping", type=Path, default=ROOT / "docs/website-content-media-register-20261030.json")
    parser.add_argument("--file-root", type=Path, default=ROOT / "storage/lq-files")
    parser.add_argument("--env-file", type=Path, default=ROOT / "backend/.env")
    parser.add_argument("--backup", type=Path, default=ROOT / "docs/website-content-media-cutover-backup-20261030.json")
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--apply", action="store_true")
    args = parser.parse_args()
    result = cutover(args.inventory, args.mapping, args.file_root.resolve(strict=True),
                     args.env_file, args.backup, args.apply)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2, default=str) + "\n", encoding="utf-8")
    print(json.dumps({key: result.get(key) for key in ("applied", "published_media", "rebound_banners",
                                                     "rebound_sections", "replaced_section_urls")}, ensure_ascii=False))


if __name__ == "__main__":
    main()
