import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import {
  HTML_COLUMN_WIDTH,
  HTML_STAGE_COLUMN_WIDTH,
  htmlColumnWidth
} from './trackingHtmlWidths'

/**
 * 网页导出默认列宽策略的契约测试。
 *
 * 宽度表按**列 key** 写死，而列 key 的唯一来源是 `useTrackingExport` 里的 `EXPORT_COLUMNS`。
 * 两边漂移不会报错，只会静默退回「按内容估宽」——列突然变宽且没人知道为什么。
 * 所以这里直读该模块源码抓 key，把漂移钉在构建期（与 trackingListColumns.test.ts 同一手法）。
 * 2026-10 起从「直读页面源码」改为读模块：导出域搬出页面后，页面已不再是列定义的宿主。
 */
const here = dirname(fileURLToPath(import.meta.url))
const viewSource = readFileSync(
  resolve(here, '../../composables/tracking/useTrackingExport.ts'), 'utf8')

/** 模块 `EXPORT_COLUMNS` 的列 key（截块再抓，避免误抓文件里其它同类字面量）。 */
function exportColumnKeys(): string[] {
  const block = viewSource.split('const EXPORT_COLUMNS: ExportColumn[] = [')[1]
  if (block === undefined) throw new Error('未找到 EXPORT_COLUMNS 定义')
  return [...block.split('\n]')[0].matchAll(/key: '([A-Za-z0-9_]+)'/g)].map(match => match[1])
}

describe('网页导出默认列宽：列 key 契约', () => {
  it('映射里的每个 key 都是 EXPORT_COLUMNS 里真实存在的列', () => {
    const keys = exportColumnKeys()
    expect(keys.length).toBeGreaterThan(20)
    for (const key of Object.keys(HTML_COLUMN_WIDTH)) expect(keys).toContain(key)
  })

  it('LQ 指定的五类列各有默认宽：两个日期列 60、两个带副行的列 90、跟单节点展开列 60', () => {
    expect(HTML_COLUMN_WIDTH.sourceCreatedAt).toBe(60)   // 生产任务单创建时间
    expect(HTML_COLUMN_WIDTH.applicationDate).toBe(60)   // 生产工单申请时间
    expect(HTML_COLUMN_WIDTH.dueDate).toBe(90)           // 要求完成 / 要求工期
    expect(HTML_COLUMN_WIDTH.expectedFinishDate).toBe(90) // 车间预计完成 / 倒计时
    expect(HTML_STAGE_COLUMN_WIDTH).toBe(60)             // 跟单节点（各节点完成日期）
  })
})

describe('网页导出默认列宽：单列取值', () => {
  it('跟单节点展开列（stage:*）走统一宽度', () => {
    expect(htmlColumnWidth('stage:备料')).toBe(60)
    expect(htmlColumnWidth('stage:氧化·喷特氟龙')).toBe(60)
  })

  it('未列出的列不设默认宽（返回 null，由渲染器按内容估算）', () => {
    for (const key of ['remark', 'product', 'customer', 'specification', 'stages']) {
      expect(htmlColumnWidth(key)).toBeNull()
    }
  })
})
