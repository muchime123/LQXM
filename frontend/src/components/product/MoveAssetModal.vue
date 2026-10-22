<!--
  移动素材到【其它产品】（跨产品移动）+ 可选重分类。
  与 components/company/MoveAssetDialog.vue 的差异（勿合并）：
    本组件：跨产品，需选「目标产品」，分类为次选项，状态自管（内部 ref）。
    MoveAssetDialog：仅在同一资产池内换分类，不跨产品，选中态由父级受控。
  两者 UI 形态（表单下拉 vs 分类按钮列表）与数据契约均不同，属合理业务差异。
-->
<template>
  <LqDialog
    :model-value="visible"
    :eyebrow="subtitle"
    :title="title"
    :size="size"
    mode="form"
    :show-close="true"
    @update:model-value="v => $emit('update:visible', v)"
    @close="$emit('close')"
  >
    <section class="move-asset-body">
      <p v-if="assetName" class="asset-name-hint">素材：{{ assetName }}</p>
      <label class="field-item">
        <span class="field-label">素材分类</span>
        <select v-model="targetCategory" class="full-width" @change="handleCategoryChange">
          <option v-if="showKeepCurrentOption" value="">保留各素材当前分类</option>
          <option v-for="category in categories" :key="category.key" :value="category.key">{{ category.label }}</option>
        </select>
      </label>
      <label class="field-item">
        <span class="field-label">搜索目标产品</span>
        <input
v-model="searchQuery"
type="text"
placeholder="输入名称、产品线或设备大类搜索..."
class="full-width" />
      </label>
      <label class="field-item">
        <span class="field-label">目标产品</span>
        <select v-model="targetModelId" class="full-width">
          <option :value="null" disabled>请选择目标产品</option>
          <option v-for="m in filteredModels" :key="m.id" :value="m.id">
            {{ m.name }}（{{ m.category || '未分类' }}）
          </option>
        </select>
        <span v-if="searchQuery && filteredModels.length === 0" class="empty-hint">未找到匹配的产品</span>
      </label>
    </section>

    <template #footer>
      <span class="footer-status">移动后可在原产品的素材库恢复</span>
      <button type="button" @click="$emit('close')">取消</button>
      <button
type="button"
class="primary"
:disabled="!targetModelId"
@click="handleConfirm">确认移动</button>
    </template>
  </LqDialog>
</template>

<script setup>
import { ref, computed, watch } from 'vue'
import LqDialog from '../common/LqDialog.vue'

const props = defineProps({
  visible: { type: Boolean, default: false },
  title: { type: String, default: '移动素材' },
  subtitle: { type: String, default: '整理素材归属' },
  size: { type: String, default: 'sm' },
  assetName: { type: String, default: '' },
  categories: { type: Array, default: () => [] },
  models: { type: Array, default: () => [] },
  showKeepCurrentOption: { type: Boolean, default: false }
})

const emit = defineEmits(['close', 'confirm', 'update:visible'])

const targetCategory = ref('')
const searchQuery = ref('')
const targetModelId = ref(null)

watch(
  () => props.visible,
  v => {
    if (v) {
      targetCategory.value = props.showKeepCurrentOption ? '' : props.categories[0]?.key || ''
      searchQuery.value = ''
      targetModelId.value = null
    }
  }
)

const filteredModels = computed(() => {
  if (!searchQuery.value) return props.models
  const q = searchQuery.value.toLowerCase()
  return props.models.filter(
    m =>
      (m.name || '').toLowerCase().includes(q) ||
      (m.category || '').toLowerCase().includes(q)
  )
})

function handleCategoryChange() {
  targetModelId.value = null
}

function handleConfirm() {
  emit('confirm', {
    targetModelId: targetModelId.value,
    targetCategory: targetCategory.value
  })
}
</script>

<style scoped>
.move-asset-body {
  display: flex;
  flex-direction: column;
  gap: var(--space-section-gap);
  padding: 20px 24px;
}

.asset-name-hint {
  margin: 0;
  padding: 10px 12px;
  border-left: 3px solid var(--color-primary);
  border-radius: var(--radius-md);
  background: var(--color-surface-alt);
  color: var(--color-text-muted);
  font-size: var(--fs-sm);
}

.field-item {
  display: flex;
  flex-direction: column;
  gap: var(--space-sm);
}

.field-label {
  font-size: var(--fs-sm);
  font-weight: var(--fw-medium);
  color: var(--color-text-strong);
}

.full-width {
  width: 100%;
}

.empty-hint {
  font-size: var(--fs-xs);
  color: var(--color-text-faint);
}
</style>
