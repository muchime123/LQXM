import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import {
  TRACKING_COLUMN_SOURCE_META,
  TRACKING_COLUMN_SOURCE_ORDER,
  groupBySource,
  type TrackingColumnSource
} from './trackingColumnSources'

/**
 * 生产跟单**字段来源三分法**的契约测试（LQ 10-15）。
 *
 * `EXPORT_COLUMNS` 的每一列都必须声明 `source`（氚云同步/跟单登记/系统计算），
 * 漏标不会编译报错（TS 只约束类型，手写漏一行照样能跑），
 * 但那列会在分组弹窗里**凭空消失**——所以这里直读源码把「每列必有来源」钉死。
 *
 * 2026-10 起列定义随导出域搬出页面，宿主是 `useTrackingExport`；
 * 页面只保留「按分组渲染」这一段模板，故两份源码各读各的。
 */
const here = dirname(fileURLToPath(import.meta.url))
const exportSource = readFileSync(
  resolve(here, '../../composables/tracking/useTrackingExport.ts'), 'utf8')
const viewSource = readFileSync(
  resolve(here, '../../apps/archive/views/production/ProductionTrackingView.vue'), 'utf8')

/** `EXPORT_COLUMNS` 的每一列：{ key, source }（截块再抓，避免误抓文件里其它同类字面量）。 */
function exportColumnEntries(): { key: string; source: TrackingColumnSource | null }[] {
  const block = exportSource.split('const EXPORT_COLUMNS: ExportColumn[] = [')[1]
  if (block === undefined) throw new Error('未找到 EXPORT_COLUMNS 定义')
  const body = block.split('\n]')[0]
  // 每列的 key 是块内唯一锚：按 `key: '` 切开后，每段尾部（到下一列为止）挂着该列的 source。
  return body.split(/key: '/).slice(1).map(chunk => {
    const key = (chunk.match(/^([A-Za-z0-9_]+)/) || [])[1] || ''
    const source = chunk.match(/source: '(h3|manual|derived)'/)
    return { key, source: (source?.[1] as TrackingColumnSource | undefined) ?? null }
  })
}

describe('字段来源三分法：分组逻辑', () => {
  it('三类的标题与颜色齐全', () => {
    expect(TRACKING_COLUMN_SOURCE_ORDER).toEqual(['h3', 'manual', 'derived'])
    expect(TRACKING_COLUMN_SOURCE_META.h3.label).toBe('氚云同步 · 只读')
    expect(TRACKING_COLUMN_SOURCE_META.manual.label).toBe('跟单登记 · 人工')
    expect(TRACKING_COLUMN_SOURCE_META.derived.label).toBe('系统计算 · 只读')
    expect(TRACKING_COLUMN_SOURCE_META.h3.tagType).toBe('info')
    expect(TRACKING_COLUMN_SOURCE_META.manual.tagType).toBe('success')
    expect(TRACKING_COLUMN_SOURCE_META.derived.tagType).toBe('warning')
  })

  it('groupBySource：按固定顺序分组、组内保持原序、空组不出现', () => {
    const columns = [
      { key: 'a', source: 'manual' as const },
      { key: 'b', source: 'h3' as const },
      { key: 'c', source: 'manual' as const },
      { key: 'd', source: 'derived' as const }
    ]
    const groups = groupBySource(columns)
    expect(groups.map(group => group.source)).toEqual(['h3', 'manual', 'derived'])
    expect(groups[0].columns.map(column => column.key)).toEqual(['b'])
    expect(groups[1].columns.map(column => column.key)).toEqual(['a', 'c'])
    expect(groups[0].meta.label).toBe('氚云同步 · 只读')
  })

  it('groupBySource：全空输入不出空组', () => {
    expect(groupBySource([])).toEqual([])
  })
})

describe('字段来源三分法：页面契约（直读源码）', () => {
  it('EXPORT_COLUMNS 的每一列都声明了 source，无一遗漏', () => {
    const entries = exportColumnEntries()
    expect(entries.length).toBeGreaterThan(30)
    const missing = entries.filter(entry => !entry.source)
    expect(missing).toEqual([])
  })

  it('三类都有列（分组弹窗不会凭空少一整段）', () => {
    const entries = exportColumnEntries()
    for (const source of TRACKING_COLUMN_SOURCE_ORDER) {
      expect(entries.some(entry => entry.source === source)).toBe(true)
    }
  })

  it('LQ 点名的几列各归各类：氚云只读/人工登记/系统计算', () => {
    const sourceOf = (key: string) => exportColumnEntries().find(entry => entry.key === key)?.source
    // 氚云同步（只读）：任务号、产品、申请时间、要求完成、氚云完工日期
    for (const key of ['orderNo', 'product', 'applicationDate', 'dueDate', 'actualFinishDate']) {
      expect(sourceOf(key), key).toBe('h3')
    }
    // 跟单登记（人工）：车间预计完成（延期至）、延期原因、跟单备注、各节点完成日期
    for (const key of ['expectedFinishDate', 'delayReason', 'remark', 'stages']) {
      expect(sourceOf(key), key).toBe('manual')
    }
    // 系统计算（只读）：工期、倒计时、交付结果、逾期天数、采购入库
    for (const key of ['requestedLeadDays', 'countdown', 'delivery', 'deliveryOverdueDays', 'inboundDate']) {
      expect(sourceOf(key), key).toBe('derived')
    }
  })

  it('节点展开列（stage:*）也是跟单登记；弹窗模板按分组渲染', () => {
    expect(exportSource).toMatch(/expandStageColumns[\s\S]{0,400}source: 'manual' as const/)
    expect(viewSource).toContain('v-for="group in EXPORT_COLUMN_GROUPS"')
    // 分组随导出格式变（网页格式不列出 Excel 专属列）⇒ 10-15 起是 computed，仍然只吃 EXPORT_COLUMNS 一份列定义
    expect(exportSource).toContain('const EXPORT_COLUMN_GROUPS = computed(() => groupBySource(visibleExportColumns.value))')
    expect(exportSource).toContain('EXPORT_COLUMNS.filter(column => !column.excelOnly)')
  })
})
