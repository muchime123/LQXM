import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'

/**
 * 结案「异常结论 → 权限」口径的**前后端一致性契约**。
 *
 * <p>为什么需要它：三种无法自证的结论（日期未知 / 本单不适用 / 适用但无法确认）后端都要求
 * {@code manufacturing.followup.abnormal}；前端必须与后端同口径，才能在保存前就
 * （1）弹出「异常结案原因」输入框、（2）拦住没有权限的人。
 *
 * <p>2026-10-30 的真实漂移：前端 {@code closureHasAbnormal} 漏算了 {@code DONE_NO_DATE}，
 * 于是用户把节点一路填到最后一步，点保存才收到一个 403 —— 界面事前毫无提示。
 * 这类漂移只有在「两边单看都没问题、放到一起才出事」时才会暴露，故在此钉成契约。
 *
 * <p>提醒：与 PermissionEntryGates.contract.test.ts 的立场相同 ——
 * 源码断言只兜**口径一致**，真正的权限边界仍在后端写方法内部。
 */

const ROOT = join(process.cwd(), 'src')
const DRAWER = join(ROOT, 'components/production/TrackingDetailDrawer.vue')
const API = join(ROOT, 'api/productionTracking.ts')
const SERVICE = join(ROOT, '../../backend/src/main/java/com/liqiang/archive/service/ProductionTrackingService.java')

function read(path: string): string {
  return readFileSync(path, 'utf8')
}

/** 后端要求 abnormal 权限的结论（与后端 ABNORMAL_RESULTS 一一对应）。 */
const ABNORMAL = ['DONE_NO_DATE', 'APPLICABLE', 'NOT_APPLICABLE'] as const
/** 四种可选结论（与后端 CONFIRM_RESULTS 对应）。 */
const ALL_RESULTS = ['DONE_WITH_DATE', 'DONE_NO_DATE', 'APPLICABLE', 'NOT_APPLICABLE'] as const

describe('TrackingClosurePermissionGate', () => {
  const drawer = read(DRAWER)

  it('后端：三种无法自证的结论统一要求 abnormal 权限', () => {
    expect(read(SERVICE)).toMatch(
      /ABNORMAL_RESULTS\s*=\s*Set\.of\("DONE_NO_DATE",\s*"APPLICABLE",\s*"NOT_APPLICABLE"\)/)
  })

  it('前端：closureHasAbnormal 逐个覆盖后端的三种异常结论', () => {
    const at = drawer.indexOf('const closureHasAbnormal')
    expect(at).toBeGreaterThan(-1)
    const body = drawer.slice(at, at + 320)
    for (const result of ABNORMAL) expect(body).toContain(`'${result}'`)
  })

  it('前端：四种结论都能在下拉里选到', () => {
    for (const result of ALL_RESULTS) expect(drawer).toContain(`value="${result}"`)
  })

  it('前端：没选异常结论时才能跳过权限校验，选了就要求权限且必填原因', () => {
    expect(drawer).toMatch(/!closureHasAbnormal\.value \|\| \(canAbnormalClosure\.value/)
    expect(drawer).toMatch(/closureAbnormalReason\.value\.trim\(\)\.length >= 6/)
    expect(drawer).toContain('canAbnormalClosure')
  })

  it('前端：没有权限时要给出提示，而不是让用户点保存才发现', () => {
    expect(drawer).toMatch(/closureHasAbnormal && !canAbnormalClosure/)
  })

  it('API 类型与后端结论取值保持一致', () => {
    const api = read(API)
    expect(api).toContain("result: 'DONE_WITH_DATE' | 'DONE_NO_DATE' | 'APPLICABLE' | 'NOT_APPLICABLE'")
    expect(api).toContain('abnormalReason: string | null = null')
  })
})
