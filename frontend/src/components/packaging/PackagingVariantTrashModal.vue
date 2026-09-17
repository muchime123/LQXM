<template>
  <LqDialog
    :model-value="visible"
    title="规格回收站"
    :subtitle="`已删除 ${variants.length} 个规格，可恢复或永久删除。`"
    width="min(720px, 94vw)"
    mode="detail"
    :close-on-click-modal="true"
    :show-cancel="false"
    :show-confirm="false"
    cancel-text="关闭"
    @update:model-value="emit('update:visible', false)"
  >
    <div class="variant-trash-body">
      <div v-if="loading" class="empty-box">加载中...</div>
      <div v-else-if="variants.length === 0" class="empty-box">回收站为空</div>
      <table v-else class="variant-trash-table">
        <thead>
          <tr>
            <th>规格名称</th>
            <th>容量/尺寸</th>
            <th>删除时间</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="variant in variants" :key="variant.id">
            <td>{{ variant.variantName || '未命名规格' }}</td>
            <td>{{ variant.capacityMl || variant.lengthMm || '-' }}</td>
            <td>{{ variant.createdAt || '-' }}</td>
            <td class="trash-actions">
              <button type="button" class="btn-xs" @click="handleRestore(variant.id)">恢复</button>
              <button type="button" class="btn-xs danger" @click="handleDelete(variant.id)">永久删除</button>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
  </LqDialog>
</template>

<script setup lang="ts">
import { ref, watch } from 'vue'
import LqDialog from '../common/LqDialog.vue'
import {
  fetchPackagingVariantTrash,
  restorePackagingVariant,
  permanentDeletePackagingVariant,
  type PackagingVariantView
} from '@/api/packaging'
import { askConfirm } from '@/composables/ui/useConfirmDialog'
import { notify } from '@/composables/ui/useToast'

const props = defineProps<{ modelId: number; visible: boolean }>()
const emit = defineEmits<{ (e: 'update:visible', value: boolean): void; (e: 'restored'): void; (e: 'deleted'): void }>()

const loading = ref(false)
const variants = ref<PackagingVariantView[]>([])

async function load() {
  loading.value = true
  try {
    variants.value = await fetchPackagingVariantTrash(props.modelId)
  } catch {
    notify?.('回收站加载失败', 'error')
  } finally {
    loading.value = false
  }
}

async function handleRestore(id: number) {
  const confirmed = await askConfirm({ message: '确定恢复该规格？' })
  if (!confirmed) return
  try {
    await restorePackagingVariant(id)
    emit('restored')
    await load()
    notify?.('规格已恢复', 'success')
  } catch {
    notify?.('恢复失败，请稍后重试', 'error')
  }
}

async function handleDelete(id: number) {
  const confirmed = await askConfirm({ message: '确定永久删除该规格？此操作不可恢复！', danger: true })
  if (!confirmed) return
  try {
    await permanentDeletePackagingVariant(id)
    emit('deleted')
    await load()
    notify?.('规格已永久删除', 'success')
  } catch {
    notify?.('删除失败，请稍后重试', 'error')
  }
}

watch(
  () => props.visible,
  v => {
    if (v) void load()
  },
  { immediate: true }
)
</script>

<style scoped>
.variant-trash-body {
  padding: var(--space-control-gap-wide);
  background: var(--color-surface-alt);
  max-height: min(440px, 60vh);
  overflow-y: auto;
}

.variant-trash-table {
  width: 100%;
  border-collapse: collapse;
}

.variant-trash-table th,
.variant-trash-table td {
  padding: 10px 12px;
  text-align: left;
}

.variant-trash-table th {
  color: var(--color-text-muted);
  font-size: var(--fs-xs);
  font-weight: var(--fw-bold);
  border-bottom: 2px solid var(--color-border);
}

.variant-trash-table td {
  color: var(--color-text-heading);
  font-size: var(--fs-sm);
  border-bottom: 1px solid var(--color-border-light);
}

.trash-actions {
  display: flex;
  gap: var(--space-sm);
}

.btn-xs {
  height: 28px;
  border-radius: var(--radius-sm);
  padding: 0 10px;
  background: var(--color-surface);
  color: var(--color-text-heading);
  font-size: var(--fs-xs);
  font-weight: var(--fw-semibold);
  cursor: pointer;
  border: 1px solid var(--color-border);
}

.btn-xs.danger {
  border-color: var(--color-danger);
  background: var(--color-danger-bg);
  color: var(--color-danger);
}

.empty-box {
  display: grid;
  place-items: center;
  padding: 40px 16px;
  color: var(--color-text-muted);
  font-size: var(--fs-sm);
}
</style>
