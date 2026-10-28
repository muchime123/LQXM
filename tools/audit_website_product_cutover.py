"""Read-only preflight for the website product source switch; never prints credentials."""

import argparse
import json
from pathlib import Path

import pymysql

from audit_website_banner_media import config_value


ROOT = Path(__file__).resolve().parents[1]


def inventory():
    password = config_value("DB_PASSWORD", ROOT / "backend/.env")
    if password is None:
        raise RuntimeError("DB_PASSWORD is unavailable")
    connection = pymysql.connect(
        host="127.0.0.1", port=3306,
        user=config_value("DB_USERNAME", ROOT / "backend/.env") or "root",
        password=password, database="product_archive", charset="utf8mb4",
        cursorclass=pymysql.cursors.DictCursor, autocommit=False,
    )
    try:
        with connection.cursor() as cursor:
            cursor.execute("START TRANSACTION READ ONLY")
            cursor.execute("SELECT DATABASE() AS schema_name")
            schema = cursor.fetchone()["schema_name"]
            counts = {}
            for table in ("website_products", "website_product_categories", "website_media_assets"):
                cursor.execute(f"SELECT status, COUNT(*) AS rows_count FROM {table} "
                               "WHERE deleted_at IS NULL GROUP BY status")
                counts[table] = {row["status"]: row["rows_count"] for row in cursor.fetchall()}
            cursor.execute("""
                SELECT COUNT(*) AS rows_count FROM website_products p
                LEFT JOIN website_product_categories c ON c.id=p.category_id
                WHERE p.status='PUBLISHED' AND p.deleted_at IS NULL
                  AND (p.category_id IS NULL OR (c.status='PUBLISHED' AND c.deleted_at IS NULL))
                """)
            visible_products = cursor.fetchone()["rows_count"]
            cursor.execute("""
                SELECT COUNT(*) AS rows_count FROM website_products
                WHERE status='PUBLISHED' AND featured=TRUE AND deleted_at IS NULL
                """)
            published_featured = cursor.fetchone()["rows_count"]
            cursor.execute("""
                SELECT COUNT(*) AS rows_count FROM website_product_media pm
                JOIN website_products p ON p.id=pm.product_id
                JOIN website_media_assets m ON m.id=pm.media_id
                WHERE p.status='PUBLISHED' AND p.deleted_at IS NULL
                  AND pm.variant_id IS NULL AND m.status='PUBLISHED' AND m.deleted_at IS NULL
                """)
            visible_gallery_rows = cursor.fetchone()["rows_count"]
            cursor.execute("""
                SELECT p.id, p.slug FROM website_products p
                WHERE p.status='PUBLISHED' AND p.deleted_at IS NULL
                  AND (p.hero_media_id IS NULL OR p.card_media_id IS NULL
                       OR NOT EXISTS (SELECT 1 FROM website_product_media pm
                                      WHERE pm.product_id=p.id AND pm.variant_id IS NULL))
                ORDER BY p.id
                """)
            missing_display = cursor.fetchall()
            connection.rollback()
    finally:
        connection.close()
    return {
        "read_only": True,
        "schema": schema,
        "status_counts": counts,
        "eligible_published_products": visible_products,
        "published_featured_products": published_featured,
        "visible_product_gallery_rows": visible_gallery_rows,
        "published_products_missing_hero_card_or_gallery": missing_display,
        "technical_gate_clear": visible_products > 0 and not missing_display,
        "note": "Technical counts only; editorial and page parity approval is separate.",
    }


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    report = inventory()
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"read-only cutover inventory saved: {args.output}")
