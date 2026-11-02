"""Read-only credential candidate inventory; never infers publication from archive status."""
import argparse
import json
from pathlib import Path

from seed_website_company_profile import ROOT, connect


def audit(env_file, file_root):
    db = connect(env_file)
    try:
        with db.cursor() as cursor:
            cursor.execute("SET SESSION TRANSACTION READ ONLY")
            cursor.execute("START TRANSACTION READ ONLY")
            cursor.execute("SELECT id, name, category, status, expiry_date, is_permanent, "
                           "issuing_authority, file_name FROM company_certifications ORDER BY id")
            source = cursor.fetchall()
            cursor.execute("SELECT id, name, category, status, valid_until, media_id, deleted_at "
                           "FROM website_credentials ORDER BY id")
            target = cursor.fetchall()
            db.rollback()
    finally:
        db.close()
    rows = []
    for item in source:
        filename = item["file_name"]
        safe = bool(filename and Path(filename).name == filename and filename not in (".", ".."))
        matches = []
        if safe:
            for relative in (Path("company") / "certification" / filename,
                             Path("company") / "certifications" / filename,
                             Path("company") / filename):
                path = (file_root / relative).resolve()
                if path.is_relative_to(file_root) and path.is_file():
                    matches.append(relative.as_posix())
        rows.append({"id": item["id"], "name": item["name"], "category": item["category"],
                     "status": item["status"], "expiry_date": item["expiry_date"],
                     "is_permanent": bool(item["is_permanent"]),
                     "issuer_present": bool(item["issuing_authority"]),
                     "file_name_present": bool(filename), "file_candidates": matches})
    return {"read_only": True, "archive_count": len(rows), "archive_candidates": rows,
            "website_count": len(target), "website_records": target}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--env-file", type=Path, default=ROOT / "backend/.env")
    parser.add_argument("--file-root", type=Path, default=ROOT / "storage/lq-files")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    report = audit(args.env_file, args.file_root.resolve(strict=True))
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2, default=str) + "\n", encoding="utf-8")
    print(json.dumps({"archive_count": report["archive_count"], "website_count": report["website_count"],
                      "with_single_file": sum(len(row["file_candidates"]) == 1 for row in report["archive_candidates"]),
                      "without_file": sum(not row["file_candidates"] for row in report["archive_candidates"])},
                     ensure_ascii=False))


if __name__ == "__main__":
    main()
