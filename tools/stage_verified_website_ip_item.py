"""Stage the visually verified design-patent certificate as website-owned IP media.

Default mode is read-only. --apply copies the certificate and enriches website
IP draft 1 only after exact source and target checks. It never publishes it.
"""
import argparse
import hashlib
import json
import shutil
from pathlib import Path

from seed_website_company_profile import ROOT, connect


SOURCE_ID = 10
TARGET_ID = 1
EXPECTED_NAME = "气调保鲜包装机"


def digest(path):
    value = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            value.update(block)
    return value.hexdigest()


def stage(file_root, apply):
    db = connect(ROOT / "backend/.env")
    try:
        with db.cursor() as cursor:
            cursor.execute("START TRANSACTION" if apply else "START TRANSACTION READ ONLY")
            if apply:
                cursor.execute("SELECT id FROM website_ip_items WHERE id=%s FOR UPDATE", (TARGET_ID,))
            cursor.execute("SELECT id,name,asset_category,asset_status,patent_type,patent_no,"
                           "publication_no,applicant,legal_status,cert_file,deleted_at "
                           "FROM certificates WHERE id=%s", (SOURCE_ID,))
            source = cursor.fetchone()
            cursor.execute("SELECT id,name,category,patent_type,public_number,applicant,"
                           "legal_status,media_id,status,deleted_at FROM website_ip_items WHERE id=%s",
                           (TARGET_ID,))
            target = cursor.fetchone()
            blockers = []
            if (not source or source["name"] != EXPECTED_NAME or source["asset_category"] != "专利"
                    or source["asset_status"] != "ACTIVE" or source["legal_status"] != "正常有效"
                    or source["patent_type"] != "外观专利" or source["deleted_at"] is not None):
                blockers.append("Source certificate identity or current status changed")
            if (not target or target["name"] != EXPECTED_NAME or target["category"] != "专利"
                    or target["status"] != "DRAFT" or target["deleted_at"] is not None
                    or target["public_number"] or target["applicant"] or target["legal_status"]
                    or target["media_id"]):
                blockers.append("Website IP draft no longer matches the staged static card")
            number = (source["patent_no"] or source["publication_no"]) if source else None
            if not number or not source["applicant"] or len(number) > 150 or len(source["applicant"]) > 300:
                blockers.append("Source certificate lacks a usable public number or applicant")
            filename = source["cert_file"] if source else None
            if not filename or Path(filename).name != filename or Path(filename).suffix.lower() != ".png":
                blockers.append("Certificate filename is missing or not a PNG")
                files = []
            else:
                files = [p for p in file_root.rglob(filename) if p.is_file()]
                if len(files) != 1:
                    blockers.append("Certificate file is not uniquely located")
            file = files[0] if len(files) == 1 else None
            sha = digest(file) if file else None
            key = f"website/images/{sha}.png" if sha else None
            cursor.execute("SELECT id,sha256,status FROM website_media_assets WHERE storage_key=%s", (key,))
            existing = cursor.fetchone()
            if existing and existing["sha256"] != sha:
                blockers.append("Website media path has conflicting content")
            report = {"ready": not blockers, "blockers": blockers, "source_id": SOURCE_ID,
                      "target_id": TARGET_ID, "certificate_found": file is not None,
                      "source_type": source["patent_type"] if source else None,
                      "draft_type_before": target["patent_type"] if target else None,
                      "media_already_registered": bool(existing), "applied": False,
                      "published": False}
            if not apply or blockers:
                db.rollback()
                return report
            destination = file_root / key
            destination.parent.mkdir(parents=True, exist_ok=True)
            if destination.exists():
                if digest(destination) != sha:
                    raise ValueError("Website destination differs from source certificate")
            else:
                with file.open("rb") as incoming, destination.open("xb") as outgoing:
                    shutil.copyfileobj(incoming, outgoing)
                if digest(destination) != sha:
                    raise ValueError("Copied certificate hash differs")
            if existing:
                if existing["status"] == "ARCHIVED":
                    raise ValueError("Existing website media is archived")
                media_id = existing["id"]
            else:
                cursor.execute("INSERT INTO website_media_assets "
                               "(storage_key,original_filename,display_name,media_type,mime_type,"
                               "size_bytes,sha256,alt_text,status) VALUES "
                               "(%s,%s,%s,'IMAGE','image/png',%s,%s,%s,'DRAFT')",
                               (key, filename, EXPECTED_NAME + "外观设计专利证书",
                                file.stat().st_size, sha, EXPECTED_NAME + "证书"))
                media_id = cursor.lastrowid
            cursor.execute("UPDATE website_ip_items SET patent_type='外观设计', public_number=%s, "
                           "applicant=%s, legal_status='正常有效', media_id=%s "
                           "WHERE id=%s AND status='DRAFT' AND media_id IS NULL AND deleted_at IS NULL",
                           (number, source["applicant"], media_id, TARGET_ID))
            if cursor.rowcount != 1:
                raise RuntimeError("Website IP draft changed concurrently")
            cursor.execute("INSERT INTO audit_logs (entity_type, entity_id, model_name, action, changes, created_at) "
                           "VALUES ('WebsiteIpItem', %s, %s, 'WEBSITE_IP_EVIDENCE_STAGE', "
                           "'核对证书原件并补全官网独立草稿，未发布', NOW())", (TARGET_ID, EXPECTED_NAME))
            db.commit()
            report.update({"applied": True, "media_id": media_id, "copy_hash_verified": True})
            return report
    except Exception:
        db.rollback()
        raise
    finally:
        db.close()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--file-root", type=Path, default=ROOT / "storage/lq-files")
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--apply", action="store_true")
    args = parser.parse_args()
    report = stage(args.file_root.resolve(strict=True), args.apply)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False))


if __name__ == "__main__":
    main()
