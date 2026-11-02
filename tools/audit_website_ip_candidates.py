"""Read-only evidence inventory for four static website IP cards.

This intentionally reports candidate IDs, not an automatic ID mapping.
"""
import argparse
import json
from collections import Counter
from pathlib import Path

from seed_website_company_profile import ROOT, connect


STATIC_NAMES = ("气调保鲜包装机", "连续式封口机", "真空包装机", "包装设备控制软件")


def audit(env_file):
    db = connect(env_file)
    try:
        with db.cursor() as cursor:
            cursor.execute("SET SESSION TRANSACTION READ ONLY")
            cursor.execute("START TRANSACTION READ ONLY")
            cursor.execute("SELECT id, name, asset_category, asset_status, patent_type, "
                           "patent_no, publication_no, applicant, legal_status, cert_file, deleted_at "
                           "FROM certificates")
            certificates = cursor.fetchall()
            db.rollback()
    finally:
        db.close()
    active = [row for row in certificates if row["deleted_at"] is None]
    candidates = []
    for display_name in STATIC_NAMES:
        matches = [row for row in active if display_name in row["name"] or row["name"] in display_name]
        candidates.append({"static_name": display_name, "exact_name_match_ids":
                           [row["id"] for row in active if row["name"] == display_name],
                           "possible_name_match_ids": [row["id"] for row in matches],
                           "possible_matches": [{"id": row["id"], "name": row["name"],
                                                 "category": row["asset_category"],
                                                 "asset_status": row["asset_status"],
                                                 "legal_status": row["legal_status"],
                                                 "number_present": bool(row["patent_no"] or row["publication_no"]),
                                                 "applicant_present": bool(row["applicant"]),
                                                 "certificate_file_present": bool(row["cert_file"])}
                                                for row in matches]})
    return {"read_only": True, "total_records": len(certificates), "active_records": len(active),
            "asset_status_counts": dict(Counter(row["asset_status"] for row in active)),
            "legal_status_counts": dict(Counter(row["legal_status"] for row in active)),
            "static_card_candidates": candidates,
            "mapping_verified": False}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--env-file", type=Path, default=ROOT / "backend/.env")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    report = audit(args.env_file)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2, default=str) + "\n", encoding="utf-8")
    print(json.dumps({"total_records": report["total_records"], "active_records": report["active_records"],
                      "candidate_counts": {row["static_name"]: len(row["possible_matches"])
                                           for row in report["static_card_candidates"]},
                      "mapping_verified": False}, ensure_ascii=False))


if __name__ == "__main__":
    main()
