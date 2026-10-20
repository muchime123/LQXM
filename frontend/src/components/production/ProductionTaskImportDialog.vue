<template>
  <H3ImportPreviewDialog
    v-model="visible"
    :kind="{ title: '生产任务单' }"
    :file="file"
    :batch-id="batchId"
    :parse-progress="progress"
    :preview="preview"
    :ledger-rows="[]"
    :loading="loading"
    :confirm-text="preview ? '确认导入' : progress && progress.status !== 'FAILED' ? '解析中…' : '开始解析'"
    :confirm-enabled="!loading && (!progress || !!preview || progress.status === 'FAILED')"
    @file-change="selectFile"
    @confirm="confirm"
  />
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { ElMessage } from 'element-plus'
import type { UploadFile } from 'element-plus'
import H3ImportPreviewDialog from '@/components/h3/H3ImportPreviewDialog.vue'
import type { BatchProgress, ImportPreview } from '@/types/h3Import'
import { confirmProductionTaskImport, productionTaskImportPreview, stageProductionTask, waitProductionTaskImport } from '@/api/productionTaskImport'

const props = defineProps<{ modelValue: boolean }>()
const emit = defineEmits<{ (e: 'update:modelValue', value: boolean): void; (e: 'imported'): void }>()
const visible = computed({ get: () => props.modelValue, set: value => emit('update:modelValue', value) })
const file = ref<File | null>(null)
const batchId = ref<number | null>(null)
const progress = ref<BatchProgress | null>(null)
const preview = ref<ImportPreview | null>(null)
const loading = ref(false)

function selectFile(selected: UploadFile) {
  file.value = selected.raw || null
  batchId.value = null
  progress.value = null
  preview.value = null
}

async function confirm() {
  if (loading.value) return
  if (preview.value && batchId.value != null) {
    loading.value = true
    try {
      await confirmProductionTaskImport(batchId.value)
      ElMessage.success('生产任务单已导入')
      visible.value = false
      emit('imported')
    } catch (error) {
      ElMessage.error(error instanceof Error ? error.message : '确认导入失败')
    } finally { loading.value = false }
    return
  }
  if (!file.value) { ElMessage.warning('请先选择生产任务单 Excel 文件'); return }
  loading.value = true
  try {
    const accepted = await stageProductionTask(file.value)
    batchId.value = accepted.batchId
    if (accepted.hashHit) ElMessage.info(accepted.message || '复用已有解析结果')
    const result = await waitProductionTaskImport(accepted.batchId, value => { progress.value = value })
    progress.value = result
    if (result.status === 'FAILED') { ElMessage.error(result.errorMessage || '解析失败'); return }
    preview.value = (await productionTaskImportPreview(accepted.batchId)).preview
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '解析生产任务单失败')
  } finally { loading.value = false }
}
</script>
