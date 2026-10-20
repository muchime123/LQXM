import { describe, expect, it } from 'vitest'
import { formatDate, isoWeek, weekRange, weekStart } from './week'

/** 构造用 new Date(y, m, d) 本地时间，与被测函数同一套构造方式，避免时区干扰。 */
const at = (year: number, month: number, day: number) => new Date(year, month - 1, day)

describe('tracking/week · 自然周口径', () => {
  it('周一起算：周日属于上一周，不能被算成下一周的开头', () => {
    // 2026-10-18 是周日 → 它属于 10.12（周一）那一周
    expect(formatDate(weekStart(at(2026, 10, 18)))).toBe('2026-10-12')
    expect(weekRange(at(2026, 10, 18))).toEqual({ from: '2026-10-12', to: '2026-10-18' })
    // 周一当天取自身
    expect(weekRange(at(2026, 10, 12))).toEqual({ from: '2026-10-12', to: '2026-10-18' })
  })

  it('上一周 = 本周一再往前 7 天，跨月也不断周', () => {
    expect(weekRange(at(2026, 10, 12), -1)).toEqual({ from: '2026-10-05', to: '2026-10-11' })
    // 10 月 1 日（周四）所在周的上一周跨到 9 月
    expect(weekRange(at(2026, 10, 1), -1)).toEqual({ from: '2026-09-21', to: '2026-09-27' })
  })

  it('周期是闭区间：起止都算在内，共 7 天', () => {
    const { from, to } = weekRange(at(2026, 10, 15))
    const span = (Date.parse(to) - Date.parse(from)) / 86_400_000 + 1
    expect(span).toBe(7)
  })

  it('跨年周按 ISO：1 月初的几天可能仍属上一年第 53 周', () => {
    // 2027-01-01 是周五 → 仍属 2026 年第 53 周
    expect(isoWeek(at(2027, 1, 1))).toEqual({ year: 2026, week: 53 })
    expect(isoWeek(at(2026, 10, 15))).toEqual({ year: 2026, week: 42 })
    expect(isoWeek(at(2026, 1, 1))).toEqual({ year: 2026, week: 1 })
  })

  it('日期格式补零，后端才能按 YYYY-MM-DD 解析', () => {
    expect(formatDate(at(2026, 1, 5))).toBe('2026-01-05')
    expect(formatDate(at(2026, 12, 31))).toBe('2026-12-31')
  })
})
