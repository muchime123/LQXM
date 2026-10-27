"""Guarded cutover for the two currently public Banner images.

Default mode only checks readiness. --apply atomically publishes website media
and links Banner 4/5, preserving their legacy image_url values for recovery.
"""
import argparse
import json
from pathlib import Path
from urllib.request import urlopen

import pymysql

from audit_website_banner_media import config_value, digest
from verify_website_banner_cutover import verify


def check_public(base_url: str, expected: dict[int, dict], root: Path) -> None:
    with urlopen(base_url.rstrip("/") + "/api/website/banners/public", timeout=15) as response:
        groups = json.load(response)
    shown = {row["id"]: row for rows in groups.values() for row in rows}
    for banner_id, item in expected.items():
        media_id = item["website_media_id"]
        url = f"/api/website/media/{media_id}/content"
        if shown.get(banner_id, {}).get("imageUrl") != url:
            raise RuntimeError(f"Banner {banner_id} did not switch in public response")
        with urlopen(base_url.rstrip("/") + url, timeout=15) as response:
            if response.status != 200 or response.headers.get_content_type() != "image/png":
                raise RuntimeError(f"Banner {banner_id} media response is invalid")
            public_bytes = response.read()
        target = (root / item["storage_key"]).resolve(strict=True)
        if digest(target) != item["sha256"]:
            raise RuntimeError(f"Banner {banner_id} local media changed")
        import hashlib
        if hashlib.sha256(public_bytes).hexdigest() != item["sha256"]:
            raise RuntimeError(f"Banner {banner_id} public media differs from copied file")


def cutover(root: Path, env_file: Path, mapping_file: Path, base_url: str, apply: bool) -> dict:
    preflight = verify(root, env_file, mapping_file, base_url)
    result = {"applied": False, "preflight": preflight, "banners": []}
    if not preflight["ready_for_runtime_cutover_test"] or not apply:
        return result
    entries = json.loads(mapping_file.read_text(encoding="utf-8"))["media"]
    expected = {row["banner_id"]: row for row in entries if row["banner_id"] in (4, 5)}
    password = config_value("DB_PASSWORD", env_file)
    if password is None:
        raise RuntimeError("DB_PASSWORD is missing")
    connection = pymysql.connect(host="127.0.0.1", port=3306,
                                 user=config_value("DB_USERNAME", env_file) or "root",
                                 password=password, database="product_archive", charset="utf8mb4",
                                 cursorclass=pymysql.cursors.DictCursor, autocommit=False)
    try:
        with connection.cursor() as cursor:
            cursor.execute("START TRANSACTION")
            cursor.execute("SELECT id, image_url, website_media_id, website_enabled, deleted_at "
                           "FROM website_banners WHERE id IN (4,5) FOR UPDATE")
            banners = {row["id"]: row for row in cursor.fetchall()}
            cursor.execute("SELECT id, storage_key, sha256, status, media_type, deleted_at "
                           "FROM website_media_assets WHERE id IN (%s,%s) FOR UPDATE",
                           (expected[4]["website_media_id"], expected[5]["website_media_id"]))
            media = {row["id"]: row for row in cursor.fetchall()}
            for banner_id, item in expected.items():
                banner = banners.get(banner_id)
                target = media.get(item["website_media_id"])
                if (not banner or not banner["website_enabled"] or banner["deleted_at"] is not None
                        or banner["website_media_id"] is not None
                        or not target or target["status"] != "DRAFT"
                        or target["media_type"] != "IMAGE" or target["deleted_at"] is not None
                        or target["storage_key"] != item["storage_key"]
                        or target["sha256"] != item["sha256"]):
                    raise RuntimeError(f"Banner {banner_id} changed after preflight")
                target_file = (root / item["storage_key"]).resolve(strict=True)
                if not target_file.is_relative_to(root / "website") or digest(target_file) != item["sha256"]:
                    raise RuntimeError(f"Banner {banner_id} website file changed after preflight")
                cursor.execute("UPDATE website_media_assets SET status='PUBLISHED' "
                               "WHERE id=%s AND status='DRAFT' AND deleted_at IS NULL",
                               (item["website_media_id"],))
                if cursor.rowcount != 1:
                    raise RuntimeError(f"Banner {banner_id} media publication failed")
                cursor.execute("UPDATE website_banners SET website_media_id=%s "
                               "WHERE id=%s AND website_media_id IS NULL AND image_url=%s "
                               "AND website_enabled=1 AND deleted_at IS NULL",
                               (item["website_media_id"], banner_id, banner["image_url"]))
                if cursor.rowcount != 1:
                    raise RuntimeError(f"Banner {banner_id} association failed")
                result["banners"].append({"banner_id": banner_id,
                                          "website_media_id": item["website_media_id"],
                                          "legacy_image_url_preserved": True})
            connection.commit()
            result["applied"] = True
    except Exception:
        connection.rollback()
        raise
    finally:
        connection.close()
    try:
        check_public(base_url, expected, root)
        result["public_verified"] = True
    except Exception as error:
        result["public_verified"] = False
        result["post_commit_error"] = str(error)
    return result


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, default=Path("storage/lq-files"))
    parser.add_argument("--env-file", type=Path, default=Path("backend/.env"))
    parser.add_argument("--mapping", type=Path,
                        default=Path("docs/website-banner-media-import-result-20261027.json"))
    parser.add_argument("--base-url", default="http://127.0.0.1:8081")
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--apply", action="store_true")
    args = parser.parse_args()
    result = cutover(args.root.resolve(strict=True), args.env_file, args.mapping,
                     args.base_url, args.apply)
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"applied": result["applied"],
                      "ready": result["preflight"]["ready_for_runtime_cutover_test"],
                      "public_verified": result.get("public_verified")}, ensure_ascii=False))
    if args.apply and not result["applied"]:
        raise SystemExit("cutover blocked by preflight; no database changes")
    if result.get("public_verified") is False:
        raise SystemExit("cutover committed but public verification failed; inspect output")


if __name__ == "__main__":
    main()
