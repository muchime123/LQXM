import { ref, type Ref } from 'vue'
import { fetchCatalogModel, fetchCatalogModelsSimple } from '../../api/catalog'
import { moveCatalogAsset } from '../../api/catalog/assets'
import { notify } from '../ui/useToast'
import { buildBatchToast } from '../common/useBatchFlow'
import type { BatchResultSummary } from '../../types/batchFlow'

interface MoveOptions {
  getSelectedId?: () => number | undefined
  getDetail?: () => { variants?: Array<{ id: number; variantName?: string }> } | undefined
  refreshCurrentModel?: (tab?: string) => Promise<void>
  selectModel?: (modelId: number, options?: { preserveTab?: boolean }) => Promise<void>
  runBatchWithProgress?: <T>(items: T[], title: string, handler: (item: T) => Promise<void>) => Promise<void>
  getSelectedAssetIds?: () => Set<number> | undefined
  clearAssetSelection?: () => void
  exitAssetBatchMode?: () => void
  setActiveTab?: (tab: string) => void
}

interface MoveAssetPayload {
  modelId: number
  variantId?: number | null
  category?: string | null
}

export interface CatalogMoveOperations {
  moveAssetVisible: Ref<boolean>
  moveAssetAssetId: Ref<number | null>
  moveAssetAssetFileName: Ref<string>
  moveAssetCategory: Ref<string>
  moveAssetVariantId: Ref<number | null>
  moveAssetTargetModelId: Ref<number | null>
  moveAssetTargetVariantId: Ref<number | null>
  moveAssetTargetVariants: Ref<unknown[]>
  moveAssetModelOptions: Ref<unknown[]>
  openMoveAssetModal: (asset: { id: number; fileName: string; category: string; variantId?: number | null }) => void
  loadMoveAssetTargetVariants: (targetModelId: number) => Promise<void>
  doMoveAsset: (payload: MoveAssetPayload) => Promise<void>
  batchMoveAssetVisible: Ref<boolean>
  batchMoveAssetModelOptions: Ref<unknown[]>
  batchMoveAssetTargetVariants: Ref<unknown[]>
  openBatchMoveAssetModal: () => void
  loadBatchMoveAssetOptions: () => Promise<void>
  loadBatchMoveAssetTargetVariants: (targetModelId: number) => Promise<void>
  doBatchMoveAsset: (payload: MoveAssetPayload) => Promise<void>
}

