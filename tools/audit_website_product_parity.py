"""Read-only comparison of imported website drafts with the public product snapshot.

This is a migration review aid, not a publication or synchronization mechanism.
It queries only website-owned tables and compares them to frozen local snapshots.
"""

import argparse
import json
from collections import defaultdict
from pathlib import Path

import pymysql

from audit_website_banner_media import config_value
from import_website_product_drafts import plain_text_html


ROOT = Path(__file__).resolve().parents[1]
PROPOSALS = ROOT / "docs/website-product-migration-proposal-20261026/product_proposals.json"
MEDIA_MANIFEST = ROOT / "docs/website-product-migration-proposal-20261026/website_media_manifest.json"
PRODUCT_MAPPING = ROOT / "docs/website-product-draft-import-20261027.json"
MEDIA_MAPPING = ROOT / "docs/website-product-display-media-import-20261027.json"


def read_inputs():
    proposals = json.loads(PROPOSALS.read_text(encoding="utf-8"))
    manifest = json.loads(MEDIA_MANIFEST.read_text(encoding="utf-8"))
    product_import = json.loads(PRODUCT_MAPPING.read_text(encoding="utf-8"))
    media_import = json.loads(MEDIA_MAPPING.read_text(encoding="utf-8"))
    if (len(proposals) != 45 or not product_import["applied"] or not media_import["applied"]):
        raise ValueError("frozen import evidence is incomplete")
    product_ids = {row["legacy_product_id"]: row["website_product_id"]
                   for row in product_import["products"]}
    media_ids = {(row["legacy_product_id"], row["legacy_asset_id"]): row["website_media_id"]
                 for row in media_import["references"]}
    expected_gallery = defaultdict(list)
    for row in manifest["references"]:
        if row["currently_selected_for_detail"]:
            key = (row["legacy_product_id"], row["legacy_asset_id"])
            expected_gallery[row["legacy_product_id"]].append(media_ids[key])
    return proposals, product_ids, media_ids, expected_gallery


