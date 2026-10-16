import { beforeEach, describe, expect, it, vi } from 'vitest'
import { nextTick, ref } from 'vue'
import { EXPORT_COLUMNS, useTrackingExport } from './useTrackingExport'

vi.mock('element-plus', () => ({
  ElMessage: { success: vi.fn(), warning: vi.fn(), error: vi.fn(), info: vi.fn() },
  ElMessageBox: { confirm: vi.fn(), prompt: vi.fn() }
}))
vi.mock('@/api/productionTracking', () => ({
  fetchTrackingExport: vi.fn(async () => ({ items: [], total: 0, exported: 0, truncated: false, maxRows: 0 }))
}))

/**
 * 勾选记忆读写的是 localStorage，而单测跑在 node 环境里没有它 —— 装一个内存实现，
 * 让「老存档升版」这条链路能被验证（这段逻辑的来龙去脉见 useTrackingExport 的 restoreExportColumns）。
 */
const store = new Map<string, string>()
Object.defineProperty(globalThis, 'localStorage', {
  value: {
    getItem: (key: string) => store.get(key) ?? null,
    setItem: (key: string, value: string) => { store.set(key, value) },
    removeItem: (key: string) => { store.delete(key) },
    clear: () => store.clear()
  },
  configurable: true,
  writable: true
})

function createExport() {
  return useTrackingExport({ filters: () => ({}), visibility: ref('ALL'), selectedIds: () => [] })
}

describe('导出勾选记忆：版本升级', () => {
  beforeEach(() => store.clear())

  it('没有记忆时全选所有列', () => {
    expect(createExport().exportColumns.value).toEqual(EXPORT_COLUMNS.map(column => column.key))
  })

  it('v1 记忆（纯数组）读入时补上 10-15 新增的三列', () => {
    // LQ 浏览器里存着的是升级前的纯数组；只取交集的话他打开弹窗永远看不到新列。
    localStorage.setItem('lq.trackingExportColumns', JSON.stringify(['orderNo', 'product']))
    const keys = createExport().exportColumns.value
    expect(keys).toContain('orderNo')
    expect(keys).toContain('product')
    for (const key of ['overdueReason', 'otherReviewReason', 'stageNotes']) expect(keys, key).toContain(key)
  })

  it('v2 记忆一律按用户自己的勾选来（不会反复把新列补回来）', () => {
    localStorage.setItem('lq.trackingExportColumns', JSON.stringify({ version: 2, keys: ['orderNo'] }))
    expect(createExport().exportColumns.value).toEqual(['orderNo'])
  })

  it('记忆里已不存在的列被丢弃，不会带着脏 key 进弹窗', () => {
    localStorage.setItem('lq.trackingExportColumns', JSON.stringify({ version: 2, keys: ['orderNo', 'noSuchColumn'] }))
    expect(createExport().exportColumns.value).toEqual(['orderNo'])
  })

  it('记忆整个坏掉时退回全选，而不是空勾选', () => {
    localStorage.setItem('lq.trackingExportColumns', '{ 这不是 JSON')
    expect(createExport().exportColumns.value).toEqual(EXPORT_COLUMNS.map(column => column.key))
  })
})

describe('导出：网页格式的产品是整体字段', () => {
  beforeEach(() => store.clear())

  it('切到网页格式时，只勾了产品组成部分也要把产品补上', async () => {
    const { exportFormat, exportColumns } = createExport()
    exportFormat.value = 'xlsx'
    await nextTick()
    // 网页格式下这三列不在选择器里露面，但勾选状态保留 ⇒ 切过去必须补出产品列，
    // 否则弹窗显示「已选 1 列」却导出一个孤零零的「数量」列。
    exportColumns.value = ['quantity']
    exportFormat.value = 'html'
    await nextTick()
    expect(exportColumns.value).toEqual(['product', 'quantity'])
  })
})
