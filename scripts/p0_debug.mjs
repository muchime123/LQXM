import { readFileSync } from 'node:fs'
const path = 'c:/Users/20676/source/LQXM/backend/src/main/java/com/liqiang/archive/controller/CompanyInfoController.java'
const lines = readFileSync(path, 'utf-8').split('\n')
const MAPPING_RE = /^(\s*)@(PostMapping|PutMapping|DeleteMapping)(.*)$/
let matchCount = 0
for (let i = 0; i < lines.length; i++) {
  const m = MAPPING_RE.exec(lines[i])
  if (m) {
    matchCount++
    console.log(`MATCH line ${i + 1}: g1=${JSON.stringify(m[1])} g3=${JSON.stringify(m[3])}`)
  }
}
console.log('matchCount:', matchCount)
// 打印所有包含 Mapping 的行
for (let i = 0; i < lines.length; i++) {
  if (lines[i].includes('Mapping')) {
    console.log(`raw ${i + 1}: ${JSON.stringify(lines[i])}`)
  }
}
