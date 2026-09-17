// P0: 为各控制器所有写接口（@Post/@Put/@DeleteMapping）批量插入 @RequirePermission。
// 已存在注解或映射注解跨多行的会跳过并报告。
import { readFileSync, writeFileSync } from 'node:fs'

const BASE = 'c:/Users/20676/source/LQXM/backend/src/main/java/com/liqiang/archive/controller/'
const IMPORT = 'import com.liqiang.archive.security.RequirePermission;'
const MAPPING_RE = /^(\s*)@(PostMapping|PutMapping|DeleteMapping)(.*)$/m

const JOBS = [
  ['ProductCatalogController.java', 'product.manage'],
  ['CatalogImportController.java', 'product.manage'],
  ['PackagingController.java', 'packaging.manage'],
  ['PackagingMoldController.java', 'mold.manage'],
  ['PackagingMoldGroupController.java', 'mold.manage'],
  ['MediaAccountController.java', 'media.manage'],
  ['CompanyInfoController.java', 'company.manage'],
  ['CertificateController.java', 'certificate.manage'],
  ['OfficeAssetController.java', 'officeAsset.manage'],
  ['WebsiteSolutionController.java', 'website.manage'],
  ['WebsiteBannerController.java', 'website.manage'],
  ['WebsitePageSectionController.java', 'website.manage'],
  ['WebsiteInquiryController.java', 'website.manage'],
  ['PlatformConfigController.java', 'system.config.manage'],
  ['FileAssetController.java', 'file.manage'],
  ['SalesProductController.java', 'profit.manage'],
  ['SalesProfitController.java', 'profit.manage'],
]

let totalAdded = 0
for (const [fname, perm] of JOBS) {
  const path = BASE + fname
  const lines = readFileSync(path, 'utf-8').split('\n')
  const out = []
  let added = 0, skipped = 0
  for (let i = 0; i < lines.length; i++) {
    const line = lines[i]
    const m = MAPPING_RE.exec(line)
    if (m) {
      const tail = m[3]
      if ((tail.match(/\(/g) || []).length !== (tail.match(/\)/g) || []).length) {
        console.log(`SKIP multiline mapping: ${fname}:${i + 1}: ${line.trim()}`)
        out.push(line)
        continue
      }
      const nearby = lines.slice(Math.max(0, i - 2), i).concat(lines.slice(i + 1, i + 3))
      if (nearby.some((ln) => ln.includes('@RequirePermission'))) {
        skipped++
        out.push(line)
        continue
      }
      out.push(`${m[1]}@RequirePermission("${perm}")`)
      added++
    }
    out.push(line)
  }

  if (!out.some((ln) => ln.includes(IMPORT))) {
    let idx = -1
    for (let j = 0; j < out.length; j++) {
      if (out[j].startsWith('import com.liqiang')) idx = j
    }
    if (idx >= 0) out.splice(idx + 1, 0, IMPORT)
    else console.log(`WARN no liqiang import anchor: ${fname}`)
  }

  writeFileSync(path, out.join('\n'), 'utf-8')
  totalAdded += added
  console.log(`${fname}: +${added} (skip ${skipped})`)
}
console.log(`TOTAL added: ${totalAdded}`)
