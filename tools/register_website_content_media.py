"""Register staged content images as website media drafts; never rebind public content.

Defaults to a read-only preflight. --apply inserts only absent media rows, in one
transaction, and records the old URL -> website media ID mapping.
"""
import argparse
import json
from pathlib import Path

import pymysql

from audit_website_banner_media import config_value, digest


ROOT = Path(__file__).resolve().parents[1]
MIME = {".jpg": "image/jpeg", ".jpeg": "image/jpeg", ".png": "image/png",
        ".webp": "image/webp", ".gif": "image/gif"}


def register(stage_file, inventory_file, file_root, env_file, apply):
    stage = json.loads(stage_file.read_text(encoding="utf-8"))
    inventory = json.loads(inventory_file.read_text(encoding="utf-8"))
    sources = {row["url"]: row for row in inventory["source_files"]}
    if len(stage["mapping"]) != len(sources):
        raise ValueError("Staged mapping and audited source count differ")
    website_root = (file_root / "website").resolve(strict=True)
    checked = []
    for row in stage["mapping"]:
        source = sources.get(row["url"])
        if source is None or source["candidate_count"] != 1:
            raise ValueError("Unknown or ambiguous source URL")
        original = source["candidates"][0]
        if row["sha256"] != original["sha256"]:
            raise ValueError("Source hash changed since staging")
        target = (file_root / row["storage_key"]).resolve(strict=True)
        if not target.is_relative_to(website_root) or digest(target) != row["sha256"]:
            raise ValueError("Website media file is missing or differs from source")
        mime = MIME.get(target.suffix.lower())
        if mime is None or target.stat().st_size != original["size_bytes"]:
            raise ValueError("Staged image extension or size is invalid")
        checked.append((row, original, mime))
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
    result = []
    inserted = 0
    try:
        with connection.cursor() as cursor:
            if not apply:
                cursor.execute("SET SESSION TRANSACTION READ ONLY")
                cursor.execute("START TRANSACTION READ ONLY")
            for row, original, mime in checked:
                cursor.execute("""SELECT id, sha256, size_bytes, status FROM website_media_assets
                    WHERE storage_key=%s""", (row["storage_key"],))
                existing = cursor.fetchone()
                if existing and (existing["sha256"] != row["sha256"]
                                 or existing["size_bytes"] != original["size_bytes"]):
                    raise ValueError("Conflicting website media record")
                if row["status"] == "existing_media" and (existing is None or existing["id"] != row["media_id"]):
                    raise ValueError("Previously mapped website media changed")
                if existing is None and apply:
                    filename = Path(original["relative_path"]).name
                    cursor.execute("""INSERT INTO website_media_assets
                        (storage_key, original_filename, display_name, media_type, mime_type,
                         size_bytes, sha256, status)
                        VALUES (%s, %s, %s, 'IMAGE', %s, %s, %s, 'DRAFT')""",
                        (row["storage_key"], filename, filename, mime, original["size_bytes"], row["sha256"]))
                    existing = {"id": cursor.lastrowid, "status": "DRAFT"}
                    inserted += 1
                result.append({"legacy_url": row["url"], "website_media_id": existing["id"] if existing else None,
                               "storage_key": row["storage_key"], "status": existing["status"] if existing else "PENDING"})
            if apply:
                connection.commit()
            else:
                connection.rollback()
    except Exception:
        connection.rollback()
        raise
    finally:
        connection.close()
    return {"applied": apply, "inserted_draft_media": inserted, "mapped": len(result),
            "public_references_changed": False, "mapping": result}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--stage", type=Path, default=ROOT / "docs/website-content-media-stage-20261030.json")
    parser.add_argument("--inventory", type=Path, default=ROOT / "docs/website-content-media-inventory-20261030.json")
    parser.add_argument("--file-root", type=Path, default=ROOT / "storage/lq-files")
    parser.add_argument("--env-file", type=Path, default=ROOT / "backend/.env")
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--apply", action="store_true")
    args = parser.parse_args()
    report = register(args.stage, args.inventory, args.file_root.resolve(strict=True), args.env_file, args.apply)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({key: report[key] for key in ("applied", "inserted_draft_media", "mapped",
                                                  "public_references_changed")}, ensure_ascii=False))


if __name__ == "__main__":
    main()
