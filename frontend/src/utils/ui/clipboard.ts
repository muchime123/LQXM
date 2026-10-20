import { notify } from '../../composables/ui/useToast'

/**
 * 降级复制：非安全上下文（http + 局域网 IP）下 navigator.clipboard 不存在，
 * 用隐藏 textarea + document.execCommand('copy') 兜底
 */
function legacyCopy(text: string): boolean {
  const textarea = document.createElement('textarea')
  textarea.value = text
  textarea.setAttribute('readonly', '')
  textarea.style.position = 'fixed'
  textarea.style.opacity = '0'
  textarea.style.pointerEvents = 'none'
  document.body.appendChild(textarea)
  textarea.select()
  let ok = false
  try {
    ok = document.execCommand('copy')
  } catch {
    ok = false
  } finally {
    document.body.removeChild(textarea)
  }
  return ok
}

/**
 * 复制文本到剪贴板并提示结果
 * @param text - 要复制的文本
 * @param label - 成功提示文案前缀，如 '专利号' → '专利号已复制到剪贴板'
 * @returns 是否复制成功
 */
export async function copyText(text: string, label = '内容'): Promise<boolean> {
  try {
    if (navigator.clipboard?.writeText) {
      await navigator.clipboard.writeText(text)
    } else if (!legacyCopy(text)) {
      throw new Error('legacy copy failed')
    }
    notify(`${label}已复制到剪贴板`, 'success')
    return true
  } catch {
    notify('复制失败，请手动复制', 'error')
    return false
  }
}
