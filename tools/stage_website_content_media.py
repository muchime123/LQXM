"""Idempotently copy audited public-content images into website storage.

No database writes or publication changes. The output maps old URLs to either an
existing website media ID or a verified file awaiting media registration.
"""
import argparse
import json
import shutil
import uuid
from pathlib import Path

from audit_website_banner_media import digest


ROOT = Path(__file__).resolve().parents[1]
IMAGE_EXTENSIONS = {".jpg", ".jpeg", ".png", ".webp", ".gif"}


def stage(inventory, file_root):
    website_root = (file_root / "website").resolve()
    result = []
    for source in inventory["source_files"]:
        if source["candidate_count"] != 1:
            raise ValueError("Source image is missing or ambiguous")
        candidate = source["candidates"][0]
        original = (file_root / candidate["relative_path"]).resolve(strict=True)
        if not original.is_relative_to(file_root) or original.is_relative_to(website_root):
            raise ValueError("Source path is outside internal storage")
        if digest(original) != candidate["sha256"] or original.stat().st_size != candidate["size_bytes"]:
            raise ValueError("Source image changed since inventory")
        existing = next((copy for copy in source["website_copies"] if copy["file_matches"]), None)
        if existing:
            result.append({"url": source["url"], "status": "existing_media",
                           "media_id": existing["media_id"], "storage_key": existing["storage_key"],
                           "sha256": candidate["sha256"]})
            continue
        extension = original.suffix.lower()
        if extension not in IMAGE_EXTENSIONS:
            raise ValueError("Unsupported source image extension")
        key = f"website/images/{candidate['sha256']}{extension}"
        target = (file_root / key).resolve()
        if not target.is_relative_to(website_root):
            raise ValueError("Target path is outside website storage")
        target.parent.mkdir(parents=True, exist_ok=True)
        if not target.exists():
            temporary = target.with_name(target.name + "." + uuid.uuid4().hex + ".tmp")
            try:
                shutil.copyfile(original, temporary)
                if digest(temporary) != candidate["sha256"]:
                    raise ValueError("Copied image hash mismatch")
                temporary.replace(target)
            finally:
                temporary.unlink(missing_ok=True)
        if not target.is_file() or digest(target) != candidate["sha256"]:
            raise ValueError("Existing website image differs from source")
        result.append({"url": source["url"], "status": "staged_file", "media_id": None,
                       "storage_key": key, "sha256": candidate["sha256"]})
    return {"database_changed": False, "published_content_changed": False,
            "existing_media": sum(row["status"] == "existing_media" for row in result),
            "staged_files": sum(row["status"] == "staged_file" for row in result),
            "mapping": result}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--inventory", type=Path,
                        default=ROOT / "docs/website-content-media-inventory-20261030.json")
    parser.add_argument("--file-root", type=Path, default=ROOT / "storage/lq-files")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    inventory = json.loads(args.inventory.read_text(encoding="utf-8"))
    result = stage(inventory, args.file_root.resolve(strict=True))
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({key: result[key] for key in ("existing_media", "staged_files", "database_changed")},
                     ensure_ascii=False))


if __name__ == "__main__":
    main()
