import { notify } from '../../composables/ui/useToast'

/**
 * 复制文本到剪贴板并提示结果
 * @param text - 要复制的文本
 * @param label - 成功提示文案前缀，如 '专利号' → '专利号已复制到剪贴板'
 * @returns 是否复制成功
 */
export async function copyText(text: string, label = '内容'): Promise<boolean> {
  try {
    await navigator.clipboard.writeText(text)
    notify(`${label}已复制到剪贴板`, 'success')
    return true
  } catch {
    notify('复制失败，请手动复制', 'error')
    return false
  }
}
