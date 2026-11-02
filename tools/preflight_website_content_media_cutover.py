"""Read-only preflight for replacing legacy public Banner/section image references.

Requires the audited URL mapping and exact current DB fields. No mutation.
"""
import argparse
import json
from collections import Counter
from datetime import date
from pathlib import Path

import pymysql

from audit_website_banner_media import config_value, digest
from audit_website_content_media import references


ROOT = Path(__file__).resolve().parents[1]


def preflight(inventory_file, mapping_file, file_root, env_file):
    inventory = json.loads(inventory_file.read_text(encoding="utf-8"))
    mapping = json.loads(mapping_file.read_text(encoding="utf-8"))["mapping"]
    by_url = {row["legacy_url"]: row for row in mapping}
    if len(by_url) != len(mapping) or any(row["website_media_id"] is None for row in mapping):
        raise ValueError("Website media mapping is incomplete or ambiguous")
    source_hash = {row["url"]: row["candidates"][0]["sha256"]
                   for row in inventory["source_files"] if row["candidate_count"] == 1}
    if set(by_url) != set(source_hash):
        raise ValueError("Mapping does not cover every audited source URL")
    expected = {(row["kind"], row["id"]): row for row in inventory["records"]
                if any(ref["kind"] == "internal_archive" for ref in row["references"])}
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
            cursor.execute("SELECT id, image_url, website_media_id, website_enabled, deleted_at, start_date, end_date FROM website_banners WHERE id IN (4,5)")
            banners = {row["id"]: row for row in cursor.fetchall()}
            ids = sorted(key[1] for key in expected if key[0] == "page_section")
            cursor.execute("SELECT id, page_key, image_url, content, enabled, deleted_at FROM website_page_sections WHERE id IN (" + ",".join(["%s"] * len(ids)) + ")", ids)
            sections = {row["id"]: row for row in cursor.fetchall()}
            media_ids = sorted({row["website_media_id"] for row in mapping})
            cursor.execute("SELECT id, storage_key, sha256, status, media_type, deleted_at FROM website_media_assets WHERE id IN (" + ",".join(["%s"] * len(media_ids)) + ")", media_ids)
            media = {row["id"]: row for row in cursor.fetchall()}
            connection.rollback()
    finally:
        connection.close()
    issues = []
    changes = []
    today = date.today()
    for key, snapshot in expected.items():
        kind, record_id = key
        row = banners.get(record_id) if kind == "banner" else sections.get(record_id)
        if row is None or row["deleted_at"] is not None:
            issues.append(f"{kind} {record_id} missing or deleted")
            continue
        if kind == "banner" and (not row["website_enabled"] or row["website_media_id"] is not None
                                 or row["start_date"] is not None and row["start_date"] > today
                                 or row["end_date"] is not None and row["end_date"] < today):
            issues.append(f"banner {record_id} no longer uses legacy public image")
            continue
        if kind == "page_section" and (not row["enabled"] or row["page_key"] == "MOLDS"):
            issues.append(f"page_section {record_id} is no longer public")
            continue
        fields = ("image_url",) if kind == "banner" else ("image_url", "content")
        current = Counter((field, ref["url"]) for field in fields
                          for ref in references(row[field]) if ref["kind"] == "internal_archive")
        audited = Counter((ref["field"], ref["url"]) for ref in snapshot["references"]
                          if ref["kind"] == "internal_archive")
        if current != audited:
            issues.append(f"{kind} {record_id} media references changed since inventory")
            continue
        for (field, url), count in sorted(current.items()):
            if url not in by_url or count != 1 or str(row[field]).count(url) != 1:
                issues.append(f"{kind} {record_id} {field} URL is ambiguous")
                continue
            changes.append({"kind": kind, "id": record_id, "field": field,
                            "old_url": url, "new_media_id": by_url[url]["website_media_id"],
                            "new_url": f"/api/website/media/{by_url[url]['website_media_id']}/content"})
    website_root = (file_root / "website").resolve(strict=True)
    for url, mapped in by_url.items():
        asset = media.get(mapped["website_media_id"])
        if (asset is None or asset["deleted_at"] is not None or asset["media_type"] != "IMAGE"
                or asset["status"] not in ("DRAFT", "PUBLISHED")
                or asset["storage_key"] != mapped["storage_key"] or asset["sha256"] != source_hash[url]):
            issues.append(f"website media {mapped['website_media_id']} changed after registration")
            continue
        path = (file_root / asset["storage_key"]).resolve()
        if not path.is_relative_to(website_root) or not path.is_file() or digest(path) != source_hash[url]:
            issues.append(f"website media {mapped['website_media_id']} file is missing or changed")
    if len(changes) != 14:
        issues.append(f"expected 14 exact substitutions, found {len(changes)}")
    return {"read_only": True, "ready": not issues, "issues": issues,
            "banner_count": sum(row["kind"] == "banner" for row in changes),
            "section_references": sum(row["kind"] == "page_section" for row in changes),
            "changes": changes,
            "runtime_verification_available": False,
            "note": "Database/file preflight only; public HTTP and browser parity require a running backend."}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--inventory", type=Path, default=ROOT / "docs/website-content-media-inventory-20261030.json")
    parser.add_argument("--mapping", type=Path, default=ROOT / "docs/website-content-media-register-20261030.json")
    parser.add_argument("--file-root", type=Path, default=ROOT / "storage/lq-files")
    parser.add_argument("--env-file", type=Path, default=ROOT / "backend/.env")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    result = preflight(args.inventory, args.mapping, args.file_root.resolve(strict=True), args.env_file)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({key: result[key] for key in ("ready", "banner_count", "section_references", "issues")},
                     ensure_ascii=False))


if __name__ == "__main__":
    main()
