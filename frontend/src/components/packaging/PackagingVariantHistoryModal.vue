<template>
  <LqDialog
    :model-value="visible"
    :title="variantName || '规格历史记录'"
    :eyebrow="variantName ? '规格历史记录' : ''"
    size="md"
    mode="detail"
    :show-footer="true"
    :show-confirm="false"
    cancel-text="关闭"
    @cancel="$emit('close')"
    @close="$emit('close')"
  >
    <div class="history-list">
      <div v-if="loading" class="history-empty">加载中...</div>
      <div v-else-if="!history || history.length === 0" class="history-empty">暂无字段变更记录。</div>
      <article
v-for="item in history"
v-else
:key="item.id"
class="history-row">
        <div class="history-row__head">
          <strong>{{ item.fieldLabel || item.fieldKey }}</strong>
          <span class="history-row__type"
            >{{ historyTypeLabel(item.changeType) }} · {{ historySourceLabel(item.changeSource) }}</span
          >
        </div>
        <dl class="history-row__changes">
          <div>
            <dt>原值</dt>
            <dd>{{ item.oldValue || '-' }}</dd>
          </div>
          <div>
            <dt>新值</dt>
            <dd>{{ item.newValue || '-' }}</dd>
          </div>
        </dl>
        <footer class="history-row__meta">
          <span>{{ formatHistoryTime(item.createdAt) }}</span>
          <span v-if="item.sourceFile">{{ item.sourceFile }}</span>
          <span v-if="item.changeReason">{{ item.changeReason }}</span>
        </footer>
      </article>
    </div>
  </LqDialog>
</template>

<script setup lang="ts">
import LqDialog from '@/components/common/LqDialog.vue'
import type { PackagingVariantHistoryView } from '@/api/packaging'

interface Props {
  visible: boolean
  variantName?: string
  history?: PackagingVariantHistoryView[]
  loading?: boolean
}

withDefaults(defineProps<Props>(), {
  variantName: '',
  history: () => [],
  loading: false
})

defineEmits<{
  close: []
}>()

function historyTypeLabel(type?: string) {
  if (type === 'create') return '创建'
  if (type === 'merge') return '合并'
  if (type === 'import') return '导入'
  return '更新'
}

function historySourceLabel(source?: string) {
  if (source === 'import') return '导入'
  if (source === 'auto') return '自动'
  return '手动'
}

function formatHistoryTime(value?: string) {
  if (!value) return '-'
  return value.replace('T', ' ').slice(0, 19)
}
</script>

<style scoped>
.history-list {
  display: flex;
  flex-direction: column;
  gap: var(--space-control-gap);
  padding: var(--space-md) var(--space-lg);
}

.history-empty {
  padding: var(--space-xl) var(--space-md);
  color: var(--color-text-muted);
  text-align: center;
  font-size: var(--fs-sm);
}

.history-row {
  padding: var(--space-control-gap);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  background: var(--color-surface-alt);
  transition: border-color var(--transition-fast);
}

.history-row:hover {
  border-color: var(--color-primary-border);
}

.history-row__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-sm);
  margin-bottom: var(--space-control-gap-tight);
}

.history-row__head strong {
  font-size: var(--fs-sm);
  font-weight: var(--fw-semibold);
  color: var(--color-text-strong);
}

.history-row__type {
  font-size: var(--fs-xs);
  color: var(--color-text-muted);
}

.history-row__changes {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: var(--space-sm);
  margin: 0 0 var(--space-control-gap-tight) 0;
}

.history-row__changes dt {
  font-size: var(--fs-xs);
  color: var(--color-text-muted);
  margin-bottom: 2px;
}

.history-row__changes dd {
  margin: 0;
  font-size: var(--fs-sm);
  color: var(--color-text-primary);
  word-break: break-word;
}

.history-row__meta {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-sm);
  font-size: var(--fs-xs);
  color: var(--color-text-muted);
}
</style>
