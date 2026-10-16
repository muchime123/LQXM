/**
 * 生产跟单**字段来源**三分法（LQ 10-15 定）：导出列按「数据从哪来、能不能改」分成三类，
 * 解决「氚云同步值和人工登记值混在一起，看着乱」的问题。三处共用：
 * - 导出弹窗的列选择器：按类分组展示（每类一个彩色标签 + 一句说明）；
 * - 网页版导出件：表头按类着色 + 图例（纸上也能看出哪列是谁说了算）；
 * - 契约测试：钉死每个导出列都必须声明来源，漏了直接红。
 *
 * Excel（xlsx）不支持样式，靠「导出说明」子表的文字说明这三类。
 */

/** 字段来源：`h3`＝氚云同步只读；`manual`＝跟单登记（人工）；`derived`＝系统计算只读。 */
export type TrackingColumnSource = 'h3' | 'manual' | 'derived'

export interface TrackingColumnSourceMeta {
  source: TrackingColumnSource
  /** 分组标题（导出弹窗的标签、导出件的图例文字共用）。 */
  label: string
  /** 一句话说明这类字段是什么、能不能改。 */
  hint: string
  /** 导出弹窗里 el-tag 的颜色。 */
  tagType: 'info' | 'success' | 'warning'
}

/** 三类的标题/说明/颜色。文案改动会同时出现在弹窗与导出件图例，改前想两头。 */
export const TRACKING_COLUMN_SOURCE_META: Record<TrackingColumnSource, TrackingColumnSourceMeta> = {
  h3: {
    source: 'h3',
    label: '氚云同步 · 只读',
    hint: '直接来自氚云生产任务单/进度表，系统里不可更改',
    tagType: 'info'
  },
  manual: {
    source: 'manual',
    label: '跟单登记 · 人工',
    hint: '跟单员在系统里登记维护，Excel 导出后也可改完导回',
    tagType: 'success'
  },
  derived: {
    source: 'derived',
    label: '系统计算 · 只读',
    hint: '由其它数据推算（工期、倒计时、交付结果、采购入库等），不可直接改',
    tagType: 'warning'
  }
}

/** 分组展示顺序：先「谁给的」再「谁填的」最后「算出来的」。 */
export const TRACKING_COLUMN_SOURCE_ORDER: TrackingColumnSource[] = ['h3', 'manual', 'derived']

export interface TrackingColumnGroup<T> {
  source: TrackingColumnSource
  meta: TrackingColumnSourceMeta
  columns: T[]
}

/**
 * 按来源分组，组内保持传入顺序，空组不出现。
 * 页面用它把 `EXPORT_COLUMNS` 切成弹窗里的三段 —— 列定义仍是唯一来源，这里不复制列清单。
 */
export function groupBySource<T extends { source: TrackingColumnSource }>(columns: readonly T[]): TrackingColumnGroup<T>[] {
  return TRACKING_COLUMN_SOURCE_ORDER
    .map(source => ({ source, meta: TRACKING_COLUMN_SOURCE_META[source], columns: columns.filter(column => column.source === source) }))
    .filter(group => group.columns.length > 0)
}
