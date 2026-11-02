"""One-time staging of website-owned static IP cards as unpublished drafts.

No archive IDs or fields are copied. Default mode is read-only. The target
must exist and be empty; no Flyway migration is run by this tool.
"""
import argparse
import json
import re
from pathlib import Path

from seed_website_company_profile import ROOT, connect


SOURCE = ROOT / "corporate-website/src/data/qualifications.js"


def static_cards(path=SOURCE):
    text = path.read_text(encoding="utf-8")
    match = re.search(r"export const patents\s*=\s*\[(.*?)\]\s*$", text, re.S)
    if not match:
        raise ValueError("Static IP array structure changed")
    cards = []
    for block in re.findall(r"\{([^{}]+)\}", match.group(1), re.S):
        def field(key):
            found = re.search(r"\b" + key + r":\s*'([^']*)'", block)
            if not found:
                raise ValueError("Missing static IP field: " + key)
            return found.group(1)
        identifier = re.search(r"\bid:\s*(\d+)", block)
        if not identifier:
            raise ValueError("Missing static IP ID")
        cards.append({"static_id": int(identifier.group(1)), "name": field("name"),
                      "category": field("assetCategory"), "patent_type": field("patentType"),
                      "public_number": field("patentNo"), "applicant": field("applicant"),
                      "image": field("image")})
    if not cards or len({row["static_id"] for row in cards}) != len(cards):
        raise ValueError("Static IP cards are empty or IDs duplicate")
    return cards


def seed(env_file, apply):
    cards = static_cards()
    db = connect(env_file)
    try:
        with db.cursor() as cursor:
            cursor.execute("START TRANSACTION" if apply else "START TRANSACTION READ ONLY")
            cursor.execute("SELECT COUNT(*) AS n FROM information_schema.TABLES "
                           "WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='website_ip_items'")
            exists = bool(cursor.fetchone()["n"])
            existing = None
            if exists:
                cursor.execute("SELECT COUNT(*) AS n FROM website_ip_items")
                existing = cursor.fetchone()["n"]
            blockers = []
            if not exists:
                blockers.append("V436 website_ip_items table is not deployed")
            elif existing:
                blockers.append("website_ip_items already contains content")
            if any(row["image"] or row["public_number"] or row["applicant"]
                   or not row["name"] or not row["category"] for row in cards):
                blockers.append("Static IP card changed; review media and public identity first")
            report = {"source": str(SOURCE.relative_to(ROOT)),
                      "static_ids": [row["static_id"] for row in cards],
                      "source_count": len(cards), "table_exists": exists,
                      "target_count_before": existing, "ready": not blockers,
                      "blockers": blockers, "applied": False, "publication_changed": False}
            if apply and not blockers:
                mapping = []
                for order, row in enumerate(cards):
                    cursor.execute("INSERT INTO website_ip_items "
                                   "(name, category, patent_type, public_number, applicant, legal_status, "
                                   "media_id, sort_order, status) "
                                   "VALUES (%s, %s, %s, NULL, NULL, NULL, NULL, %s, 'DRAFT')",
                                   (row["name"], row["category"], row["patent_type"] or None, order))
                    mapping.append({"static_id": row["static_id"], "website_id": cursor.lastrowid})
                cursor.execute("SELECT name, category, patent_type, public_number, applicant, "
                               "legal_status, media_id, sort_order, status "
                               "FROM website_ip_items ORDER BY sort_order, id")
                target = cursor.fetchall()
                if len(target) != len(cards) or any(
                    (a["name"], a["category"], a["patent_type"] or None, order)
                    != (b["name"], b["category"], b["patent_type"], b["sort_order"])
                    or b["status"] != "DRAFT" or any(b[k] is not None for k in
                        ("public_number", "applicant", "legal_status", "media_id"))
                    for order, (a, b) in enumerate(zip(cards, target))
                ):
                    raise RuntimeError("IP draft copy differs from static cards; rolled back")
                db.commit()
                report.update({"applied": True, "mapping": mapping,
                               "target_count_after": len(target), "field_mismatches": 0})
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
