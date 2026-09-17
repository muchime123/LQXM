<template>
  <LqDialog
    :model-value="modelValue"
    :title="dialogTitle"
    size="sm"
    mode="form"
    :close-on-click-modal="false"
    destroy-on-close
    :loading="saving"
    @update:model-value="val => emit('update:modelValue', val)"
    @close="emit('close')"
    @cancel="emit('close')"
    @confirm="handleSave"
  >
    <div class="tag-editor-content">
      <p v-if="mode === 'single' && targetAsset" class="tag-file-name">{{ targetAsset.fileName }}</p>
      <div class="tag-form-field">
        <label>标签</label>
        <!-- R4 配方：LqTagInput 替代文本输入 + 手写建议 chip -->
        <LqTagInput
          :model-value="localTags"
          :suggestions="COMMON_BRAND_TAGS"
          placeholder="输入标签后按 Enter 添加，或点击下方常用标签"
          @update:model-value="onTagsChange"
        />
      </div>
    </div>
  </LqDialog>
</template>

<script setup lang="ts">
import { ref, computed, watch } from 'vue'
import LqDialog from '../common/LqDialog.vue'
import LqTagInput from '../common/LqTagInput.vue'
import { COMMON_BRAND_TAGS } from '../../constants/fields/brand-assets'

const props = defineProps<{
  modelValue: boolean
  mode: 'single' | 'batch'
  targetAsset: any | null
  selectedCount: number
  currentTags: string
  saving: boolean
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', val: boolean): void
  (e: 'save', tags: string): void
  (e: 'close'): void
}>()

const localTags = ref(props.currentTags)

watch(
  () => props.modelValue,
  val => {
    if (val) {
      localTags.value = props.currentTags || ''
    }
  }
)

function onTagsChange(val: string) {
  localTags.value = val
}

const dialogTitle = computed(() => {
  if (props.mode === 'batch') {
    return `批量标记 ${props.selectedCount || 0} 个素材`
  }
  return '编辑标签'
})

function handleSave() {
  emit('save', localTags.value)
}
</script>

<style scoped>
.tag-editor-content {
  display: flex;
  flex-direction: column;
  gap: var(--space-control-gap-tight);
}

.tag-file-name {
  font-size: 13px;
  color: var(--color-text-secondary);
  margin: 0 0 12px;
  padding: 6px 10px;
  background: var(--color-surface-alt);
  border-radius: var(--radius-md);
}

.tag-form-field {
  display: flex;
  flex-direction: column;
  gap: 5px;
  margin-bottom: 16px;
}

.tag-form-field label {
  font-size: 13px;
  font-weight: 500;
  color: var(--color-text-secondary);
}

.tag-form-field input {
  padding: 9px 12px;
  border: 1px solid var(--color-border-light);
  border-radius: var(--radius-md);
  font-size: 14px;
  color: var(--color-text-primary);
  background: var(--color-surface);
  transition: border-color var(--transition-fast);
}

.tag-form-field input:focus {
  outline: none;
  border-color: var(--color-primary);
  box-shadow: 0 0 0 3px var(--color-primary-ring);
}

.tag-suggestions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
}

.sug-label {
  font-size: 12px;
  color: var(--color-text-faint);
  margin-right: 4px;
}

.sug-chip {
  padding: 3px 10px;
  border: 1px solid var(--color-border-lighter);
  border-radius: var(--radius-pill);
  background: var(--color-surface);
  color: var(--color-text-secondary);
  font-size: 12px;
  cursor: pointer;
  transition: all var(--transition-fast);
}

.sug-chip:hover {
  border-color: var(--color-primary);
  color: var(--color-primary);
}

.sug-chip.added {
  background: var(--color-primary-bg);
  color: var(--color-primary);
  border-color: var(--color-primary-border);
}
</style>
