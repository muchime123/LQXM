"""Read-only inventory of media URLs in currently public website content.

The report contains record IDs and URLs only, never database credentials or body text.
"""
import argparse
import json
import re
from pathlib import Path

import pymysql

from audit_website_banner_media import config_value, digest, local_filename


ROOT = Path(__file__).resolve().parents[1]
MEDIA_URL = re.compile(r"/api/website/media/\d+/content(?:\?[^\s\"'<>]*)?", re.I)
ARCHIVE_URL = re.compile(
    r"(?:https?://[^/\s\"'<>]+)?/(?:api/files|api/file-assets)/[^\s\"'<>\\]+"
    r"|(?:product_model|packaging_model)/assets/[^\s\"'<>\\]+", re.I)
PUBLIC_IMAGE = re.compile(r"/images/[^\s\"'<>\\]+", re.I)
EXTERNAL_URL = re.compile(r"https?://[^\s\"'<>\\]+", re.I)


def references(value):
    if not value:
        return []
    text = str(value).replace("\\/", "/")
    result = []
    for kind, pattern in (("internal_archive", ARCHIVE_URL), ("website_media", MEDIA_URL),
                          ("public_static", PUBLIC_IMAGE), ("external_review", EXTERNAL_URL)):
        for match in pattern.finditer(text):
            url = match.group(0).rstrip("),.;}")
            if kind == "external_review" and (ARCHIVE_URL.search(url) or MEDIA_URL.search(url)):
                continue
            entry = {"kind": kind, "url": url}
            if entry not in result:
                result.append(entry)
    return result


def item(kind, row, fields):
    found = [{"field": field, **ref} for field in fields for ref in references(row.get(field))]
    return {"kind": kind, "id": row["id"], "references": found}


def audit(env_file, file_root):
    password = config_value("DB_PASSWORD", env_file)
    if password is None:
        raise RuntimeError("DB_PASSWORD is unavailable from environment or backend/.env")
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
            cursor.execute("SELECT DATABASE() AS schema_name")
            schema = cursor.fetchone()["schema_name"]
            cursor.execute("""SELECT id, image_url, website_media_id FROM website_banners
                WHERE website_enabled=TRUE AND deleted_at IS NULL
                  AND (start_date IS NULL OR start_date <= CURRENT_DATE)
                  AND (end_date IS NULL OR end_date >= CURRENT_DATE) ORDER BY id""")
            banners = cursor.fetchall()
            cursor.execute("""SELECT id, image_url, content FROM website_page_sections
                WHERE enabled=TRUE AND deleted_at IS NULL AND page_key <> 'MOLDS' ORDER BY id""")
            sections = cursor.fetchall()
            cursor.execute("""SELECT id, cover_image_url, content_html, content_sections FROM website_solutions
                WHERE website_enabled=TRUE ORDER BY id""")
            solutions = cursor.fetchall()
            cursor.execute("""SELECT id, storage_key, sha256, status FROM website_media_assets
                WHERE deleted_at IS NULL ORDER BY id""")
            media = cursor.fetchall()
            connection.rollback()
    finally:
        connection.close()
    records = []
    for row in banners:
        # A website media ID takes precedence over the historical image_url.
        if row["website_media_id"] is not None:
            records.append({"kind": "banner", "id": row["id"], "references": [
                {"field": "website_media_id", "kind": "website_media", "url":
                 f"/api/website/media/{row['website_media_id']}/content"}]})
        else:
            records.append(item("banner", row, ("image_url",)))
    records.extend(item("page_section", row, ("image_url", "content")) for row in sections)
    records.extend(item("solution", row, ("cover_image_url", "content_html", "content_sections"))
                   for row in solutions)
    affected = [row for row in records if any(ref["kind"] == "internal_archive" for ref in row["references"])]
    source_urls = sorted({ref["url"] for row in affected for ref in row["references"]
                          if ref["kind"] == "internal_archive"})
    source_files = []
    media_by_sha = {}
    media_by_id = {row["id"]: row for row in media}
    for asset in media:
        media_by_sha.setdefault(asset["sha256"], []).append(asset)
    for url in source_urls:
        filename = local_filename(url)
        candidates = sorted(path for path in file_root.rglob(filename) if path.is_file()) if filename else []
        copies = []
        if len(candidates) == 1:
            source_hash = digest(candidates[0])
            website_root = file_root.resolve() / "website"
            for asset in media_by_sha.get(source_hash, []):
                target = (file_root / asset["storage_key"]).resolve() if asset["storage_key"] else file_root
                copies.append({"media_id": asset["id"], "storage_key": asset["storage_key"],
                               "status": asset["status"],
                               "file_matches": target.is_relative_to(website_root) and target.is_file()
                               and digest(target) == source_hash})
        source_files.append({"url": url, "source_filename": filename,
                             "candidate_count": len(candidates), "candidates": [
                                 {"relative_path": path.relative_to(file_root).as_posix(),
                                  "size_bytes": path.stat().st_size, "sha256": digest(path)}
                                 for path in candidates], "website_copies": copies})
    public_media_ids = sorted({int(match.group(1)) for row in records for ref in row["references"]
                               if ref["kind"] == "website_media"
                               for match in [re.search(r"/api/website/media/(\d+)/content", ref["url"])]
                               if match})
    unavailable_media = []
    website_root = file_root.resolve() / "website"
    for media_id in public_media_ids:
        asset = media_by_id.get(media_id)
        if asset is None or asset["status"] != "PUBLISHED" or not asset["storage_key"]:
            unavailable_media.append(media_id)
            continue
        path = (file_root / asset["storage_key"]).resolve()
        if not path.is_relative_to(website_root) or not path.is_file() or digest(path) != asset["sha256"]:
            unavailable_media.append(media_id)
    return {"read_only": True, "schema": schema,
            "counts": {"banners": len(banners), "page_sections": len(sections), "solutions": len(solutions),
                       "records_with_internal_archive_media": len(affected),
                       "unique_internal_archive_urls": len(source_urls),
                       "unresolved_or_ambiguous_files": sum(row["candidate_count"] != 1 for row in source_files),
                       "sources_without_verified_website_copy": sum(
                           not any(copy["file_matches"] for copy in row["website_copies"])
                           for row in source_files),
                       "referenced_website_media": len(public_media_ids),
                       "unavailable_referenced_website_media": len(unavailable_media)},
            "records": records, "source_files": source_files,
            "unavailable_website_media_ids": unavailable_media,
            "note": "Only effective active Banner images are counted; external URLs require manual review."}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--env-file", type=Path, default=ROOT / "backend/.env")
    parser.add_argument("--file-root", type=Path, default=ROOT / "storage/lq-files")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    result = audit(args.env_file, args.file_root.resolve(strict=True))
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2, default=str) + "\n", encoding="utf-8")
    print(json.dumps(result["counts"], ensure_ascii=False))


if __name__ == "__main__":
    main()
