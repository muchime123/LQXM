"""Register currently displayed product images as website-owned DRAFT media.

Reads the reviewed public snapshot manifest and isolated copied files. Default
mode is a read-only preflight; --apply copies verified bytes into app.files.root
and inserts missing website_media_assets rows. Product records, references and
publication status are never changed.
"""
import argparse
import json
import re
import shutil
from pathlib import Path

import pymysql

from audit_website_banner_media import config_value, digest


SIGNATURES = {
    "image/png": lambda data: data.startswith(b"\x89PNG\r\n\x1a\n"),
    "image/jpeg": lambda data: data.startswith(b"\xff\xd8\xff"),
    "image/gif": lambda data: data.startswith((b"GIF87a", b"GIF89a")),
    "image/webp": lambda data: data.startswith(b"RIFF") and data[8:12] == b"WEBP",
}


def selected_manifest(manifest_file: Path, stage_root: Path) -> tuple[list[dict], list[dict]]:
    manifest = json.loads(manifest_file.read_text(encoding="utf-8"))
    references = [row for row in manifest["references"]
                  if row["publication_review"] == "DISPLAY_CANDIDATE"]
    keys = {row["storage_key"] for row in references}
    assets = [row for row in manifest["media_assets"] if row["storage_key"] in keys]
    if len(assets) != len(keys) or len(references) != 142:
        raise ValueError("display selection differs from reviewed 2026-10-26 snapshot")
    for item in assets:
        key = item["storage_key"]
        if (not re.fullmatch(r"website/images/[a-f0-9]{64}\.(?:png|jpg|jpeg|gif|webp)", key)
                or item["media_type"] != "IMAGE" or item["mime_type"] not in SIGNATURES
                or item["status"] != "DRAFT" or key.split("/")[-1][:64] != item["sha256"]):
            raise ValueError("invalid website image manifest entry: " + key)
        source = (stage_root / key).resolve(strict=True)
        if not source.is_relative_to(stage_root) or source.stat().st_size != item["size_bytes"]:
            raise ValueError("staged image missing or size changed: " + key)
        with source.open("rb") as content:
            if not SIGNATURES[item["mime_type"]](content.read(16)):
                raise ValueError("staged image signature differs from manifest: " + key)
        if digest(source) != item["sha256"]:
            raise ValueError("staged image checksum differs from manifest: " + key)
    return assets, references


def import_media(manifest_file: Path, stage_root: Path, destination_root: Path,
                 env_file: Path, apply: bool) -> dict:
    stage_root = stage_root.resolve(strict=True)
    destination_root = destination_root.resolve(strict=True)
    website_root = destination_root / "website"
    assets, references = selected_manifest(manifest_file, stage_root)
    password = config_value("DB_PASSWORD", env_file)
    if password is None:
        raise RuntimeError("DB_PASSWORD is unavailable")
    connection = pymysql.connect(host="127.0.0.1", port=3306,
                                 user=config_value("DB_USERNAME", env_file) or "root",
                                 password=password, database="product_archive", charset="utf8mb4",
                                 cursorclass=pymysql.cursors.DictCursor, autocommit=False)
    mapping = {}
    copied = 0
    registered = 0
    try:
        with connection.cursor() as cursor:
            if not apply:
                cursor.execute("SET SESSION TRANSACTION READ ONLY")
                cursor.execute("START TRANSACTION READ ONLY")
            else:
                cursor.execute("START TRANSACTION")
            for item in assets:
                key = item["storage_key"]
                destination = (destination_root / key).resolve()
                if not destination.is_relative_to(website_root):
                    raise ValueError("destination escapes website directory: " + key)
                if destination.exists() and digest(destination) != item["sha256"]:
                    raise ValueError("existing destination checksum mismatch: " + key)
                cursor.execute("SELECT id, sha256, size_bytes, media_type, mime_type, status, deleted_at "
                               "FROM website_media_assets WHERE storage_key=%s", (key,))
                existing = cursor.fetchone()
                if existing and (existing["sha256"] != item["sha256"]
                                 or existing["size_bytes"] != item["size_bytes"]
                                 or existing["media_type"] != "IMAGE"
                                 or existing["mime_type"] != item["mime_type"]
                                 or existing["deleted_at"] is not None
                                 or existing["status"] == "ARCHIVED"):
                    raise ValueError("website media record conflicts with manifest: " + key)
                if apply:
                    destination.parent.mkdir(parents=True, exist_ok=True)
                    if (not destination.parent.resolve(strict=True).is_relative_to(website_root.resolve(strict=True))
                            or destination.is_symlink()):
                        raise ValueError("website destination is not a safe file path: " + key)
                    if not destination.exists():
                        try:
                            with (stage_root / key).open("rb") as source, destination.open("xb") as target:
                                shutil.copyfileobj(source, target, 1024 * 1024)
                            copied += 1
                        except FileExistsError:
                            pass
                    if digest(destination) != item["sha256"]:
                        raise ValueError("copied website image checksum mismatch: " + key)
                    if not existing:
                        original = Path(item["source_relative_path"].replace("\\", "/")).name[:500]
                        cursor.execute("INSERT INTO website_media_assets "
                                       "(storage_key, original_filename, display_name, media_type, mime_type, "
                                       "size_bytes, sha256, status) "
                                       "VALUES (%s,%s,%s,'IMAGE',%s,%s,%s,'DRAFT')",
                                       (key, original, original, item["mime_type"],
                                        item["size_bytes"], item["sha256"]))
                        existing = {"id": cursor.lastrowid, "status": "DRAFT"}
                        registered += 1
                mapping[key] = {"website_media_id": existing["id"] if existing else None,
                                "status": existing["status"] if existing else "PENDING_DRAFT_IMPORT"}
            if apply:
                connection.commit()
            else:
                connection.rollback()
    except Exception:
        connection.rollback()
        raise
    finally:
        connection.close()
    return {"applied": apply, "selected_images": len(assets), "selected_references": len(references),
            "copied_files": copied, "registered_media": registered,
            "references": [{"legacy_product_id": row["legacy_product_id"],
                            "legacy_asset_id": row["legacy_asset_id"],
                            "storage_key": row["storage_key"], **mapping[row["storage_key"]]}
                           for row in references]}


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--manifest", type=Path, default=Path(
        "docs/website-product-migration-proposal-20261026/website_media_manifest.json"))
    parser.add_argument("--stage-root", type=Path, default=Path("storage/website-migration-stage"))
    parser.add_argument("--destination-root", type=Path, default=Path("storage/lq-files"))
    parser.add_argument("--env-file", type=Path, default=Path("backend/.env"))
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--apply", action="store_true")
    args = parser.parse_args()
    result = import_media(args.manifest, args.stage_root, args.destination_root,
                          args.env_file, args.apply)
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({key: result[key] for key in
                      ("applied", "selected_images", "selected_references", "copied_files", "registered_media")},
                     ensure_ascii=False))


if __name__ == "__main__":
    main()
