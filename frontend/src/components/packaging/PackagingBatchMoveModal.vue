<template>
  <LqDialog
    :model-value="visible"
    title="移动规格到其他系列"
    :subtitle="`已选 ${selectedCount} 个规格，移动后图片和素材会一起归到目标系列。`"
    width="min(620px, 94vw)"
    mode="form"
    :close-on-click-modal="true"
    :loading="loading"
    :confirm-disabled="!targetModelId"
    confirm-text="确认移动"
    @update:model-value="emit('update:visible', false)"
    @confirm="handleConfirm"
  >
    <div class="batch-move-search">
      <input
        v-model.trim="targetModelKeyword"
        type="search"
        placeholder="搜索目标系列名称、材质、容量..."
        @keyup.enter="loadTargetModels"
      />
      <button
type="button"
class="btn-sm"
:disabled="targetModelsLoading"
@click="loadTargetModels">
        {{ targetModelsLoading ? '搜索中...' : '搜索' }}
      </button>
    </div>

    <div class="batch-move-list">
      <button
        v-for="model in targetModels"
        :key="model.id"
        type="button"
        class="batch-move-target"
        :class="{ active: targetModelId === model.id }"
        @click="targetModelId = model.id"
      >
        <span class="target-kind">{{ kindLabel(model.packagingKind) || '包材' }}</span>
        <strong>{{ model.name }}</strong>
        <small>{{ targetModelMeta(model) }}</small>
        <em>{{ model.variantCount }} 规格</em>
      </button>
      <div v-if="!targetModelsLoading && targetModels.length === 0" class="batch-move-empty">
        没有找到可移动的目标系列。
      </div>
    </div>
  </LqDialog>
</template>

<script setup lang="ts">
import { ref, watch } from 'vue'
import LqDialog from '../common/LqDialog.vue'
import { fetchPackagingModels } from '@/api/packaging'
import { kindLabel, shapeLabel, filmFormLabel } from '@/constants/packaging'
import { notify } from '@/composables/ui/useToast'
import type { PackagingModelSummary } from '@/api/packaging'

const props = defineProps<{
  visible: boolean
  selectedCount: number
  currentModelId: number
  loading: boolean
}>()

const emit = defineEmits<{
  (e: 'update:visible', value: boolean): void
  (e: 'confirm', targetModelId: number): void
}>()

const targetModelId = ref<number | null>(null)
const targetModelKeyword = ref('')
const targetModels = ref<PackagingModelSummary[]>([])
const targetModelsLoading = ref(false)

async function loadTargetModels() {
  targetModelsLoading.value = true
  try {
    const res = await fetchPackagingModels({
      keyword: targetModelKeyword.value || undefined,
      page: 0,
      size: 80,
      sort: 'sortOrder,asc'
    })
    const content = Array.isArray(res?.content) ? res.content : []
    targetModels.value = content.filter((model: PackagingModelSummary) => model.id !== props.currentModelId)
  } catch {
    targetModels.value = []
    notify?.('目标系列加载失败', 'error')
  } finally {
    targetModelsLoading.value = false
  }
}

function targetModelMeta(model: PackagingModelSummary) {
  return (
    [
      model.material,
      shapeLabel(model.shapeType),
      model.filmForm ? filmFormLabel(model.filmForm) : '',
      model.capacityMl ? `${model.capacityMl}ml` : ''
    ]
      .filter(Boolean)
      .join(' · ') || '暂无详细信息'
  )
}

function handleConfirm() {
  if (!targetModelId.value) return
  emit('confirm', targetModelId.value)
}

watch(
  () => props.visible,
  async v => {
    if (v) {
      targetModelId.value = null
      targetModelKeyword.value = ''
      await loadTargetModels()
    }
  },
  { immediate: true }
)
</script>

<style scoped>
.batch-move-search {
  display: flex;
  gap: var(--space-sm);
  padding: 12px 16px;
  border-bottom: 1px solid var(--color-border-light);
}

.batch-move-search input {
  flex: 1;
  height: 36px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  padding: 0 12px;
  background: var(--color-surface);
  color: var(--color-text-heading);
  font-size: var(--fs-sm);
}

.batch-move-search input:focus {
  outline: none;
  border-color: var(--color-primary);
  box-shadow: 0 0 0 3px rgba(13, 148, 136, 0.1);
}

.batch-move-list {
  min-height: 0;
  max-height: min(480px, 60vh);
  overflow-y: auto;
  padding: var(--space-control-gap);
  background: var(--color-surface-alt);
}

.batch-move-target {
  display: grid;
  grid-template-columns: auto 1fr auto;
  gap: var(--space-control-gap-tight) var(--space-control-gap);
  align-items: center;
  width: 100%;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
  padding: 10px 12px;
  margin-bottom: var(--space-control-gap);
  background: var(--color-surface);
  color: var(--color-text-heading);
  font: inherit;
  text-align: left;
  cursor: pointer;
}

.batch-move-target:hover {
  border-color: var(--color-primary);
}

.batch-move-target.active {
  border-color: var(--color-primary);
  background: var(--color-primary-bg-light);
}

.batch-move-target .target-kind {
  display: inline-block;
  border-radius: var(--radius-sm);
  padding: 2px 6px;
  background: var(--color-surface-hover);
  color: var(--color-text-muted);
  font-size: var(--fs-xs);
  font-weight: var(--fw-bold);
}

.batch-move-target strong {
  grid-column: 2;
  color: var(--color-text-heading);
  font-size: var(--fs-sm);
}

.batch-move-target small {
  grid-column: 2;
  color: var(--color-text-muted);
  font-size: var(--fs-xs);
}

.batch-move-target em {
  grid-column: 3;
  grid-row: 1 / span 2;
  color: var(--color-text-muted);
  font-size: var(--fs-xs);
  font-style: normal;
}

.batch-move-empty {
  display: grid;
  place-items: center;
  padding: 40px 16px;
  color: var(--color-text-muted);
  font-size: var(--fs-sm);
}

.btn-sm {
  height: 32px;
  border-radius: var(--radius-md);
  padding: 0 14px;
  background: var(--color-surface);
  color: var(--color-text-heading);
  font-size: var(--fs-xs);
  font-weight: var(--fw-bold);
  cursor: pointer;
  border: 1px solid var(--color-border);
}

.btn-sm:disabled {
  cursor: not-allowed;
  opacity: 0.5;
}

.btn-sm.primary {
  background: var(--color-primary);
  color: var(--color-text-on-primary);
  border-color: var(--color-primary);
}
</style>
