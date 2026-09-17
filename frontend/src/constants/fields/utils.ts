/**
 * 共享工具函数
 */

export function formatValue(value: unknown): string {
  return value === null || value === undefined || String(value).trim() === '' ? '-' : String(value)
}
