"""Review and publish the frozen 45-product website copy, without archive writes.

Default mode is read-only. --apply publishes only when the exact imported copy,
website media files and their hashes pass preflight. It writes a status backup.
"""

import argparse
import hashlib
import json
from pathlib import Path

from audit_website_product_parity import compare
from seed_website_company_profile import ROOT, connect


PRODUCT_MAP = ROOT / "docs/website-product-draft-import-20261027.json"
MEDIA_MAP = ROOT / "docs/website-product-display-media-import-20261027.json"


def digest(path):
    value = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            value.update(block)
    return value.hexdigest()


def expected_ids():
    products = json.loads(PRODUCT_MAP.read_text(encoding="utf-8"))
    assets = json.loads(MEDIA_MAP.read_text(encoding="utf-8"))
    if not products.get("applied") or not assets.get("applied"):
        raise ValueError("Frozen website import is not complete")
    product_ids = {row["website_product_id"] for row in products["products"]}
    media_ids = {row["website_media_id"] for row in assets["references"]}
    if len(product_ids) != 45 or len(media_ids) != 136:
        raise ValueError("Frozen product or media mapping has changed")
    return product_ids, media_ids


def inspect(cursor, file_root, product_ids, media_ids):
    cursor.execute("SELECT p.*, c.name AS category_name FROM website_products p "
                   "LEFT JOIN website_product_categories c ON c.id=p.category_id "
                   "WHERE p.deleted_at IS NULL ORDER BY p.id")
    products = cursor.fetchall()
    cursor.execute("SELECT * FROM website_product_categories WHERE deleted_at IS NULL ORDER BY id")
    categories = cursor.fetchall()
    cursor.execute("SELECT * FROM website_product_media ORDER BY product_id, sort_order, id")
    gallery = cursor.fetchall()
    placeholders = ",".join(["%s"] * len(media_ids))
    cursor.execute(f"SELECT * FROM website_media_assets WHERE id IN ({placeholders}) ORDER BY id",
                   tuple(sorted(media_ids)))
    media = cursor.fetchall()
    blockers = []
    if {p["id"] for p in products} != product_ids or any(p["status"] != "DRAFT" for p in products):
        blockers.append("Website products differ from the 45 frozen drafts")
    if len(categories) != 9 or any(c["status"] != "DRAFT" for c in categories):
        blockers.append("Website categories differ from the 9 frozen drafts")
    if len(gallery) != 141 or any(row["product_id"] not in product_ids for row in gallery):
        blockers.append("Product gallery differs from the 141 imported associations")
    if {m["id"] for m in media} != media_ids or any(
            m["status"] != "DRAFT" or m["deleted_at"] is not None or m["media_type"] != "IMAGE"
            for m in media):
        blockers.append("Imported website media is incomplete or not all draft images")
    by_id = {m["id"]: m for m in media}
    website_root = (file_root / "website").resolve(strict=True)
    for asset in media:
        try:
            file = (file_root / asset["storage_key"]).resolve(strict=True)
            if (not file.is_relative_to(website_root) or not file.is_file()
                    or file.stat().st_size != asset["size_bytes"] or digest(file) != asset["sha256"]):
                blockers.append(f"Website media {asset['id']} file differs or escapes website storage")
        except (OSError, ValueError):
            blockers.append(f"Website media {asset['id']} file is missing")
    gallery_by_product = {}
    for row in gallery:
        if row["variant_id"] is None:
            gallery_by_product.setdefault(row["product_id"], []).append(row)
        if row["media_id"] not in by_id:
            blockers.append(f"Gallery row {row['id']} has media outside frozen import")
    category_ids = {c["id"] for c in categories}
    for product in products:
        pid = product["id"]
        if product["category_id"] not in category_ids or not product["category_name"]:
            blockers.append(f"Product {pid} has no website category")
        if not all(product[field] and str(product[field]).strip() for field in ("name", "slug", "summary")):
            blockers.append(f"Product {pid} lacks name, slug or summary")
        for field in ("hero_media_id", "card_media_id"):
            if product[field] is not None and product[field] not in by_id:
                blockers.append(f"Product {pid} {field} is outside frozen website media")
        if pid == 1:
            if product["hero_media_id"] is not None or product["card_media_id"] not in by_id or gallery_by_product.get(pid):
                blockers.append("Product 1 no longer matches the documented card-image exception")
        elif (product["hero_media_id"] is None or product["card_media_id"] is None
              or not gallery_by_product.get(pid)):
            blockers.append(f"Product {pid} lacks hero, card or gallery")
        body = (product["description_html"] or "") + (product["description_html_en"] or "")
        lower = body.lower()
        if any(token in lower for token in ("<img", "<video", "<source", "/api/files/",
                                             "/api/file-assets/", "product_model/assets/")):
            blockers.append(f"Product {pid} description contains unreviewed media markup")
    report = {
        "ready": not blockers, "blockers": blockers, "products": len(products),
        "categories": len(categories), "gallery_rows": len(gallery), "media_files_verified": len(media),
        "product_1_uses_existing_card_as_hero_and_gallery": True,
        "seo_policy": "existing public name + brand for title; existing public summary for description",
    }
    return report, products, categories, gallery, media


