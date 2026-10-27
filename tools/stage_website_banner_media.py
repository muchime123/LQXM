"""Copy audited Banner images to isolated website staging; never write database."""
import argparse
import json
from pathlib import Path

from audit_website_product_media import copy_verified, digest


def stage(inventory_path: Path, source_root: Path, destination_root: Path) -> dict:
    inventory = json.loads(inventory_path.read_text(encoding="utf-8"))
    result = []
    for banner in inventory["banners"]:
        files = banner["files"]
        if banner["source_kind"] != "local_company" or len(files) != 1:
            result.append({"banner_id": banner["id"], "status": "manual_review" if banner["image_url"] else "no_image"})
            continue
        source = (source_root / files[0]["relative_path"]).resolve(strict=True)
        if not source.is_relative_to(source_root):
            raise ValueError("source path escapes file root")
        if digest(source) != files[0]["sha256"]:
            raise ValueError(f"source changed after audit: Banner {banner['id']}")
        key, status = copy_verified(source, destination_root, files[0]["sha256"])
        target = destination_root / key
        if digest(target) != files[0]["sha256"]:
            raise ValueError(f"staged file mismatch: Banner {banner['id']}")
        result.append({"banner_id": banner["id"], "legacy_source": files[0]["relative_path"],
                       "storage_key": key, "sha256": files[0]["sha256"],
                       "size_bytes": files[0]["size_bytes"], "copy_status": status,
                       "status": "public_candidate" if banner["website_enabled"] and not banner["deleted"] else "historical_draft"})
    return {"banners": result}


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--inventory", type=Path, required=True)
    parser.add_argument("--source-root", type=Path, required=True)
    parser.add_argument("--destination-root", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    result = stage(args.inventory, args.source_root.resolve(strict=True), args.destination_root.resolve(strict=True))
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"copied": sum(item.get("copy_status") == "copied" for item in result["banners"]),
                      "already_present": sum(item.get("copy_status") == "already_present" for item in result["banners"]),
                      "public_candidates": sum(item["status"] == "public_candidate" for item in result["banners"]),
                      "historical_drafts": sum(item["status"] == "historical_draft" for item in result["banners"])}, ensure_ascii=False))


if __name__ == "__main__":
    main()
