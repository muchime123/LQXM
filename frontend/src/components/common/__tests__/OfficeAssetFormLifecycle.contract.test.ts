import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

/**
 * B7-B1a：OfficeAssetView 表单生命周期契约。
 *
 * 为什么是源码级而不是挂载式：与 OperationLogPilot.contract.test.ts 同因——
 * 在本仓库里新建 `src/apps/archive/views/**\/__tests__/` 并在其中挂载业务页面，
 * 会在收集期触发 Vitest 崩溃（根因见 B7-B0：Windows 盘符大小写导致的
 * 模块双实例，与文件数量无关）。页面级行为验证由
 * e2e/office-asset-form-lifecycle.spec.ts 在真实浏览器中承担。
 *
 * 这里钉死「新建 / 编辑状态彻底隔离」的实现不变量，防止修复被无意回退。
 */

const VIEW = resolve(__dirname, '../../../apps/archive/views/product/OfficeAssetView.vue')
const source = readFileSync(VIEW, 'utf-8')
const code = source.replace(/\/\*[\s\S]*?\*\//g, '').replace(/<!--[\s\S]*?-->/g, '')

/** 取出 `EDITABLE_FIELDS = [...]` 字面量里的字段名集合 */
function editableFieldKeys(): string[] {
  const m = code.match(/EDITABLE_FIELDS\s*=\s*\[([\s\S]*?)\]/)
  expect(m, '未找到 EDITABLE_FIELDS 数组字面量').not.toBeNull()
  return [...(m?.[1] ?? '').matchAll(/'([^']+)'/g)].map(x => x[1] ?? '')
}

/** 取出 emptyForm() 返回对象的键集合 */
function emptyFormKeys(): string[] {
  const m = code.match(/const emptyForm[\s\S]*?=>\s*\(\{([\s\S]*?)\}\),/)
  expect(m, '未找到 emptyForm 返回对象').not.toBeNull()
  return [...(m?.[1] ?? '').matchAll(/(?:^|\n)\s*(\w+):/g)].map(x => x[1] ?? '')
}

const BACKEND_MANAGED = ['id', 'lastInventoryAt', 'createdAt', 'updatedAt', 'deletedAt']

describe('OfficeAssetView 表单生命周期契约（B7-B1a）', () => {
  it('声明可编辑字段白名单 EDITABLE_FIELDS', () => {
    expect(code).toMatch(/EDITABLE_FIELDS\s*=\s*\[/)
  })

  it('白名单覆盖全部可编辑字段', () => {
    const fields = editableFieldKeys()
    for (const key of [
      'name',
      'category',
      'assetCode',
      'status',
      'brand',
      'model',
      'serialNumber',
      'department',
      'custodian',
      'location',
      'purchaseDate',
      'purchasePrice',
      'supplier',
      'warrantyUntil',
      'notes'
    ]) {
      expect(fields, `白名单缺少可编辑字段 ${key}`).toContain(key)
    }
  })

  it('白名单不含后端维护字段（id / lastInventoryAt / createdAt / updatedAt / deletedAt）', () => {
    const fields = editableFieldKeys()
    for (const key of BACKEND_MANAGED) {
      expect(fields, `白名单不应包含后端维护字段 ${key}`).not.toContain(key)
    }
  })

  it('emptyForm() 键集与白名单口径一致（进出双向同口径）', () => {
    expect(editableFieldKeys().sort()).toEqual(emptyFormKeys().sort())
  })

  it('存在显式表单状态构造函数 toFormState，且以 emptyForm() 打底、只取白名单字段', () => {
    expect(code).toMatch(/function\s+toFormState\s*\(/)
    expect(code).toMatch(/toFormState[\s\S]{0,400}?emptyForm\(\)[\s\S]{0,400}?EDITABLE_FIELDS/)
  })

  it('openCreate / openEdit 一律走 resetForm 整体替换，不存在缺陷形态的裸 Object.assign', () => {
    expect(code).not.toMatch(/Object\.assign\(\s*form\s*,\s*emptyForm\(\)\s*\)/)
    expect(code).not.toMatch(/Object\.assign\(\s*form\s*,\s*asset\s*\)/)
    expect(code).toMatch(/function\s+openCreate[\s\S]{0,220}?resetForm\(/)
    expect(code).toMatch(/function\s+openEdit[\s\S]{0,220}?resetForm\(/)
  })

  it('resetForm 先删除全部历史 key 再写入，并清理上一次的校验错误态', () => {
    const body = code.slice(code.indexOf('function resetForm'))
    expect(body.slice(0, 400)).toMatch(/delete\s+form\[/)
    expect(body.slice(0, 600)).toMatch(/clearValidate\(\)/)
  })

  it('提交载荷经过白名单映射，不直接发送整个 form', () => {
    expect(code).not.toMatch(/createOfficeAsset\(\s*form\s*\)/)
    expect(code).not.toMatch(/updateOfficeAsset\([^)]*,\s*form\s*\)/)
    expect(code).toMatch(/const\s+payload\s*=\s*toFormState\(/)
  })
})
