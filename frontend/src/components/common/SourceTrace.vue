<template>
  <span class="source-trace" :title="fullText" :aria-label="fullText">
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"
      stroke-linejoin="round" width="12" height="12" aria-hidden="true">
      <path d="M4 4h16v16H4z" />
      <path d="M8 8h8M8 12h8M8 16h4" />
    </svg>
    {{ shortText }}
  </span>
</template>

<script setup lang="ts">
import { computed } from 'vue'

const props = defineProps<{
  /** Excel 导入溯源 */
  sourceFile?: string
  sourceSheet?: string
  sourceRowNumber?: number
  importBatchId?: number | string | null
  /** 系统来源溯源（氚云 / 钉钉等外部事实表） */
  sourceSystem?: string | null
  sourceCreatedAt?: string | null
  sourceCreatedBy?: string | null
  fallback?: string
}>()

const SYSTEM_LABEL: Record<string, string> = {
  H3YUN: '氚云',
  DINGTALK: '钉钉',
  MANUAL: '手工'
}

const systemLabel = computed(() => {
  const s = (props.sourceSystem || '').toUpperCase()
  return SYSTEM_LABEL[s] || (s || props.fallback || '手动维护')
})

const isExcelMode = computed(() => Boolean(props.sourceRowNumber || props.sourceFile))
const isSystemMode = computed(() => !isExcelMode.value && Boolean(props.sourceSystem))

const shortText = computed(() => {
  if (props.sourceRowNumber) return `Excel 第 ${props.sourceRowNumber} 行`
  if (props.sourceFile) return props.sourceFile
  if (isSystemMode.value) {
    const date = props.sourceCreatedAt ? String(props.sourceCreatedAt).slice(0, 10) : ''
    return date ? `${systemLabel.value} · ${date}` : systemLabel.value
  }
  return props.fallback || '手动维护'
})

const fullText = computed(() => {
  if (isExcelMode.value) {
    const parts: string[] = []
    if (props.sourceFile) parts.push(`文件：${props.sourceFile}`)
    if (props.sourceSheet) parts.push(`工作表：${props.sourceSheet}`)
    if (props.sourceRowNumber) parts.push(`Excel 第 ${props.sourceRowNumber} 行`)
    if (props.importBatchId) parts.push(`导入批次 #${props.importBatchId}`)
    return parts.join(' / ')
  }
  if (isSystemMode.value) {
    const parts: string[] = [`来源系统：${systemLabel.value}`]
    if (props.sourceCreatedBy) parts.push(`创建人：${props.sourceCreatedBy}`)
    if (props.sourceCreatedAt) parts.push(`创建时间：${String(props.sourceCreatedAt).replace('T', ' ').slice(0, 19)}`)
    return parts.join(' / ')
  }
  return props.fallback || '手动维护'
})
</script>

<style scoped>
.source-trace {
  display: inline-flex;
  align-items: center;
  gap: var(--space-control-gap-tight);
  max-width: 100%;
  overflow: hidden;
  color: var(--color-text-muted);
  font-size: var(--fs-xxs);
  line-height: 1.35;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.source-trace svg {
  flex: 0 0 auto;
  color: var(--color-text-faint);
}
</style>
