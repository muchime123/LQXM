"""Read-only inventory of the packaging rows currently eligible for website display."""
import argparse
import json
from collections import Counter
from pathlib import Path

from seed_website_company_profile import ROOT, connect


def audit(env_file):
    db = connect(env_file)
    try:
        with db.cursor() as cursor:
            cursor.execute("SET SESSION TRANSACTION READ ONLY")
            cursor.execute("START TRANSACTION READ ONLY")
            cursor.execute("SELECT id, name, model_code, packaging_kind, material, shape_type, "
                           "capacity_ml, applicable, customizable, default_image, "
                           "website_sort_order FROM packaging_models "
                           "WHERE deleted_at IS NULL AND status='在售' AND website_enabled=1 "
                           "ORDER BY COALESCE(website_sort_order,0), id")
            source = cursor.fetchall()
            cursor.execute("SELECT COUNT(*) AS n FROM website_packaging_items")
            native_items = cursor.fetchone()["n"]
            cursor.execute("SELECT COUNT(*) AS n FROM website_packaging_variants")
            native_variants = cursor.fetchone()["n"]
            ids = [row["id"] for row in source]
            variant_counts = {}
            if ids:
                cursor.execute("SELECT model_id, COUNT(*) AS n FROM packaging_variants "
                               "WHERE deleted_at IS NULL AND model_id IN (" + ",".join(["%s"] * len(ids))
                               + ") GROUP BY model_id", ids)
                variant_counts = {row["model_id"]: row["n"] for row in cursor.fetchall()}
            db.rollback()
    finally:
        db.close()
    return {"read_only": True, "public_source_count": len(source),
            "source_ids": ids, "source_kind_counts": dict(Counter(row["packaging_kind"] for row in source)),
            "source_with_default_image": sum(bool(row["default_image"]) for row in source),
            "source_with_variant": sum(variant_counts.get(row["id"], 0) > 0 for row in source),
            "source_variant_count": sum(variant_counts.values()),
            "native_items": native_items, "native_variants": native_variants,
            "source_models": [{"id": row["id"], "name": row["name"], "model_code": row["model_code"],
                               "default_image_present": bool(row["default_image"]),
                               "variant_count": variant_counts.get(row["id"], 0)} for row in source]}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--env-file", type=Path, default=ROOT / "backend/.env")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    report = audit(args.env_file)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({k: report[k] for k in ("public_source_count", "source_with_default_image",
                                                  "source_with_variant", "source_variant_count",
                                                  "native_items", "native_variants")}, ensure_ascii=False))


if __name__ == "__main__":
    main()
