import { type ComputedRef, type Ref, reactive, ref } from 'vue'
import { reorderPackagingAssets, type PackagingAssetView, type PackagingModelDetail } from '@/api/packaging'
import type { FileOwnerType } from '@/types/fileAsset'
import { packagingFileOwnerTypeEntries } from '@/utils/catalog/fileAssetUtils'
import { askConfirm } from '@/composables/ui/useConfirmDialog'
import { notify, type NotifyFn } from '@/composables/ui/useToast'
import type { useFileAssetActions } from '@/composables/upload/useFileAssetActions'
import { PACKAGING_ASSET_CATEGORY_KEYS } from '@/constants/packaging'
import { FILE_ASSET_FAILURE_PREFIX } from '@/constants/fileAssetMessages'

/**
 * 包材素材资产动作
 *
 * 抽取自 PackagingDetailDrawer.vue，负责素材库的设为默认、标签编辑、
 * 删除、拖拽排序等操作，以及 tagEditor / draggingAssetId 状态管理。
 */
type AssetActions = Pick<ReturnType<typeof useFileAssetActions>, 'setDefault' | 'updateTags' | 'remove' | 'reorder'>

export interface PackagingAssetActionsHelpers {
  fileAssetActions: AssetActions
  filteredAssets: ComputedRef<PackagingAssetView[]>
  fileOwnerTypeForPackagingAsset: (asset: Partial<PackagingAssetView> | null | undefined) => FileOwnerType | null
  notify?: NotifyFn
  onAssetChanged: () => void
}

export function usePackagingAssetActions(
  detail: Ref<PackagingModelDetail | null>,
  helpers: PackagingAssetActionsHelpers
) {
  const { fileAssetActions, filteredAssets, fileOwnerTypeForPackagingAsset, onAssetChanged } = helpers
  const notifyFn = helpers.notify ?? notify

  const tagEditor = reactive({
    visible: false,
    assetId: 0,
    tags: ''
  })
  const draggingAssetId = ref<number | null>(null)

  async function makeDefault(id: number) {
    const asset = detail.value?.assets.find(item => item.id === id)
    const ownerType = fileOwnerTypeForPackagingAsset(asset)
    if (!ownerType) throw new Error('Packaging asset owner type is missing')
    await fileAssetActions.setDefault(ownerType, id)
    if (detail.value) {
      detail.value.assets.forEach(a => {
        if (a.category === asset?.category && a.variantId === asset?.variantId) {
          a.defaultAsset = a.id === id
        }
      })
      if (
        asset?.defaultAsset !== false &&
        asset?.category === PACKAGING_ASSET_CATEGORY_KEYS.mainImage &&
        !asset?.variantId
      ) {
        detail.value.model.defaultImage = asset.fileName
      }
    }
    onAssetChanged()
    notifyFn('已设为主图', 'success')
  }

  function editAssetTags(asset: PackagingAssetView) {
    tagEditor.assetId = asset.id
    tagEditor.tags = asset.tags || ''
    tagEditor.visible = true
  }

  async function saveAssetTags() {
    try {
      const asset = detail.value?.assets.find(item => item.id === tagEditor.assetId)
      const ownerType = fileOwnerTypeForPackagingAsset(asset)
      if (!ownerType) throw new Error('Packaging asset owner type is missing')
      await fileAssetActions.updateTags(ownerType, tagEditor.assetId, tagEditor.tags.trim())
      if (asset) {
        asset.tags = tagEditor.tags.trim()
      }
      tagEditor.visible = false
      notifyFn('标签已更新', 'success')
    } catch (error) {
      notifyFn.errorWithDetail(FILE_ASSET_FAILURE_PREFIX.updateTags, error)
    }
  }

  async function removeAsset(id: number) {
    const ok = await askConfirm({ title: '删除素材', message: '确定删除该素材？', danger: true })
    if (!ok) return
    const asset = detail.value?.assets.find(item => item.id === id)
    const ownerType = fileOwnerTypeForPackagingAsset(asset)
    if (!ownerType) throw new Error('Packaging asset owner type is missing')
    await fileAssetActions.remove(ownerType, id)
    if (detail.value) {
      const index = detail.value.assets.findIndex(a => a.id === id)
      if (index >= 0) {
        detail.value.assets.splice(index, 1)
      }
      if (
        asset?.category === PACKAGING_ASSET_CATEGORY_KEYS.mainImage &&
        !asset?.variantId &&
        detail.value.model.defaultImage === asset.fileName
      ) {
        const remainingMainImage = detail.value.assets.find(
          a => a.category === PACKAGING_ASSET_CATEGORY_KEYS.mainImage && !a.variantId
        )
        detail.value.model.defaultImage = remainingMainImage?.fileName || ''
      }
    }
    onAssetChanged()
    notifyFn('素材已删除', 'success')
  }

  function handleAssetDragStart(asset: PackagingAssetView, event: DragEvent) {
    draggingAssetId.value = asset.id
    event.dataTransfer?.setData('text/plain', String(asset.id))
    if (event.dataTransfer) event.dataTransfer.effectAllowed = 'move'
  }

  function handleAssetDragEnd() {
    draggingAssetId.value = null
  }

  async function handleAssetDrop(targetAsset: PackagingAssetView) {
    const sourceId = draggingAssetId.value
    draggingAssetId.value = null
    if (!sourceId || sourceId === targetAsset.id) return
    const assets = filteredAssets.value.slice()
    const sourceIndex = assets.findIndex(asset => asset.id === sourceId)
    const targetIndex = assets.findIndex(asset => asset.id === targetAsset.id)
    if (sourceIndex < 0 || targetIndex < 0) return
    const [source] = assets.splice(sourceIndex, 1)
    assets.splice(targetIndex, 0, source)
    const orderItems = assets.map((asset, index) => ({ id: asset.id, sortOrder: index + 1 }))
    const ownerTypes = packagingFileOwnerTypeEntries(assets)
    if (ownerTypes.length === 1) {
      await fileAssetActions.reorder(ownerTypes[0], orderItems)
    } else {
      await reorderPackagingAssets(orderItems)
    }
    if (detail.value) {
      orderItems.forEach(item => {
        const asset = detail.value!.assets.find(a => a.id === item.id)
        if (asset) {
          asset.sortOrder = item.sortOrder
        }
      })
    }
    onAssetChanged()
  }

  return {
    tagEditor,
    draggingAssetId,
    makeDefault,
    editAssetTags,
    saveAssetTags,
    removeAsset,
    handleAssetDragStart,
    handleAssetDragEnd,
    handleAssetDrop
  }
}
