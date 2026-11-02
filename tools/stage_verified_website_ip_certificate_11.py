"""Stage reviewed certificate 11 as an independent, unpublished website IP item.

The source image was visually checked against the registered name, type,
patent number and owner. Default mode performs a read-only preflight.
"""
import argparse
import hashlib
import json
import shutil
from pathlib import Path

from seed_website_company_profile import ROOT, connect


EXPECTED = {
    "id": 11,
    "name": "全自动连续式气调保鲜包装机",
    "patent_type": "外观专利",
    "patent_no": "ZL202030035953.8",
    "applicant": "浙江利强包装机械有限公司",
}


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def stage(file_root, apply):
    db = connect(ROOT / "backend/.env")
    try:
        with db.cursor() as cursor:
            cursor.execute("START TRANSACTION" if apply else "START TRANSACTION READ ONLY")
            cursor.execute("SELECT id,name,asset_category,asset_status,patent_type,patent_no,"
                           "applicant,legal_status,cert_file,deleted_at FROM certificates WHERE id=11")
            source = cursor.fetchone()
            cursor.execute("SELECT id FROM website_ip_items WHERE name=%s AND deleted_at IS NULL",
                           (EXPECTED["name"],))
            duplicate = cursor.fetchone()
            blockers = []
            if not source or any(source.get(key) != value for key, value in EXPECTED.items()):
                blockers.append("Certificate identity differs from reviewed original")
            if not source or source["asset_category"] != "专利" or source["asset_status"] != "ACTIVE" \
                    or source["legal_status"] != "正常有效" or source["deleted_at"] is not None:
                blockers.append("Certificate is not currently eligible")
            if duplicate:
                blockers.append("A website IP item with this name already exists")
            filename = source["cert_file"] if source else None
            files = [p for p in file_root.rglob(filename) if p.is_file()] if filename \
                    and Path(filename).name == filename and Path(filename).suffix.lower() == ".png" else []
            if len(files) != 1:
                blockers.append("Reviewed PNG source file is not uniquely available")
            original = files[0] if len(files) == 1 else None
            digest = sha256(original) if original else None
            key = f"website/images/{digest}.png" if digest else None
            cursor.execute("SELECT id,sha256,status FROM website_media_assets WHERE storage_key=%s", (key,))
            existing = cursor.fetchone()
            if existing and (existing["sha256"] != digest or existing["status"] == "ARCHIVED"):
                blockers.append("Website media key is conflicting or archived")
            report = {"source_id": 11, "ready": not blockers, "blockers": blockers,
                      "source_file_found": original is not None, "media_already_registered": bool(existing),
                      "applied": False, "published": False}
            if blockers or not apply:
                db.rollback()
                return report
            destination = file_root / key
            destination.parent.mkdir(parents=True, exist_ok=True)
            if not destination.exists():
                with original.open("rb") as incoming, destination.open("xb") as outgoing:
                    shutil.copyfileobj(incoming, outgoing)
            if sha256(destination) != digest:
                raise ValueError("Website copy does not match reviewed certificate")
            if existing:
                media_id = existing["id"]
            else:
                cursor.execute("INSERT INTO website_media_assets "
                               "(storage_key,original_filename,display_name,media_type,mime_type,"
                               "size_bytes,sha256,alt_text,status) VALUES "
                               "(%s,%s,%s,'IMAGE','image/png',%s,%s,%s,'DRAFT')",
                               (key, filename, EXPECTED["name"] + "外观设计专利证书",
                                original.stat().st_size, digest, EXPECTED["name"] + "专利证书"))
                media_id = cursor.lastrowid
            cursor.execute("INSERT INTO website_ip_items "
                           "(name,category,patent_type,public_number,applicant,legal_status,"
                           "media_id,sort_order,status) VALUES "
                           "(%s,'专利','外观设计',%s,%s,'正常有效',%s,4,'DRAFT')",
                           (EXPECTED["name"], EXPECTED["patent_no"], EXPECTED["applicant"], media_id))
            website_id = cursor.lastrowid
            cursor.execute("INSERT INTO audit_logs "
                           "(entity_type,entity_id,model_name,action,changes,created_at) VALUES "
                           "('WebsiteIpItem',%s,%s,'WEBSITE_IP_EVIDENCE_STAGE',"
                           "'依据证书原件建立官网独立草稿，未发布',NOW())",
                           (website_id, EXPECTED["name"]))
            db.commit()
            report.update({"applied": True, "website_id": website_id, "media_id": media_id,
                           "copy_hash_verified": True})
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
