"""Read-only SQL smoke check for the native website product read model."""

import argparse
import json
from pathlib import Path

import pymysql

from audit_website_banner_media import config_value


ROOT = Path(__file__).resolve().parents[1]


def check():
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
            cursor.execute("SELECT DATABASE() AS schema_name")
            schema = cursor.fetchone()["schema_name"]
            cursor.execute("SELECT status, COUNT(*) AS rows_count FROM website_products GROUP BY status")
            statuses = {row["status"]: row["rows_count"] for row in cursor.fetchall()}
            published_condition = """
                p.status='PUBLISHED' AND p.deleted_at IS NULL
                AND (p.category_id IS NULL OR (c.status='PUBLISHED' AND c.deleted_at IS NULL))
                """
            joins = """
                FROM website_products p
                LEFT JOIN website_product_categories c ON c.id=p.category_id
                LEFT JOIN website_media_assets hm ON hm.id=p.hero_media_id
                LEFT JOIN website_media_assets cm ON cm.id=p.card_media_id
                """
            list_sql = ("SELECT p.id, p.name, c.name AS category_name, "
                        "CASE WHEN hm.status='PUBLISHED' AND hm.deleted_at IS NULL THEN hm.id ELSE NULL END AS visible_hero_media_id, "
                        "CASE WHEN cm.status='PUBLISHED' AND cm.deleted_at IS NULL THEN cm.id ELSE NULL END AS visible_card_media_id "
                        + joins + " WHERE " + published_condition
                        + " AND JSON_CONTAINS(p.process_tags, JSON_QUOTE(%s)) "
                        + "ORDER BY p.sort_order,p.id LIMIT %s OFFSET %s")
            cursor.execute("EXPLAIN " + list_sql, ("普通封口", 12, 0))
            list_plan_rows = len(cursor.fetchall())
            cursor.execute(list_sql, ("普通封口", 12, 0))
            visible_rows = len(cursor.fetchall())
            cursor.execute("""
                SELECT p.id, p.description_html, p.application_tags, p.process_tags,
                       p.technical_parameters, c.name AS category_name
                FROM website_products p LEFT JOIN website_product_categories c ON c.id=p.category_id
                WHERE p.slug=%s AND p.status='DRAFT' AND p.deleted_at IS NULL
                """, ("product-65",))
            draft = cursor.fetchone()
            if draft is None:
                raise ValueError("expected imported draft product-65 is absent")
            cursor.execute("""
                SELECT pm.id FROM website_product_media pm
                JOIN website_media_assets m ON m.id=pm.media_id
                WHERE pm.product_id=%s AND pm.variant_id IS NULL
                  AND m.status='PUBLISHED' AND m.deleted_at IS NULL
                ORDER BY pm.sort_order,pm.id
                """, (draft["id"],))
            published_gallery_rows = len(cursor.fetchall())
            cursor.execute("""
                SELECT id FROM website_product_variants
                WHERE product_id=%s AND status='PUBLISHED' AND deleted_at IS NULL
                ORDER BY sort_order,id
                """, (draft["id"],))
            published_variant_rows = len(cursor.fetchall())
            connection.rollback()
    finally:
        connection.close()
    return {"read_only": True, "schema": schema, "product_status_counts": statuses,
            "native_list_explain_rows": list_plan_rows,
            "native_filtered_public_rows": visible_rows,
            "draft_detail_query_resolved": True,
            "draft_published_gallery_rows": published_gallery_rows,
            "draft_published_variant_rows": published_variant_rows}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--report", type=Path, required=True)
    args = parser.parse_args()
    report = check()
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))
