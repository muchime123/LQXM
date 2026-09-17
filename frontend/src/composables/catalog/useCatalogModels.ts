import { ref } from 'vue'
import { fetchCatalogModels, fetchCatalogModel } from '../../api/catalog'
import { notify } from '@/composables/ui/useToast'
import { useArchiveHealthCache } from '@/composables/catalog/useArchiveHealth'
import { ASSET_CATEGORY_KEYS } from '../../constants/fields'
import type { CatalogDetail } from '../../types/catalog'

interface Model {
  id: number
  healthScore?: number
  [key: string]: unknown
}

interface PageInfo {
  page: number
  size: number
  pages: number
  total: number
}

interface LoadModelsOptions {
  isPageChange?: boolean
}

export function useCatalogModels() {
  const models = ref<Model[]>([])
  const detail = ref<CatalogDetail | null>(null)
  const selectedId = ref<number | null>(null)
  const selectedVariant = ref<{ id: number; [key: string]: unknown } | null>(null)
  const loading = ref(false)
  const selectingModel = ref(false)

  let loadModelsRequestId = 0
  let selectModelRequestId = 0

  const { cacheModelHealth, getCachedHealth } = useArchiveHealthCache()

  async function loadModels(
    getQueryParams: () => Record<string, unknown>,
    getSortParams: () => { sort: string; dir: string },
    page: PageInfo,
    pageNum?: number,
    options: LoadModelsOptions = {}
  ): Promise<void> {
    const { isPageChange = false } = options
    loading.value = true
    const requestId = ++loadModelsRequestId

    const p = pageNum ?? page.page
    const params: Record<string, unknown> = { ...getQueryParams(), page: p, size: page.size }
    if (!params.keyword) delete params.keyword

    const sortParams = getSortParams()
    if (sortParams.sort) params.sort = sortParams.sort + ',' + sortParams.dir

    try {
      const data = await fetchCatalogModels(params)
      if (requestId !== loadModelsRequestId) return
      models.value = (data.content as Model[]) || []
      page.page = data.number ?? 0
      page.pages = data.totalPages ?? 0
      page.total = data.totalElements ?? 0
      const uncachedModels = models.value.filter(m => m.healthScore == null)
      if (uncachedModels.length > 0) cacheModelHealth(uncachedModels)
      if (!isPageChange) {
        if (selectedId.value && !models.value.some(m => m.id === selectedId.value)) {
          selectedId.value = null
          detail.value = null
          selectedVariant.value = null
        }
      }
    } catch (err) {
      if (requestId !== loadModelsRequestId) return
      notify.errorWithDetail('加载产品列表失败', err)
    } finally {
      if (requestId === loadModelsRequestId) loading.value = false
    }
  }

  interface SelectModelOptions {
    preserveTab?: boolean
    nextTab?: string
  }

  async function selectModel(id: number, options: SelectModelOptions = {}): Promise<void> {
    const { preserveTab = false } = options
    const requestId = ++selectModelRequestId
    const previousVariantId = selectedVariant.value?.id

    selectingModel.value = true

    try {
      const data = await fetchCatalogModel(id)
      if (requestId !== selectModelRequestId) return
      detail.value = data
      selectedId.value = id
      const detailAssets = detail.value?.assets || []
      cacheModelHealth([
        {
          ...(data.model || {}),
          variantCount: data.variantCount ?? 0,
          assetCount: data.assetCount ?? 0,
          skuImageCount: detailAssets.filter((asset: { category: string; fileName?: string }) => asset.category === ASSET_CATEGORY_KEYS.skuImage && asset.fileName).length,
          mainImageCount: detailAssets.filter(
            (asset: { category: string; fileName?: string }) =>
              asset.category === ASSET_CATEGORY_KEYS.mainImage && asset.fileName
          ).length
        } as Model
      ])
      if (!preserveTab) {
        const variants = detail.value?.variants || []
        if (variants.length > 0 && previousVariantId) {
          selectedVariant.value = variants.find((v: { id: number }) => v.id === previousVariantId) || variants[0]
        } else {
          selectedVariant.value = variants[0] || null
        }
      }
    } catch (err) {
      if (requestId !== selectModelRequestId) return
      notify.errorWithDetail('加载产品详情失败', err)
    } finally {
      if (requestId === selectModelRequestId) selectingModel.value = false
    }
  }

  function refreshCurrentModel(preferredTab?: string): Promise<void> | undefined {
    if (!selectedId.value) return
    return selectModel(selectedId.value, { preserveTab: true, nextTab: preferredTab })
  }

  return {
    models,
    detail,
    selectedId,
    selectedVariant,
    loading,
    selectingModel,
    loadModels,
    selectModel,
    refreshCurrentModel,
    cacheModelHealth,
    getCachedHealth
  }
}
