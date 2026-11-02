"""Guarded one-time copy of currently public milestones into website drafts.

Default is read-only. --apply inserts drafts only if the native table is empty.
"""
import argparse
import json
from pathlib import Path

from seed_website_company_profile import ROOT, connect


def seed(env_file, apply):
    db = connect(env_file)
    try:
        with db.cursor() as cursor:
            cursor.execute("START TRANSACTION" if apply else "START TRANSACTION READ ONLY")
            cursor.execute("SELECT id, year, title, description, sort_order, file_name "
                           "FROM company_milestones WHERE website_enabled=1 "
                           "ORDER BY sort_order, id")
            source = cursor.fetchall()
            cursor.execute("SELECT COUNT(*) AS n FROM website_company_milestones")
            existing = cursor.fetchone()["n"]
            blockers = []
            if existing:
                blockers.append("website_company_milestones already contains content")
            if any(row["year"] is None or not row["title"] or len(row["title"]) > 200
                   or (row["description"] and len(row["description"]) > 20000) for row in source):
                blockers.append("public milestone exceeds native field constraints")
            if any(row["file_name"] for row in source):
                blockers.append("public milestone has an attachment that needs separate media review")
            report = {"source_ids": [row["id"] for row in source], "source_count": len(source),
                      "target_count_before": existing, "ready": not blockers,
                      "blockers": blockers, "applied": False}
            if apply and not blockers:
                mapped = []
                for row in source:
                    cursor.execute(
                        "INSERT INTO website_company_milestones "
                        "(milestone_year, title, description, sort_order, status) "
                        "VALUES (%s, %s, %s, %s, 'DRAFT')",
                        (row["year"], row["title"], row["description"], row["sort_order"] or 0),
                    )
                    mapped.append({"source_id": row["id"], "website_id": cursor.lastrowid})
                cursor.execute("SELECT milestone_year, title, description, sort_order, status "
                               "FROM website_company_milestones ORDER BY sort_order, id")
                target = cursor.fetchall()
                if len(target) != len(source) or any(
                    (a["year"], a["title"], a["description"], a["sort_order"] or 0)
                    != (b["milestone_year"], b["title"], b["description"], b["sort_order"])
                    or b["status"] != "DRAFT" for a, b in zip(source, target)
                ):
                    raise RuntimeError("Milestone copy differs from public source; rolled back")
                db.commit()
                report.update({"applied": True, "mapping": mapped, "target_count_after": len(target),
                               "field_mismatches": 0})
            else:
                db.rollback()
            return report
    except Exception:
        db.rollback()
        raise
    finally:
        db.close()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--env-file", type=Path, default=ROOT / "backend/.env")
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--apply", action="store_true")
    args = parser.parse_args()
    result = seed(args.env_file, args.apply)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(result, ensure_ascii=False))


if __name__ == "__main__":
    main()
