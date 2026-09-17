/**
 * v-reveal 自定义指令：替代原 IntersectionObserver 逻辑。
 * 元素进入视口时添加 .visible 类触发渐入动画。
 *
 * 用法：<div class="reveal" v-reveal>...</div>
 *
 * 性能：触发后立即 unobserve，避免重复回调。
 */
let observer = null

function ensureObserver() {
  if (observer || typeof IntersectionObserver === 'undefined') return observer
  observer = new IntersectionObserver(
    (entries) => {
      entries.forEach((entry) => {
        if (entry.isIntersecting) {
          entry.target.classList.add('visible')
          observer.unobserve(entry.target)
        }
      })
    },
    { threshold: 0.12 }
  )
  return observer
}

export const vReveal = {
  mounted(el) {
    const obs = ensureObserver()
    if (obs) {
      obs.observe(el)
    } else {
      // 不支持 IntersectionObserver 的环境直接显示
      el.classList.add('visible')
    }
  },
}
