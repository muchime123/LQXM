/**
 * 文本处理工具
 * 从 DocumentView.vue 等文件抽取的重复文本处理逻辑
 */

/**
 * 截断字符串，超出最大长度显示省略号
 * @param str - 原字符串
 * @param max - 最大长度
 * @returns string
 */
export function truncate(str: string | null | undefined, max: number): string {
  if (!str) return ''
  return str.length > max ? str.slice(0, max) + '...' : str
}

interface TagListOptions {
  max?: number
}

/**
 * 将逗号/空格/分号分隔的标签字符串解析为数组
 * 支持英文半角逗号、中文全角逗号、分号、顿号、空格、换行等多种分隔符
 * @param tags - 分隔的标签字符串
 * @returns string[]
 */
export function tagList(tags: string | null | undefined, options: TagListOptions = {}): string[] {
  const { max = 0 } = options
  if (!tags) return []
  const normalized = String(tags)
    .replace(/[,，;；、\s]+/g, ',')
    .replace(/\s*,\s*/g, ',')
    .replace(/^,+|,+$/g, '')
  if (!normalized) return []
  const result = normalized
    .split(',')
    .map(t => t.trim())
    .filter(Boolean)
  return max > 0 ? result.slice(0, max) : result
}

/**
 * 字符串脱敏处理（如手机号、身份证）
 * @param str
 * @param start - 显示前几位
 * @param end - 显示后几位
 */
export function maskString(str: string | null | undefined, start = 3, end = 4): string {
  if (!str || str.length <= start + end) return str || ''
  const mask = '*'.repeat(str.length - start - end)
  return str.slice(0, start) + mask + str.slice(-end)
}

/**
 * 提取字符串中的纯数字（去除非数字字符）
 * 用于生成实际可用的专利号：如 'ZL202521392375.7' → '2025213923757'
 * @param str
 * @returns string
 */
export function digitsOnly(str: string | null | undefined): string {
  if (!str) return ''
  return String(str).replace(/\D/g, '')
}
