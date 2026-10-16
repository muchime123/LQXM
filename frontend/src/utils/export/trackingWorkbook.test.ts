import { describe, expect, it } from 'vitest'
import * as XLSX from 'xlsx'
import { buildTrackingWorkbook, ROUND_TRIP_GUIDE_SHEET, trackingExportFileName, trackingExportGuideSheet, trackingSheet, type TrackingWorkbookSheet } from './trackingWorkbook'

/** 取回工作表的二维数组（含表头行），用于断言单元格内容。 */
function aoa(sheet: XLSX.WorkSheet): unknown[][] {
  return XLSX.utils.sheet_to_json(sheet, { header: 1, blankrows: true, defval: '' }) as unknown[][]
}

describe('生产跟单导出：xlsx 组装', () => {
  it('表头与每行单元格按选择顺序落位', () => {
    const sheet = trackingSheet(['任务号', '跟单状态'], [['SCGD20260006047', '待跟单'], ['SCGD20260006048', '跟单中']])
    expect(aoa(sheet)).toEqual([
      ['任务号', '跟单状态'],
      ['SCGD20260006047', '待跟单'],
      ['SCGD20260006048', '跟单中']
    ])
  })

  it('空值写空单元格，不写 0 / "null"（否则数字列会被污染）', () => {
    const sheet = trackingSheet(['A', 'B'], [[null, undefined], [0, '']])
    expect(aoa(sheet)).toEqual([['A', 'B'], ['', ''], [0, '']])
  })

  it('数字保持数值类型，不会被转成文本（便于 Excel 直接求平均/计数）', () => {
    const sheet = trackingSheet(['数量'], [[3]])
    const cell = sheet['A2'] as XLSX.CellObject
    expect(cell.t).toBe('n')
    expect(cell.v).toBe(3)
  })

  it('列宽按中文全角估算，并夹在 8~40 之间', () => {
    const sheet = trackingSheet(['任务号', '生产交付结果'], [['SCGD20260006047', '逾期完工 12 天']])
    const cols = sheet['!cols'] as XLSX.ColInfo[]
    expect(cols).toHaveLength(2)
    // 表头「任务号」=6，但数据 SCGD20260006047 =15 个半角 ⇒ 取数据宽度 15（表头不会比数据宽）
    expect(cols[0].wch).toBe(15)
    // 「生产交付结果」6 个汉字 = 12，+2 = 14；数据「逾期完工 12 天」= 8+1+2+1+2 = 14 ⇒ 取 14
    expect(cols[1].wch).toBe(14)
  })

  it('一列都不选时直接报错，不生成空表头文件', () => {
    expect(() => trackingSheet([], [])).toThrow('请至少选择一列再导出')
  })

  it('每个车间一个子表，顺序与内容都按传入落位', async () => {
    const sheets: TrackingWorkbookSheet[] = [
      { name: '封口机车间', headers: ['任务号', '备料'], body: [['SCGD20260006047', '2026-09-20']] },
      { name: '全自动气调机车间', headers: ['任务号', '画图'], body: [['SCGD20260005101', '2026-09-12']] }
    ]
    const blob = buildTrackingWorkbook(sheets)
    const book = XLSX.read(await blob.arrayBuffer(), { type: 'array' })
    expect(book.SheetNames).toEqual(['封口机车间', '全自动气调机车间'])
    expect(aoa(book.Sheets['封口机车间'])[1]).toEqual(['SCGD20260006047', '2026-09-20'])
    expect(aoa(book.Sheets['全自动气调机车间'])[1]).toEqual(['SCGD20260005101', '2026-09-12'])
  })

  it('子表名做 Excel 合法化：非法字符替换、超 31 截断、重名追加序号', async () => {
    const longName = '很长的车间名称'.repeat(6) // 42 字 > 31
    const blob = buildTrackingWorkbook([
      { name: 'A/B\\C?D*[E]:F', headers: ['任务号'], body: [['x1']] },
      { name: longName, headers: ['任务号'], body: [['x2']] },
      { name: longName, headers: ['任务号'], body: [['x3']] }
    ])
    const book = XLSX.read(await blob.arrayBuffer(), { type: 'array' })
    expect(book.SheetNames[0]).toBe('A B C D  E  F')
    expect(book.SheetNames[1]).toBe(longName.slice(0, 31))
    expect(book.SheetNames[2]).toBe(longName.slice(0, 28) + '(2)')
    expect(new Set(book.SheetNames).size).toBe(3)
  })

  it('一个子表都没有时直接报错（空结果由调用方落成「只有表头的单表」）', () => {
    expect(() => buildTrackingWorkbook([])).toThrow('没有可导出的数据')
  })

  it('生成的 xlsx 能被重新读回（证明不是坏文件）', async () => {
    const blob = buildTrackingWorkbook([{ name: '生产跟单', headers: ['任务号'], body: [['SCGD20260006047']] }])
    expect(blob.size).toBeGreaterThan(0)
    const book = XLSX.read(await blob.arrayBuffer(), { type: 'array' })
    expect(book.SheetNames).toEqual(['生产跟单'])
    expect(aoa(book.Sheets['生产跟单'])[1]).toEqual(['SCGD20260006047'])
  })

  it('文件名带本地时间戳且为 .xlsx', () => {
    expect(trackingExportFileName(new Date(2026, 9, 10, 16, 32))).toBe('生产跟单_20261010_1632.xlsx')
  })

  it('「导出说明」子表写清可回写列与规则（导回的契约：这些列名就是后端接受的表头）', async () => {
    const guide = trackingExportGuideSheet()
    expect(guide.name).toBe(ROUND_TRIP_GUIDE_SHEET)
    const text = guide.body.map(line => line.join('：')).join('\n')
    // 四类可回写列必须逐个写进说明，否则用户不知道能在表里改什么
    for (const label of ['跟单备注', '车间预计完成', '延期原因', '各节点完成日期']) expect(text).toContain(label)
    expect(text).toContain('任务号')
    expect(text).toContain('空白单元格＝不修改')
    // 说明子表放进工作簿后仍能被读回，且排在数据子表之前
    const blob = buildTrackingWorkbook([guide, { name: '封口机车间', headers: ['任务号'], body: [['SCGD1']] }])
    const book = XLSX.read(await blob.arrayBuffer(), { type: 'array' })
    expect(book.SheetNames).toEqual([ROUND_TRIP_GUIDE_SHEET, '封口机车间'])
    expect(aoa(book.Sheets[ROUND_TRIP_GUIDE_SHEET])[1]).toEqual(['文件用途', '导出后可直接在 Excel 里修改，改完用跟单页面的「导入跟单表（回写）」导回系统。'])
  })
})
