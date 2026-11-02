"""Read-only inspection of the internal company Logo and website copy candidates."""
import argparse
import json
from pathlib import Path

from audit_website_banner_media import digest
from seed_website_company_profile import ROOT, connect


def audit(env_file, file_root):
    db = connect(env_file)
    try:
        with db.cursor() as cursor:
            cursor.execute("SET SESSION TRANSACTION READ ONLY")
            cursor.execute("START TRANSACTION READ ONLY")
            cursor.execute("SELECT id, logo_file_name FROM company_info ORDER BY id LIMIT 1")
            source = cursor.fetchone()
            cursor.execute("SELECT id, status, logo_media_id FROM website_company_profiles WHERE id=1")
            profile = cursor.fetchone()
            cursor.execute("SELECT id, storage_key, sha256, status, media_type, deleted_at "
                           "FROM website_media_assets")
            assets = cursor.fetchall()
            db.rollback()
    finally:
        db.close()
    filename = source["logo_file_name"] if source else None
    if filename and (Path(filename).name != filename or filename in (".", "..")):
        raise ValueError("Unsafe Logo filename in archive row")
    candidates = []
    if filename:
        for rel in (Path("company") / "brand_logo" / filename, Path("company") / filename):
            path = (file_root / rel).resolve()
            if path.is_relative_to(file_root) and path.is_file():
                candidates.append({"relative_path": rel.as_posix(), "sha256": digest(path),
                                   "size_bytes": path.stat().st_size})
    matches = []
    if len(candidates) == 1:
        for media in assets:
            if (media["sha256"] == candidates[0]["sha256"] and media["media_type"] == "IMAGE"
                    and media["deleted_at"] is None):
                target = (file_root / media["storage_key"]).resolve()
                matches.append({"id": media["id"], "status": media["status"],
                                "storage_key": media["storage_key"],
                                "file_matches": target.is_file() and digest(target) == media["sha256"]})
    return {"read_only": True, "source_company_id": source["id"] if source else None,
            "source_logo_filename": filename, "profile": profile,
            "candidates": candidates, "website_media_matches": matches}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--env-file", type=Path, default=ROOT / "backend/.env")
    parser.add_argument("--file-root", type=Path, default=ROOT / "storage/lq-files")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    result = audit(args.env_file, args.file_root.resolve(strict=True))
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2, default=str) + "\n", encoding="utf-8")
    print(json.dumps(result, ensure_ascii=False, default=str))


if __name__ == "__main__":
    main()
