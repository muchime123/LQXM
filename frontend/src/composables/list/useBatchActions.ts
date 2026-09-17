import { ref } from 'vue'
import { batchDeleteModels, batchUpdateModels } from '../../api/catalog'
import { notify } from '@/composables/ui/useToast'

interface ConfirmOptions {
  title?: string
  message?: string
  confirmText?: string
  danger?: boolean
}

interface BatchResult {
  affected: number
  [key: string]: unknown
}

type ConfirmFn = (options: ConfirmOptions) => Promise<boolean>

export function useBatchActions() {
  const globalLoading = ref(false)
  const globalLoadingText = ref('')

  function setGlobalLoading(loading: boolean, text = ''): void {
    globalLoading.value = loading
    globalLoadingText.value = text
  }

  async function batchDelete(modelIds: number[], confirmFn: ConfirmFn): Promise<BatchResult | null> {
    if (
      !(await confirmFn({
        title: '批量删除产品？',
        message: `确定删除已选的 ${modelIds.length} 个产品？会删除其下素材记录（不删除本地文件）。`,
        confirmText: '删除',
        danger: true
      }))
    )
      return null

    try {
      const result = await batchDeleteModels(modelIds)
      notify(`已删除 ${result.affected} 个产品`)
      return result
    } catch (err) {
      notify.errorWithDetail('批量删除失败', err)
      throw err
    }
  }

  async function batchUpdate(modelIds: number[], updates: Record<string, unknown>): Promise<BatchResult> {
    try {
      const result = await batchUpdateModels(modelIds, updates)
      notify(`已更新 ${result.affected} 个产品`)
      return result
    } catch (err) {
      notify.errorWithDetail('批量更新失败', err)
      throw err
    }
  }

  return {
    globalLoading,
    globalLoadingText,
    setGlobalLoading,
    batchDelete,
    batchUpdate
  }
}
