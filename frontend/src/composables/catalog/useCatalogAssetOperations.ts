import { ref, type Ref } from 'vue'
import { notify } from '@/composables/ui/useToast'
import { askConfirm } from '@/composables/ui/useConfirmDialog'
import {
  reorderCatalogAssets,
  updateCatalogAssetMetadata,
  updateCatalogAssetMetadataBatch
} from '../../api/catalog'
import { ASSET_CATEGORY_KEYS } from '../../constants/fields'
import { FILE_ASSET_FAILURE_PREFIX } from '@/constants/fileAssetMessages'
import type { FileOwnerAssetGroupEntries } from '../../utils/catalog/fileAssetUtils'

export interface UseCatalogAssetOperationsOptions {
  detail: Ref<any>
  selectedId: Ref<number | null>
  activeTab: Ref<string>
  assetBatchSelection: {
    batchMode: Ref<boolean>
    selectedIds: Set<number>
    toggleBatchMode: () => void
    toggleSelection: (id: number) => void
    toggleSelectAll: (items: any[]) => void
  }
  fileOwnerTypeForProductAsset: (asset: any) => string
  findProductModelFileHandle: (asset: any) => any
  productModelFileAssets: any
  fileAssetActions: any
  filePreviewUrlForProductAsset: (asset: any) => string | undefined
  selectedProductAssetGroups: (ids?: number[]) => FileOwnerAssetGroupEntries
  processUploadFiles: (files: File[], category: string) => Promise<void>
  refreshCurrentModel: (tab?: string) => Promise<void>
  emitPreviewAsset: (asset: any) => void
  assetUploadCategory: Ref<string>
}

