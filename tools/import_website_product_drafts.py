"""One-time, reviewable import of the 2026-10-26 public product snapshot.

Default mode performs read-only validation. --apply inserts only DRAFT website
categories and products into an empty target; it never updates existing rows or
changes the public API. Source IDs remain only in the JSON result, not the DB.
"""

import argparse
import html
import json
from pathlib import Path

import pymysql

from audit_website_banner_media import config_value


ROOT = Path(__file__).resolve().parents[1]
PROPOSALS = ROOT / "docs/website-product-migration-proposal-20261026/product_proposals.json"
MEDIA = ROOT / "docs/website-product-display-media-import-20261027.json"
CATEGORY_SLUGS = {
    "封口机": "sealing-machines",
    "封切机": "sealing-cutting-machines",
    "辅助设备": "auxiliary-equipment",
    "灌装封口生产线": "filling-sealing-lines",
    "配件": "accessories",
    "气调包装机": "modified-atmosphere-packaging-machines",
    "气调封口机": "modified-atmosphere-sealers",
    "贴体包装机": "skin-packaging-machines",
    "托盒封口机": "tray-sealers",
}
REQUIRED_COLUMNS = {
    "website_products": {"id", "category_id", "name", "name_en", "public_model", "slug",
                         "summary", "description_html", "description_html_en", "application_tags",
                         "container_tags", "process_tags", "capacity_level", "hero_media_id",
                         "card_media_id", "sort_order", "featured", "status", "published_at",
                         "deleted_at"},
    "website_product_categories": {"id", "name", "slug", "sort_order", "status", "deleted_at"},
    "website_media_assets": {"id", "status", "deleted_at"},
}


def plain_text_html(value):
    """Preserve source plain text as safe draft HTML without trusting markup."""
    if not value:
        return None
    return "".join("<p>" + html.escape(line) + "</p>" for line in value.splitlines() if line.strip()) or None


def read_source():
    proposals = json.loads(PROPOSALS.read_text(encoding="utf-8"))
    media_report = json.loads(MEDIA.read_text(encoding="utf-8"))
    if len(proposals) != 45 or not media_report.get("applied"):
        raise ValueError("reviewed product or media import snapshot is unavailable")
    if len({p["legacy_product_id"] for p in proposals}) != 45 or len({p["slug"] for p in proposals}) != 45:
        raise ValueError("duplicate source product ID or slug")
    if {p["category_name"] for p in proposals} != set(CATEGORY_SLUGS):
        raise ValueError("source category set changed; review slug mapping")
    refs = {}
    all_ids = set()
    for row in media_report["references"]:
        if row["status"] != "DRAFT":
            raise ValueError("source media mapping is not draft")
        key = (row["legacy_product_id"], row["legacy_asset_id"])
        if key in refs and refs[key] != row["website_media_id"]:
            raise ValueError("conflicting media mapping")
        refs[key] = row["website_media_id"]
        all_ids.add(row["website_media_id"])
    if len(media_report["references"]) != 142 or len(all_ids) != 136:
        raise ValueError("media mapping count differs from reviewed snapshot")
    for p in proposals:
        if p["status"] != "PUBLISHED_CANDIDATE":
            raise ValueError("unexpected source product status")
        for field in ("hero_legacy_asset_id", "card_legacy_asset_id"):
            asset = p[field]
            if asset is not None and (p["legacy_product_id"], asset) not in refs:
                raise ValueError(f"missing {field} mapping for product {p['legacy_product_id']}")
    return proposals, refs, all_ids


def ensure_schema(cursor):
    for table, required in REQUIRED_COLUMNS.items():
        cursor.execute("SELECT COLUMN_NAME FROM information_schema.COLUMNS "
                       "WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=%s", (table,))
        found = {row["COLUMN_NAME"] for row in cursor.fetchall()}
        if required - found:
            raise ValueError(f"{table} missing columns: {sorted(required - found)}; do not run migrations here")


def inspect(cursor, proposals, media_ids):
    ensure_schema(cursor)
    counts = {}
    for table in ("website_product_categories", "website_products"):
        cursor.execute(f"SELECT COUNT(*) AS n FROM {table}")
        counts[table] = cursor.fetchone()["n"]
    placeholders = ",".join(["%s"] * len(media_ids))
    cursor.execute(f"SELECT id, status, deleted_at FROM website_media_assets WHERE id IN ({placeholders})",
                   tuple(sorted(media_ids)))
    found = {row["id"]: row for row in cursor.fetchall()}
    invalid = sorted(media_ids - found.keys()) + sorted(
        id_ for id_, row in found.items() if row["status"] != "DRAFT" or row["deleted_at"] is not None)
    if invalid:
        raise ValueError(f"website image records missing or not DRAFT: {invalid}")
    if counts["website_product_categories"] or counts["website_products"]:
        raise ValueError(f"target contains existing content {counts}; refusing to overwrite or mix imports")
    return counts


