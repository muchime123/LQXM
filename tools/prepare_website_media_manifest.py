"""Join read-only media audit with product mapping; never copy files or write DB.

The output is a review manifest, not executable import data. All website media
statuses remain DRAFT until the actual website content is reviewed.
"""
import argparse
import json
from collections import Counter
from pathlib import Path


MIME = {".jpg": "image/jpeg", ".jpeg": "image/jpeg", ".png": "image/png", ".webp": "image/webp", ".gif": "image/gif", ".pdf": "application/pdf", ".psd": "image/vnd.adobe.photoshop"}
KIND = {".jpg": "images", ".jpeg": "images", ".png": "images", ".webp": "images", ".gif": "images"}


def prepare(proposal_dir: Path) -> dict:
    links = json.loads((proposal_dir / "media_links.json").read_text(encoding="utf-8"))
    audit = [json.loads(line) for line in (proposal_dir / "media_audit.jsonl").read_text(encoding="utf-8-sig").splitlines()]
    by_id = {int(item["asset_id"]): item for item in audit}
    if len(by_id) != len(audit) or {item["legacy_asset_id"] for item in links} != set(by_id):
        raise ValueError("audit and mapping asset IDs differ")
    assets_by_key = {}
    references = []
    exceptions = []
    for link in links:
        source_id = link["legacy_asset_id"]
        item = by_id[source_id]
        if item["candidate_count"] != 1:
            exceptions.append({"asset_id": source_id, "reason": "source_missing_or_ambiguous"})
            continue
        source = item["candidates"][0]
        extension = Path(source["relative_path"]).suffix.lower()
        if extension not in MIME:
            exceptions.append({"asset_id": source_id, "reason": "unsupported_extension", "extension": extension})
            continue
        kind = KIND.get(extension, "documents")
        key = f"website/{kind}/{source['sha256']}{extension}"
        assets_by_key.setdefault(key, {"storage_key": key, "sha256": source["sha256"], "size_bytes": source["size_bytes"], "mime_type": MIME[extension], "media_type": "IMAGE" if kind == "images" else "DOCUMENT", "status": "DRAFT", "source_relative_path": source["relative_path"]})
        shown = link["selected_by_current_detail_gallery"] or link["selected_by_current_list_card"]
        references.append({"legacy_product_id": link["legacy_product_id"], "legacy_asset_id": source_id, "storage_key": key, "category": link["category"], "proposed_role": link["proposed_role"], "currently_selected_for_detail": link["selected_by_current_detail_gallery"], "currently_selected_for_card": link["selected_by_current_list_card"], "publication_review": "DISPLAY_CANDIDATE" if shown else "RETAIN_DRAFT"})
    result = {"media_assets": list(assets_by_key.values()), "references": references, "exceptions": exceptions}
    (proposal_dir / "website_media_manifest.json").write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return {"legacy_references": len(links), "unique_media_files": len(assets_by_key), "detail_display_candidates": sum(row["currently_selected_for_detail"] for row in references), "card_display_candidates": sum(row["currently_selected_for_card"] for row in references), "draft_only_references": sum(row["publication_review"] == "RETAIN_DRAFT" for row in references), "exceptions": dict(Counter(row["reason"] for row in exceptions))}


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--proposal-dir", type=Path, required=True)
    args = parser.parse_args()
    print(json.dumps(prepare(args.proposal_dir), ensure_ascii=False))


if __name__ == "__main__":
    main()