export function useCatalogAssetOperations(options: UseCatalogAssetOperationsOptions) {
  const {
    detail,
    selectedId,
    activeTab,
    assetBatchSelection,
    fileOwnerTypeForProductAsset,
    findProductModelFileHandle,
    productModelFileAssets,
    fileAssetActions,
    filePreviewUrlForProductAsset,
    selectedProductAssetGroups,
    processUploadFiles,
    refreshCurrentModel,
    emitPreviewAsset,
    assetUploadCategory
  } = options

  const assetFilter = ref('all')
  const highlightedAssetId = ref<number | null>(null)
  const highlightedAssetTimer = ref<ReturnType<typeof setTimeout> | null>(null)
  const assetMenuId = ref<number | null>(null)

  const { batchMode: assetBatchMode, selectedIds: selectedAssetIds } = assetBatchSelection

  function assetReferenceLabels(asset: any) {
    const model = detail.value?.model
    if (!model || !asset?.fileName) return []
    const labels: string[] = []
    if (asset.category === ASSET_CATEGORY_KEYS.mainImage && asset.fileName === model.defaultImage)
      labels.push('产品主图')
    if (asset.category === 'manual') labels.push('说明书')
    return labels
  }

  function assetReferenceWarning(assets: any[]) {
    const references = assets.flatMap(asset => assetReferenceLabels(asset))
    if (!references.length) return ''
    const preview = [...new Set(references)].slice(0, 4).join('、')
    const suffix = references.length > 4 ? ' 等' : ''
    return (
      '\n\n当前引用：' + preview + suffix + '。删除后系统会尝试自动切换到同类剩余素材；如无可用素材，对应引用将被清空。'
    )
  }

  async function setAssetDefault(asset: any) {
    try {
      const ownerType = fileOwnerTypeForProductAsset(asset)
      if (findProductModelFileHandle(asset)) {
        await productModelFileAssets.setDefault(asset.id)
      } else {
        await fileAssetActions.setDefault(ownerType, asset.id)
        await refreshCurrentModel(activeTab.value)
        notify('默认素材已更新')
      }
    } catch (err) {
      notify.errorWithDetail(FILE_ASSET_FAILURE_PREFIX.setDefault, err)
    }
  }

  async function saveAssetTags(payload: {
    id: number
    contentTags: string
    machineTierAction: 'KEEP' | 'ASSIGN' | 'UNLINK'
    machineTierIds: number[]
    applicationTags?: string
    videoContentType?: string
    videoUsageChannels?: string
    videoContentStatus?: string
    featureTags?: string
    demonstrationTags?: string
  }) {
    try {
      const updatedAsset: any = await updateCatalogAssetMetadata(payload.id, payload)
      await refreshCurrentModel('assets')
      notify(updatedAsset?.machineTierLabel ? `素材已归属机型「${updatedAsset.machineTierLabel}」` : '素材信息已保存', 'success')
    } catch (err) {
      notify.errorWithDetail(FILE_ASSET_FAILURE_PREFIX.updateTags, err)
    }
  }

  async function batchSaveAssetTags(payload: {
    contentTags: string
    tagAction: 'APPEND' | 'REPLACE' | 'REMOVE'
    machineTierAction: 'KEEP' | 'ASSIGN' | 'UNLINK'
    machineTierIds: number[]
    applicationTags?: string
    videoContentType?: string
    videoUsageChannels?: string
    videoContentStatus?: string
    featureTags?: string
    demonstrationTags?: string
    replaceVideoMetadata?: boolean
  }) {
    if (selectedAssetIds.size === 0) return
    try {
      const affected = selectedAssetIds.size
      await updateCatalogAssetMetadataBatch({ ids: [...selectedAssetIds], ...payload })
      await refreshCurrentModel('assets')
      const scopeMessage = payload.machineTierAction === 'UNLINK'
        ? '，并改为产品通用'
        : payload.machineTierAction === 'ASSIGN' ? '，并更新机型归属' : ''
      notify(`已更新 ${affected} 个素材${scopeMessage}`, 'success')
    } catch (err) {
      notify.errorWithDetail(FILE_ASSET_FAILURE_PREFIX.batchUpdateTags, err)
    }
  }

  async function removeAsset(asset: any) {
    assetMenuId.value = null
    const warning = assetReferenceWarning([asset])
    const decision = await askConfirm({
      title: '删除素材？',
      message:
        '素材：' +
        asset.fileName +
        '\n选择“只删记录”会保留 storage 中的本地文件；选择“同时删除文件”会连同本地文件一起删除。' +
        warning,
      cancelText: '取消',
      secondaryText: '只删记录',
      confirmText: '同时删除文件',
      danger: true
    })
    if (!decision) return
    try {
      const handle = findProductModelFileHandle(asset)
      const ownerType = fileOwnerTypeForProductAsset(asset)
      if (decision !== true) {
        if (handle) {
          await productModelFileAssets.remove(asset.id)
        } else {
          await fileAssetActions.remove(ownerType, asset.id)
          await refreshCurrentModel(activeTab.value)
          notify('素材记录已删除，本地文件已保留')
        }
      } else {
        await fileAssetActions.remove(ownerType, asset.id, { permanent: true, deletePhysical: true })
        await refreshCurrentModel(activeTab.value)
        notify('素材记录和本地文件已删除')
      }
    } catch (err) {
      notify.errorWithDetail(FILE_ASSET_FAILURE_PREFIX.delete, err)
    }
  }

  function toggleAssetBatchMode() {
    assetBatchSelection.toggleBatchMode()
  }
  function toggleAssetSelect(id: number) {
    assetBatchSelection.toggleSelection(id)
  }
  function toggleSelectAllAssets(visibleItems: any[] = []) {
    assetBatchSelection.toggleSelectAll(visibleItems)
  }

  async function batchRemoveAssets() {
    if (selectedAssetIds.size === 0) return
    const selectedAssets = (detail.value?.assets || []).filter((asset: any) => selectedAssetIds.has(asset.id))
    const warning = assetReferenceWarning(selectedAssets)
    const decision = await askConfirm({
      title: '批量删除素材？',
      message:
        '已选择 ' +
        selectedAssetIds.size +
        ' 个素材。\n选择“只删记录”会保留本地文件；选择“同时删除文件”会连同 storage 中的本地文件一起删除。' +
        warning,
      cancelText: '取消',
      secondaryText: '只删记录',
      confirmText: '同时删除文件',
      danger: true
    })
    if (!decision) return
    try {
      const ids = Array.from(selectedAssetIds)
      const deleteFile = decision === true
      const groups = selectedProductAssetGroups(ids)
      for (const [ownerType, groupIds] of groups) {
        await fileAssetActions.batchRemove(
          ownerType,
          groupIds,
          deleteFile ? { permanent: true, deletePhysical: true } : {}
        )
      }
      notify(
        deleteFile
          ? '已删除 ' + ids.length + ' 个素材及本地文件'
          : '已删除 ' + ids.length + ' 个素材记录，本地文件已保留'
      )
      selectedAssetIds.clear()
      assetBatchMode.value = false
      await refreshCurrentModel('assets')
    } catch (err) {
      notify.errorWithDetail(FILE_ASSET_FAILURE_PREFIX.batchDelete, err)
    }
  }

  function openAssetTask(category = 'all') {
    activeTab.value = 'assets'
    assetFilter.value = category
    selectedAssetIds.clear()
  }

  async function handleReorderMainImages(orderedIds: number[]) {
    if (!detail.value?.assets) return
    const orderMap = new Map(orderedIds.map((id, i) => [id, i]))
    for (const asset of detail.value.assets) {
      if (orderMap.has(asset.id)) asset.sortOrder = orderMap.get(asset.id)
    }
    detail.value.assets.sort((a: any, b: any) => {
      const ca = a.category ?? '',
        cb = b.category ?? ''
      if (ca !== cb) return ca.localeCompare(cb)
      return (a.sortOrder ?? 0) - (b.sortOrder ?? 0)
    })
    try {
      const items = orderedIds.map((id, i) => ({ id, order: i }))
      await reorderCatalogAssets(items)
    } catch (err) {
      notify.errorWithDetail(FILE_ASSET_FAILURE_PREFIX.reorder, err)
      await refreshCurrentModel('assets')
    }
  }

  async function handleReorderAssets(items: { id: number; sortOrder: number }[]) {
    if (!detail.value?.assets) return
    const orderMap = new Map(items.map(item => [item.id, item.sortOrder]))
    for (const asset of detail.value.assets) {
      if (orderMap.has(asset.id)) {
        asset.sortOrder = orderMap.get(asset.id)
      }
    }
    detail.value.assets.sort((a: any, b: any) => {
      const ca = a.category ?? '',
        cb = b.category ?? ''
      if (ca !== cb) return ca.localeCompare(cb)
      return (a.sortOrder ?? 0) - (b.sortOrder ?? 0)
    })
    try {
      const apiItems = items.map(item => ({ id: item.id, order: item.sortOrder }))
      await reorderCatalogAssets(apiItems)
    } catch (err) {
      notify.errorWithDetail(FILE_ASSET_FAILURE_PREFIX.reorder, err)
      await refreshCurrentModel('assets')
    }
  }

  async function handleDropMainImages(files: File[]) {
    if (!detail.value) return
    await processUploadFiles(files, ASSET_CATEGORY_KEYS.mainImage)
    await refreshCurrentModel('assets')
  }

  async function handleAssetDragUpload(event: DragEvent) {
    if (!event.dataTransfer?.files?.length) return
    const files = Array.from(event.dataTransfer.files)
    await processUploadFiles(files, assetUploadCategory.value)
  }

  async function handleAssetPasteUpload(files: File[]) {
    if (!files?.length) return
    await processUploadFiles(files, assetUploadCategory.value)
  }

  function normalizePreviewAsset(asset: any) {
    if (!asset) return
    const normalized =
      typeof asset === 'string' ? { category: ASSET_CATEGORY_KEYS.mainImage, fileName: asset } : { ...asset }
    if (!normalized.fileName) return
    if (normalized.modelId == null && selectedId.value != null) {
      normalized.modelId = selectedId.value
    }
    if (normalized.id && !normalized.assetId) {
      normalized.assetId = normalized.id
    }
    if (!normalized.assetId) {
      const handle = findProductModelFileHandle(normalized)
      if (handle) {
        normalized.assetId = handle.assetId
        normalized.ownerType = handle.ownerType
      }
    }
    if (normalized.assetId && !normalized.ownerType) {
      normalized.ownerType = fileOwnerTypeForProductAsset(normalized)
    }
    const previewUrl = filePreviewUrlForProductAsset(normalized)
    if (previewUrl) normalized.fileUrl = previewUrl
    return normalized
  }

  function previewCatalogAsset(asset: any, galleryAssets?: any[], galleryIndex = 0) {
    const normalized = normalizePreviewAsset(asset)
    if (!normalized) return
    if (galleryAssets?.length) {
      normalized.gallery = galleryAssets.map(normalizePreviewAsset).filter(Boolean)
      normalized.galleryIndex = Math.min(Math.max(galleryIndex, 0), normalized.gallery.length - 1)
    }
    emitPreviewAsset(normalized)
  }

  function selectAssetFilter(key: string) {
    assetFilter.value = key
    selectedAssetIds.clear()
    highlightedAssetId.value = null
    assetMenuId.value = null
  }

  return {
    assetFilter,
    highlightedAssetId,
    highlightedAssetTimer,
    assetMenuId,
    assetReferenceLabels,
    assetReferenceWarning,
    setAssetDefault,
    saveAssetTags,
    batchSaveAssetTags,
    removeAsset,
    toggleAssetBatchMode,
    toggleAssetSelect,
    toggleSelectAllAssets,
    batchRemoveAssets,
    openAssetTask,
    handleReorderMainImages,
    handleReorderAssets,
    handleDropMainImages,
    handleAssetDragUpload,
    handleAssetPasteUpload,
    previewCatalogAsset,
    selectAssetFilter
  }
}
