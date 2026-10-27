"""Idempotently register staged Banner files as DRAFT website media.

Default mode is read-only preflight. --apply copies files into app.files.root/website
and inserts media rows, but never changes Banner references or publication state.
"""
import argparse
import json
import shutil
from pathlib import Path

import pymysql

from audit_website_banner_media import config_value, digest


def import_media(stage_file: Path, stage_root: Path, destination_root: Path,
                 env_file: Path, apply: bool) -> dict:
    data = json.loads(stage_file.read_text(encoding="utf-8"))
    entries = [item for item in data["banners"] if item.get("storage_key")]
    if len({item["storage_key"] for item in entries}) != len(entries):
        raise ValueError("duplicate Banner storage key requires manual mapping")
    app_root = destination_root.resolve(strict=True)
    website_root = app_root / "website"
    for item in entries:
        key = item["storage_key"]
        source = (stage_root / key).resolve(strict=True)
        if not source.is_relative_to(stage_root) or not key.startswith("website/images/"):
            raise ValueError("invalid staged Banner media path")
        if digest(source) != item["sha256"] or source.stat().st_size != item["size_bytes"]:
            raise ValueError(f"staged file changed: Banner {item['banner_id']}")
        with source.open("rb") as image:
            if image.read(8) != b"\x89PNG\r\n\x1a\n":
                raise ValueError(f"Banner file is not PNG: {item['banner_id']}")
    password = config_value("DB_PASSWORD", env_file)
    if password is None:
        raise RuntimeError("DB_PASSWORD is not available")
    connection = pymysql.connect(host="127.0.0.1", port=3306,
                                 user=config_value("DB_USERNAME", env_file) or "root",
                                 password=password, database="product_archive",
                                 charset="utf8mb4", cursorclass=pymysql.cursors.DictCursor,
                                 autocommit=False)
    results = []
    try:
        with connection.cursor() as cursor:
            if not apply:
                cursor.execute("SET SESSION TRANSACTION READ ONLY")
                cursor.execute("START TRANSACTION READ ONLY")
            for item in entries:
                key = item["storage_key"]
                cursor.execute("SELECT id, sha256, size_bytes, status FROM website_media_assets WHERE storage_key = %s", (key,))
                existing = cursor.fetchone()
                if existing and (existing["sha256"] != item["sha256"] or existing["size_bytes"] != item["size_bytes"]):
                    raise ValueError(f"media record conflicts with staged file: Banner {item['banner_id']}")
                if apply:
                    destination = (app_root / key).resolve()
                    if not destination.is_relative_to(website_root):
                        raise ValueError("destination escapes website directory")
                    destination.parent.mkdir(parents=True, exist_ok=True)
                    if not destination.parent.resolve(strict=True).is_relative_to(app_root.resolve(strict=True)):
                        raise ValueError("destination directory escapes file root")
                    try:
                        with (stage_root / key).open("rb") as source, destination.open("xb") as target:
                            shutil.copyfileobj(source, target, 1024 * 1024)
                    except FileExistsError:
                        pass
                    if digest(destination) != item["sha256"]:
                        raise ValueError(f"destination file checksum mismatch: Banner {item['banner_id']}")
                    if not existing:
                        original = Path(item["legacy_source"]).name
                        cursor.execute("INSERT INTO website_media_assets "
                                       "(storage_key, original_filename, display_name, media_type, mime_type, size_bytes, sha256, status) "
                                       "VALUES (%s, %s, %s, 'IMAGE', 'image/png', %s, %s, 'DRAFT')",
                                       (key, original, original, item["size_bytes"], item["sha256"]))
                        existing = {"id": cursor.lastrowid, "status": "DRAFT"}
                results.append({"banner_id": item["banner_id"], "storage_key": key,
                                "website_media_id": existing["id"] if existing else None,
                                "status": existing["status"] if existing else "PENDING_DRAFT_IMPORT"})
            if apply:
                connection.commit()
            else:
                connection.rollback()
    except Exception:
        connection.rollback()
        raise
    finally:
        connection.close()
    return {"applied": apply, "media": results}


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--stage-file", type=Path, required=True)
    parser.add_argument("--stage-root", type=Path, required=True)
    parser.add_argument("--destination-root", type=Path, required=True)
    parser.add_argument("--env-file", type=Path, default=Path("backend/.env"))
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--apply", action="store_true")
    args = parser.parse_args()
    result = import_media(args.stage_file, args.stage_root.resolve(strict=True),
                          args.destination_root, args.env_file, args.apply)
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"applied": result["applied"], "mapped": len(result["media"]),
                      "drafts": sum(row["status"] == "DRAFT" for row in result["media"])}, ensure_ascii=False))


if __name__ == "__main__":
    main()
