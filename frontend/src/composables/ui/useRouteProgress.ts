import { reactive } from 'vue'

/**
 * 路由级顶部进度条
 *
 * 行为契约：
 * - 仅 path 变化时启动（query/hash 变化不触发，由调用方在 beforeEach 判断）
 * - 进度从 0 缓慢推进到 80%，路由完成后跳到 100% 再淡出
 * - 失败时变色为 --color-danger 并 1s 后淡出
 * - prefers-reduced-motion: reduce 时跳过过渡，仅做显隐
 *
 * 使用：
 *   router.beforeEach((to, from) => {
 *     if (to.path !== from.path) startProgress()
 *   })
 *   router.afterEach(() => doneProgress())
 *   router.onError(() => failProgress())
 */
interface RouteProgressState {
  visible: boolean
  failed: boolean
  width: number
}

const state = reactive<RouteProgressState>({
  visible: false,
  failed: false,
  width: 0
})

let tickTimer: ReturnType<typeof setInterval> | null = null
let resetTimer: ReturnType<typeof setTimeout> | null = null

function clearTimers(): void {
  if (tickTimer !== null) {
    clearInterval(tickTimer)
    tickTimer = null
  }
  if (resetTimer !== null) {
    clearTimeout(resetTimer)
    resetTimer = null
  }
}

/** 启动进度条：从 0 开始，缓慢推进到 80% 上限 */
function startProgress(): void {
  clearTimers()
  state.visible = true
  state.failed = false
  state.width = 0

  const prefersReducedMotion =
    typeof window !== 'undefined' &&
    window.matchMedia?.('(prefers-reduced-motion: reduce)').matches

  if (prefersReducedMotion) {
    // 减少动画：直接固定在 80%，不逐帧推进
    state.width = 80
    return
  }

  // 用 --duration-moderate（0.3s）档位推进，单步 120ms 涨 4%~6%
  // 整体接近 moderate 时长内推到 80%
  tickTimer = setInterval(() => {
    if (state.width < 80) {
      // 越接近 80 推进越慢
      const remaining = 80 - state.width
      const step = Math.max(1, Math.round(remaining * 0.1))
      state.width = Math.min(80, state.width + step)
    } else if (tickTimer !== null) {
      clearInterval(tickTimer)
      tickTimer = null
    }
  }, 120)
}

/** 路由完成：跳到 100% 后淡出 */
function doneProgress(): void {
  if (!state.visible) return
  clearTimers()
  state.failed = false
  state.width = 100

  resetTimer = setTimeout(() => {
    state.visible = false
    // 淡出后再重置宽度，避免再次出现时从 100 跳变
    resetTimer = setTimeout(() => {
      state.width = 0
    }, 200)
  }, 200)
}

/** 路由失败：变红并 1s 后淡出 */
function failProgress(): void {
  if (!state.visible) return
  clearTimers()
  state.failed = true
  state.width = 100

  resetTimer = setTimeout(() => {
    state.visible = false
    resetTimer = setTimeout(() => {
      state.width = 0
      state.failed = false
    }, 200)
  }, 1000)
}

export const routeProgressState = state
export { startProgress, doneProgress, failProgress }