def verify_import(cursor, proposals, refs, media_ids):
    ensure_schema(cursor)
    cursor.execute("SELECT id, name, slug, status, deleted_at FROM website_product_categories")
    categories = cursor.fetchall()
    by_name = {row["name"]: row for row in categories}
    if (len(categories) != len(CATEGORY_SLUGS) or set(by_name) != set(CATEGORY_SLUGS)
            or any(row["slug"] != CATEGORY_SLUGS[name] or row["status"] != "DRAFT"
                   or row["deleted_at"] is not None for name, row in by_name.items())):
        raise ValueError("website draft categories differ from source mapping")
    cursor.execute("SELECT id, category_id, name, slug, status, published_at, deleted_at, "
                   "hero_media_id, card_media_id FROM website_products")
    products = cursor.fetchall()
    by_slug = {row["slug"]: row for row in products}
    if len(products) != len(proposals) or len(by_slug) != len(proposals):
        raise ValueError("website draft product count or slugs differ from source")
    for p in proposals:
        row = by_slug.get(p["slug"])
        source_id = p["legacy_product_id"]
        hero = refs.get((source_id, p["hero_legacy_asset_id"])) if p["hero_legacy_asset_id"] else None
        card = refs.get((source_id, p["card_legacy_asset_id"])) if p["card_legacy_asset_id"] else None
        if (row is None or row["name"] != p["name"] or row["category_id"] != by_name[p["category_name"]]["id"]
                or row["hero_media_id"] != hero or row["card_media_id"] != card
                or row["status"] != "DRAFT" or row["published_at"] is not None
                or row["deleted_at"] is not None):
            raise ValueError(f"website draft product differs from source: {p['slug']}")
    placeholders = ",".join(["%s"] * len(media_ids))
    cursor.execute(f"SELECT id, status, deleted_at FROM website_media_assets WHERE id IN ({placeholders})",
                   tuple(sorted(media_ids)))
    media = cursor.fetchall()
    if (len(media) != len(media_ids) or any(row["status"] != "DRAFT" or row["deleted_at"] is not None
                                          for row in media)):
        raise ValueError("website media draft state changed")
    return {"website_product_categories": len(categories), "website_products": len(products),
            "website_media_assets": len(media)}


def run(apply, verify=False):
    proposals, refs, media_ids = read_source()
    password = config_value("DB_PASSWORD", ROOT / "backend/.env")
    if password is None:
        raise RuntimeError("DB_PASSWORD is unavailable")
    connection = pymysql.connect(host="127.0.0.1", port=3306,
                                 user=config_value("DB_USERNAME", ROOT / "backend/.env") or "root",
                                 password=password, database="product_archive", charset="utf8mb4",
                                 cursorclass=pymysql.cursors.DictCursor, autocommit=False)
    report = {
        "applied": False,
        "schema": "product_archive",
        "source_products": len(proposals),
        "source_categories": len(CATEGORY_SLUGS),
        "mapped_media": len(media_ids),
        "draft_only": True,
        "category_slugs_require_editorial_review": True,
        "products_requiring_review": [p["legacy_product_id"] for p in proposals if p["review_required"]],
        "categories": [],
        "products": [],
    }
    try:
        with connection.cursor() as cursor:
            cursor.execute("START TRANSACTION" if apply else "START TRANSACTION READ ONLY")
            if verify:
                report["verified_counts"] = verify_import(cursor, proposals, refs, media_ids)
                connection.rollback()
                return report
            report["target_counts_before"] = inspect(cursor, proposals, media_ids)
            category_ids = {}
            for index, (name, slug) in enumerate(CATEGORY_SLUGS.items()):
                if apply:
                    cursor.execute("INSERT INTO website_product_categories "
                                   "(name, slug, sort_order, status) VALUES (%s,%s,%s,'DRAFT')",
                                   (name, slug, index))
                    category_ids[name] = cursor.lastrowid
                report["categories"].append({"name": name, "slug": slug,
                                             "website_category_id": category_ids.get(name)})
            for p in proposals:
                legacy_id = p["legacy_product_id"]
                hero = refs.get((legacy_id, p["hero_legacy_asset_id"])) if p["hero_legacy_asset_id"] else None
                card = refs.get((legacy_id, p["card_legacy_asset_id"])) if p["card_legacy_asset_id"] else None
                if apply:
                    cursor.execute("INSERT INTO website_products "
                                   "(category_id, name, name_en, public_model, slug, summary, "
                                   "description_html, description_html_en, application_tags, container_tags, "
                                   "process_tags, capacity_level, hero_media_id, card_media_id, sort_order, "
                                   "featured, status, published_at) "
                                   "VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,'DRAFT',NULL)",
                                   (category_ids[p["category_name"]], p["name"], p["name_en"],
                                    p["public_model"], p["slug"], p["summary"],
                                    plain_text_html(p["description_cn"] or p["introduction_cn"]),
                                    plain_text_html(p["description_en"] or p["introduction_en"]),
                                    json.dumps(p["application_tags"], ensure_ascii=False),
                                    json.dumps(p["container_tags"], ensure_ascii=False),
                                    json.dumps(p["process_tags"], ensure_ascii=False),
                                    p["capacity_level"], hero, card, p["sort_order"], p["featured"]))
                    website_id = cursor.lastrowid
                else:
                    website_id = None
                report["products"].append({"legacy_product_id": legacy_id, "slug": p["slug"],
                                           "website_product_id": website_id, "hero_media_id": hero,
                                           "card_media_id": card, "review_required": p["review_required"]})
            if apply:
                cursor.execute("SELECT COUNT(*) AS n, SUM(status='DRAFT') AS drafts, "
                               "SUM(published_at IS NOT NULL) AS published "
                               "FROM website_products")
                verified = cursor.fetchone()
                if (verified["n"], int(verified["drafts"]), int(verified["published"])) != (45, 45, 0):
                    raise ValueError("inserted product counts/status mismatch")
                connection.commit()
                report["applied"] = True
            else:
                connection.rollback()
    except BaseException:
        connection.rollback()
        raise
    finally:
        connection.close()
    return report


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apply", action="store_true", help="insert verified draft content into empty website tables")
    parser.add_argument("--verify", action="store_true", help="read-only check of imported drafts")
    parser.add_argument("--report", type=Path, help="write JSON mapping/report")
    args = parser.parse_args()
    if args.apply and args.verify:
        parser.error("--apply and --verify cannot be combined")
    result = run(args.apply, args.verify)
    if args.report:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({key: value for key, value in result.items() if key not in ("products", "categories")},
                     ensure_ascii=False, indent=2))
