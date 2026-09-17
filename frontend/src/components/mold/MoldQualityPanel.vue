<template>
  <div class="quality-panel">
    <div class="panel-title">
      <h4>数据质量</h4>
      <span class="panel-hint">字段完整性、必填、与追溯信息。</span>
    </div>

    <!-- 完整度评分 -->
    <div class="quality-score">
      <div class="score-circle" :style="{ background: scoreBg(quality.score) }">
        <div class="score-inner">
          <div class="score-num">{{ quality.score }}%</div>
          <div class="score-label">完整度</div>
        </div>
      </div>
      <div class="score-summary">
        <div class="score-meta">
          <strong>{{ quality.completedCount }}</strong> 个必填/关键字段已填，
          <strong>{{ quality.missingCount }}</strong> 个缺失。
        </div>
        <div v-if="quality.unverified" class="score-warn">
          <span class="warn-dot"></span>
          <span>该模具仍为<strong>「待核实」</strong>状态，建议确认以下问题后修改：</span>
        </div>
      </div>
    </div>

    <!-- 问题列表 -->
    <div class="quality-items">
      <h5>质量检查项</h5>
      <ul>
        <li v-for="(item, i) in quality.items" :key="i" :class="['q-item', `q-item--${item.level}`]">
          <span class="q-indicator"></span>
          <span class="q-label">{{ item.label }}</span>
          <span v-if="item.hint" class="q-hint">{{ item.hint }}</span>
        </li>
      </ul>
    </div>

    <!-- 变更历史 -->
    <div class="history-section">
      <h5>变更历史</h5>
      <el-timeline v-if="history.length" class="history-timeline">
        <el-timeline-item
          v-for="h in history"
          :key="h.id"
          :type="h.changeType === 'status_change' ? 'warning' : 'primary'"
          :hollow="h.changeType !== 'status_change'"
          :timestamp="formatTime(h.createdAt)"
          placement="top"
        >
          <div class="history-item">
            <div class="history-head">
              <span class="history-field">{{ h.fieldLabel || h.fieldKey }}</span>
              <el-tag v-if="h.changeType === 'status_change'" size="small" type="warning" effect="plain">状态变更</el-tag>
              <el-tag v-else size="small" type="info" effect="plain">字段更新</el-tag>
            </div>
            <div class="history-diff">
              <span class="diff-old">{{ formatValue(h.fieldKey, h.oldValue) }}</span>
              <span class="diff-arrow">→</span>
              <span class="diff-new">{{ formatValue(h.fieldKey, h.newValue) }}</span>
            </div>
            <div v-if="h.changeReason" class="history-reason">原因：{{ h.changeReason }}</div>
          </div>
        </el-timeline-item>
      </el-timeline>
      <div v-else-if="loadingHistory" class="history-empty">加载中…</div>
      <div v-else class="history-empty">暂无变更记录</div>
    </div>

    <!-- 追溯信息 -->
    <SourceTrace v-if="detail.mold.sourceFile || detail.mold.importBatchId"
      :source="{
        sourceFile: detail.mold.sourceFile,
        sourceSheet: detail.mold.sourceSheet,
        sourceRowNumber: detail.mold.sourceRowNumber,
        importBatchId: detail.mold.importBatchId,
        createdAt: detail.mold.createdAt,
        updatedAt: detail.mold.updatedAt,
      }" />
    <LqDescriptions v-else title="审计时间" :cols="2" size="small" border>
      <el-descriptions-item label="创建时间">{{ detail.mold.createdAt || '—' }}</el-descriptions-item>
      <el-descriptions-item label="更新时间">{{ detail.mold.updatedAt || '—' }}</el-descriptions-item>
    </LqDescriptions>
  </div>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { fetchMoldHistory, type MoldHistoryEntry, type PackagingMoldDetail } from '@/api'
import SourceTrace from '@/components/common/SourceTrace.vue'
import LqDescriptions from '@/components/common/LqDescriptions.vue'

