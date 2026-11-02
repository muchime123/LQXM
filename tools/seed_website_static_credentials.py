"""Stage the website's own static credential cards as unpublished native drafts.

No archive content is read. Default mode is a read-only preflight. Existing
website credential rows block insertion, so reruns cannot duplicate content.
"""
import argparse
import json
import re
from pathlib import Path

from seed_website_company_profile import ROOT, connect


STATIC_SOURCE = ROOT / "corporate-website/src/data/qualifications.js"


def public_cards(path):
    text = path.read_text(encoding="utf-8")
    match = re.search(r"export const certifications\s*=\s*\[(.*?)\]\s*/\*\*", text, re.S)
    if not match:
        raise ValueError("Static credential array structure changed")
    cards = []
    for block in re.findall(r"\{([^{}]+)\}", match.group(1), re.S):
        def field(key):
            found = re.search(r"\b" + key + r":\s*'([^']*)'", block)
            if not found:
                raise ValueError("Static credential field missing: " + key)
            return found.group(1)
        identifier = re.search(r"\bid:\s*(\d+)", block)
        if not identifier:
            raise ValueError("Static credential ID missing")
        cards.append({"static_id": int(identifier.group(1)), "name": field("name"),
                      "category": field("category"), "description": field("description"),
                      "image": field("image")})
    if not cards or len({row["static_id"] for row in cards}) != len(cards):
        raise ValueError("Static credential cards are empty or IDs duplicate")
    return cards


def seed(env_file, apply, source_file=STATIC_SOURCE):
    cards = public_cards(source_file)
    db = connect(env_file)
    try:
        with db.cursor() as cursor:
            cursor.execute("START TRANSACTION" if apply else "START TRANSACTION READ ONLY")
            cursor.execute("SELECT COUNT(*) AS n FROM website_credentials")
            existing = cursor.fetchone()["n"]
            cursor.execute("SELECT name, category, public_description, issuer, valid_until, "
                           "media_id, sort_order, status FROM website_credentials ORDER BY sort_order, id")
            current = cursor.fetchall()
            blockers = []
            if existing:
                blockers.append("website_credentials already contains content")
            if any(row["image"] or not row["name"] or len(row["name"]) > 300
                   or len(row["category"]) > 100 or len(row["description"]) > 20000 for row in cards):
                blockers.append("Static card has media or exceeds target field constraints")
            report = {"source": str(source_file.relative_to(ROOT)),
                      "static_ids": [row["static_id"] for row in cards],
                      "source_count": len(cards), "target_count_before": existing,
                      "current_draft_matches_static": len(current) == len(cards) and all(
                          (a["name"], a["category"], a["description"], order)
                          == (b["name"], b["category"], b["public_description"], b["sort_order"])
                          and b["status"] == "DRAFT" and b["media_id"] is None
                          for order, (a, b) in enumerate(zip(cards, current))),
                      "ready": not blockers, "blockers": blockers, "applied": False,
                      "publication_changed": False}
            if apply and not blockers:
                mapping = []
                for order, row in enumerate(cards):
                    cursor.execute("INSERT INTO website_credentials "
                                   "(name, category, public_description, issuer, valid_until, media_id, "
                                   "sort_order, status) VALUES (%s, %s, %s, NULL, NULL, NULL, %s, 'DRAFT')",
                                   (row["name"], row["category"], row["description"], order))
                    mapping.append({"static_id": row["static_id"], "website_id": cursor.lastrowid})
                cursor.execute("SELECT name, category, public_description, issuer, valid_until, "
                               "media_id, sort_order, status FROM website_credentials ORDER BY sort_order, id")
                saved = cursor.fetchall()
                if len(saved) != len(cards) or any(
                    (a["name"], a["category"], a["description"], order)
                    != (b["name"], b["category"], b["public_description"], b["sort_order"])
                    or b["status"] != "DRAFT" or b["media_id"] is not None
                    or b["issuer"] is not None or b["valid_until"] is not None
                    for order, (a, b) in enumerate(zip(cards, saved))
                ):
                    raise RuntimeError("Static credential copy differs; rolled back")
                db.commit()
                report.update({"applied": True, "mapping": mapping,
                               "target_count_after": len(saved), "field_mismatches": 0})
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
