"""Inventory product media and optionally copy it into isolated website storage.

Input is a TSV with columns: id, model_id, category, file_name. The script only
prints JSON lines. Copying requires --copy and --destination-root explicitly.
"""
import argparse
import csv
import hashlib
import json
import shutil
from pathlib import Path


def digest(path: Path) -> str:
    value = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            value.update(block)
    return value.hexdigest()


def candidates(root: Path, model_id: str, category: str, file_name: str) -> list[Path]:
    if Path(file_name).name != file_name or not model_id.isdecimal():
        return []
    bases = [root / "products" / model_id, root / model_id]
    bases.extend(path for path in root.glob(f"{model_id}_*") if path.is_dir())
    result = []
    for base in bases:
        path = (base / category / file_name).resolve()
        if path.is_relative_to(root) and path.is_file() and path not in result:
            result.append(path)
    return result


def copy_verified(source: Path, root: Path, sha256: str) -> tuple[str, str]:
    suffix = source.suffix.lower()
    kind = "images" if suffix in {".jpg", ".jpeg", ".png", ".gif", ".webp", ".svg", ".avif"} else "videos" if suffix in {".mp4", ".mov", ".webm"} else "documents"
    relative = Path("website") / kind / f"{sha256}{suffix}"
    destination = (root / relative).resolve()
    if not destination.is_relative_to(root) or destination.parent.is_symlink():
        raise ValueError("destination escapes website storage root")
    destination.parent.mkdir(parents=True, exist_ok=True)
    if destination.exists():
        if digest(destination) != sha256:
            raise ValueError("existing destination has different contents")
        return str(relative).replace("\\", "/"), "already_present"
    created = False
    try:
        with source.open("rb") as incoming, destination.open("xb") as outgoing:
            created = True
            shutil.copyfileobj(incoming, outgoing, 1024 * 1024)
        if digest(destination) != sha256:
            raise ValueError("copied file hash mismatch")
    except Exception:
        if created:
            destination.unlink(missing_ok=True)
        raise
    return str(relative).replace("\\", "/"), "copied"


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--input", type=Path, required=True)
    parser.add_argument("--destination-root", type=Path)
    parser.add_argument("--copy", action="store_true", help="Copy unique source files into destination-root/website")
    args = parser.parse_args()
    root = args.root.resolve(strict=True)
    if args.copy and args.destination_root is None:
        parser.error("--copy requires --destination-root")
    destination_root = args.destination_root.resolve(strict=True) if args.copy else None
    with args.input.open(newline="", encoding="utf-8-sig") as source:
        for row in csv.DictReader(source, delimiter="\t"):
            paths = candidates(root, row["model_id"], row["category"], row["file_name"])
            result = {
                "asset_id": row["id"],
                "model_id": row["model_id"],
                "category": row["category"],
                "candidate_count": len(paths),
                "candidates": [
                    {"relative_path": str(path.relative_to(root)), "size_bytes": path.stat().st_size, "sha256": digest(path)}
                    for path in paths
                ],
            }
            if args.copy:
                if len(paths) != 1:
                    result["copy_status"] = "skipped_ambiguous_source"
                else:
                    try:
                        storage_key, status = copy_verified(paths[0], destination_root, result["candidates"][0]["sha256"])
                        result.update(storage_key=storage_key, copy_status=status)
                    except Exception as error:
                        result.update(copy_status="failed", error=str(error))
            print(json.dumps(result, ensure_ascii=False))


if __name__ == "__main__":
    main()