def publish(file_root, backup_path, apply):
    product_ids, media_ids = expected_ids()
    parity = compare()
    if (parity["field_mismatches"] or parity["extra_website_product_ids"]
            or parity["extra_gallery_product_ids"] or parity["website_products"] != 45):
        raise ValueError("Current website copy differs from the frozen public snapshot")
    db = connect(ROOT / "backend/.env")
    try:
        with db.cursor() as cursor:
            cursor.execute("START TRANSACTION" if apply else "START TRANSACTION READ ONLY")
            if apply:
                cursor.execute("SELECT id FROM website_products WHERE deleted_at IS NULL FOR UPDATE")
                cursor.execute("SELECT id FROM website_product_categories WHERE deleted_at IS NULL FOR UPDATE")
                placeholders = ",".join(["%s"] * len(media_ids))
                cursor.execute(f"SELECT id FROM website_media_assets WHERE id IN ({placeholders}) FOR UPDATE",
                               tuple(sorted(media_ids)))
            report, products, categories, gallery, media = inspect(cursor, file_root, product_ids, media_ids)
            report["applied"] = False
            if not apply or not report["ready"]:
                db.rollback()
                return report
            if backup_path.exists():
                raise ValueError("Backup already exists; choose a fresh path")
            backup = {
                "products": [{k: row[k] for k in ("id", "status", "published_at", "seo_title",
                                                     "seo_description", "hero_media_id")}
                             for row in products],
                "categories": [{k: row[k] for k in ("id", "status", "seo_title", "seo_description")}
                               for row in categories],
                "media": [{"id": row["id"], "status": row["status"]} for row in media],
                "gallery_rows_before": len(gallery),
            }
            backup_path.parent.mkdir(parents=True, exist_ok=True)
            backup_path.write_text(json.dumps(backup, ensure_ascii=False, indent=2, default=str) + "\n",
                                   encoding="utf-8")
            for asset in media:
                cursor.execute("UPDATE website_media_assets SET status='PUBLISHED' "
                               "WHERE id=%s AND status='DRAFT' AND deleted_at IS NULL", (asset["id"],))
                if cursor.rowcount != 1:
                    raise RuntimeError("Media changed concurrently")
                cursor.execute("INSERT INTO audit_logs (entity_type, entity_id, model_name, action, changes, created_at) "
                               "VALUES ('WebsiteMediaAsset', %s, %s, 'WEBSITE_MEDIA_PUBLISH', "
                               "'审核后发布官网产品图片', NOW())", (asset["id"], asset["display_name"] or ""))
            for category in categories:
                title = category["seo_title"] or (category["name"] + " | 利强包装")[:300]
                description = category["seo_description"] or category["name"]
                cursor.execute("UPDATE website_product_categories SET status='PUBLISHED', "
                               "seo_title=%s, seo_description=%s WHERE id=%s AND status='DRAFT' "
                               "AND deleted_at IS NULL", (title, description, category["id"]))
                if cursor.rowcount != 1:
                    raise RuntimeError("Category changed concurrently")
                cursor.execute("INSERT INTO audit_logs (entity_type, entity_id, model_name, action, changes, created_at) "
                               "VALUES ('WebsiteProductCategory', %s, %s, 'WEBSITE_PRODUCT_CATEGORY_PUBLISH', "
                               "'审核后发布官网产品分类', NOW())", (category["id"], category["name"][:200]))
            for product in products:
                title = product["seo_title"] or (product["name"] + " | 利强包装")[:300]
                description = product["seo_description"] or product["summary"].strip()[:500]
                hero = product["hero_media_id"] or product["card_media_id"]
                if product["id"] == 1:
                    cursor.execute("INSERT INTO website_product_media "
                                   "(product_id, variant_id, media_id, display_role, alt_text, sort_order) "
                                   "VALUES (1, NULL, %s, 'HERO', %s, 0)",
                                   (hero, product["name"][:500]))
                cursor.execute("UPDATE website_products SET status='PUBLISHED', published_at=NOW(), "
                               "seo_title=%s, seo_description=%s, hero_media_id=%s "
                               "WHERE id=%s AND status='DRAFT' AND deleted_at IS NULL",
                               (title, description, hero, product["id"]))
                if cursor.rowcount != 1:
                    raise RuntimeError("Product changed concurrently")
                cursor.execute("INSERT INTO audit_logs (entity_type, entity_id, model_name, action, changes, created_at) "
                               "VALUES ('WebsiteProduct', %s, %s, 'WEBSITE_PRODUCT_PUBLISH', "
                               "'审核公开快照后发布官网独立产品', NOW())",
                               (product["id"], product["name"][:200]))
            db.commit()
            report["applied"] = True
            report["backup"] = str(backup_path.relative_to(ROOT))
            return report
    except Exception:
        db.rollback()
        raise
    finally:
        db.close()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--file-root", type=Path, default=ROOT / "storage/lq-files")
    parser.add_argument("--backup", type=Path, default=ROOT / "docs/website-product-publish-backup-20261102.json")
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--apply", action="store_true")
    args = parser.parse_args()
    report = publish(args.file_root.resolve(strict=True), args.backup, args.apply)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({k: v for k, v in report.items() if k != "blockers"}, ensure_ascii=False))


if __name__ == "__main__":
    main()
