import { type ComputedRef, type Ref, computed, reactive, ref } from 'vue'
import {
  batchDeletePackagingVariants,
  batchUpdatePackagingVariants,
  duplicatePackagingVariant,
  movePackagingVariant,
  type PackagingModelDetail,
  type PackagingVariantView
} from '@/api/packaging'
import { askConfirm } from '@/composables/ui/useConfirmDialog'
import { notify, type NotifyFn } from '@/composables/ui/useToast'
import { buildBatchToast, useBatchProgress } from '@/composables/common/useBatchFlow'
import type { BatchResultSummary } from '@/types/batchFlow'
import { todayIso } from '@/utils/common/dateUtils'
import { downloadBlob } from '@/utils/ui/downloadBlob'

/**
 * 包材规格批量操作
 *
 * 抽取自 PackagingDetailDrawer.vue，负责规格的批量选择、删除、复制、
 * 移动、补齐、编辑、导出等操作，以及批量进度条状态管理。
 */
export interface VariantBatchOpsHelpers {
  isFilmModel: ComputedRef<boolean>
  filteredVariants: ComputedRef<PackagingVariantView[]>
  selectedVariant: Ref<PackagingVariantView | null>
  suppressClick: Ref<boolean> | ComputedRef<boolean>
  notify?: NotifyFn
  onAssetChanged: () => void
  onReloadDetail: () => Promise<void>
}

