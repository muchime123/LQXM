/**
 * 判断横向轨道是否真的溢出（内容多到需要滚动）。
 *
 * 用途：证书 / 专利这类「内容少」的轮播区，只有内容超过一屏才显示
 * 「上一组 / 下一组」按钮；否则一张证书配两个按钮，会让人以为还有内容没看到。
 *
 * 用 scrollWidth 与 clientWidth 实测而不是按条数估算：
 * 桌面 4 列、平板 2 列、手机 82% 一列，同一份数据在不同视口下结果不同。
 */
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'

/**
 * @param {import('vue').Ref<HTMLElement|null>} trackRef 横向轨道元素
 */
export function useScrollOverflow(trackRef) {
  const overflowing = ref(false)
  let observer = null

  function measure() {
    const el = trackRef.value
    /* 留 4px 容差，避免小数像素导致的误判 */
    overflowing.value = !!el && el.scrollWidth - el.clientWidth > 4
  }

  function observe(el) {
    observer?.disconnect()
    observer = null
    if (!el || typeof ResizeObserver === 'undefined') return
    observer = new ResizeObserver(() => measure())
    observer.observe(el)
  }

  /* 区块可能因后台开关延后渲染，轨道元素出现 / 替换时重新绑定并测量 */
  watch(trackRef, (el) => {
    observe(el)
    measure()
  }, { flush: 'post' })

  onMounted(() => {
    observe(trackRef.value)
    measure()
    window.addEventListener('resize', measure)
  })

  onBeforeUnmount(() => {
    observer?.disconnect()
    observer = null
    window.removeEventListener('resize', measure)
  })

  return { overflowing, measure }
}
