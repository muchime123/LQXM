/**
 * 生产跟单「今天」的**唯一来源**。
 *
 * 【为什么要单独一个模块】此前页面里并存两种取法：
 *   · `countdownToday` 这个 60 秒心跳 ref（驱动「生产倒计时」列跨零点自动刷新）；
 *   · 各处直接写的 `new Date().toLocaleDateString('sv-SE')`（时间核实判据、默认完成日期、看板兜底统计）。
 * 两者格式相同、取值时点不同 —— 页面长挂或跨零点时，同一行数据在「倒计时」和「时间核实」两处
 * 会按**不同的今天**判定，且不会有任何报错（典型的静默漂移）。
 *
 * 现在统一走 `todayKey()`：
 *   · 需要跨零点自动刷新的地方（倒计时列）继续由页面的心跳 ref 持有，初始值与每次跳动都取自本模块；
 *   · 其余一次性取值（默认完成日期、时间核实判据、看板兜底）直接调用 `todayKey()`；
 *   · 判据函数（见 `./rules`）一律**显式接收** today 参数，需要跟随心跳时由页面传入。
 *
 * 口径＝**本地时区**的 `YYYY-MM-DD`（等价于 `toLocaleDateString('sv-SE')` 的短格式），
 * 与后端日期列、`trackingDateGuard.daysBetween` 的入参格式一致。
 */
const pad = (value: number) => String(value).padStart(2, '0')

/** 本地时区 `YYYY-MM-DD`。 */
export function todayKey(date: Date = new Date()) {
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`
}

/** `date` 之后第 `days` 天的 `YYYY-MM-DD`（可为负）。 */
export function todayKeyAfter(days: number, date: Date = new Date()) {
  const shifted = new Date(date)
  shifted.setDate(shifted.getDate() + days)
  return todayKey(shifted)
}
