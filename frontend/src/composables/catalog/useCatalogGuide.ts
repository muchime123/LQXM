import { type Ref } from 'vue'
import { ASSET_CATEGORY_KEYS } from '../../constants/fields'

export interface UseCatalogGuideOptions {
  activeTab: Ref<string>
  showNewProductGuide: Ref<boolean>
  openAssetTask: (category: string) => void
  openModelEditor: () => void
}

export function useCatalogGuide(options: UseCatalogGuideOptions) {
  const { activeTab, showNewProductGuide, openAssetTask, openModelEditor } = options

  function handleArchiveTask(key: string) {
    if (key === 'mainImage') {
      openAssetTask(ASSET_CATEGORY_KEYS.mainImage)
      return
    }
    if (key === 'baseInfo') {
      openModelEditor()
      return
    }
    if (key === 'newVariant' || key === 'variants') {
      openModelEditor()
      return
    }
    if (key === 'skuImages' || key === 'assets') {
      openAssetTask('all')
      return
    }
    if (key === 'manual') {
      openModelEditor()
      return
    }
  }

  function runHealthAction(key: string) {
    handleArchiveTask(key)
  }

  function handleGuideAction(action: string) {
    showNewProductGuide.value = false
    if (action === 'edit') {
      openModelEditor()
    } else if (action === 'assets') {
      activeTab.value = 'assets'
    } else if (action === 'manual') {
      openModelEditor()
    }
  }

  return {
    handleArchiveTask,
    runHealthAction,
    handleGuideAction
  }
}
