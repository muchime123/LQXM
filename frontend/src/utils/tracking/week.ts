/**
 * 自然周计算 —— 周复盘看板周期的唯一来源。
 *
 * 【为什么单独一个文件】周期算错不会报错，只会让看板统计的是「另一个七天」：
 * 周一/周日算成周日/周六、跨月跨年时算漏一天，看板上就是「本周 65 单」对不上手工周报。
 * 这里把三种易错点一次性钉住：① 周日 getDay()=0 要归到上一周；② 必须用本地时间构造，
 * 走 UTC 会在东八区把周一算成周日；③ 后端 fromDate/toDate 是**闭区间**，周日=周一+6 天。
 */
const DAY = 86_400_000

/** YYYY-MM-DD；与后端要求的日期格式一致，不引依赖、不受时区影响。 */
export function formatDate(date: Date): string {
  const month = `${date.getMonth() + 1}`.padStart(2, '0')
  const day = `${date.getDate()}`.padStart(2, '0')
  return `${date.getFullYear()}-${month}-${day}`
}

/**
 * 含 date 的那一周的**周一**；offsetWeeks=-1 为上一周。
 * 一律用 new Date(y,m,d) 本地时间构造，不做 UTC 换算。
 */
export function weekStart(date: Date, offsetWeeks = 0): Date {
  const start = new Date(date.getFullYear(), date.getMonth(), date.getDate())
  start.setDate(start.getDate() - mondayIndex(start) + offsetWeeks * 7)
  return start
}

/** 自然周区间 [周一, 周日]，闭区间 —— 与后端 fromDate/toDate 的闭区间口径一致。 */
export function weekRange(date: Date, offsetWeeks = 0): { from: string; to: string } {
  const from = weekStart(date, offsetWeeks)
  const to = new Date(from.getFullYear(), from.getMonth(), from.getDate() + 6)
  return { from: formatDate(from), to: formatDate(to) }
}

/** ISO 周次：含该日期的周四决定它属于哪一年，跨年周（1 月初 / 12 月底）靠这一条才不会算错。 */
export function isoWeek(date: Date): { year: number; week: number } {
  const thursday = new Date(date.getFullYear(), date.getMonth(), date.getDate())
  thursday.setDate(thursday.getDate() - mondayIndex(thursday) + 3)
  const firstThursday = new Date(thursday.getFullYear(), 0, 4)
  firstThursday.setDate(firstThursday.getDate() - mondayIndex(firstThursday) + 3)
  return {
    year: thursday.getFullYear(),
    week: 1 + Math.round((thursday.getTime() - firstThursday.getTime()) / (7 * DAY))
  }
}

/** 周一=0 … 周日=6（getDay() 把周日当 0，直接用会把周日算进下一周的周一）。 */
function mondayIndex(date: Date): number {
  return (date.getDay() + 6) % 7
}
