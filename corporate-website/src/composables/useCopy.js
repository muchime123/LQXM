/**
 * 全局复制工具（单例）。
 * - copyText(text) 复制到剪贴板（含降级方案），成功后触发 toast 反馈
 * - toast 为全局响应式字符串，由 App.vue 挂载展示
 * 网站策略：电话 / 邮箱一律不唤起拨号或邮件客户端，统一复制。
 */
import { ref } from 'vue'

const toast = ref('')
let timer = null

function showToast(msg) {
  toast.value = msg
  clearTimeout(timer)
  timer = setTimeout(() => (toast.value = ''), 2200)
}

function legacyCopy(text) {
  const ta = document.createElement('textarea')
  ta.value = text
  ta.style.position = 'fixed'
  ta.style.opacity = '0'
  ta.style.pointerEvents = 'none'
  document.body.appendChild(ta)
  ta.select()
  try {
    document.execCommand('copy')
  } finally {
    document.body.removeChild(ta)
  }
}

async function copyText(text) {
  const value = String(text ?? '').trim()
  if (!value) return false
  try {
    if (navigator.clipboard && window.isSecureContext) {
      await navigator.clipboard.writeText(value)
    } else {
      legacyCopy(value)
    }
    showToast(`已复制：${value}`)
    return true
  } catch (err) {
    console.warn('Copy failed:', err)
    showToast('复制失败，请手动复制')
    return false
  }
}

export function useCopy() {
  return { copyText, toast }
}
