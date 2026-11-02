"""Copy the website's existing public Logo into website media and bind draft profile.

Default is a read-only preflight. --apply copies the file, registers a DRAFT
website media asset if needed, and binds only a DRAFT company profile.
"""
import argparse
import json
import shutil
import uuid
from pathlib import Path

from audit_website_banner_media import digest
from seed_website_company_profile import ROOT, connect


SOURCE = ROOT / "corporate-website/public/images/logo-only.png"


def stage(env_file, file_root, apply):
    source = SOURCE.resolve(strict=True)
    if not source.is_file() or source.suffix.lower() != ".png":
        raise ValueError("Website Logo source is not a PNG file")
    sha = digest(source)
    size = source.stat().st_size
    website_root = (file_root / "website").resolve()
    key = f"website/images/brand-logo-{sha}.png"
    target = (file_root / key).resolve()
    if not target.is_relative_to(website_root):
        raise ValueError("Target is outside website media storage")
    db = connect(env_file)
    try:
        with db.cursor() as cursor:
            cursor.execute("START TRANSACTION" if apply else "START TRANSACTION READ ONLY")
            cursor.execute("SELECT id, status, logo_media_id FROM website_company_profiles WHERE id=1"
                           + (" FOR UPDATE" if apply else ""))
            profile = cursor.fetchone()
            cursor.execute("SELECT id, storage_key, sha256, size_bytes, media_type, status, deleted_at "
                           "FROM website_media_assets WHERE sha256=%s AND deleted_at IS NULL ORDER BY id", (sha,))
            matches = cursor.fetchall()
            reusable = []
            for row in matches:
                media_file = (file_root / row["storage_key"]).resolve()
                if (row["media_type"] == "IMAGE" and row["size_bytes"] == size
                        and row["storage_key"].startswith("website/")
                        and media_file.is_relative_to(website_root) and media_file.is_file()
                        and digest(media_file) == sha and row["status"] in ("DRAFT", "PUBLISHED")):
                    reusable.append(row)
            chosen = reusable[0] if reusable else None
            blockers = []
            if profile is None or profile["status"] != "DRAFT":
                blockers.append("Website company profile is absent or not a draft")
            if (profile and profile["logo_media_id"] is not None
                    and (chosen is None or profile["logo_media_id"] != chosen["id"])):
                blockers.append("Website company profile already uses a different Logo")
            if target.exists() and (not target.is_file() or digest(target) != sha):
                blockers.append("Deterministic website Logo path has different content")
            report = {"source": str(SOURCE.relative_to(ROOT)), "sha256": sha,
                      "size_bytes": size, "profile_status": profile["status"] if profile else None,
                      "profile_logo_media_id_before": profile["logo_media_id"] if profile else None,
                      "reusable_media_id": chosen["id"] if chosen else None,
                      "ready": not blockers, "blockers": blockers, "applied": False,
                      "public_content_changed": False}
            if apply and not blockers:
                if chosen is None:
                    target.parent.mkdir(parents=True, exist_ok=True)
                    if not target.exists():
                        temporary = target.with_name(target.name + "." + uuid.uuid4().hex + ".tmp")
                        try:
                            shutil.copyfile(source, temporary)
                            if digest(temporary) != sha:
                                raise ValueError("Copied Logo hash differs")
                            temporary.replace(target)
                        finally:
                            temporary.unlink(missing_ok=True)
                    if digest(target) != sha or target.stat().st_size != size:
                        raise ValueError("Website Logo file verification failed")
                    cursor.execute("INSERT INTO website_media_assets "
                                   "(storage_key, original_filename, display_name, media_type, "
                                   "mime_type, size_bytes, sha256, alt_text, status) "
                                   "VALUES (%s, %s, %s, 'IMAGE', 'image/png', %s, %s, %s, 'DRAFT')",
                                   (key, SOURCE.name, "利强官网标志", size, sha, "利强包装标志"))
                    media_id = cursor.lastrowid
                    report["inserted_media"] = True
                else:
                    media_id = chosen["id"]
                    report["inserted_media"] = False
                if profile["logo_media_id"] != media_id:
                    cursor.execute("UPDATE website_company_profiles SET logo_media_id=%s "
                                   "WHERE id=1 AND status='DRAFT' AND logo_media_id IS NULL", (media_id,))
                    if cursor.rowcount != 1:
                        raise RuntimeError("Company draft changed concurrently")
                db.commit()
                report.update({"applied": True, "media_id": media_id,
                               "storage_key": chosen["storage_key"] if chosen else key})
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
    parser.add_argument("--file-root", type=Path, default=ROOT / "storage/lq-files")
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--apply", action="store_true")
    args = parser.parse_args()
    report = stage(args.env_file, args.file_root.resolve(strict=True), args.apply)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False))


if __name__ == "__main__":
    main()
