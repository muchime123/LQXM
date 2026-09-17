import { type ComputedRef, type Ref, computed, ref } from 'vue'
import type { PackagingAssetView, PackagingModelDetail } from '@/api/packaging'
import { filmFormLabel, kindLabel, shapeLabel, PACKAGING_ASSET_CATEGORY_KEYS } from '@/constants/packaging'
import type { UploadOverrideOptions, UploadWorkflowResult } from '@/composables/upload/useFileUploadWorkflow'

/**
 * 包材系列面板
 *
 * 抽取自 PackagingDetailDrawer.vue，负责「系列详情」视图的状态与逻辑：
 * 系列主图、容量范围、系列资料字段、主图上传与拖拽。
 */
export interface SeriesPanelUploadHelpers {
  uploadTarget: Ref<{ category: string; variantId?: number | null; makeDefault?: boolean } | null>
  pickFiles: (options: UploadOverrideOptions) => Promise<UploadWorkflowResult[] | Array<{ file: File }>>
  uploadFiles: (files: FileList | File[] | null, options: UploadOverrideOptions) => Promise<unknown>
}

export interface SeriesPanelHelpers {
  isFilmModel: ComputedRef<boolean>
  packagingAssetPreviewUrl: (asset: Partial<PackagingAssetView> | null | undefined) => string
  upload: SeriesPanelUploadHelpers
}

export function useSeriesPanel(detail: Ref<PackagingModelDetail | null>, helpers: SeriesPanelHelpers) {
  const { isFilmModel, packagingAssetPreviewUrl, upload } = helpers

  const capacityRange = computed(() => {
    const values = (detail.value?.variants || [])
      .map(item => Number(String(item.capacityMl || '').replace(/[^\d.]/g, '')))
      .filter(value => Number.isFinite(value) && value > 0)
    if (!values.length) return '-'
    return `${Math.min(...values)}~${Math.max(...values)}ml`
  })

  const mainImage = computed(() => {
    const assets =
      detail.value?.assets.filter(
        asset => asset.category === PACKAGING_ASSET_CATEGORY_KEYS.mainImage && !asset.variantId
      ) || []
    return (
      assets.find(asset => asset.defaultAsset)?.fileName ||
      assets[0]?.fileName ||
      detail.value?.model.defaultImage ||
      ''
    )
  })

  const seriesFields = computed(() => {
    const model = detail.value?.model
    if (!model) return []
    const commonFields = [
      { label: '英文名称', value: model.nameEn },
      { label: '包材类型', value: kindLabel(model.packagingKind) },
      { label: '材质', value: model.material },
      { label: isFilmModel.value ? '工艺/风格' : '颜色/风格', value: model.colorStyle },
      { label: '起订量', value: model.moq },
      { label: '参考单价', value: model.unitPrice },
      { label: '交期', value: model.leadTime },
      { label: '支持定制', value: model.customizable ? '是' : '否' },
      { label: '标签', value: model.tags }
    ]
    if (isFilmModel.value) {
      return [
        commonFields[0],
        commonFields[1],
        { label: '膜形态', value: filmFormLabel(model.filmForm) },
        ...commonFields.slice(2)
      ]
    }
    return [
      commonFields[0],
      commonFields[1],
      { label: '形状', value: shapeLabel(model.shapeType) },
      ...commonFields.slice(2, 4),
      { label: '容量范围', value: model.capacityMl || capacityRange.value },
      ...commonFields.slice(4)
    ]
  })

  function seriesMainImageUrl() {
    if (!mainImage.value) return ''
    const asset = detail.value?.assets.find(
      item =>
        item.category === PACKAGING_ASSET_CATEGORY_KEYS.mainImage &&
        !item.variantId &&
        item.fileName === mainImage.value
    )
    return packagingAssetPreviewUrl(
      asset || { category: PACKAGING_ASSET_CATEGORY_KEYS.mainImage, fileName: mainImage.value }
    )
  }

  const seriesImageDragging = ref(false)

  async function uploadSeriesImage() {
    upload.uploadTarget.value = {
      category: PACKAGING_ASSET_CATEGORY_KEYS.mainImage,
      variantId: null,
      makeDefault: true
    }
    const results = (await upload.pickFiles({
      category: PACKAGING_ASSET_CATEGORY_KEYS.mainImage,
      accept: 'image/*',
      multiple: false
    })) as {
      file: File
    }[]
    if (!results.length) upload.uploadTarget.value = null
  }

  async function handleSeriesImageDrop(event: DragEvent) {
    event.preventDefault()
    seriesImageDragging.value = false
    const files = Array.from(event.dataTransfer?.files || []).filter(f => f.type.startsWith('image/'))
    if (!files.length) return
    upload.uploadTarget.value = {
      category: PACKAGING_ASSET_CATEGORY_KEYS.mainImage,
      variantId: null,
      makeDefault: true
    }
    await upload.uploadFiles(files, { category: PACKAGING_ASSET_CATEGORY_KEYS.mainImage })
  }

  return {
    capacityRange,
    mainImage,
    seriesFields,
    seriesMainImageUrl,
    seriesImageDragging,
    uploadSeriesImage,
    handleSeriesImageDrop
  }
}
