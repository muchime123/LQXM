/** 仅识别氚云生产任务单备注中的明确加急要求；否定句不产生加急标记。 */
export function isSourceUrgent(remark?: string | null): boolean {
  if (!remark) return false
  const text = remark.replace(/\s+/g, '')
  if (!text.includes('加急')) return false
  return !/(?:不|无须|无需|不用|不需要|取消|非)加急/.test(text)
}
