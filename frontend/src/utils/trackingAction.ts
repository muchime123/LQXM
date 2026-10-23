import type { TrackingActionCode } from '@/api/productionTracking'

/**
 * 【跟单待办 · 唯一映射源】后端只回一个代码（ProductionTrackingService.TRACKING_ACTION），
 * 文案、配色与「为什么轮到我」的说明全部在这里，列表列 / 下拉 / 详情抽屉三处共用，
 * 禁止各写一套 —— 否则同一个代码在三处会显示成三种说法。
 *
 * 新增的排产与交期提醒由后端实时计算，筛选按各自条件匹配，不依赖主提醒代码。
 */
export const TRACKING_ACTION_CODES: TrackingActionCode[] = [
  'FINISHED_PENDING', 'INBOUND_PENDING', 'SCHEDULED_PENDING', 'INBOUND_UNLOGGED',
  'SCHEDULE_DUE', 'DELIVERY_PRESSURE', 'STALE_FOLLOWUP'
]

/** ⚠️ 必须与后端 ProductionTrackingService.STALE_FOLLOWUP_DAYS 一致，否则提示与筛选口径会打架。 */
export const STALE_FOLLOWUP_DAYS = 7

export const TRACKING_ACTION_LABELS: Record<TrackingActionCode, string> = {
  FINISHED_PENDING: '已完工待跟单',
  INBOUND_PENDING: '已入库待跟单',
  SCHEDULED_PENDING: '已排产待跟单',
  INBOUND_UNLOGGED: '已入库待登记',
  SCHEDULE_DUE: '待排产提醒',
  DELIVERY_PRESSURE: '交期风险待核实',
  STALE_FOLLOWUP: '7 天未更新'
}

/** 提醒说得出依据的最少字段（TrackingOrder 直接满足）。 */
export interface TrackingActionFacts {
  trackingAction?: TrackingActionCode | null
  /** 氚云报工完工日期。 */
  actualFinishDate?: string | null
  /** 采购首次入库日期。 */
  inboundDate?: string | null
  /** 氚云排产时间。 */
  scheduleTime?: string | null
  /** 最近一次跟单写入时间。 */
  lastFollowupAt?: string | null
  latestStartDate?: string | null
}

export function trackingActionLabel(code?: TrackingActionCode | null) {
  return code ? TRACKING_ACTION_LABELS[code] : ''
}

/** 提醒配色：事实已发生却一点没记的用最重的红，已在跟单只是漏一步的用蓝。 */
export function trackingActionTone(code: TrackingActionCode): 'danger' | 'warning' | 'info' {
  return code === 'FINISHED_PENDING' ? 'danger'
    : ['INBOUND_PENDING', 'SCHEDULE_DUE', 'DELIVERY_PRESSURE'].includes(code) ? 'warning' : 'info'
}

/**
 * 「为什么现在轮到我」——把命中的那个事实连同日期一起说出来，不打开详情也能判断该干什么。
 * 日期缺失时显式写「（日期缺失）」而不是留空，避免看起来像排版漏了。
 */
export function trackingActionHint(row: TrackingActionFacts) {
  const day = (value?: string | null) => (value ? value.slice(0, 10) : '（日期缺失）')
  switch (row.trackingAction) {
    case 'FINISHED_PENDING': return `氚云已于 ${day(row.actualFinishDate)} 报工完工，但跟单尚未登记任何节点；请尽快补录进度。`
    case 'INBOUND_PENDING': return `采购已于 ${day(row.inboundDate)} 入库，但跟单尚未开始；请尽快登记进度。`
    case 'SCHEDULED_PENDING': return `车间已于 ${day(row.scheduleTime)} 排产，但跟单尚未开始；请尽快登记进度。`
    case 'INBOUND_UNLOGGED': return `采购已于 ${day(row.inboundDate)} 入库，但「入库」节点还没登记完成日期；请补登节点。`
    case 'STALE_FOLLOWUP': return `最近一次跟进 ${row.lastFollowupAt ? row.lastFollowupAt.slice(0, 10) : '（无记录）'}，已超过 ${STALE_FOLLOWUP_DAYS} 天没有更新且尚未跟完；请确认现场进度。`
    case 'SCHEDULE_DUE': return `尚未排产，建议最晚 ${day(row.latestStartDate)} 排产；请联系车间确认开工。`
    case 'DELIVERY_PRESSURE': return '按当前节点参考进度，可能赶不上车间交期；请核实关键节点能否提前完成。'
    default: return ''
  }
}