const props = defineProps<{ detail: PackagingMoldDetail }>()

const history = ref<MoldHistoryEntry[]>([])
const loadingHistory = ref(false)

const STATUS_LABELS: Record<string, string> = {
  in_use: '在用',
  stopped: '停用',
  repair: '维修中',
  unverified: '待核实'
}

function formatValue(fieldKey: string, v?: string): string {
  if (v == null || v === '') return '—'
  if (fieldKey === 'status') return STATUS_LABELS[v] || v
  return v
}

function formatTime(t?: string): string {
  if (!t) return '—'
  return t.replace('T', ' ').slice(0, 16)
}

async function loadHistory(id?: number) {
  if (!id) {
    history.value = []
    return
  }
  loadingHistory.value = true
  try {
    history.value = await fetchMoldHistory(id)
  } catch {
    history.value = []
  } finally {
    loadingHistory.value = false
  }
}

watch(() => props.detail.mold.id, id => loadHistory(id), { immediate: true })

interface QualityItem { level: 'ok' | 'warn' | 'missing' | 'info'; label: string; hint?: string }

const quality = computed(() => {
  const m = props.detail.mold
  const items: QualityItem[] = []

  // 归属
  const hasModel = Boolean(m.modelId && m.modelName)
  items.push(hasModel
    ? { level: 'ok', label: '归属包材系列已关联' }
    : { level: 'missing', label: '未归属包材系列', hint: '需绑定 PackagingModel' })

  // 模号
  const hasCode = Boolean(m.moldCode)
  const numericOnly = hasCode && /^\d+$/.test(m.moldCode.trim())
  items.push(hasCode && !numericOnly
    ? { level: 'ok', label: `模号 ${m.moldCode} 格式正常` }
    : numericOnly
      ? { level: 'warn', label: '纯数字模号', hint: '旧编号体系，建议确认对应 M/P 主编号' }
      : { level: 'missing', label: '缺少模号' })

  // 出模数 + 盒型
  const hasCavity = m.cavityCount !== undefined && m.cavityCount !== null
  const hasType = Boolean(m.moldTypeCode)
  items.push({
    level: hasCavity && hasType ? 'ok' : hasCavity || hasType ? 'warn' : 'missing',
    label: `出模数 ${hasCavity ? '✓' : '✗'} · 盒型 ${hasType ? `✓ (${m.moldTypeCode})` : '✗'}`,
    hint: !hasCavity && !hasType ? '模号未解析或未手工填写' : undefined
  })

  // 刻字
  const engravingFilled = Boolean(m.bottomEngraving) || Boolean(m.engravingType)
  const engravingClassified = Boolean(m.engravingType)
  items.push({
    level: engravingFilled && engravingClassified ? 'ok' : engravingFilled ? 'warn' : 'info',
    label: engravingFilled ? `刻字：${m.engravingType || '未分类'}${m.bottomEngraving ? `（${m.bottomEngraving.slice(0, 24)}${m.bottomEngraving.length > 24 ? '…' : ''}）` : ''}` : '未填写杯底刻字',
    hint: !engravingClassified && engravingFilled ? '建议标记为通用/专版/功能/空白' : undefined
  })

  // 规格
  const variantCount = props.detail.variantLinks.length
  items.push({
    level: variantCount > 0 ? 'ok' : 'warn',
    label: variantCount ? `已关联 ${variantCount} 个规格（高度/容量）` : '未关联任何规格',
    hint: variantCount ? undefined : '建议在「成品规格」页签绑定至少 1 个 PackagingVariant'
  })

  // 配件
  const accessoryCount = props.detail.accessories.length
  const hasRawText = Boolean(m.rawAccessoriesText)
  items.push({
    level: accessoryCount > 0 ? 'ok' : hasRawText ? 'warn' : 'info',
    label: accessoryCount
      ? `${accessoryCount} 个配件已结构化`
      : hasRawText
        ? `配件仍为原始文本未拆分：${truncate(m.rawAccessoriesText || '', 40)}`
        : '未填写配件（无配件的情况可忽略）',
    hint: hasRawText && !accessoryCount ? '建议切到「模具与配件」页签按 + 号拆分' : undefined
  })

  // 状态
  const statusOk = m.status !== 'unverified'
  items.push(statusOk
    ? { level: 'ok', label: `状态：${m.status || '—'}` }
    : { level: 'warn', label: '状态：待核实', hint: '确认模号、高度、配件后改为「在用」' })

  // 评分：ok=2，warn=1，missing=0，info=1
  const weights: Record<QualityItem['level'], number> = { ok: 2, warn: 1, missing: 0, info: 1 }
  const total = items.length * 2
  const got = items.reduce((acc, it) => acc + (weights[it.level] ?? 0), 0)
  const score = Math.round((got / total) * 100)
  const completedCount = items.filter(i => i.level === 'ok').length
  const missingCount = items.filter(i => i.level === 'missing').length
  const unverified = m.status === 'unverified'

  return { items, score, completedCount, missingCount, unverified }
})

