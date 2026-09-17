import { computed, ref, type Ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { displayModelTag } from '../../utils/catalog/productDisplay'

export interface UseCatalogDisplayModeOptions {
  selectedId: Ref<number | null>
  detail: Ref<any>
  models: Ref<any[]>
  activeTab: Ref<string>
  selectModel: (id: number, options?: any) => Promise<void>
}

export function useCatalogDisplayMode(options: UseCatalogDisplayModeOptions) {
  const { detail, models, selectModel } = options
  const route = useRoute()
  const router = useRouter()

  const CATALOG_DISPLAY_MODE_KEY = 'product-archive.catalog-display-mode'

  const getInitialDisplayMode = () => {
    const urlView = route.query.view
    if (urlView === 'workbench' || urlView === 'browse') return urlView as string
    try {
      return localStorage.getItem(CATALOG_DISPLAY_MODE_KEY) === 'workbench' ? 'workbench' : 'browse'
    } catch {
      return 'browse'
    }
  }

  const catalogDisplayMode = ref(getInitialDisplayMode())
  const isCompactViewport = ref(false)
  const effectiveCatalogDisplayMode = computed(() => (isCompactViewport.value ? 'browse' : catalogDisplayMode.value))

  const detailDrawerVisible = ref(false)
  const resumeDetailAfterModelEdit = ref(false)

  function syncViewportMode() {
    isCompactViewport.value = window.innerWidth < 980
  }

  async function setCatalogDisplayMode(mode: string | number | undefined) {
    if (mode === undefined || isCompactViewport.value) return
    const next = String(mode)
    if (next === catalogDisplayMode.value) return
    catalogDisplayMode.value = next
    try {
      localStorage.setItem(CATALOG_DISPLAY_MODE_KEY, next)
    } catch {
      /* ignore */
    }
    router.replace({ query: { ...route.query, view: next } })
    if (next === 'browse') {
      detailDrawerVisible.value = false
      return
    }
    if (!detail.value && models.value.length) {
      await selectModel(models.value[0].id, { nextTab: 'assets' })
    }
  }

  function closeDetailDrawer() {
    detailDrawerVisible.value = false
  }

  async function openProductDrawer(id: number | string) {
    await selectModel(Number(id))
    if (effectiveCatalogDisplayMode.value === 'browse') detailDrawerVisible.value = true
  }

  const detailDrawerSubtitleParts = computed(() => {
    if (!detail.value) return []
    const parts: string[] = []
    if (detail.value.model.category) parts.push(detail.value.model.category)
    return parts
  })

  const detailDrawerSubtitle = computed(() => detailDrawerSubtitleParts.value.join(' · '))

  const showDrawerModelTag = computed(() => {
    const tag = String(displayModelTag(detail?.value?.model) || '').trim()
    const name = String(detail?.value?.model?.name || '').trim()
    return Boolean(tag) && !name.toLocaleLowerCase().startsWith(tag.toLocaleLowerCase())
  })

  return {
    catalogDisplayMode,
    isCompactViewport,
    effectiveCatalogDisplayMode,
    detailDrawerVisible,
    resumeDetailAfterModelEdit,
    syncViewportMode,
    setCatalogDisplayMode,
    closeDetailDrawer,
    openProductDrawer,
    detailDrawerSubtitleParts,
    detailDrawerSubtitle,
    showDrawerModelTag
  }
}