export function useCatalogMoveOperations(options: MoveOptions = {}): CatalogMoveOperations {

  const moveAssetVisible = ref(false)
  const moveAssetAssetId = ref<number | null>(null)
  const moveAssetAssetFileName = ref('')
  const moveAssetCategory = ref('')
  const moveAssetVariantId = ref<number | null>(null)
  const moveAssetTargetModelId = ref<number | null>(null)
  const moveAssetTargetVariantId = ref<number | null>(null)
  const moveAssetTargetVariants = ref<unknown[]>([])
  const moveAssetModelOptions = ref<unknown[]>([])

  async function openMoveAssetModal(asset: {
    id: number
    fileName: string
    category: string
    variantId?: number | null
  }): Promise<void> {
    moveAssetAssetId.value = asset.id
    moveAssetAssetFileName.value = asset.fileName
    moveAssetCategory.value = asset.category
    moveAssetVariantId.value = asset.variantId || null
    moveAssetTargetModelId.value = options.getSelectedId?.() || null
    moveAssetTargetVariantId.value = asset.variantId || null
    moveAssetTargetVariants.value = options.getDetail?.()?.variants || []
    try {
      moveAssetModelOptions.value = await fetchCatalogModelsSimple()
    } catch (err) {
      notify.errorWithDetail('加载产品列表失败', err)
      return
    }
    moveAssetVisible.value = true
  }

  async function loadMoveAssetTargetVariants(targetModelId: number): Promise<void> {
    if (!targetModelId) {
      moveAssetTargetVariants.value = []
      return
    }
    try {
      const targetModelDetail = await fetchCatalogModel(targetModelId)
      moveAssetTargetVariants.value = targetModelDetail.variants || []
    } catch {
      notify('加载目标产品历史规格失败', 'error')
      moveAssetTargetVariants.value = []
    }
  }

  async function doMoveAsset(payload: MoveAssetPayload): Promise<void> {
    if (!payload.modelId || !payload.category) return
    try {
      await (moveCatalogAsset as any)(
        moveAssetAssetId.value,
        payload.modelId,
        payload.variantId ?? null,
        payload.category ?? null
      )
      const selectedId = options.getSelectedId?.()
      const summary: BatchResultSummary = { affected: 1, total: 1 }
      const toast = buildBatchToast(summary, '移动')
      notify(toast.message || (payload.modelId === selectedId ? '素材分类或归属已更新' : '素材已移动到目标产品'), toast.level)
      moveAssetVisible.value = false
      await options.refreshCurrentModel?.('assets')
    } catch (err) {
      notify.errorWithDetail('移动失败', err)
    }
  }

  const batchMoveAssetVisible = ref(false)
  const batchMoveAssetModelOptions = ref<unknown[]>([])
  const batchMoveAssetTargetVariants = ref<unknown[]>([])

  function openBatchMoveAssetModal(): void {
    const selectedIds = options.getSelectedAssetIds?.()
    if (!selectedIds || selectedIds.size === 0) return
    batchMoveAssetTargetVariants.value = []
    loadBatchMoveAssetOptions()
    batchMoveAssetVisible.value = true
  }

  async function loadBatchMoveAssetOptions(): Promise<void> {
    try {
      batchMoveAssetModelOptions.value = await fetchCatalogModelsSimple()
    } catch (err) {
      notify.errorWithDetail('加载产品列表失败', err)
    }
  }

  async function loadBatchMoveAssetTargetVariants(targetModelId: number): Promise<void> {
    batchMoveAssetTargetVariants.value = []
    if (!targetModelId) return
    try {
      const target = await fetchCatalogModel(targetModelId)
      batchMoveAssetTargetVariants.value = target.variants || []
    } catch (err) {
      notify.errorWithDetail('加载目标产品历史规格失败', err)
    }
  }

  async function doBatchMoveAsset(payload: MoveAssetPayload): Promise<void> {
    const targetId = payload.modelId
    if (!targetId) return
    try {
      const ids = Array.from(options.getSelectedAssetIds?.() || [])
      const targetVid = payload.variantId || null
      const targetCategory = payload.category || null
      await options.runBatchWithProgress?.(ids, '移动素材', id =>
        (moveCatalogAsset as any)(id, targetId, targetVid, targetCategory)
      )
      const summary: BatchResultSummary = { affected: ids.length, total: ids.length }
      const toast = buildBatchToast(summary, '移动')
      notify(toast.message || `已将 ${ids.length} 个素材移动到目标产品`, toast.level)
      batchMoveAssetVisible.value = false
      options.clearAssetSelection?.()
      options.exitAssetBatchMode?.()
      await options.refreshCurrentModel?.('assets')
    } catch (err) {
      notify.errorWithDetail('移动失败', err)
    }
  }

  return {
    moveAssetVisible,
    moveAssetAssetId,
    moveAssetAssetFileName,
    moveAssetCategory,
    moveAssetVariantId,
    moveAssetTargetModelId,
    moveAssetTargetVariantId,
    moveAssetTargetVariants,
    moveAssetModelOptions,
    openMoveAssetModal,
    loadMoveAssetTargetVariants,
    doMoveAsset,
    batchMoveAssetVisible,
    batchMoveAssetModelOptions,
    batchMoveAssetTargetVariants,
    openBatchMoveAssetModal,
    loadBatchMoveAssetOptions,
    loadBatchMoveAssetTargetVariants,
    doBatchMoveAsset
  }
}
