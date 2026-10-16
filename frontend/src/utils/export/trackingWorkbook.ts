/**
 * 生产跟单导出：把「表头 + 二维单元格值」组装成 xlsx，**每个车间一个子表**。
 *
 * 这里只负责**「值 → 文件」**这一段；「有哪些列、每个值显示成什么」由调用方决定 ——
 * 页面上的显示函数就是唯一口径，导出直接复用它们，绝不另写一份文案映射
 * （否则「逾期待记录原因」这类文案迟早两处不一致）。
 */
import * as XLSX from 'xlsx'

export type CellValue = string | number | null | undefined

/** 一个子表 = 一个车间：sheet 名（≤31 字符，Excel 硬限制）+ 表头 + 行。 */
export interface TrackingWorkbookSheet { name: string; headers: string[]; body: CellValue[][] }

/** 列宽估算：中日韩全角字符按 2 计，最短 8、最长 40，避免长描述列把表格撑爆。 */
function displayWidth(value: CellValue): number {
  const text = value == null ? '' : String(value)
  let width = 0
  for (const ch of text) width += /[\u2E80-\u9FFF\uFF00-\uFFEF\u3000-\u303F]/.test(ch) ? 2 : 1
  return Math.min(width, 40)
}

function columnWidth(header: string, body: CellValue[][], index: number): number {
  const widths = body.map(line => displayWidth(line[index]))
  return Math.min(40, Math.max(8, displayWidth(header) + 2, ...widths))
}

/** 生成工作表（纯函数，供单测直接断言单元格内容与列宽）。 */
export function trackingSheet(headers: string[], body: CellValue[][]): XLSX.WorkSheet {
  if (!headers.length) throw new Error('请至少选择一列再导出')
  // null/undefined 一律写空串：否则 SheetJS 会写成 0 或 "null"
  const sheet = XLSX.utils.aoa_to_sheet([headers, ...body.map(line => line.map(cell => cell ?? ''))])
  sheet['!cols'] = headers.map((header, index) => ({ wch: columnWidth(header, body, index) }))
  return sheet
}

/**
 * 子表名合法化：Excel 禁止 \ / ? * [ ] :，且名字最长 31、不得重复。
 * 重名（或超长截断后撞名）追加 (2)/(3) 兜底，保证 book_append_sheet 不抛错。
 */
function uniqueSheetName(raw: string, used: Set<string>): string {
  const base = (raw.replace(/[\\/?*[\]:]/g, ' ').trim() || '生产跟单').slice(0, 31)
  if (!used.has(base)) { used.add(base); return base }
  for (let i = 2; ; i += 1) {
    const suffix = `(${i})`
    const candidate = base.slice(0, 31 - suffix.length) + suffix
    if (!used.has(candidate)) { used.add(candidate); return candidate }
  }
}

/**
 * 组装为 xlsx Blob：**每个车间一个子表**（由调用方按车间分组传入），
 * 交给 `downloadBlob` 触发下载。子表顺序＝传入顺序。
 */
export function buildTrackingWorkbook(sheets: TrackingWorkbookSheet[], sheetName = '生产跟单'): Blob {
  if (!sheets.length) throw new Error('没有可导出的数据')
  const book = XLSX.utils.book_new()
  const used = new Set<string>()
  for (const sheet of sheets) {
    XLSX.utils.book_append_sheet(book, trackingSheet(sheet.headers, sheet.body), uniqueSheetName(sheet.name || sheetName, used))
  }
  const out = XLSX.write(book, { bookType: 'xlsx', type: 'array' }) as ArrayBuffer
  return new Blob([out], { type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet' })
}

/** 导出文件名：生产跟单_YYYYMMDD_HHmm.xlsx（本地时间）。 */
export function trackingExportFileName(now: Date = new Date()): string {
  const pad = (value: number) => String(value).padStart(2, '0')
  return `生产跟单_${now.getFullYear()}${pad(now.getMonth() + 1)}${pad(now.getDate())}`
    + `_${pad(now.getHours())}${pad(now.getMinutes())}.xlsx`
}

/**
 * 「导出说明」子表：把「哪些列能改完导回、怎么填」写在文件里。
 *
 * 放在子表最前面，人一打开就能看到；导回解析按「有没有任务号列」认数据子表，因此这张说明表会被自动忽略。
 * 这里列出的可回写列必须与后端导回服务接受的列一致（`ProductionTrackingRoundTripService` 的表头常量）。
 */
export const ROUND_TRIP_GUIDE_SHEET = '导出说明'

export function trackingExportGuideSheet(): TrackingWorkbookSheet {
  return {
    name: ROUND_TRIP_GUIDE_SHEET,
    headers: ['项', '说明'],
    body: [
      ['文件用途', '导出后可直接在 Excel 里修改，改完用跟单页面的「导入跟单表（回写）」导回系统。'],
      ['可回写的列', '跟单备注、车间预计完成、延期原因、各节点完成日期（备料/模具生产/氧化·喷特氟龙/组装/入库等）。'],
      ['只读列', '其余列改了不会写回：任务号、产品、规格、车间、客户、业务员、数量及各类日期、交付结果 —— 它们是氚云源数据或系统派生值。'],
      ['字段来源', '列分三类：氚云同步（只读，直接来自氚云生产任务单）；跟单登记（人工，跟单员维护、可回写）；系统计算（只读，由其它数据推算，如工期/倒计时/交付结果/采购入库）。网页版导出件的表头颜色与此一致：蓝＝氚云同步、绿＝跟单登记、橙＝系统计算。'],
      ['匹配方式', '按「任务号」精确匹配生产任务，请不要改动任务号；同一任务号只能出现一行。'],
      ['空白规则', '空白单元格＝不修改（保持系统里的原值）；漏填不会清空已登记的内容。'],
      ['车间预计完成怎么填', '填的日期与系统里「车间给出的预计完成」不同 ⇒ 记为延期至；填回车间原日期 ⇒ 取消延期。'],
      ['节点完成日期怎么填', '填日期即表示该节点已完成；本单不走的按需工序填「无」。清空日期不会把节点改回未完成（空白＝不修改）。'],
      ['日期写法', '请写成 2026-10-08，或用 Excel 日期格式；无法识别的写法会让该行在预检里标为「无法识别」并整行跳过。']
    ]
  }
}