function truncate(s: string, n: number) {
  return s.length > n ? s.slice(0, n) + '…' : s
}

function scoreBg(score: number): string {
  if (score >= 85) return 'conic-gradient(from 0deg, #10b981, #059669 360deg)'
  if (score >= 60) return 'conic-gradient(from 0deg, #f59e0b, #d97706 360deg)'
  return 'conic-gradient(from 0deg, #ef4444, #dc2626 360deg)'
}
</script>

<style scoped>
.quality-panel {
  padding-top: var(--space-xs, 4px);
}

.panel-title {
  display: flex;
  align-items: baseline;
  gap: var(--space-s, 8px);
  margin-bottom: var(--space-section-gap, 24px);
}

.panel-title h4 {
  font-size: 14px;
  font-weight: 600;
  color: var(--color-text-primary, #111827);
  margin: 0;
}

.panel-hint {
  font-size: 12px;
  color: var(--color-text-tertiary, #9ca3af);
}

.quality-score {
  display: flex;
  gap: var(--space-section-gap, 24px);
  padding: var(--space-m, 16px);
  background: var(--color-bg-input, #f9fafb);
  border-radius: var(--radius-card, 10px);
  margin-bottom: var(--space-section-gap, 24px);
  align-items: center;
}

.score-circle {
  width: 96px;
  height: 96px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  padding: 3px;
}

.score-circle .score-inner {
  width: 100%;
  height: 100%;
  background: var(--color-bg-surface, #fff);
  border-radius: 50%;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
}

.score-num {
  font-size: 22px;
  font-weight: 700;
  color: var(--color-text-primary, #111827);
  line-height: 1.1;
}

.score-label {
  font-size: 11px;
  color: var(--color-text-tertiary, #9ca3af);
  margin-top: 2px;
}

.score-summary {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: var(--space-s, 8px);
  min-width: 0;
}

.score-meta {
  font-size: 13px;
  color: var(--color-text-secondary, #374151);
}

.score-meta strong {
  color: var(--color-text-primary, #111827);
}

.score-warn {
  display: flex;
  align-items: flex-start;
  gap: var(--space-xs, 4px);
  font-size: 13px;
  color: var(--color-warning-600, #d97706);
  background: color-mix(in srgb, var(--color-warning-100) 70%, transparent);
  padding: var(--space-s, 8px) var(--space-m, 12px);
  border-radius: var(--radius-card, 8px);
}

.warn-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: currentColor;
  margin-top: 6px;
  flex-shrink: 0;
}

.quality-items h5 {
  font-size: 13px;
  font-weight: 600;
  color: var(--color-text-secondary, #374151);
  margin: 0 0 var(--space-s, 8px);
}

.quality-items ul {
  list-style: none;
  padding: 0;
  margin: 0;
  display: flex;
  flex-direction: column;
  gap: var(--space-xs, 6px);
  margin-bottom: var(--space-section-gap, 24px);
}

.q-item {
  display: flex;
  align-items: center;
  gap: var(--space-s, 8px);
  padding: var(--space-s, 8px) var(--space-m, 12px);
  border-radius: var(--radius-card, 8px);
  font-size: 13px;
  line-height: 1.5;
  background: var(--color-bg-surface, #fff);
  border: 1px solid var(--color-border-subtle, #e5e7eb);
}

.q-indicator {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  flex-shrink: 0;
  background: var(--color-text-tertiary, #9ca3af);
}

.q-item--ok {
  border-color: color-mix(in srgb, var(--color-success-300) 60%, transparent);
  background: color-mix(in srgb, var(--color-success-50) 70%, transparent);
}

.q-item--ok .q-indicator {
  background: var(--color-success-600, #059669);
}

.q-item--warn {
  border-color: color-mix(in srgb, var(--color-warning-300) 60%, transparent);
  background: color-mix(in srgb, var(--color-warning-50) 70%, transparent);
}

.q-item--warn .q-indicator {
  background: var(--color-warning-600, #d97706);
}

.q-item--missing {
  border-color: color-mix(in srgb, var(--color-danger-300) 60%, transparent);
  background: color-mix(in srgb, var(--color-danger-50) 70%, transparent);
}

.q-item--missing .q-indicator {
  background: var(--color-danger-600, #dc2626);
}

.q-item--info {
  border-color: var(--color-border-subtle, #e5e7eb);
}

.q-item--info .q-indicator {
  background: var(--color-text-tertiary, #9ca3af);
}

.q-label {
  color: var(--color-text-primary, #111827);
  font-weight: 500;
}

.q-hint {
  margin-left: auto;
  font-size: 12px;
  color: var(--color-text-tertiary, #9ca3af);
}

.history-section {
  margin-bottom: var(--space-section-gap, 24px);
}

.history-section h5 {
  font-size: 13px;
  font-weight: 600;
  color: var(--color-text-secondary, #374151);
  margin: 0 0 var(--space-s, 8px);
}

.history-timeline {
  padding-left: var(--space-s, 8px);
}

.history-timeline :deep(.el-timeline-item__timestamp) {
  font-size: 12px;
  color: var(--color-text-tertiary, #9ca3af);
}

.history-item {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: var(--space-s, 8px) var(--space-m, 12px);
  background: var(--color-bg-surface, #fff);
  border: 1px solid var(--color-border-subtle, #e5e7eb);
  border-radius: var(--radius-card, 8px);
}

.history-head {
  display: flex;
  align-items: center;
  gap: var(--space-s, 8px);
}

.history-field {
  font-size: 13px;
  font-weight: 600;
  color: var(--color-text-primary, #111827);
}

.history-diff {
  display: flex;
  align-items: center;
  gap: var(--space-s, 8px);
  font-size: 13px;
  font-family: var(--font-mono, ui-monospace, SFMono-Regular, Menlo, monospace);
}

.diff-old {
  color: var(--color-text-tertiary, #9ca3af);
  text-decoration: line-through;
  text-decoration-color: color-mix(in srgb, var(--color-danger-400) 70%, transparent);
}

.diff-arrow {
  color: var(--color-text-tertiary, #9ca3af);
  font-family: inherit;
}

.diff-new {
  color: var(--color-primary-600, #2563eb);
  font-weight: 600;
}

.history-reason {
  font-size: 12px;
  color: var(--color-text-secondary, #374151);
  background: color-mix(in srgb, var(--color-warning-50) 60%, transparent);
  padding: 2px var(--space-s, 8px);
  border-radius: var(--radius-card, 6px);
}

.history-empty {
  font-size: 13px;
  color: var(--color-text-tertiary, #9ca3af);
  padding: var(--space-m, 16px);
  text-align: center;
  background: var(--color-bg-input, #f9fafb);
  border-radius: var(--radius-card, 8px);
}
</style>
