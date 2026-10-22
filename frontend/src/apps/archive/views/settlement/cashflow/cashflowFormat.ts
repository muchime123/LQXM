import { downloadBlob } from '@/utils/ui/downloadBlob'

/** 金额展示：来源结存允许 4 位小数（Excel 折算列），整数部分按中文千分位。 */
export function amount(value: number | string | null | undefined): string {
  return value == null || value === ''
    ? '—'
    : Number(value).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 4 })
}

/** 期初/期末专用：null 一律写「期初缺失」，不兜底成 0。 */
export function openingAmount(value: number | null | undefined): string {
  return value == null ? '期初缺失' : amount(value)
}

export function closingTagType(status: string): 'ok' | 'neutral' {
  return status === 'OK' ? 'ok' : 'neutral'
}

/** 核对清单条目类别。前端只认 issueCode，不认账户名。 */
const ISSUE_LABELS: Record<string, string> = {
  MISSING_OPENING: '缺期初',
  MISSING_DATE: '缺日期',
  BOTH_SIDES: '收支双填',
  MISSING_FX: '外币缺汇率',
  SOURCE_BALANCE_BREAK: '逐笔结存断点'
}

export function issueLabel(code: string): string {
  return ISSUE_LABELS[code] ?? code
}

export function issueTagType(code: string): 'warn' | 'neutral' | 'danger' {
  if (code === 'SOURCE_BALANCE_BREAK') return 'warn'
  if (code === 'MISSING_OPENING') return 'neutral'
  return 'danger'
}

/** CSV 导出（BOM + 公式注入防护）。 */
export function downloadCsv(rows: unknown[][], filename: string): void {
  const escape = (value: unknown) => {
    const text = String(value ?? '')
    const safe = typeof value === 'string' && /^[=+\-@]/.test(text) ? `'${text}` : text
    return `"${safe.replace(/"/g, '""')}"`
  }
  const csv = rows.map(row => row.map(escape).join(',')).join('\r\n')
  downloadBlob(new Blob([`\uFEFF${csv}`], { type: 'text/csv;charset=utf-8' }), filename)
}
