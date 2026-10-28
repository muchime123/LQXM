"""Link the reviewed product detail images to independent draft products.

The default and --verify modes are read-only. --apply inserts one GALLERY row per
currently displayed detail reference, preserving snapshot order and duplicate
appearances. It never changes product/media publication or public API behavior.
"""

import argparse
import json
from pathlib import Path

import pymysql

from audit_website_banner_media import config_value


ROOT = Path(__file__).resolve().parents[1]
MANIFEST = ROOT / "docs/website-product-migration-proposal-20261026/website_media_manifest.json"
MEDIA_MAPPING = ROOT / "docs/website-product-display-media-import-20261027.json"
PRODUCT_MAPPING = ROOT / "docs/website-product-draft-import-20261027.json"


def source_rows():
    manifest = json.loads(MANIFEST.read_text(encoding="utf-8"))
    media = json.loads(MEDIA_MAPPING.read_text(encoding="utf-8"))
    products = json.loads(PRODUCT_MAPPING.read_text(encoding="utf-8"))
    if not media["applied"] or not products["applied"]:
        raise ValueError("draft product/media import reports are incomplete")
    media_ids = {(r["legacy_product_id"], r["legacy_asset_id"]): r["website_media_id"]
                 for r in media["references"]}
    product_ids = {r["legacy_product_id"]: r["website_product_id"] for r in products["products"]}
    if len(product_ids) != 45:
        raise ValueError("product mapping must contain 45 source products")
    order = {}
    rows = []
    for reference in manifest["references"]:
        if not reference["currently_selected_for_detail"]:
            continue
        key = (reference["legacy_product_id"], reference["legacy_asset_id"])
        if reference["publication_review"] != "DISPLAY_CANDIDATE" or key not in media_ids:
            raise ValueError(f"detail image was not imported as draft media: {key}")
        legacy_product = reference["legacy_product_id"]
        if legacy_product not in product_ids:
            raise ValueError(f"detail image has no draft product: {legacy_product}")
        sort_order = order.get(legacy_product, 0)
        rows.append({"legacy_product_id": legacy_product,
                     "legacy_asset_id": reference["legacy_asset_id"],
                     "website_product_id": product_ids[legacy_product],
                     "website_media_id": media_ids[key],
                     "sort_order": sort_order})
        order[legacy_product] = sort_order + 1
    if len(rows) != 141 or len(order) != 44:
        raise ValueError("detail gallery selection differs from reviewed snapshot")
    return rows


def validate_targets(cursor, rows):
    cursor.execute("SELECT id, status, deleted_at FROM website_products")
    products = {r["id"]: r for r in cursor.fetchall()}
    cursor.execute("SELECT id, status, deleted_at FROM website_media_assets")
    media = {r["id"]: r for r in cursor.fetchall()}
    for row in rows:
        product = products.get(row["website_product_id"])
        asset = media.get(row["website_media_id"])
        if (not product or product["status"] != "DRAFT" or product["deleted_at"] is not None
                or not asset or asset["status"] != "DRAFT" or asset["deleted_at"] is not None):
            raise ValueError(f"gallery target is missing or not draft: {row}")


def run(apply=False, verify=False):
    rows = source_rows()
    password = config_value("DB_PASSWORD", ROOT / "backend/.env")
    if password is None:
        raise RuntimeError("DB_PASSWORD is unavailable")
    connection = pymysql.connect(host="127.0.0.1", port=3306,
                                 user=config_value("DB_USERNAME", ROOT / "backend/.env") or "root",
                                 password=password, database="product_archive", charset="utf8mb4",
                                 cursorclass=pymysql.cursors.DictCursor, autocommit=False)
    report = {"applied": False, "source_detail_references": len(rows), "draft_only": True,
              "associations": []}
    try:
        with connection.cursor() as cursor:
            cursor.execute("START TRANSACTION" if apply else "START TRANSACTION READ ONLY")
            validate_targets(cursor, rows)
            cursor.execute("SELECT id, product_id, media_id, display_role, sort_order, variant_id "
                           "FROM website_product_media ORDER BY product_id, sort_order, id")
            existing = cursor.fetchall()
            if verify:
                expected = [(r["website_product_id"], r["website_media_id"], r["sort_order"])
                            for r in sorted(rows, key=lambda x: (x["website_product_id"], x["sort_order"]))]
                actual = [(r["product_id"], r["media_id"], r["sort_order"]) for r in existing]
                if (actual != expected or any(r["display_role"] != "GALLERY" or r["variant_id"] is not None
                                              for r in existing)):
                    raise ValueError("website gallery associations differ from reviewed snapshot")
                report["verified_associations"] = len(existing)
            elif existing:
                raise ValueError("website_product_media already contains rows; refusing to mix or overwrite")
            elif apply:
                for row in rows:
                    cursor.execute("INSERT INTO website_product_media "
                                   "(product_id, variant_id, media_id, display_role, sort_order) "
                                   "VALUES (%s,NULL,%s,'GALLERY',%s)",
                                   (row["website_product_id"], row["website_media_id"], row["sort_order"]))
                    report["associations"].append({**row, "website_product_media_id": cursor.lastrowid})
                connection.commit()
                report["applied"] = True
            else:
                report["associations"] = rows
            if not apply:
                connection.rollback()
    except BaseException:
        connection.rollback()
        raise
    finally:
        connection.close()
    return report


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apply", action="store_true")
    parser.add_argument("--verify", action="store_true")
    parser.add_argument("--report", type=Path)
    args = parser.parse_args()
    if args.apply and args.verify:
        parser.error("--apply and --verify cannot be combined")
    result = run(args.apply, args.verify)
    if args.report:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({key: value for key, value in result.items() if key != "associations"},
                     ensure_ascii=False, indent=2))
