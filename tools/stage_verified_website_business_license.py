"""Copy the visually reviewed business license into an unpublished website draft.

Default mode is read-only. --apply affects website credential 3 and website
media only; it does not change the company archive or publish the credential.
"""
import argparse
import json
import shutil
from pathlib import Path

from seed_website_company_profile import ROOT, connect
from stage_verified_website_ip_item import digest


SOURCE_ID = 1
TARGET_ID = 3
COMPANY = "浙江利强包装科技有限公司"
LICENSE_NUMBER = "9133030207622289XP"


def stage(file_root, apply):
    db = connect(ROOT / "backend/.env")
    try:
        with db.cursor() as cursor:
            cursor.execute("START TRANSACTION" if apply else "START TRANSACTION READ ONLY")
            if apply:
                cursor.execute("SELECT id FROM website_credentials WHERE id=%s FOR UPDATE", (TARGET_ID,))
            cursor.execute("SELECT id,name,category,cert_number,status,expiry_date,"
                           "issuing_authority,file_name FROM company_certifications WHERE id=%s",
                           (SOURCE_ID,))
            source = cursor.fetchone()
            cursor.execute("SELECT id,name,category,media_id,status,issuer,valid_until,"
                           "deleted_at FROM website_credentials WHERE id=%s", (TARGET_ID,))
            target = cursor.fetchone()
            blockers = []
            if not source or source["name"] != COMPANY or source["category"] != "营业执照" \
                    or source["cert_number"] != LICENSE_NUMBER or source["status"] != "有效":
                blockers.append("Archive license differs from visually reviewed original")
            if not target or target["name"] != "营业执照" or target["status"] != "DRAFT" \
                    or target["media_id"] is not None or target["deleted_at"] is not None:
                blockers.append("Website license draft has changed")
            filename = source["file_name"] if source else None
            paths = [p for p in file_root.rglob(filename) if p.is_file()] if filename \
                    and Path(filename).name == filename and Path(filename).suffix.lower() in (".jpg", ".jpeg") else []
            if len(paths) != 1:
                blockers.append("Reviewed license image is not uniquely available")
            original = paths[0] if len(paths) == 1 else None
            sha = digest(original) if original else None
            key = f"website/images/{sha}.jpg" if sha else None
            cursor.execute("SELECT id,sha256,status FROM website_media_assets WHERE storage_key=%s", (key,))
            existing = cursor.fetchone()
            if existing and (existing["sha256"] != sha or existing["status"] == "ARCHIVED"):
                blockers.append("Website media key conflicts with another file")
            report = {"source_id": SOURCE_ID, "target_id": TARGET_ID, "ready": not blockers,
                      "blockers": blockers, "source_file_found": original is not None,
                      "media_already_registered": bool(existing), "applied": False, "published": False}
            if blockers or not apply:
                db.rollback()
                return report
            destination = file_root / key
            destination.parent.mkdir(parents=True, exist_ok=True)
            if not destination.exists():
                with original.open("rb") as incoming, destination.open("xb") as outgoing:
                    shutil.copyfileobj(incoming, outgoing)
            if digest(destination) != sha:
                raise ValueError("Website license copy failed hash verification")
            if existing:
                media_id = existing["id"]
            else:
                cursor.execute("INSERT INTO website_media_assets "
                               "(storage_key,original_filename,display_name,media_type,mime_type,"
                               "size_bytes,sha256,alt_text,status) VALUES "
                               "(%s,%s,%s,'IMAGE','image/jpeg',%s,%s,%s,'DRAFT')",
                               (key, filename, "营业执照", original.stat().st_size, sha, "公司营业执照"))
                media_id = cursor.lastrowid
            cursor.execute("UPDATE website_credentials SET media_id=%s,issuer=%s,valid_until=%s "
                           "WHERE id=%s AND status='DRAFT' AND media_id IS NULL AND deleted_at IS NULL",
                           (media_id, source["issuing_authority"], source["expiry_date"], TARGET_ID))
            if cursor.rowcount != 1:
                raise RuntimeError("Website license draft changed concurrently")
            cursor.execute("INSERT INTO audit_logs "
                           "(entity_type,entity_id,model_name,action,changes,created_at) VALUES "
                           "('WebsiteCredential',%s,'营业执照','WEBSITE_CREDENTIAL_EVIDENCE_STAGE',"
                           "'原件核验后补全官网独立草稿图片，未发布',NOW())", (TARGET_ID,))
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
    print(json.dumps(report, ensure_ascii=True))


if __name__ == "__main__":
    main()
