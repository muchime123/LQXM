"""Build reviewable migration proposals from a saved public API snapshot.

No database or network access. Proposed media links use legacy IDs only in this
offline mapping and must be resolved to website_media_assets before import.
"""
import argparse
import csv
import json
from collections import Counter
from pathlib import Path


DISPLAY_CATEGORIES = {"main_image", "scene_image", "detail_image", "parameter_image", "sku_image"}
ROLE = {"main_image": "HERO", "scene_image": "SCENE", "detail_image": "DETAIL", "parameter_image": "PARAMETER", "sku_image": "GALLERY"}


def build(snapshot_dir: Path, output_dir: Path) -> dict:
    manifest = json.loads((snapshot_dir / "manifest.json").read_text(encoding="utf-8"))
    page = json.loads((snapshot_dir / "products.json").read_text(encoding="utf-8"))
    featured = json.loads((snapshot_dir / "featured.json").read_text(encoding="utf-8"))
    if page["total"] != len(page["items"]) or len(page["items"]) != len(manifest["products"]):
        raise ValueError("snapshot product count does not match manifest")
    featured_ids = {item["id"] for item in featured}
    proposals, media_rows, issues = [], [], []
    seen_assets = {}
    for rank, (item, index) in enumerate(zip(page["items"], manifest["products"])):
        if item["id"] != index["legacy_id"] or item["websiteSlug"] != index["slug"]:
            raise ValueError("snapshot product order differs from manifest")
        detail = json.loads((snapshot_dir / index["detail_file"]).read_text(encoding="utf-8"))
        model = detail["model"]
        if model["id"] != item["id"] or model["websiteSlug"] != item["websiteSlug"]:
            raise ValueError("detail differs from list: " + item["websiteSlug"])
        gallery = detail.get("gallery") or []
        display = [asset for asset in gallery if asset["category"] in DISPLAY_CATEGORIES]
        main = [asset for asset in gallery if asset["category"] == "main_image"]
        chosen = main if main else display
        if not chosen:
            issues.append({"kind": "STATIC_IMAGE_FALLBACK", "legacy_product_id": item["id"], "slug": item["websiteSlug"], "action": "review category image or website placeholder"})
        if item.get("defaultImageAssetId") and item["defaultImageAssetId"] not in {asset["id"] for asset in gallery}:
            issues.append({"kind": "DEFAULT_IMAGE_OUTSIDE_GALLERY", "legacy_product_id": item["id"], "asset_id": item["defaultImageAssetId"], "action": "check list card image separately"})
            if not item.get("defaultImage"):
                raise ValueError("list card asset has no filename: " + item["websiteSlug"])
            media_rows.append({"legacy_product_id": item["id"], "legacy_asset_id": item["defaultImageAssetId"], "category": "main_image", "file_name": item["defaultImage"], "proposed_role": "HERO", "sort_order": 0, "selected_by_current_detail_gallery": False, "selected_by_current_list_card": True})
        for asset in gallery:
            identity = (item["id"], asset["id"])
            if asset["id"] in seen_assets and seen_assets[asset["id"]] != item["id"]:
                issues.append({"kind": "ASSET_SHARED_ACROSS_PRODUCTS", "asset_id": asset["id"], "product_ids": [seen_assets[asset["id"]], item["id"]]})
            seen_assets[asset["id"]] = item["id"]
            if asset["category"] not in DISPLAY_CATEGORIES:
                issues.append({"kind": "NON_DISPLAY_GALLERY_ASSET", "legacy_product_id": item["id"], "asset_id": asset["id"], "category": asset["category"], "action": "retain source file; review public role"})
            media_rows.append({"legacy_product_id": item["id"], "legacy_asset_id": asset["id"], "category": asset["category"], "file_name": asset["fileName"], "proposed_role": ROLE.get(asset["category"], "DOCUMENT"), "sort_order": asset.get("sortOrder") or 0, "selected_by_current_detail_gallery": asset in chosen, "selected_by_current_list_card": asset["id"] == item.get("defaultImageAssetId")})
        proposals.append({"legacy_product_id": item["id"], "slug": item["websiteSlug"], "name": model["name"], "name_en": model.get("nameEn"), "public_model": model.get("modelCode"), "category_name": item.get("category"), "summary": model.get("websiteSummary"), "description_cn": model.get("descriptionCn"), "description_en": model.get("descriptionEn"), "introduction_cn": model.get("introductionCn"), "introduction_en": model.get("introductionEn"), "application_tags": item.get("applicationTags") or [], "container_tags": item.get("containerTags") or [], "process_tags": item.get("packagingProcessTags") or [], "capacity_level": item.get("capacityLevel"), "featured": item["id"] in featured_ids, "sort_order": rank, "status": "PUBLISHED_CANDIDATE", "hero_legacy_asset_id": chosen[0]["id"] if chosen else None, "card_legacy_asset_id": item.get("defaultImageAssetId"), "gallery_count": len(gallery), "variant_count": len(detail.get("variants") or []), "review_required": not chosen or item.get("defaultImageAssetId") not in {asset["id"] for asset in gallery}})
    output_dir.mkdir(parents=True, exist_ok=True)
    (output_dir / "product_proposals.json").write_text(json.dumps(proposals, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    (output_dir / "media_links.json").write_text(json.dumps(media_rows, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    (output_dir / "issues.json").write_text(json.dumps(issues, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    with (output_dir / "media_audit_input.tsv").open("w", encoding="utf-8", newline="") as target:
        writer = csv.DictWriter(target, fieldnames=["id", "model_id", "category", "file_name"], delimiter="\t")
        writer.writeheader()
        for row in media_rows:
            writer.writerow({"id": row["legacy_asset_id"], "model_id": row["legacy_product_id"], "category": row["category"], "file_name": row["file_name"]})
    return {"products": len(proposals), "featured": len(featured_ids), "media_links": len(media_rows), "unique_assets": len({row["legacy_asset_id"] for row in media_rows}), "issues": dict(Counter(issue["kind"] for issue in issues))}


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--snapshot-dir", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    args = parser.parse_args()
    print(json.dumps(build(args.snapshot_dir, args.output_dir), ensure_ascii=False))


if __name__ == "__main__":
    main()
