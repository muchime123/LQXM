import { computed, ref } from 'vue'

export type PeriodPreset = 'month' | 'quarter' | 'year' | 'custom'

const pad = (value: number) => String(value).padStart(2, '0')
export const dateText = (date: Date) => `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`
const queryDate = (value: unknown) =>
  typeof value === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(value) ? value : null

const today = new Date()
const todayText = dateText(today)

function anchorMonth(year: number) {
  return year === today.getFullYear() ? today.getMonth() : year < today.getFullYear() ? 11 : 0
}

/** 自然月、季、年的完整区间。 */
export function fullRange(kind: 'month' | 'quarter' | 'year', year: number): [string, string] {
  if (kind === 'year') return [`${year}-01-01`, `${year}-12-31`]
  const month = anchorMonth(year)
  const first = kind === 'quarter' ? Math.floor(month / 3) * 3 : month
  return [dateText(new Date(year, first, 1)), dateText(new Date(year, first + (kind === 'quarter' ? 3 : 1), 0))]
}

/** 当前账年截至今日；历史账年展示完整期间，未来账年先从首日开始。 */
export function periodRange(kind: 'month' | 'quarter' | 'year', year: number): [string, string] {
  const [from, to] = fullRange(kind, year)
  const capped = to > todayText ? todayText : to
  return [from, capped < from ? from : capped]
}

export function matchPresetOf(from: string, to: string, year: number): PeriodPreset {
  for (const kind of ['month', 'quarter', 'year'] as const) {
    for (const range of [periodRange(kind, year), fullRange(kind, year)]) {
      if (range[0] === from && range[1] === to) return kind
    }
  }
  return 'custom'
}

/** 三个工作视图共享同一账年和期间；可用年度由服务端有效期初决定。 */
export function useCashflowPeriod(options: {
  availableYears: number[]
  initialStart?: unknown
  initialEnd?: unknown
  onChange: () => void
}) {
  const years = [...new Set(options.availableYears)].sort((a, b) => b - a)
  const requestedStart = queryDate(options.initialStart)
  const requestedEnd = queryDate(options.initialEnd)
  const requestedYear = requestedStart ? Number(requestedStart.slice(0, 4)) : null
  const initialYear = requestedYear != null && years.includes(requestedYear)
    ? requestedYear : years.includes(today.getFullYear()) ? today.getFullYear() : years[0] ?? 2026
  const year = ref(initialYear)
  const initial = periodRange('month', initialYear)
  const validQuery = requestedStart && requestedEnd && requestedStart <= requestedEnd
    && requestedStart.slice(0, 4) === String(initialYear) && requestedEnd.slice(0, 4) === String(initialYear)
  const start = ref(validQuery ? requestedStart : initial[0])
  const end = ref(validQuery ? requestedEnd : initial[1])
  const period = ref<[string, string]>([start.value, end.value])
  const preset = ref<PeriodPreset>(matchPresetOf(start.value, end.value, initialYear))
  const presetLabel = computed(() => ({ month: '本月', quarter: '本季', year: '本年', custom: '自定义' })[preset.value])
  const isPresetCappedToday = computed(() => preset.value !== 'custom' && end.value === todayText)

  function apply(range: [string, string], explicit?: PeriodPreset) {
    start.value = range[0]
    end.value = range[1]
    period.value = range
    preset.value = explicit ?? matchPresetOf(range[0], range[1], year.value)
    options.onChange()
  }

  function setPeriod(kind: 'month' | 'quarter' | 'year') {
    apply(periodRange(kind, year.value), kind)
  }

  function setYear(value: number) {
    if (!years.includes(value) || value === year.value) return
    year.value = value
    apply(periodRange('year', value), 'year')
  }

  function onPeriodPicked() {
    if (!period.value || period.value.length !== 2) return
    const [from, to] = period.value
    if (from > to || from.slice(0, 4) !== String(year.value) || to.slice(0, 4) !== String(year.value)) return
    apply([from, to])
  }

  const disableOtherYears = (date: Date) => date.getFullYear() !== year.value

  return { year, start, end, period, preset, presetLabel, isPresetCappedToday,
    setPeriod, setYear, onPeriodPicked, disableOtherYears }
}
