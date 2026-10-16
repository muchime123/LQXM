/**
 * 生产跟单**网页版**导出的默认列宽策略（LQ 10-15 定）。
 *
 * 为什么要单独一份按 key 写死的宽度：网页版日期列只显示到 MM-DD、跟单节点列只显示完成日期，
 * 内容长度全都一样；让它们跟着内容各自估宽，会得出十几个宽窄不一的列，横竖都浪费纸。
 * 统一收窄后，一张横向 A4 能多落下几列。
 *
 * ⚠️ 这里的 key 是 `ProductionTrackingView.vue` 里 `EXPORT_COLUMNS` 的 key（业务列身份的唯一来源）。
 * 写错 key / 列被改名都不会报错，只会静默退回「按内容估宽」——所以由契约测试
 * （trackingHtmlWidths.test.ts 直读页面源码）钉死：每个 key 必须真实存在。
 *
 * 只作用于网页版：xlsx 用 Excel 自己的列宽策略（见 trackingWorkbook.ts）。
 * 也只是**默认值**：导出件里的列宽数字框与表头拖拽仍可覆盖，双击把手 / 「恢复默认」回到这里给的值。
 */

/** 按列 key 指定的默认列宽（px）。未列出的列不设默认，仍按内容估算。 */
export const HTML_COLUMN_WIDTH: Record<string, number> = {
  sourceCreatedAt: 60,     // 生产任务单创建时间：网页版只到 MM-DD，60px 放得下
  applicationDate: 60,     // 生产工单申请时间：同上
  dueDate: 90,             // 要求完成 / 要求工期：主值下面还挂着「要求工期」副行
  expectedFinishDate: 90   // 车间预计完成 / 倒计时：主值下挂着「生产倒计时」副行
}

/** 跟单节点（各车间按 Policy 展开的「节点完成日期」列）统一 60px。 */
export const HTML_STAGE_COLUMN_WIDTH = 60

/** 节点列的前缀（`EXPORT_COLUMNS` 展开后的 key 形如 `stage:备料`）。 */
export const HTML_STAGE_KEY_PREFIX = 'stage:'

/**
 * 单列的网页默认列宽：节点列走统一值，其余查表，未命中返回 `null`（＝调用方按内容估算）。
 * `key` 为 `EXPORT_COLUMNS`/展开列的 key。
 */
export function htmlColumnWidth(key: string): number | null {
  if (key.startsWith(HTML_STAGE_KEY_PREFIX)) return HTML_STAGE_COLUMN_WIDTH
  return HTML_COLUMN_WIDTH[key] ?? null
}
