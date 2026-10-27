"""Read-only gate for switching Banner 4/5 to website-owned media.

Checks DB state, source and copied bytes, and the current public response.
Never changes Banner links or media publication status.
"""
import argparse
import json
from pathlib import Path
from urllib.request import urlopen
from urllib.error import URLError

import pymysql

from audit_website_banner_media import config_value, digest, local_filename


def verify(root: Path, env_file: Path, mapping_file: Path, base_url: str) -> dict:
    mapping = json.loads(mapping_file.read_text(encoding="utf-8"))["media"]
    wanted = {row["banner_id"]: row for row in mapping if row["banner_id"] in (4, 5)}
    if set(wanted) != {4, 5}:
        raise ValueError("Banner 4 and 5 mappings are required")
    public = None
    runtime_error = None
    try:
        with urlopen(base_url.rstrip("/") + "/api/website/banners/public", timeout=15) as response:
            public = json.load(response)
    except URLError as error:
        runtime_error = str(error.reason)
    public_by_id = {row["id"]: row for group in public.values() for row in group} if public else {}
    password = config_value("DB_PASSWORD", env_file)
    if password is None:
        raise RuntimeError("DB_PASSWORD is missing")
    connection = pymysql.connect(host="127.0.0.1", port=3306,
                                 user=config_value("DB_USERNAME", env_file) or "root",
                                 password=password, database="product_archive", charset="utf8mb4",
                                 cursorclass=pymysql.cursors.DictCursor)
    try:
        with connection.cursor() as cursor:
            cursor.execute("SET SESSION TRANSACTION READ ONLY")
            cursor.execute("START TRANSACTION READ ONLY")
            cursor.execute("SELECT id, image_url, website_media_id, website_enabled, deleted_at FROM website_banners WHERE id IN (3,4,5)")
            banners = {row["id"]: row for row in cursor.fetchall()}
            cursor.execute("SELECT id, storage_key, sha256, size_bytes, status, media_type, deleted_at FROM website_media_assets WHERE id IN (%s,%s,%s)",
                           (next(row["website_media_id"] for row in mapping if row["banner_id"] == 3),
                            wanted[4]["website_media_id"], wanted[5]["website_media_id"]))
            media = {row["id"]: row for row in cursor.fetchall()}
            connection.rollback()
    finally:
        connection.close()
    issues = []
    if runtime_error:
        issues.append("public API unavailable: " + runtime_error)
    if not banners.get(3) or banners[3]["deleted_at"] is None or banners[3]["website_media_id"] is not None:
        issues.append("historical Banner 3 changed")
    checks = []
    for banner_id in (4, 5):
        banner = banners.get(banner_id)
        target_id = wanted[banner_id]["website_media_id"]
        target = media.get(target_id)
        shown = public_by_id.get(banner_id)
        if not banner or not target:
            issues.append(f"Banner {banner_id} or mapped media missing from database")
            continue
        if public is not None and not shown:
            issues.append(f"Banner {banner_id} missing from public response")
        if not banner["website_enabled"] or banner["deleted_at"] is not None:
            issues.append(f"Banner {banner_id} is not active")
        if banner["website_media_id"] is not None:
            issues.append(f"Banner {banner_id} already switched")
        if target["status"] != "DRAFT" or target["media_type"] != "IMAGE" or target["deleted_at"] is not None:
            issues.append(f"website media {target_id} is not an available image draft")
        if target["storage_key"] != wanted[banner_id]["storage_key"]:
            issues.append(f"website media {target_id} storage key changed")
        filename = local_filename(banner["image_url"])
        if filename is None or (shown is not None and shown.get("imageUrl") != banner["image_url"]):
            issues.append(f"Banner {banner_id} legacy URL changed")
            continue
        sources = [path for path in root.rglob(filename) if path.is_file()]
        copied = (root / target["storage_key"]).resolve()
        if len(sources) != 1 or not copied.is_relative_to(root) or not copied.is_file():
            issues.append(f"Banner {banner_id} source or copy missing")
            continue
        source_hash, copied_hash = digest(sources[0]), digest(copied)
        if source_hash != copied_hash or source_hash != target["sha256"] or copied.stat().st_size != target["size_bytes"]:
            issues.append(f"Banner {banner_id} content mismatch")
        checks.append({"banner_id": banner_id, "website_media_id": target_id,
                       "sha256": target["sha256"], "bytes": target["size_bytes"],
                       "legacy_url_unchanged": shown.get("imageUrl") == banner["image_url"] if shown else None})
    return {"ready_for_runtime_cutover_test": not issues, "checks": checks, "issues": issues}


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, default=Path("storage/lq-files"))
    parser.add_argument("--env-file", type=Path, default=Path("backend/.env"))
    parser.add_argument("--mapping", type=Path, default=Path("docs/website-banner-media-import-result-20261027.json"))
    parser.add_argument("--base-url", default="http://127.0.0.1:8081")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    result = verify(args.root.resolve(strict=True), args.env_file, args.mapping, args.base_url)
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"ready": result["ready_for_runtime_cutover_test"], "checks": len(result["checks"]),
                      "issues": result["issues"]}, ensure_ascii=False))


if __name__ == "__main__":
    main()
