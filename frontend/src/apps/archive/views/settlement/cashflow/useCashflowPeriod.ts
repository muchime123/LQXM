import { computed, ref } from 'vue'

/** P1 仍限制在 2026 年（P2 随年度结转放开）。 */
export const CASHFLOW_PERIOD_YEAR = 2026

export type PeriodPreset = 'month' | 'quarter' | 'year' | 'custom'

const pad = (value: number) => String(value).padStart(2, '0')
export const dateText = (date: Date) => `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`

const queryDate = (value: unknown) =>
  typeof value === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(value) ? value : null

const today = new Date()
const todayText = dateText(today)
const anchorMonth = today.getFullYear() === CASHFLOW_PERIOD_YEAR ? today.getMonth() : 11

/** 自然月 / 自然季 / 自然年 的完整区间（季度一律自然季度）。 */
export function fullRange(kind: 'month' | 'quarter' | 'year'): [string, string] {
  if (kind === 'year') return [`${CASHFLOW_PERIOD_YEAR}-01-01`, `${CASHFLOW_PERIOD_YEAR}-12-31`]
  const months = kind === 'quarter' ? 3 : 1
  const firstMonth = kind === 'quarter' ? Math.floor(anchorMonth / 3) * 3 : anchorMonth
  return [
    dateText(new Date(CASHFLOW_PERIOD_YEAR, firstMonth, 1)),
    dateText(new Date(CASHFLOW_PERIOD_YEAR, firstMonth + months, 0))
  ]
}

/**
 * 快捷期间的默认查询区间：**截至今天**，不把未来的月末/季末/年末算进查询范围。
 * 自定义期间仍允许未来日期，由页面显式展示实际查询范围。
 */
export function periodRange(kind: 'month' | 'quarter' | 'year'): [string, string] {
  const [from, to] = fullRange(kind)
  const capped = to > todayText ? todayText : to
  return [from, capped < from ? from : capped]
}

export function matchPresetOf(from: string, to: string): PeriodPreset {
  for (const kind of ['month', 'quarter', 'year'] as const) {
    for (const range of [periodRange(kind), fullRange(kind)]) {
      if (range[0] === from && range[1] === to) return kind
    }
  }
  return 'custom'
}

/**
 * 期间与快捷期间的唯一状态源。三个视图共用同一份 start/end，切换视图不需要重新选条件。
 *
 * <p>注意：同一天可能同时是「本月」和「本季」的起点（如 10-01 ~ 10-22），
 * 因此按钮高亮由**最后点击的预设**决定，不从区间反推，避免两个按钮抢高亮。
 */
export function useCashflowPeriod(options: {
  initialStart?: unknown
  initialEnd?: unknown
  onChange: () => void
}) {
  const initial = periodRange('month')
  const start = ref(queryDate(options.initialStart) ?? initial[0])
  const end = ref(queryDate(options.initialEnd) ?? initial[1])
  const period = ref<[string, string]>([start.value, end.value])
  const preset = ref<PeriodPreset>(matchPresetOf(start.value, end.value))

  const presetLabel = computed(
    () => ({ month: '本月', quarter: '本季', year: '本年', custom: '自定义' })[preset.value]
  )
  const isPresetCappedToday = computed(() => preset.value !== 'custom' && end.value === todayText)

  function apply(range: [string, string], explicit?: PeriodPreset) {
    start.value = range[0]
    end.value = range[1]
    period.value = [range[0], range[1]]
    preset.value = explicit ?? matchPresetOf(range[0], range[1])
    options.onChange()
  }

  function setPeriod(kind: 'month' | 'quarter' | 'year') {
    apply(periodRange(kind), kind)
  }

  function onPeriodPicked() {
    if (!period.value || period.value.length !== 2) return
    apply([period.value[0], period.value[1]])
  }

  const disableOtherYears = (date: Date) => date.getFullYear() !== CASHFLOW_PERIOD_YEAR

  return {
    CASHFLOW_PERIOD_YEAR,
    todayText,
    start,
    end,
    period,
    preset,
    presetLabel,
    isPresetCappedToday,
    setPeriod,
    onPeriodPicked,
    disableOtherYears
  }
}