def compare():
    proposals, product_ids, media_ids, expected_gallery = read_inputs()
    password = config_value("DB_PASSWORD", ROOT / "backend/.env")
    if password is None:
        raise RuntimeError("DB_PASSWORD is unavailable")
    connection = pymysql.connect(host="127.0.0.1", port=3306,
                                 user=config_value("DB_USERNAME", ROOT / "backend/.env") or "root",
                                 password=password, database="product_archive", charset="utf8mb4",
                                 cursorclass=pymysql.cursors.DictCursor, autocommit=False)
    try:
        with connection.cursor() as cursor:
            cursor.execute("START TRANSACTION READ ONLY")
            cursor.execute("""
                SELECT p.*, c.name AS category_name FROM website_products p
                LEFT JOIN website_product_categories c ON c.id = p.category_id
                """)
            products = {row["id"]: row for row in cursor.fetchall()}
            cursor.execute("""
                SELECT product_id, media_id, sort_order, display_role, variant_id
                FROM website_product_media ORDER BY product_id, sort_order, id
                """)
            gallery = defaultdict(list)
            for row in cursor.fetchall():
                gallery[row["product_id"]].append(row)
            cursor.execute("SELECT id, status, deleted_at FROM website_media_assets")
            assets = {row["id"]: row for row in cursor.fetchall()}
            connection.rollback()
    finally:
        connection.close()

    by_product = []
    mismatches = []
    for source in proposals:
        legacy_id = source["legacy_product_id"]
        website_id = product_ids[legacy_id]
        row = products.get(website_id)
        if row is None:
            mismatches.append({"legacy_product_id": legacy_id, "field": "website_product_id",
                               "expected": website_id, "actual": None})
            continue
        expected = {
            "slug": source["slug"], "name": source["name"], "name_en": source["name_en"],
            "public_model": source["public_model"], "category_name": source["category_name"],
            "summary": source["summary"],
            "description_html": plain_text_html(source["description_cn"] or source["introduction_cn"]),
            "description_html_en": plain_text_html(source["description_en"] or source["introduction_en"]),
            "application_tags": source["application_tags"],
            "container_tags": source["container_tags"], "process_tags": source["process_tags"],
            "capacity_level": source["capacity_level"], "sort_order": source["sort_order"],
            "featured": bool(source["featured"]), "status": "DRAFT", "published_at": None,
        }
        for field in ("application_tags", "container_tags", "process_tags"):
            row[field] = json.loads(row[field]) if row[field] is not None else []
        for field, value in expected.items():
            actual = bool(row[field]) if field == "featured" else row[field]
            if actual != value:
                mismatches.append({"legacy_product_id": legacy_id, "field": field,
                                   "expected": value, "actual": actual})
        expected_hero = (media_ids.get((legacy_id, source["hero_legacy_asset_id"]))
                         if source["hero_legacy_asset_id"] else None)
        expected_card = (media_ids.get((legacy_id, source["card_legacy_asset_id"]))
                         if source["card_legacy_asset_id"] else None)
        for field, expected_id in (("hero_media_id", expected_hero), ("card_media_id", expected_card)):
            if row[field] != expected_id:
                mismatches.append({"legacy_product_id": legacy_id, "field": field,
                                   "expected": expected_id, "actual": row[field]})
        linked = gallery.get(website_id, [])
        actual_gallery = [item["media_id"] for item in linked]
        if (actual_gallery != expected_gallery[legacy_id]
                or any(item["display_role"] != "GALLERY" or item["variant_id"] is not None
                       or item["sort_order"] != index for index, item in enumerate(linked))):
            mismatches.append({"legacy_product_id": legacy_id, "field": "gallery_media_ids",
                               "expected": expected_gallery[legacy_id], "actual": actual_gallery})
        used_media = [id_ for id_ in [row["hero_media_id"], row["card_media_id"], *actual_gallery] if id_]
        invalid_media = sorted({id_ for id_ in used_media if id_ not in assets
                                or assets[id_]["status"] != "DRAFT" or assets[id_]["deleted_at"] is not None})
        if invalid_media:
            mismatches.append({"legacy_product_id": legacy_id, "field": "draft_media_status",
                               "invalid_media_ids": invalid_media})
        by_product.append({
            "legacy_product_id": legacy_id, "website_product_id": website_id, "slug": row["slug"],
            "gallery_count": len(linked), "has_hero": row["hero_media_id"] is not None,
            "has_card": row["card_media_id"] is not None,
            "missing_seo_title": not bool(row["seo_title"]),
            "missing_seo_description": not bool(row["seo_description"]),
            "source_review_required": bool(source["review_required"]),
        })
    extra_products = sorted(set(products) - set(product_ids.values()))
    extra_gallery = sorted(set(gallery) - set(product_ids.values()))
    return {
        "read_only": True, "schema": "product_archive", "snapshot_products": len(proposals),
        "website_products": len(products), "website_product_gallery_rows": sum(map(len, gallery.values())),
        "field_mismatches": mismatches, "extra_website_product_ids": extra_products,
        "extra_gallery_product_ids": extra_gallery,
        "review_queue": {
            "missing_hero": [p["legacy_product_id"] for p in by_product if not p["has_hero"]],
            "missing_seo_title": [p["legacy_product_id"] for p in by_product if p["missing_seo_title"]],
            "missing_seo_description": [p["legacy_product_id"] for p in by_product if p["missing_seo_description"]],
            "source_review_required": [p["legacy_product_id"] for p in by_product if p["source_review_required"]],
        },
        "products": by_product,
    }


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--report", type=Path, required=True)
    args = parser.parse_args()
    report = compare()
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({key: value for key, value in report.items() if key != "products"},
                     ensure_ascii=False, indent=2))
