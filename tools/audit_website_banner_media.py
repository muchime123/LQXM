"""Read-only inventory of Banner image URLs and local source files.

Reads DB credentials from environment or backend/.env without printing them.
Uses a read-only MySQL transaction and never follows external image URLs.
"""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
from urllib.parse import parse_qs, urlparse

import pymysql


def config_value(key: str, env_file: Path) -> str | None:
    if os.environ.get(key):
        return os.environ[key]
    if env_file.is_file():
        for line in env_file.read_text(encoding="utf-8-sig").splitlines():
            name, separator, value = line.partition("=")
            if separator and name.strip() == key:
                return value.strip().strip('"').strip("'")
    return None


def digest(path: Path) -> str:
    sha = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            sha.update(chunk)
    return sha.hexdigest()


def local_filename(url: str | None) -> str | None:
    if not url:
        return None
    parsed = urlparse(url)
    if parsed.path != "/api/files/company" or parsed.scheme or parsed.netloc:
        return None
    encoded = parse_qs(parsed.query).get("encoded", [None])[0]
    if not encoded:
        return None
    try:
        name = base64.urlsafe_b64decode(encoded + "=" * (-len(encoded) % 4)).decode("utf-8")
    except (ValueError, UnicodeDecodeError):
        return None
    return name if Path(name).name == name else None


def inventory(root: Path, env_file: Path) -> list[dict]:
    password = config_value("DB_PASSWORD", env_file)
    if password is None:
        raise RuntimeError("DB_PASSWORD is not available from environment or backend/.env")
    connection = pymysql.connect(host="127.0.0.1", port=3306,
                                 user=config_value("DB_USERNAME", env_file) or "root",
                                 password=password, database="product_archive",
                                 charset="utf8mb4", cursorclass=pymysql.cursors.DictCursor)
    try:
        with connection.cursor() as cursor:
            cursor.execute("SET SESSION TRANSACTION READ ONLY")
            cursor.execute("START TRANSACTION READ ONLY")
            cursor.execute("SELECT id, position, image_url, image_asset_id, website_media_id, website_enabled, "
                           "start_date, end_date, deleted_at FROM website_banners ORDER BY id")
            rows = cursor.fetchall()
            cursor.execute("SELECT version, success FROM flyway_schema_history WHERE version IN ('411','413','417') ORDER BY installed_rank")
            versions = cursor.fetchall()
            connection.rollback()
    finally:
        connection.close()
    result = []
    for row in rows:
        url = row["image_url"]
        name = local_filename(url)
        paths = list(root.rglob(name)) if name else []
        files = [{"relative_path": path.relative_to(root).as_posix(), "size_bytes": path.stat().st_size,
                  "sha256": digest(path)} for path in paths if path.is_file()]
        result.append({"id": row["id"], "position": row["position"], "website_enabled": bool(row["website_enabled"]),
                       "start_date": str(row["start_date"]) if row["start_date"] else None,
                       "end_date": str(row["end_date"]) if row["end_date"] else None,
                       "deleted": row["deleted_at"] is not None,
                       "image_asset_id": row["image_asset_id"], "image_url": url,
                       "website_media_id": row["website_media_id"],
                       "source_kind": "local_company" if name else "none" if not url else "other_url",
                       "source_filename": name, "files": files})
    return [{"flyway_versions": versions, "banners": result}]


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, default=Path("storage/lq-files"))
    parser.add_argument("--env-file", type=Path, default=Path("backend/.env"))
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    result = inventory(args.root.resolve(strict=True), args.env_file)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result[0], ensure_ascii=False, indent=2, default=str) + "\n", encoding="utf-8")
    print(json.dumps({"banners": len(result[0]["banners"]),
                      "local_files": sum(len(item["files"]) for item in result[0]["banners"]),
                      "flyway_versions": result[0]["flyway_versions"]}, ensure_ascii=False, default=str))


if __name__ == "__main__":
    main()