export function useVariantBatchOps(detail: Ref<PackagingModelDetail | null>, helpers: VariantBatchOpsHelpers) {
  const { isFilmModel, filteredVariants, selectedVariant, suppressClick, onAssetChanged, onReloadDetail } = helpers
  const notifyFn = helpers.notify ?? notify

  const selectedVariantIds = reactive(new Set<number>())
  const batchMode = ref(false)
  const batchMoveVisible = ref(false)
  const batchMoveLoading = ref(false)
  const batchFillVisible = ref(false)
  const batchFillLoading = ref(false)
  const batchEditVisible = ref(false)
  const batchEditLoading = ref(false)

  /** R2 配方：useBatchProgress 替代手写 reactive progress + show/update/done/error 四件套。 */
  const { progress: batchProgress, showProgress: showBatchProgress, updateProgress: updateBatchProgress, setDone: setBatchProgressDone, setError: setBatchProgressError } = useBatchProgress()

  const selectedVariants = computed(() => {
    return filteredVariants.value.filter(v => selectedVariantIds.has(v.id))
  })

  const isAllSelected = computed(() => {
    return filteredVariants.value.length > 0 && filteredVariants.value.every(v => selectedVariantIds.has(v.id))
  })

  function toggleVariantSelection(id: number) {
    if (selectedVariantIds.has(id)) {
      selectedVariantIds.delete(id)
    } else {
      selectedVariantIds.add(id)
    }
  }

  function toggleAllVariantSelection() {
    if (isAllSelected.value) {
      selectedVariantIds.clear()
    } else {
      filteredVariants.value.forEach(v => selectedVariantIds.add(v.id))
    }
  }

  function enterBatchMode() {
    batchMode.value = true
  }

  function exitBatchMode() {
    batchMode.value = false
    selectedVariantIds.clear()
  }

  function handleVariantCheckboxClick() {
    event?.stopPropagation()
  }

  function handleVariantCardClick(variant: PackagingVariantView, event: MouseEvent | KeyboardEvent) {
    if ((event.target as HTMLElement).closest('.spec-card-checkbox')) {
      return
    }
    if (!suppressClick.value) {
      selectedVariant.value = variant
    }
  }

  async function batchDeleteVariants() {
    if (selectedVariants.value.length === 0) return
    const ok = await askConfirm({
      title: '批量删除规格',
      message: `确定要删除选中的 ${selectedVariants.value.length} 个规格吗？`,
      danger: true
    })
    if (!ok) return
    showBatchProgress('批量删除规格', 1)
    try {
      const ids = selectedVariants.value.map(v => v.id)
      await batchDeletePackagingVariants(ids)
      selectedVariantIds.clear()
      await onReloadDetail()
      updateBatchProgress(1)
      setBatchProgressDone()
      /** R2 配方：统一推导 toast 语义与 level。 */
      const summary: BatchResultSummary = { affected: ids.length, total: ids.length }
      const toast = buildBatchToast(summary, '删除')
      notifyFn?.(toast.message || '删除成功', toast.level)
    } catch {
      setBatchProgressError()
      notifyFn?.('删除失败', 'error')
    }
  }

  async function batchDuplicateVariants() {
    if (selectedVariants.value.length === 0) return
    const count = selectedVariants.value.length
    showBatchProgress('批量复制规格', count)
    try {
      for (let i = 0; i < selectedVariants.value.length; i++) {
        await duplicatePackagingVariant(selectedVariants.value[i].id)
        updateBatchProgress(i + 1)
      }
      selectedVariantIds.clear()
      await onReloadDetail()
      setBatchProgressDone()
      /** R2 配方：统一推导 toast 语义与 level。 */
      const summary: BatchResultSummary = { affected: count, total: count }
      const toast = buildBatchToast(summary, '复制')
      notifyFn?.(toast.message || `已复制 ${count} 个规格`, toast.level)
    } catch {
      setBatchProgressError()
      notifyFn?.('复制失败', 'error')
    }
  }

  function openBatchMoveModal() {
    if (selectedVariants.value.length === 0) return
    batchMoveVisible.value = true
  }

  function closeBatchMoveModal() {
    if (batchMoveLoading.value) return
    batchMoveVisible.value = false
  }

  async function confirmBatchMoveVariants(targetId: number) {
    if (!targetId || selectedVariants.value.length === 0) return
    const movingVariants = [...selectedVariants.value]
    showBatchProgress('批量移动规格', movingVariants.length)
    batchMoveLoading.value = true
    try {
      for (let i = 0; i < movingVariants.length; i++) {
        await movePackagingVariant(movingVariants[i].id, targetId)
        updateBatchProgress(i + 1)
      }
      selectedVariantIds.clear()
      selectedVariant.value = null
      batchMoveVisible.value = false
      await onReloadDetail()
      onAssetChanged()
      setBatchProgressDone()
      /** R2 配方：统一推导 toast 语义与 level。 */
      const summary: BatchResultSummary = { affected: movingVariants.length, total: movingVariants.length }
      const toast = buildBatchToast(summary, '移动')
      notifyFn?.(toast.message || `已移动 ${movingVariants.length} 个规格`, toast.level)
    } catch {
      setBatchProgressError()
      notifyFn?.('移动失败，请稍后重试', 'error')
    } finally {
      batchMoveLoading.value = false
    }
  }

  function openBatchFillModal() {
    if (!isFilmModel.value || selectedVariants.value.length === 0) return
    batchFillVisible.value = true
  }

  function closeBatchFillModal() {
    if (batchFillLoading.value) return
    batchFillVisible.value = false
  }

  async function confirmBatchFillVariants(idsToFill: number[], fillPayload: Record<string, unknown>) {
    if (idsToFill.length === 0) {
      batchFillVisible.value = false
      notifyFn?.('所有选中规格已填有对应字段，无需补齐', 'info')
      return
    }
    showBatchProgress('批量补齐膜规格', 1)
    batchFillLoading.value = true
    try {
      await batchUpdatePackagingVariants(idsToFill, fillPayload)
      batchFillVisible.value = false
      await onReloadDetail()
      updateBatchProgress(1)
      setBatchProgressDone()
      /** R2 配方：统一推导 toast 语义与 level。 */
      const summary: BatchResultSummary = { affected: idsToFill.length, total: idsToFill.length }
      const toast = buildBatchToast(summary, '补齐')
      notifyFn?.(toast.message || `已补齐 ${idsToFill.length} 个膜规格`, toast.level)
    } catch {
      setBatchProgressError()
      notifyFn?.('批量补齐失败，请稍后重试', 'error')
    } finally {
      batchFillLoading.value = false
    }
  }

  function openBatchEditModal() {
    if (selectedVariants.value.length === 0) return
    batchEditVisible.value = true
    batchEditLoading.value = false
  }

  function closeBatchEditModal() {
    if (batchEditLoading.value) return
    batchEditVisible.value = false
  }

  async function confirmBatchEditVariants(payload: Record<string, unknown>) {
    if (selectedVariants.value.length === 0) return
    const ids = selectedVariants.value.map(v => v.id)
    if (Object.keys(payload).length === 0) return
    showBatchProgress('批量编辑规格', 1)
    batchEditLoading.value = true
    try {
      await batchUpdatePackagingVariants(ids, payload)
      batchEditVisible.value = false
      selectedVariantIds.clear()
      selectedVariant.value = null
      batchMode.value = false
      await onReloadDetail()
      onAssetChanged()
      updateBatchProgress(1)
      setBatchProgressDone()
      /** R2 配方：统一推导 toast 语义与 level。 */
      const summary: BatchResultSummary = { affected: ids.length, total: ids.length }
      const toast = buildBatchToast(summary, '批量编辑')
      notifyFn?.(toast.message || `已批量编辑 ${ids.length} 个规格`, toast.level)
    } catch {
      setBatchProgressError()
      notifyFn?.('批量编辑失败，请稍后重试', 'error')
    } finally {
      batchEditLoading.value = false
    }
  }

  function escapeCsv(value: unknown) {
    const text = String(value ?? '')
    return `"${text.replace(/"/g, '""')}"`
  }

  function exportSelectedVariants() {
    if (selectedVariants.value.length === 0) return
    const variants = selectedVariants.value
    let headers: string[]
    let rows: (string | number)[][]
    if (isFilmModel.value) {
      headers = [
        '规格名称',
        '膜材质',
        '供货类型',
        '易揭性',
        '版费',
        '计价单位',
        '50-99kg',
        '100-299kg',
        '300-499kg',
        '500-999kg',
        '1000-1999kg',
        '2000kg+',
        '备注'
      ]
      rows = variants.map(v => [
        v.variantName || '',
        v.filmMaterial || '',
        v.filmSupplyType || '',
        v.peelability || '',
        v.plateFee || '',
        v.priceUnit || '',
        v.tier5099 || '',
        v.tier100299 || '',
        v.tier300499 || '',
        v.tier500999 || '',
        v.tier10001999 || '',
        v.tier2000Up || '',
        v.remark || ''
      ])
    } else {
      headers = ['规格名称', '容量', '长度', '宽度', '高度', '材质明细', '形状', '经销价', '零售价', '备注']
      rows = variants.map(v => [
        v.variantName || '',
        v.capacityMl || '',
        v.lengthMm || '',
        v.widthMm || '',
        v.heightMm || '',
        v.materialDetail || '',
        v.shapeType || '',
        v.dealerPrice || '',
        v.retailPrice || '',
        v.remark || ''
      ])
    }
    const csv = [headers, ...rows].map(row => row.map(escapeCsv).join(',')).join('\n')
    const blob = new Blob([`\uFEFF${csv}`], { type: 'text/csv;charset=utf-8' })
    const fileName = `${detail.value?.model.name || '规格'}-${todayIso()}.csv`
    downloadBlob(blob, fileName)
  }

  return {
    selectedVariantIds,
    batchMode,
    batchMoveVisible,
    batchMoveLoading,
    batchFillVisible,
    batchFillLoading,
    batchEditVisible,
    batchEditLoading,
    batchProgress,
    selectedVariants,
    isAllSelected,
    toggleVariantSelection,
    toggleAllVariantSelection,
    enterBatchMode,
    exitBatchMode,
    handleVariantCheckboxClick,
    handleVariantCardClick,
    batchDeleteVariants,
    batchDuplicateVariants,
    openBatchMoveModal,
    closeBatchMoveModal,
    confirmBatchMoveVariants,
    openBatchFillModal,
    closeBatchFillModal,
    confirmBatchFillVariants,
    openBatchEditModal,
    closeBatchEditModal,
    confirmBatchEditVariants,
    exportSelectedVariants
  }
}
