/**
 * 生产跟单分析/复盘抽屉的共用格式化 —— 三抽屉（生产分析 / 周复盘 / 节点分析）及其子组件的唯一取数口径。
 *
 * 【为什么单独一个文件】percent / barWidth / statusLabel 曾在四个文件里各写一份：
 * 生产分析抽屉、周复盘抽屉、TrackingWeeklyReasonTable、TrackingReviewHistory。它们逻辑完全一致，
 * 唯一差别是「样本不足时是否也返回 undefined」。若继续各写各的，一处把口径改成「样本不足显示 —」，
 * 其他三处不跟进，看板上就会「同一指标两套显示」。这里一次性钉住。
 */

import type { TrackingAnalyticsOrder } from '@/api/productionTracking'

/**
 * 比率显示：null / undefined = 样本不足（没有该周期的分母时不能当 0% 糊弄），否则保留 1 位小数 + %。
 */
export function percent(value: number | null | undefined): string {
  return value === null || value === undefined ? '样本不足' : `${value.toFixed(1)}%`
}

/** 占比条宽度：total 为 0 时不产生除零，返回 0%。 */
export function barWidth(count: number, total: number): string {
  return `${total ? (count * 100) / total : 0}%`
}

/** 分析/复盘下钻列表的整单状态中文名（与后端 TrackingAnalyticsOrder.status 一一对应）。 */
export function statusLabel(status: TrackingAnalyticsOrder['status']): string {
  return (
    {
      ON_TIME: '按期完工',
      LATE: '逾期完工',
      UNFINISHED: '未完工',
      CANCELLED: '已取消',
      STOPPED: '已停产',
      MISSING_EXPECTED: '待核实交期'
    } as Record<TrackingAnalyticsOrder['status'], string>
  )[status]
}
