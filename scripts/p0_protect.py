# -*- coding: utf-8 -*-
"""P0: 为各控制器所有写接口（@Post/@Put/@DeleteMapping）批量插入 @RequirePermission。
已存在注解或映射注解跨多行的会跳过并报告。"""
import re
import sys

BASE = "c:/Users/20676/source/LQXM/backend/src/main/java/com/liqiang/archive/controller/"

# (文件名, 权限码)
JOBS = [
    ("ProductCatalogController.java", "product.manage"),
    ("CatalogImportController.java", "product.manage"),
    ("PackagingController.java", "packaging.manage"),
    ("PackagingMoldController.java", "mold.manage"),
    ("PackagingMoldGroupController.java", "mold.manage"),
    ("MediaAccountController.java", "media.manage"),
    ("CompanyInfoController.java", "company.manage"),
    ("CertificateController.java", "certificate.manage"),
    ("OfficeAssetController.java", "officeAsset.manage"),
    ("WebsiteSolutionController.java", "website.manage"),
    ("WebsiteBannerController.java", "website.manage"),
    ("WebsitePageSectionController.java", "website.manage"),
    ("WebsiteInquiryController.java", "website.manage"),
    ("PlatformConfigController.java", "system.config.manage"),
    ("FileAssetController.java", "file.manage"),
    ("SalesProductController.java", "profit.manage"),
    ("SalesProfitController.java", "profit.manage"),
]

IMPORT = "import com.liqiang.archive.security.RequirePermission;"
MAPPING_RE = re.compile(r'^(\s*)@(PostMapping|PutMapping|DeleteMapping)(.*)$')

total_added = 0
for fname, perm in JOBS:
    path = BASE + fname
    with open(path, encoding="utf-8") as f:
        lines = f.readlines()

    out = []
    added = skipped = 0
    for i, line in enumerate(lines):
        m = MAPPING_RE.match(line)
        if m:
            tail = m.group(3)
            if tail.count('(') != tail.count(')'):
                print(f"SKIP multiline mapping: {fname}:{i+1}: {line.strip()}")
                out.append(line)
                continue
            # 若注解块上下文中已有 @RequirePermission 则跳过，避免重复
            nearby = lines[max(0, i - 2):i] + lines[i + 1:i + 3]
            if any("@RequirePermission" in ln for ln in nearby):
                skipped += 1
                out.append(line)
                continue
            out.append(f'{m.group(1)}@RequirePermission("{perm}")\n')
            added += 1
        out.append(line)

    if not any(IMPORT in ln for ln in out):
        idx = None
        for j, ln in enumerate(out):
            if ln.startswith("import com.liqiang"):
                idx = j
        if idx is not None:
            out.insert(idx + 1, IMPORT + "\n")
        else:
            print(f"WARN no liqiang import anchor: {fname}")

    with open(path, "w", encoding="utf-8") as f:
        f.writelines(out)
    total_added += added
    print(f"{fname}: +{added} (skip {skipped})")

print(f"TOTAL added: {total_added}")
